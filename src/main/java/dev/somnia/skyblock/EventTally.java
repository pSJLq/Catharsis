package dev.somnia.skyblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event-agnostic counters for the website leaderboard. Every 30 s it writes plugins/SomniaSkyblock/event/board.json:
 * per player SkyBlock level/XP, skill XP, sent transactions (by contract), actions, and rolling anomaly metrics
 * (XP per skill in the last 60 min, active minutes in the last 24 h, minute-to-minute steadiness, click-interval
 * regularity, a salted IP tag). The website keeps the event start/end and subtracts its start snapshot, so nothing
 * here knows about the event. Counters persist in event/tally.json. Cheap: a few map updates per action and one
 * small JSON write (off the main thread) every 30 s.
 */
final class EventTally implements Listener {
    private static volatile EventTally instance;

    private final SomniaSkyblockPlugin plugin;
    private final File dir;
    private final Map<UUID, Rec> recs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> txBy = new ConcurrentHashMap<>();
    private final AtomicLong txAll = new AtomicLong();
    private String salt;
    private long lastMinute = System.currentTimeMillis() / 60_000L;
    private int ticks;
    private boolean scanned;

    static final class Rec {
        String name = "";
        final AtomicLong tx = new AtomicLong();
        final Map<String, AtomicLong> txBy = new ConcurrentHashMap<>();
        long actions;
        long firstSeen;
        long lastSeen;
        String ipTag;
        int level;
        int sbXp;
        /** Profile.resets seen together with sbXp: the website restarts the player's event count when it changes. */
        int epoch;
        final long[] xp = new long[Skills.Skill.values().length];
        // rolling windows (memory only)
        final long[] prevXp = new long[Skills.Skill.values().length];
        boolean havePrev;
        final long[][] xpMin = new long[Skills.Skill.values().length][60];
        final int[] actMin = new int[60];
        final boolean[] active24 = new boolean[1440];
        int minuteActions;
        final long[] gaps = new long[300];
        int gapCount;
        int gapPos;
        long lastAction;
    }

