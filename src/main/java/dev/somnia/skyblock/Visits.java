package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /visit &lt;player&gt;: stand on someone else's island as a guest. Guests can walk (and fly with a Booster)
 * but cannot change the island. The island world is always loaded, so offline hosts are allowed.
 * Also keeps a co-op member off their reserved old solo slot (blocks stay until they leave the co-op;
 * the solo deed was already burned onchain by SkyIsland4.accept).
 */
final class Visits implements Listener {
    private final SomniaSkyblockPlugin plugin;
    /** guest -> host profile UUID */
    private final Map<UUID, UUID> guests = new ConcurrentHashMap<>();

    Visits(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isGuest(Player player) {
        return player != null && guests.containsKey(player.getUniqueId());
    }

    Profile hostOf(Player guest) {
        UUID host = guests.get(guest.getUniqueId());
        return host == null ? null : plugin.profiles().get(host);
    }

    void end(Player player) {
        guests.remove(player.getUniqueId());
    }

    /**
     * The player may stand here: their current (co-op or solo) island, or the island they are visiting.
     * Reserved old co-op slots are not included.
     */
    boolean allowedHere(Player player, Profile profile, Location at) {
        if (at == null || plugin.islands() == null || plugin.islands().world() == null) {
            return false;
        }
        if (!at.getWorld().equals(plugin.islands().world())) {
            return false;
        }
        if (isGuest(player)) {
            Profile host = hostOf(player);
            return host != null && host.islandReady && plugin.islands().contains(host, at);
        }
        return profile != null && profile.islandReady && plugin.islands().contains(profile, at);
    }

    /** Break, place, containers, drops, damage, minions: not as a guest, and only on your current island. */
    boolean mayEdit(Player player, Location at) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        if (plugin.islands() == null || plugin.islands().world() == null || at.getWorld() == null) {
            return true;
        }
        if (!at.getWorld().equals(plugin.islands().world())) {
            return true;
        }
        if (isGuest(player)) {
            return false;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        return profile.islandReady && plugin.islands().contains(profile, at);
    }

    void deny(Player player) {
        if (isGuest(player)) {
            player.sendMessage(Component.text("You are a guest on this island. You cannot change anything. /is or /hub to leave.", NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text(awayMessage(player, player.getLocation()), NamedTextColor.RED));
        }
    }

    private String awayMessage(Player player, Location at) {
        if (plugin.coops() != null) {
            Coops.Coop coop = plugin.coops().of(player.getUniqueId());
            if (coop != null) {
                int[] home = coop.homes.get(player.getUniqueId());
                if (home != null && at != null && at.getWorld() != null && at.getWorld().equals(plugin.islands().world())) {
                    int dx = Math.abs(at.getBlockX() - home[0]);
                    int dz = Math.abs(at.getBlockZ() - home[1]);
                    if (dx <= IslandManager.RADIUS && dz <= IslandManager.RADIUS) {
                        return "Your old island is not available while you are in this co-op. /is goes to the co-op island.";
                    }
                }
            }
        }
        return "You can only build on your current island.";
    }

    boolean go(Player player, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            player.sendMessage(Component.text("Usage: /visit <player>", NamedTextColor.YELLOW));
            return true;
        }
        Resolved target = resolve(rawName);
        if (target == null) {
            player.sendMessage(Component.text("No island found for that player.", NamedTextColor.RED));
            return true;
        }
        if (target.id.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Use /is to go to your own island.", NamedTextColor.YELLOW));
            return true;
        }
        if (!target.profile.islandReady || plugin.islands() == null || plugin.islands().world() == null) {
            player.sendMessage(Component.text("That player has no island yet.", NamedTextColor.RED));
            return true;
        }
        // One shared island world, always loaded: visiting an offline host only teleports to their existing slot.
        guests.put(player.getUniqueId(), target.id);
        player.teleport(plugin.islands().home(target.profile));
        player.sendMessage(Component.text("Visiting " + target.name + "'s island. You are a guest: you can walk"
                + (plugin.cookies() != null && plugin.cookies().active(player) ? " and fly with your Booster" : "")
                + ", but you cannot change anything. /is or /hub leaves.", NamedTextColor.GREEN));
        return true;
    }

    /** Send a guest who left the allowed island back to the visit spawn, or a wanderer home. */
    void bounce(Player player, Location to) {
        if (plugin.islands() == null || plugin.islands().world() == null || to == null || to.getWorld() == null) {
            return;
        }
        if (!to.getWorld().equals(plugin.islands().world())) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (!profile.islandReady && !isGuest(player)) {
            return;
        }
        if (allowedHere(player, profile, to)) {
            return;
        }
        if (isGuest(player)) {
            Profile host = hostOf(player);
            if (host != null && host.islandReady) {
                player.teleport(plugin.islands().home(host));
                return;
            }
            end(player);
        }
        player.sendMessage(Component.text(awayMessage(player, to), NamedTextColor.RED));
        plugin.islands().teleport(player, profile);
    }

    java.util.List<Resolved> listTargets(Player viewer) {
        java.util.LinkedHashMap<UUID, Resolved> out = new java.util.LinkedHashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            if (plugin.auth() != null && !plugin.auth().authed(online)) {
                continue;
            }
            Profile p = plugin.profiles().get(online.getUniqueId());
            if (p.islandReady) {
                out.put(online.getUniqueId(), new Resolved(online.getUniqueId(), online.getName(), p, true));
            }
        }
        for (OfflinePlayer off : Bukkit.getOfflinePlayers()) {
            if (off.getUniqueId().equals(viewer.getUniqueId()) || out.containsKey(off.getUniqueId())) {
                continue;
            }
            if (off.getName() == null || !plugin.profiles().exists(off.getUniqueId())) {
                continue;
            }
            Profile p = plugin.profiles().get(off.getUniqueId());
            if (p.islandReady) {
                out.put(off.getUniqueId(), new Resolved(off.getUniqueId(), off.getName(), p, false));
            }
        }
        return new java.util.ArrayList<>(out.values());
    }

