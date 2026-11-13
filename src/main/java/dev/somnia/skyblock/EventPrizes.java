package dev.somnia.skyblock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Launch event prizes, claimed in game (/event claim or the Claim button in /event) after the admin finalized the
 * reviewed winners on the website (site/event.js writes plugins/SomniaSkyblock/event/winners.json).
 * The SOMI goes on Somnia mainnet from the prize wallet to the winner's own purse (same address as on Shannon); from
 * there the player can move it out. The prize wallet's private key lives only in secrets.yml ({@code event-prize-key});
 * without it a claim only says that payouts open after the review. One payout is in flight at a time; every claim is
 * stored in event/claims.json before it is broadcast (signed bytes included), so a restart never pays twice: the
 * minute scan finishes, re-broadcasts the same bytes, or frees the claim once its nonce was used by something else.
 * The website reads event/claims-public.json (state and tx hash only).
 */
final class EventPrizes implements CommandExecutor, TabCompleter, Listener {
    static final String SECRET_KEY = "event-prize-key";
    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger FEE_FLOOR = BigInteger.valueOf(6L).multiply(GWEI);

    private final SomniaSkyblockPlugin plugin;
    private final Web3j main;
    private final long chainId;
    private final File dir;
    private final File claimsFile;
    private final File publicFile;
    private final File secretsFile;
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    private final ReentrantLock payLock = new ReentrantLock();
    private volatile boolean recovering;

    record Winner(int rank, UUID uuid, String name, BigInteger wei, String somi) {
    }

    record Winners(String event, List<Winner> list) {
        Winner of(UUID id) {
            for (Winner w : list) {
                if (w.uuid().equals(id)) {
                    return w;
                }
            }
            return null;
        }
    }

    EventPrizes(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        String rpc = plugin.getConfig().getString("cookie.mainnet-rpc", "https://api.infra.mainnet.somnia.network/");
        this.main = Web3j.build(new HttpService(rpc));
        this.chainId = plugin.getConfig().getLong("cookie.mainnet-chain-id", 5031L);
        this.dir = new File(plugin.getDataFolder(), "event");
        dir.mkdirs();
        this.claimsFile = new File(dir, "claims.json");
        this.publicFile = new File(dir, "claims-public.json");
        this.secretsFile = new File(plugin.getDataFolder(), "secrets.yml");
    }

