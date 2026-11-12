package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A welcome NPC ("Wren the Island Guide") with a CLICK hologram next to each player's island spawn.
 * Entities only: the island blocks and the island layout are never touched.
 */
final class IslandGuide implements Listener {
    private static final String TAG = "sb-guide";
    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, UUID[]> spawned = new HashMap<>();
    private int islandHolosRemoved;

    IslandGuide(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 60L, 100L);
    }

    private void tick() {
        World world = plugin.islands().world();
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            // One Wren per island: co-op members share the owner's.
            Profile profile = Coops.host(plugin.profiles().get(player.getUniqueId()));
            if (!profile.islandReady || !plugin.islands().contains(profile, player.getLocation())) {
                continue;
            }
            ensure(world, profile);
        }
        // drop guides whose island is empty
        spawned.entrySet().removeIf(e -> {
            Profile profile = plugin.profiles().get(e.getKey());
            boolean someone = false;
            for (Player p : world.getPlayers()) {
                if (plugin.islands().contains(profile, p.getLocation())) {
                    someone = true;
                    break;
                }
            }
            if (someone) {
                return false;
            }
            for (UUID id : e.getValue()) {
                Entity entity = world.getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
                Npcs.forget(id);
            }
            return true;
        });
    }

    private void ensure(World world, Profile profile) {
        UUID[] ids = spawned.get(profile.uuid);
        if (ids != null) {
            Entity v = world.getEntity(ids[0]);
            if (v != null && v.isValid()) {
                return;
            }
        }
        Location base = spot(world, profile);
        if (base == null) {
            return;
        }
        String skin = plugin.getConfig().getString("npc-skins.guide", "default:alex:slim");
        Npcs.Spawned made = Npcs.spawn(world, base, TAG, "Wren the Island Guide", NamedTextColor.YELLOW, skin, plugin.getLogger());
        spawned.put(profile.uuid, new UUID[]{made.body(), made.label(), made.nameLine()});
    }

    /** Where Wren stands on this player's island (her entity, else her spot), or null. For the objective arrow. */
    Location wren(World world, Profile profile) {
        UUID[] ids = spawned.get(Coops.host(profile).uuid);
        if (ids != null) {
            Entity v = world.getEntity(ids[0]);
            if (v != null && v.isValid()) {
                return v.getLocation();
            }
        }
        if (profile.islandNpc != null) {
            double[] n = profile.islandNpc;
            return new Location(world, profile.islandX + Math.floor(n[0]) + 0.5, IslandManager.GROUND_Y + n[1], profile.islandZ + Math.floor(n[2]) + 0.5);
        }
        return null;
    }

    /** A free standing spot a few blocks from the island spawn, found by scanning down. */
    private Location spot(World world, Profile profile) {
        if (profile.islandNpc != null) {
            double[] n = profile.islandNpc;
            int bx = profile.islandX + (int) Math.floor(n[0]);
            int bz = profile.islandZ + (int) Math.floor(n[2]);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                return null;
            }
            return new Location(world, profile.islandX + Math.floor(n[0]) + 0.5, IslandManager.GROUND_Y + n[1], profile.islandZ + Math.floor(n[2]) + 0.5, (float) n[3], 0f);
        }
        int[][] tries = {{3, 2}, {-3, 2}, {3, -2}, {-3, -2}, {2, 3}, {-2, 3}};
        for (int[] t : tries) {
            int x = profile.islandX + t[0];
            int z = profile.islandZ + t[1];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            for (int y = IslandManager.GROUND_Y + 6; y >= IslandManager.GROUND_Y - 6; y--) {
                if (world.getBlockAt(x, y, z).getType().isSolid() && world.getBlockAt(x, y + 1, z).getType().isAir()
                        && world.getBlockAt(x, y + 2, z).getType().isAir()) {
                    Location at = new Location(world, x + 0.5, y + 1, z + 0.5);
                    at.setDirection(new Location(world, profile.islandX + 0.5, y + 1, profile.islandZ + 0.5).toVector().subtract(at.toVector()));
                    return at;
                }
            }
        }
        return null;
    }

    /** Old (browser) clients: hide our name line, the Mannequin's own nametag shows the name there. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                Npcs.hideNameLinesFor(player);
            }
        }, 10L);
    }

    /** NPC labels are never saved; any that come back from disk (older builds, a crash) are leftovers. */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
        int removed = Npcs.removeLeftovers(event.getEntities(), null);
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " leftover NPC label(s) in " + event.getWorld().getName() + ".");
        }
        // Island info holograms ("Minion Spot", "Travel to: Hub") were saved with the world; they are not used any more.
        int holos = 0;
        for (Entity e : event.getEntities()) {
            if (e instanceof TextDisplay && e.getScoreboardTags().contains("sb_island_holo")) {
                e.remove();
                holos++;
            }
        }
        if (holos > 0) {
            islandHolosRemoved += holos;
            plugin.getLogger().info("Removed " + holos + " island info hologram(s) at chunk " + event.getChunk().getX() + "," + event.getChunk().getZ()
                    + " (total this run: " + islandHolosRemoved + ").");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) {
            event.setCancelled(true);
            if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player player) {
                talk(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (event.getAttacked().getScoreboardTags().contains(TAG)) {
            event.setCancelled(true);
            talk(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTalk(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getScoreboardTags().contains(TAG)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
            talk(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTalkAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(TAG)) {
            event.setCancelled(true);
            if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                talk(event.getPlayer());
            }
        }
    }

    private final Map<UUID, Long> talkedAt = new HashMap<>();

    /** Wren's dialogue (line by line); right- or left-click both work. Talking to her is the first intro objective. */
    private void talk(Player player) {
        long now = System.currentTimeMillis();
        Long last = talkedAt.put(player.getUniqueId(), now);
        if (last != null && now - last < 1000) {
            return;
        }
        plugin.getLogger().info("Island guide talked to " + player.getName());
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Quests.Step step = plugin.quests().current(profile);
        java.util.List<String> lines;
        Runnable after = null;
        if (step != null && step.event().equals("talk_wren")) {
            lines = java.util.List.of(
                    "Welcome to your Private Island!",
                    "This little rock is all yours. Everything you gather is written to your purse onchain.",
                    "The starter chest has blocks and buckets, but no tools: you make those yourself.",
                    "Punch the tree for logs, then craft a wooden pickaxe from your SkyBlock Menu.",
                    "When you are ready, the portal takes you to " + plugin.hub().name("plaza") + ".");
            after = () -> plugin.quests().hit(player, "talk_wren", 1);
        } else if (step != null && Quests.STEPS.indexOf(step) < Quests.indexOf("talk_quest")) {
            lines = java.util.List.of("Keep going! " + step.title() + plugin.quests().progressSuffix(profile) + ".", step.hint());
        } else if (step != null) {
            lines = java.util.List.of("Your island is coming along nicely.",
                    "Your next task: " + step.title() + ".",
                    "The portal or /hub takes you to " + plugin.hub().name("plaza") + ".");
        } else {
            lines = java.util.List.of("You have done everything I can teach. Grow your island and your skills!",
                    "More quests SOON.");
        }
        Chat.npc(plugin, player, "Wren", lines, after);
    }
}