    java.util.List<String> complete(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (plugin.auth() != null && !plugin.auth().authed(online)) {
                continue;
            }
            if (plugin.profiles().get(online.getUniqueId()).islandReady) {
                names.add(online.getName());
            }
        }
        for (OfflinePlayer off : Bukkit.getOfflinePlayers()) {
            if (off.getName() != null && plugin.profiles().exists(off.getUniqueId())
                    && plugin.profiles().get(off.getUniqueId()).islandReady) {
                names.add(off.getName());
            }
        }
        return names.stream()
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(p))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(50)
                .toList();
    }

    private Resolved resolve(String raw) {
        String want = raw.trim();
        Player online = Bukkit.getPlayerExact(want);
        if (online == null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(want)) {
                    online = p;
                    break;
                }
            }
        }
        if (online != null) {
            return new Resolved(online.getUniqueId(), online.getName(), plugin.profiles().get(online.getUniqueId()), true);
        }
        OfflinePlayer found = null;
        for (OfflinePlayer off : Bukkit.getOfflinePlayers()) {
            if (off.getName() != null && off.getName().equalsIgnoreCase(want)) {
                found = off;
                break;
            }
        }
        if (found == null || !plugin.profiles().exists(found.getUniqueId())) {
            return null;
        }
        return new Resolved(found.getUniqueId(), found.getName(), plugin.profiles().get(found.getUniqueId()), false);
    }

    record Resolved(UUID id, String name, Profile profile, boolean online) {
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!mayEdit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!mayEdit(event.getPlayer(), event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isGuest(event.getPlayer()) || !mayEdit(event.getPlayer(), event.getPlayer().getLocation())) {
            event.setCancelled(true);
            if (isGuest(event.getPlayer())) {
                deny(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && (isGuest(player) || !mayEdit(player, event.getItem().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.PHYSICAL) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        if (mayEdit(event.getPlayer(), event.getClickedBlock().getLocation())) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.PHYSICAL) {
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!mayEdit(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        if (!mayEdit(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!mayEdit(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!mayEdit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!mayEdit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player damager = damager(event);
        if (damager == null) {
            return;
        }
        if (!mayEdit(damager, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(damager);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!isGuest(player) && mayEdit(player, player.getLocation())) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof org.bukkit.block.BlockState || holder instanceof org.bukkit.entity.minecart.StorageMinecart
                || holder instanceof org.bukkit.entity.minecart.HopperMinecart) {
            if (isGuest(player) || !mayEdit(player, player.getLocation())) {
                event.setCancelled(true);
                deny(player);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer());
    }

    private static Player damager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
