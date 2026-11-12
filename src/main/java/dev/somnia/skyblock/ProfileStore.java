package dev.somnia.skyblock;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class ProfileStore {
    private final SomniaSkyblockPlugin plugin;
    private final File directory;
    private final Map<UUID, Profile> cache = new ConcurrentHashMap<>();

    ProfileStore(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "players");
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("Could not create the players folder");
        }
    }

    Profile get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    private static double[] doubles(java.util.List<Double> list, int size) {
        if (list == null || list.size() < size) {
            return null;
        }
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static void readLongs(java.util.List<Long> list, long[] into) {
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size() && i < into.length; i++) {
            Long v = list.get(i);
            into[i] = v == null ? 0L : v;
        }
    }

    private Profile load(UUID uuid) {
        Profile profile = new Profile(uuid);
        File file = file(uuid);
        if (!file.exists()) {
            return profile;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        profile.islandX = yaml.getInt("island-x");
        profile.islandZ = yaml.getInt("island-z");
        profile.islandReady = yaml.getBoolean("island-ready");
        profile.layout = yaml.getInt("layout");
        profile.islandSpawn = doubles(yaml.getDoubleList("island-spawn"), 5);
        profile.islandNpc = doubles(yaml.getDoubleList("island-npc"), 4);
        profile.islandPortalDest = yaml.getString("island-portal-dest");
        profile.seenGuide = yaml.getBoolean("seen-guide");
        profile.bagCleared = yaml.getBoolean("bag-cleared");
        profile.seenHub = yaml.getBoolean("seen-hub");
        profile.seenCoal = yaml.getBoolean("seen-coal");
        profile.seenGold = yaml.getBoolean("seen-gold");
        profile.miningXp = yaml.getLong("mining-xp");
        profile.farmingXp = yaml.getLong("farming-xp");
        profile.foragingXp = yaml.getLong("foraging-xp");
        profile.cobble = yaml.getLong("cobble");
        profile.oak = yaml.getLong("oak");
        profile.wheat = yaml.getLong("wheat");
        profile.enchantedCobble = yaml.getLong("enchanted-cobble");
        profile.enchantedOak = yaml.getLong("enchanted-oak");
        profile.pendingCobble = yaml.getInt("pending-cobble");
        profile.pendingOak = yaml.getInt("pending-oak");
        profile.pendingWheat = yaml.getInt("pending-wheat");
        profile.wallet = yaml.getString("wallet", "");
        profile.walletEnc = yaml.getString("wallet-enc");
        profile.walletPending = yaml.getString("wallet-pending");
        profile.walletOrphaned = yaml.getString("wallet-enc-orphaned");
        profile.keyExported = yaml.getBoolean("key-exported", false);
        profile.lastWorld = yaml.getString("last-world");
        profile.lastPos = doubles(yaml.getDoubleList("last-pos"), 5);
        String plain = yaml.getString("wallet-key", "");
        if (plain != null && !plain.isBlank()) {
            if (profile.walletEnc == null && profile.walletPending == null) {
                profile.walletPending = plugin.auth().migrate(profile, plain);
            }
            profile.migratedNow = true;
        }
        profile.holdReady = yaml.getBoolean("hold-ready");
        profile.coins = yaml.getLong("coins");
        profile.combatXp = yaml.getLong("combat-xp");
        profile.kills = yaml.getLong("kills");
        profile.bestWave = yaml.getInt("best-wave");
        profile.lastCheckpoint = yaml.getInt("last-checkpoint");
        profile.questStep = yaml.getInt("quest-step");
        profile.questProgress = yaml.getInt("quest-progress");
        profile.questVersion = yaml.getInt("quest-version", 1);
        profile.questSkip.addAll(yaml.getStringList("quest-skip"));
        profile.fishingXp = yaml.getLong("fishing-xp");
        profile.enchantingXp = yaml.getLong("enchanting-xp");
        profile.carpentryXp = yaml.getLong("carpentry-xp");
        profile.discovered.addAll(yaml.getStringList("discovered"));
        org.bukkit.configuration.ConfigurationSection coll = yaml.getConfigurationSection("collections");
        if (coll != null) {
            for (String key : coll.getKeys(false)) {
                profile.collections.put(key, coll.getLong(key));
            }
        }
        // Island and hub share one path now: the old SkyIsland counters fold into the normal collections once
        // (SkySkills already holds them onchain), and the per-resource island batches stop.
        if (profile.cobble > 0 || profile.oak > 0 || profile.wheat > 0) {
            if (profile.cobble > 0) {
                profile.collections.merge("cobblestone", profile.cobble, Long::sum);
            }
            if (profile.oak > 0) {
                profile.collections.merge("oak_log", profile.oak, Long::sum);
            }
            if (profile.wheat > 0) {
                profile.collections.merge("wheat", profile.wheat, Long::sum);
            }
            profile.cobble = 0;
            profile.oak = 0;
            profile.wheat = 0;
        }
        profile.pendingCobble = 0;
        profile.pendingOak = 0;
        profile.pendingWheat = 0;
        readLongs(yaml.getLongList("skills-pending-xp"), profile.pendingXp);
        readLongs(yaml.getLongList("skills-pending-col"), profile.pendingCol);
        profile.skillsMigrated = yaml.getString("skills-migrated");
        readLongs(yaml.getLongList("skills-base-xp"), profile.skillsBase);
        readLongs(yaml.getLongList("skills-base-col"), profile.colBase);
        profile.skillTx = yaml.getString("skills-tx");
        profile.skillTxAt = yaml.getLong("skills-tx-at");
        readLongs(yaml.getLongList("skills-tx-xp"), profile.skillTxXp);
        readLongs(yaml.getLongList("skills-tx-col"), profile.skillTxCol);
        profile.lastSkillTx = yaml.getString("skills-last-tx");
        profile.skillsResetPending = yaml.getBoolean("skills-reset-pending");
        profile.minionsHeld = yaml.getInt("minions-held");
        profile.heldTiers.clear();
        profile.heldTiers.addAll(yaml.getIntegerList("minions-held-tiers"));
        profile.starterMinion = yaml.getBoolean("starter-minion");
        profile.bossbarOff = yaml.getBoolean("bossbar-off");
        profile.menuItemOff = yaml.getBoolean("menu-item-off");
        if (Quests.migrate(profile)) {
            plugin.getLogger().info("Quests v1 -> v2 for " + uuid + ": step " + profile.questStep + ", already done later " + profile.questSkip);
        }
        return profile;
    }

    void save(Profile profile) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("island-x", profile.islandX);
        yaml.set("island-z", profile.islandZ);
        yaml.set("island-ready", profile.islandReady);
        yaml.set("layout", profile.layout);
        yaml.set("island-spawn", profile.islandSpawn == null ? null : java.util.Arrays.stream(profile.islandSpawn).boxed().toList());
        yaml.set("island-npc", profile.islandNpc == null ? null : java.util.Arrays.stream(profile.islandNpc).boxed().toList());
        yaml.set("island-portal-dest", profile.islandPortalDest);
        yaml.set("seen-guide", profile.seenGuide);
        yaml.set("bag-cleared", profile.bagCleared);
        yaml.set("seen-hub", profile.seenHub);
        yaml.set("seen-coal", profile.seenCoal);
        yaml.set("seen-gold", profile.seenGold);
        yaml.set("mining-xp", profile.miningXp);
        yaml.set("farming-xp", profile.farmingXp);
        yaml.set("foraging-xp", profile.foragingXp);
        yaml.set("cobble", profile.cobble);
        yaml.set("oak", profile.oak);
        yaml.set("wheat", profile.wheat);
        yaml.set("enchanted-cobble", profile.enchantedCobble);
        yaml.set("enchanted-oak", profile.enchantedOak);
        yaml.set("pending-cobble", profile.pendingCobble);
        yaml.set("pending-oak", profile.pendingOak);
        yaml.set("pending-wheat", profile.pendingWheat);
        yaml.set("wallet", profile.wallet);
        yaml.set("wallet-enc", profile.walletEnc);
        yaml.set("wallet-pending", profile.walletPending);
        yaml.set("wallet-enc-orphaned", profile.walletOrphaned);
        yaml.set("key-exported", profile.keyExported ? Boolean.TRUE : null);
        yaml.set("last-world", profile.lastWorld);
        yaml.set("last-pos", profile.lastPos == null ? null : java.util.Arrays.stream(profile.lastPos).boxed().toList());
        yaml.set("hold-ready", profile.holdReady);
        yaml.set("coins", profile.coins);
        yaml.set("combat-xp", profile.combatXp);
        yaml.set("kills", profile.kills);
        yaml.set("best-wave", profile.bestWave);
        yaml.set("last-checkpoint", profile.lastCheckpoint);
        yaml.set("quest-step", profile.questStep);
        yaml.set("quest-progress", profile.questProgress);
        yaml.set("quest-version", profile.questVersion);
        yaml.set("quest-skip", new ArrayList<>(profile.questSkip));
        yaml.set("fishing-xp", profile.fishingXp);
        yaml.set("enchanting-xp", profile.enchantingXp);
        yaml.set("carpentry-xp", profile.carpentryXp);
        yaml.set("discovered", new ArrayList<>(new java.util.TreeSet<>(profile.discovered)));
        for (Map.Entry<String, Long> entry : profile.collections.entrySet()) {
            yaml.set("collections." + entry.getKey(), entry.getValue());
        }
        synchronized (profile) {
            yaml.set("skills-pending-xp", java.util.Arrays.stream(profile.pendingXp).boxed().toList());
            yaml.set("skills-pending-col", java.util.Arrays.stream(profile.pendingCol).boxed().toList());
            yaml.set("skills-migrated", profile.skillsMigrated);
        yaml.set("skills-base-xp", java.util.Arrays.stream(profile.skillsBase).boxed().toList());
        yaml.set("skills-base-col", java.util.Arrays.stream(profile.colBase).boxed().toList());
            yaml.set("skills-tx", profile.skillTx);
            yaml.set("skills-tx-at", profile.skillTxAt);
            yaml.set("skills-tx-xp", java.util.Arrays.stream(profile.skillTxXp).boxed().toList());
            yaml.set("skills-tx-col", java.util.Arrays.stream(profile.skillTxCol).boxed().toList());
            yaml.set("skills-last-tx", profile.lastSkillTx);
            yaml.set("skills-reset-pending", profile.skillsResetPending);
        }
        yaml.set("minions-held", profile.minionsHeld);
        yaml.set("minions-held-tiers", new java.util.ArrayList<>(profile.heldTiers));
        yaml.set("starter-minion", profile.starterMinion);
        yaml.set("bossbar-off", profile.bossbarOff);
        yaml.set("menu-item-off", profile.menuItemOff);
        try {
            yaml.save(file(profile.uuid));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save profile " + profile.uuid + ": " + exception.getMessage());
        }
    }

    void eachReady(Consumer<Profile> consumer) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        List<Profile> ready = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            UUID uuid;
            try {
                uuid = UUID.fromString(name.substring(0, name.length() - 4));
            } catch (IllegalArgumentException exception) {
                continue;
            }
            Profile profile = get(uuid);
            if (profile.islandReady) {
                ready.add(profile);
            }
        }
        for (Profile profile : ready) {
            consumer.accept(profile);
        }
    }

    void saveAll() {
        for (Profile profile : cache.values()) {
            save(profile);
        }
    }

    private File file(UUID uuid) {
        return new File(directory, uuid + ".yml");
    }
}
