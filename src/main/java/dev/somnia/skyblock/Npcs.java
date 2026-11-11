package dev.somnia.skyblock;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Player-like NPCs: Paper's native Mannequin entity (a player model with a skin, no AI, no extra plugin),
 * plus two label lines (bold yellow CLICK above the coloured name) made of invisible marker armor stands.
 *
 * Skins (config.yml "npc-skins.&lt;id&gt;"):
 *   default:&lt;steve|alex|ari|efe|kai|makena|noor|sunny|zuri&gt;[:slim]  built-in client skins, work offline
 *   player:&lt;MinecraftName&gt;                                       resolved from the player name by the server
 *   texture:&lt;base64 value&gt;|&lt;signature&gt;                           a signed textures property (e.g. from mineskin.org)
 */
final class Npcs {
    static final String TAG = "sb-npc";

    private Npcs() {
    }

    /** label is the CLICK/SOON line, nameLine the coloured name line under it. */
    record Spawned(UUID body, UUID label, UUID nameLine) {
    }

    static Spawned spawn(World world, Location at, String tag, String name, NamedTextColor color, String skin, Logger log) {
        return spawn(world, at, tag, name, color, skin, log, false);
    }

    /** soon: the NPC's feature is not in this version; the label says SOON (gray) instead of CLICK. */
    static Spawned spawn(World world, Location at, String tag, String name, NamedTextColor color, String skin, Logger log, boolean soon) {
        int stale = clearAround(world, at, tag);
        if (stale > 0) {
            log.info("NPC " + tag + ": removed " + stale + " stale body/label entities before respawn.");
        }
        Mannequin body = world.spawn(at, Mannequin.class, m -> {
            m.setProfile(profile(skin, log));
            m.setImmovable(true);
            m.setAI(false);
            m.setGravity(false);
            m.setSilent(true);
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.setCollidable(false);
            m.setCanPickupItems(false);
            m.setRotation(at.getYaw(), 0f);
            m.customName(Component.text(name, color));
            m.setCustomNameVisible(false);
            try {
                m.setDescription(null);
            } catch (RuntimeException ignored) {
                m.setDescription(Component.empty());
            }
            m.addScoreboardTag(TAG);
            m.addScoreboardTag(tag);
        });
        Component top = (soon ? Component.text("SOON", NamedTextColor.GRAY) : Component.text("CLICK", NamedTextColor.YELLOW)).decoration(TextDecoration.BOLD, true);
        ArmorStand label = line(world, at.clone().add(0, CLICK_Y, 0), top, tag);
        ArmorStand nameLine = line(world, at.clone().add(0, NAME_Y, 0), Component.text(name, color), tag);
        NAME_LINES.add(nameLine.getUniqueId());
        hideFromLegacy(nameLine);
        return new Spawned(body.getUniqueId(), label.getUniqueId(), nameLine.getUniqueId());
    }

    /*
     * Labels are one invisible marker armor stand per line, not a multi-line TextDisplay: browser (1.8) clients get
     * display entities as one emulated nametag that cannot hold a line break, and they get every Mannequin as a fake
     * player whose nametag is forced on with the custom name as a team prefix. Both showed up as a doubled name.
     * Old clients therefore do not get our name line (the Mannequin's own nametag already says the name there);
     * modern clients see the Mannequin without a nametag and get both lines from us.
     */
    static final String LINE_TAG = "sb-npc-line";
    static final NamespacedKey LABEL_KEY = new NamespacedKey("somniaskyblock", "npc_label");
    /** Height of the name line's stand above the NPC's feet (the nametag floats about 0.5 above a marker). */
    private static final double NAME_Y = 1.82;
    private static final double CLICK_Y = 2.12;
    /** 1.21.9: first protocol that has Mannequins natively. */
    private static final int MANNEQUIN_PROTOCOL = 773;
    private static final Set<UUID> NAME_LINES = ConcurrentHashMap.newKeySet();

    private static ArmorStand line(World world, Location at, Component text, String tag) {
        return world.spawn(at, ArmorStand.class, s -> {
            s.setMarker(true);
            s.setInvisible(true);
            s.setGravity(false);
            s.setSmall(true);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setPersistent(false);
            s.setCanPickupItems(false);
            s.customName(text);
            s.setCustomNameVisible(true);
            s.addScoreboardTag(LINE_TAG);
            s.addScoreboardTag("sb-holo");
            s.addScoreboardTag(tag);
            s.getPersistentDataContainer().set(LABEL_KEY, PersistentDataType.STRING, tag);
        });
    }

