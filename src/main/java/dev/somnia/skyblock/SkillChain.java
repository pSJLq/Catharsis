package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill XP, collections and SkyBlock XP live in SkyProfile, keyed by the island deed (SkyIsland4): a profile is the
 * island, like a SkyBlock profile. Chain = source of truth, YAML = cache.
 * <ul>
 *   <li>Collections are one shared counter per item per island (the co-op total).</li>
 *   <li>Skill XP and task SkyBlock XP (quest steps, areas) are per member on that island.</li>
 *   <li>SkyBlock XP / Level is computed by the contract from that data (sbXpOf / levelOf); the game uses the same
 *       formula on the chain values plus the not yet confirmed queue.</li>
 * </ul>
 * Every gain is queued in Profile.pendingXp / pendingCol and sent in the 4 s batch, signed by the player's own purse
 * (a Booster sponsors gas as before). Pending is only cleared after receipt status 1. A player who leaves a co-op keeps
 * their record on the old island and starts from zero on the next one. Skill ids: 0 mining, 1 foraging, 2 farming,
 * 3 combat, 4 fishing, 5 enchanting, 6 alchemy, 7 taming, 8 carpentry. Collection id = index in Collects.ENTRIES.
 */
final class SkillChain implements Listener {
    static final int SKILLS = 9;
    static final int COLLECTIONS = 64;
    static final int MAX_ENTRIES = 64;
    static final long MAX_AMOUNT = 1_000_000L;
    static final long MAX_TASK = 10_000L;
    private static final long INFLIGHT_GIVE_UP_MS = 300_000L;
    private static final long HINT_EVERY_MS = 120_000L;

    /** For the static collection views (Collects.shared). */
    private static volatile SkillChain instance;

    private final SomniaSkyblockPlugin plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hinted = new ConcurrentHashMap<>();
    /** Island deed id -> shared collection counters as last read or confirmed onchain. */
    private final Map<Long, long[]> islandCols = new ConcurrentHashMap<>();

    SkillChain(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        if (enabled()) {
            plugin.getLogger().info("Profile contract " + contract() + " (SkyProfile: skills, shared collections and SkyBlock Level per island deed).");
            // One pass over every saved profile: anything cached from the old wallet-based contract is dropped, so the
            // game starts from zero together with the new contract (no import).
            Bukkit.getScheduler().runTask(plugin, this::freshStartAll);
        }
    }

    static SkillChain get() {
        return instance;
    }

    String contract() {
        String value = plugin.getConfig().getString("chain.skills-contract", "");
        return value == null ? "" : value.trim();
    }

    /** 4 = SkyProfile (per island deed). Older values belong to the retired wallet-based SkySkills contracts. */
    int version() {
        return plugin.getConfig().getInt("chain.skills-version", 4);
    }

    boolean canReset() {
        return enabled();
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("chain.enabled") && !contract().isBlank() && version() >= 4;
    }

