package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

final class SkyListener implements Listener {
    private final SomniaSkyblockPlugin plugin;
    private final Set<Long> placed = new HashSet<>();

    SkyListener(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(SbLoginEvent event) {
        Player player = event.getPlayer();
        Profile profile = plugin.profiles().get(player.getUniqueId());
        boolean first = !profile.islandReady;
        plugin.islands().ensureIsland(profile);
        plugin.wallets().ensure(profile);
        plugin.chain().ensureIsland(profile);
        fillHunger(player);
        solidify(player);
        if (first) {
            player.setHealth(20);
        }
        org.bukkit.Location back = event.back();
        if (back != null && plugin.hub().isHub(back.getWorld()) && !plugin.hub().pasting() && !safeSpot(back)) {
            // The hub may have been rebuilt since the player left: a saved spot inside blocks goes to the hub spawn.
            back = plugin.hub().spawn();
        }
        if (first || back == null || back.getWorld() == null) {
            plugin.islands().teleport(player, profile);
        } else if (!back.getWorld().equals(player.getWorld()) || back.distanceSquared(player.getLocation()) > 1) {
            player.teleport(back);
        }
        player.setRespawnLocation(home(profile), true);
        boolean showGuide = !profile.seenGuide;
        if (showGuide) {
            profile.seenGuide = true;
        }
        if (!plugin.wordMode() && !profile.bagCleared && player.getGameMode() != GameMode.CREATIVE) {
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setItemOnCursor(null);
            profile.bagCleared = true;
        }
        plugin.profiles().save(profile);
        plugin.sidebar().show(player, profile);
        if (first) {
            player.sendMessage(Component.text("Your private island is ready.", NamedTextColor.GOLD));
            player.sendMessage(Component.text("Your purse is " + profile.wallet + ". You pay gas from it. /deposit /withdraw", NamedTextColor.AQUA));
        }
        if (showGuide) {
            guide(player, first);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.chat().welcome(player, profile);
            }
        }, 30L);
    }

    private void guide(Player player, boolean first) {
        if (!first) {
            player.sendMessage(Component.text("The island was rebuilt.", NamedTextColor.GOLD));
        }
        java.util.List<String> tips = plugin.islands().tips(plugin.profiles().get(player.getUniqueId()));
        if (tips != null) {
            for (String tip : tips) {
                player.sendMessage(Component.text(tip, NamedTextColor.GRAY));
            }
            return;
        }
        player.sendMessage(Component.text("The oak is on the hill. The wheat is in the fenced garden.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("The cobble machine is on the east cliff. Step down and mine the framed block. It comes back.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Cross the dirt bridge. The cave chest has the wooden pick. The minion says Soon.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("The portal goes to the hub. /hub and /is.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Coal is first. A stone pick reaches the iron. An iron pick reaches the gold.", NamedTextColor.GRAY));
    }

    @EventHandler(ignoreCancelled = true)
    public void onJerry(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager) || villager.getWorld() == null) {
            return;
        }
        if (!villager.getWorld().equals(plugin.islands().world())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("The portal past the bridge goes to the Hub.", NamedTextColor.GRAY));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVillagerHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Villager villager
                && villager.getWorld() != null
                && villager.getWorld().equals(plugin.islands().world())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            fillHunger(player);
        }
    }

    @EventHandler
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    private static void fillHunger(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        event.setCancelled(true);
        solidify(event.getPlayer());
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (HoldItems.makeUnbreakable(result)) {
            event.getInventory().setResult(result);
        }
    }

    @EventHandler
    public void onPrepareSmith(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (HoldItems.makeUnbreakable(result)) {
            event.setResult(result);
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            solidify(player);
        }
        Inventory inventory = event.getInventory();
        ItemStack[] contents = inventory.getContents();
        if (HoldItems.makeUnbreakable(contents)) {
            inventory.setContents(contents);
        }
    }

    private static void solidify(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack cursor = player.getItemOnCursor();
        ItemStack[] ender = player.getEnderChest().getContents();
        if (HoldItems.makeUnbreakable(storage)) {
            player.getInventory().setStorageContents(storage);
        }
        if (HoldItems.makeUnbreakable(armor)) {
            player.getInventory().setArmorContents(armor);
        }
        if (HoldItems.makeUnbreakable(offhand)) {
            player.getInventory().setItemInOffHand(offhand);
        }
        if (HoldItems.makeUnbreakable(cursor)) {
            player.setItemOnCursor(cursor);
        }
        if (HoldItems.makeUnbreakable(ender)) {
            player.getEnderChest().setContents(ender);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.profiles().save(plugin.profiles().get(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        fillHunger(player);
        if (plugin.hub().isHub(player.getWorld())) {
            event.setRespawnLocation(plugin.hub().spawn());
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.islandReady) {
            event.setRespawnLocation(home(profile));
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getY() >= 40 || event.getFrom().getBlockY() == to.getBlockY()) {
            return;
        }
        if (to.getWorld() == null || !to.getWorld().equals(plugin.islands().world())) {
            return;
        }
        Profile profile = plugin.profiles().get(event.getPlayer().getUniqueId());
        if (!profile.islandReady) {
            return;
        }
        plugin.islands().teleport(event.getPlayer(), profile);
        Chat.voidDeath(event.getPlayer());
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.wordMode() || event.isCancelled() || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type == Material.COBBLESTONE || type == Material.OAK_LOG) {
            placed.add(key(event.getBlock()));
        }
    }

    /** The template's island portal (portal_frame box) cannot be broken, and its portal blocks ignore physics updates. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW, ignoreCancelled = true)
    public void onPortalFrameBreak(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE && plugin.islands().inPortalFrame(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text("The island portal cannot be broken.", net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalFramePhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        if (event.getBlock().getType() == Material.NETHER_PORTAL && plugin.islands().inPortalFrame(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalFrameExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.islands().inPortalFrame(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalFrameBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.islands().inPortalFrame(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalFramePiston(org.bukkit.event.block.BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> plugin.islands().inPortalFrame(b))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (plugin.wordMode() || event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (!profile.islandReady || !plugin.islands().contains(profile, block.getLocation())) {
            return;
        }
        long packed = key(block);
        if (placed.remove(packed)) {
            return;
        }

        // Same XP and collection rules as the hub (SkySkills batch); no separate island counters any more.
        plugin.hub().islandCleared(player, block.getBlockData().getAsString());
        if (block.getType() == Material.COBBLESTONE) {
            refillGenerator(block);
        }
    }

    @EventHandler
    public void onMenu(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTaken(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> solidify(player));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (plugin.islands().isGenerator(event.getBlock())) {
            event.getNewState().setType(Material.COBBLESTONE);
        }
    }

    /** Water or lava flowing into the generator cell becomes cobblestone (the template's water sits next to it). */
    @EventHandler(ignoreCancelled = true)
    public void onGeneratorFlow(org.bukkit.event.block.BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (plugin.islands().isGenerator(to) && (to.getType().isAir() || to.getType() == Material.WATER || to.getType() == Material.LAVA)) {
            event.setCancelled(true);
            to.setType(Material.COBBLESTONE);
        }
    }

    private void refillGenerator(Block block) {
        Location location = block.getLocation();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Block now = location.getBlock();
            if (!plugin.islands().isGenerator(now)) {
                return;
            }
            Material type = now.getType();
            if (type.isAir() || type == Material.WATER || type == Material.LAVA || type == Material.OBSIDIAN || type == Material.STONE || type == Material.COBBLESTONE) {
                now.setType(Material.COBBLESTONE);
            }
        }, 8L);
    }

    private Location home(Profile profile) {
        return plugin.islands().home(profile);
    }

    private long key(Block block) {
        int x = block.getX();
        int z = block.getZ();
        int y = block.getY();
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    /** Feet and head free, something solid within 3 blocks below. */
    private static boolean safeSpot(org.bukkit.Location at) {
        org.bukkit.block.Block feet = at.getBlock();
        if (!feet.isPassable() || !feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
            return false;
        }
        for (int dy = 1; dy <= 3; dy++) {
            if (feet.getRelative(0, -dy, 0).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }
}