    EventTally(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "event");
        dir.mkdirs();
        load();
        instance = this;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 600L);
    }

    void stop() {
        String state = state();
        write("tally.json", state);
        instance = null;
    }

    // ------------------------------------------------------------------ transactions (any thread)

    /** Called right after a transaction was accepted by the RPC (hash returned). */
    static void tx(Profile profile, String to, String fallbackKind) {
        EventTally t = instance;
        if (t == null || profile == null) {
            return;
        }
        try {
            String kind = t.kind(to, fallbackKind);
            Rec r = t.recs.computeIfAbsent(profile.uuid, u -> new Rec());
            r.tx.incrementAndGet();
            r.txBy.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
            t.txAll.incrementAndGet();
            t.txBy.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
        } catch (RuntimeException ignored) {
            // never let the tally break a write
        }
    }

    private String kind(String to, String fallback) {
        if (fallback != null) {
            return fallback;
        }
        if (to == null || to.isBlank()) {
            return "other";
        }
        String a = to.trim().toLowerCase(Locale.ROOT);
        var c = plugin.getConfig();
        String[][] map = {
                {"chain.skills-contract", "skills"}, {"chain.hold-contract", "bag"}, {"chain.word-contract", "words"},
                {"chain.profile-contract", "island"}, {"chain.aotv-contract", "sword"}, {"chain.realm-contract", "market"},
                {"chain.bin-contract", "market"}, {"chain.keys-contract", "keys"}};
        for (String[] m : map) {
            String v = c.getString(m[0], "");
            if (v != null && !v.isBlank() && v.trim().toLowerCase(Locale.ROOT).equals(a)) {
                return m[1];
            }
        }
        if (a.equalsIgnoreCase(RealmChain.DEFAULT_REALM)) {
            return "market";
        }
        return "other";
    }

    /** Right after a full profile reset (main thread): take the new (zeroed) totals and epoch at once. */
    static void profileReset(Profile profile) {
        EventTally t = instance;
        if (t == null || profile == null) {
            return;
        }
        Rec r = t.recs.computeIfAbsent(profile.uuid, u -> new Rec());
        t.capture(r, profile, false);
        r.havePrev = false; // the rolling XP-per-hour window must not see the drop as anything
    }

    // ------------------------------------------------------------------ actions (main thread)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        action(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            action(killer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            action(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Rec r = recs.computeIfAbsent(p.getUniqueId(), u -> new Rec());
        r.name = p.getName();
        long now = System.currentTimeMillis();
        if (r.firstSeen == 0) {
            r.firstSeen = now;
        }
        r.lastSeen = now;
        InetAddress ip = RealIp.inet(p);
        if (ip != null && !ip.isLoopbackAddress() && !ip.isSiteLocalAddress() && !ip.isLinkLocalAddress() && !ip.isAnyLocalAddress()) {
            r.ipTag = tag(ip.getHostAddress());
        }
    }

    private void action(Player p) {
        Rec r = recs.computeIfAbsent(p.getUniqueId(), u -> new Rec());
        long now = System.currentTimeMillis();
        r.actions++;
        r.minuteActions++;
        if (r.lastAction > 0) {
            long gap = now - r.lastAction;
            if (gap > 0 && gap < 10_000) {
                r.gaps[r.gapPos] = gap;
                r.gapPos = (r.gapPos + 1) % r.gaps.length;
                r.gapCount = Math.min(r.gapCount + 1, r.gaps.length);
            }
        }
        r.lastAction = now;
    }

    // ------------------------------------------------------------------ sampling (main thread, every 30 s)

    private void tick() {
        long now = System.currentTimeMillis();
        if (!scanned) {
            scanned = true;
            scanOffline();
        }
        long minute = now / 60_000L;
        boolean rolled = minute != lastMinute;
        long steps = Math.min(60, Math.max(0, minute - lastMinute));
        lastMinute = minute;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Rec r = recs.computeIfAbsent(p.getUniqueId(), u -> new Rec());
            r.name = p.getName();
            r.lastSeen = now;
            if (r.firstSeen == 0) {
                r.firstSeen = now;
            }
            capture(r, plugin.profiles().get(p.getUniqueId()), true);
        }
        if (rolled) {
            for (Rec r : recs.values()) {
                roll(r, minute, steps);
            }
        }
        ticks++;
        String board = board(now);
        String state = ticks % 10 == 0 ? state() : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            write("board.json", board);
            if (state != null) {
                write("tally.json", state);
            }
        });
    }

    private void capture(Rec r, Profile profile, boolean rate) {
        if (profile == null) {
            return;
        }
        synchronized (profile) {
            r.epoch = profile.resets;
            r.sbXp = Skills.skyblockXp(profile);
            r.level = r.sbXp / 100;
            for (Skills.Skill s : Skills.Skill.values()) {
                r.xp[s.ordinal()] = Skills.xp(profile, s);
            }
        }
    }

    /** Called once per new minute (steps = minutes passed): pushes the last minute's XP and action count. */
    private void roll(Rec r, long minute, long steps) {
        int slot = (int) (minute % 60);
        int slot24 = (int) (minute % 1440);
        for (Skills.Skill s : Skills.Skill.values()) {
            int i = s.ordinal();
            long gained = r.havePrev ? Math.max(0, r.xp[i] - r.prevXp[i]) : 0;
            r.prevXp[i] = r.xp[i];
            for (long k = 1; k < steps; k++) {
                r.xpMin[i][(int) Math.floorMod(minute - k, 60L)] = 0;
            }
            r.xpMin[i][slot] = gained;
        }
        r.havePrev = true;
        for (long k = 1; k < steps; k++) {
            r.actMin[(int) Math.floorMod(minute - k, 60L)] = 0;
            r.active24[(int) Math.floorMod(minute - k, 1440L)] = false;
        }
        r.actMin[slot] = r.minuteActions;
        r.active24[slot24] = r.minuteActions > 0;
        r.minuteActions = 0;
    }

    private void scanOffline() {
        File players = new File(plugin.getDataFolder(), "players");
        File[] files = players.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        int added = 0;
        for (File f : files) {
            UUID uuid;
            try {
                uuid = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Rec r = recs.computeIfAbsent(uuid, u -> new Rec());
            if (r.name == null || r.name.isBlank()) {
                String n = Bukkit.getOfflinePlayer(uuid).getName();
                r.name = n == null ? "" : n;
            }
            if (Bukkit.getPlayer(uuid) == null) {
                capture(r, plugin.profiles().get(uuid), false);
            }
            added++;
        }
        plugin.getLogger().info("Event tally: " + added + " profile(s) indexed for the website leaderboard.");
    }

    // ------------------------------------------------------------------ output

    private static final String[] DEFAULT_EXCLUDED_WALLETS = {
            "0x2Ba34FC1479d17A7B1F6539b73D624d4143b848E",
            "0x9EbD5ED2BD30F943fF9ffEE6443a4963aaD966d6"
    };

    private java.util.Set<String> devNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String n : plugin.getConfig().getStringList("dev-accounts")) {
            if (n != null && !n.isBlank()) {
                names.add(n.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private java.util.Set<String> excludedWallets() {
        java.util.Set<String> wallets = new java.util.HashSet<>();
        for (String a : DEFAULT_EXCLUDED_WALLETS) {
            wallets.add(a.toLowerCase(Locale.ROOT));
        }
        for (String a : plugin.getConfig().getStringList("event-excluded-wallets")) {
            if (a != null && !a.isBlank()) {
                wallets.add(a.trim().toLowerCase(Locale.ROOT));
            }
        }
        return wallets;
    }

    private boolean isDevName(Rec r, java.util.Set<String> devs) {
        return r.name != null && !r.name.isBlank() && devs.contains(r.name.toLowerCase(Locale.ROOT));
    }

    /** Dev-accounts and owner/admin purses never take event prizes. */
    private boolean isExcluded(UUID uuid, Rec r, java.util.Set<String> devs, java.util.Set<String> wallets) {
        if (isDevName(r, devs)) {
            return true;
        }
        Profile profile = plugin.profiles().get(uuid);
        if (profile == null || profile.wallet == null || profile.wallet.isBlank()) {
            return false;
        }
        return wallets.contains(profile.wallet.trim().toLowerCase(Locale.ROOT));
    }

    private String board(long now) {
        java.util.Set<String> devs = devNames();
        java.util.Set<String> wallets = excludedWallets();
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("at", now);
        root.addProperty("tx", txAll.get());
        root.add("txBy", counts(txBy));
        JsonArray list = new JsonArray();
        for (var e : recs.entrySet()) {
            Rec r = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.getKey().toString());
            o.addProperty("name", r.name == null ? "" : r.name);
            o.addProperty("dev", isDevName(r, devs));
            o.addProperty("excluded", isExcluded(e.getKey(), r, devs, wallets));
            o.addProperty("online", Bukkit.getPlayer(e.getKey()) != null);
            o.addProperty("level", r.level);
            o.addProperty("sbXp", r.sbXp);
            o.addProperty("epoch", r.epoch);
            JsonObject skills = new JsonObject();
            JsonObject hour = new JsonObject();
            for (Skills.Skill s : Skills.Skill.values()) {
                String k = s.name().toLowerCase(Locale.ROOT);
                skills.addProperty(k, r.xp[s.ordinal()]);
                long sum = 0;
                for (long v : r.xpMin[s.ordinal()]) {
                    sum += v;
                }
                hour.addProperty(k, sum);
            }
            o.add("skills", skills);
            o.addProperty("tx", r.tx.get());
            o.add("txBy", counts(r.txBy));
            o.addProperty("actions", r.actions);
            o.addProperty("firstSeen", r.firstSeen);
            o.addProperty("lastSeen", r.lastSeen);
            if (r.ipTag != null) {
                o.addProperty("ipTag", r.ipTag);
            }
            JsonObject rate = new JsonObject();
            rate.add("xpHour", hour);
            int active24 = 0;
            for (boolean b : r.active24) {
                if (b) {
                    active24++;
                }
            }
            rate.addProperty("active24h", active24);
            int active60 = 0;
            double sum = 0;
            double sq = 0;
            for (int v : r.actMin) {
                if (v > 0) {
                    active60++;
                }
                sum += v;
                sq += (double) v * v;
            }
            rate.addProperty("active60", active60);
            double mean = sum / 60.0;
            rate.addProperty("steadyCv", mean > 0 ? round(Math.sqrt(Math.max(0, sq / 60.0 - mean * mean)) / mean) : -1);
            if (r.gapCount >= 100) {
                double gs = 0;
                double gq = 0;
                for (int i = 0; i < r.gapCount; i++) {
                    gs += r.gaps[i];
                    gq += (double) r.gaps[i] * r.gaps[i];
                }
                double gm = gs / r.gapCount;
                rate.addProperty("gapCv", round(Math.sqrt(Math.max(0, gq / r.gapCount - gm * gm)) / gm));
                rate.addProperty("gapMs", Math.round(gm));
            }
            rate.addProperty("gapN", r.gapCount);
            o.add("rate", rate);
            list.add(o);
        }
        root.add("players", list);
        return root.toString();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static JsonObject counts(Map<String, AtomicLong> m) {
        JsonObject o = new JsonObject();
        for (var e : new java.util.TreeMap<>(m).entrySet()) {
            o.addProperty(e.getKey(), e.getValue().get());
        }
        return o;
    }

    private String state() {
        JsonObject root = new JsonObject();
        root.addProperty("salt", salt);
        root.addProperty("tx", txAll.get());
        root.add("txBy", counts(txBy));
        JsonObject players = new JsonObject();
        for (var e : recs.entrySet()) {
            Rec r = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("name", r.name == null ? "" : r.name);
            o.addProperty("tx", r.tx.get());
            o.add("txBy", counts(r.txBy));
            o.addProperty("actions", r.actions);
            o.addProperty("firstSeen", r.firstSeen);
            o.addProperty("lastSeen", r.lastSeen);
            if (r.ipTag != null) {
                o.addProperty("ipTag", r.ipTag);
            }
            players.add(e.getKey().toString(), o);
        }
        root.add("players", players);
        return root.toString();
    }

    private void load() {
        File f = new File(dir, "tally.json");
        try {
            if (f.exists()) {
                JsonObject root = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("salt")) {
                    salt = root.get("salt").getAsString();
                }
                txAll.set(root.has("tx") ? root.get("tx").getAsLong() : 0);
                if (root.has("txBy")) {
                    for (var e : root.getAsJsonObject("txBy").entrySet()) {
                        txBy.put(e.getKey(), new AtomicLong(e.getValue().getAsLong()));
                    }
                }
                if (root.has("players")) {
                    for (var e : root.getAsJsonObject("players").entrySet()) {
                        JsonObject o = e.getValue().getAsJsonObject();
                        Rec r = new Rec();
                        r.name = o.has("name") ? o.get("name").getAsString() : "";
                        r.tx.set(o.has("tx") ? o.get("tx").getAsLong() : 0);
                        if (o.has("txBy")) {
                            for (var t : o.getAsJsonObject("txBy").entrySet()) {
                                r.txBy.put(t.getKey(), new AtomicLong(t.getValue().getAsLong()));
                            }
                        }
                        r.actions = o.has("actions") ? o.get("actions").getAsLong() : 0;
                        r.firstSeen = o.has("firstSeen") ? o.get("firstSeen").getAsLong() : 0;
                        r.lastSeen = o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0;
                        r.ipTag = o.has("ipTag") ? o.get("ipTag").getAsString() : null;
                        recs.put(UUID.fromString(e.getKey()), r);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Event tally: could not read tally.json (" + e.getMessage() + "); starting fresh counters.");
        }
        if (salt == null || salt.isBlank()) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            salt = HexFormat.of().formatHex(b);
        }
    }

    private String tag(String ip) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void write(String name, String body) {
        try {
            File tmp = new File(dir, name + ".tmp");
            Files.writeString(tmp.toPath(), body, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(new File(dir, name).toPath(), body, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // next tick tries again
            }
        }
    }
}