    /** Cache key: the wallet plus the contract its values belong to. */
    private String cacheKey(String wallet) {
        return (wallet == null ? "" : wallet).toLowerCase(java.util.Locale.ROOT) + "@" + contract().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean current(Profile p) {
        String stored = p.skillsMigrated == null ? "" : p.skillsMigrated.trim().toLowerCase(java.util.Locale.ROOT);
        return stored.equals(cacheKey(p.wallet));
    }

    /** True when this profile's cached numbers belong to SkyProfile island `island` (the shared view counts it). */
    boolean onIsland(Profile p, long island) {
        return island > 0 && p.chainIsland == island && current(p);
    }

    // ------------------------------------------------------------------ fresh start (no import)

    private void freshStartAll() {
        int[] n = {0};
        plugin.profiles().eachReady(p -> {
            if (p.wallet == null || p.wallet.isBlank() || current(p)) {
                return;
            }
            synchronized (p) {
                clearLocal(p);
                p.skillsMigrated = cacheKey(p.wallet);
            }
            plugin.profiles().save(p);
            n[0]++;
        });
        if (n[0] > 0) {
            plugin.getLogger().info("SkyProfile fresh start: cleared cached skills and collections of " + n[0] + " profile(s); nothing imported from the old contract.");
        }
    }

    /** Zeroes the cached skill XP, collections, queues and the inflight batch. Caller holds the profile lock. */
    private static void clearLocal(Profile p) {
        for (Skills.Skill skill : Skills.Skill.values()) {
            Skills.put(p, skill, 0);
        }
        java.util.Arrays.fill(p.xpTenths, 0);
        p.collections.clear();
        p.discovered.removeIf(k -> k.startsWith("c:"));
        p.cobble = 0;
        p.oak = 0;
        p.wheat = 0;
        java.util.Arrays.fill(p.pendingXp, 0);
        java.util.Arrays.fill(p.pendingCol, 0);
        java.util.Arrays.fill(p.skillsBase, 0);
        java.util.Arrays.fill(p.colBase, 0);
        p.skillsResetPending = false;
        p.skillsResetWhole = false;
        p.chainIsland = 0;
        p.chainTask = 0;
        clearInflight(p);
    }

    // ------------------------------------------------------------------ resets and island moves

    /**
     * Profile delete / fresh start. {@code whole}: the deed holder (solo) wipes the island profile onchain
     * (SkyProfile.resetProfile); otherwise only this member's record (resetSelf). Sent from the player's own purse on the
     * next flush; queued XP is dropped. Returns false when the contract is not configured.
     */
    boolean requestReset(Profile profile, boolean whole) {
        if (!canReset()) {
            return false;
        }
        synchronized (profile) {
            java.util.Arrays.fill(profile.pendingXp, 0);
            java.util.Arrays.fill(profile.pendingCol, 0);
            java.util.Arrays.fill(profile.skillsBase, 0);
            java.util.Arrays.fill(profile.colBase, 0);
            for (Skills.Skill skill : Skills.Skill.values()) {
                Skills.put(profile, skill, 0);
            }
            profile.collections.clear();
            profile.chainTask = 0;
            profile.skillsResetPending = true;
            profile.skillsResetWhole = whole;
        }
        loaded.remove(profile.uuid);
        plugin.profiles().save(profile);
        plugin.getLogger().info("Profile reset queued for " + profile.wallet + " (SkyProfile." + (whole ? "resetProfile" : "resetSelf") + " on next flush).");
        return true;
    }

    boolean requestReset(Profile profile) {
        return requestReset(profile, plugin.coops() == null || !plugin.coops().inCoop(profile.uuid));
    }

    /**
     * Co-op join or leave: the player moves to another island deed. Nothing is wiped onchain: their record stays on the
     * old profile, and the next load reads their record on the new island (zero for a new member). Queued XP of the
     * old island is dropped. Returns false when the contract is not configured.
     */
    boolean switchIsland(Profile profile) {
        if (!enabled()) {
            return false;
        }
        synchronized (profile) {
            for (Skills.Skill skill : Skills.Skill.values()) {
                Skills.put(profile, skill, 0);
            }
            java.util.Arrays.fill(profile.pendingXp, 0);
            java.util.Arrays.fill(profile.pendingCol, 0);
            java.util.Arrays.fill(profile.skillsBase, 0);
            java.util.Arrays.fill(profile.colBase, 0);
            profile.collections.clear();
            profile.skillsResetPending = false;
            profile.skillsResetWhole = false;
            profile.chainIsland = 0;
            profile.chainTask = 0;
            clearInflight(profile);
        }
        loaded.remove(profile.uuid);
        retryAt.remove(profile.uuid);
        return true;
    }

    /** /sbadmin reset-skills <name>: zero one player's profile onchain (whole island when solo, else their own record). */
    void adminReset(String name, java.util.function.Consumer<String> report) {
        if (!canReset()) {
            report.accept("SkyProfile is not configured (chain.skills-contract, skills-version 4).");
            return;
        }
        org.bukkit.OfflinePlayer target = name == null ? null : Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            report.accept("Unknown player " + name + ".");
            return;
        }
        Profile profile = plugin.profiles().get(target.getUniqueId());
        if (profile.wallet == null || profile.wallet.isBlank()) {
            report.accept(name + " has no purse.");
            return;
        }
        requestReset(profile);
        Player online = target.getPlayer();
        if (online != null) {
            plugin.stats().markDirty(online);
        }
        report.accept("Reset queued for " + name + " (" + profile.wallet + "). It is sent from their purse " + (online != null ? "now." : "when they next log in."));
    }

