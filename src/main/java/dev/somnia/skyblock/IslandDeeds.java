package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint128;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Island deeds as ERC-721 tokens (SkyIsland4) and the Island Market.
 *
 * A deed stands for one island slot in the skyblock world. deeds.yml remembers which slot (origin, spawn, guide
 * spot, portal, layout) belongs to which deed. The chain decides who owns a deed and which island a wallet plays
 * on (memberIsland). The server follows SkyIsland4 logs (Transfer, IslandActive, sale events) every few seconds
 * and checks each player again when they log in:
 *   - a wallet whose active deed has a known slot that is not the profile's slot moves there (the new owner gets
 *     the island at login, or right away when online);
 *   - a profile still standing on a slot whose deed now belongs to another wallet gets a fresh starter island,
 *     and ChainClient mints a fresh solo deed for it from the player's purse.
 * Co-op members (not owners) follow the owner's island as before; a deed with members cannot move onchain.
 * Island Market (Auction House): BIN and bid auctions settled by SkyIsland4 itself; players pay their own gas.
 */
final class IslandDeeds implements Listener {
    record Sale(long id, String seller, long endMs, int kind, BigInteger price, BigInteger bid, String bidder) {
        boolean bin() {
            return kind == 1;
        }

        BigInteger shown() {
            return bid.signum() > 0 ? bid : price;
        }
    }

    record Slot(int x, int z, double[] spawn, double[] npc, String portal, int layout) {
        boolean same(Profile p) {
            return p.islandX == x && p.islandZ == z;
        }

        static Slot of(Profile p) {
            return new Slot(p.islandX, p.islandZ, p.islandSpawn == null ? null : p.islandSpawn.clone(),
                    p.islandNpc == null ? null : p.islandNpc.clone(), p.islandPortalDest, p.layout);
        }
    }

    /** What the chain says about one wallet, read off the main thread. */
    private record Seen(BigInteger active, String activeHolder, Long standing, String standingHolder) {
    }

    private static final String ZERO = "0x0000000000000000000000000000000000000000";
    private static final String T_TRANSFER = Hash.sha3String("Transfer(address,address,uint256)");
    private static final String T_ACTIVE = Hash.sha3String("IslandActive(address,uint256)");
    private static final String T_LISTED = Hash.sha3String("IslandListed(uint256,address,uint8,uint256,uint64,uint256)");
    private static final String T_BID = Hash.sha3String("IslandBid(uint256,address,uint256,uint64)");
    private static final String T_SOLD = Hash.sha3String("IslandSold(uint256,address,address,uint256,uint256)");
    private static final String T_CLOSED = Hash.sha3String("IslandSaleClosed(uint256,address)");
    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int[] DURATIONS = {60, 360, 720, 1440, 2880};

    /** Per-player Sell Island screen state. */
    private static final class Draft {
        long deed;
        boolean bin = true;
        BigInteger price = new BigInteger("100000000000000000"); // 0.1 STT
        int minutes = 1440;
    }

    private final SomniaSkyblockPlugin plugin;
    private final File file;
    private final Map<Long, Slot> slots = new ConcurrentHashMap<>();
    private final Map<Long, String> holders = new ConcurrentHashMap<>();
    private final Map<Long, Sale> sales = new ConcurrentHashMap<>();
    private final Map<String, UUID> byWallet = new ConcurrentHashMap<>();
    private final Set<Long> settling = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> settleRetry = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> checking = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private volatile BigInteger lastBlock;
    private volatile boolean ready;
    private volatile boolean polling;

