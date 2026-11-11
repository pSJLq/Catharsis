package dev.somnia.skyblock;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.profile.PlayerTextures;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Player-like NPCs: Paper's native Mannequin entity (a player model with a skin, no AI, no extra plugin),
 * plus a hologram with a bold yellow CLICK above the coloured name.
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

    record Spawned(UUID body, UUID label) {
    }

    static Spawned spawn(World world, Location at, String tag, String name, NamedTextColor color, String skin, Logger log) {
        return spawn(world, at, tag, name, color, skin, log, false);
    }

    /** soon: the NPC's feature is not in this version; the label says SOON (gray) instead of CLICK. */
    static Spawned spawn(World world, Location at, String tag, String name, NamedTextColor color, String skin, Logger log, boolean soon) {
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
        Component text = (soon ? Component.text("SOON", NamedTextColor.GRAY) : Component.text("CLICK", NamedTextColor.YELLOW)).decoration(TextDecoration.BOLD, true)
                .append(Component.newline())
                .append(Component.text(name, color).decoration(TextDecoration.BOLD, false));
        TextDisplay label = world.spawn(at.clone().add(0, 2.05, 0), TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.addScoreboardTag("sb-holo");
            d.addScoreboardTag(tag);
        });
        return new Spawned(body.getUniqueId(), label.getUniqueId());
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