    /** Removes every body or label of this NPC near the spot, so a respawn can never stack a second copy. */
    private static int clearAround(World world, Location at, String tag) {
        int removed = 0;
        for (Entity e : world.getNearbyEntities(at, 2.5, 4.0, 2.5)) {
            java.util.Set<String> tags = e.getScoreboardTags();
            boolean ours = tags.contains(tag) && (tags.contains(TAG) || tags.contains("sb-holo") || tags.contains(LINE_TAG));
            String pdc = e.getPersistentDataContainer().get(LABEL_KEY, PersistentDataType.STRING);
            if (ours || tag.equals(pdc)) {
                NAME_LINES.remove(e.getUniqueId());
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Leftover NPC labels (any world, e.g. after a crash) are removed when their chunk loads; ours are never saved. */
    static int removeLeftovers(Iterable<? extends Entity> entities, java.util.Set<UUID> keep) {
        int removed = 0;
        for (Entity e : entities) {
            if ((e.getScoreboardTags().contains(LINE_TAG) || e.getPersistentDataContainer().has(LABEL_KEY, PersistentDataType.STRING))
                    && (keep == null || !keep.contains(e.getUniqueId()))) {
                NAME_LINES.remove(e.getUniqueId());
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    static boolean legacyClient(Player player) {
        int version = protocol(player);
        return version > 0 && version < MANNEQUIN_PROTOCOL;
    }

    /** The client's protocol via ViaVersion (reflection, no compile dependency); -1 when unknown or same as the server. */
    static int protocol(Player player) {
        try {
            Plugin via = Bukkit.getPluginManager().getPlugin("ViaVersion");
            if (via == null || !via.isEnabled()) {
                return -1;
            }
            Class<?> api = Class.forName("com.viaversion.viaversion.api.Via", true, via.getClass().getClassLoader());
            Object instance = api.getMethod("getAPI").invoke(null);
            Class<?> viaApi = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, via.getClass().getClassLoader());
            Object version = viaApi.getMethod("getPlayerVersion", UUID.class).invoke(instance, player.getUniqueId());
            return version instanceof Integer i ? i : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void hideFromLegacy(Entity nameLine) {
        Plugin plugin = owner();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (legacyClient(player)) {
                player.hideEntity(plugin, nameLine);
            }
        }
    }

    /** Called on join: an old client gets the name only from the Mannequin's own nametag. */
    static void hideNameLinesFor(Player player) {
        if (!legacyClient(player)) {
            return;
        }
        Plugin plugin = owner();
        NAME_LINES.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) {
                return true;
            }
            player.hideEntity(plugin, e);
            return false;
        });
    }

    /** Counts per NPC tag: bodies and label lines that are alive now (control file "holo"). */
    static String census() {
        java.util.Map<String, int[]> per = new java.util.TreeMap<>();
        int legacy = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (legacyClient(p)) {
                legacy++;
            }
        }
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                java.util.Set<String> tags = e.getScoreboardTags();
                boolean body = tags.contains(TAG) && e instanceof Mannequin;
                boolean line = tags.contains(LINE_TAG) || (e instanceof TextDisplay && tags.contains("sb-holo") && tags.stream().anyMatch(t -> t.startsWith("sb-npc:") || t.equals("sb-guide")));
                if (!body && !line) {
                    continue;
                }
                String key = tags.stream().filter(t -> t.startsWith("sb-npc:") || t.equals("sb-guide")).findFirst().orElse("?")
                        + "@" + w.getName() + ":" + e.getLocation().getBlockX() + "," + e.getLocation().getBlockZ();
                int[] c = per.computeIfAbsent(key, k -> new int[2]);
                c[body ? 0 : 1]++;
            }
        }
        int bad = 0;
        StringBuilder b = new StringBuilder();
        for (var en : per.entrySet()) {
            boolean ok = en.getValue()[0] == 1 && en.getValue()[1] == 2;
            if (!ok) {
                bad++;
            }
            b.append(en.getKey()).append(" body=").append(en.getValue()[0]).append(" lines=").append(en.getValue()[1]).append(ok ? "" : " !!").append("; ");
        }
        return "NPCs " + per.size() + ", not 1 body + 2 lines: " + bad + ", legacy clients online: " + legacy + ". " + b;
    }

    private static Plugin owner() {
        return JavaPlugin.getPlugin(SomniaSkyblockPlugin.class);
    }

    static ResolvableProfile profile(String skin, Logger log) {
        String s = skin == null || skin.isBlank() ? "default:steve" : skin.trim();
        try {
            if (s.startsWith("player:")) {
                return ResolvableProfile.resolvableProfile().name(s.substring(7)).build();
            }
            if (s.startsWith("texture:")) {
                String[] parts = s.substring(8).split("\\|", 2);
                return ResolvableProfile.resolvableProfile().name("npc").uuid(UUID.nameUUIDFromBytes(parts[0].getBytes()))
                        .addProperty(new ProfileProperty("textures", parts[0], parts.length > 1 ? parts[1] : null)).build();
            }
            String[] parts = s.replace("default:", "").split(":");
            boolean slim = parts.length > 1 && parts[1].equalsIgnoreCase("slim");
            String file = parts[0].toLowerCase(java.util.Locale.ROOT);
            return ResolvableProfile.resolvableProfile().skinPatch(p -> p
                    .body(Key.key("minecraft", "entity/player/" + (slim ? "slim/" : "wide/") + file))
                    .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build();
        } catch (RuntimeException e) {
            log.warning("NPC skin '" + s + "' is not usable (" + e.getMessage() + "); using Steve.");
            return Mannequin.defaultProfile();
        }
    }
}
