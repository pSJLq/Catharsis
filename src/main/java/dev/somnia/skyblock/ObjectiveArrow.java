package dev.somnia.skyblock;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Where the current objective is, for the sidebar arrow next to "Objective". Only targets in the player's own
 * world (and, on the island world, only their own island) get a direction; anything else shows the plain marker.
 */
final class ObjectiveArrow {
    /** U+2190..U+2199 (unicode page 21): in the 1.8 font pages as well as the modern font. Clockwise from ahead. */
    static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    /** Shown when there is no target here, or the player is already there. */
    static final String NONE = "»";
    /** Closer than this (blocks, horizontal) counts as arrived. */
    private static final double ARRIVED = 3.0;

    private ObjectiveArrow() {
    }

    /** The glyph for this player's current objective. */
    static String glyph(SomniaSkyblockPlugin plugin, Player player, Profile profile, Quests.Step step) {
        if (step == null) {
            return NONE;
        }
        Location target;
        try {
            target = target(plugin, player, profile, step);
        } catch (RuntimeException e) {
            return NONE;
        }
        return glyph(player.getLocation(), target);
    }

    /** Pure direction math (also used by the offline test): target relative to where the player looks. */
    static String glyph(Location from, Location target) {
        if (target == null || target.getWorld() == null || from.getWorld() == null || !target.getWorld().equals(from.getWorld())) {
            return NONE;
        }
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        if (dx * dx + dz * dz < ARRIVED * ARRIVED) {
            return NONE;
        }
        return ARROWS[bucket(from.getYaw(), dx, dz)];
    }

    /** 0 = ahead, 2 = right, 4 = behind, 6 = left (Minecraft yaw: 0 faces +z, 90 faces -x). */
    static int bucket(float yaw, double dx, double dz) {
        double toward = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = toward - yaw;
        rel = ((rel % 360) + 540) % 360 - 180; // -180..180
        return Math.floorMod((int) Math.round(rel / 45.0), 8);
    }

    private static Location target(SomniaSkyblockPlugin plugin, Player player, Profile profile, Quests.Step step) {
        boolean onIsland = plugin.islands().world() != null && player.getWorld().equals(plugin.islands().world())
                && profile.islandReady && plugin.islands().contains(profile, player.getLocation());
        boolean inHub = plugin.hub().isHub(player.getWorld());
        return switch (step.event()) {
            case "talk_wren" -> onIsland ? plugin.guide().wren(player.getWorld(), profile) : null;
            case "island_cobble" -> onIsland ? new Location(player.getWorld(), profile.islandX + IslandManager.GEN_DX + 0.5,
                    IslandManager.GROUND_Y, profile.islandZ + IslandManager.GEN_DZ + 0.5) : null;
            case "talk_quest" -> inHub ? plugin.hub().npcNear(player, "quest") : null;
            case "hub_crop", "level_farming" -> inHub ? plugin.hub().warpPoint("farm") : null;
            case "hub_ore" -> inHub ? plugin.hub().warpPoint("mine") : null;
            case "hub_log" -> inHub ? plugin.hub().warpPoint("forest") : null;
            case "kill_graveyard" -> inHub ? plugin.hub().warpPoint("barrows") : null;
            case "kill_ruins" -> inHub ? plugin.hub().warpPoint("ruins") : null;
            case "forge_sword", "forge_armor" -> inHub ? plugin.hub().npcNear(player, "blacksmith") : null;
            case "talk_bank" -> inHub ? plugin.hub().npcNear(player, "bank") : null;
            case "fish" -> inHub ? plugin.hub().warpPoint("wharf") : null;
            case "wave" -> inHub ? plugin.hub().warpPoint("colosseum") : null;
            case "sell" -> inHub ? plugin.hub().npcNear(player, "merchant") : null;
            case "bazaar" -> inHub ? plugin.hub().npcNear(player, "bazaar") : null;
            case "auction" -> inHub ? plugin.hub().npcNear(player, "auction") : null;
            default -> null;
        };
    }
}
