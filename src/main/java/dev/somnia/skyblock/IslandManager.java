package dev.somnia.skyblock;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

final class IslandManager {
    static final String WORLD_NAME = "skyblock";
    static final int GROUND_Y = 100;
    static final int SPACING = 512;
    static final int RADIUS = 48;
    static final int LAYOUT = 3;
    static final int GEN_DX = 12;
    static final int GEN_DZ = -3;

    private final SomniaSkyblockPlugin plugin;
    private World world;
    private int nextIndex;
    /** Starter template (island/starter.sbh) for new islands; null = built-in generator. */
    private IslandTemplate template;

    IslandManager(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.generator(new VoidGenerator());
            creator.generateStructures(false);
            world = creator.createWorld();
        }
        if (world == null) {
            throw new IllegalStateException("Could not create the skyblock world");
        }
        world.setGameRule(GameRule.DO_MOB_SPAWNING, true);
        world.setGameRule(GameRule.SPAWN_MONSTERS, true);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
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
        world.setSpawnFlags(true, true);
        world.setDifficulty(Difficulty.EASY);
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);
        nextIndex = plugin.getConfig().getInt("next-island", 0);
        template = IslandTemplate.load(new java.io.File(plugin.getDataFolder(), "island/starter.sbh"), plugin.getLogger());
        world.setSpawnLocation(0, GROUND_Y + 1, 0);
        if (!world.getBlockAt(0, 64, 0).getType().isAir()) {
            plugin.getLogger().severe("The skyblock world contains normal terrain. Delete server/skyblock and restart.");
        }
        java.util.List<String> fresh = plugin.getConfig().getStringList("fresh-start");
        plugin.profiles().eachReady(profile -> {
            if (fresh.contains(profile.uuid.toString())) {
                freshStart(profile);
                return;
            }
            if (plugin.coops() != null && plugin.coops().isGuestMember(profile.uuid)) {
                return; // the owner's profile looks after the shared island
            }
            if (profile.layout < LAYOUT) {
                repaste(profile, false);
                org.bukkit.entity.Player online = Bukkit.getPlayer(profile.uuid);
                if (online != null && online.getWorld().equals(world)) {
                    teleport(online, profile);
                }
            } else {
                blankIslandInfoSigns(profile.islandX, profile.islandZ);
            }
        });
        if (!fresh.isEmpty()) {
            plugin.getConfig().set("fresh-start", null);
            plugin.saveConfig();
        }
    }

    World world() {
        return world;
    }

    /**
     * One-shot full fresh start for the profiles listed in config "fresh-start" (then the list is removed).
     * Keeps the purse (wallet, sealed key), the island slot and its deed. Skills/collections get a base equal to
     * what is onchain (SkySkills is add-only), the island is rebuilt from the starter template, and bagCleared=false
     * makes the next login clear the onchain bag from the player's own purse.
     */
    private void freshStart(Profile p) {
        // A co-op member's island is shared: their own data is wiped, the island stays for the others.
        boolean shared = plugin.coops() != null && plugin.coops().inCoop(p.uuid);
        int how = wipe(p, true, shared ? 1 : 2);
        if (how < 0) {
            return;
        }
        boolean onchainReset = how == 1;
        if (shared) {
            p.bagCleared = false;
            p.seenGuide = false;
            plugin.profiles().save(p);
        } else {
            repaste(p, true);
        }
        EventTally.profileReset(p);
        plugin.getLogger().info("Fresh start for " + p.uuid + " (" + p.wallet + "): island " + p.islandX + "," + p.islandZ
                + (shared ? " kept (co-op island)" : " rebuilt from the starter") + ", " + (onchainReset ? "profile reset onchain (SkyProfile) at next login" : "skills base " + java.util.Arrays.toString(p.skillsBase))
                + ", bag clears onchain at next login.");
    }

    /** Joins a co-op: the player's own data is wiped (as a reset) and they move onto the owner's island. */
    void joinCoop(Profile p, Profile owner) {
        wipe(p, false, 0);
        copyIsland(owner, p);
        p.bagCleared = false;
        p.seenGuide = false;
        plugin.profiles().save(p);
        EventTally.profileReset(p);
    }

    /** Leaves (or is kicked from) a co-op: wiped again and given a fresh starter island on their old slot. */
    void leaveCoop(Profile p, int[] home) {
        wipe(p, false, 0);
        Profile host = Coops.host(p);
        boolean useHome = home != null && !(host != p && host.islandX == home[0] && host.islandZ == home[1]);
        if (useHome) {
            p.islandX = home[0];
            p.islandZ = home[1];
        } else {
            int index = nextIndex++;
            plugin.getConfig().set("next-island", nextIndex);
            plugin.saveConfig();
            p.islandX = index * SPACING;
            p.islandZ = 0;
        }
        p.islandReady = true;
        repaste(p, true);
        EventTally.profileReset(p);
    }

    /** The island fields a co-op member shares with the owner. */
    static void copyIsland(Profile from, Profile to) {
        to.islandX = from.islandX;
        to.islandZ = from.islandZ;
        to.islandSpawn = from.islandSpawn == null ? null : from.islandSpawn.clone();
        to.islandNpc = from.islandNpc == null ? null : from.islandNpc.clone();
        to.islandPortalDest = from.islandPortalDest;
        to.layout = from.layout;
        to.islandReady = true;
    }

    /**
     * Wipes the player's own progress (not the island). -1 = skipped, 0 = offset reset, 1 = onchain reset queued.
     * {@code chain}: 0 = co-op join/leave (SkyProfile: the record stays on the old island, the new island is read fresh),
     * 1 = reset only this member's record onchain, 2 = wipe the whole island profile onchain (solo holder).
     */
    private int wipe(Profile p, boolean checkMinions, int chain) {
        if (checkMinions && plugin.minions().countOn(p) > 0) {
            plugin.getLogger().severe("Fresh start skipped for " + p.uuid + ": minions are placed on the island.");
            return -1;
        }
        boolean onchainReset = plugin.skillChain() != null
                && (chain == 0 ? plugin.skillChain().switchIsland(p) : plugin.skillChain().requestReset(p, chain == 2));
        synchronized (p) {
            if (!onchainReset) {
                for (Skills.Skill skill : Skills.Skill.values()) {
                    int i = SkillChain.id(skill);
                    p.skillsBase[i] += Skills.xp(p, skill);
                    Skills.put(p, skill, 0);
                }
                for (int i = 0; i < Collects.ENTRIES.size() && i < SkillChain.COLLECTIONS; i++) {
                    p.colBase[i] += Collects.amount(p, Collects.ENTRIES.get(i));
                }
            }
            p.collections.clear();
            p.cobble = 0;
            p.oak = 0;
            p.wheat = 0;
            p.enchantedCobble = 0;
            p.enchantedOak = 0;
            p.pendingCobble = 0;
            p.pendingOak = 0;
            p.pendingWheat = 0;
            p.coins = 0;
            p.kills = 0;
            p.bestWave = 0;
            p.lastCheckpoint = 0;
            p.questStep = 0;
            p.questProgress = 0;
            p.questSkip.clear();
            p.questRetired = 0;
            p.questVersion = Quests.VERSION;
            java.util.Arrays.fill(p.xpTenths, 0);
            p.resets++; // the website leaderboard starts this player's count again
            p.discovered.clear();
            p.seenHub = false;
            p.seenCoal = false;
            p.seenGold = false;
            p.minionsHeld = 0;
            p.heldTiers.clear();
            p.starterMinion = false;
            p.lastWorld = null;
            p.lastPos = null;
        }
        return onchainReset ? 1 : 0;
    }

    /**
     * SkyBlock Menu > Profile Management > Reset Profile (after the confirm page): the same full reset as the admin
     * fresh start, for the player's own profile. Open trades and the stash would lose items or escrowed STT, so they
     * must be settled first. The player is disconnected first, so nothing in game still holds the old state; the
     * onchain parts (SkySkills resetProfile, SkyWord bag clear) are sent from their purse when they join again.
     */
    void resetProfile(org.bukkit.entity.Player player) {
        net.kyori.adventure.text.format.NamedTextColor red = net.kyori.adventure.text.format.NamedTextColor.RED;
        Profile p = plugin.profiles().get(player.getUniqueId());
        if (!p.islandReady || p.wallet == null || p.wallet.isBlank()) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Your profile is not set up yet, there is nothing to reset.", red));
            return;
        }
        if (plugin.words() != null && plugin.words().busy(player)) {
            player.sendMessage(net.kyori.adventure.text.Component.text("A signature is still in flight. Try again in a few seconds.", red));
            return;
        }
        int trades = plugin.market() == null ? 0 : plugin.market().openTrades(p.uuid);
        if (trades > 0) {
            player.sendMessage(net.kyori.adventure.text.Component.text("You have " + trades + " open Bazaar/Auction House trade(s). Cancel or finish them first (/bz orders, /ah), then reset.", red));
            return;
        }
        if (plugin.market() != null && plugin.market().hasStash(p.uuid)) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Items wait in your stash. Claim them first (/bz claim), then reset.", red));
            return;
        }
        long placed = plugin.minions().countOn(p);
        if (placed > 0) {
            player.sendMessage(net.kyori.adventure.text.Component.text("You have " + placed + " placed minion(s); the reset cannot run yet.", red));
            return;
        }
        Coops coops = plugin.coops();
        if (coops != null && coops.isGuestMember(p.uuid)) {
            player.sendMessage(net.kyori.adventure.text.Component.text("You are in a co-op. Leave it first (Profile Management ➜ Co-op); leaving resets your profile.", red));
            return;
        }
        if (coops != null && coops.inCoop(p.uuid)) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Your co-op still has members. Kick them first (/coopkick), then reset.", red));
            return;
        }
        if (plugin.deeds() != null && plugin.deeds().listedActive(p) != null) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Your island is listed in the Island Market. Cancel the listing first (Auction House ➜ Island Market ➜ Your Deeds), then reset.", red));
            return;
        }
        String limit = coops == null ? null : coops.resetLimit(p);
        if (limit != null) {
            player.sendMessage(net.kyori.adventure.text.Component.text(limit, red));
            return;
        }
        java.util.UUID id = p.uuid;
        String name = player.getName();
        plugin.getLogger().info("Profile reset requested by " + name + " (" + id + ", " + p.wallet + ").");
        player.kick(net.kyori.adventure.text.Component.text("Your profile is being reset. Join again to start fresh.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        // Two ticks later the quit handlers (craft grid, bag, sessions) have run; then wipe and save.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(id) != null) {
                plugin.getLogger().warning("Profile reset for " + name + " skipped: the player is online again.");
                return;
            }
            Profile fresh = plugin.profiles().get(id);
            fresh.lastResetAt = System.currentTimeMillis();
            freshStart(fresh);
            plugin.profiles().save(fresh);
            EventTally.profileReset(fresh);
            plugin.getLogger().info("Profile reset done for " + name + " (resets: " + fresh.resets + ").");
        }, 2L);
    }

    void ensureIsland(Profile profile) {
        if (profile.islandReady) {
            return;
        }
        int index = nextIndex++;
        plugin.getConfig().set("next-island", nextIndex);
        plugin.saveConfig();
        profile.islandX = index * SPACING;
        profile.islandZ = 0;
        // A brand-new island slot: use the starter template when there is one.
        repaste(profile, true);
        profile.islandReady = true;
    }

    boolean isGenerator(Block block) {
        if (world == null || block.getWorld() == null || !block.getWorld().equals(world)) {
            return false;
        }
        if (block.getY() != GROUND_Y) {
            return false;
        }
        return block.getZ() == GEN_DZ && Math.floorMod(block.getX() - GEN_DX, SPACING) == 0;
    }

    private void repaste(Profile profile, boolean useTemplate) {
        clearStarter(profile.islandX, profile.islandZ);
        profile.islandSpawn = null;
        profile.islandNpc = null;
        profile.islandPortalDest = null;
        boolean pasted = false;
        if (useTemplate && template != null) {
            try {
                pasteTemplate(profile);
                pasted = true;
            } catch (java.io.IOException | RuntimeException e) {
                plugin.getLogger().warning("Island template paste failed (" + e.getMessage() + "); using the generator.");
                clearStarter(profile.islandX, profile.islandZ);
                profile.islandSpawn = null;
                profile.islandNpc = null;
                profile.islandPortalDest = null;
            }
        }
        if (!pasted) {
            paste(profile.islandX, profile.islandZ);
        }
        profile.layout = LAYOUT;
        profile.seenGuide = false;
        profile.bagCleared = false;
        plugin.profiles().save(profile);
        if (plugin.coops() != null) {
            plugin.coops().islandRebuilt(profile); // co-op members follow the owner's new spawn and NPC spots
        }
        if (plugin.deeds() != null) {
            plugin.deeds().refreshSlot(profile); // the deed's slot record follows the new spawn and NPC spots
        }
    }

    private void clearStarter(int ox, int oz) {
        for (int x = -96; x <= 96; x++) {
            for (int z = -96; z <= 96; z++) {
                for (int y = GROUND_Y - 24; y <= GROUND_Y + 32; y++) {
                    Block block = world.getBlockAt(ox + x, y, oz + z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
        Location center = new Location(world, ox, GROUND_Y, oz);
        for (Entity entity : world.getNearbyEntities(center, 100, 48, 100)) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
    }

    void teleport(org.bukkit.entity.Player player, Profile profile) {
        blankIslandInfoSigns(profile.islandX, profile.islandZ);
        player.teleport(home(profile));
        welcome(player, profile);
    }

    /** "Private Island!" title and chat line plus a profile line, on every island arrival. */
    void welcome(org.bukkit.entity.Player player, Profile profile) {
        net.kyori.adventure.text.format.NamedTextColor green = net.kyori.adventure.text.format.NamedTextColor.GREEN;
        net.kyori.adventure.text.format.NamedTextColor gray = net.kyori.adventure.text.format.NamedTextColor.GRAY;
        String wallet = profile.wallet == null || profile.wallet.length() < 10 ? "no purse yet" : WalletService.shortAddress(profile.wallet);
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("Private Island!", green).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true),
                net.kyori.adventure.text.Component.text("Profile " + wallet, gray)));
        player.sendMessage(net.kyori.adventure.text.Component.text("Private Island!", green).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        player.sendMessage(net.kyori.adventure.text.Component.text("You are playing on profile ", gray)
                .append(net.kyori.adventure.text.Component.text(wallet, net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                .append(net.kyori.adventure.text.Component.text(" · SkyBlock Level " + Skills.skyblockLevel(profile), net.kyori.adventure.text.format.NamedTextColor.AQUA)));
    }

    /** Welcome tips for a template island, or null for the generator island's built-in tips. */
    java.util.List<String> tips(Profile profile) {
        if (profile.islandSpawn == null) {
            return null;
        }
        if (template != null && !template.tips.isEmpty()) {
            return template.tips;
        }
        return java.util.List.of("The cobble machine regrows the framed cobblestone. Mine it with a pickaxe you craft.",
                "The starter chest has blocks and buckets; craft your tools from the tree's logs. The portal goes to the hub. /hub and /is.",
                Minions.ENABLED ? "Place your minion with /minion place. Coal is first; a stone pick reaches the iron." : "Coal is first; a stone pick reaches the iron. Minions: SOON.");
    }

    /** Island spawn: the template's spawn for template islands, otherwise the origin. */
    Location home(Profile profile) {
        double[] s = profile.islandSpawn;
        if (s != null) {
            return new Location(world, profile.islandX + s[0], GROUND_Y + s[1], profile.islandZ + s[2], (float) s[3], (float) s[4]);
        }
        return new Location(world, profile.islandX + 0.5, GROUND_Y + 1, profile.islandZ + 0.5, 0f, 0f);
    }

    private void pasteTemplate(Profile profile) throws java.io.IOException {
        int ox = profile.islandX;
        int oz = profile.islandZ;
        long placed = template.paste(world, ox, GROUND_Y, oz, plugin.getLogger());
        profile.islandSpawn = template.spawn.clone();
        profile.islandNpc = template.npc == null ? null : template.npc.clone();
        profile.islandPortalDest = template.portalDest;
        if (template.chest != null) {
            int[] c = template.chest;
            fillChest(ox + c[0], GROUND_Y + c[1], oz + c[2], BlockFace.EAST, false);
        }
        // No info holograms on islands (no sign-style text); the minion spot and the hub portal speak for themselves.
        blankIslandInfoSigns(ox, oz, true); // a fresh paste brings the template signs back
        Block gen = world.getBlockAt(ox + GEN_DX, GROUND_Y, oz + GEN_DZ);
        if (gen.getType() != Material.COBBLESTONE) {
            plugin.getLogger().warning("Island template has no cobblestone at the generator spot (" + GEN_DX + ", 0, " + GEN_DZ + "); the generator will not regrow.");
        }
        plugin.getLogger().info("Island at " + ox + "," + oz + " made from " + template.file.getName() + " (" + placed + " blocks).");
    }

    private void hologram(Location at, net.kyori.adventure.text.Component text) {
        world.spawn(at, org.bukkit.entity.TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setPersistent(true);
            d.setShadowed(true);
            d.addScoreboardTag("sb_island_holo");
        });
    }

    /** Opt-in: /island reset confirm rebuilds the player's island from the current starter (template or generator). */
    boolean reset(org.bukkit.entity.Player player, Profile profile, boolean confirm) {
        if (plugin.coops() != null && plugin.coops().isGuestMember(profile.uuid)) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Only " + Coops.name(Coops.host(profile).uuid) + ", the island owner, can rebuild the co-op island.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }
        if (!profile.islandReady) {
            player.sendMessage(net.kyori.adventure.text.Component.text("You have no island yet. /island makes one.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }
        long placedMinions = plugin.minions().countOn(profile);
        if (placedMinions > 0) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Pick up your " + placedMinions + " placed minion(s) first; they keep their tier.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }
        if (!confirm) {
            player.sendMessage(net.kyori.adventure.text.Component.text("This wipes every block and chest on your island and builds a fresh starter island"
                    + (template != null ? " (" + template.file.getName() + ")" : "") + ". Your purse, bag, skills and collections stay. Type /island reset confirm.",
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            return true;
        }
        for (org.bukkit.entity.Player other : world.getPlayers()) {
            if (contains(profile, other.getLocation())) {
                plugin.hub().teleport(other);
            }
        }
        boolean bag = profile.bagCleared;
        repaste(profile, true);
        profile.bagCleared = bag; // a reset keeps the bag (repaste on a new island starts it empty)
        plugin.profiles().save(profile);
        teleport(player, profile);
        player.setRespawnLocation(home(profile), true);
        player.sendMessage(net.kyori.adventure.text.Component.text("Your island was rebuilt.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        plugin.getLogger().info(player.getName() + " reset their island at " + profile.islandX + "," + profile.islandZ + ".");
        return true;
    }

    /** True for a block inside the template's portal_frame box on any island (relative to that island's origin). */
    boolean inPortalFrame(Block block) {
        if (template == null || template.portalFrame == null || !block.getWorld().equals(world)) {
            return false;
        }
        int[] f = template.portalFrame;
        int ox = Math.round(block.getX() / (float) SPACING) * SPACING;
        int oz = Math.round(block.getZ() / (float) SPACING) * SPACING;
        int x = block.getX() - ox;
        int y = block.getY() - GROUND_Y;
        int z = block.getZ() - oz;
        return x >= Math.min(f[0], f[3]) && x <= Math.max(f[0], f[3])
                && y >= Math.min(f[1], f[4]) && y <= Math.max(f[1], f[4])
                && z >= Math.min(f[2], f[5]) && z <= Math.max(f[2], f[5]);
    }

    boolean contains(Profile profile, Location location) {
        if (location.getWorld() == null || !location.getWorld().equals(world)) {
            return false;
        }
        int dx = Math.abs(location.getBlockX() - profile.islandX);
        int dz = Math.abs(location.getBlockZ() - profile.islandZ);
        return dx <= RADIUS && dz <= RADIUS;
    }

    /** z = -12..30. Values are inclusive x of the grass before coves and spurs. */
    private static final int[][] SPAN = {
            {-1, 2}, {-4, 5}, {-6, 7}, {-8, 8}, {-9, 10},
            {-11, 11}, {-12, 12}, {-12, 12}, {-13, 13}, {-13, 12},
            {-14, 13}, {-14, 13}, {-14, 13}, {-13, 13}, {-12, 12},
            {-11, 11}, {-11, 10}, {-10, 9}, {-9, 7}, {-6, 5},
            {-4, 4}, {-3, 3}, {-2, 2}, {-1, 1}, {-1, 1},
            {0, 2}, {-1, 3}, {-3, 5}, {-5, 7}, {-7, 8},
            {-8, 9}, {-10, 10}, {-11, 11}, {-11, 11}, {-10, 11},
            {-10, 10}, {-9, 9}, {-8, 8}, {-7, 7}, {-6, 6},
            {-5, 5}, {-3, 4}, {-1, 2}
    };

    private void paste(int ox, int oz) {
        if (SPAN.length != 43) {
            throw new IllegalStateException("Island span is " + SPAN.length);
        }
        for (int z = -16; z <= 34; z++) {
            for (int x = -20; x <= 20; x++) {
                if (land(x, z)) {
                    sculpt(ox, oz, x, z);
                }
            }
        }
        flora(ox, oz);
        pond(ox, oz);
        oak(ox - 5, oz - 7, surfaceY(-5, -7));
        bush(ox, oz);
        fallenLog(ox, oz);
        boulder(ox, oz);
        garden(ox, oz);
        world.getBlockAt(ox + 2, GROUND_Y + 1, oz - 1).setType(Material.CRAFTING_TABLE);
        path(ox, oz);
        pasteGenerator(ox + GEN_DX, oz + GEN_DZ);
        cave(ox, oz);
        portal(ox - 1, oz + 25);
        minion(ox + 5, oz + 22);
        jerry(ox - 6, oz + 23);
        campfire(ox + 3, oz + 17);
        torch(ox + 3, GROUND_Y + 1, oz - 1);
        torch(ox + 2, GROUND_Y + 1, oz + 24);
        accent(ox, oz, 1, -2, Material.DANDELION);
        accent(ox, oz, -2, 2, Material.POPPY);
        accent(ox, oz, 4, 8, Material.WHITE_TULIP);
        accent(ox, oz, -6, 16, Material.AZURE_BLUET);
        accent(ox, oz, 6, 20, Material.DANDELION);
        expect(ox + GEN_DX, GROUND_Y, oz + GEN_DZ, Material.COBBLESTONE);
        expect(ox + GEN_DX, GROUND_Y, oz + GEN_DZ - 1, Material.WATER);
        expect(ox + GEN_DX + 1, GROUND_Y + 1, oz + GEN_DZ, Material.LAVA);
        expect(ox + GEN_DX, GROUND_Y + 1, oz + GEN_DZ, Material.AIR);
    }

    private static boolean land(int x, int z) {
        if (extra(x, z)) {
            return true;
        }
        if (z < -12 || z > 30) {
            return false;
        }
        int[] span = SPAN[z + 12];
        if (x < span[0] || x > span[1]) {
            return false;
        }
        return !cut(x, z);
    }

    private static boolean cut(int x, int z) {
        if (z >= 0 && z <= 2 && x >= 11) {
            return true;
        }
        if (z == 4 && x <= -9) {
            return true;
        }
        if (z == -8 && x >= 8) {
            return true;
        }
        return z == 20 && x >= 9;
    }

    private static boolean extra(int x, int z) {
        if (z == 0 && (x == -15 || x == -16)) {
            return true;
        }
        if (z == 1 && x == -15) {
            return true;
        }
        if (z == -2 && (x == 14 || x == 15)) {
            return true;
        }
        if (z == -1 && x == 14) {
            return true;
        }
        if (z == 22 && (x == 12 || x == 13)) {
            return true;
        }
        if (z == 23 && x == 12) {
            return true;
        }
        if (z == 8 && (x == 5 || x == 6)) {
            return true;
        }
        return z == 9 && x == 5;
    }

    private static int mix(int x, int z) {
        int hash = x * 734287 + z * 912271;
        hash ^= hash >>> 16;
        return hash & Integer.MAX_VALUE;
    }

    private int edgeOf(int x, int z) {
        for (int distance = 1; distance <= 8; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == distance && !land(x + dx, z + dz)) {
                        return distance;
                    }
                }
            }
        }
        return 9;
    }

    private boolean hill(int x, int z) {
        return x >= -8 && x <= -1 && z >= -9 && z <= -5 && land(x, z);
    }

    private boolean lowBank(int x, int z) {
        if (z < 2 || z > 8 || (x >= -8 && x <= -3 && z >= 2 && z <= 6)) {
            return false;
        }
        return edgeOf(x, z) == 1 && mix(x, z) % 2 == 0;
    }

    private int surfaceY(int x, int z) {
        if (hill(x, z)) {
            return GROUND_Y + 1;
        }
        if (lowBank(x, z)) {
            return GROUND_Y - 1;
        }
        return GROUND_Y;
    }

    private void sculpt(int ox, int oz, int x, int z) {
        int edge = edgeOf(x, z);
        int top = surfaceY(x, z);
        int layers = 3 + Math.min(edge, 8);
        for (int depth = 0; depth < layers; depth++) {
            world.getBlockAt(ox + x, top - depth, oz + z).setType(layer(x, z, depth, edge));
        }
        if (edge == 1) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0 && dz == 0) || land(x + dx, z + dz)) {
                        continue;
                    }
                    Block lip = world.getBlockAt(ox + x + dx, top - 1, oz + z + dz);
                    if (lip.getType().isAir()) {
                        lip.setType(Material.DIRT);
                    }
                }
            }
        }
        if (edge >= 5 && mix(x, z) % 5 == 0) {
            drip(ox + x, top - layers, oz + z);
        }
    }

    private Material layer(int x, int z, int depth, int edge) {
        if (depth == 0) {
            if (edge == 1 && mix(x, z) % 3 == 0) {
                return mix(x, z) % 2 == 0 ? Material.COBBLESTONE : Material.ANDESITE;
            }
            if (edge >= 3 && !(x == 0 && z == 0) && mix(x, z) % 31 == 0) {
                return Material.COARSE_DIRT;
            }
            return Material.GRASS_BLOCK;
        }
        if (depth <= 2) {
            return Material.DIRT;
        }
        int hash = mix(x + depth * 13, z + depth * 17);
        if (hash % 8 == 0) {
            return Material.ANDESITE;
        }
        if (hash % 11 == 0) {
            return Material.DIORITE;
        }
        if (hash % 7 == 0) {
            return Material.COBBLESTONE;
        }
        if (hash % 13 == 0) {
            return Material.MOSSY_COBBLESTONE;
        }
        if (hash % 29 == 0) {
            return Material.GRANITE;
        }
        return Material.STONE;
    }

    private void drip(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (mix(x, z) % 2 == 0) {
            block.setType(Material.COBBLESTONE);
            return;
        }
        block.setType(Material.POINTED_DRIPSTONE);
        if (block.getBlockData() instanceof PointedDripstone dripstone) {
            dripstone.setVerticalDirection(BlockFace.DOWN);
            dripstone.setThickness(PointedDripstone.Thickness.TIP);
            block.setBlockData(dripstone);
        }
    }

    private void flora(int ox, int oz) {
        Material[] flowers = {Material.WHITE_TULIP, Material.POPPY, Material.DANDELION, Material.AZURE_BLUET};
        for (int z = -16; z <= 34; z++) {
            for (int x = -20; x <= 20; x++) {
                if (!land(x, z) || (Math.abs(x) <= 1 && Math.abs(z) <= 1)) {
                    continue;
                }
                int y = surfaceY(x, z);
                if (world.getBlockAt(ox + x, y, oz + z).getType() != Material.GRASS_BLOCK) {
                    continue;
                }
                Block above = world.getBlockAt(ox + x, y + 1, oz + z);
                if (!above.getType().isAir()) {
                    continue;
                }
                int hash = mix(x, z);
                if (hash % 9 == 0) {
                    above.setType(Material.SHORT_GRASS);
                } else if (hash % 17 == 0) {
                    above.setType(Material.FERN);
                } else if (hash % 23 == 0) {
                    above.setType(flowers[(hash / 23) % flowers.length]);
                }
            }
        }
    }

    private void pond(int ox, int oz) {
        int[][] cells = {{-11, -2}, {-10, -2}, {-11, -1}, {-10, -1}, {-11, 0}};
        for (int[] cell : cells) {
            source(ox + cell[0], GROUND_Y, oz + cell[1], Material.WATER);
        }
        Block lily = world.getBlockAt(ox - 10, GROUND_Y + 1, oz - 2);
        if (lily.getType().isAir()) {
            lily.setType(Material.LILY_PAD);
        }
        Block bank = world.getBlockAt(ox - 9, GROUND_Y, oz - 1);
        if (bank.getType() == Material.GRASS_BLOCK || bank.getType() == Material.DIRT) {
            bank.setType(Material.DIRT);
            world.getBlockAt(ox - 9, GROUND_Y + 1, oz - 1).setType(Material.SUGAR_CANE);
        }
    }

    private void oak(int x, int z, int ground) {
        for (int height = 1; height <= 5; height++) {
            world.getBlockAt(x, ground + height, z).setType(Material.OAK_LOG);
        }
        canopy(x, ground + 4, z, 3);
        canopy(x, ground + 5, z, 2);
        canopy(x, ground + 6, z, 2);
        canopy(x, ground + 7, z, 1);
        placeLeaves(world.getBlockAt(x, ground + 8, z));
    }

    private void canopy(int x, int y, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int ax = Math.abs(dx);
                int az = Math.abs(dz);
                if (ax == radius && az == radius) {
                    continue;
                }
                if (radius >= 3 && ax + az >= 5) {
                    continue;
                }
                placeLeaves(world.getBlockAt(x + dx, y, z + dz));
            }
        }
    }

    private void bush(int ox, int oz) {
        int ground = surfaceY(6, -6);
        world.getBlockAt(ox + 6, ground + 1, oz - 6).setType(Material.OAK_LOG);
        canopy(ox + 6, ground + 1, oz - 6, 1);
        canopy(ox + 6, ground + 2, oz - 6, 1);
    }

    private void fallenLog(int ox, int oz) {
        for (int x = 3; x <= 4; x++) {
            Block log = world.getBlockAt(ox + x, GROUND_Y + 1, oz - 3);
            log.setType(Material.OAK_LOG);
            if (log.getBlockData() instanceof Orientable axis) {
                axis.setAxis(Axis.X);
                log.setBlockData(axis);
            }
        }
    }

    private void boulder(int ox, int oz) {
        world.getBlockAt(ox + 9, GROUND_Y, oz - 6).setType(Material.ANDESITE);
        world.getBlockAt(ox + 9, GROUND_Y + 1, oz - 6).setType(Material.COBBLESTONE);
        world.getBlockAt(ox + 8, GROUND_Y, oz - 6).setType(Material.COBBLESTONE);
    }

    private void garden(int ox, int oz) {
        for (int x = -8; x <= -3; x++) {
            for (int z = 2; z <= 6; z++) {
                if (!land(x, z)) {
                    continue;
                }
                boolean rim = x == -8 || x == -3 || z == 2 || z == 6;
                Block ground = world.getBlockAt(ox + x, GROUND_Y, oz + z);
                Block above = world.getBlockAt(ox + x, GROUND_Y + 1, oz + z);
                if (rim) {
                    ground.setType(Material.DIRT);
                    if (x == -3 && z == 4) {
                        above.setType(Material.OAK_FENCE_GATE);
                        if (above.getBlockData() instanceof Gate gate) {
                            gate.setFacing(BlockFace.EAST);
                            gate.setOpen(false);
                            above.setBlockData(gate);
                        }
                    } else {
                        above.setType(Material.OAK_FENCE);
                    }
                    continue;
                }
                if (x == -6 && z == 4) {
                    above.setType(Material.AIR);
                    source(ox + x, GROUND_Y, oz + z, Material.WATER);
                    continue;
                }
                ground.setType(Material.FARMLAND);
                if (ground.getBlockData() instanceof Farmland soil) {
                    soil.setMoisture(soil.getMaximumMoisture());
                    ground.setBlockData(soil);
                }
                above.setType(Material.WHEAT);
                if (above.getBlockData() instanceof Ageable age) {
                    age.setAge(age.getMaximumAge());
                    above.setBlockData(age);
                }
            }
        }
    }

    private void path(int ox, int oz) {
        for (int z = 9; z <= 24; z++) {
            for (int x = 0; x <= 1; x++) {
                if (!land(x, z)) {
                    continue;
                }
                if (z >= 11 && z <= 13 && x == 1) {
                    continue;
                }
                int y = surfaceY(x, z);
                world.getBlockAt(ox + x, y, oz + z).setType(mix(x, z) % 5 == 0 ? Material.COARSE_DIRT : Material.DIRT_PATH);
                world.getBlockAt(ox + x, y + 1, oz + z).setType(Material.AIR);
                if (z >= 11 && z <= 13) {
                    world.getBlockAt(ox + x, y - 1, oz + z).setType(Material.COBBLESTONE);
                }
            }
        }
        for (int z = 11; z <= 13; z++) {
            for (int x = -2; x <= 3; x++) {
                if (x == 0 || !land(x, z) || edgeOf(x, z) != 1) {
                    continue;
                }
                int y = surfaceY(x, z);
                world.getBlockAt(ox + x, y, oz + z).setType(Material.DIRT);
                world.getBlockAt(ox + x, y + 1, oz + z).setType(Material.SPRUCE_FENCE);
                world.getBlockAt(ox + x, y - 1, oz + z).setType(Material.COBBLESTONE);
            }
            if (!land(-1, z)) {
                world.getBlockAt(ox - 1, GROUND_Y, oz + z).setType(Material.DIRT);
                world.getBlockAt(ox - 1, GROUND_Y - 1, oz + z).setType(Material.COBBLESTONE);
                world.getBlockAt(ox - 1, GROUND_Y + 1, oz + z).setType(Material.SPRUCE_FENCE);
            }
        }
    }

    private void cave(int ox, int oz) {
        int y = GROUND_Y;
        for (int x = -2; x <= 2; x++) {
            for (int z = 18; z <= 22; z++) {
                if (!land(x, z)) {
                    continue;
                }
                boolean mouth = Math.abs(x) <= 1 && z == 18;
                boolean hollow = Math.abs(x) <= 1 && z >= 19 && z <= 21;
                world.getBlockAt(ox + x, y, oz + z).setType(hollow || mouth ? Material.MOSSY_COBBLESTONE : Material.DIRT);
                world.getBlockAt(ox + x, y + 1, oz + z).setType(hollow || mouth ? Material.AIR : Material.DIRT);
                world.getBlockAt(ox + x, y + 2, oz + z).setType(hollow || mouth ? Material.AIR : Material.DIRT);
                world.getBlockAt(ox + x, y + 3, oz + z).setType(hollow ? Material.GRASS_BLOCK : Material.DIRT);
            }
        }
        fillChest(ox, y + 1, oz + 21, BlockFace.NORTH, true);
        Block torch = world.getBlockAt(ox + 1, y + 2, oz + 20);
        torch.setType(Material.WALL_TORCH);
        if (torch.getBlockData() instanceof Directional facing) {
            facing.setFacing(BlockFace.WEST);
            torch.setBlockData(facing);
        }
    }

    private void fillChest(int x, int y, int z, BlockFace facing, boolean base) {
        if (base) {
            world.getBlockAt(x, y - 1, z).setType(Material.MOSSY_COBBLESTONE);
        }
        Block chestBlock = world.getBlockAt(x, y, z);
        boolean keep = !base && chestBlock.getType() == Material.CHEST;
        if (!keep) {
            chestBlock.setType(Material.CHEST);
        }
        if (!keep && chestBlock.getBlockData() instanceof Directional direction) {
            direction.setFacing(facing);
            chestBlock.setBlockData(direction);
        }
        if (chestBlock.getState() instanceof Chest chest) {
            // Exactly the starter kit, nothing else (a template chest is emptied first).
            chest.getBlockInventory().clear();
            chest.getBlockInventory().addItem(
                    new ItemStack(Material.DIRT, 5),
                    new ItemStack(Material.GRASS_BLOCK, 7),
                    new ItemStack(Material.COBBLESTONE, 8),
                    new ItemStack(Material.WATER_BUCKET),
                    new ItemStack(Material.LAVA_BUCKET),
                    new ItemStack(Material.BONE_MEAL, 3));
        }
    }

    private void jerry(int x, int z) {
        world.getBlockAt(x, GROUND_Y, z).setType(Material.GRASS_BLOCK);
        world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.AIR);
        Villager jerry = world.spawn(new Location(world, x + 0.5, GROUND_Y + 1, z + 0.5), Villager.class);
        jerry.setProfession(Villager.Profession.NITWIT);
        jerry.setVillagerType(Villager.Type.PLAINS);
        jerry.setAI(false);
        jerry.setAware(false);
        jerry.setSilent(true);
        jerry.setInvulnerable(true);
        jerry.setPersistent(true);
        jerry.setRemoveWhenFarAway(false);
        jerry.setCanPickupItems(false);
        jerry.customName(net.kyori.adventure.text.Component.text("Jerry", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        jerry.setCustomNameVisible(true);
    }

    private void campfire(int x, int z) {
        world.getBlockAt(x, GROUND_Y, z).setType(Material.DIRT);
        Block fire = world.getBlockAt(x, GROUND_Y + 1, z);
        fire.setType(Material.CAMPFIRE);
        if (fire.getBlockData() instanceof Campfire camp) {
            camp.setLit(true);
            fire.setBlockData(camp);
        }
    }

    private void torch(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (!block.getType().isAir()) {
            return;
        }
        block.setType(Material.TORCH);
    }

    private void accent(int ox, int oz, int x, int z, Material flower) {
        if (!land(x, z)) {
            return;
        }
        int y = surfaceY(x, z);
        Block ground = world.getBlockAt(ox + x, y, oz + z);
        Block above = world.getBlockAt(ox + x, y + 1, oz + z);
        if (ground.getType() == Material.GRASS_BLOCK && above.getType().isAir()) {
            above.setType(flower);
        }
    }

    private void source(int x, int y, int z, Material fluid) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(fluid);
        if (block.getBlockData() instanceof Levelled levelled) {
            levelled.setLevel(0);
            block.setBlockData(levelled);
        }
    }

    private void expect(int x, int y, int z, Material type) {
        Material found = world.getBlockAt(x, y, z).getType();
        if (found != type) {
            plugin.getLogger().severe("Island layout mismatch at " + x + "," + y + "," + z + ": " + found + " wanted " + type);
        }
    }

    private void set(int x, int y, int z, Material type) {
        world.getBlockAt(x, y, z).setType(type);
    }

    /** Generator islands only: a template island keeps its own minion pad untouched (no base is built there). */
    private void minion(int x, int z) {
        world.getBlockAt(x, GROUND_Y, z).setType(Material.MOSSY_COBBLESTONE);
        world.getBlockAt(x, GROUND_Y + 1, z).setType(Material.OAK_FENCE);
        Location feet = new Location(world, x + 0.5, GROUND_Y + 1, z + 0.5);
        ArmorStand body = world.spawn(feet, ArmorStand.class);
        body.setSmall(true);
        body.setArms(true);
        body.setBasePlate(false);
        body.setGravity(false);
        body.setInvulnerable(true);
        body.setCustomNameVisible(true);
        body.customName(net.kyori.adventure.text.Component.text("Cobblestone Minion"));
        body.getEquipment().setHelmet(new ItemStack(Material.COBBLESTONE));
        body.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        body.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_PICKAXE));
        ArmorStand label = world.spawn(feet.clone().add(0, 1.15, 0), ArmorStand.class);
        label.setVisible(false);
        label.setMarker(true);
        label.setGravity(false);
        label.setInvulnerable(true);
        label.setCustomNameVisible(true);
        label.customName(net.kyori.adventure.text.Component.text("Soon", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }

    private void portal(int x, int z) {
        for (int dx = 0; dx <= 3; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                boolean frame = dx == 0 || dx == 3 || dy == 0 || dy == 4;
                Block block = world.getBlockAt(x + dx, GROUND_Y + dy, z);
                if (frame) {
                    block.setType(Material.OBSIDIAN);
                    continue;
                }
                block.setType(Material.NETHER_PORTAL);
                if (block.getBlockData() instanceof Orientable orientable) {
                    orientable.setAxis(Axis.X);
                    block.setBlockData(orientable);
                }
            }
        }
    }

    private void placeLeaves(Block block) {
        if (!block.getType().isAir() && block.getType() != Material.OAK_LEAVES) {
            return;
        }
        block.setType(Material.OAK_LEAVES);
        if (block.getBlockData() instanceof Leaves leaves) {
            leaves.setPersistent(false);
            block.setBlockData(leaves);
        }
    }

    /**
     * The face stays at (mx, GROUND_Y, mz). Water is the enclosed source north of it.
     * Lava sits one block up on the east and can only enter the air above the face.
     * The miner stands one block down in the west nook, so the lava seal is a ceiling
     * and the cobble's west side stays open.
     */
    private void pasteGenerator(int mx, int mz) {
        int y = GROUND_Y;
        for (int x = 0; x <= 2; x++) {
            for (int z = -2; z <= 1; z++) {
                for (int dy = -6; dy <= -1; dy++) {
                    set(mx + x, y + dy, mz + z, Material.STONE);
                }
            }
        }
        set(mx - 1, y - 2, mz, Material.STONE_BRICKS);
        set(mx - 2, y - 1, mz, Material.STONE_BRICKS);
        set(mx - 2, y - 1, mz - 1, Material.STONE_BRICKS);
        set(mx - 2, y - 1, mz + 1, Material.STONE_BRICKS);
        set(mx - 2, y, mz, Material.AIR);
        set(mx - 2, y, mz - 1, Material.AIR);
        set(mx - 2, y + 1, mz, Material.AIR);
        set(mx - 2, y + 2, mz, Material.AIR);
        set(mx - 1, y - 1, mz, Material.AIR);
        set(mx - 1, y, mz, Material.AIR);
        set(mx - 1, y + 1, mz, Material.GLASS);
        set(mx - 1, y - 1, mz - 1, Material.STONE_BRICKS);
        set(mx - 1, y, mz - 1, Material.STONE_BRICKS);
        set(mx - 1, y - 1, mz + 1, Material.STONE_BRICKS);
        set(mx - 1, y, mz + 1, Material.STONE_BRICKS);
        set(mx, y, mz - 2, Material.GLASS);
        set(mx, y + 1, mz - 1, Material.STONE_BRICKS);
        set(mx + 1, y, mz - 1, Material.STONE_BRICKS);
        set(mx, y, mz + 1, Material.STONE_BRICKS);
        set(mx, y + 1, mz + 1, Material.STONE_BRICKS);
        set(mx + 1, y, mz, Material.STONE_BRICKS);
        set(mx + 1, y, mz + 1, Material.STONE_BRICKS);
        set(mx + 2, y, mz, Material.STONE_BRICKS);
        set(mx + 2, y + 1, mz, Material.STONE_BRICKS);
        set(mx + 1, y + 1, mz - 1, Material.STONE_BRICKS);
        set(mx + 1, y + 1, mz + 1, Material.STONE_BRICKS);
        set(mx + 2, y + 1, mz - 1, Material.STONE_BRICKS);
        set(mx + 2, y + 1, mz + 1, Material.STONE_BRICKS);
        set(mx + 1, y + 2, mz, Material.STONE_BRICKS);
        set(mx, y + 2, mz, Material.STONE_BRICKS);
        set(mx, y + 1, mz, Material.AIR);
        source(mx, y, mz - 1, Material.WATER);
        source(mx + 1, y + 1, mz, Material.LAVA);
        set(mx, y, mz, Material.COBBLESTONE);
        torch(mx - 2, y, mz - 1);
    }

    /**
     * Signs are not used in the game: the first time an island is seen by this build, every sign block in its area
     * (template signs, the old portal "Hub" and generator "Cobble" signs) becomes air. Done once per island, so
     * anything a player places later is left alone. Old island info holograms are removed by IslandGuide.
     */
    private void blankIslandInfoSigns(int ox, int oz) {
        blankIslandInfoSigns(ox, oz, false);
    }

    private void blankIslandInfoSigns(int ox, int oz, boolean force) {
        if (world == null) {
            return;
        }
        String key = ox + "," + oz;
        java.util.List<String> done = plugin.getConfig().getStringList("island-signs-cleared");
        if (done.contains(key) && !force) {
            return;
        }
        int removed = 0;
        for (int cx = (ox - RADIUS) >> 4; cx <= (ox + RADIUS) >> 4; cx++) {
            for (int cz = (oz - RADIUS) >> 4; cz <= (oz + RADIUS) >> 4; cz++) {
                org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
                for (org.bukkit.block.BlockState state : chunk.getTileEntities(b -> org.bukkit.Tag.ALL_SIGNS.isTagged(b.getType()), false)) {
                    Block block = state.getBlock();
                    block.setType(Material.AIR, false);
                    removed++;
                    plugin.getLogger().info("Removed island sign at " + block.getX() + "," + block.getY() + "," + block.getZ() + ".");
                }
            }
        }
        done = new java.util.ArrayList<>(done);
        if (!done.contains(key)) {
            done.add(key);
        }
        plugin.getConfig().set("island-signs-cleared", done);
        plugin.saveConfig();
        plugin.getLogger().info("Island " + key + ": " + removed + " sign(s) removed (once per island).");
    }
}
