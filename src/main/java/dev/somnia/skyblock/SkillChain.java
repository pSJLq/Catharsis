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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill XP and collections live in SkySkills (chain = source of truth, YAML = cache).
 * Every XP gain and collection increment is queued in Profile.pendingXp / pendingCol and sent
 * in the 4 s batch, signed by the player's own purse. Pending is only cleared after receipt status 1,
 * so a failed or reverted batch is retried and no XP is lost. On login the profile is set to
 * chain value + pending; the first login also migrates the old YAML values as one-time deltas.
 * Skill ids: 0 mining, 1 foraging, 2 farming, 3 combat, 4 fishing, 5 enchanting, 6 alchemy, 7 taming, 8 carpentry.
 * Collection id = index in Collects.ENTRIES (append only, never reorder).
 */
final class SkillChain implements Listener {
    static final int SKILLS = 9;
    static final int COLLECTIONS = 64;
    static final int MAX_ENTRIES = 64;
    static final long MAX_AMOUNT = 1_000_000L;
    private static final long INFLIGHT_GIVE_UP_MS = 300_000L;
    private static final long HINT_EVERY_MS = 120_000L;

    private final SomniaSkyblockPlugin plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hinted = new ConcurrentHashMap<>();

    SkillChain(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        if (enabled()) {
            plugin.getLogger().info("Skills contract " + contract() + " (chain is the source of truth for skill XP and collections).");
        }
    }

    String contract() {
        String value = plugin.getConfig().getString("chain.skills-contract", "");
        return value == null ? "" : value.trim();
    }

    /** 1 = SkySkills (add-only), 2 = SkySkills2 (profile epochs, resetProfile). */
    int version() {
        return plugin.getConfig().getInt("chain.skills-version", 1);
    }

    boolean canReset() {
        return enabled() && version() >= 2;
    }

    /** Migration key: the wallet plus the contract it was migrated to. A bare wallet means the v1 contract. */
    private String migrationKey(String wallet) {
        return wallet.toLowerCase(java.util.Locale.ROOT) + "@" + contract().toLowerCase(java.util.Locale.ROOT);
    }

