package dev.somnia.skyblock;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Regrow cue for plants: a small glowing spirit hovers above each patch of land (one per 32x32 cell, spawned on
 * first use, never saved to the world). When a plant grows back, a trail of white sparkles flies from the spirit
 * down to the block over a few ticks and ends in a small burst there.
 */
final class Sparkles {
    private static final int CELL = 32;
    private static final double HOVER = 9.0;
    private static final int TRAIL_TICKS = 8;
    private static final long IDLE_MS = 5 * 60_000L;

    private final SomniaSkyblockPlugin plugin;
    private final NamespacedKey tag;
    private final Map<String, Spirit> spirits = new HashMap<>();
    private int sweep = -1;

    private static final class Spirit {
        final ItemDisplay entity;
        long used;

        Spirit(ItemDisplay entity) {
            this.entity = entity;
        }
    }

    Sparkles(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "regrow-spirit");
    }

    void regrow(Block block) {
        World world = block.getWorld();
        Location target = block.getLocation().add(0.5, 0.5, 0.5);
        if (world.getNearbyPlayers(target, 48).isEmpty()) {
            return; // nobody to see it
        }
        Spirit spirit = spirit(block);
        spirit.used = System.currentTimeMillis();
        Location from = spirit.entity.getLocation();
        Vector step = target.toVector().subtract(from.toVector()).multiply(1.0 / TRAIL_TICKS);
        for (int t = 0; t <= TRAIL_TICKS; t++) {
            int tick = t;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (tick < TRAIL_TICKS) {
                    // Two points per tick so the trail reads as a line, not dots.
                    for (double f = 0; f < 1.0; f += 0.5) {
                        Location p = from.clone().add(step.clone().multiply(tick + f));
                        world.spawnParticle(Particle.END_ROD, p, 1, 0.01, 0.01, 0.01, 0.0, null, false);
                    }
                } else {
                    world.spawnParticle(Particle.FIREWORK, target, 8, 0.3, 0.3, 0.3, 0.03, null, false);
                    world.spawnParticle(Particle.END_ROD, target, 4, 0.25, 0.25, 0.25, 0.02, null, false);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, target, 5, 0.3, 0.2, 0.3, 0.0, null, false);
                }
            }, tick);
        }
        world.spawnParticle(Particle.END_ROD, from, 3, 0.15, 0.15, 0.15, 0.01, null, false);
    }

    private Spirit spirit(Block block) {
        World world = block.getWorld();
        int cx = Math.floorDiv(block.getX(), CELL);
        int cz = Math.floorDiv(block.getZ(), CELL);
        String key = world.getName() + ":" + cx + ":" + cz;
        Spirit spirit = spirits.get(key);
        if (spirit != null && spirit.entity.isValid()) {
            return spirit;
        }
        double x = cx * CELL + CELL / 2.0;
        double z = cz * CELL + CELL / 2.0;
        // Hover a few blocks above the plant itself (not the cell top) so the trail stays short and inside the area.
        Location at = new Location(world, x, block.getY() + HOVER, z);
        if (at.distanceSquared(block.getLocation()) > 20 * 20) {
            at = block.getLocation().add(0.5, HOVER, 0.5);
        }
        ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(new ItemStack(Material.NETHER_STAR));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.WHITE);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.35f, 0.35f, 0.35f), new AxisAngle4f()));
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        spirit = new Spirit(display);
        spirits.put(key, spirit);
        if (sweep < 0) {
            sweep = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 1200L, 1200L).getTaskId();
        }
        return spirit;
    }

    /** Idle spirits leave after a few minutes without a regrow. */
    private void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Spirit>> it = spirits.entrySet().iterator();
        while (it.hasNext()) {
            Spirit spirit = it.next().getValue();
            if (!spirit.entity.isValid() || now - spirit.used > IDLE_MS) {
                spirit.entity.remove();
                it.remove();
            }
        }
    }

    void clear() {
        for (Spirit spirit : spirits.values()) {
            spirit.entity.remove();
        }
        spirits.clear();
        if (sweep >= 0) {
            plugin.getServer().getScheduler().cancelTask(sweep);
            sweep = -1;
        }
    }
}