    static int id(Skills.Skill skill) {
        return switch (skill) {
            case MINING -> 0;
            case FORAGING -> 1;
            case FARMING -> 2;
            case COMBAT -> 3;
            case FISHING -> 4;
            case ENCHANTING -> 5;
            case CARPENTRY -> 8;
        };
    }

    /** Caller holds the profile lock or runs on the main thread. */
    void queueXp(Profile profile, Skills.Skill skill, long amount) {
        if (amount <= 0) {
            return;
        }
        synchronized (profile) {
            profile.pendingXp[id(skill)] += amount;
        }
    }

    void queueCollection(Profile profile, Collects.Entry entry, long amount) {
        int index = Collects.ENTRIES.indexOf(entry);
        if (index < 0 || index >= COLLECTIONS || amount <= 0) {
            return;
        }
        synchronized (profile) {
            profile.pendingCol[index] += amount;
        }
    }

    long pending(Profile profile, Skills.Skill skill) {
        synchronized (profile) {
            return profile.pendingXp[id(skill)];
        }
    }

    boolean loaded(UUID id) {
        return loaded.contains(id);
    }

    // ------------------------------------------------------------------ shared collections and SkyBlock XP

    /**
     * The island's shared collection as the game shows it: the onchain counter plus what every member of that island
     * still has queued. Null when this profile is not bound to a SkyProfile island yet (the caller falls back).
     */
    Long sharedCollection(Profile p, int index) {
        long island = p.chainIsland;
        if (!enabled() || island <= 0 || !current(p) || index < 0 || index >= COLLECTIONS) {
            return null;
        }
        long[] chain = islandCols.get(island);
        if (chain == null) {
            return null;
        }
        long sum = chain[index];
        List<Profile> group = Coops.group(p);
        boolean self = false;
        for (Profile m : group) {
            if (m.uuid.equals(p.uuid)) {
                self = true;
            }
            if (onIsland(m, island)) {
                synchronized (m) {
                    sum += m.pendingCol[index];
                }
            }
        }
        if (!self) {
            synchronized (p) {
                sum += p.pendingCol[index];
            }
        }
        return sum;
    }

    /** Task SkyBlock XP the game knows locally: quest steps and discovered areas (same rule as Skills.skyblockXp). */
    static long localTaskXp(Profile p) {
        long xp = (long) Quests.SKYBLOCK_XP * Quests.xpSteps(p);
        for (String key : p.discovered) {
            if (!key.contains(":")) {
                xp += 5;
            }
        }
        return xp;
    }

    /** Task XP counted in the SkyBlock Level: the onchain record (plus inflight) or the local value, whichever is higher. */
    static long taskXp(Profile p) {
        long local = localTaskXp(p);
        SkillChain sc = instance;
        if (sc == null || !sc.enabled() || !sc.current(p)) {
            return local;
        }
        return Math.max(local, p.chainTask);
    }

    // ------------------------------------------------------------------ deploy (retired)

    void deploy(String name, java.util.function.Consumer<String> report) {
        deploy(name, report, false);
    }

    /** The wallet-based SkySkills contracts are retired; SkyProfile is deployed with tools/chain/deploy-profile.js. */
    void deploy(String name, java.util.function.Consumer<String> report, boolean v2) {
        report.accept("Skills now live in SkyProfile" + (contract().isBlank() ? "" : " at " + contract()) + "; there is nothing to deploy from the game.");
    }

