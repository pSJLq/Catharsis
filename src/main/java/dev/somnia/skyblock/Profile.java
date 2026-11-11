package dev.somnia.skyblock;

import java.util.UUID;

final class Profile {
    final UUID uuid;
    int islandX;
    int islandZ;
    boolean islandReady;
    int layout;
    /** Set only for islands made from the starter template: spawn x,y,z,yaw,pitch and guide x,y,z,yaw, relative to the origin. */
    double[] islandSpawn;
    double[] islandNpc;
    String islandPortalDest;
    boolean seenGuide;
    boolean bagCleared;
    boolean seenHub;
    boolean seenCoal;
    boolean seenGold;
    long miningXp;
    long farmingXp;
    long foragingXp;
    long cobble;
    long oak;
    long wheat;
    long enchantedCobble;
    long enchantedOak;
    int pendingCobble;
    int pendingOak;
    int pendingWheat;
    String wallet = "";
    /** Purse key sealed with the player's password (KeyVault a2id blob); the decrypted key lives only in Auth (RAM). */
    String walletEnc;
    /** Old plaintext key sealed under the temporary migration key until the player runs /register. */
    String walletPending;
    /** /exportkey done: the purse is in mode B (SkyKeys flag set onchain before the key was shown). */
    boolean keyExported;
    /** Blob kept (unusable) after /sbadmin unregister, with the old purse address. */
    String walletOrphaned;
    transient boolean migratedNow;
    String lastWorld;
    double[] lastPos;
    boolean holdReady;
    long coins;
    long combatXp;
    long kills;
    int bestWave;
    int lastCheckpoint;
    int questStep;
    int questProgress;
    long fishingXp;
    /** Leftover tenths of skill XP per Skills.Skill ordinal (pumpkin +4.5); never sent onchain until they add up to 1. */
    final int[] xpTenths = new int[7];
    long enchantingXp;
    long carpentryXp;
    /** Discovered hub zones, unlocked collections ("c:"), discovered stats ("s:"). */
    final java.util.Set<String> discovered = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Hub and minion collections by item key (island cobble/oak/wheat stay in the chain-backed counters above). */
    final java.util.Map<String, Long> collections = new java.util.concurrent.ConcurrentHashMap<>();
    /** SkySkills queue: XP per skill id and collection amounts per Collects.ENTRIES index, not yet confirmed onchain. */
    final long[] pendingXp = new long[SkillChain.SKILLS];
    final long[] pendingCol = new long[SkillChain.COLLECTIONS];
    /** Wallet whose YAML values were already migrated to SkySkills (once). */
    String skillsMigrated;
    /** Inflight SkySkills batch (survives restarts, so a landed tx is never counted twice). */
    String skillTx;
    long skillTxAt;
    long[] skillTxXp = new long[SkillChain.SKILLS];
    long[] skillTxCol = new long[SkillChain.COLLECTIONS];
    String lastSkillTx;
    /** Profile delete on SkySkills2: resetProfile() still has to land from this player's purse. */
    boolean skillsResetPending;
    int minionsHeld;
    /** Tiers of minions in storage (picked-up minions keep their tier); entries missing here are tier I. */
    final java.util.List<Integer> heldTiers = new java.util.ArrayList<>();
    boolean starterMinion;
    boolean bossbarOff;
    boolean menuItemOff;
    /** Fresh start: SkySkills is add-only, so XP/collections onchain at the reset are subtracted (chain - base). */
    final long[] skillsBase = new long[SkillChain.SKILLS];
    final long[] colBase = new long[SkillChain.COLLECTIONS];

    org.bukkit.Location lastLocation(org.bukkit.Server server) {
        if (lastWorld == null || lastPos == null) {
            return null;
        }
        org.bukkit.World w = server.getWorld(lastWorld);
        return w == null ? null : new org.bukkit.Location(w, lastPos[0], lastPos[1], lastPos[2], (float) lastPos[3], (float) lastPos[4]);
    }

    void setLastLocation(org.bukkit.Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        lastWorld = at.getWorld().getName();
        lastPos = new double[]{at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()};
    }

    Profile(UUID uuid) {
        this.uuid = uuid;
    }
}
