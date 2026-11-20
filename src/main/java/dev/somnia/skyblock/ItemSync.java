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
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.FishHook;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
    /** Player who just used a spawn egg, mob bucket, thrown egg or eye, so the spawned entity can be tied to the bag write. */
    private final Map<UUID, Integer> spawnedFrom = new HashMap<>();

    ItemSync(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean chainBag(Player player) {
        return plugin.wordMode() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.hold().offchain(player);
    }

    /** Next tick (after vanilla applied the use), sign the live bag once. */
    void sync(Player player) {
        if (!chainBag(player)) {
            return;
        }
        // Arm before the queue check. A leash, frame or lectern snap must lock breaks in this tick.
        // The following commit still waits until next tick, after vanilla has applied the use.
        if (plugin.words() != null) {
            plugin.words().armSnaps(player);
        }
        if (!queued.add(player.getUniqueId())) {
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
     * rolls the bag back and the block keeps the book, disc, pot item, shelf stack or grown crop. Wait instead,
     * and snapshot the block so a later non-gas failure puts it back. A powered shelf swaps the menu slot, so
     * that click is refused and the shelf stays one item at a time.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStore(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        // A powered shelf swaps the rightmost hotbar slots, and one of those is the menu slot,
        // which is not bag data. An unpowered click while that slot is selected does the same.
        if (shelf(block.getType())) {
            Player player = event.getPlayer();
            if (poweredShelf(block) || player.getInventory().getHeldItemSlot() == SbMenu.STAR_SLOT) {
                event.setCancelled(true);
                if (event.getHand() == EquipmentSlot.HAND) {
                    player.sendActionBar(Component.text(poweredShelf(block)
                            ? "A powered shelf swaps the menu slot. Turn the redstone off and move one item at a time."
                            : "That hotbar slot is the menu. Choose another slot.", NamedTextColor.YELLOW));
                }
                return;
            }
        }
        org.bukkit.inventory.ItemStack hand = event.getItem();
        if (hand != null && hand.getType() == Material.BONE_MEAL) {
            if (denyGas(event, event.getPlayer())) {
                return;
            }
            // BlockFertilizeEvent snapshots the blocks that actually change.
            return;
        }
        if (bottleOnCauldron(block, hand)) {
            // Cancelling here is before vanilla fills or empties the bottle. The level event is what
            // snapshots, and only when the liquid actually changes, so an empty click does not freeze it.
            if (gasBlocked(event.getPlayer())) {
                event.setCancelled(true);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                if (event.getHand() == EquipmentSlot.HAND) {
                    tellGas(event.getPlayer());
                }
                return;
            }
        }
        if (!storesHeldItem(block, hand)) {
            return;
        }
        if (denyGas(event, event.getPlayer())) {
            return;
        }
        snapBlock(event.getPlayer(), block);
    }

    /**
     * Pouring a bottle in and filling one out are not bucket events. A failed signature used to put the
     * bottle back and leave the new liquid, so a second bottle could take it out again.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCauldronBottle(CauldronLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player) || !bottleReason(event.getReason())) {
            return;
        }
        if (denyGas(event, player)) {
            return;
        }
        snapBlock(player, event.getBlock());
        if (plugin.words() != null) {
            plugin.words().armSnaps(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldronBottled(CauldronLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && bottleReason(event.getReason())) {
            sync(player);
        }
    }

    private static boolean bottleReason(CauldronLevelChangeEvent.ChangeReason reason) {
        return reason == CauldronLevelChangeEvent.ChangeReason.BOTTLE_EMPTY
                || reason == CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL;
    }

    /** A glass bottle or a potion against a cauldron. Lava and powder snow stay on the bucket path. */
    private static boolean bottleOnCauldron(Block block, org.bukkit.inventory.ItemStack hand) {
        if (block == null || hand == null || !block.getType().name().endsWith("CAULDRON")) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.LAVA_CAULDRON || type == Material.POWDER_SNOW_CAULDRON) {
            return false;
        }
        Material held = hand.getType();
        return held == Material.GLASS_BOTTLE || held == Material.POTION
                || held == Material.SPLASH_POTION || held == Material.LINGERING_POTION;
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
        // A shelf swaps the hand with one displayed stack. A block item is not a block place.
        if (shelf(type)) {
            return !empty || shelfHolds(block);
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
        // Honeycomb is spent into the block. Scraping the wax later drops another honeycomb.
        if (held == Material.HONEYCOMB && waxable(block)) {
            return true;
        }
        return false;
    }

    /** Copper that is not waxed yet, and a sign that can still take wax. Ore and raw blocks do not. */
    private static boolean waxable(Block block) {
        String name = block.getType().name();
        if (!name.startsWith("WAXED_") && !name.contains("ORE") && !name.startsWith("RAW_") && name.contains("COPPER")) {
            return true;
        }
        return block.getState() instanceof org.bukkit.block.Sign sign && !sign.isWaxed();
    }

    /** Oak shelf, poplar shelf, and the rest of the wood set. Not a chiseled bookshelf. */
    private static boolean shelf(Material type) {
        return type != null && type.name().endsWith("_SHELF");
    }

    private static boolean poweredShelf(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.Powerable power && power.isPowered();
    }

    private static boolean shelfHolds(Block block) {
        if (!(block.getState() instanceof org.bukkit.inventory.InventoryHolder holder)) {
            return false;
        }
        for (org.bukkit.inventory.ItemStack stack : holder.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                return true;
            }
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
        // A dirt block on a shelf, or a plant in a pot, leaves the bag without placing a block.
        boolean stored = clicked != null && storesHeldItem(clicked, hand);
        if (!composter && !stored && hand != null && hand.getType().isBlock()) {
            return; // block placing has its own signed path (WordChain.onPlace)
        }
        if (!composter && (hand == null || hand.getType().isAir()) && a == Action.RIGHT_CLICK_AIR) {
            return;
        }
        sync(event.getPlayer());
    }

    /**
     * A saddle, chest, carpet, horse armor, wolf armor or harness leaves the bag and stays on the mob.
     * The horse menu snapshots a click inside it. A right-click equip does not, so a failed signature
     * put the item back in the bag and left it on the mob. Snapshot before vanilla applies the click.
     * Tagged NPCs, combat mobs and minions are not mounts.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMountEquip(PlayerInteractEntityEvent event) {
        org.bukkit.entity.Entity target = event.getRightClicked();
        Player player = event.getPlayer();
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItem(event.getHand());
        if (!willEquip(target, hand)) {
            return;
        }
        if (gasBlocked(player)) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) {
                tellGas(player);
            }
            return;
        }
        if (plugin.words() != null && chainBag(player)) {
            plugin.words().rememberMount(player, target);
        }
    }

    /**
     * Feeding spends the food and leaves the animal in love. A short purse used to roll the food back
     * and still let the baby be born. Cancel before the food is used. A later signature failure is
     * undone by the love snapshot.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreedGas(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Animals animal)) {
            return;
        }
        Player player = event.getPlayer();
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItem(event.getHand());
        if (hand == null || hand.getType().isAir() || !animal.isBreedItem(hand)) {
            return;
        }
        if (!gasBlocked(player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            tellGas(player);
        }
    }

    /** True when this right-click will move the held stack onto the mob (or swap a piece that drops the old one). */
    private static boolean willEquip(org.bukkit.entity.Entity entity, org.bukkit.inventory.ItemStack hand) {
        if (entity == null || entity instanceof Player || hand == null || hand.getType().isAir() || pluginMarked(entity)) {
            return false;
        }
        Material type = hand.getType();
        String name = type.name();
        if (type == Material.SADDLE) {
            if (entity instanceof org.bukkit.entity.Steerable steerable
                    && !(entity instanceof org.bukkit.entity.AbstractHorse)
                    && !steerable.hasSaddle()) {
                return true;
            }
            if (entity instanceof org.bukkit.entity.AbstractHorse horse && emptyStack(horse.getInventory().getSaddle())) {
                return true;
            }
            if (entity instanceof org.bukkit.entity.AbstractNautilus nautilus && emptyStack(nautilus.getInventory().getSaddle())) {
                return true;
            }
            return saddleSlotEmpty(entity);
        }
        if (type == Material.CHEST && entity instanceof org.bukkit.entity.ChestedHorse horse) {
            return !horse.isCarryingChest();
        }
        if (name.endsWith("_CARPET") && !name.contains("MOSS") && entity instanceof org.bukkit.entity.Llama) {
            return true;
        }
        if (name.endsWith("_HORSE_ARMOR") && entity instanceof org.bukkit.entity.Horse) {
            return true;
        }
        if (name.contains("NAUTILUS_ARMOR") && entity instanceof org.bukkit.entity.AbstractNautilus) {
            return true;
        }
        if (!bodyGear(type) || !(entity instanceof org.bukkit.entity.LivingEntity living)) {
            return false;
        }
        if (!living.canUseEquipmentSlot(EquipmentSlot.BODY)) {
            return false;
        }
        // Wolf armor and a harness do not swap. A second one stays in the hand.
        return emptyStack(equipment(living, EquipmentSlot.BODY));
    }

    private static boolean saddleSlotEmpty(org.bukkit.entity.Entity entity) {
        if (entity instanceof org.bukkit.entity.AbstractHorse || entity instanceof org.bukkit.entity.AbstractNautilus) {
            return false;
        }
        if (!(entity instanceof org.bukkit.entity.LivingEntity living) || !living.canUseEquipmentSlot(EquipmentSlot.SADDLE)) {
            return false;
        }
        return emptyStack(equipment(living, EquipmentSlot.SADDLE));
    }

    private static boolean bodyGear(Material type) {
        if (type == null || type == Material.SADDLE) {
            return false;
        }
        String name = type.name();
        return type == Material.WOLF_ARMOR || name.endsWith("_HARNESS") || name.contains("NAUTILUS_ARMOR");
    }

    private static org.bukkit.inventory.ItemStack equipment(org.bukkit.entity.LivingEntity living, EquipmentSlot slot) {
        org.bukkit.inventory.EntityEquipment eq = living.getEquipment();
        if (eq == null || !living.canUseEquipmentSlot(slot)) {
            return null;
        }
        try {
            return eq.getItem(slot);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean emptyStack(org.bukkit.inventory.ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** NPC bodies, combat mobs, minions and other plugin entities are not player tack. */
    private static boolean pluginMarked(org.bukkit.entity.Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("sb-") || tag.startsWith("sb_")) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        // A frame place/take and an armor-stand swap fire a second event after this one.
        // Arming the snapshot here would cancel that second event and the use would never apply.
        org.bukkit.entity.Entity target = event.getRightClicked();
        if (target instanceof org.bukkit.entity.ItemFrame || target instanceof org.bukkit.entity.ArmorStand) {
            return;
        }
        sync(event.getPlayer());
    }

    /** After the frame actually took or returned the item, lock it until the bag write lands. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameChanged(io.papermc.paper.event.player.PlayerItemFrameChangeEvent event) {
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
        if (mobBucket(event.getBucket())) {
            armSpawn(event.getPlayer());
        }
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

    /**
     * A short purse cannot pay for the arrow or the charge. Cancelling the bow event leaves the arrow in the bag.
     * Tridents, pearls, eggs and spawn eggs are a right-click, cancelled below before the entity exists.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBowGas(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && gasBlocked(player)) {
            event.setCancelled(true);
            tellGas(player);
        }
    }

    /**
     * Throws and spawn eggs spend a bag item and leave an entity behind. With too little gas the write puts the
     * item back and the entity would stay (a second arrow, a free mob, a chicken that lays more eggs). A click
     * that opens a chest or a table still opens: the item is not used.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onThrowGas(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item == null || !consumableThrow(item.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking() && opensUi(event.getClickedBlock())) {
            return;
        }
        if (!gasBlocked(player)) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            tellGas(player);
        }
    }

    /** Remember who used the item this tick. The mob or the eye spawns after this event returns. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrowArm(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item == null || !chainBag(event.getPlayer())) {
            return;
        }
        Material type = item.getType();
        if (type.name().endsWith("_SPAWN_EGG") || type == Material.ENDER_EYE) {
            armSpawn(event.getPlayer());
        }
    }

    /** The chicken hatches when the egg lands, which can be while the bag write is still open. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEggLand(PlayerEggThrowEvent event) {
        if (chainBag(event.getPlayer())) {
            armSpawn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsumedSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && reason != CreatureSpawnEvent.SpawnReason.BUCKET
                && reason != CreatureSpawnEvent.SpawnReason.EGG) {
            return;
        }
        Player player = spawner(event.getLocation());
        if (player == null || plugin.words() == null) {
            return;
        }
        // A chicken from a thrown egg is only rolled back while the egg's write is still open.
        // After that write lands, the egg was paid for and the chick stays.
        if (reason == CreatureSpawnEvent.SpawnReason.EGG && !plugin.words().writing(player)) {
            return;
        }
        plugin.words().rememberConsumed(player, event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEye(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.EnderSignal signal) || plugin.words() == null) {
            return;
        }
        Player player = spawner(signal.getLocation());
        if (player != null) {
            plugin.words().rememberConsumed(player, signal);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            return;
        }
        // The hook is the rod, not a spent item. Removing it on a failed write would cancel a legal cast.
        if (!(event.getEntity() instanceof FishHook) && plugin.words() != null && chainBag(player)) {
            plugin.words().rememberConsumed(player, event.getEntity());
        }
        sync(player);
    }

    private void armSpawn(Player player) {
        if (player != null && chainBag(player)) {
            spawnedFrom.put(player.getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    /** Closest player who used a spawning item this tick, in this world. */
    private Player spawner(org.bukkit.Location at) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        int tick = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, Integer>> it = spawnedFrom.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            if (entry.getValue() != tick) {
                it.remove();
            }
        }
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<UUID, Integer> entry : spawnedFrom.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !at.getWorld().equals(player.getWorld())) {
                continue;
            }
            double dist = player.getLocation().distanceSquared(at);
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    /** A right-click that spends the item and leaves a projectile or a mob. */
    private static boolean consumableThrow(Material type) {
        if (type == null) {
            return false;
        }
        if (type.name().endsWith("_SPAWN_EGG")) {
            return true;
        }
        return type == Material.EGG
                || type == Material.SNOWBALL
                || type == Material.ENDER_PEARL
                || type == Material.ENDER_EYE
                || type == Material.EXPERIENCE_BOTTLE
                || type == Material.SPLASH_POTION
                || type == Material.LINGERING_POTION
                || type == Material.WIND_CHARGE
                || type == Material.FIRE_CHARGE
                || type == Material.TRIDENT;
    }

    /** Fish, axolotl, tadpole and the rest. Liquid and powder buckets do not spawn a mob. */
    private static boolean mobBucket(Material type) {
        if (type == null || !type.name().endsWith("_BUCKET")) {
            return false;
        }
        return type != Material.WATER_BUCKET
                && type != Material.LAVA_BUCKET
                && type != Material.MILK_BUCKET
                && type != Material.POWDER_SNOW_BUCKET;
    }

    /** Chests, workstations and other blocks that open instead of using the held item. */
    private static boolean opensUi(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.CRAFTING_TABLE || type == Material.ENCHANTING_TABLE || type == Material.ENDER_CHEST
                || type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL
                || type == Material.GRINDSTONE || type == Material.SMITHING_TABLE || type == Material.STONECUTTER
                || type == Material.LOOM || type == Material.CARTOGRAPHY_TABLE || type == Material.BEACON
                || type == Material.BREWING_STAND) {
            return true;
        }
        return block.getState() instanceof org.bukkit.inventory.InventoryHolder;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            sync(player);
        }
    }
}
