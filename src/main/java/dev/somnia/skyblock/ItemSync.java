package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla item use that changes the bag without a block break/place or an inventory click: bone meal, composter
 * input, buckets, eating, thrown items, dyes/shears/name tags on mobs, armor equipped by right-click, picked-up
 * items. The server already applied the change to the live inventory (server-authoritative); this signs that live
 * bag into SkyWord right after (WordChain.commitLocal, the same trusted write as chest and merchant moves), so an
 * unrelated later slot check can no longer paint the old signed bag back (the bone meal / compost duplication).
 * A write is only sent when the bag really differs from the signed one.
 *
 * The composter's bone meal goes straight into the bag (stash when full) instead of popping onto the ground.
 */
final class ItemSync implements Listener {
    private final SomniaSkyblockPlugin plugin;
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    ItemSync(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean chainBag(Player player) {
        return plugin.wordMode() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.hold().offchain(player);
    }

    /** Next tick (after vanilla applied the use), sign the live bag once. */
    void sync(Player player) {
        if (!chainBag(player) || !queued.add(player.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            queued.remove(player.getUniqueId());
            if (player.isOnline() && chainBag(player)) {
                plugin.words().commitLocal(player);
            }
        });
    }

    /**
     * Item frames and armor stands take items out of the bag into the world (and give them back). If that bag write
     * cannot be paid, the bag goes back to its last signed state while the frame or stand keeps the item (a dupe),
     * so with too little gas these uses wait, like block breaks and places do. Picking items up waits too, so a
     * pickup is not taken away again by a rolled-back write. The first interaction of a pending write also snapshots
     * the frame or stand so a later non-gas failure can put it back.
     */
    private boolean gasBlocked(Player player) {
        return chainBag(player) && plugin.wallets().shortOnGas(plugin.profiles().get(player.getUniqueId()));
    }

    private void tellGas(Player player) {
        String text = plugin.wallets().sponsorCapReached(plugin.profiles().get(player.getUniqueId()))
                ? WalletService.sponsorCapMessage()
                : "Not enough STT for gas, use /deposit.";
        player.sendActionBar(Component.text(text, NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrameOrStand(PlayerInteractEntityEvent event) {
        org.bukkit.entity.Entity target = event.getRightClicked();
        // Player-placed frames, and stands without plugin tags (minions and NPC labels carry tags).
        boolean world = target instanceof org.bukkit.entity.ItemFrame
                || (target instanceof org.bukkit.entity.ArmorStand && target.getScoreboardTags().isEmpty());
        if (!world) {
            return;
        }
        Player player = event.getPlayer();
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItem(event.getHand());
        if (hand != null && !hand.getType().isAir() && gasBlocked(player)) {
            event.setCancelled(true);
            tellGas(player);
            return;
        }
        if (plugin.words() == null || !chainBag(player)) {
            return;
        }
        if (target instanceof org.bukkit.entity.ItemFrame frame) {
            plugin.words().rememberFrame(player, frame);
        } else if (target instanceof org.bukkit.entity.ArmorStand stand) {
            plugin.words().rememberStand(player, stand);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStandGas(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        if (!event.getRightClicked().getScoreboardTags().isEmpty()) {
            return;
        }
        if (!event.getPlayerItem().getType().isAir() && gasBlocked(player)) {
            event.setCancelled(true);
            tellGas(player);
            return;
        }
        if (plugin.words() != null && chainBag(player)) {
            plugin.words().rememberStand(player, event.getRightClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && gasBlocked(event.getPlayer())) {
            event.setCancelled(true);
            tellGas(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlaced(HangingPlaceEvent event) {
        if (event.getPlayer() != null && plugin.words() != null && chainBag(event.getPlayer()) && event.getEntity() != null) {
            plugin.words().rememberSpawned(event.getPlayer(), event.getEntity());
            sync(event.getPlayer());
        }
    }

    /**
     * Frames, stands, boats, minecarts and crystals leave the bag and exist in the world. Same gas rule as
     * {@link #onFrameOrStand}: with too little STT the write would roll the bag back while the entity stayed.
     */
    private static boolean worldPlaced(org.bukkit.entity.Entity entity) {
        if (entity instanceof org.bukkit.entity.ItemFrame || entity instanceof org.bukkit.entity.EnderCrystal
                || entity instanceof org.bukkit.entity.Vehicle) {
            return true;
        }
        return entity instanceof org.bukkit.entity.ArmorStand && entity.getScoreboardTags().isEmpty();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && worldPlaced(event.getEntity()) && gasBlocked(event.getPlayer())) {
            event.setCancelled(true);
            tellGas(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlaced(EntityPlaceEvent event) {
        if (event.getPlayer() != null && plugin.words() != null && chainBag(event.getPlayer())
                && worldPlaced(event.getEntity())) {
            plugin.words().rememberSpawned(event.getPlayer(), event.getEntity());
            sync(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickupGas(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && gasBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFishGas(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                && event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) {
            return;
        }
        Player player = event.getPlayer();
        if (!gasBlocked(player)) {
            return;
        }
        event.setCancelled(true);
        // CAUGHT_FISH spawns a new item. CAUGHT_ENTITY is a mob, cart, frame or ground item that was already
        // in the world: cancelling stops the reel, and removing it would delete the Gate minecart or a mob.
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof org.bukkit.entity.Item) {
            event.getCaught().remove();
        }
        tellGas(player);
    }

    /**
     * Uses that move an item between the bag and the world, same class as a frame. With too little gas the write
     * rolls the bag back and the block keeps the book, disc, pot item or grown crop. Wait instead, and snapshot
     * the block so a later non-gas failure puts it back.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStore(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        org.bukkit.inventory.ItemStack hand = event.getItem();
        if (hand != null && hand.getType() == Material.BONE_MEAL) {
            if (denyGas(event, event.getPlayer())) {
                return;
            }
            // BlockFertilizeEvent snapshots the blocks that actually change.
            return;
        }
        if (!storesHeldItem(block, hand)) {
            return;
        }
        if (denyGas(event, event.getPlayer())) {
            return;
        }
        snapBlock(event.getPlayer(), block);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternGas(PlayerTakeLecternBookEvent event) {
        if (denyGas(event, event.getPlayer())) {
            return;
        }
        snapBlock(event.getPlayer(), event.getLectern().getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLecternTaken(PlayerTakeLecternBookEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilizeGas(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (denyGas(event, player)) {
            return;
        }
        snapBlock(player, event.getBlock());
        for (org.bukkit.block.BlockState state : event.getBlocks()) {
            if (state != null) {
                snapBlock(player, state.getBlock());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHarvestGas(PlayerHarvestBlockEvent event) {
        if (denyGas(event, event.getPlayer())) {
            return;
        }
        snapBlock(event.getPlayer(), event.getHarvestedBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmptyGas(PlayerBucketEmptyEvent event) {
        if (denyGas(event, event.getPlayer())) {
            return;
        }
        snapBlock(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFillGas(PlayerBucketFillEvent event) {
        if (denyGas(event, event.getPlayer())) {
            return;
        }
        snapBlock(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntityGas(PlayerBucketEntityEvent event) {
        denyGas(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        sync(event.getPlayer());
    }

    /** Wool, mushrooms and honeycomb would otherwise sit on the ground for anyone who can pay gas. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShearGas(PlayerShearEntityEvent event) {
        denyGas(event, event.getPlayer());
    }

    /** A lead that returns to the bag while the knot stays is a second lead when the knot is broken. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeashGas(PlayerLeashEntityEvent event) {
        denyGas(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeashed(PlayerLeashEntityEvent event) {
        if (plugin.words() != null && chainBag(event.getPlayer())) {
            plugin.words().rememberLeash(event.getPlayer(), event.getEntity());
        }
        sync(event.getPlayer());
    }

    private boolean denyGas(Cancellable event, Player player) {
        if (!gasBlocked(player)) {
            return false;
        }
        event.setCancelled(true);
        tellGas(player);
        return true;
    }

    private void snapBlock(Player player, Block block) {
        if (block != null && plugin.words() != null && chainBag(player)) {
            plugin.words().rememberBlock(player, block);
        }
    }

    /** Right-click that puts an item into a block, or takes one out. A full composter is handled in {@link #onComposter}. */
    private static boolean storesHeldItem(Block block, org.bukkit.inventory.ItemStack hand) {
        Material type = block.getType();
        Material held = hand == null ? Material.AIR : hand.getType();
        boolean empty = held.isAir();
        if (type == Material.COMPOSTER) {
            if (!(block.getBlockData() instanceof Levelled level) || level.getLevel() >= level.getMaximumLevel()) {
                return false;
            }
            return !empty;
        }
        if (type == Material.JUKEBOX) {
            boolean disc = block.getState() instanceof org.bukkit.block.Jukebox juke && juke.hasRecord();
            return disc || held.name().contains("MUSIC_DISC");
        }
        if (type == Material.LECTERN) {
            return held == Material.WRITABLE_BOOK || held == Material.WRITTEN_BOOK;
        }
        if (type == Material.CHISELED_BOOKSHELF) {
            return empty || held == Material.BOOK || held == Material.ENCHANTED_BOOK
                    || held == Material.WRITABLE_BOOK || held == Material.WRITTEN_BOOK || held == Material.KNOWLEDGE_BOOK;
        }
        if (type == Material.DECORATED_POT) {
            return !empty;
        }
        if ((type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE) && held.isEdible()) {
            return true;
        }
        if (type == Material.FLOWER_POT) {
            return !empty;
        }
        if ((type == Material.BEEHIVE || type == Material.BEE_NEST)
                && (held == Material.GLASS_BOTTLE || held == Material.SHEARS)
                && block.getBlockData() instanceof org.bukkit.block.data.type.Beehive hive) {
            return hive.getHoneyLevel() >= hive.getMaximumHoneyLevel();
        }
        if (type == Material.RESPAWN_ANCHOR && held == Material.GLOWSTONE
                && block.getBlockData() instanceof org.bukkit.block.data.type.RespawnAnchor anchor) {
            return anchor.getCharges() < anchor.getMaximumCharges();
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onComposter(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.COMPOSTER || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (!chainBag(player) || !(block.getBlockData() instanceof Levelled level) || level.getLevel() < level.getMaximumLevel()) {
            return;
        }
        // Full composter: take the bone meal into the bag instead of dropping it on the ground.
        // Do not empty it when the purse cannot pay: the harvest would be stashed and the level already spent.
        event.setCancelled(true);
        if (gasBlocked(player)) {
            tellGas(player);
            return;
        }
        if (!plugin.words().ready(player)) {
            player.sendMessage(Component.text("The chain inventory is still loading.", NamedTextColor.RED));
            return;
        }
        level.setLevel(0);
        block.setBlockData(level);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_COMPOSTER_EMPTY, 1f, 1f);
        plugin.market().giveOrStash(player.getUniqueId(), "minecraft:bone_meal", 1, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY && event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block clicked = event.getClickedBlock();
        boolean composter = clicked != null && clicked.getType() == Material.COMPOSTER;
        org.bukkit.inventory.ItemStack hand = event.getItem();
        if (!composter && hand != null && hand.getType().isBlock()) {
            return; // block placing has its own signed path (WordChain.onPlace)
        }
        if (!composter && (hand == null || hand.getType().isAir()) && a == Action.RIGHT_CLICK_AIR) {
            return;
        }
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (event.getPlayer() != null) {
            sync(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            sync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            sync(player);
        }
    }
}