    IslandDeeds(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "deeds.yml");
        load();
    }

    boolean enabled() {
        return plugin.chain() != null && plugin.chain().enabled();
    }

    /** Startup: index every profile's wallet, read all deeds and sales, then follow the logs. */
    void start() {
        if (!enabled()) {
            return;
        }
        List<UUID> ids = new ArrayList<>();
        plugin.profiles().eachReady(p -> {
            if (p.wallet != null && !p.wallet.isBlank()) {
                byWallet.put(p.wallet.toLowerCase(Locale.ROOT), p.uuid);
                ids.add(p.uuid);
            }
        });
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                BigInteger head = plugin.wallets().blockNumber();
                long next = plugin.wallets().islandNextId();
                for (long id = 1; id < next; id++) {
                    String holder = plugin.wallets().islandOwner(BigInteger.valueOf(id));
                    if (holder == null) {
                        holders.remove(id);
                    } else {
                        holders.put(id, holder.toLowerCase(Locale.ROOT));
                    }
                    Sale sale = plugin.wallets().islandSale(id);
                    if (sale == null) {
                        sales.remove(id);
                    } else {
                        sales.put(id, sale);
                    }
                }
                if (lastBlock == null || lastBlock.compareTo(head) > 0 || head.subtract(lastBlock).compareTo(BigInteger.valueOf(20_000)) > 0) {
                    lastBlock = head; // the full read above covers anything older
                }
                ready = true;
                plugin.getLogger().info("Island deeds: " + holders.size() + " deed(s) on SkyIsland4, " + sales.size() + " listed, " + slots.size() + " slot(s) known.");
                // Seed slots for every known profile (and fix profiles whose island was sold while they were away).
                for (UUID id : ids) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> check(id, false));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Island deeds: startup read failed (" + RealmChain.reason(e) + "); retrying with the log follower.");
                ready = true;
            }
        }, 140L);
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 200L, 100L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::settleTick, 300L, 200L);
    }

    // ------------------------------------------------------------------ chain follower

    private void poll() {
        if (!ready || polling) {
            return;
        }
        polling = true;
        try {
            BigInteger head = plugin.wallets().blockNumber();
            if (lastBlock == null) {
                lastBlock = head;
                return;
            }
            Set<String> touched = new java.util.HashSet<>();
            List<Runnable> notes = new ArrayList<>();
            int rounds = 0;
            while (lastBlock.compareTo(head) < 0 && rounds++ < 8) {
                BigInteger from = lastBlock.add(BigInteger.ONE);
                BigInteger to = from.add(BigInteger.valueOf(799)).min(head);
                for (Log log : plugin.wallets().islandLogs(from, to)) {
                    ingest(log, touched, notes);
                }
                lastBlock = to;
            }
            if (!touched.isEmpty() || !notes.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    notes.forEach(Runnable::run);
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        Profile p = plugin.profiles().get(online.getUniqueId());
                        if (p.wallet != null && touched.contains(p.wallet.toLowerCase(Locale.ROOT))) {
                            check(online.getUniqueId(), true);
                        }
                    }
                    save();
                });
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Island deeds poll: " + RealmChain.reason(e));
        } finally {
            polling = false;
        }
    }

    private void ingest(Log log, Set<String> touched, List<Runnable> notes) throws IOException {
        List<String> t = log.getTopics();
        if (t == null || t.isEmpty()) {
            return;
        }
        String topic = t.get(0).toLowerCase(Locale.ROOT);
        if (topic.equals(T_TRANSFER) && t.size() == 4) {
            String from = addr(t.get(1));
            String to = addr(t.get(2));
            long id = num(t.get(3));
            if (to.equals(ZERO)) {
                holders.remove(id);
            } else {
                holders.put(id, to);
            }
            touched.add(from);
            touched.add(to);
            if (!from.equals(ZERO) && !to.equals(ZERO)) {
                plugin.getLogger().info("Island deed #" + id + " moved " + from + " -> " + to + " (tx " + log.getTransactionHash() + ").");
            }
        } else if (topic.equals(T_ACTIVE) && t.size() == 3) {
            touched.add(addr(t.get(1)));
        } else if ((topic.equals(T_LISTED) || topic.equals(T_BID) || topic.equals(T_CLOSED)) && t.size() >= 2) {
            long id = num(t.get(1));
            Sale sale = plugin.wallets().islandSale(id);
            Sale before = sales.get(id);
            if (sale == null) {
                sales.remove(id);
            } else {
                sales.put(id, sale);
            }
            if (topic.equals(T_LISTED) && sale != null) {
                String text = "[Island Market] " + nameOf(sale.seller()) + " listed island #" + id + (sale.bin() ? " for " : " from ") + RealmChain.stt(sale.price()) + " STT" + (sale.bin() ? " (BIN)." : " (auction).") + " /ah";
                notes.add(() -> plugin.getServer().broadcast(Component.text(text, NamedTextColor.LIGHT_PURPLE)));
            } else if (topic.equals(T_BID) && sale != null && before != null && before.bidder() != null && !before.bidder().equalsIgnoreCase(sale.bidder())) {
                String outbid = before.bidder();
                notes.add(() -> tellWallet(outbid, "[Island Market] You were outbid on island #" + id + ". Your STT comes back to your purse.", NamedTextColor.YELLOW));
            }
        } else if (topic.equals(T_SOLD) && t.size() == 4) {
            long id = num(t.get(1));
            String buyer = addr(t.get(2));
            String seller = addr(t.get(3));
            Sale before = sales.remove(id);
            String price = before == null ? "" : " for " + RealmChain.stt(before.shown()) + " STT";
            notes.add(() -> {
                tellWallet(seller, "[Island Market] Island #" + id + " sold to " + nameOf(buyer) + price + " (2% tax).", NamedTextColor.GOLD);
                tellWallet(buyer, "[Island Market] You bought island #" + id + price + ".", NamedTextColor.GREEN);
            });
        }
    }

    private static String addr(String topic) {
        String hex = topic.length() >= 40 ? topic.substring(topic.length() - 40) : topic;
        return ("0x" + hex).toLowerCase(Locale.ROOT);
    }

    private static long num(String topic) {
        return new BigInteger(topic.startsWith("0x") ? topic.substring(2) : topic, 16).longValueExact();
    }

    // ------------------------------------------------------------------ reconcile a profile with the chain

    @EventHandler
    public void onJoin(SbLoginEvent event) {
        Player player = event.getPlayer();
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (p.wallet != null && !p.wallet.isBlank()) {
            byWallet.put(p.wallet.toLowerCase(Locale.ROOT), p.uuid);
        }
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> check(id, true), 60L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> claimRefund(id), 260L);
    }

    /** After ChainClient minted a deed for this profile: remember its slot. */
    void minted(UUID id) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> check(id, plugin.getServer().getPlayer(id) != null), 40L);
    }

    /** Reads the chain for one profile off the main thread, then applies the result on the main thread. */
    void check(UUID id, boolean online) {
        if (!enabled() || !checking.add(id)) {
            return;
        }
        Profile p = plugin.profiles().get(id);
        String wallet = p.wallet == null ? "" : p.wallet.trim();
        if (wallet.isEmpty() || !p.islandReady || (plugin.coops() != null && plugin.coops().isGuestMember(id))) {
            checking.remove(id);
            return;
        }
        Long standing = deedAt(p);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BigInteger active = plugin.wallets().memberIsland(wallet);
                String activeHolder = active.signum() > 0 ? plugin.wallets().islandOwner(active) : null;
                String standingHolder = standing == null ? null : plugin.wallets().islandOwner(BigInteger.valueOf(standing));
                Seen seen = new Seen(active, activeHolder, standing, standingHolder);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    checking.remove(id);
                    apply(id, wallet, seen, online);
                });
            } catch (Exception e) {
                checking.remove(id);
                plugin.getLogger().info("Island deeds: check of " + wallet + " skipped (" + RealmChain.reason(e) + ").");
            }
        });
    }

    private void apply(UUID id, String wallet, Seen seen, boolean online) {
        Profile p = plugin.profiles().get(id);
        if (!wallet.equalsIgnoreCase(p.wallet) || !p.islandReady || (plugin.coops() != null && plugin.coops().isGuestMember(id))) {
            return;
        }
        Long standing = deedAt(p);
        if (!java.util.Objects.equals(standing, seen.standing())) {
            return; // the profile moved meanwhile; the next check sees the new state
        }
        boolean ownsActive = seen.active().signum() > 0 && wallet.equalsIgnoreCase(seen.activeHolder());
        boolean standingLost = standing != null && seen.standingHolder() != null && !wallet.equalsIgnoreCase(seen.standingHolder())
                && !formerWallet(p, seen.standingHolder());
        boolean standingFree = standing == null || seen.standingHolder() == null; // no deed, or a burned one
        Player player = online ? plugin.getServer().getPlayer(id) : null;
        if (ownsActive) {
            long active = seen.active().longValueExact();
            Slot target = slots.get(active);
            if (target == null) {
                if (standingLost) {
                    fresh(p, player, standing, seen.standingHolder());
                    record(active, p);
                } else if (standingFree) {
                    record(active, p);
                }
                return;
            }
            if (target.same(p)) {
                return;
            }
            if (standingFree && standing == null && player != null) {
                // The island the player is on has no deed: moving would leave it behind, so ask first.
                tell(player, "You play on island deed #" + active + " now. Open the Auction House ➜ Island Market ➜ Your Deeds to move there.", NamedTextColor.YELLOW);
                return;
            }
            if (standingFree && standing == null) {
                return;
            }
            move(p, player, active, target);
            return;
        }
        if (seen.active().signum() == 0 && standingLost) {
            fresh(p, player, standing, seen.standingHolder());
        }
    }

    /** The profile takes over the slot of a deed it owns and plays on. */
    private void move(Profile p, Player player, long deed, Slot slot) {
        Long left = deedAt(p);
        p.islandX = slot.x();
        p.islandZ = slot.z();
        p.islandSpawn = slot.spawn() == null ? null : slot.spawn().clone();
        p.islandNpc = slot.npc() == null ? null : slot.npc().clone();
        p.islandPortalDest = slot.portal();
        p.layout = slot.layout();
        p.islandReady = true;
        plugin.profiles().save(p);
        plugin.getLogger().info("Island deeds: " + p.uuid + " (" + p.wallet + ") now plays on deed #" + deed + " at " + slot.x() + "," + slot.z()
                + (left == null ? "" : " (left deed #" + left + ")") + ".");
        if (player != null) {
            arrive(player, p);
            tell(player, "You now play on island #" + deed + "." + (left == null ? "" : " Island #" + left + " stays yours as a held deed (Your Deeds)."), NamedTextColor.GREEN);
        }
    }

    /** The profile's island deed belongs to another wallet now: a fresh starter island and a fresh solo deed. */
    private void fresh(Profile p, Player player, long lost, String holder) {
        plugin.getLogger().info("Island deeds: deed #" + lost + " of " + p.uuid + " (" + p.wallet + ") belongs to " + holder + "; fresh island.");
        p.islandReady = false;
        plugin.islands().ensureIsland(p);
        plugin.profiles().save(p);
        if (player != null) {
            arrive(player, p);
            tell(player, "Island #" + lost + " now belongs to " + nameOf(holder) + ". You got a fresh island; your purse mints its deed.", NamedTextColor.YELLOW);
        }
        plugin.chain().ensureIsland(p);
    }

    /** The purse this profile had before an admin unregister (its deed is not "sold"). */
    private static boolean formerWallet(Profile p, String holder) {
        return p.walletOrphaned != null && holder != null && p.walletOrphaned.toLowerCase(Locale.ROOT).endsWith("|" + holder.toLowerCase(Locale.ROOT));
    }

    private void arrive(Player player, Profile p) {
        player.setRespawnLocation(plugin.islands().home(p), true);
        if (player.getWorld().equals(plugin.islands().world())) {
            plugin.islands().teleport(player, p);
        }
    }

    private void record(long deed, Profile p) {
        Slot slot = Slot.of(p);
        slots.entrySet().removeIf(e -> e.getKey() != deed && e.getValue().x() == slot.x() && e.getValue().z() == slot.z());
        Slot before = slots.put(deed, slot);
        if (before == null || !before.same(p)) {
            plugin.getLogger().info("Island deeds: deed #" + deed + " is the island at " + slot.x() + "," + slot.z() + " (" + p.wallet + ").");
        }
        save();
    }

    /** The owner rebuilt or reset the island (spawn and guide spots may change): keep the slot record current. */
    void refreshSlot(Profile p) {
        Long deed = deedAt(p);
        if (deed != null && p.wallet != null && p.wallet.equalsIgnoreCase(holders.get(deed))) {
            slots.put(deed, Slot.of(p));
            save();
        }
    }

    private Long deedAt(Profile p) {
        for (Map.Entry<Long, Slot> e : slots.entrySet()) {
            if (e.getValue().same(p)) {
                return e.getKey();
            }
        }
        return null;
    }

    Sale saleOf(long deed) {
        return sales.get(deed);
    }

    /**
     * Before the other join handlers place the player: a profile whose island deed (from the log follower) now
     * belongs to another wallet gets its fresh island right away, so it never logs in onto the sold island.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoinEarly(SbLoginEvent event) {
        if (!enabled() || !ready) {
            return;
        }
        Profile p = plugin.profiles().get(event.getPlayer().getUniqueId());
        if (p.wallet == null || p.wallet.isBlank() || !p.islandReady || (plugin.coops() != null && plugin.coops().isGuestMember(p.uuid))) {
            return;
        }
        Long standing = deedAt(p);
        String holder = standing == null ? null : holders.get(standing);
        if (holder != null && !holder.equalsIgnoreCase(p.wallet) && !formerWallet(p, holder)) {
            Player player = event.getPlayer();
            fresh(p, null, standing, holder);
            player.sendMessage(Component.text("Island #" + standing + " now belongs to " + nameOf(holder) + ". You got a fresh island; your purse mints its deed.", NamedTextColor.YELLOW));
            // after the other join handlers placed the player (maybe on the old island): go to the new one
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    arrive(player, p);
                }
            }, 5L);
        }
    }

    /** The deed a profile plays on as its owner (by slot), when it is listed. */
    Sale listedActive(Profile p) {
        Long deed = deedAt(p);
        return deed == null ? null : sales.get(deed);
    }

    // ------------------------------------------------------------------ settlement and refunds

    private void settleTick() {
        long now = System.currentTimeMillis();
        for (Sale sale : new ArrayList<>(sales.values())) {
            if (now < sale.endMs() + 3000 || settling.contains(sale.id()) || now < settleRetry.getOrDefault(sale.id(), 0L)) {
                continue;
            }
            UUID payer = signer(sale.seller());
            if (payer == null && sale.bidder() != null) {
                payer = signer(sale.bidder());
            }
            if (payer == null) {
                continue;
            }
            settling.add(sale.id());
            plugin.realm().send(payer, "Settle island #" + sale.id(), contract(), new Function("settleIsland",
                            List.of(new Uint256(BigInteger.valueOf(sale.id()))), Collections.emptyList()), BigInteger.ZERO,
                    receipt -> {
                        settling.remove(sale.id());
                        sales.remove(sale.id());
                        if (sale.bidder() == null) {
                            tellWallet(sale.seller(), "[Island Market] Island #" + sale.id() + " did not sell; it stays yours.", NamedTextColor.YELLOW);
                        }
                    },
                    reason -> {
                        settling.remove(sale.id());
                        settleRetry.put(sale.id(), System.currentTimeMillis() + 60_000L);
                    });
        }
    }

    private UUID signer(String wallet) {
        UUID id = byWallet.get(wallet.toLowerCase(Locale.ROOT));
        if (id == null || plugin.getServer().getPlayer(id) == null) {
            return null;
        }
        Profile p = plugin.profiles().get(id);
        return plugin.wallets().canSign(p) ? id : null;
    }

    private void claimRefund(UUID id) {
        Profile p = plugin.profiles().get(id);
        if (!enabled() || p.wallet == null || p.wallet.isBlank() || !plugin.wallets().canSign(p)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BigInteger due = plugin.wallets().islandPending(p.wallet);
                if (due.signum() <= 0) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.realm().send(id, "Island Market refund " + RealmChain.stt(due) + " STT", contract(),
                        new Function("withdrawPending", Collections.emptyList(), Collections.emptyList()), BigInteger.ZERO, r -> {
                        }, reason -> {
                        }));
            } catch (Exception e) {
                plugin.getLogger().info("Island refund check skipped for " + id + ": " + RealmChain.reason(e));
            }
        });
    }

    // ------------------------------------------------------------------ Island Market GUI

    private String contract() {
        return plugin.wallets().contract();
    }

    private List<Long> owned(Profile p) {
        List<Long> out = new ArrayList<>();
        if (p.wallet == null || p.wallet.isBlank()) {
            return out;
        }
        String w = p.wallet.toLowerCase(Locale.ROOT);
        for (Map.Entry<Long, String> e : holders.entrySet()) {
            if (e.getValue().equals(w)) {
                out.add(e.getKey());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static ItemStack icon(Material m, String name, NamedTextColor color, List<String> lore) {
        return Market.icon(m, name, color, lore);
    }

    private static Market.View view(int rows, String title) {
        Market.View view = new Market.View(rows * 9, title);
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < rows * 9; i++) {
            view.getInventory().setItem(i, filler);
        }
        return view;
    }

    private static String stt(BigInteger wei) {
        return RealmChain.stt(wei) + " STT";
    }

    private static String left(long endMs) {
        long s = Math.max(0, endMs - System.currentTimeMillis()) / 1000;
        if (s == 0) {
            return "ended";
        }
        return s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : (s / 60) + "m " + (s % 60) + "s";
    }

    String nameOf(String wallet) {
        if (wallet == null) {
            return "nobody";
        }
        UUID id = byWallet.get(wallet.toLowerCase(Locale.ROOT));
        return id == null ? WalletService.shortAddress(wallet) : plugin.market().name(id);
    }

    private boolean gate(Player player) {
        if (!enabled() || !ready) {
            tell(player, "The Island Market is still loading from the chain. Try again in a moment.", NamedTextColor.RED);
            return false;
        }
        return plugin.market().chainReady(player);
    }

    /** Auction House ➜ Island Market: every island listed for sale. */
    void market(Player player) {
        if (!gate(player)) {
            return;
        }
        Market.View v = view(6, "Island Market");
        List<Sale> open = new ArrayList<>(sales.values());
        open.sort(Comparator.comparingLong(Sale::endMs));
        int g = 0;
        for (Sale sale : open) {
            if (g >= GRID.length) {
                break;
            }
            v.set(GRID[g++], saleIcon(player, sale), c -> inspect(player, sale.id()));
        }
        if (g == 0) {
            v.set(22, icon(Material.BARRIER, "No islands for sale", NamedTextColor.RED, List.of("Sell yours from Your Deeds.")), null);
        }
        v.set(45, icon(Material.OAK_SIGN, "How it works", NamedTextColor.GOLD, List.of(
                "Island deeds are NFTs on SkyIsland4.",
                "Only an island without co-op members",
                "can be listed or sent.",
                "BIN or bids: 1% listing fee (min 0.001 STT)",
                "+ 2% tax when sold; half burned,",
                "half to the Relic Pool. Bids: +5% min",
                "raise, 30 s anti-snipe. You pay your gas.",
                "",
                "The buyer moves onto the island; the",
                "seller gets a fresh island and deed.")), null);
        v.set(48, icon(Material.ARROW, "Go Back", NamedTextColor.GREEN, List.of("To Auction House")), c -> plugin.marketGui().auctionHome(player));
        v.set(49, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        v.set(50, icon(Material.FILLED_MAP, "Your Deeds", NamedTextColor.GREEN, List.of("Your island deeds: " + owned(plugin.profiles().get(player.getUniqueId())).size(),
                "Sell one, or move to a held one.", "", "!Click to open!")), c -> deeds(player));
        player.openInventory(v.getInventory());
    }

    private ItemStack saleIcon(Player player, Sale sale) {
        List<String> lore = new ArrayList<>();
        lore.add("-------------------");
        lore.add("Seller: " + nameOf(sale.seller()));
        if (sale.bin()) {
            lore.add("Buy it now: $" + stt(sale.price()));
        } else {
            lore.add(sale.bid().signum() == 0 ? "Starting bid: $" + stt(sale.price()) : "Top bid: $" + stt(sale.bid()));
            if (sale.bidder() != null) {
                lore.add("Bidder: " + nameOf(sale.bidder()));
            }
        }
        lore.add("");
        lore.add("Ends in: " + left(sale.endMs()));
        lore.add("");
        Profile p = plugin.profiles().get(player.getUniqueId());
        lore.add(sale.seller().equalsIgnoreCase(p.wallet) ? "+This is your own listing!" : "!Click to inspect!");
        return icon(sale.bin() ? Material.GRASS_BLOCK : Material.MOSS_BLOCK, "Island #" + sale.id() + (sale.bin() ? " (BIN)" : " (Auction)"), NamedTextColor.GREEN, lore);
    }

    private void inspect(Player player, long deed) {
        Sale sale = sales.get(deed);
        if (sale == null) {
            tell(player, "Island #" + deed + " is not for sale any more.", NamedTextColor.RED);
            market(player);
            return;
        }
        Profile p = plugin.profiles().get(player.getUniqueId());
        boolean mine = sale.seller().equalsIgnoreCase(p.wallet);
        Market.View v = view(4, "Island #" + deed);
        v.set(13, saleIcon(player, sale), null);
        List<String> after = plugin.coops() != null && (plugin.coops().inCoop(p.uuid) || plugin.coops().isGuestMember(p.uuid))
                ? List.of("You are in a co-op: the deed is held", "(Your Deeds) until you leave the co-op.")
                : List.of("You move onto this island; the island", "you play on now stays yours as a held deed.");
        if (mine) {
            v.set(22, icon(Material.RED_TERRACOTTA, "Cancel Listing", NamedTextColor.RED, sale.bin() || sale.bid().signum() == 0
                    ? List.of("Takes the listing down.", "The listing fee is not refunded.", "", "!Click to sign on Somnia!")
                    : List.of("-An auction with bids cannot be cancelled.")), c -> cancel(player, deed));
        } else if (sale.bin()) {
            List<String> lore = new ArrayList<>(List.of("Cost: $" + stt(sale.price()), "+ gas, paid from your purse.", ""));
            lore.addAll(after);
            lore.addAll(List.of("", "!Click to sign on Somnia!"));
            v.set(20, icon(Material.LIME_TERRACOTTA, "Buy Island", NamedTextColor.GREEN, lore), c -> buy(player, deed));
        } else {
            BigInteger min = RealmChain.minBid(sale.price(), sale.bid());
            boolean top = p.wallet != null && p.wallet.equalsIgnoreCase(sale.bidder());
            List<String> lore = new ArrayList<>(top ? List.of("+You have the top bid.") : List.of("New bid: $" + stt(min), "Escrowed in SkyIsland4;", "refunded if you are outbid.", ""));
            if (!top) {
                lore.addAll(after);
                lore.addAll(List.of("", "!Click to bid!"));
            }
            v.set(20, icon(Material.GOLD_NUGGET, "Submit Bid", NamedTextColor.GOLD, lore), c -> {
                if (!top) {
                    bid(player, deed, min);
                }
            });
            v.set(24, icon(Material.OAK_SIGN, "Custom Bid", NamedTextColor.GREEN, List.of("Minimum: $" + stt(min), "", "!Click to type an amount!")),
                    c -> plugin.marketGui().ask(player, "Your bid for island #" + deed + " in STT (min " + RealmChain.stt(min) + ")?", text -> {
                        BigInteger amount = parse(player, text);
                        if (amount != null) {
                            bid(player, deed, amount);
                        }
                    }));
        }
        v.set(30, icon(Material.ARROW, "Go Back", NamedTextColor.GREEN, List.of("To Island Market")), c -> market(player));
        v.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(v.getInventory());
    }

    /** Island Market ➜ Your Deeds. */
    void deeds(Player player) {
        if (!gate(player)) {
            return;
        }
        Profile p = plugin.profiles().get(player.getUniqueId());
        Long here = deedAt(p);
        Market.View v = view(6, "Your Deeds");
        int g = 0;
        boolean guest = plugin.coops() != null && plugin.coops().isGuestMember(p.uuid);
        for (long deed : owned(p)) {
            if (g >= GRID.length) {
                break;
            }
            Sale sale = sales.get(deed);
            boolean playing = !guest && here != null && here == deed;
            List<String> lore = new ArrayList<>();
            lore.add(playing ? "+You play on this island." : slots.containsKey(deed) ? "Held deed." : "Held deed (no island placed yet).");
            if (sale != null) {
                lore.add("Listed: " + (sale.bin() ? "BIN $" + stt(sale.price()) : "auction, " + (sale.bid().signum() > 0 ? "top bid $" + stt(sale.bid()) : "from $" + stt(sale.price()))));
                lore.add("Ends in: " + left(sale.endMs()));
            }
            lore.add("");
            lore.add("!Click for options!");
            v.set(GRID[g++], icon(playing ? Material.GRASS_BLOCK : Material.MAP, "Island #" + deed, NamedTextColor.GREEN, lore), c -> deed(player, deed));
        }
        if (g == 0) {
            v.set(22, icon(Material.BARRIER, "No deeds", NamedTextColor.RED, List.of(guest ? "Co-op members play on the owner's deed." : "Your deed is minted when your purse has gas.")), null);
        }
        v.set(48, icon(Material.ARROW, "Go Back", NamedTextColor.GREEN, List.of("To Island Market")), c -> market(player));
        v.set(49, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(v.getInventory());
    }

    private void deed(Player player, long deed) {
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (p.wallet == null || !p.wallet.equalsIgnoreCase(holders.get(deed))) {
            deeds(player);
            return;
        }
        Sale sale = sales.get(deed);
        Long here = deedAt(p);
        boolean guest = plugin.coops() != null && plugin.coops().isGuestMember(p.uuid);
        boolean playing = !guest && here != null && here == deed;
        Market.View v = view(4, "Island #" + deed);
        v.set(4, icon(playing ? Material.GRASS_BLOCK : Material.MAP, "Island #" + deed, NamedTextColor.GREEN, List.of(playing ? "You play on this island." : "Held deed.")), null);
        if (sale != null) {
            v.set(13, saleIcon(player, sale), null);
            v.set(22, icon(Material.RED_TERRACOTTA, "Cancel Listing", NamedTextColor.RED, sale.bin() || sale.bid().signum() == 0
                    ? List.of("Takes the listing down.", "The listing fee is not refunded.", "", "!Click to sign on Somnia!")
                    : List.of("-An auction with bids cannot be cancelled.")), c -> cancel(player, deed));
        } else {
            v.set(11, icon(Material.GOLD_INGOT, "Sell This Island", NamedTextColor.GOLD, List.of("BIN or bids in the Island Market.", "", "!Click to set a price!")), c -> {
                Draft d = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
                d.deed = deed;
                sell(player);
            });
            if (!playing) {
                v.set(15, icon(Material.ENDER_PEARL, "Play On This Island", NamedTextColor.AQUA, List.of(
                        "Moves you onto island #" + deed + ".", "The island you play on now stays", "yours as a held deed.", "", "!Click to sign on Somnia!")), c -> use(player, deed));
            }
        }
        v.set(30, icon(Material.ARROW, "Go Back", NamedTextColor.GREEN, List.of("To Your Deeds")), c -> deeds(player));
        v.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(v.getInventory());
    }

    private void sell(Player player) {
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        Market.View v = view(6, d.bin ? "Sell Island #" + d.deed + " (BIN)" : "Sell Island #" + d.deed + " (Auction)");
        BigInteger fee = RealmChain.listingFee(d.price);
        v.set(13, icon(Material.GRASS_BLOCK, "Island #" + d.deed, NamedTextColor.GREEN, List.of("The whole island goes to the buyer:", "blocks, chests and what is in them.")), null);
        v.set(29, icon(Material.LIME_TERRACOTTA, d.bin ? "List for Buy-It-Now" : "Start Auction", NamedTextColor.GREEN, List.of(
                (d.bin ? "Price: " : "Starting bid: ") + stt(d.price), "Duration: " + duration(d.minutes), "",
                "Listing fee: $" + stt(fee) + " (half burned)", "Tax when sold: 2%", "",
                "While listed the island cannot take", "co-op members or be sent.", "", "!Click to sign on Somnia!")), c -> {
            player.closeInventory();
            drafts.remove(player.getUniqueId());
            list(player, d.deed, d.bin, d.price, d.minutes);
        });
        v.set(31, icon(Material.GOLD_INGOT, d.bin ? "Island Price" : "Starting Bid", NamedTextColor.GOLD, List.of(stt(d.price), "", "!Click to type a price!")),
                c -> plugin.marketGui().ask(player, (d.bin ? "BIN price" : "Starting bid") + " for island #" + d.deed + " in STT (e.g. 0.5)?", text -> {
                    BigInteger price = parse(player, text);
                    if (price != null) {
                        d.price = price;
                    }
                    sell(player);
                }));
        v.set(33, icon(Material.CLOCK, "Duration", NamedTextColor.YELLOW, List.of(duration(d.minutes), "", "!Click to cycle: 1h, 6h, 12h, 24h, 48h")), c -> {
            int i = 0;
            while (i < DURATIONS.length && DURATIONS[i] != d.minutes) {
                i++;
            }
            d.minutes = DURATIONS[(i + 1) % DURATIONS.length];
            sell(player);
        });
        v.set(48, icon(d.bin ? Material.GOLDEN_CARROT : Material.GOLD_BLOCK, d.bin ? "Switch to Auction" : "Switch to BIN", NamedTextColor.GREEN, List.of(
                d.bin ? "Let players bid; highest bid wins." : "Sell for a fixed Buy-It-Now price.", "", "!Click to switch!")), c -> {
            d.bin = !d.bin;
            sell(player);
        });
        v.set(45, icon(Material.ARROW, "Go Back", NamedTextColor.GREEN, List.of("To Your Deeds")), c -> deeds(player));
        v.set(49, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(v.getInventory());
    }

    private static String duration(int minutes) {
        return minutes >= 60 ? (minutes / 60) + " Hour" + (minutes >= 120 ? "s" : "") : minutes + " Minutes";
    }

    private BigInteger parse(Player player, String text) {
        try {
            BigInteger value = RealmChain.wei(text);
            if (value.bitLength() > 127) {
                throw new IllegalArgumentException("That price is too high.");
            }
            return value;
        } catch (RuntimeException e) {
            tell(player, "That is not a price: " + e.getMessage(), NamedTextColor.RED);
            return null;
        }
    }

    // ------------------------------------------------------------------ market writes (the player's own purse pays)

    private boolean start(Player player) {
        if (!gate(player)) {
            return false;
        }
        if (!busy.add(player.getUniqueId())) {
            tell(player, "Wait for your last island transaction to land.", NamedTextColor.GRAY);
            return false;
        }
        return true;
    }

    private void done(UUID id) {
        busy.remove(id);
    }

    private void failed(UUID id, String what, String reason) {
        done(id);
        String low = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        String text = low.startsWith("purse has") || low.contains("insufficient funds") || low.contains("/deposit")
                ? "Not enough STT for gas, use /deposit"
                : what + " did not go through: " + reason;
        Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            tell(online, text, NamedTextColor.RED);
        }
    }

    private void list(Player player, long deed, boolean bin, BigInteger price, int minutes) {
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (!p.wallet.equalsIgnoreCase(holders.get(deed))) {
            tell(player, "You do not own island #" + deed + ".", NamedTextColor.RED);
            return;
        }
        if (sales.containsKey(deed)) {
            tell(player, "Island #" + deed + " is already listed.", NamedTextColor.RED);
            return;
        }
        Long here = deedAt(p);
        if (here != null && here == deed) {
            if (plugin.coops() != null && plugin.coops().inCoop(p.uuid)) {
                tell(player, "Your island has co-op members. Only an island without members can be sold (/coopkick).", NamedTextColor.RED);
                return;
            }
            if (plugin.minions().countOn(p) > 0) {
                tell(player, "Pick up your placed minions first.", NamedTextColor.RED);
                return;
            }
        }
        if (!start(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        BigInteger fee = RealmChain.listingFee(price);
        Function fn = new Function("listIsland", List.of(new Uint256(BigInteger.valueOf(deed)), new Uint8(BigInteger.valueOf(bin ? 1 : 2)),
                new Uint128(price), new Uint32(BigInteger.valueOf(minutes * 60L))), Collections.emptyList());
        plugin.realm().send(id, "List island #" + deed + (bin ? " BIN " : " auction from ") + RealmChain.stt(price) + " STT (fee " + RealmChain.stt(fee) + " STT)",
                contract(), fn, fee, receipt -> {
                    done(id);
                    refreshSale(deed);
                    plugin.getLogger().info("Island Market: " + p.wallet + " listed deed #" + deed + (bin ? " BIN " : " auction ") + price + " wei " + receipt.getTransactionHash());
                }, reason -> failed(id, "Listing", reason));
    }

    private void buy(Player player, long deed) {
        Sale sale = sales.get(deed);
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (sale == null || !sale.bin() || System.currentTimeMillis() >= sale.endMs()) {
            tell(player, "That listing is not open any more.", NamedTextColor.RED);
            return;
        }
        if (sale.seller().equalsIgnoreCase(p.wallet)) {
            tell(player, "That is your own listing.", NamedTextColor.RED);
            return;
        }
        if (!start(player)) {
            return;
        }
        player.closeInventory();
        UUID id = player.getUniqueId();
        plugin.realm().send(id, "Buy island #" + deed + " for " + RealmChain.stt(sale.price()) + " STT", contract(),
                new Function("buyIsland", List.of(new Uint256(BigInteger.valueOf(deed))), Collections.emptyList()), sale.price(),
                receipt -> {
                    done(id);
                    sales.remove(deed);
                    holders.put(deed, p.wallet.toLowerCase(Locale.ROOT));
                    check(id, true);
                }, reason -> {
                    failed(id, "The purchase", reason);
                    refreshSale(deed);
                });
    }

    private void bid(Player player, long deed, BigInteger amount) {
        Sale sale = sales.get(deed);
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (sale == null || sale.bin() || System.currentTimeMillis() >= sale.endMs()) {
            tell(player, "That auction is not open any more.", NamedTextColor.RED);
            return;
        }
        if (sale.seller().equalsIgnoreCase(p.wallet)) {
            tell(player, "You cannot bid on your own island.", NamedTextColor.RED);
            return;
        }
        BigInteger min = RealmChain.minBid(sale.price(), sale.bid());
        if (amount.compareTo(min) < 0) {
            tell(player, "The minimum bid is " + stt(min) + ".", NamedTextColor.RED);
            return;
        }
        if (!start(player)) {
            return;
        }
        player.closeInventory();
        UUID id = player.getUniqueId();
        plugin.realm().send(id, "Bid " + RealmChain.stt(amount) + " STT on island #" + deed, contract(),
                new Function("bidIsland", List.of(new Uint256(BigInteger.valueOf(deed))), Collections.emptyList()), amount,
                receipt -> {
                    done(id);
                    refreshSale(deed);
                }, reason -> {
                    failed(id, "The bid", reason);
                    refreshSale(deed);
                });
    }

    private void cancel(Player player, long deed) {
        Sale sale = sales.get(deed);
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (sale == null || !sale.seller().equalsIgnoreCase(p.wallet)) {
            return;
        }
        if (!sale.bin() && sale.bid().signum() > 0) {
            tell(player, "An auction with bids cannot be cancelled.", NamedTextColor.RED);
            return;
        }
        if (!start(player)) {
            return;
        }
        player.closeInventory();
        UUID id = player.getUniqueId();
        boolean ended = System.currentTimeMillis() >= sale.endMs();
        plugin.realm().send(id, (ended ? "Close" : "Cancel") + " the listing of island #" + deed, contract(),
                new Function(ended ? "settleIsland" : "cancelIslandSale", List.of(new Uint256(BigInteger.valueOf(deed))), Collections.emptyList()), BigInteger.ZERO,
                receipt -> {
                    done(id);
                    sales.remove(deed);
                    tell(player, "Island #" + deed + " is no longer listed.", NamedTextColor.GREEN);
                }, reason -> {
                    failed(id, "Cancelling", reason);
                    refreshSale(deed);
                });
    }

    private void use(Player player, long deed) {
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (plugin.coops() != null && (plugin.coops().inCoop(p.uuid) || plugin.coops().isGuestMember(p.uuid))) {
            tell(player, "You are in a co-op. Leave it (or kick the members) before you move to another island.", NamedTextColor.RED);
            return;
        }
        if (!slots.containsKey(deed)) {
            tell(player, "Island #" + deed + " has no island placed on this server yet.", NamedTextColor.RED);
            return;
        }
        if (!start(player)) {
            return;
        }
        player.closeInventory();
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BigInteger active;
            try {
                active = plugin.wallets().memberIsland(p.wallet);
            } catch (IOException e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> failed(id, "Moving", RealmChain.reason(e)));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (active.longValue() == deed) {
                    // Already active onchain (a deed received while playing on an island without a deed).
                    done(id);
                    Slot slot = slots.get(deed);
                    if (slot != null) {
                        move(p, plugin.getServer().getPlayer(id), deed, slot);
                    }
                    return;
                }
                plugin.realm().send(id, "Move to island #" + deed, contract(),
                        new Function("useIsland", List.of(new Uint256(BigInteger.valueOf(deed))), Collections.emptyList()), BigInteger.ZERO,
                        receipt -> {
                            done(id);
                            check(id, true);
                        }, reason -> failed(id, "Moving", reason));
            });
        });
    }

    private void refreshSale(long deed) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Sale sale = plugin.wallets().islandSale(deed);
                if (sale == null) {
                    sales.remove(deed);
                } else {
                    sales.put(deed, sale);
                }
            } catch (IOException ignored) {
                // the log follower catches up
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void tellWallet(String wallet, String text, NamedTextColor color) {
        UUID id = byWallet.get(wallet.toLowerCase(Locale.ROOT));
        Player online = id == null ? null : plugin.getServer().getPlayer(id);
        if (online != null) {
            tell(online, text, color);
        }
    }

    private static void tell(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text(text, color));
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String block = yaml.getString("last-block", "");
        if (block != null && !block.isBlank()) {
            lastBlock = new BigInteger(block);
        }
        ConfigurationSection s = yaml.getConfigurationSection("deeds");
        if (s == null) {
            return;
        }
        for (String k : s.getKeys(false)) {
            ConfigurationSection d = s.getConfigurationSection(k);
            if (d == null) {
                continue;
            }
            slots.put(Long.parseLong(k), new Slot(d.getInt("x"), d.getInt("z"), doubles(d.getDoubleList("spawn")), doubles(d.getDoubleList("npc")),
                    d.getString("portal"), d.getInt("layout")));
        }
    }

    private static double[] doubles(List<Double> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static List<Double> list(double[] values) {
        List<Double> out = new ArrayList<>();
        if (values != null) {
            for (double v : values) {
                out.add(v);
            }
        }
        return out;
    }

    synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (lastBlock != null) {
            yaml.set("last-block", lastBlock.toString());
        }
        for (Map.Entry<Long, Slot> e : new java.util.TreeMap<>(slots).entrySet()) {
            String k = "deeds." + e.getKey() + ".";
            Slot s = e.getValue();
            yaml.set(k + "x", s.x());
            yaml.set(k + "z", s.z());
            yaml.set(k + "spawn", list(s.spawn()));
            yaml.set(k + "npc", list(s.npc()));
            yaml.set(k + "portal", s.portal());
            yaml.set(k + "layout", s.layout());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save deeds.yml: " + e.getMessage());
        }
    }
}