    // ------------------------------------------------------------------ login: read the chain

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(SbLoginEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    void forget(UUID id) {
        loaded.remove(id);
        retryAt.remove(id);
        hinted.remove(id);
    }

    void load(Player player) {
        if (!enabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        String wallet = profile.wallet;
        if (wallet == null || wallet.isBlank() || !busy.add(id)) {
            return;
        }
        loaded.remove(id);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String from = contract();
                if (!current(profile)) {
                    synchronized (profile) {
                        clearLocal(profile);
                        profile.skillsMigrated = cacheKey(wallet);
                    }
                    plugin.getLogger().info("SkyProfile fresh start for " + wallet + " (old cached skills dropped).");
                } else {
                    resolveInflight(profile, true);
                }
                long island = plugin.wallets().memberIsland(wallet).longValue();
                if (island <= 0) {
                    busy.remove(id);
                    retryAt.put(id, System.currentTimeMillis() + 15_000L);
                    return;
                }
                WalletService.ProfileView view = plugin.wallets().profileOf(from, island, wallet, Collects.ENTRIES.size());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (from.equalsIgnoreCase(contract())) {
                            landed(player, profile, wallet, island, view);
                        }
                    } finally {
                        busy.remove(id);
                    }
                });
            } catch (Exception exception) {
                busy.remove(id);
                retryAt.put(id, System.currentTimeMillis() + 15_000L);
                plugin.getLogger().warning("Profile read failed for " + wallet + ": " + exception.getMessage() + " (using the cached YAML for now)");
            }
        });
    }

    private void landed(Player player, Profile profile, String wallet, long island, WalletService.ProfileView view) {
        boolean moved;
        synchronized (profile) {
            moved = profile.chainIsland != 0 && profile.chainIsland != island;
            if (moved || profile.skillsResetPending) {
                // Queued gains belong to the island the player left (or to the profile being wiped).
                java.util.Arrays.fill(profile.pendingXp, 0);
                java.util.Arrays.fill(profile.pendingCol, 0);
                clearInflight(profile);
            }
            boolean wiping = profile.skillsResetPending;
            for (Skills.Skill skill : Skills.Skill.values()) {
                int i = id(skill);
                long chain = wiping ? 0 : view.skills()[i];
                Skills.put(profile, skill, chain + profile.pendingXp[i]);
            }
            profile.chainTask = wiping ? 0 : view.task();
            profile.chainIsland = island;
            if (moved) {
                profile.collections.clear();
            }
        }
        if (!profile.skillsResetPending || !profile.skillsResetWhole) {
            islandCols.put(island, view.collections());
        }
        loaded.add(profile.uuid);
        plugin.profiles().save(profile);
        if (player.isOnline()) {
            plugin.stats().markDirty(player);
        }
        long shown = Skills.skyblockXp(profile);
        plugin.getLogger().info("Profile loaded from SkyProfile for " + wallet + ": island #" + island + " era " + view.epoch()
                + ", contract SkyBlock XP " + view.sbXp() + " (game shows " + shown + " incl. queue)" + (moved ? ", moved from another island (queue dropped)" : ""));
    }

    // ------------------------------------------------------------------ 4 s batch

    void flush() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (busy.contains(id) || retryAt.getOrDefault(id, 0L) > now) {
                continue;
            }
            Profile profile = plugin.profiles().get(id);
            if (!plugin.wallets().canSign(profile) || profile.wallet == null || profile.wallet.isBlank()) {
                continue;
            }
            if (profile.skillTx != null) {
                busy.add(id);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        resolveInflight(profile, false);
                    } catch (Exception exception) {
                        retryAt.put(id, System.currentTimeMillis() + 5_000L);
                    } finally {
                        busy.remove(id);
                    }
                });
                continue;
            }
            if (profile.skillsResetPending) {
                sendReset(player, profile);
                continue;
            }
            if (!loaded.contains(id)) {
                load(player);
                continue;
            }
            send(player, profile);
        }
    }

    /** SkyProfile.resetProfile(island) or resetSelf(island) from the player's own purse. */
    private void sendReset(Player player, Profile profile) {
        UUID id = profile.uuid;
        busy.add(id);
        String to = contract();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long island = plugin.wallets().memberIsland(profile.wallet).longValue();
                if (island <= 0) {
                    retryAt.put(id, System.currentTimeMillis() + 15_000L);
                    return;
                }
                BigInteger balance = plugin.wallets().balanceWei(profile.wallet);
                if ((plugin.cookies() == null || !plugin.cookies().active(profile))
                        && balance.compareTo(plugin.wallets().gasFeeCap()) < 0) {
                    throw new IllegalStateException("Purse has insufficient STT for gas.");
                }
                boolean whole = profile.skillsResetWhole;
                String hash;
                try {
                    hash = plugin.wallets().submitTo(profile, to, whole ? WalletService.resetIslandProfile(island) : WalletService.resetSelf(island), BigInteger.ZERO);
                } catch (Exception refused) {
                    if (!whole || String.valueOf(refused.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("insufficient")) {
                        throw refused;
                    }
                    // Not the solo holder (a member, or members joined since): only this player's own record.
                    whole = false;
                    hash = plugin.wallets().submitTo(profile, to, WalletService.resetSelf(island), BigInteger.ZERO);
                }
                plugin.getLogger().info("Profile reset sent for " + profile.wallet + " (island #" + island + ", " + (whole ? "whole profile" : "own record") + "): " + hash);
                TransactionReceipt receipt = plugin.wallets().waitReceipt(hash, 60);
                if (receipt == null || !receipt.isStatusOK()) {
                    plugin.getLogger().warning("Profile reset " + hash + (receipt == null ? " has no receipt yet" : " reverted") + ", trying again.");
                    retryAt.put(id, System.currentTimeMillis() + 15_000L);
                    return;
                }
                boolean wipedAll = whole;
                synchronized (profile) {
                    profile.skillsResetPending = false;
                    profile.skillsResetWhole = false;
                    profile.lastSkillTx = hash;
                    profile.chainIsland = island;
                    profile.chainTask = 0;
                }
                if (wipedAll) {
                    islandCols.put(island, new long[COLLECTIONS]);
                }
                loaded.remove(id);
                plugin.getLogger().info("Profile reset landed for " + profile.wallet + " tx " + hash + " status 1 gas " + receipt.getGasUsed() + ".");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.profiles().save(profile);
                    if (player.isOnline()) {
                        plugin.stats().markDirty(player);
                    }
                });
            } catch (Exception exception) {
                String why = String.valueOf(exception.getMessage());
                boolean broke = why.startsWith("Purse has") || why.toLowerCase(java.util.Locale.ROOT).contains("insufficient");
                retryAt.put(id, System.currentTimeMillis() + (broke ? 20_000L : 8_000L));
                if (broke) {
                    Bukkit.getScheduler().runTask(plugin, () -> hint(player));
                } else {
                    plugin.getLogger().warning("Profile reset was not sent for " + profile.wallet + ": " + why);
                }
            } finally {
                busy.remove(id);
            }
        });
    }

    private void send(Player player, Profile profile) {
        int[] skills = new int[MAX_ENTRIES];
        long[] amounts = new long[MAX_ENTRIES];
        int[] cols = new int[MAX_ENTRIES];
        long[] colAmts = new long[MAX_ENTRIES];
        int ns = 0;
        int nc = 0;
        long task;
        long island;
        synchronized (profile) {
            island = profile.chainIsland;
            for (int i = 0; i < SKILLS && ns + nc < MAX_ENTRIES; i++) {
                long a = Math.min(profile.pendingXp[i], MAX_AMOUNT);
                if (a > 0) {
                    skills[ns] = i;
                    amounts[ns++] = a;
                }
            }
            for (int i = 0; i < COLLECTIONS && ns + nc < MAX_ENTRIES; i++) {
                long a = Math.min(profile.pendingCol[i], MAX_AMOUNT);
                if (a > 0) {
                    cols[nc] = i;
                    colAmts[nc++] = a;
                }
            }
            task = Math.max(0, Math.min(MAX_TASK, localTaskXp(profile) - profile.chainTask));
        }
        if (ns + nc == 0 && task == 0) {
            return;
        }
        if (island <= 0) {
            loaded.remove(profile.uuid);
            return;
        }
        UUID id = profile.uuid;
        busy.add(id);
        int[] s = java.util.Arrays.copyOf(skills, ns);
        long[] sa = java.util.Arrays.copyOf(amounts, ns);
        int[] c = java.util.Arrays.copyOf(cols, nc);
        long[] ca = java.util.Arrays.copyOf(colAmts, nc);
        long t = task;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long active = plugin.wallets().memberIsland(profile.wallet).longValue();
                if (active != island) {
                    // Joined or left a co-op since the last read: the next load binds the new island.
                    loaded.remove(id);
                    return;
                }
                BigInteger balance = plugin.wallets().balanceWei(profile.wallet);
                if ((plugin.cookies() == null || !plugin.cookies().active(profile))
                        && balance.compareTo(plugin.wallets().gasFeeCap()) < 0) {
                    throw new IllegalStateException("Purse has insufficient STT for gas.");
                }
                String hash = plugin.wallets().submitTo(profile, contract(), WalletService.applyXp(island, s, sa, c, ca, t), BigInteger.ZERO);
                long[] txXp = new long[SKILLS];
                long[] txCol = new long[COLLECTIONS];
                for (int i = 0; i < s.length; i++) {
                    txXp[s[i]] = sa[i];
                }
                for (int i = 0; i < c.length; i++) {
                    txCol[c[i]] = ca[i];
                }
                synchronized (profile) {
                    profile.skillTx = hash;
                    profile.skillTxAt = System.currentTimeMillis();
                    profile.skillTxXp = txXp;
                    profile.skillTxCol = txCol;
                    profile.skillTxTask = t;
                    profile.skillTxIsland = island;
                }
                Bukkit.getScheduler().runTask(plugin, () -> plugin.profiles().save(profile));
                resolveInflight(profile, false);
            } catch (Exception exception) {
                String why = String.valueOf(exception.getMessage());
                boolean broke = why.startsWith("Purse has") || why.toLowerCase(java.util.Locale.ROOT).contains("insufficient");
                retryAt.put(id, System.currentTimeMillis() + (broke ? 20_000L : 8_000L));
                if (broke) {
                    Bukkit.getScheduler().runTask(plugin, () -> hint(player));
                } else {
                    plugin.getLogger().warning("Profile batch was not sent for " + profile.wallet + ": " + why);
                    loaded.remove(id);
                }
            } finally {
                busy.remove(id);
            }
        });
    }

    /** Off the main thread. Applies a finished inflight batch: status 1 clears pending, a revert keeps it for a retry. */
    private void resolveInflight(Profile profile, boolean once) throws Exception {
        String hash;
        synchronized (profile) {
            hash = profile.skillTx;
        }
        if (hash == null) {
            return;
        }
        TransactionReceipt receipt = plugin.wallets().waitReceipt(hash, once ? 20 : 60);
        boolean changed = false;
        synchronized (profile) {
            if (!hash.equals(profile.skillTx)) {
                return;
            }
            if (receipt != null && receipt.isStatusOK()) {
                for (int i = 0; i < SKILLS; i++) {
                    profile.pendingXp[i] = Math.max(0, profile.pendingXp[i] - profile.skillTxXp[i]);
                }
                long[] shared = profile.skillTxIsland > 0 ? islandCols.get(profile.skillTxIsland) : null;
                for (int i = 0; i < COLLECTIONS; i++) {
                    profile.pendingCol[i] = Math.max(0, profile.pendingCol[i] - profile.skillTxCol[i]);
                    if (shared != null) {
                        shared[i] += profile.skillTxCol[i];
                    }
                }
                if (profile.skillTxIsland == profile.chainIsland) {
                    profile.chainTask += profile.skillTxTask;
                }
                profile.lastSkillTx = hash;
                clearInflight(profile);
                changed = true;
            } else if (receipt != null) {
                plugin.getLogger().warning("Profile batch reverted " + hash + ", keeping it queued.");
                clearInflight(profile);
                changed = true;
            } else if (System.currentTimeMillis() - profile.skillTxAt > INFLIGHT_GIVE_UP_MS) {
                plugin.getLogger().warning("Profile batch " + hash + " has no receipt after 5 min, sending again.");
                clearInflight(profile);
                changed = true;
            }
        }
        if (changed) {
            if (receipt != null && receipt.isStatusOK()) {
                plugin.getLogger().info("Profile batch " + hash + " status 1 gas " + receipt.getGasUsed());
            }
            Bukkit.getScheduler().runTask(plugin, () -> plugin.profiles().save(profile));
        }
    }

    private static void clearInflight(Profile profile) {
        profile.skillTx = null;
        profile.skillTxAt = 0;
        profile.skillTxXp = new long[SKILLS];
        profile.skillTxCol = new long[COLLECTIONS];
        profile.skillTxTask = 0;
        profile.skillTxIsland = 0;
    }

    private void hint(Player player) {
        if (!player.isOnline()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (hinted.getOrDefault(player.getUniqueId(), 0L) > now - HINT_EVERY_MS) {
            return;
        }
        hinted.put(player.getUniqueId(), now);
        player.sendMessage(Component.text("Your skill XP is saved and waiting to go onchain, but your game purse has no STT for gas.", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Top up with /deposit. Your queued progress is sent as soon as STT arrives.", NamedTextColor.GRAY));
    }
}