    void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::recover, 300L, 1200L);
        Credentials c = credentials(false);
        plugin.getLogger().info("Event prizes: " + (c == null ? "no prize wallet configured (claims show 'Prize payouts open after review')"
                : "prize wallet " + c.getAddress() + " on Somnia mainnet " + chainId));
    }

    // ------------------------------------------------------------------ files

    /** The admin-approved winners, or null before the website finalized them. */
    Winners winners() {
        File f = new File(dir, "winners.json");
        if (!f.exists()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            String event = root.has("event") ? root.get("event").getAsString() : "";
            if (event.isBlank() || !root.has("winners")) {
                return null;
            }
            BigDecimal cap = maxPrize();
            List<Winner> list = new ArrayList<>();
            for (var el : root.getAsJsonArray("winners")) {
                JsonObject o = el.getAsJsonObject();
                BigDecimal somi = new BigDecimal(o.get("prize").getAsString().trim());
                if (somi.signum() <= 0 || somi.compareTo(cap) > 0) {
                    plugin.getLogger().warning("Event prizes: winners.json prize " + somi + " SOMI is outside 0.." + cap + "; skipped.");
                    continue;
                }
                list.add(new Winner(o.get("rank").getAsInt(), UUID.fromString(o.get("uuid").getAsString()),
                        o.has("name") ? o.get("name").getAsString() : "?",
                        Convert.toWei(somi, Convert.Unit.ETHER).toBigIntegerExact(), somi.stripTrailingZeros().toPlainString()));
            }
            return new Winners(event, list);
        } catch (Exception e) {
            plugin.getLogger().warning("Event prizes: could not read winners.json (" + e.getMessage() + ").");
            return null;
        }
    }

    private BigDecimal maxPrize() {
        try {
            return new BigDecimal(plugin.getConfig().getString("event.max-prize-somi", "100").trim());
        } catch (Exception e) {
            return new BigDecimal("100");
        }
    }

    private synchronized JsonObject claims() {
        try {
            if (claimsFile.exists()) {
                JsonObject root = JsonParser.parseString(Files.readString(claimsFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!root.has("claims")) {
                    root.add("claims", new JsonObject());
                }
                return root;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Event prizes: could not read claims.json (" + e.getMessage() + ").");
            throw new IllegalStateException("claims.json unreadable");
        }
        JsonObject root = new JsonObject();
        root.add("claims", new JsonObject());
        return root;
    }

    private synchronized JsonObject claim(String key) {
        JsonObject c = claims().getAsJsonObject("claims");
        return c.has(key) ? c.getAsJsonObject(key) : null;
    }

    /** Merges {@code fields} into one claim and writes both files. */
    private synchronized void put(String key, JsonObject fields) {
        JsonObject root = claims();
        JsonObject all = root.getAsJsonObject("claims");
        JsonObject c = all.has(key) ? all.getAsJsonObject(key) : new JsonObject();
        if (!c.has("first")) {
            c.addProperty("first", System.currentTimeMillis());
        }
        for (var e : fields.entrySet()) {
            c.add(e.getKey(), e.getValue());
        }
        c.addProperty("at", System.currentTimeMillis());
        all.add(key, c);
        write(claimsFile, root.toString());
        JsonObject pub = new JsonObject();
        JsonObject list = new JsonObject();
        for (var e : all.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            JsonObject p = new JsonObject();
            String state = o.has("state") ? o.get("state").getAsString() : "";
            p.addProperty("event", o.has("event") ? o.get("event").getAsString() : "");
            p.addProperty("uuid", o.has("uuid") ? o.get("uuid").getAsString() : "");
            p.addProperty("state", state.equals("paid") ? "paid" : state.equals("signed") || state.equals("sent") ? "pending" : "open");
            if (state.equals("paid") && o.has("hash")) {
                p.addProperty("tx", o.get("hash").getAsString());
            }
            p.addProperty("at", o.get("at").getAsLong());
            list.add(e.getKey(), p);
        }
        pub.add("claims", list);
        write(publicFile, pub.toString());
    }

    private static JsonObject fields(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                o.addProperty((String) kv[i], n);
            } else if (v instanceof Boolean b) {
                o.addProperty((String) kv[i], b);
            } else {
                o.addProperty((String) kv[i], v.toString());
            }
        }
        return o;
    }

    private void write(File target, String body) {
        try {
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            Files.writeString(tmp.toPath(), body, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(target.toPath(), body, StandardCharsets.UTF_8);
            } catch (Exception again) {
                plugin.getLogger().warning("Event prizes: could not write " + target.getName() + ": " + again.getMessage());
            }
        }
    }

    static String key(String event, UUID id) {
        return event + ":" + id;
    }

    /** The prize wallet from secrets.yml, or null (never logged or shown). */
    private Credentials credentials(boolean quiet) {
        String raw = YamlConfiguration.loadConfiguration(secretsFile).getString(SECRET_KEY, "");
        if (raw == null || !raw.trim().matches("(?i)(0x)?[0-9a-f]{64}")) {
            return null;
        }
        Credentials c = Credentials.create(raw.trim());
        String expected = plugin.getConfig().getString("event.prize-wallet", "");
        if (expected != null && !expected.isBlank() && !expected.trim().equalsIgnoreCase(c.getAddress())) {
            if (!quiet) {
                plugin.getLogger().warning("Event prizes: secrets.yml " + SECRET_KEY + " does not belong to event.prize-wallet " + expected.trim() + "; payouts stay closed.");
            }
            return null;
        }
        return c;
    }

    // ------------------------------------------------------------------ /event

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("claim")) {
            claim(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            player.sendMessage(Component.text(statusLine(player), NamedTextColor.YELLOW));
            return true;
        }
        open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("claim", "status") : List.of();
    }

    private String statusLine(Player player) {
        Winners w = winners();
        if (w == null) {
            return EventTally.eventRunning() ? "The launch event is running. Prizes: 1st 100 SOMI, 2nd 75 SOMI, 3rd 25 SOMI."
                    : "No event prizes to claim right now. Winners can claim after the event ends and the review is done.";
        }
        Winner me = w.of(player.getUniqueId());
        if (me == null) {
            return "The results are final. You have no event prize to claim.";
        }
        JsonObject c = claim(key(w.event(), me.uuid()));
        String state = c == null || !c.has("state") ? "" : c.get("state").getAsString();
        if (state.equals("paid")) {
            return "You placed #" + me.rank() + " and claimed your " + me.somi() + " SOMI prize.";
        }
        if (state.equals("signed") || state.equals("sent")) {
            return "You placed #" + me.rank() + ". Your " + me.somi() + " SOMI prize payout is on its way.";
        }
        return "You placed #" + me.rank() + "! Claim your " + me.somi() + " SOMI prize with /event claim.";
    }

    void open(Player player) {
        Market.View view = new Market.View(27, "Launch Event");
        ItemStack pane = Market.icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 27; i++) {
            view.getInventory().setItem(i, pane);
        }
        List<Component> info = new ArrayList<>();
        info.add(SbMenu.plain("Gain the most SkyBlock XP while", NamedTextColor.GRAY));
        info.add(SbMenu.plain("the event runs.", NamedTextColor.GRAY));
        info.add(Component.empty());
        info.add(SbMenu.plain("Prizes:", NamedTextColor.GRAY));
        info.add(SbMenu.plain(" 1st  100 SOMI", NamedTextColor.GOLD));
        info.add(SbMenu.plain(" 2nd  75 SOMI", NamedTextColor.WHITE));
        info.add(SbMenu.plain(" 3rd  25 SOMI", NamedTextColor.GOLD));
        info.add(Component.empty());
        info.add(SbMenu.plain(EventTally.eventRunning() ? "The event is running now." : "The event is not running now.",
                EventTally.eventRunning() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        info.add(SbMenu.plain("Standings are on the website Event page.", NamedTextColor.GRAY));
        view.set(11, SbMenu.icon(Material.NETHER_STAR, SbMenu.plain("Launch Event", NamedTextColor.GOLD), info), null);

        List<Component> board = new ArrayList<>();
        board.add(SbMenu.plain("The in-game leaderboard is", NamedTextColor.GRAY));
        board.add(SbMenu.plain("coming SOON.", NamedTextColor.GRAY));
        view.set(13, SbMenu.icon(Material.PAPER, SbMenu.plain("Leaderboard", NamedTextColor.YELLOW), board), null);

        List<Component> lore = new ArrayList<>(SbMenu.wrap(statusLine(player), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(SbMenu.plain("Paid in SOMI on Somnia mainnet", NamedTextColor.DARK_GRAY));
        lore.add(SbMenu.plain("to your own purse.", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(SbMenu.plain("Click to claim!", NamedTextColor.YELLOW));
        view.set(15, SbMenu.icon(Material.GOLD_BLOCK, SbMenu.plain("Claim Prize", NamedTextColor.GREEN), lore), c -> {
            player.closeInventory();
            claim(player);
        });
        view.set(22, Market.icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }

    // ------------------------------------------------------------------ claim

    void claim(Player player) {
        UUID id = player.getUniqueId();
        Winners w = winners();
        if (w == null) {
            player.sendMessage(Component.text("Prizes are not final yet. Winners can claim after the event ends and the review is done.", NamedTextColor.YELLOW));
            return;
        }
        Winner me = w.of(id);
        if (me == null) {
            player.sendMessage(Component.text("You have no event prize to claim.", NamedTextColor.GRAY));
            return;
        }
        String key = key(w.event(), id);
        JsonObject c;
        try {
            c = claim(key);
        } catch (RuntimeException e) {
            player.sendMessage(Component.text("Prize claims are paused for a moment. Try again later.", NamedTextColor.RED));
            return;
        }
        String state = c == null || !c.has("state") ? "" : c.get("state").getAsString();
        if (state.equals("paid")) {
            player.sendMessage(Component.text("You already claimed your " + me.somi() + " SOMI prize.", NamedTextColor.GREEN));
            if (c.has("hash")) {
                tellTx(player, c.get("hash").getAsString());
            }
            return;
        }
        if (state.equals("signed") || state.equals("sent")) {
            player.sendMessage(Component.text("Your " + me.somi() + " SOMI prize payout is on its way. It lands in your purse on Somnia mainnet.", NamedTextColor.YELLOW));
            return;
        }
        if (credentials(true) == null) {
            player.sendMessage(Component.text("Prize payouts open after review.", NamedTextColor.YELLOW));
            return;
        }
        Profile profile = plugin.profiles().get(id);
        String wallet = profile.wallet;
        if (wallet == null || !wallet.trim().matches("(?i)0x[0-9a-f]{40}")) {
            player.sendMessage(Component.text("Your purse is not set up. Log in first.", NamedTextColor.RED));
            return;
        }
        if (!claiming.add(id)) {
            player.sendMessage(Component.text("Your claim is already being processed.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Sending your " + me.somi() + " SOMI prize on Somnia mainnet...", NamedTextColor.GRAY));
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String reply;
            NamedTextColor color = NamedTextColor.RED;
            String paidHash = null;
            try {
                if (!payLock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    reply = "Another prize payout is in flight. Try again in a minute.";
                } else {
                    try {
                        String[] out = pay(w.event(), me, wallet.trim(), name);
                        if (out[0].equals("paid")) {
                            reply = "Your " + me.somi() + " SOMI prize landed in your purse on Somnia mainnet. Congratulations!";
                            color = NamedTextColor.GREEN;
                            paidHash = out[1];
                        } else {
                            reply = out[1];
                            color = out[0].equals("pending") ? NamedTextColor.YELLOW : NamedTextColor.RED;
                        }
                    } finally {
                        payLock.unlock();
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Event prize claim for " + name + " (" + wallet + ") failed: " + e.getMessage());
                reply = "The prize payout did not go through. Try again in a minute.";
            }
            String text = reply;
            NamedTextColor col = color;
            String hash = paidHash;
            Bukkit.getScheduler().runTask(plugin, () -> {
                claiming.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    online.sendMessage(Component.text(text, col));
                    if (hash != null) {
                        tellTx(online, hash);
                        online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                    }
                }
            });
        });
    }

    /** Holds payLock. Returns {state, text-or-hash}: paid/hash, pending/text, failed/text. */
    private String[] pay(String event, Winner me, String wallet, String name) throws Exception {
        String key = key(event, me.uuid());
        JsonObject c = claim(key);
        String state = c == null || !c.has("state") ? "" : c.get("state").getAsString();
        if (state.equals("paid")) {
            return new String[]{"failed", "You already claimed this prize."};
        }
        if (state.equals("signed") || state.equals("sent")) {
            return new String[]{"pending", "Your prize payout is on its way."};
        }
        if (unresolved()) {
            return new String[]{"failed", "Another prize payout is in flight. Try again in a minute."};
        }
        Credentials prize = credentials(false);
        if (prize == null) {
            return new String[]{"failed", "Prize payouts open after review."};
        }
        String from = prize.getAddress();
        BigInteger price = main.ethGasPrice().send().getGasPrice();
        if (price == null || price.compareTo(FEE_FLOOR) < 0) {
            price = FEE_FLOOR;
        }
        BigInteger gas = BigInteger.valueOf(21_000L);
        try {
            var est = main.ethEstimateGas(org.web3j.protocol.core.methods.request.Transaction.createEtherTransaction(from, null, null, null, wallet, me.wei())).send();
            if (!est.hasError() && est.getAmountUsed() != null && est.getAmountUsed().signum() > 0) {
                gas = est.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN).max(gas);
            }
        } catch (Exception ignored) {
            // a plain transfer to a purse: 21000
        }
        BigInteger balance = main.ethGetBalance(from, DefaultBlockParameterName.LATEST).send().getBalance();
        if (balance == null || balance.compareTo(me.wei().add(gas.multiply(price))) < 0) {
            plugin.getLogger().warning("Event prizes: prize wallet " + from + " holds " + Cookies.fmt(balance == null ? BigInteger.ZERO : balance)
                    + " SOMI, not enough for " + me.somi() + " SOMI to " + name + ".");
            return new String[]{"failed", "Prize payouts are not funded yet. Try again later."};
        }
        BigInteger nonce = main.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        RawTransaction tx = RawTransaction.createEtherTransaction(nonce, price, gas, wallet, me.wei());
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, prize);
        String raw = Numeric.toHexString(signed);
        String hash = Numeric.toHexString(Hash.sha3(signed));
        put(key, fields("event", event, "uuid", me.uuid().toString(), "name", name, "rank", me.rank(), "to", wallet,
                "from", from, "value", me.wei().toString(), "nonce", nonce.toString(), "hash", hash, "raw", raw, "state", "signed"));
        plugin.getLogger().info("Event prize: " + name + " (#" + me.rank() + ") claims " + me.somi() + " SOMI to " + wallet + ", tx " + hash);
        EthSendTransaction sent;
        try {
            sent = main.ethSendRawTransaction(raw).send();
        } catch (java.io.IOException io) {
            // maybe broadcast: the minute scan settles it from the chain (and re-sends these exact bytes)
            return new String[]{"pending", "Your prize payout is on its way. It lands in your purse on Somnia mainnet shortly."};
        }
        if (sent.hasError()) {
            String msg = String.valueOf(sent.getError().getMessage()).toLowerCase(Locale.ROOT);
            if (!msg.contains("already known") && !msg.contains("known transaction")) {
                put(key, fields("state", "failed", "note", msg.length() > 140 ? msg.substring(0, 140) : msg));
                plugin.getLogger().warning("Event prize tx " + hash + " rejected: " + msg);
                return new String[]{"failed", "The prize payout was rejected by the network. Try again in a minute."};
            }
        }
        put(key, fields("state", "sent"));
        for (int i = 0; i < 120; i++) {
            TransactionReceipt r = main.ethGetTransactionReceipt(hash).send().getTransactionReceipt().orElse(null);
            if (r != null) {
                return settle(key, r, false) ? new String[]{"paid", hash}
                        : new String[]{"failed", "The prize payout did not go through. Try again in a minute."};
            }
            Thread.sleep(500L);
        }
        return new String[]{"pending", "Your prize payout is on its way. It lands in your purse on Somnia mainnet shortly."};
    }

    private boolean unresolved() {
        JsonObject all = claims().getAsJsonObject("claims");
        for (var e : all.entrySet()) {
            String s = e.getValue().getAsJsonObject().has("state") ? e.getValue().getAsJsonObject().get("state").getAsString() : "";
            if (s.equals("signed") || s.equals("sent")) {
                return true;
            }
        }
        return false;
    }

    /** A mined prize tx: paid when status 1 and it matches the record; otherwise the claim opens again. */
    private boolean settle(String key, TransactionReceipt receipt, boolean recovered) throws Exception {
        JsonObject c = claim(key);
        if (c == null) {
            return false;
        }
        String hash = c.get("hash").getAsString();
        if (!receipt.isStatusOK()) {
            put(key, fields("state", "reverted"));
            plugin.getLogger().warning("Event prize tx " + hash + " reverted; the claim is open again.");
            return false;
        }
        var tx = main.ethGetTransactionByHash(hash).send().getTransaction().orElse(null);
        boolean ok = tx != null && tx.getTo() != null && tx.getTo().equalsIgnoreCase(c.get("to").getAsString())
                && tx.getFrom() != null && tx.getFrom().equalsIgnoreCase(c.get("from").getAsString())
                && tx.getValue() != null && tx.getValue().compareTo(new BigInteger(c.get("value").getAsString())) >= 0;
        if (!ok) {
            put(key, fields("state", "invalid", "note", "mined tx does not match the claim"));
            plugin.getLogger().severe("Event prize tx " + hash + " does not match its claim record; check it by hand before paying again.");
            return false;
        }
        put(key, fields("state", "paid", "notify", recovered ? Boolean.TRUE : null));
        plugin.getLogger().info("Event prize paid: " + (c.has("name") ? c.get("name").getAsString() : "?") + " " + Cookies.fmt(new BigInteger(c.get("value").getAsString()))
                + " SOMI to " + c.get("to").getAsString() + " tx " + hash + (recovered ? " (settled by the minute scan)" : ""));
        if (recovered) {
            UUID id = UUID.fromString(c.get("uuid").getAsString());
            Bukkit.getScheduler().runTask(plugin, () -> notifyPaid(id));
        }
        return true;
    }

    /** Every minute (async): settles claims left in signed/sent. Never signs anything new. */
    void recover() {
        if (recovering) {
            return;
        }
        recovering = true;
        try {
            if (!payLock.tryLock()) {
                return;
            }
            try {
                JsonObject all = claims().getAsJsonObject("claims");
                for (var e : all.entrySet()) {
                    JsonObject c = e.getValue().getAsJsonObject();
                    String s = c.has("state") ? c.get("state").getAsString() : "";
                    if (!s.equals("signed") && !s.equals("sent")) {
                        continue;
                    }
                    String hash = c.get("hash").getAsString();
                    try {
                        TransactionReceipt r = main.ethGetTransactionReceipt(hash).send().getTransactionReceipt().orElse(null);
                        if (r != null) {
                            settle(e.getKey(), r, true);
                            continue;
                        }
                        BigInteger used = main.ethGetTransactionCount(c.get("from").getAsString(), DefaultBlockParameterName.LATEST).send().getTransactionCount();
                        BigInteger nonce = new BigInteger(c.get("nonce").getAsString());
                        if (used.compareTo(nonce) > 0) {
                            // the nonce went to another transaction and ours has no receipt: it can never land now
                            put(e.getKey(), fields("state", "dropped"));
                            plugin.getLogger().warning("Event prize tx " + hash + " was replaced (nonce " + nonce + " used); the claim is open again.");
                            continue;
                        }
                        EthSendTransaction again = main.ethSendRawTransaction(c.get("raw").getAsString()).send();
                        if (again.hasError()) {
                            plugin.getLogger().info("Event prize tx " + hash + " re-broadcast: " + again.getError().getMessage());
                        }
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Event prize check for " + hash + " failed: " + ex.getMessage());
                    }
                }
            } finally {
                payLock.unlock();
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Event prize scan failed: " + e.getMessage());
        } finally {
            recovering = false;
        }
    }

    private void tellTx(Player player, String hash) {
        player.sendMessage(Component.text("Mainnet tx: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(hash, NamedTextColor.GRAY).clickEvent(ClickEvent.openUrl("https://explorer.somnia.network/tx/" + hash))
                        .hoverEvent(HoverEvent.showText(Component.text("Open in the Somnia explorer", NamedTextColor.YELLOW)))));
    }

    /** Main thread: tells a winner whose payout the minute scan settled; offline winners hear it on their next join. */
    private void notifyPaid(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String[]> told = new ArrayList<>();
            synchronized (this) {
                JsonObject root;
                try {
                    root = claims();
                } catch (RuntimeException e) {
                    return;
                }
                JsonObject all = root.getAsJsonObject("claims");
                for (var e : all.entrySet()) {
                    JsonObject c = e.getValue().getAsJsonObject();
                    if (c.has("notify") && c.get("notify").getAsBoolean() && id.toString().equals(c.get("uuid").getAsString())) {
                        c.remove("notify");
                        told.add(new String[]{Cookies.fmt(new BigInteger(c.get("value").getAsString())), c.get("hash").getAsString()});
                    }
                }
                if (told.isEmpty()) {
                    return;
                }
                write(claimsFile, root.toString());
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    return;
                }
                for (String[] t : told) {
                    p.sendMessage(Component.text("Your " + t[0] + " SOMI event prize landed in your purse on Somnia mainnet. Congratulations!", NamedTextColor.GREEN));
                    tellTx(p, t[1]);
                }
            });
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> notifyPaid(id), 120L);
    }
}