    private String storedKey(Profile profile) {
        String stored = profile.skillsMigrated == null ? "" : profile.skillsMigrated.trim();
        if (stored.isEmpty() || stored.contains("@")) {
            return stored.toLowerCase(java.util.Locale.ROOT);
        }
        String v1 = plugin.getConfig().getString("chain.skills-contract-v1", "");
        String legacy = v1 == null || v1.isBlank() ? contract() : v1.trim();
        return (stored + "@" + legacy).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Profile delete: zero this player's skills and collections onchain (SkySkills2.resetProfile from their own purse,
     * through the same per-purse nonce lane as every other write). Queued XP is dropped; the flag survives restarts.
     * Caller holds no lock. Returns false on the old add-only contract.
     */
    boolean requestReset(Profile profile) {
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
            profile.skillsResetPending = true;
        }
        plugin.profiles().save(profile);
        plugin.getLogger().info("Skills reset queued for " + profile.wallet + " (SkySkills2.resetProfile on next flush).");
        return true;
    }

    /** /sbadmin reset-skills <name>: zero one player's skills and collections onchain. */
    void adminReset(String name, java.util.function.Consumer<String> report) {
        if (!canReset()) {
            report.accept("SkySkills2 is not deployed yet: run /sbadmin deploy-skills2 first.");
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

    boolean enabled() {
        return plugin.getConfig().getBoolean("chain.enabled") && !contract().isBlank();
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

    /** Deploys SkySkills from a logged-in player's own purse and switches the server to it (no restart needed). */
    void deploy(String name, java.util.function.Consumer<String> report) {
        deploy(name, report, false);
    }

    /** v2 = SkySkills2: the old address is kept as chain.skills-contract-v1, every player migrates once from the YAML cache. */
    void deploy(String name, java.util.function.Consumer<String> report, boolean v2) {
        if (!v2 && !contract().isBlank()) {
            report.accept("SkySkills is already configured at " + contract() + ".");
            return;
        }
        if (v2 && version() >= 2 && !contract().isBlank()) {
            report.accept("SkySkills2 is already configured at " + contract() + ".");
            return;
        }
        Player player = name == null ? null : Bukkit.getPlayerExact(name);
        if (player == null) {
            report.accept("Player " + name + " is not online.");
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (!plugin.wallets().canSign(profile)) {
            report.accept(name + " has not logged in yet.");
            return;
        }
        String bytecode;
        try (java.io.InputStream in = plugin.getResource(v2 ? "SkySkills2.bin" : "SkySkills.bin")) {
            bytecode = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (Exception exception) {
            report.accept((v2 ? "SkySkills2.bin" : "SkySkills.bin") + " is missing from the jar.");
            return;
        }
        String label = v2 ? "SkySkills2" : "SkySkills";
        report.accept("Deploying " + label + " from " + profile.wallet + "...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String[] done = plugin.wallets().deployFrom(profile, bytecode);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (v2) {
                        String old = contract();
                        if (!old.isBlank() && plugin.getConfig().getString("chain.skills-contract-v1", "").isBlank()) {
                            plugin.getConfig().set("chain.skills-contract-v1", old);
                        }
                        plugin.getConfig().set("chain.skills-version", 2);
                        plugin.getConfig().set("chain.skills-deploy-tx", done[1]);
                    }
                    plugin.getConfig().set("chain.skills-contract", done[0]);
                    plugin.saveConfig();
                    plugin.getLogger().info(label + " deployed at " + done[0] + " tx " + done[1]);
                    report.accept(label + " deployed at " + done[0] + " tx " + done[1]);
                    if (v2) {
                        // Everyone online re-reads the new contract now (one-time import of the cached values).
                        loaded.clear();
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            load(online);
                        }
                    }
                });
            } catch (Exception exception) {
                plugin.getLogger().warning(label + " deploy failed: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> report.accept(label + " deploy failed: " + exception.getMessage()));
            }
        });
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
                resolveInflight(profile, true);
                String from = contract();
                long[] xp = plugin.wallets().skillsOf(from, wallet);
                long[] col = plugin.wallets().collectionsOf(from, wallet, Collects.ENTRIES.size());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (from.equalsIgnoreCase(contract())) {
                            landed(player, profile, wallet, xp, col);
                        }
                    } finally {
                        busy.remove(id);
                    }
                });
            } catch (Exception exception) {
                busy.remove(id);
                retryAt.put(id, System.currentTimeMillis() + 15_000L);
                plugin.getLogger().warning("Skills read failed for " + wallet + ": " + exception.getMessage() + " (using the cached YAML for now)");
            }
        });
    }

    private void landed(Player player, Profile profile, String wallet, long[] xp, long[] col) {
        boolean migrate;
        long migratedXp = 0;
        long migratedCol = 0;
        synchronized (profile) {
            String key = migrationKey(wallet);
            migrate = !key.equals(storedKey(profile));
            if (profile.skillsResetPending) {
                // The reset is not onchain yet: the old epoch still reads its totals, which are already gone.
                xp = new long[xp.length];
                col = new long[col.length];
                migrate = false;
            }
            if (migrate && !storedKey(profile).isEmpty()) {
                // New contract: the old fresh-start offsets belonged to the previous contract.
                java.util.Arrays.fill(profile.skillsBase, 0);
                java.util.Arrays.fill(profile.colBase, 0);
            }
            for (Skills.Skill skill : Skills.Skill.values()) {
                int i = id(skill);
                long chain = Math.max(0, xp[i] - profile.skillsBase[i]);
                long local = Skills.xp(profile, skill);
                long pend = profile.pendingXp[i];
                if (migrate && local > chain + pend) {
                    migratedXp += local - chain - pend;
                    pend = local - chain;
                    profile.pendingXp[i] = pend;
                }
                Skills.put(profile, skill, chain + pend);
            }
            for (int i = 0; i < Collects.ENTRIES.size() && i < COLLECTIONS; i++) {
                Collects.Entry entry = Collects.ENTRIES.get(i);
                long chain = Math.max(0, col[i] - profile.colBase[i]);
                long local = Collects.amount(profile, entry);
                long pend = profile.pendingCol[i];
                if (migrate && local > chain + pend) {
                    migratedCol += local - chain - pend;
                    pend = local - chain;
                    profile.pendingCol[i] = pend;
                }
                long total = chain + pend;
                long island = Collects.islandPart(profile, entry);
                long extra = Math.max(0, total - island);
                if (extra == 0) {
                    profile.collections.remove(entry.key());
                } else {
                    profile.collections.put(entry.key(), extra);
                }
            }
            profile.skillsMigrated = key;
        }
        loaded.add(profile.uuid);
        plugin.profiles().save(profile);
        if (player.isOnline()) {
            plugin.stats().markDirty(player);
        }
        plugin.getLogger().info("Skills loaded from chain for " + wallet + (migrate ? " (one-time migration queued: " + migratedXp + " XP, " + migratedCol + " collection)" : ""));
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
            if (!loaded.contains(id)) {
                load(player);
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
                if (canReset()) {
                    sendReset(player, profile);
                }
                continue;
            }
            send(player, profile);
        }
    }

    /** SkySkills2.resetProfile() from the player's own purse; only after any inflight batch has landed (nonce order). */
    private void sendReset(Player player, Profile profile) {
        UUID id = profile.uuid;
        busy.add(id);
        String to = contract();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BigInteger balance = plugin.wallets().balanceWei(profile.wallet);
                if (balance.signum() == 0) {
                    throw new IllegalStateException("Purse has 0 STT.");
                }
                String hash = plugin.wallets().submitTo(profile, to, WalletService.resetProfile(), BigInteger.ZERO);
                plugin.getLogger().info("Skills reset sent for " + profile.wallet + ": " + hash);
                TransactionReceipt receipt = plugin.wallets().waitReceipt(hash, 60);
                if (receipt == null || !receipt.isStatusOK()) {
                    plugin.getLogger().warning("Skills reset " + hash + (receipt == null ? " has no receipt yet" : " reverted") + ", trying again.");
                    retryAt.put(id, System.currentTimeMillis() + 15_000L);
                    return;
                }
                long[] xp = plugin.wallets().skillsOf(to, profile.wallet);
                long sum = 0;
                for (long v : xp) {
                    sum += v;
                }
                long total = sum;
                synchronized (profile) {
                    profile.skillsResetPending = false;
                    profile.lastSkillTx = hash;
                    profile.skillsMigrated = migrationKey(profile.wallet);
                }
                plugin.getLogger().info("Skills reset landed for " + profile.wallet + " tx " + hash + " status 1 gas " + receipt.getGasUsed()
                        + "; xpOf now sums to " + total + ".");
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
                    plugin.getLogger().warning("Skills reset was not sent for " + profile.wallet + ": " + why);
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
        synchronized (profile) {
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
        }
        if (ns + nc == 0) {
            return;
        }
        UUID id = profile.uuid;
        busy.add(id);
        int[] s = java.util.Arrays.copyOf(skills, ns);
        long[] sa = java.util.Arrays.copyOf(amounts, ns);
        int[] c = java.util.Arrays.copyOf(cols, nc);
        long[] ca = java.util.Arrays.copyOf(colAmts, nc);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BigInteger balance = plugin.wallets().balanceWei(profile.wallet);
                if (balance.signum() == 0) {
                    throw new IllegalStateException("Purse has 0 STT.");
                }
                String hash = plugin.wallets().submitTo(profile, contract(), WalletService.applyXp(s, sa, c, ca), BigInteger.ZERO);
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
                    plugin.getLogger().warning("Skills batch was not sent for " + profile.wallet + ": " + why);
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
                for (int i = 0; i < COLLECTIONS; i++) {
                    profile.pendingCol[i] = Math.max(0, profile.pendingCol[i] - profile.skillTxCol[i]);
                }
                profile.lastSkillTx = hash;
                clearInflight(profile);
                changed = true;
            } else if (receipt != null) {
                plugin.getLogger().warning("Skills batch reverted " + hash + ", keeping it queued.");
                clearInflight(profile);
                changed = true;
            } else if (System.currentTimeMillis() - profile.skillTxAt > INFLIGHT_GIVE_UP_MS) {
                plugin.getLogger().warning("Skills batch " + hash + " has no receipt after 5 min, sending again.");
                clearInflight(profile);
                changed = true;
            }
        }
        if (changed) {
            if (receipt != null && receipt.isStatusOK()) {
                plugin.getLogger().info("Skills batch " + hash + " status 1 gas " + receipt.getGasUsed());
            }
            Bukkit.getScheduler().runTask(plugin, () -> plugin.profiles().save(profile));
        }
    }

    private static void clearInflight(Profile profile) {
        profile.skillTx = null;
        profile.skillTxAt = 0;
        profile.skillTxXp = new long[SKILLS];
        profile.skillTxCol = new long[COLLECTIONS];
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
