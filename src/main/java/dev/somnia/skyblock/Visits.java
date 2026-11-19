package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
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

    /**
     * A right-click in the air never hits the block guard. An ender pearl or chorus fruit then leaves the
     * island, and a spawn egg leaves a mob on the host's Hearth. The item stays in the hand.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAirToss(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item == null || !airToss(item.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (mayEdit(player, player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        deny(player);
    }

    private static boolean airToss(Material type) {
        if (type == null) {
            return false;
        }
        if (type == Material.ENDER_PEARL || type == Material.CHORUS_FRUIT) {
            return true;
        }
        return type.name().endsWith("_SPAWN_EGG");
    }

    /**
     * A right-click in the air is not a block edit, so a fire charge still leaves the hand and lights the host's
     * wood. Flint and steel on a block is already denied above. Spread with no player is left alone.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player != null && !mayEdit(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            return;
        }
        org.bukkit.entity.Entity shot = event.getEntity();
        boolean lights = shot instanceof org.bukkit.entity.Fireball || shot instanceof org.bukkit.entity.WindCharge;
        if (!lights || mayEdit(player, player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        deny(player);
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
        org.bukkit.entity.Entity raw = event.getDamager();
        Player damager = actor(raw);
        if (damager == null) {
            return;
        }
        if (!mayEdit(damager, event.getEntity().getLocation())) {
            event.setCancelled(true);
            // A tamed wolf ticks this every hit. A punch or an arrow still gets the guest line.
            if (raw instanceof Player || raw instanceof org.bukkit.entity.Projectile) {
                deny(damager);
            }
        }
    }

    /** Bone meal on a host's crops. Cancelling the click is not always enough for the fertilize event. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFertilize(org.bukkit.event.block.BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player != null && !mayEdit(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLectern(org.bukkit.event.player.PlayerTakeLecternBookEvent event) {
        if (!mayEdit(event.getPlayer(), event.getLectern().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** A thrown egg hatches a chicken the guest does not own. The egg is still used. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEgg(org.bukkit.event.player.PlayerEggThrowEvent event) {
        if (!mayEdit(event.getPlayer(), event.getEgg().getLocation())) {
            event.setHatching(false);
            deny(event.getPlayer());
        }
    }

    /**
     * A double chest's holder is {@code DoubleChest}, not a block state. A llama, donkey or horse is not a
     * vehicle. The click cancel does not always stop the open, so check the inventory itself.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Menu || holder instanceof Player) {
            return;
        }
        boolean foreign = holder instanceof org.bukkit.block.BlockState
                || holder instanceof org.bukkit.block.DoubleChest
                || holder instanceof org.bukkit.entity.Entity;
        if (!foreign) {
            return;
        }
        Location at = event.getInventory().getLocation();
        if (at == null && holder instanceof org.bukkit.entity.Entity entity) {
            at = entity.getLocation();
        }
        if (at == null) {
            at = player.getLocation();
        }
        if (!mayEdit(player, at)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(org.bukkit.event.hanging.HangingBreakByEntityEvent event) {
        Player player = actor(event.getRemover());
        if (player != null && !mayEdit(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFrameChange(io.papermc.paper.event.player.PlayerItemFrameChangeEvent event) {
        if (!mayEdit(event.getPlayer(), event.getItemFrame().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDamage(org.bukkit.event.vehicle.VehicleDamageEvent event) {
        Player player = actor(event.getAttacker());
        if (player != null && !mayEdit(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDestroy(org.bukkit.event.vehicle.VehicleDestroyEvent event) {
        Player player = actor(event.getAttacker());
        if (player != null && !mayEdit(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && !mayEdit(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMount(org.bukkit.event.entity.EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && !mayEdit(player, event.getMount().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPlace(org.bukkit.event.entity.EntityPlaceEvent event) {
        if (event.getPlayer() != null && !mayEdit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(org.bukkit.event.hanging.HangingPlaceEvent event) {
        if (event.getPlayer() != null && event.getEntity() != null
                && !mayEdit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHarvest(org.bukkit.event.player.PlayerHarvestBlockEvent event) {
        if (!mayEdit(event.getPlayer(), event.getHarvestedBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** Reeling a frame, cart, animal or ground item. Catching a fish is not an edit. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent event) {
        if (event.getState() != org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_ENTITY || event.getCaught() == null) {
            return;
        }
        if (!mayEdit(event.getPlayer(), event.getCaught().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShear(org.bukkit.event.player.PlayerShearEntityEvent event) {
        if (!mayEdit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** Shears on a pumpkin or hive. Cancelling the click does not always stop the carve or the honeycomb. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShearBlock(io.papermc.paper.event.block.PlayerShearBlockEvent event) {
        if (!mayEdit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEntity(org.bukkit.event.player.PlayerBucketEntityEvent event) {
        if (!mayEdit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        if (!mayEdit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer());
    }

    private static Player actor(org.bukkit.entity.Entity entity) {
        org.bukkit.entity.Entity credit = Stats.credit(entity);
        return credit instanceof Player player ? player : null;
    }
}
