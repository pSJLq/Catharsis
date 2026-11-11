package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint128;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SkyRealm on Somnia Shannon (50312): bazaar settlement, auction escrow, arena runs with
 * Somnia VRF loot, relic pool, and stat checkpoints. Every write is signed by the player's own purse.
 */
final class RealmChain {
    static final String DEFAULT_REALM = "0xEE46A54C65561Ec4F1589Db5a612c30b8fEcC59d";
    static final BigInteger ARENA_FEE = new BigInteger("5000000000000000");
    static final BigInteger MIN_LISTING = new BigInteger("1000000000000000");
    private static final String EXPLORER = "https://explorer.testnet.somnia.network";

    private final SomniaSkyblockPlugin plugin;

    RealmChain(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    boolean enabled() {
        return plugin.wallets().realmEnabled();
    }

    String address() {
        return plugin.wallets().realmContract();
    }

    // ---------- writes ----------

    void send(UUID owner, String label, Function function, BigInteger value,
              Consumer<TransactionReceipt> ok, Consumer<String> fail) {
        send(owner, label, address(), function, value, ok, fail);
    }

    boolean binEnabled() {
        return enabled() && !plugin.wallets().binContract().isBlank();
    }

    /** A write to the SkyBin (Buy-It-Now) contract. */
    void sendBin(UUID owner, String label, Function function, BigInteger value,
                 Consumer<TransactionReceipt> ok, Consumer<String> fail) {
        if (!binEnabled()) {
            fail.accept("SkyBin is not configured.");
            return;
        }
        send(owner, label, plugin.wallets().binContract(), function, value, ok, fail);
    }

    void send(UUID owner, String label, String contract, Function function, BigInteger value,
              Consumer<TransactionReceipt> ok, Consumer<String> fail) {
        Profile profile = plugin.profiles().get(owner);
        Player online = plugin.getServer().getPlayer(owner);
        if (!enabled()) {
            fail.accept("SkyRealm is not configured.");
            return;
        }
        if (online != null) {
            online.sendMessage(Component.text("⛓ " + label + " — signing from your purse"
                    + (value.signum() > 0 ? " (" + stt(value) + " STT)" : "") + "…", NamedTextColor.DARK_AQUA));
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                TransactionReceipt receipt = plugin.wallets().sendTo(profile, contract, function, value);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player now = plugin.getServer().getPlayer(owner);
                    if (now != null) {
                        now.sendMessage(txLine("⛓ " + label + " confirmed on Somnia", receipt.getTransactionHash()));
                    }
                    ok.accept(receipt);
                });
            } catch (Exception exception) {
                String reason = reason(exception);
                plugin.getLogger().warning("Realm " + label + " failed for " + owner + ": " + reason);
                plugin.getServer().getScheduler().runTask(plugin, () -> fail.accept(reason));
            }
        });
    }

    /** Pulls outbid refunds (SkyRealm2 credits them instead of pushing, so no bidder can block an auction). */
    void claimRefund(UUID owner) {
        Profile profile = plugin.profiles().get(owner);
        if (!enabled() || profile.wallet == null || profile.wallet.isBlank() || !plugin.wallets().canSign(profile)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BigInteger due = plugin.wallets().pendingRefund(profile.wallet);
                if (due.signum() <= 0) {
                    return;
                }
                TransactionReceipt receipt = plugin.wallets().sendTo(profile, address(), fn("withdrawPending"), BigInteger.ZERO);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player now = plugin.getServer().getPlayer(owner);
                    if (now != null) {
                        now.sendMessage(txLine("[AH] Refunded " + stt(due) + " STT from outbid auctions.", receipt.getTransactionHash()));
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().info("Refund claim skipped for " + owner + ": " + reason(exception));
            }
        });
    }

    void view(Function function, Consumer<List<Type>> ok, Consumer<String> fail) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<Type> out = plugin.wallets().viewRealm(function);
                plugin.getServer().getScheduler().runTask(plugin, () -> ok.accept(out));
            } catch (Exception exception) {
                String reason = reason(exception);
                plugin.getServer().getScheduler().runTask(plugin, () -> fail.accept(reason));
            }
        });
    }

    // ---------- functions ----------

    static Function checkpoint(BigInteger packed, int milestone) {
        return fn("checkpoint", new Uint256(packed), new Uint16(milestone));
    }

    static Function fillOffer(long offerId, String seller, int item, int qty) {
        return fn("fillOffer", new Uint256(offerId), new Address(seller), new Uint32(item), new Uint32(qty));
    }

    static Function placeBuyOrder(int item, int qty, BigInteger unit) {
        return fn("placeBuyOrder", new Uint32(item), new Uint32(qty), new Uint128(unit));
    }

    static Function fillBuyOrder(long id, int qty) {
        return fn("fillBuyOrder", new Uint256(id), new Uint32(qty));
    }

    static Function cancelBuyOrder(long id) {
        return fn("cancelBuyOrder", new Uint256(id));
    }

    static Function createAuction(int item, int qty, BigInteger start, int seconds) {
        return fn("createAuction", new Uint32(item), new Uint32(qty), new Uint128(start), new Uint32(seconds));
    }

    // ---------- SkyBin (Buy-It-Now) ----------

    static Function binList(int item, int qty, BigInteger price, int seconds) {
        return fn("list", new Uint32(item), new Uint32(qty), new Uint128(price), new Uint32(seconds));
    }

    static Function binBuy(long id) {
        return fn("buy", new Uint256(id));
    }

    static Function binCancel(long id) {
        return fn("cancel", new Uint256(id));
    }

    /** SkyBin and SkyRealm both charge 1 % of the price (min 0.001 STT) to list. */
    static BigInteger binFee(BigInteger price) {
        return listingFee(price);
    }

    static Function bid(long id) {
        return fn("bid", new Uint256(id));
    }

    static Function settle(long id) {
        return fn("settle", new Uint256(id));
    }

    static Function enterArena() {
        return fn("enterArena");
    }

    static Function finishRun(BigInteger id, int wave, int kills) {
        return fn("finishRun", new Uint256(id), new Uint16(wave), new Uint32(kills));
    }

    static Function cashIn(long n) {
        return fn("cashIn", new Uint256(n));
    }

    static Function relicsOf(String wallet) {
        return new Function("relics", List.of(new Address(wallet)), List.of(new TypeReference<Uint256>() { }));
    }

    static Function uintView(String name) {
        return new Function(name, Collections.emptyList(), List.of(new TypeReference<Uint256>() { }));
    }

    static Function run(BigInteger id) {
        return new Function("runs", List.of(new Uint256(id)), List.of(
                new TypeReference<Address>() { },
                new TypeReference<Uint16>() { },
                new TypeReference<Uint32>() { },
                new TypeReference<Uint8>() { },
                new TypeReference<Uint8>() { },
                new TypeReference<Uint16>() { }
        ));
    }

    static Function auction(long id) {
        return new Function("auctions", List.of(new Uint256(id)), List.of(
                new TypeReference<Address>() { },
                new TypeReference<Uint64>() { },
                new TypeReference<Uint32>() { },
                new TypeReference<Uint32>() { },
                new TypeReference<Address>() { },
                new TypeReference<Uint128>() { },
                new TypeReference<Uint128>() { },
                new TypeReference<Bool>() { }
        ));
    }

    private static Function fn(String name, Type... args) {
        return new Function(name, Arrays.<Type>asList(args), Collections.emptyList());
    }

    // ---------- helpers ----------

    /** Reads an indexed uint from the first log matching the event signature. */
    static BigInteger indexed(TransactionReceipt receipt, String signature, int topic) {
        String wanted = EventEncoder.buildEventSignature(signature).toLowerCase(Locale.ROOT);
        for (Log log : receipt.getLogs()) {
            List<String> topics = log.getTopics();
            if (topics.size() > topic && topics.get(0).toLowerCase(Locale.ROOT).equals(wanted)) {
                return Numeric.toBigInt(topics.get(topic));
            }
        }
        return null;
    }

    static BigInteger wei(String stt) {
        BigDecimal value = new BigDecimal(stt.trim());
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Price must be above zero.");
        }
        BigDecimal scaled = value.movePointRight(18);
        if (scaled.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Too many decimals.");
        }
        return scaled.toBigIntegerExact();
    }

    static String stt(BigInteger wei) {
        if (wei == null) {
            return "0";
        }
        return new BigDecimal(wei).movePointLeft(18).stripTrailingZeros().toPlainString();
    }

    static BigInteger listingFee(BigInteger start) {
        BigInteger fee = start.multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(10000));
        return fee.compareTo(MIN_LISTING) < 0 ? MIN_LISTING : fee;
    }

    static BigInteger minBid(BigInteger start, BigInteger bid) {
        if (bid == null || bid.signum() == 0) {
            return start;
        }
        return bid.multiply(BigInteger.valueOf(105)).divide(BigInteger.valueOf(100)).add(BigInteger.ONE);
    }

    Component txLine(String text, String hash) {
        String url = EXPLORER + "/tx/" + hash;
        return Component.text(text + " ", NamedTextColor.DARK_AQUA)
                .append(Component.text("[tx " + hash.substring(0, Math.min(10, hash.length())) + "…]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text(url))));
    }

    Component contractLine() {
        String url = EXPLORER + "/address/" + address();
        return Component.text("SkyRealm " + WalletService.shortAddress(address()) + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text("[explorer]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)));
    }

    static String reason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        message = message.replace("execution reverted: ", "").replace("execution reverted", "reverted");
        return message.length() > 140 ? message.substring(0, 140) + "…" : message;
    }

    @SuppressWarnings("unused")
    private static Event unused() {
        return null;
    }
}
