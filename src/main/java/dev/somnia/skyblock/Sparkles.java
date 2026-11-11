package dev.somnia.skyblock;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Regrow cue for plants: when a plant grows back, a small glowing spirit appears above it, a trail of white
 * sparkles flies from it down to the block and ends in a small burst, and the spirit leaves again after
 * {@link #LIFE_TICKS}. Spirits are never saved (non-persistent) and carry a PDC tag; leftovers from older builds,
 * which kept one spirit per 32x32 area for minutes, are removed on enable and whenever their chunk loads.
 */
final class Sparkles {
    private static final int CELL = 32;
    private static final double HOVER = 9.0;
    private static final int TRAIL_TICKS = 8;
    /** About 4 s: trail (8 ticks) + burst, then a short hover before it fades out. */
    private static final long LIFE_TICKS = 80L;

    private final SomniaSkyblockPlugin plugin;
    private final NamespacedKey tag;
    /** One live spirit per cell at most; a regrow in the same cell while it is still there reuses it. */
    private final Map<String, Spirit> spirits = new HashMap<>();
    private int leftoversRemoved;

    private static final class Spirit {
        final ItemDisplay entity;
        long until;

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
        long now = System.currentTimeMillis();
        spirit.until = now + LIFE_TICKS * 50L;
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
        // Leaves LIFE_TICKS after the last regrow it showed.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> expire(spirit), LIFE_TICKS + 1);
    }

    private void expire(Spirit spirit) {
        if (System.currentTimeMillis() + 25L < spirit.until && spirit.entity.isValid()) {
            return; // a newer regrow extended it; that one schedules its own expiry
        }
        spirit.entity.remove();
        spirits.values().remove(spirit);
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
        // Hover a few blocks above the plant itself so the trail stays short.
        Location at = block.getLocation().add(0.5, HOVER, 0.5);
        ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(new ItemStack(Material.NETHER_STAR));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.WHITE);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.35f, 0.35f, 0.35f), new AxisAngle4f()));
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            d.addScoreboardTag("sb-regrow-spirit");
        });
        spirit = new Spirit(display);
        spirits.put(key, spirit);
        return spirit;
    }

    private boolean live(Entity e) {
        for (Spirit s : spirits.values()) {
            if (s.entity.getUniqueId().equals(e.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A regrow spirit this run does not own: our PDC tag, or (older builds) an item display holding a nether star.
     * No other part of the plugin uses item displays, so NPC labels (armor stands) and text displays are never touched.
     */
    private boolean leftover(Entity e) {
        if (!(e instanceof ItemDisplay d) || live(e)) {
            return false;
        }
        if (d.getPersistentDataContainer().has(tag, PersistentDataType.BYTE) || d.getScoreboardTags().contains("sb-regrow-spirit")) {
            return true;
        }
        ItemStack item = d.getItemStack();
        return item != null && item.getType() == Material.NETHER_STAR && d.getScoreboardTags().isEmpty();
    }

    /** Removes leftover spirits among these entities; returns how many. */
    int removeLeftovers(Iterable<? extends Entity> entities, String where) {
        int removed = 0;
        for (Entity e : entities) {
            if (leftover(e)) {
                e.remove();
                removed++;
            }
        }
        if (removed > 0) {
            leftoversRemoved += removed;
            plugin.getLogger().info("Removed " + removed + " leftover regrow spirit(s) " + where + " (total this run: " + leftoversRemoved + ").");
        }
        return removed;
    }

    /** On enable: every loaded world. */
    void removeLeftovers() {
        for (World w : plugin.getServer().getWorlds()) {
            removeLeftovers(w.getEntitiesByClass(ItemDisplay.class), "in " + w.getName());
        }
    }

    int leftoversRemoved() {
        return leftoversRemoved;
    }

    void clear() {
        for (Spirit spirit : spirits.values()) {
            spirit.entity.remove();
        }
        spirits.clear();
    }
}
