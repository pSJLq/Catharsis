package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HubManager implements Listener {
    static final String WORLD_NAME = "hub";
    static final int HUB_LAYOUT = 11;
    static final String BUILD_FILE = "hub/hub11_1.sbh";
    /** Fallback colosseum gate (outside its south gate) when the build META has no "gate". */
    static final double GATE_X = 165.5, GATE_Y = 101, GATE_Z = 54.5;
    /** The island spans these bounds (the build file is cleared and pasted inside them). */
    static final int MIN_X = -600, MAX_X = 600, MIN_Z = -510, MAX_Z = 510, MIN_Y = -64, MAX_Y = 320;
    /** District ids the plugin relies on; display names come from META / hub/zones.json. */
    static final List<String> DISTRICT_IDS = List.of("plaza", "relics", "colosseum", "farm", "mine", "barrows",
            "spire", "ruins", "forest", "mountain", "lake", "wharf", "crypts");
    private static final int Y = IslandManager.GROUND_Y;
    private static final long REGEN_TICKS = 500L;

    private final SomniaSkyblockPlugin plugin;
    private final Sparkles sparkles;
    private boolean stopping;
    private final Map<Long, Material> ores = new HashMap<>();
    private final Map<UUID, Long> portalAt = new HashMap<>();
    private World world;
    private Zones zones = new Zones();
    private final Map<UUID, String> zoneOf = new HashMap<>();
    private double gateX = GATE_X, gateY = GATE_Y, gateZ = GATE_Z;
    /** META "spawn" {x,y,z,yaw,pitch}; null = built-in plaza spawn. */
    private double[] spawnAt;
    /** META landmarks.colosseum: arena floor centre (standing height); null = Arena's built-in centre. */
    private double[] arenaAt;
    private final List<Object[]> checks = new ArrayList<>();
    private volatile boolean pasting;
    private volatile String pasteStatus = "idle";

    HubManager(SomniaSkyblockPlugin plugin) {
        this.sparkles = new Sparkles(plugin);
        this.plugin = plugin;
    }

    void load() {
        world = plugin.getServer().getWorld(WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.generator(new VoidGenerator());
            creator.generateStructures(false);
            world = creator.createWorld();
        }
        if (world == null) {
            throw new IllegalStateException("Could not create the hub world");
        }
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.SPAWN_MONSTERS, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.SPAWN_RADIUS, 0);
        world.setGameRule(GameRule.LOCATOR_BAR, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_VINES_SPREAD, false);
        world.setSpawnFlags(false, false);
        world.setDifficulty(Difficulty.EASY);
        world.setStorm(false);
        world.setThundering(false);
        if (plugin.getConfig().getInt("hub-layout", 0) < HUB_LAYOUT) {
            world.setTime(1000);
        }
        world.setClearWeatherDuration(Integer.MAX_VALUE);
        world.setSpawnLocation(spawn());
        boolean place = plugin.getConfig().getInt("hub-layout", 0) < HUB_LAYOUT;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::populate, 40L, 200L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> sparkles.removeLeftovers(), 60L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> fillPortals(false), 1200L, 1200L);
        java.io.File build = new java.io.File(plugin.getDataFolder(), BUILD_FILE);
        if (!build.isFile()) {
            plugin.getLogger().severe("Hub build file is missing: " + build + ". The old hub stays.");
            return;
        }
        if (!place) {
            try {
                HubPaste.Result known = HubPaste.read(world, build, false, plugin.getLogger());
                buildSigns.clear();
                buildSigns.addAll(known.signs);
                markOres(known.ores, false);
                useZones(known.meta);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    fillPortals(true);
                    clearInfoSigns();
                });
            } catch (java.io.IOException | RuntimeException e) {
                plugin.getLogger().severe("Hub build file could not be read: " + e.getMessage());
            }
            return;
        }
        startPaste(build);
    }

    boolean pasting() {
        return pasting;
    }

    String pasteStatus() {
        return pasteStatus;
    }

    /**
     * Clears the old hub and pastes the build file a block budget per tick (config hub-paste-blocks-per-tick,
     * hub-paste-ms-per-tick), so the watchdog never sees a long tick. Players are kept out of the hub meanwhile.
     */
    private void startPaste(java.io.File build) {
        HubPaste.Plan plan;
        try {
            plan = HubPaste.plan(build, plugin.getLogger());
        } catch (java.io.IOException | RuntimeException e) {
            plugin.getLogger().severe("Hub paste failed: " + e);
            return;
        }
        pasting = true;
        for (Player player : world.getPlayers()) {
            evict(player);
        }
        long budget = Math.max(1000, plugin.getConfig().getLong("hub-paste-blocks-per-tick", 60000));
        long millis = Math.max(5, plugin.getConfig().getLong("hub-paste-ms-per-tick", 30));
        plugin.getLogger().info("Pasting the hub from " + build.getName() + ": " + plan.total + " blocks, up to "
                + budget + " blocks / " + millis + " ms per tick.");
        List<long[]> columns = new ArrayList<>();
        for (int cx = Math.floorDiv(MIN_X, 16); cx <= Math.floorDiv(MAX_X, 16); cx++) {
            for (int cz = Math.floorDiv(MIN_Z, 16); cz <= Math.floorDiv(MAX_Z, 16); cz++) {
                if (world.isChunkGenerated(cx, cz)) {
                    columns.add(new long[]{cx, cz});
                }
            }
        }
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                doomed.add(entity);
            }
        }
        doomed.forEach(Entity::remove);
        long startedAt = System.currentTimeMillis();
        long[] cleared = {0};
        int[] column = {0};
        boolean[] clearing = {true};
        long[] lastLog = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            if (clearing[0]) {
                long done = 0;
                while (column[0] < columns.size() && done < budget && System.nanoTime() < deadline) {
                    long[] c = columns.get(column[0]++);
                    done += clearChunk((int) c[0], (int) c[1]);
                }
                cleared[0] += done;
                pasteStatus = "clearing " + column[0] + "/" + columns.size() + " chunks";
                if (column[0] >= columns.size()) {
                    clearing[0] = false;
                    plugin.getLogger().info("Old hub blocks cleared: " + cleared[0] + " in " + (System.currentTimeMillis() - startedAt) + " ms.");
                }
            } else if (plan.step(world, budget, deadline)) {
                task.cancel();
                pasteStatus = "done";
                plugin.getLogger().info("Hub paste took " + (System.currentTimeMillis() - startedAt) + " ms, "
                        + plan.result.badStates + " unknown block states.");
                finishPaste(plan.result);
                return;
            } else {
                pasteStatus = String.format(java.util.Locale.ROOT, "pasting %.0f%%", plan.progress() * 100);
            }
            if (System.currentTimeMillis() - lastLog[0] > 5000) {
                lastLog[0] = System.currentTimeMillis();
                plugin.getLogger().info("Hub paste: " + pasteStatus + " (" + plan.result.placed + "/" + plan.total + " blocks)");
            }
        }, 1L, 1L);
    }

    private long clearChunk(int cx, int cz) {
        org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
        org.bukkit.ChunkSnapshot snap = chunk.getChunkSnapshot(false, false, false);
        int low = Math.max(MIN_Y, world.getMinHeight());
        int high = Math.min(MAX_Y, world.getMaxHeight() - 1);
        int minSection = Math.floorDiv(world.getMinHeight(), 16);
        long cleared = 0;
        for (int sy = Math.floorDiv(low, 16); sy <= Math.floorDiv(high, 16); sy++) {
            if (snap.isSectionEmpty(sy - minSection)) {
                continue;
            }
            for (int y = Math.max(low, sy * 16); y <= Math.min(high, sy * 16 + 15); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!snap.getBlockType(x, y, z).isAir()) {
                            chunk.getBlock(x, y, z).setType(Material.AIR, false);
                            cleared++;
                        }
                    }
                }
            }
        }
        return cleared;
    }

    private void finishPaste(HubPaste.Result pasted) {
        markOres(pasted.ores, true);
        useZones(pasted.meta);
        clearInfoSigns();
        fillPortals(true);
        world.setSpawnLocation(spawn());
        pasting = false;
        if (!intact()) {
            plugin.getLogger().severe("Hub layout was not saved. It will try again next start.");
            return;
        }
        resetSeen();
        plugin.getConfig().set("hub-layout", HUB_LAYOUT);
        plugin.saveConfig();
        plugin.getLogger().info("Hub layout " + HUB_LAYOUT + " pasted (" + pasted.placed + " blocks).");
        plugin.getServer().broadcast(Component.text("The hub is open again: /hub", NamedTextColor.GREEN));
    }

    /** Sign positions of the build file. Signs are not used in the game at all, so every one of them is removed. */
    private final List<int[]> buildSigns = new ArrayList<>();
    private int signsRemoved;

    /** Removes the build's signs and any sign block in the loaded hub chunks (blocks become air, no block updates). */
    private void clearInfoSigns() {
        if (world == null) {
            return;
        }
        int build = 0;
        for (int[] at : buildSigns) {
            if (world.isChunkLoaded(at[0] >> 4, at[2] >> 4) || world.loadChunk(at[0] >> 4, at[2] >> 4, false)) {
                if (removeSign(world.getBlockAt(at[0], at[1], at[2]))) {
                    build++;
                }
            }
        }
        int loose = 0;
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            loose += removeSigns(chunk);
        }
        plugin.getLogger().info("Hub signs: " + buildSigns.size() + " in the build file, removed now: " + build + " build + " + loose
                + " other (total this run: " + signsRemoved + ").");
    }

    /** Every sign block (standing, wall, hanging) in this chunk becomes air. */
    private int removeSigns(org.bukkit.Chunk chunk) {
        int done = 0;
        for (org.bukkit.block.BlockState state : chunk.getTileEntities(block -> org.bukkit.Tag.ALL_SIGNS.isTagged(block.getType()), false)) {
            if (removeSign(state.getBlock())) {
                done++;
            }
        }
        return done;
    }

    /** Regrow spirits of older builds (they stayed for minutes and piled up) go away when their chunk's entities load. */
    @EventHandler
    public void onSpiritChunk(org.bukkit.event.world.EntitiesLoadEvent event) {
        sparkles.removeLeftovers(event.getEntities(), "at chunk " + event.getChunk().getX() + "," + event.getChunk().getZ() + " in " + event.getWorld().getName());
    }

    @EventHandler
    public void onHubChunk(org.bukkit.event.world.ChunkLoadEvent event) {
        if (world == null || pasting || !event.getWorld().equals(world)) {
            return;
        }
        int done = removeSigns(event.getChunk());
        if (done > 0) {
            plugin.getLogger().info("Hub chunk " + event.getChunk().getX() + "," + event.getChunk().getZ() + ": removed " + done + " sign(s) (total this run: " + signsRemoved + ").");
        }
    }

    /** True when the block was a sign and is now air. Logs what it said. */
    private boolean removeSign(Block block) {
        if (!org.bukkit.Tag.ALL_SIGNS.isTagged(block.getType())) {
            return false;
        }
        StringBuilder was = new StringBuilder();
        if (block.getState() instanceof org.bukkit.block.Sign sign) {
            for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
                for (int i = 0; i < 4; i++) {
                    String line = PlainTextComponentSerializer.plainText().serialize(sign.getSide(side).line(i));
                    if (line != null && !line.isBlank()) {
                        was.append(was.isEmpty() ? "" : " | ").append(line);
                    }
                }
            }
        }
        block.setType(Material.AIR, false);
        signsRemoved++;
        plugin.getLogger().info("Removed hub sign at " + block.getX() + "," + block.getY() + "," + block.getZ() + (was.isEmpty() ? " (blank)." : " (was: " + was + ")."));
        return true;
    }

    /** Sends a player out of the hub while it is being pasted. */
    private void evict(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.islandReady) {
            plugin.islands().teleport(player, profile);
        } else {
            player.teleport(plugin.islands().world().getSpawnLocation());
        }
        player.sendMessage(Component.text("The hub is being rebuilt (" + pasteStatus + "). /hub works again in a few minutes.", NamedTextColor.YELLOW));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPasteGuard(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (pasting && event.getTo() != null && isHub(event.getTo().getWorld()) && !isHub(event.getFrom().getWorld())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("The hub is being rebuilt (" + pasteStatus + "). Try again in a few minutes.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPasteJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pasting && player.isOnline() && isHub(player.getWorld())) {
                evict(player);
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPasteMove(PlayerMoveEvent event) {
        if (pasting && isHub(event.getPlayer().getWorld())) {
            evict(event.getPlayer());
        }
    }

    private static int metaVersion(String json) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return root.has("layout") ? root.get("layout").getAsInt() : root.has("version") ? root.get("version").getAsInt() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Reads "gate": [x, y, z] and "checks": [[x, y, z, "block"], ...] from META, falling back to the built-in values. */
    private void readGateAndChecks(String meta) {
        gateX = GATE_X;
        gateY = GATE_Y;
        gateZ = GATE_Z;
        checks.clear();
        try {
            com.google.gson.JsonObject root = meta == null || meta.isEmpty() ? new com.google.gson.JsonObject()
                    : com.google.gson.JsonParser.parseString(meta).getAsJsonObject();
            spawnAt = null;
            if (root.has("spawn") && root.get("spawn").isJsonObject()) {
                com.google.gson.JsonObject sp = root.getAsJsonObject("spawn");
                spawnAt = new double[]{sp.get("x").getAsDouble(), sp.get("y").getAsDouble(), sp.get("z").getAsDouble(),
                        sp.has("yaw") ? sp.get("yaw").getAsDouble() : 180, sp.has("pitch") ? sp.get("pitch").getAsDouble() : 0};
            }
            if (root.has("bounds")) {
                plugin.getLogger().info("Hub META bounds: " + root.get("bounds") + " (paste/clear uses X " + MIN_X + ".." + MAX_X + ", Z " + MIN_Z + ".." + MAX_Z + ", Y " + MIN_Y + ".." + MAX_Y + ")");
            }
            arenaAt = null;
            com.google.gson.JsonObject lm = root.has("landmarks") && root.get("landmarks").isJsonObject() ? root.getAsJsonObject("landmarks") : new com.google.gson.JsonObject();
            com.google.gson.JsonObject old = root.has("landmarks_v9") && root.get("landmarks_v9").isJsonObject() ? root.getAsJsonObject("landmarks_v9") : lm;
            if (lm.has("arena_center") && lm.get("arena_center").isJsonObject() && lm.getAsJsonObject("arena_center").has("x")) {
                com.google.gson.JsonObject a = lm.getAsJsonObject("arena_center");
                arenaAt = new double[]{a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble()};
            } else if (old.has("colosseum") && old.get("colosseum").isJsonObject() && old.getAsJsonObject("colosseum").has("center")) {
                com.google.gson.JsonObject col = old.getAsJsonObject("colosseum");
                com.google.gson.JsonArray c = col.getAsJsonArray("center");
                double floor = col.has("arena_y") ? col.get("arena_y").getAsDouble() : c.get(1).getAsDouble();
                arenaAt = new double[]{c.get(0).getAsDouble() + 0.5, floor + 1, c.get(2).getAsDouble() + 0.5};
            }
            readLandmarks(lm);
            if (root.has("gate")) {
                com.google.gson.JsonArray g = root.getAsJsonArray("gate");
                gateX = g.get(0).getAsDouble();
                gateY = g.get(1).getAsDouble();
                gateZ = g.get(2).getAsDouble();
            }
            if (root.has("checks")) {
                for (com.google.gson.JsonElement e : root.getAsJsonArray("checks")) {
                    com.google.gson.JsonArray c = e.getAsJsonArray();
                    Material type = Material.matchMaterial(c.get(3).getAsString());
                    if (type == null) {
                        plugin.getLogger().warning("Hub META check uses an unknown block: " + c.get(3).getAsString());
                        continue;
                    }
                    checks.add(new Object[]{c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt(), type});
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Hub META gate/checks could not be read, using defaults: " + e.getMessage());
        }
        if (checks.isEmpty()) {
            plugin.getLogger().severe("Hub META has no \"checks\"; the layout cannot be verified.");
        }
        plugin.getLogger().info("Hub gate " + gateX + "," + gateY + "," + gateZ + ", " + checks.size() + " layout checks.");
    }

    /** Arena floor centre (where a player stands) from META landmarks, or null. */
    Location arenaCenter() {
        return arenaAt == null || world == null ? null : new Location(world, arenaAt[0], arenaAt[1], arenaAt[2], 180f, 0f);
    }

    /** Colosseum gate from META ("gate": [x, y, z]) or the fallback. */
    Location gate() {
        return new Location(world, gateX, gateY, gateZ, 180f, 0f);
    }

    /** Display name of a district id from META / zones.json, or a fallback. */
    String name(String id) {
        return zones == null ? Zones.fallbackName(id) : zones.name(id);
    }

    World world() {
        return world;
    }

    Zones zones() {
        return zones;
    }

    private String buildMeta = "";

    private void useZones(String meta) {
        buildMeta = meta == null ? "" : meta;
        // Spawn regions/zones can be maintained outside the build file: hub/zones.json wins when present.
        // Gate and layout checks always come from the build file itself.
        readGateAndChecks(meta);
        java.io.File override = new java.io.File(plugin.getDataFolder(), "hub/zones.json");
        if (override.isFile()) {
            try {
                String text = java.nio.file.Files.readString(override.toPath());
                int want = metaVersion(meta);
                int have = metaVersion(text);
                if (want >= 0 && want != have) {
                    plugin.getLogger().info("hub/zones.json is for layout " + have + " (build file is layout " + want + "): ignored, using the build file's zones.");
                } else {
                    meta = text;
                    plugin.getLogger().info("Hub zones from hub/zones.json (overrides the build file).");
                }
            } catch (java.io.IOException | RuntimeException e) {
                plugin.getLogger().warning("hub/zones.json could not be read, using the build file: " + e.getMessage());
            }
        }
        try {
            zones = Zones.parse(meta, plugin.getLogger());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Hub zones could not be read: " + e);
            zones = new Zones();
        }
        plugin.getLogger().info("Hub zones: " + zones.districts().size() + " districts, " + zones.points().size() + " mob spawn points.");
        readMetaHolos(buildMeta);
        List<String> missing = new ArrayList<>();
        for (String id : DISTRICT_IDS) {
            if (!zones.has(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("Hub META has no district/area for: " + String.join(", ", missing) + " (fallback names are used).");
        }
        Sidebar.zone = player -> {
            if (!isHub(player.getWorld())) {
                return null;
            }
            String[] here = zones.at(player.getLocation());
            return here == null ? name("plaza") : here[1];
        };
        Sidebar.zoneCombat = player -> {
            if (!isHub(player.getWorld())) {
                return false;
            }
            String[] here = zones.at(player.getLocation());
            return here != null && "combat".equals(here[2]);
        };
    }

    /** Warp point for a district (its road gateway), or null. */
    /** The nearest hub NPC with this role (bazaar, auction, merchant, blacksmith, quest, ...), or null. */
    Location npcNear(Player player, String role) {
        if (world == null || !isHub(player.getWorld())) {
            return null;
        }
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<UUID, Npc> e : npcByEntity.entrySet()) {
            Npc npc = e.getValue();
            String r = npc.kind() != null ? npc.kind() : roleOf(npc.id());
            if (!role.equals(r)) {
                continue;
            }
            Entity body = world.getEntity(e.getKey());
            Location at = body != null && body.isValid() ? body.getLocation() : new Location(world, npc.x(), npc.y(), npc.z());
            double d = at.distanceSquared(player.getLocation());
            if (d < bestD) {
                bestD = d;
                best = at;
            }
        }
        return best;
    }

    Location warpPoint(String id) {
        if ("plaza".equals(id)) {
            return spawn();
        }
        int[] w = zones == null ? null : zones.warp(id);
        return w == null || world == null ? null : new Location(world, w[0] + 0.5, w[1], w[2] + 0.5);
    }

    /** Zone id the player is in, or null outside the hub. */
    String zoneId(Player player) {
        if (!isHub(player.getWorld())) {
            return null;
        }
        String[] here = zones.at(player.getLocation());
        return here == null ? null : here[0];
    }

    boolean isHub(World other) {
        return world != null && other != null && other.equals(world);
    }

    Location spawn() {
        if (spawnAt != null) {
            return new Location(world, spawnAt[0], spawnAt[1], spawnAt[2], (float) spawnAt[3], (float) spawnAt[4]);
        }
        return new Location(world, 0.5, 108, 70.5, 180f, 8f);
    }

    void teleport(Player player) {
        player.teleport(spawn());
        player.setFallDistance(0);
        player.setRespawnLocation(spawn(), true);
    }

    void greet(Player player, Profile profile) {
        if (profile.seenHub) {
            return;
        }
        profile.seenHub = true;
        plugin.profiles().save(profile);
        player.sendMessage(Component.text("Welcome to " + name("plaza") + ", the Somnia SkyBlock hub.", NamedTextColor.GOLD));
        player.sendMessage(Component.text("In " + name("plaza") + ": Bazaar, Auction House, Bank, Merchant and Blacksmith. " + name("relics") + " is just east of the town.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("The portal north of the fountain leads to your Private Island. /warp takes you to the other districts.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Peaceful: " + name("farm") + ", " + name("forest") + ", " + name("mountain") + " with the " + name("spire") + ", " + name("lake") + ", " + name("wharf") + ".", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Combat: " + name("barrows") + ", " + name("ruins") + " and " + name("crypts") + " below " + name("mine") + ". The " + name("colosseum") + " is sealed until dungeons open.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Follow the roads out of " + name("plaza") + ". /quest shows your path, /is takes you home.", NamedTextColor.DARK_GRAY));
    }

    /** A hub resource block was harvested (after the onchain bag write in word mode): XP, collection, quest, regen chain. */
    void cleared(Location location, Player player) {
        if (!isHub(location.getWorld())) {
            return;
        }
        Block block = location.getBlock();
        long k = key(block);
        String data = breaking.remove(k);
        Material spot = ores.get(k);
        Material type = data != null ? Material.matchMaterial(data.replaceAll("\\[.*", "")) : spot;
        if (type == null) {
            return;
        }
        if (player != null && player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            skill(player, type);
        }
        String original = data != null ? data : grown(spot).getAsString();
        if (isFlora(type)) {
            // Tall grass, large fern, tall flowers: the regen chain sits on the lower half and regrows both halves.
            try {
                org.bukkit.block.data.BlockData d = org.bukkit.Bukkit.createBlockData(original);
                if (d instanceof org.bukkit.block.data.Bisected half && half.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
                    half.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
                    block = block.getRelative(BlockFace.DOWN);
                    original = half.getAsString();
                }
            } catch (IllegalArgumentException ignored) {
                // keep as is
            }
        }
        startRegen(block, original, type);
    }

    void cleared(Location location) {
        cleared(location, null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreakProtect(BlockBreakEvent event) {
        if (!isHub(event.getBlock().getWorld())) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type == Material.OBSIDIAN
                || type == Material.NETHER_PORTAL
                || type == Material.OAK_SIGN
                || type == Material.OAK_WALL_SIGN
                || type == Material.FURNACE
                || type == Material.CRAFTING_TABLE
                || type == Material.GOLD_BLOCK) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOre(BlockBreakEvent event) {
        if (!isHub(event.getBlock().getWorld()) || !breaking.containsKey(key(event.getBlock())) && !ores.containsKey(key(event.getBlock()))) {
            return;
        }
        Player player = event.getPlayer();
        // Word mode: WordChain calls cleared() after the bag write. Without it (or for offchain players) do it here.
        if (!plugin.wordMode() || plugin.hold().offchain(player)) {
            cleared(event.getBlock().getLocation(), player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        Entity hurt = event.getEntity();
        if (!isHub(hurt.getWorld()) || !(hurt instanceof Villager || hurt.getScoreboardTags().contains(Npcs.TAG))) {
            return;
        }
        event.setCancelled(true);
        // Left-click talks too.
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player player) {
            Npc npc = npcByEntity.get(hurt.getUniqueId());
            if (npc != null) {
                talk(player, npc);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTalk(PlayerInteractEntityEvent event) {
        Npc npc = npcByEntity.get(event.getRightClicked().getUniqueId());
        if (npc == null) {
            if (event.getRightClicked() instanceof Villager && isHub(event.getRightClicked().getWorld())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
            talk(event.getPlayer(), npc);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTalkAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        Npc npc = npcByEntity.get(event.getRightClicked().getUniqueId());
        if (npc != null) {
            event.setCancelled(true);
            if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                talk(event.getPlayer(), npc);
            }
        }
    }

    /** Left-click on an invulnerable NPC never reaches EntityDamageEvent, so catch the attack itself. */
    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        Npc npc = npcByEntity.get(event.getAttacked().getUniqueId());
        if (npc != null) {
            event.setCancelled(true);
            talk(event.getPlayer(), npc);
        }
    }

    private final Map<UUID, Npc> npcByEntity = new HashMap<>();
    private final Map<UUID, Long> talkedAt = new HashMap<>();

    private void talk(Player player, Npc npc) {
        long now = System.currentTimeMillis();
        Long last = talkedAt.put(player.getUniqueId(), now);
        if (last != null && now - last < 500) {
            return;
        }
        String name = npc.name();
        plugin.getLogger().info("NPC " + npc.id() + " talked to " + player.getName());
        if (npc.kind() != null) {
            talkRole(player, npc);
            return;
        }
        switch (name) {
            case "Bazaar Broker" -> plugin.market().openBazaar(player);
            case "Auctioneer" -> plugin.market().openAuctions(player);
            case "Banker", "Archivist" -> plugin.market().openBank(player);
            case "Arena Warden" -> {
                if (npc.warp()) {
                    toColosseum(player);
                } else {
                    plugin.arena().open(player);
                }
            }
            case "Fisher Hale", "Farmer Odo", "Woodward Fenn" -> plugin.market().openMerchant(player, name);
            case "Arcanist Ysolde" -> player.sendMessage(Component.text("[NPC] Arcanist Ysolde: Somnia's VRF decides every rare Colosseum drop, and no one, not even me, can bend the dice. Win ranked runs and check /realm for the proof.", NamedTextColor.LIGHT_PURPLE));
            case "Island Keeper" -> {
                Profile profile = plugin.profiles().get(player.getUniqueId());
                plugin.islands().ensureIsland(profile);
                plugin.islands().teleport(player, profile);
                player.sendMessage(Component.text("You are on your island. /hub brings you back.", NamedTextColor.GREEN));
            }
            case "Blacksmith" -> plugin.market().openShop(player);
            case "Merchant" -> plugin.market().openMerchant(player, name);
            case "Foreman Brak" -> player.sendMessage(Component.text("[NPC] Foreman Brak: " + name("mine") + "'s veins grow back. Coal and iron in the galleries, gold up on " + name("mountain") + ". The way down leads to " + name("crypts") + ". Bring a sword.", NamedTextColor.YELLOW));
            default -> player.sendMessage(Component.text("[NPC] " + name + ": Hello!", NamedTextColor.YELLOW));
        }
    }

    /** META landmark NPCs: the role picks the backend; roles without one get a placeholder line. */
    private void talkRole(Player player, Npc npc) {
        String who = "[NPC] " + npc.name() + ": ";
        switch (npc.kind()) {
            case "bazaar" -> plugin.market().openBazaar(player);
            case "auction" -> plugin.market().openAuctions(player);
            case "bank" -> plugin.market().openBank(player);
            case "blacksmith" -> plugin.market().openShop(player);
            case "merchant" -> plugin.market().openMerchant(player, npc.name());
            case "island" -> {
                Profile profile = plugin.profiles().get(player.getUniqueId());
                plugin.islands().ensureIsland(profile);
                plugin.islands().teleport(player, profile);
                player.sendMessage(Component.text("You are on your island. /hub brings you back.", NamedTextColor.GREEN));
            }
            case "quest" -> questGiver(player, npc);
            case "travel" -> player.performCommand("warp");
            case "arcanist" -> player.sendMessage(Component.text(who + "Somnia's VRF decides every rare " + name("colosseum") + " drop, and no one, not even me, can bend the dice. Win ranked runs and check /realm for the proof.", NamedTextColor.LIGHT_PURPLE));
            case "guide" -> Chat.npc(plugin, player, npc.name(), List.of(
                    "Welcome to " + name("plaza") + "!",
                    "The Grand Bazaar is east of the square, the Auction House west, the Bank by the east road.",
                    "The portal north of the fountain leads to your island. /warp takes you to the districts.",
                    "The Quest Giver south of the fountain has work for newcomers."), null);
            default -> Chat.npc(plugin, player, npc.name(), List.of(PLACEHOLDER.getOrDefault(npc.id(), "I'll have more to say soon. Come back later!")), null);
        }
    }

    /** The Quest Giver: intro dialogue line by line; meeting them is an intro objective. */
    private void questGiver(Player player, Npc npc) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Quests.Step step = plugin.quests().current(profile);
        int at = step == null ? Quests.STEPS.size() : Quests.STEPS.indexOf(step);
        int meet = Quests.indexOf("talk_quest");
        List<String> lines;
        Runnable after = null;
        if (step != null && step.event().equals("talk_quest")) {
            lines = List.of("Welcome to " + name("plaza") + ", traveler!",
                    "Every Lumenholder starts small: a few crops, a little ore, a stack of logs.",
                    "Goldmeadow, " + name("mine") + " and Elderglade all grow back what you take.",
                    "Work the land for me and I'll make it worth your while.");
            after = () -> plugin.quests().hit(player, "talk_quest", 1);
        } else if (step != null && at < meet) {
            lines = List.of("Ah, a fresh face! Your island needs you first.",
                    "Finish what Wren asked: " + step.title() + ".");
        } else if (step != null) {
            lines = List.of("Your task: " + step.title() + " (" + plugin.quests().progress(profile) + ").",
                    step.hint(),
                    "Come back any time. /quest shows the whole path.");
        } else {
            lines = List.of("You have done everything I asked. Lumenhold is lucky to have you!");
        }
        Chat.npc(plugin, player, npc.name(), lines, after);
    }

    private static final Map<String, String> PLACEHOLDER = Map.ofEntries(
            Map.entry("bartender", "Welcome to the Tipsy Griffin! The taps open soon. Grab a seat in the meantime."),
            Map.entry("enchanter", "The enchanting tables are still cooling down. Come back soon."),
            Map.entry("fisherman", "Fishing on the docks is coming soon. Sell your catch to the Fish Merchant down the pier."),
            Map.entry("apothecary", "My shelves are still empty. Potion ingredients arrive soon."),
            Map.entry("florist", "Fresh flowers soon! Goldmeadow grows the best crops in the meantime."),
            Map.entry("baker", "The ovens are warming up. Wheat from Goldmeadow keeps this town fed."),
            Map.entry("mayor", "Welcome to Lumenhold. Mind the Quest Giver south of the fountain."),
            Map.entry("huntmaster", "The undead of Dusk Thicket and the wolves of Greyfang Citadel need thinning out."));

    // ===== NPCs and holograms (respawned when missing; not saved with the world) =====
    private final Map<String, UUID> spawned = new HashMap<>();

    /**
     * A hub NPC. With an anchor (a META warp id) x/z are offsets from that warp, the floor is searched near the
     * warp height and the NPC faces the warp; without one x/y/z/yaw are absolute.
     */
    private record Npc(String id, String name, double x, double y, double z, float yaw, Villager.Profession profession, String role, boolean warp, String anchor, String kind) {
        Npc(String id, String name, double x, double y, double z, float yaw, Villager.Profession profession, String role, boolean warp) {
            this(id, name, x, y, z, yaw, profession, role, warp, null, null);
        }

        Npc(String id, String name, double x, double y, double z, float yaw, Villager.Profession profession, String role, boolean warp, String anchor) {
            this(id, name, x, y, z, yaw, profession, role, warp, anchor, null);
        }
    }

    /** NPCs of the current layout: META npc_* landmarks plus the warp-anchored district NPCs. */
    private final List<Npc> npcs = new ArrayList<>();
    /** mine_cart_* landmark spots (decorative minecarts). */
    private final Map<String, double[]> carts = new java.util.LinkedHashMap<>();
    /** quest_board_1..n landmark block positions (builder signs to blank). */
    private final List<int[]> boardSigns = new ArrayList<>();
    private final java.util.Set<String> reportedSpots = new java.util.HashSet<>();

    /** Role of an npc_* landmark id; ids without a backend get a placeholder dialogue. */
    static String roleOf(String id) {
        String k = id.startsWith("npc_") ? id.substring(4) : id;
        if (k.startsWith("bazaar")) {
            return "bazaar";
        }
        if (k.startsWith("auction")) {
            return "auction";
        }
        if (k.startsWith("merchant")) {
            return "merchant";
        }
        return switch (k) {
            case "banker" -> "bank";
            case "blacksmith" -> "blacksmith";
            case "island_keeper" -> "island";
            case "quest_board" -> "quest";
            case "fast_travel" -> "travel";
            case "wizard" -> "arcanist";
            case "guide" -> "guide";
            default -> "placeholder";
        };
    }

    private void readLandmarks(com.google.gson.JsonObject lm) {
        npcs.clear();
        carts.clear();
        boardSigns.clear();
        int fromMeta = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> e : lm.entrySet()) {
            if (!e.getValue().isJsonObject() || !e.getValue().getAsJsonObject().has("x")) {
                continue;
            }
            com.google.gson.JsonObject o = e.getValue().getAsJsonObject();
            double x = o.get("x").getAsDouble(), y = o.get("y").getAsDouble(), z = o.get("z").getAsDouble();
            if (e.getKey().startsWith("npc_")) {
                String name = o.has("name_ascii") ? o.get("name_ascii").getAsString() : e.getKey().substring(4);
                float yaw = o.has("yaw") ? o.get("yaw").getAsFloat() : 0f;
                String role = roleOf(e.getKey());
                npcs.add(new Npc(e.getKey().substring(4), name, x, y, z, yaw, null, ROLE_LINE.getOrDefault(role, ""), false, null, role));
                fromMeta++;
            } else if (e.getKey().startsWith("quest_board")) {
                boardSigns.add(new int[]{(int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)});
            } else if (e.getKey().startsWith("mine_cart_")) {
                carts.put(e.getKey(), new double[]{x, y, z});
            }
        }
        if (fromMeta > 0) {
            // Layout 10+: the plaza NPCs come from META; keep only the district NPCs anchored to warps.
            java.util.Set<String> metaNames = new java.util.HashSet<>();
            for (Npc npc : npcs) {
                metaNames.add(npc.name().toLowerCase(java.util.Locale.ROOT));
            }
            for (Npc npc : NPCS) {
                if (npc.anchor() != null && ANCHORED_KEEP.contains(npc.id())) {
                    if (metaNames.contains(npc.name().toLowerCase(java.util.Locale.ROOT))) {
                        plugin.getLogger().info("Hub NPC " + npc.id() + " (" + npc.name() + ") skipped: the build has its own npc_* spot for it.");
                        continue;
                    }
                    npcs.add(npc);
                }
            }
        } else {
            npcs.addAll(NPCS);
        }
        plugin.getLogger().info("Hub NPCs: " + npcs.size() + " (" + fromMeta + " from META npc_* landmarks), " + carts.size() + " minecart spots.");
    }

    private static final java.util.Set<String> ANCHORED_KEEP = java.util.Set.of("miner", "arena", "archivist");
    private static final Map<String, String> ROLE_LINE = Map.ofEntries(
            Map.entry("bazaar", "STT market • click"), Map.entry("auction", "Auctions in STT • click"),
            Map.entry("merchant", "Sell for coins • click"), Map.entry("bank", "Relic Pool • click"),
            Map.entry("blacksmith", "Forge gear • click"), Map.entry("island", "Go to your island • click"),
            Map.entry("quest", "Quests • click"), Map.entry("travel", "Fast travel • click"),
            Map.entry("arcanist", "Somnia VRF • click"), Map.entry("guide", "New here? • click"),
            Map.entry("placeholder", "SOON"));
    /** Name colour and default skin per role (META NPCs); config "npc-skins.<id>" still overrides. */
    private static final Map<String, Object[]> ROLE_LOOK = Map.ofEntries(
            Map.entry("bazaar", new Object[]{NamedTextColor.GOLD, "default:kai"}),
            Map.entry("auction", new Object[]{NamedTextColor.GOLD, "default:noor:slim"}),
            Map.entry("merchant", new Object[]{NamedTextColor.GREEN, "default:steve"}),
            Map.entry("bank", new Object[]{NamedTextColor.AQUA, "default:makena:slim"}),
            Map.entry("blacksmith", new Object[]{NamedTextColor.GRAY, "default:zuri"}),
            Map.entry("island", new Object[]{NamedTextColor.GREEN, "default:sunny"}),
            Map.entry("quest", new Object[]{NamedTextColor.YELLOW, "default:ari:slim"}),
            Map.entry("travel", new Object[]{NamedTextColor.AQUA, "default:efe"}),
            Map.entry("arcanist", new Object[]{NamedTextColor.LIGHT_PURPLE, "default:alex:slim"}),
            Map.entry("guide", new Object[]{NamedTextColor.GREEN, "default:alex:slim"}),
            Map.entry("placeholder", new Object[]{NamedTextColor.YELLOW, "default:steve"}));

    /** Solid floor under the feet and two free blocks: a player (or mannequin) can stand there. */
    private boolean standable(Location at) {
        Block feet = at.getBlock();
        return feet.getRelative(BlockFace.DOWN).getType().isSolid() && feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable();
    }

    private record Holo(String id, double x, double y, double z, String text, NamedTextColor color) {
    }

    /** Name colour and default skin per NPC id (config "npc-skins.<id>" overrides the skin). */
    private static final Map<String, Object[]> LOOK = Map.ofEntries(
            Map.entry("bazaar", new Object[]{NamedTextColor.GOLD, "default:kai"}),
            Map.entry("auction", new Object[]{NamedTextColor.GOLD, "default:noor:slim"}),
            Map.entry("merchant", new Object[]{NamedTextColor.GREEN, "default:steve"}),
            Map.entry("blacksmith", new Object[]{NamedTextColor.GRAY, "default:zuri"}),
            Map.entry("banker", new Object[]{NamedTextColor.AQUA, "default:makena:slim"}),
            Map.entry("arena-warp", new Object[]{NamedTextColor.RED, "default:efe"}),
            Map.entry("island", new Object[]{NamedTextColor.GREEN, "default:sunny"}),
            Map.entry("miner", new Object[]{NamedTextColor.YELLOW, "default:steve"}),
            Map.entry("arena", new Object[]{NamedTextColor.RED, "default:efe"}),
            Map.entry("archivist", new Object[]{NamedTextColor.LIGHT_PURPLE, "default:ari:slim"}),
            Map.entry("fisher", new Object[]{NamedTextColor.AQUA, "default:kai"}),
            Map.entry("farmer", new Object[]{NamedTextColor.GREEN, "default:sunny"}),
            Map.entry("woodward", new Object[]{NamedTextColor.DARK_GREEN, "default:zuri"}),
            Map.entry("arcanist", new Object[]{NamedTextColor.LIGHT_PURPLE, "default:alex:slim"}));

    // Hub layout 9 (Lumenhold). Plaza spots checked offline in hub9.sbh: solid floor at y 100, feet and head clear.
    private static final List<Npc> NPCS = List.of(
            new Npc("bazaar", "Bazaar Broker", -13.5, Y + 1, 6.5, -60f, Villager.Profession.FARMER, "STT market • click", false),
            new Npc("auction", "Auctioneer", 14.5, Y + 1, 6.5, 60f, Villager.Profession.LIBRARIAN, "Auctions in STT • click", false),
            new Npc("merchant", "Merchant", -9.5, Y + 1, 18.5, -112f, Villager.Profession.CLERIC, "Sell for coins • click", false),
            new Npc("blacksmith", "Blacksmith", 10.5, Y + 1, 18.5, 112f, Villager.Profession.ARMORER, "Forge gear • click", false),
            new Npc("banker", "Banker", 18.5, Y + 1, -6.5, 41f, Villager.Profession.CARTOGRAPHER, "Relic Pool • click", false),
            new Npc("arena-warp", "Arena Warden", 5.5, Y + 1, 24.5, 40f, Villager.Profession.WEAPONSMITH, "To the Colosseum • click", true),
            new Npc("island", "Island Keeper", -4.5, Y + 1, 24.5, -40f, Villager.Profession.MASON, "Go to your island • click", false),
            // Next to the district warps from META (offset in blocks from the warp).
            new Npc("miner", "Foreman Brak", -2, 0, 0, 0f, Villager.Profession.TOOLSMITH, "Mine", false, "mine"),
            new Npc("arena", "Arena Warden", -2, 0, 0, 0f, Villager.Profession.WEAPONSMITH, "Colosseum • sealed", false, "colosseum"),
            new Npc("archivist", "Archivist", -2, 0, 0, 0f, Villager.Profession.LIBRARIAN, "Relic Pool • click", false, "relics"),
            new Npc("fisher", "Fisher Hale", -2, 0, 0, 0f, Villager.Profession.FISHERMAN, "Sell your catch • click", false, "wharf"),
            new Npc("farmer", "Farmer Odo", -2, 0, 0, 0f, Villager.Profession.FARMER, "Sell crops • click", false, "farm"),
            new Npc("woodward", "Woodward Fenn", 1, 0, 1, 0f, Villager.Profession.FLETCHER, "Sell logs • click", false, "forest"),
            new Npc("arcanist", "Arcanist Ysolde", -1, 0, -1, 0f, Villager.Profession.CLERIC, "Spire", false, "spire")
    );

    /** One-line descriptions for the district holograms shown above each META warp. */
    private static final Map<String, Object[]> WARP_HOLO = Map.ofEntries(
            Map.entry("relics", new Object[]{"Relic Pool • the Archivist", NamedTextColor.LIGHT_PURPLE}),
            Map.entry("colosseum", new Object[]{"⚔ Sealed • future dungeon hub", NamedTextColor.GOLD}),
            Map.entry("farm", new Object[]{"Wheat • Carrots • Potatoes • Farming XP", NamedTextColor.GREEN}),
            Map.entry("mine", new Object[]{"Coal • Iron • Gold • Lapis • Redstone • Diamond", NamedTextColor.YELLOW}),
            Map.entry("barrows", new Object[]{"☠ Barrow Shamblers • Hollow Villagers", NamedTextColor.RED}),
            Map.entry("spire", new Object[]{"Stargazer Wizard • Enchanter", NamedTextColor.LIGHT_PURPLE}),
            Map.entry("ruins", new Object[]{"☠ Wolves • Alpha Direwolf • Bone Archers at night", NamedTextColor.GRAY}),
            Map.entry("forest", new Object[]{"Five groves • the Lumber Merchant", NamedTextColor.DARK_GREEN}),
            Map.entry("mountain", new Object[]{"Climb to the Stargazer Tower", NamedTextColor.WHITE}),
            Map.entry("lake", new Object[]{"Quiet water", NamedTextColor.AQUA}),
            Map.entry("wharf", new Object[]{"Fishing • the Fish Merchant buys your catch", NamedTextColor.AQUA}));

    private static final List<Holo> HOLOS = List.of(
            new Holo("spawn", 0.5, 107.5, 0.5, "LUMENHOLD\nSomnia Skyblock • on-chain economy\n/quest • /bz • /ah • /arena • /stats", NamedTextColor.GOLD));

    /** Highest standing spot (solid floor, two free blocks) within dy of y at x,z; NaN if none. */
    private double floorNear(int x, int y, int z, int dy) {
        for (int yy = y + dy; yy >= y - dy; yy--) {
            Block below = world.getBlockAt(x, yy - 1, z);
            if (below.getType().isSolid() && world.getBlockAt(x, yy, z).isPassable() && world.getBlockAt(x, yy + 1, z).isPassable()) {
                return yy;
            }
        }
        return Double.NaN;
    }

    /** Where an NPC stands: absolute, or next to its META warp facing it; null when the warp or floor is missing. */
    private Location npcSpot(Npc npc) {
        if (npc.anchor() == null) {
            return new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), 0f);
        }
        int[] w = zones == null ? null : zones.warp(npc.anchor());
        if (w == null) {
            return null;
        }
        int bx = w[0] + (int) npc.x();
        int bz = w[2] + (int) npc.z();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null;
        }
        double fy = floorNear(bx, w[1], bz, 4);
        if (Double.isNaN(fy)) {
            return null;
        }
        Location at = new Location(world, bx + 0.5, fy, bz + 0.5);
        at.setDirection(new Location(world, w[0] + 0.5, fy, w[2] + 0.5).toVector().subtract(at.toVector()));
        at.setPitch(0f);
        return at;
    }


    void toColosseum(Player player) {
        Location at = gate();
        player.teleport(at);
        player.setFallDistance(0);
        player.sendMessage(Component.text(name("colosseum") + ". The gates are sealed for now; dungeons will open here later. PvE only; no player combat anywhere in the hub.", NamedTextColor.GOLD));
    }

    /**
     * Portal sheets of the hub layout 9 arches (nether_portal[axis=x]); the first hub9 build had air inside them.
     * Same boxes as "fill ... minecraft:nether_portal[axis=x] strict": placed without block updates, air only.
     * Runs at start (logged) and every minute as a quiet repair; portal blocks in the hub also ignore physics.
     */
    /** Rows {x0, x1, y0, y1, z0, z1} (the META "rows" order). */
    private static final int[][] PORTAL_FILL = {
            {-39, -33, 107, 115, -90, -90}, {-38, -34, 116, 116, -90, -90}, {-37, -35, 117, 117, -90, -90},
            {-3, 3, 107, 115, -90, -90}, {-2, 2, 116, 116, -90, -90}, {-1, 1, 117, 117, -90, -90},
            {33, 39, 107, 115, -87, -87}, {34, 38, 116, 116, -87, -87}, {35, 37, 117, 117, -87, -87}};

    private void fillPortals(boolean report) {
        if (world == null || pasting) {
            return;
        }
        List<Zones.Portal> list = zones == null ? List.of() : zones.portals();
        boolean meta = list.stream().anyMatch(p -> !p.rows().isEmpty());
        if (!meta) {
            // Old layout 9 build without rows in META: the built-in boxes.
            if (plugin.getConfig().getInt("hub-layout", 0) != 9) {
                return;
            }
            fillSheet("Private Island", List.of(PORTAL_FILL[0], PORTAL_FILL[1], PORTAL_FILL[2]), org.bukkit.Axis.X, report);
            for (int p = 1; p < 3; p++) {
                clearSheet("old arch " + p, List.of(PORTAL_FILL[p * 3], PORTAL_FILL[p * 3 + 1], PORTAL_FILL[p * 3 + 2]), report);
            }
            return;
        }
        for (Zones.Portal p : list) {
            if (p.rows().isEmpty()) {
                continue;
            }
            if (link(p)) {
                fillSheet(p.label() + " (" + p.id() + ")", p.rows(), "z".equalsIgnoreCase(p.axis()) ? org.bukkit.Axis.Z : org.bukkit.Axis.X, report);
            } else {
                clearSheet(p.label() + " (" + p.id() + ")", p.rows(), report);
            }
        }
    }

    /**
     * The only portal left in the hub is the island link. Mine, colosseum, event and dungeon arches are plain
     * decoration now (districts are reached with /warp); the hub12 build will replace them.
     */
    static boolean link(Zones.Portal p) {
        return p != null && "island".equals(p.dest());
    }

    /** Turns the portal sheet of a dropped arch into air (no block updates); the frame stays. */
    private void clearSheet(String name, List<int[]> rows, boolean report) {
        int cleared = 0;
        for (int[] b : rows) {
            if (!world.isChunkLoaded(b[0] >> 4, b[4] >> 4) && !report) {
                return;
            }
            for (int x = b[0]; x <= b[1]; x++) {
                for (int y = b[2]; y <= b[3]; y++) {
                    for (int z = b[4]; z <= b[5]; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.NETHER_PORTAL) {
                            block.setType(Material.AIR, false);
                            cleared++;
                        }
                    }
                }
            }
        }
        if (cleared > 0) {
            plugin.getLogger().info("Hub arch " + name + ": portal removed (" + cleared + " portal blocks cleared); use /warp.");
        }
    }

    /** Fills air in the rows with nether_portal (no block updates); other blocks are left alone and reported. */
    private void fillSheet(String name, List<int[]> rows, org.bukkit.Axis axis, boolean report) {
        org.bukkit.block.data.Orientable sheet = (org.bukkit.block.data.Orientable) Material.NETHER_PORTAL.createBlockData();
        sheet.setAxis(axis);
        int placed = 0, already = 0;
        java.util.Map<String, Integer> other = new java.util.TreeMap<>();
        for (int[] b : rows) {
            if (!report && !world.isChunkLoaded(b[0] >> 4, b[4] >> 4)) {
                return;
            }
            for (int x = b[0]; x <= b[1]; x++) {
                for (int y = b[2]; y <= b[3]; y++) {
                    for (int z = b[4]; z <= b[5]; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();
                        if (type == Material.NETHER_PORTAL) {
                            if (block.getBlockData() instanceof org.bukkit.block.data.Orientable o && o.getAxis() == axis) {
                                already++;
                                continue;
                            }
                        } else if (!type.isAir()) {
                            other.merge(type.getKey().getKey() + "@" + x + "," + y + "," + z, 1, Integer::sum);
                            continue;
                        }
                        block.setBlockData(sheet, false);
                        placed++;
                    }
                }
            }
        }
        if (report || placed > 0) {
            plugin.getLogger().info("Hub portal " + name + ": " + placed + " portal blocks placed, " + already + " already there"
                    + (other.isEmpty() ? "" : ", left alone (not air): " + other.keySet()) + ".");
        }
    }

    private void populate() {
        if (world == null || pasting || world.getPlayers().isEmpty()) {
            return;
        }
        java.util.Set<UUID> ours = new java.util.HashSet<>(spawned.values());
        for (Villager stray : world.getEntitiesByClass(Villager.class)) {
            if (!ours.contains(stray.getUniqueId())) {
                stray.remove();
            }
        }
        for (org.bukkit.entity.Mannequin stray : world.getEntitiesByClass(org.bukkit.entity.Mannequin.class)) {
            if (!ours.contains(stray.getUniqueId())) {
                stray.remove(); // every hub mannequin is ours; leftovers from older layouts stood on top of the new NPCs
            }
        }
        for (TextDisplay stray : world.getEntitiesByClass(TextDisplay.class)) {
            if (stray.getScoreboardTags().contains("sb-holo") && !ours.contains(stray.getUniqueId())) {
                stray.remove();
            }
        }
        Npcs.removeLeftovers(world.getEntitiesByClass(org.bukkit.entity.ArmorStand.class), ours);
        npcByEntity.keySet().removeIf(id -> !ours.contains(id));
        for (Npc npc : npcs) {
            if (alive("npc:" + npc.id())) {
                continue;
            }
            Location at = npcSpot(npc);
            if (at == null || !at.isChunkLoaded()) {
                continue;
            }
            // Old label of a body that died: remove it before making a new pair.
            for (String part : new String[]{"npclabel:", "npcname:"}) {
                UUID oldLabel = spawned.remove(part + npc.id());
                if (oldLabel != null && world.getEntity(oldLabel) != null) {
                    world.getEntity(oldLabel).remove();
                }
            }
            if (!standable(at) && reportedSpots.add(npc.id())) {
                Block feet = at.getBlock();
                plugin.getLogger().info("Hub NPC spot not standable: " + npc.id() + " at " + feet.getX() + "," + feet.getY() + "," + feet.getZ()
                        + " (floor " + feet.getRelative(BlockFace.DOWN).getType() + ", feet " + feet.getType() + ", head " + feet.getRelative(BlockFace.UP).getType() + ").");
            }
            Object[] look = npc.kind() != null ? ROLE_LOOK.getOrDefault(npc.kind(), new Object[]{NamedTextColor.YELLOW, "default:steve"})
                    : LOOK.getOrDefault(npc.id(), new Object[]{NamedTextColor.YELLOW, "default:steve"});
            String skin = plugin.getConfig().getString("npc-skins." + npc.id(), null);
            if (skin == null) {
                skin = skinFor(npc, at, (String) look[1]);
            }
            npcSpots.put(npc.id(), new Object[]{at.clone(), skin});
            Npcs.Spawned made = Npcs.spawn(world, at, "sb-npc:" + npc.id(), npc.name(), (NamedTextColor) look[0], skin, plugin.getLogger(),
                    "placeholder".equals(npc.kind()));
            spawned.put("npc:" + npc.id(), made.body());
            spawned.put("npclabel:" + npc.id(), made.label());
            spawned.put("npcname:" + npc.id(), made.nameLine());
            npcByEntity.put(made.body(), npc);
        }
        if (zones != null) {
            for (Zones.Portal p : zones.portals()) {
                String key = "portal:" + p.dest();
                if (!link(p) || alive(key) || !new Location(world, p.x(), p.y(), p.z()).isChunkLoaded()) {
                    continue;
                }
                boolean soon = comingSoon(p);
                String text = soon ? p.label() + "\nSOON" : "Travel to:\n" + p.label();
                NamedTextColor color = soon ? NamedTextColor.GRAY : NamedTextColor.AQUA;
                if (p.min() != null && p.max() != null) {
                    double hx = p.labelAt() != null ? p.labelAt()[0] : (p.min()[0] + p.max()[0] + 1) / 2.0;
                    double hz = p.labelAt() != null ? p.labelAt()[2] : (p.min()[2] + p.max()[2] + 1) / 2.0;
                    holo(key, hx, p.max()[1] + 1.6, hz, text, color);
                } else {
                    holo(key, p.x() + 0.5, p.y() + 10.3, p.z() - 0.6, text, color);
                }
            }
            for (Map.Entry<String, double[]> c : carts.entrySet()) {
                String key = "cart:" + c.getKey();
                double[] v = c.getValue();
                Location at = new Location(world, Math.floor(v[0]) + 0.5, v[1], Math.floor(v[2]) + 0.5);
                if (alive(key) || !at.isChunkLoaded()) {
                    continue;
                }
                org.bukkit.entity.Minecart cart = world.spawn(at, org.bukkit.entity.minecart.RideableMinecart.class, m -> {
                    m.setPersistent(false);
                    m.setSlowWhenEmpty(true);
                    m.setMaxSpeed(0.0);
                    m.addScoreboardTag("sb-cart");
                });
                spawned.put(key, cart.getUniqueId());
            }
        }
        if (zones != null) {
            for (Map.Entry<String, Object[]> e : WARP_HOLO.entrySet()) {
                int[] w = zones.warp(e.getKey());
                if (w == null || alive("warp-holo:" + e.getKey()) || !world.isChunkLoaded(w[0] >> 4, w[2] >> 4)) {
                    continue;
                }
                holo("warp-holo:" + e.getKey(), w[0] + 0.5, w[1] + 3.2, w[2] + 0.5,
                        name(e.getKey()).toUpperCase(java.util.Locale.ROOT) + "\n" + e.getValue()[0], (NamedTextColor) e.getValue()[1]);
            }
        }
        // META holograms (the build's old sign texts) are no longer shown: no sign-style info text in the hub.
        for (Holo holo : HOLOS) {
            if (alive("holo:" + holo.id())) {
                continue;
            }
            if (!new Location(world, holo.x(), holo.y(), holo.z()).isChunkLoaded()) {
                continue;
            }
            holo("holo:" + holo.id(), holo.x(), holo.y(), holo.z(), holo.text(), holo.color());
        }
    }

    // ===== NPC skins: no Steve, and no two NPCs near each other share a look =====
    private static final List<String> SKINS = List.of(
            "default:alex:slim", "default:ari:slim", "default:efe", "default:kai", "default:makena:slim", "default:noor:slim",
            "default:sunny", "default:zuri", "default:alex", "default:ari", "default:efe:slim", "default:kai:slim",
            "default:makena", "default:noor", "default:sunny:slim", "default:zuri:slim");
    private final Map<String, Object[]> npcSpots = new HashMap<>();

    /** The role's look unless it is Steve or a nearby NPC already wears it; then the variant whose nearest twin is farthest. */
    private String skinFor(Npc npc, Location at, String preferred) {
        Object[] mine = npcSpots.get(npc.id());
        if (mine != null) {
            return (String) mine[1];
        }
        java.util.List<String> order = new ArrayList<>();
        if (preferred != null && !preferred.startsWith("default:steve")) {
            order.add(preferred);
        }
        int seed = Math.floorMod(npc.id().hashCode(), SKINS.size());
        for (int i = 0; i < SKINS.size(); i++) {
            String s = SKINS.get((seed + i) % SKINS.size());
            if (!order.contains(s)) {
                order.add(s);
            }
        }
        String best = order.get(0);
        double bestGap = -1;
        for (String s : order) {
            double gap = Double.MAX_VALUE;
            for (Map.Entry<String, Object[]> e : npcSpots.entrySet()) {
                Location other = (Location) e.getValue()[0];
                if (s.equals(e.getValue()[1]) && other.getWorld().equals(at.getWorld())) {
                    gap = Math.min(gap, other.distance(at));
                }
            }
            if (gap >= 48) {
                return s;
            }
            if (gap > bestGap) {
                bestGap = gap;
                best = s;
            }
        }
        return best;
    }

    // ===== META holograms (the build's former sign texts): English lines only, no placeholders =====
    private final List<Holo> metaHolos = new ArrayList<>();
    private static final java.util.regex.Pattern HOLO_DROP = java.util.regex.Pattern.compile(
            "(?i)leaderboard|parkour|daily|order \\d|party|looking for|dungeon|stt rate|coming soon|top \\d");

    private void readMetaHolos(String meta) {
        metaHolos.clear();
        int dropped = 0, dupes = 0;
        try {
            com.google.gson.JsonObject root = meta == null || meta.isBlank() ? new com.google.gson.JsonObject()
                    : com.google.gson.JsonParser.parseString(meta).getAsJsonObject();
            if (!root.has("holograms") || !root.get("holograms").isJsonArray()) {
                return;
            }
            int i = 0;
            for (com.google.gson.JsonElement e : root.getAsJsonArray("holograms")) {
                com.google.gson.JsonObject h = e.getAsJsonObject();
                com.google.gson.JsonArray xyz = h.getAsJsonArray("xyz");
                List<String> raw = new ArrayList<>();
                if (h.has("hologram_lines") && h.get("hologram_lines").isJsonArray()) {
                    for (com.google.gson.JsonElement l : h.getAsJsonArray("hologram_lines")) {
                        raw.add(l.getAsString());
                    }
                } else if (h.has("hologram_text")) {
                    raw.addAll(List.of(h.get("hologram_text").getAsString().split("\n")));
                }
                List<String> lines = new ArrayList<>();
                boolean arrow = false;
                for (String r : raw) {
                    String l = r.replaceAll("§.", "").trim();
                    if (l.isEmpty() || l.chars().anyMatch(c -> c >= 0x0400 && c <= 0x04FF)) {
                        continue;
                    }
                    if (!l.matches(".*[A-Za-z].*")) {
                        arrow |= l.equals("→");
                        continue;
                    }
                    lines.add(arrow ? "→ " + l : l);
                    arrow = false;
                }
                String text = String.join("\n", lines);
                if (lines.isEmpty() || HOLO_DROP.matcher(text).find()) {
                    dropped++;
                    continue;
                }
                double x = xyz.get(0).getAsDouble() + 0.5, y = xyz.get(1).getAsDouble() + 0.6, z = xyz.get(2).getAsDouble() + 0.5;
                if (duplicate(text, x, z)) {
                    dupes++;
                    continue;
                }
                boolean landmark = h.has("landmark") && !h.get("landmark").isJsonNull();
                metaHolos.add(new Holo("meta" + (i++), x, y, z, text, landmark ? NamedTextColor.GOLD : NamedTextColor.AQUA));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Hub META holograms could not be read: " + e.getMessage());
        }
        plugin.getLogger().info("Hub META holograms: " + metaHolos.size() + " read, none shown (no sign-style holograms), " + dropped + " dropped (non-English/placeholder/not in v1), "
                + dupes + " duplicates of portal or nearby labels.");
    }

    /** Same first line as a portal label within 12 blocks, or as a kept META hologram within 14 blocks. */
    private boolean duplicate(String text, double x, double z) {
        String first = text.split("\n")[0].replace("→ ", "").toLowerCase(java.util.Locale.ROOT);
        for (Zones.Portal p : zones.portals()) {
            if (p.label() != null && p.label().toLowerCase(java.util.Locale.ROOT).contains(first)
                    && Math.hypot(p.x() - x, p.z() - z) < 12) {
                return true;
            }
        }
        for (Holo h : metaHolos) {
            if (h.text().split("\n")[0].replace("→ ", "").equalsIgnoreCase(first) && Math.hypot(h.x() - x, h.z() - z) < 14) {
                return true;
            }
        }
        return false;
    }

    private boolean alive(String key) {
        UUID id = spawned.get(key);
        if (id == null) {
            return false;
        }
        Entity entity = world.getEntity(id);
        return entity != null && entity.isValid();
    }

    private void holo(String key, double x, double y, double z, String text, NamedTextColor color) {
        UUID old = spawned.get(key);
        if (old != null) {
            Entity entity = world.getEntity(old);
            if (entity != null && entity.isValid()) {
                return;
            }
        }
        String[] lines = text.split("\n");
        Component body = Component.text(lines[0], color).decoration(TextDecoration.BOLD, true);
        for (int i = 1; i < lines.length; i++) {
            body = body.append(Component.newline()).append(Component.text(lines[i], NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
        }
        Component shown = body;
        TextDisplay display = world.spawn(new Location(world, x, y, z), TextDisplay.class, d -> {
            d.text(shown);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.addScoreboardTag("sb-holo");
        });
        spawned.put(key, display.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (from == null) {
            return;
        }
        if (from.equals(plugin.islands().world()) || isHub(from)) {
            event.setCancelled(true);
            event.setCanCreatePortal(false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        if (isHub(to.getWorld()) && to.getY() < 20) {
            teleport(player);
            Chat.voidDeath(player);
            return;
        }
        Block body = to.getBlock().getRelative(BlockFace.UP);
        if (to.getBlock().getType() != Material.NETHER_PORTAL && body.getType() != Material.NETHER_PORTAL) {
            discover(player, to);
            return;
        }
        long now = System.currentTimeMillis();
        Zones.Portal at = isHub(to.getWorld()) && zones != null ? zones.portalAt(to) : null;
        if (at != null && !link(at)) {
            return; // dropped arch: a leftover portal block does nothing, /warp is the way
        }
        Long last = portalAt.get(player.getUniqueId());
        if (last != null && now - last < 1500) {
            return;
        }
        portalAt.put(player.getUniqueId(), now);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (to.getWorld().equals(plugin.islands().world())) {
            // The island portal is the island <-> hub link only (no warps as portals).
            teleport(player);
            greet(player, profile);
            player.sendMessage(Component.text("The portal brought you to the hub. /is goes home.", NamedTextColor.GREEN));
            return;
        }
        if (isHub(to.getWorld())) {
            plugin.islands().ensureIsland(profile);
            plugin.islands().teleport(player, profile);
            player.setRespawnLocation(plugin.islands().home(profile), true);
            player.sendMessage(Component.text("You are on your island.", NamedTextColor.GREEN));
        }
    }

    private final Map<UUID, Long> soonAt = new HashMap<>();
    /** Portal destinations that are not built yet: they show "Coming soon" and never teleport. */
    private static final java.util.Set<String> SOON = java.util.Set.of("mines_deep", "event", "dungeon");

    private boolean comingSoon(Zones.Portal p) {
        String d = p.dest();
        if (SOON.contains(d)) {
            return true;
        }
        return !"island".equals(d) && !"colosseum".equals(d) && !"hub".equals(d) && !"plaza".equals(d) && warpPoint(d) == null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        soonAt.remove(event.getPlayer().getUniqueId());
        portalAt.remove(event.getPlayer().getUniqueId());
        zoneOf.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleported(org.bukkit.event.player.PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                discover(player, player.getLocation());
            }
        }, 10L);
    }

    private void discover(Player player, Location to) {
        if (!isHub(to.getWorld())) {
            zoneOf.remove(player.getUniqueId());
            return;
        }
        String[] here = zones.at(to);
        String id = here == null ? "" : here[0];
        String last = zoneOf.put(player.getUniqueId(), id);
        if (id.equals(last) || here == null) {
            return;
        }
        boolean combat = "combat".equals(here[2]);
        plugin.chat().discovered(player, plugin.profiles().get(player.getUniqueId()), id, here[1], combat);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        plugin.sidebar().show(player, profile);
        if ("mine".equals(id) && !profile.seenCoal) {
            profile.seenCoal = true;
            plugin.profiles().save(profile);
            player.sendMessage(Component.text(name("mine") + ". Coal needs any pickaxe, iron and lapis a stone pick, gold, redstone and diamond an iron pick. Ore grows back.", NamedTextColor.GRAY));
        } else if ("mountain".equals(id) && !profile.seenGold && to.getBlockY() >= 135) {
            profile.seenGold = true;
            plugin.profiles().save(profile);
            player.sendMessage(Component.text(name("mountain") + ": gold and iron on the high slopes. The shrine sits on the summit.", NamedTextColor.GOLD));
        }
    }

    private void scheduleRegen(Location location, Material ore) {
        long delay = ore == Material.WHEAT ? 400L : isLog(ore) ? 600L : REGEN_TICKS;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Block block = location.getBlock();
            if (block.getType().isAir()) {
                grow(block, ore);
            }
        }, delay);
    }

    private static boolean isLog(Material type) {
        return type == Material.OAK_LOG || type == Material.BIRCH_LOG || type == Material.DARK_OAK_LOG;
    }

    private static void grow(Block block, Material type) {
        block.setType(type, false);
        if (block.getBlockData() instanceof org.bukkit.block.data.Ageable crop) {
            crop.setAge(crop.getMaximumAge());
            block.setBlockData(crop, false);
        }
    }

    /** Skill XP for hub resource spots: farming (Goldmeadow wheat), foraging (Elderwood and meadow logs), mining (ore). */
    private void skill(Player player, Material type) {
        skill(player, type, true);
    }

    /**
     * A natural resource broken on the player's own island: the same XP and collection rules as the hub
     * (SkySkills XP + collections, sent in the 4 s batch). Player-placed blocks never reach this. Unripe crops pay nothing.
     */
    void islandCleared(Player player, String blockData) {
        if (player == null || player.getGameMode() == org.bukkit.GameMode.CREATIVE || blockData == null) {
            return;
        }
        org.bukkit.block.data.BlockData data;
        try {
            data = org.bukkit.Bukkit.createBlockData(blockData);
        } catch (IllegalArgumentException exception) {
            return;
        }
        Material type = data.getMaterial();
        boolean resource = CROPS.contains(type) || isNaturalLog(type) || isLeaves(type) || isStone(type) || isFlora(type)
                || type.name().endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
        if (!resource) {
            return;
        }
        if (CROPS.contains(type) && data instanceof org.bukkit.block.data.Ageable age && age.getAge() < age.getMaximumAge()) {
            return;
        }
        skill(player, type, false);
        if (type == Material.OAK_LOG || type == Material.OAK_WOOD) {
            plugin.quests().hit(player, "island_oak", 1);
        } else if (type == Material.COBBLESTONE) {
            plugin.quests().hit(player, "island_cobble", 1);
        }
        plugin.sidebar().show(player, plugin.profiles().get(player.getUniqueId()));
    }

    private void skill(Player player, Material type, boolean hub) {
        String n = type.name();
        if (CROPS.contains(type)) {
            // Wiki Farming XP per block broken: wheat/carrot/potato/melon/cocoa/nether wart 4, pumpkin 4.5,
            // sugar cane and cactus 2. Beetroot 4 is ours.
            long tenths = type == Material.PUMPKIN ? 45 : type == Material.SUGAR_CANE || type == Material.CACTUS ? 20 : 40;
            plugin.skills().addTenths(player, Skills.Skill.FARMING, tenths);
            if (hub) {
                plugin.quests().hit(player, "hub_crop", 1);
            }
        } else if (isNaturalLog(type)) {
            plugin.skills().add(player, Skills.Skill.FORAGING, 6);
            if (hub) {
                plugin.quests().hit(player, "hub_log", 1);
            }
        } else if (isLeaves(type)) {
            plugin.skills().add(player, Skills.Skill.FORAGING, 1);
            return;
        } else if (grassXp(type) > 0) {
            // Grass and ferns: Foraging XP only, no item and no collection.
            plugin.skills().add(player, Skills.Skill.FORAGING, grassXp(type));
            return;
        } else if (isFlower(type)) {
            plugin.skills().add(player, Skills.Skill.FORAGING, 1);
        } else if (isStone(type)) {
            plugin.skills().add(player, Skills.Skill.MINING, type.name().contains("DEEPSLATE") ? 2 : 1);
        } else {
            // Wiki Mining XP: coal / iron / quartz 5, gold 6, redstone / lapis 7, emerald 9, diamond 10. Copper 6 is ours.
            long xp = n.contains("DIAMOND") ? 10 : n.contains("EMERALD") ? 9 : n.contains("GOLD") ? 6
                    : n.contains("REDSTONE") || n.contains("LAPIS") ? 7 : n.contains("IRON") ? 5 : n.contains("COPPER") ? 6 : 5;
            plugin.skills().add(player, Skills.Skill.MINING, xp);
            if (hub) {
                plugin.quests().hit(player, "hub_ore", 1);
            }
        }
        Collects.add(plugin, player, type, 1);
        plugin.profiles().save(plugin.profiles().get(player.getUniqueId()));
    }

    private static String hint(Material ore) {
        if (ore == Material.GOLD_ORE) {
            return "Gold needs an iron pick.";
        }
        if (ore == Material.DIAMOND_ORE) {
            return "Diamond needs an iron pick.";
        }
        if (ore == Material.REDSTONE_ORE) {
            return "Redstone needs an iron pick.";
        }
        if (ore == Material.LAPIS_ORE) {
            return "Lapis needs a stone pick.";
        }
        if (ore == Material.IRON_ORE) {
            return "Iron needs a stone pick.";
        }
        if (ore == Material.COAL_ORE || isStone(ore)) {
            return "You need a pickaxe for this.";
        }
        return "It grows back soon.";
    }

    private void resetSeen() {
        plugin.profiles().eachReady(profile -> {
            profile.seenHub = false;
            profile.seenCoal = false;
            profile.seenGold = false;
            plugin.profiles().save(profile);
        });
    }

    private boolean intact() {
        boolean ok = !checks.isEmpty();
        for (Object[] c : checks) {
            ok &= expect((Integer) c[0], (Integer) c[1], (Integer) c[2], (Material) c[3]);
        }
        if (ores.isEmpty()) {
            plugin.getLogger().severe("Hub has no resource spots.");
            ok = false;
        }
        if (zones.points().isEmpty()) {
            plugin.getLogger().severe("Hub has no mob spawn points.");
            ok = false;
        }
        return ok;
    }

    private boolean expect(int x, int y, int z, Material type) {
        Material found = world.getBlockAt(x, y, z).getType();
        if (found == type) {
            return true;
        }
        plugin.getLogger().severe("Hub layout mismatch at " + x + "," + y + "," + z + ": " + found + " expected " + type);
        return false;
    }

    private void markOres(List<HubPaste.Ore> spots, boolean place) {
        ores.clear();
        int skipped = 0;
        for (HubPaste.Ore ore : spots) {
            if (ore.type() == null) {
                skipped++; // ore letter without a resource backend yet: the build block stays as protected decor
                continue;
            }
            spot(ore.x(), ore.y(), ore.z(), ore.type(), place);
        }
        if (skipped > 0) {
            plugin.getLogger().info("Hub resource spots skipped (letter not wired yet): " + skipped);
        }
        plugin.getLogger().info("Hub resource spots (ore, wheat, logs): " + ores.size());
    }

    private void spot(int x, int y, int z, Material type, boolean place) {
        Block block = world.getBlockAt(x, y, z);
        ores.put(key(block), type);
        if (place || block.getType().isAir()) {
            grow(block, type);
        }
    }

    // ===== Hub guard: the build is read-only for players outside creative; only ore spots can be mined =====
    private boolean guarded(Player player, World where) {
        return isHub(where) && player.getGameMode() != org.bukkit.GameMode.CREATIVE;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGuardBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isHub(block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        long k = key(block);
        if (regen.containsKey(k)) {
            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("This is growing back. Give it a moment.", NamedTextColor.GRAY));
            }
            return;
        }
        Material type = resource(block);
        if (type == null) {
            if (guarded(player, block.getWorld())) {
                event.setCancelled(true);
            }
            return;
        }
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            String why = null;
            if (block.getBlockData() instanceof org.bukkit.block.data.Ageable crop && CROPS.contains(type) && crop.getAge() < crop.getMaximumAge()) {
                why = "Not ripe yet.";
            } else if (type == Material.SUGAR_CANE || type == Material.CACTUS) {
                if (block.getRelative(BlockFace.DOWN).getType() != type) {
                    why = "Leave the bottom block so it grows back.";
                } else if (block.getRelative(BlockFace.UP).getType() == type) {
                    why = "Harvest it from the top.";
                }
            } else if (!isLeaves(type) && !isFlora(type) && block.getDrops(player.getInventory().getItemInMainHand(), player).isEmpty()) {
                why = hint(type);
            }
            if (why != null) {
                event.setCancelled(true);
                player.sendActionBar(Component.text(why, NamedTextColor.YELLOW));
                return;
            }
        }
        breaking.put(k, block.getBlockData().getAsString());
    }

    // ===== Hub resources and regen chains =====
    // ore -> bedrock -> ore, natural stone -> bedrock -> stone, tree log / leaves -> air -> back,
    // crop -> sprout -> half grown -> ripe, melon / pumpkin / top sugar cane / cactus -> air -> back.
    // Outside the spawn village and META interior/safe/event boxes; structures (builder blocks touching) stay
    // protected. Everything is restored on stop.
    private static final java.util.Set<Material> CROPS = java.util.EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.SUGAR_CANE, Material.CACTUS, Material.MELON, Material.PUMPKIN, Material.NETHER_WART, Material.COCOA);
    private static final long STONE_TICKS = 300L;
    private static final long LEAF_TICKS = 400L;
    private static final long ORE_TICKS = 500L;
    private static final long LOG_TICKS = 600L;
    private static final long CROP_TICKS = 400L;
    /** Blocks in a regen chain: key -> the block data they grow back to. */
    private final Map<Long, String> regen = new HashMap<>();
    private final Map<Long, Location> regenAt = new HashMap<>();
    /** Harvested blocks waiting for the onchain bag write: key -> original block data. */
    private final Map<Long, String> breaking = new HashMap<>();

    static boolean isOreType(Material t) {
        return t.name().endsWith("_ORE");
    }

    /** Tree trunks: the hub trees are built from *_WOOD (bark on all sides) as well as *_LOG. */
    static boolean isNaturalLog(Material t) {
        String n = t.name();
        return (n.endsWith("_LOG") || n.endsWith("_WOOD")) && !n.startsWith("STRIPPED_");
    }

    static boolean isLeaves(Material t) {
        return t.name().endsWith("_LEAVES");
    }

    /** Natural flowers (small and tall): the flower goes to the bag, +1 Foraging XP and the Flowers collection. */
    static boolean isFlower(Material t) {
        return org.bukkit.Tag.SMALL_FLOWERS.isTagged(t) || t == Material.SUNFLOWER || t == Material.LILAC
                || t == Material.PEONY || t == Material.ROSE_BUSH;
    }

    /** Foraging XP for grass and ferns (no item): 1 for short grass / fern, 2 for tall grass / large fern, else 0. */
    static int grassXp(Material t) {
        return switch (t) {
            case SHORT_GRASS, FERN -> 1;
            case TALL_GRASS, LARGE_FERN -> 2;
            default -> 0;
        };
    }

    static boolean isFlora(Material t) {
        return isFlower(t) || grassXp(t) > 0;
    }

    private static final long FLORA_TICKS = 400L;

    /** Island flowers and grass regrow server-side (no tx) when the spot is still free soil with air above. */
    void islandRegrow(Location location, String data) {
        if (location == null || data == null) {
            return;
        }
        org.bukkit.block.data.BlockData d;
        try {
            d = org.bukkit.Bukkit.createBlockData(data);
        } catch (IllegalArgumentException exception) {
            return;
        }
        if (!isFlora(d.getMaterial())) {
            return;
        }
        Block at = location.getBlock();
        if (d instanceof org.bukkit.block.data.Bisected half && half.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
            half.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
            at = at.getRelative(BlockFace.DOWN);
        }
        Block spot = at;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            boolean tall = d instanceof org.bukkit.block.data.Bisected;
            if (!spot.getType().isAir() || tall && !spot.getRelative(BlockFace.UP).getType().isAir()
                    || !org.bukkit.Tag.DIRT.isTagged(spot.getRelative(BlockFace.DOWN).getType())) {
                return;
            }
            place(spot, d);
            sparkle(spot);
        }, FLORA_TICKS);
    }

    /** Regrow cue for plants: sparkle trail from the area's hovering spirit down to the block (see Sparkles). */
    private void sparkle(Block block) {
        if (!stopping) {
            sparkles.regrow(block);
        }
    }

    /** Sets a plant, both halves for tall grass / large fern. */
    private static void place(Block block, org.bukkit.block.data.BlockData data) {
        block.setBlockData(data, false);
        if (data instanceof org.bukkit.block.data.Bisected half && half.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM) {
            org.bukkit.block.data.BlockData top = data.clone();
            ((org.bukkit.block.data.Bisected) top).setHalf(org.bukkit.block.data.Bisected.Half.TOP);
            block.getRelative(BlockFace.UP).setBlockData(top, false);
        }
    }

    /** Natural stone: mined straight to bedrock, back after STONE_TICKS. */
    private static final java.util.Set<Material> STONES = java.util.EnumSet.of(Material.STONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.TUFF, Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.BASALT, Material.SMOOTH_BASALT,
            Material.BLACKSTONE);

    static boolean isStone(Material t) {
        return STONES.contains(t);
    }

    /** Builder blocks: a resource touching one of these is part of a structure and stays protected. */
    private static boolean structural(Material t) {
        String n = t.name();
        if (n.startsWith("STRIPPED_")) {
            return true;
        }
        for (String part : STRUCT_PARTS) {
            if (n.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] STRUCT_PARTS = {"_PLANKS", "_STAIRS", "_SLAB", "_FENCE", "_DOOR", "_TRAPDOOR", "GLASS", "_PANE",
            "LANTERN", "TORCH", "_WOOL", "_CARPET", "BRICK", "CHAIN", "BARREL", "CHEST", "BOOKSHELF", "LADDER", "_SIGN", "_BANNER",
            "_BED", "LECTERN", "ANVIL", "BELL", "CAMPFIRE", "POLISHED_", "SMOOTH_STONE", "SMOOTH_SANDSTONE", "CUT_", "TERRACOTTA",
            "CONCRETE", "IRON_BARS", "_WALL", "CHISELED_", "TILES", "PILLAR"};

    private static final BlockFace[] SIDES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private static boolean touchesStructure(Block b) {
        for (BlockFace f : SIDES) {
            if (structural(b.getRelative(f).getType())) {
                return true;
            }
        }
        return false;
    }

    /** Spawn village (plaza district / Lumenhold safe region) and every META interior, safe or event box. */
    private boolean protectedSpot(Block b) {
        if (zones == null) {
            return true;
        }
        Location at = b.getLocation();
        String[] here = zones.at(at);
        if (here != null && "plaza".equals(here[0])) {
            return true;
        }
        for (Zones.Region r : zones.regions()) {
            String kind = r.kind();
            if (("interior".equals(kind) || "safe".equals(kind) || "event".equals(kind) || "plaza".equals(r.district()) && !"resource".equals(kind))
                    && r.contains(at)) {
                return true;
            }
        }
        for (Zones.Portal p : zones.portals()) {
            if (p.contains(at)) {
                return true;
            }
        }
        return false;
    }

    /** Trunk blocks known to belong (or not) to a natural tree: filled per connected wood cluster. */
    private final Map<Long, Boolean> treeCache = new HashMap<>();
    private static final int TREE_SCAN = 4096;

    /**
     * A natural tree: the connected wood/log cluster carries leaves (at least one leaf per four trunk blocks)
     * and barely touches builder blocks (houses, fences, beams). Checked once per cluster and cached.
     */
    private boolean isTree(Block start) {
        Boolean known = treeCache.get(key(start));
        if (known != null) {
            return known;
        }
        java.util.ArrayDeque<Block> todo = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.Set<Long> leaves = new java.util.HashSet<>();
        todo.add(start);
        seen.add(key(start));
        int size = 0;
        int struct = 0;
        while (!todo.isEmpty() && size < TREE_SCAN) {
            Block q = todo.poll();
            size++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block r = q.getRelative(dx, dy, dz);
                        Material t = r.getType();
                        long rk = key(r);
                        if (isNaturalLog(t)) {
                            if (seen.add(rk)) {
                                todo.add(r);
                            }
                        } else if (isLeaves(t)) {
                            leaves.add(rk);
                        } else if ((dx == 0 ? 1 : 0) + (dy == 0 ? 1 : 0) + (dz == 0 ? 1 : 0) == 2 && structural(t)) {
                            struct++;
                        }
                    }
                }
            }
        }
        boolean tree = leaves.size() * 4 >= size && struct * 10 <= size;
        for (Long k : seen) {
            treeCache.putIfAbsent(k, tree);
        }
        return tree;
    }

    /** Leaves of a natural tree: a tree trunk within 4 blocks, not hedge decor glued to a structure. */
    private boolean treeLeaves(Block b) {
        if (touchesStructure(b)) {
            return false;
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block r = b.getRelative(dx, dy, dz);
                    if (isNaturalLog(r.getType()) && isTree(r)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Resource type a player may harvest here, or null (build blocks, plaza, blocks already regrowing). */
    private Material resource(Block b) {
        long k = key(b);
        if (regen.containsKey(k)) {
            return null;
        }
        Material spot = ores.get(k);
        Material t = b.getType();
        if (spot != null) {
            return t.isAir() ? null : t;
        }
        if (!(isOreType(t) || CROPS.contains(t) || isNaturalLog(t) || isLeaves(t) || isStone(t) || isFlora(t))) {
            return null;
        }
        if (protectedSpot(b)) {
            return null;
        }
        if (isNaturalLog(t)) {
            return isTree(b) ? t : null;
        }
        if (isLeaves(t)) {
            return treeLeaves(b) ? t : null;
        }
        if ((isStone(t) || isOreType(t) || isFlora(t)) && touchesStructure(b)) {
            return null;
        }
        return t;
    }

    private static org.bukkit.block.data.BlockData grown(Material type) {
        org.bukkit.block.data.BlockData d = type.createBlockData();
        if (d instanceof org.bukkit.block.data.Ageable a) {
            a.setAge(a.getMaximumAge());
        }
        return d;
    }

    private static org.bukkit.block.data.BlockData ripe(String data) {
        org.bukkit.block.data.BlockData d = org.bukkit.Bukkit.createBlockData(data);
        if (d instanceof org.bukkit.block.data.Ageable a && !(d.getMaterial() == Material.SUGAR_CANE || d.getMaterial() == Material.CACTUS)) {
            a.setAge(a.getMaximumAge());
        }
        return d;
    }

    private void startRegen(Block block, String original, Material type) {
        long k = key(block);
        regen.put(k, original);
        regenAt.put(k, block.getLocation());
        // Stone/ore bedrock and crop sprouts go in this same tick: the client never sees an air gap.
        org.bukkit.block.data.BlockData hold = stage(original, type);
        if (hold != null && !block.getBlockData().equals(hold)) {
            block.setBlockData(hold, false);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!original.equals(regen.get(k))) {
                return;
            }
            if (isOreType(type)) {
                block.setType(Material.BEDROCK, false);
                later(k, original, ORE_TICKS, () -> restore(k));
            } else if (isStone(type)) {
                block.setType(Material.BEDROCK, false);
                later(k, original, STONE_TICKS, () -> restore(k));
            } else if (isNaturalLog(type)) {
                block.setType(Material.AIR, false);
                later(k, original, LOG_TICKS, () -> restore(k));
            } else if (isLeaves(type)) {
                block.setType(Material.AIR, false);
                later(k, original, LEAF_TICKS, () -> restore(k));
            } else if (org.bukkit.Bukkit.createBlockData(original) instanceof org.bukkit.block.data.Ageable && type != Material.SUGAR_CANE && type != Material.CACTUS) {
                org.bukkit.block.data.BlockData sprout = org.bukkit.Bukkit.createBlockData(original);
                org.bukkit.block.data.Ageable a = (org.bukkit.block.data.Ageable) sprout;
                int max = a.getMaximumAge();
                a.setAge(0);
                block.setBlockData(sprout, false);
                later(k, original, CROP_TICKS / 2, () -> {
                    org.bukkit.block.data.BlockData half = org.bukkit.Bukkit.createBlockData(original);
                    ((org.bukkit.block.data.Ageable) half).setAge(Math.max(1, max / 2));
                    block.setBlockData(half, false);
                });
                later(k, original, CROP_TICKS, () -> restore(k));
            } else {
                block.setType(Material.AIR, false);
                later(k, original, CROP_TICKS, () -> restore(k));
            }
        });
    }

    /** Regen placeholder that replaces a hub resource the moment it is broken; null where air is by design (trees, others). */
    private org.bukkit.block.data.BlockData stage(String original, Material type) {
        if (type == null) {
            return null;
        }
        if (isOreType(type) || isStone(type)) {
            return Material.BEDROCK.createBlockData();
        }
        if (isNaturalLog(type) || isLeaves(type) || isFlora(type) || type == Material.SUGAR_CANE || type == Material.CACTUS) {
            return null;
        }
        try {
            org.bukkit.block.data.BlockData sprout = org.bukkit.Bukkit.createBlockData(original);
            if (sprout instanceof org.bukkit.block.data.Ageable a) {
                a.setAge(0);
                return sprout;
            }
        } catch (IllegalArgumentException ignored) {
            // unknown data: no placeholder
        }
        return null;
    }

    /**
     * Placeholder for a hub resource that is being broken right now (guard already approved it), or null.
     * The SkyWord break path sets it in the break tick instead of letting the block turn into air.
     */
    org.bukkit.block.data.BlockData placeholder(Block block) {
        if (!isHub(block.getWorld())) {
            return null;
        }
        long k = key(block);
        if (regen.containsKey(k)) {
            return null;
        }
        String data = breaking.get(k);
        Material spot = ores.get(k);
        Material type = data != null ? Material.matchMaterial(data.replaceAll("\\[.*", "")) : spot;
        if (type == null) {
            return null;
        }
        return stage(data != null ? data : grown(spot).getAsString(), type);
    }

    private void later(long k, String original, long ticks, Runnable step) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (original.equals(regen.get(k))) {
                step.run();
            }
        }, ticks);
    }

    private void restore(long k) {
        String original = regen.remove(k);
        Location at = regenAt.remove(k);
        if (original == null || at == null) {
            return;
        }
        try {
            org.bukkit.block.data.BlockData back = ripe(original);
            place(at.getBlock(), back);
            if (isFlora(back.getMaterial()) || CROPS.contains(back.getMaterial())) {
                sparkle(at.getBlock());
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Regen could not restore " + original + ": " + e.getMessage());
        }
    }

    /** Server stop: every block still in a regen chain goes back to its grown state. */
    void restoreRegen() {
        stopping = true;
        sparkles.clear();
        int n = 0;
        for (Long k : new ArrayList<>(regen.keySet())) {
            restore(k);
            n++;
        }
        if (n > 0) {
            plugin.getLogger().info("Hub regen: " + n + " blocks restored on stop.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGuardPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (guarded(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGuardBucket(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (guarded(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGuardFill(org.bukkit.event.player.PlayerBucketFillEvent event) {
        if (guarded(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGuardSign(org.bukkit.event.block.SignChangeEvent event) {
        if (guarded(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGuardTrample(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND && isHub(event.getClickedBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardBed(org.bukkit.event.player.PlayerBedEnterEvent event) {
        if (isHub(event.getBed().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardFlow(org.bukkit.event.block.BlockFromToEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardFade(org.bukkit.event.block.BlockFadeEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardBurn(org.bukkit.event.block.BlockBurnEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardFall(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (isHub(event.getBlock().getWorld()) && !(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        Material physType = event.getBlock().getType();
        if (isHub(event.getBlock().getWorld()) && (physType.hasGravity() || physType == Material.NETHER_PORTAL)) {
            event.setCancelled(true);
        }
    }

    private static long key(Block block) {
        return ((long) block.getX() & 0x3FFFFFFL) << 38
                | ((long) block.getZ() & 0x3FFFFFFL) << 12
                | (block.getY() & 0xFFFL);
    }
}
