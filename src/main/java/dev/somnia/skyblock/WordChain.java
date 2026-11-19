package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LeashHitch;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.entity.Leashable;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The chain is the inventory. Breaks, places, and the sword move immediately.
 * A failed receipt puts the block, the items, and the player back.
 * One write carries at most eight dirty slots. Mana is the combat word, not a tick loop.
 */
final class WordChain implements Listener {
    static final int COBBLE = 1851776868;
    private static final int BAG = HoldItems.BAG;
    /** Hotbar slot 9 (index 8): the SkyBlock Menu star. Reserved, never item data on chain. */
    static final int STAR = SbMenu.STAR_SLOT;
    private static final int MAX_MANA = 100;
    private static final int MANA_COST = 45;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger MASK16 = BigInteger.ONE.shiftLeft(16).subtract(BigInteger.ONE);
    private static final BigInteger MASK32 = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
    private static final BigInteger MASK64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger MASK160 = BigInteger.ONE.shiftLeft(160).subtract(BigInteger.ONE);

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();
    /** Drop key (Q / Ctrl+Q) or cursor drop in an open inventory: {source bag slot or -1, tick}. The drop event that follows in the same tick uses it instead of the held slot. */
    private final Map<UUID, int[]> clickDrops = new ConcurrentHashMap<>();
    private final Set<Long> placed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> painting = ConcurrentHashMap.newKeySet();
    /** Ender-chest snapshots to restore on the next login if the write rolled back while the player was offline. */
    private final Map<UUID, ItemStack[]> pendingEnder = new ConcurrentHashMap<>();
    /** Set while a rollback removes a leash, so the knot does not drop a second lead. */
    private boolean suppressLeadDrop;
    private final String forgedTopic;
    private static final EquipmentSlot[] STAND_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    WordChain(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.forgedTopic = EventEncoder.encode(new Event("Forged", Arrays.asList(
                new TypeReference<Address>(true) {
                },
                new TypeReference<Uint256>(true) {
                },
                new TypeReference<Uint8>() {
                }
        )));
    }

    /**
     * Blocks players placed on islands give no Skill XP or collection when broken. The set used to live only in
     * memory, so after every restart placed blocks counted as natural ones (place, wait for a restart, break: free
     * XP and event score). It is kept in words/placed.bin now: loaded at start, saved every 5 minutes and at stop.
     */
    private java.io.File placedFile() {
        return new java.io.File(plugin.getDataFolder(), "words/placed.bin");
    }

    private void loadPlaced() {
        java.io.File file = placedFile();
        if (!file.isFile()) {
            return;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                placed.add(in.readLong());
            }
            plugin.getLogger().info("Placed blocks loaded: " + n);
        } catch (java.io.IOException exception) {
            plugin.getLogger().warning("Could not read " + file + ": " + exception.getMessage());
        }
    }

    private void savePlaced() {
        long[] keys = placed.stream().mapToLong(Long::longValue).toArray();
        java.io.File file = placedFile();
        java.io.File tmp = new java.io.File(file.getPath() + ".tmp");
        try {
            file.getParentFile().mkdirs();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
                out.writeInt(keys.length);
                for (long k : keys) {
                    out.writeLong(k);
                }
            }
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException exception) {
            plugin.getLogger().warning("Could not save " + file + ": " + exception.getMessage());
        }
    }

    void start() {
        loadPlaced();
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::savePlaced, 20L * 300, 20L * 300);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickMana, 20L, 20L);
        NamespacedKey key = recipeKey();
        if (Bukkit.getRecipe(key) == null) {
            ShapelessRecipe recipe = new ShapelessRecipe(key, voidResult());
            recipe.addIngredient(new RecipeChoice.MaterialChoice(Material.COBBLESTONE)); // cobble carries SkyBlock lore now
            Bukkit.addRecipe(recipe);
        }
    }

    void stop() {
        savePlaced();
        Bukkit.removeRecipe(recipeKey());
        // A hub break still waiting for its signature holds a bedrock/sprout placeholder: put the resource back
        // so the hub never keeps a stray placeholder outside the regen timers.
        for (Seat seat : seats.values()) {
            for (PendingBreak pending : seat.breaks) {
                if (pending.placeholder) {
                    restoreBlock(pending.location, pending.rollback);
                }
            }
        }
    }

    void login(Player player, boolean fromCreative) {
        UUID id = player.getUniqueId();
        Seat seat = seats.computeIfAbsent(id, ignored -> new Seat());
        Profile profile = plugin.profiles().get(id);
        plugin.wallets().ensure(profile);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BigInteger[] words;
            BigInteger combat;
            try {
                words = plugin.wallets().wordsOf(profile.wallet);
                combat = plugin.wallets().combatOf(profile.wallet);
                try {
                    plugin.wallets().balanceWei(profile.wallet);
                } catch (Exception ignored) {
                    // Gate stays optimistic until a later balance read.
                }
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> loginFailed(player, fromCreative, exception));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> loginLanded(player, fromCreative, words, combat));
        });
    }

    boolean craftVoid(Player player) {
        if (plugin.hold().offchain(player)) {
            tell(player, "Creative mode does not sign crafts. /gamemode 0 restores the chain inventory.", NamedTextColor.RED);
            return false;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            tell(player, "The chain inventory is still loading.", NamedTextColor.RED);
            return false;
        }
        if (busy(player)) {
            tell(player, "A signature is already in flight.", NamedTextColor.YELLOW);
            return false;
        }
        BigInteger[] base = base(seat);
        int from = cobbleSlot(seat.words);
        if (from < 0) {
            tell(player, "You need cobblestone in the signed inventory.", NamedTextColor.RED);
            return false;
        }
        int have = count(seat.words[from]);
        int to = have == 1 ? from : emptySlot(seat.words, base);
        if (to < 0) {
            tell(player, "Free a slot for the sword.", NamedTextColor.RED);
            return false;
        }
        seat.bagBusy = true;
        BigInteger[] preview = base;
        int left = have - 1;
        preview[from] = left <= 0 ? ZERO : stackWord(COBBLE, left);
        paint(player, preview);
        tell(player, "Signing the Voidstep Blade.", NamedTextColor.GRAY);
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            TransactionReceipt receipt = null;
            Exception failure = null;
            try {
                receipt = plugin.wallets().craftVoid(profile, from, to);
            } catch (Exception exception) {
                failure = exception;
            }
            TransactionReceipt landed = receipt;
            Exception error = failure;
            plugin.getServer().getScheduler().runTask(plugin, () -> finishCraft(id, from, to, have, landed, error));
        });
        return true;
    }

    boolean busy(Player player) {
        Seat seat = seats.get(player.getUniqueId());
        return seat == null
                || !seat.loaded
                || seat.bagBusy
                || !seat.breaks.isEmpty()
                || !seat.places.isEmpty()
                || !seat.drops.isEmpty();
    }

    boolean ready(Player player) {
        if (plugin.hold().offchain(player)) {
            return true;
        }
        Seat seat = seats.get(player.getUniqueId());
        return seat != null && seat.loaded;
    }

    boolean barOwned(Player player) {
        if (plugin.hold().offchain(player)) {
            return false;
        }
        Seat seat = seats.get(player.getUniqueId());
        return seat != null && seat.loaded;
    }

    void commitLocal(Player player) {
        commitLocal(player, null);
    }

    /**
     * Signs the live bag, then tells {@code hook} whether the write landed (true) or was rolled back (false).
     * Sinks that pay out (merchant sales) grant the reward only after the bag update lands.
     */
    void commitLocal(Player player, java.util.function.Consumer<Boolean> hook) {
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded || plugin.hold().offchain(player)) {
            if (hook != null) {
                hook.accept(true);
            }
            return;
        }
        if (hook != null) {
            seat.hooks.add(hook);
        }
        seat.trustLocal = true;
        schedule(player);
    }

    private void resolveHooks(Seat seat, boolean ok) {
        List<java.util.function.Consumer<Boolean>> done = seat.flying;
        seat.flying = null;
        if (done == null) {
            return;
        }
        for (java.util.function.Consumer<Boolean> hook : done) {
            try {
                hook.accept(ok);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Bag hook failed: " + exception);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.hold().offchain(player)) {
            return;
        }
        Block block = event.getBlock();
        if (plugin.visits() != null && !plugin.visits().mayEdit(player, block.getLocation())) {
            event.setCancelled(true);
            plugin.visits().deny(player);
            return;
        }
        if (block.getState() instanceof Container || block.getType() == Material.ENDER_CHEST) {
            event.setCancelled(true);
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            event.setCancelled(true);
            return;
        }
        if (plugin.wallets().shortOnGas(plugin.profiles().get(player.getUniqueId()))) {
            event.setCancelled(true);
            tellGas(player);
            return;
        }
        List<ItemStack> drops = new ArrayList<>(block.getDrops(player.getInventory().getItemInMainHand(), player));
        Block lowerHalf = lowerHalf(block);
        if (lowerHalf != null) {
            // Tall flower / tall grass hit on the top half: the item comes from the bottom half (vanilla loot).
            drops = new ArrayList<>(lowerHalf.getDrops(player.getInventory().getItemInMainHand(), player));
        }
        if (HubManager.grassXp(block.getType()) > 0) {
            drops.clear(); // grass and ferns: no bag item (and no Foraging XP; Hypixel grants XP for flowers and logs only)
        }
        Map<Integer, Integer> totals = new HashMap<>();
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir() || drop.getAmount() <= 0) {
                continue;
            }
            int item = HoldItems.id(drop, plugin);
            if (item == 0) {
                continue;
            }
            totals.merge(item, drop.getAmount(), Integer::sum);
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        boolean onIsland = profile.islandReady && plugin.islands().contains(profile, block.getLocation());
        if (totals.isEmpty()) {
            event.setDropItems(false);
            event.setExpToDrop(0);
            clearLowerHalf(lowerHalf);
            if (!onIsland && plugin.hub().placeholder(block) != null) {
                // Regen placeholder goes in now (cleared -> startRegen); the vanilla break must not turn it into air.
                event.setCancelled(true);
            }
            // Nothing to write to the bag (leaves, blocks without a bag item): the resource still pays XP (hub: and regrows).
            plugin.hub().cleared(block.getLocation(), player);
            if (onIsland && !placed.contains(key(block))) {
                plugin.hub().islandCleared(player, block.getBlockData().getAsString());
                plugin.hub().islandRegrow(block.getLocation(), block.getBlockData().getAsString());
            }
            return;
        }
        int[] items = new int[totals.size()];
        int[] counts = new int[totals.size()];
        int cursor = 0;
        for (Map.Entry<Integer, Integer> entry : totals.entrySet()) {
            items[cursor] = entry.getKey();
            counts[cursor] = entry.getValue();
            cursor++;
        }
        long key = key(block);
        if (!seat.held.add(key)) {
            event.setCancelled(true);
            return;
        }
        boolean wasPlaced = placed.contains(key);
        Material type = block.getType();
        // Island and hub share one path: SkyWord bag write now, then Skills XP + collections (SkySkills batch) when it lands.
        boolean islandResource = onIsland && !wasPlaced;
        PendingBreak pending = new PendingBreak(
                block.getLocation().clone(),
                items,
                counts,
                islandResource,
                onIsland && type == Material.COBBLESTONE,
                key,
                wasPlaced,
                block.getBlockData().getAsString()
        );
        // A trusted local change (craft, chest move) is not signed yet: build on the live bag, never repaint the chain over it.
        BigInteger[] trial = seat.trustLocal ? liveWords(player) : shown(seat);
        if (!applyBreak(trial, pending)) {
            seat.held.remove(key);
            event.setCancelled(true);
            tell(player, "Your inventory is full.", NamedTextColor.RED);
            return;
        }
        seat.breaks.add(pending);
        clearLowerHalf(lowerHalf);
        event.setDropItems(false);
        event.setExpToDrop(0);
        // Hub stone/ore/crops: never let the client see air. Cancel the vanilla break and put the regen
        // placeholder (bedrock / sprout) in this same tick; a failed signature still restores the original block.
        BlockData hold = onIsland ? null : plugin.hub().placeholder(block);
        if (hold != null) {
            event.setCancelled(true);
            block.setBlockData(hold, false);
            pending.placeholder = true;
            player.sendBlockChange(block.getLocation(), hold);
        }
        if (seat.trustLocal) {
            paint(player, trial);
        } else {
            show(player, seat);
        }
        schedule(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.hold().offchain(player)) {
            return;
        }
        if (plugin.visits() != null && !plugin.visits().mayEdit(player, event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            plugin.visits().deny(player);
            return;
        }
        ItemStack hand = event.getItemInHand();
        if (hand == null || hand.getType().isAir()) {
            event.setCancelled(true);
            return;
        }
        if (containerItem(hand.getType())) {
            event.setCancelled(true);
            tell(player, "Chests stay as they are.", NamedTextColor.GRAY);
            return;
        }
        if (hand.getType().name().contains("BUCKET")) {
            event.setCancelled(true);
            tell(player, "Buckets stay in the bag.", NamedTextColor.GRAY);
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            event.setCancelled(true);
            return;
        }
        if (plugin.wallets().shortOnGas(plugin.profiles().get(player.getUniqueId()))) {
            event.setCancelled(true);
            tellGas(player);
            return;
        }
        BigInteger packed = pack(hand);
        if (!isFungible(packed) || itemId(packed) == 0) {
            event.setCancelled(true);
            tell(player, "That item is not a signed stack.", NamedTextColor.RED);
            return;
        }
        int slot = event.getHand() == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        Material type = event.getBlockPlaced().getType();
        long placedKey = key(event.getBlockPlaced());
        if (!seat.held.add(placedKey)) {
            event.setCancelled(true);
            return;
        }
        PendingPlace pending = new PendingPlace(
                event.getBlockPlaced().getLocation().clone(),
                event.getBlockPlaced().getBlockData().getAsString(),
                slot,
                itemId(packed),
                placedKey,
                type == Material.COBBLESTONE || type == Material.OAK_LOG,
                event.getBlockReplacedState().getBlockData().getAsString()
        );
        BigInteger[] trial = seat.trustLocal ? liveWords(player) : shown(seat);
        if (!applyPlace(trial, pending)) {
            seat.held.remove(placedKey);
            event.setCancelled(true);
            tell(player, "That block is not in the signed inventory.", NamedTextColor.RED);
            return;
        }
        seat.places.add(pending);
        schedule(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (plugin.hold().offchain(player)) {
            return;
        }
        if (plugin.visits() != null && (plugin.visits().isGuest(player) || !plugin.visits().mayEdit(player, player.getLocation()))) {
            event.setCancelled(true);
            if (plugin.visits().isGuest(player)) {
                plugin.visits().deny(player);
            }
            return;
        }
        event.setCancelled(true);
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        int slot = player.getInventory().getHeldItemSlot();
        int[] click = clickDrops.remove(player.getUniqueId());
        if (click != null && click[1] == Bukkit.getCurrentTick()) {
            if (click[0] < 0) {
                // Dropped from the cursor or from a slot outside the bag: the server puts it back into the inventory
                // (the drop is cancelled) and the next layout write records where it went. Never touch the held slot.
                return;
            }
            slot = click[0];
        }
        int amount = Math.max(1, event.getItemDrop().getItemStack().getAmount());
        if (slot >= 0 && slot < BAG && isAotv(shown(seat)[slot])) {
            tell(player, "The sword stays in the bag.", NamedTextColor.YELLOW);
            return;
        }
        PendingDrop pending = new PendingDrop(slot, amount);
        BigInteger[] trial = seat.trustLocal ? liveWords(player) : shown(seat);
        if (!applyDrop(trial, pending)) {
            tell(player, "That item is not signed into that slot yet.", NamedTextColor.RED);
            return;
        }
        seat.drops.add(pending);
        if (seat.trustLocal) {
            paint(player, trial);
        } else {
            show(player, seat);
        }
        schedule(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCast(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (plugin.hold().offchain(player)) {
            return;
        }
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack stack = player.getInventory().getItem(slot);
        Long serial = swordSerial(stack);
        if (serial == null) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && interactable(event.getClickedBlock())) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            tell(player, "The chain inventory is still loading.", NamedTextColor.RED);
            return;
        }
        int tick = Bukkit.getCurrentTick();
        if (seat.castTick == tick) {
            return;
        }
        BigInteger word = seat.words[slot];
        if (!isAotv(word) || serial(word) != serial) {
            tell(player, "That sword is not signed into that slot yet.", NamedTextColor.RED);
            return;
        }
        if (manaNow(seat) < MANA_COST) {
            tell(player, "Not enough mana.", NamedTextColor.RED);
            return;
        }
        Location dest = aim(player);
        if (dest == null) {
            tell(player, "A wall is in the way.", NamedTextColor.RED);
            return;
        }
        seat.castTick = tick;
        Hop hop = new Hop(player.getLocation().clone(), dest, slot);
        seat.combat = spent(seat);
        player.teleport(dest);
        player.setFallDistance(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, true, true));
        if (plugin.stats() != null) {
            plugin.stats().chainMana(player, manaNow(seat), MAX_MANA);
            plugin.stats().flash(player, Component.text("-" + MANA_COST + " Mana (", NamedTextColor.AQUA)
                    .append(Component.text("Void Step", NamedTextColor.GOLD)).append(Component.text(")", NamedTextColor.AQUA)), 1500);
        } else {
            player.sendActionBar(Component.text("Mana " + manaNow(seat) + "/" + MAX_MANA, NamedTextColor.AQUA));
        }
        seat.hops.add(hop);
        seat.castQueue.add(hop);
        if (!seat.castDrain) {
            seat.castDrain = true;
            UUID id = player.getUniqueId();
            Profile profile = plugin.profiles().get(id);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> drainCasts(id, profile));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof ShapelessRecipe recipe) || !recipeKey().equals(recipe.getKey())) {
            return;
        }
        if (!(event.getInventory() instanceof CraftingInventory inventory)) {
            return;
        }
        if (inventory.getLocation() == null || !plainCobbleCraft(inventory)) {
            inventory.setResult(null);
            return;
        }
        inventory.setResult(voidResult());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCraftTake(CraftItemEvent event) {
        if (!(event.getRecipe() instanceof ShapelessRecipe recipe) || !recipeKey().equals(recipe.getKey())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getInventory() instanceof CraftingInventory inventory)) {
            return;
        }
        if (inventory.getLocation() == null || !plainCobbleCraft(inventory)) {
            inventory.setResult(null);
            return;
        }
        if (!craftVoid(player)) {
            return;
        }
        ItemStack[] matrix = inventory.getMatrix();
        Arrays.fill(matrix, null);
        inventory.setMatrix(matrix);
        inventory.setResult(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        clickDrops.remove(player.getUniqueId());
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null) {
            return;
        }
        // A layout write still waiting for its 2-tick batch must not be lost when the player leaves right after a move.
        boolean pending = seat.task >= 0 || seat.again;
        if (seat.task >= 0) {
            plugin.getServer().getScheduler().cancelTask(seat.task);
            seat.task = -1;
        }
        if (!seat.bagBusy && (pending || seat.trusted != null || seat.trustLocal || !seat.breaks.isEmpty() || !seat.places.isEmpty() || !seat.drops.isEmpty())) {
            flush(player);
        }
    }

    /**
     * Moves between the bag and a chest, furnace, hopper or ender chest are trusted bag writes. If that write cannot
     * be paid, the bag goes back to its last signed state but the container keeps what it got (a dupe), so with too
     * little gas these clicks wait, like block breaks and places do. The first click of a pending write also snapshots
     * the container so a later non-gas failure can put it back.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (lockedByOther(player, top)) {
            event.setCancelled(true);
            tellSaving(player);
            return;
        }
        if (containerGasBlocked(player, top)) {
            event.setCancelled(true);
            tellGas(player);
            return;
        }
        rememberContainer(player, top);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onContainerDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (lockedByOther(player, top)) {
            event.setCancelled(true);
            tellSaving(player);
            return;
        }
        if (containerGasBlocked(player, top)) {
            event.setCancelled(true);
            tellGas(player);
            return;
        }
        rememberContainer(player, top);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSnapOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (lockedByOther(player, event.getInventory())) {
            event.setCancelled(true);
            tellSaving(player);
        }
    }

    /**
     * A failed bag write restores the snapshot taken at the first click. A hopper, dropper or second player can move
     * the items out before that receipt, so the restore puts them back and the bag still has them (a dupe). Hold the
     * snapshot still until the write lands or is fully rolled back.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapHopper(InventoryMoveItemEvent event) {
        if (!anySnaps()) {
            return;
        }
        if (snapLocks(event.getSource()) || snapLocks(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSnapHopperSearch(org.bukkit.event.inventory.HopperInventorySearchEvent event) {
        if (!anySnaps()) {
            return;
        }
        Block search = event.getSearchBlock();
        if ((event.getInventory() != null && snapLocks(event.getInventory())) || (search != null && snapLocksBlock(search))) {
            event.setInventory(null);
        }
    }

    /**
     * Breaking a snapshotted frame, stand, boat, cart or chested animal drops the item and the rollback puts it
     * back as well. Hold the entity still until the write lands or is fully rolled back.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapHurt(EntityDamageEvent event) {
        if (snapLocksEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapVehicleDestroy(VehicleDestroyEvent event) {
        if (snapLocksEntity(event.getVehicle())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapHangingBreak(HangingBreakEvent event) {
        if (snapLocksEntity(event.getEntity()) || hitchLeashesSnap(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Breaking the knot while the lead's bag write can still roll back would drop a lead and then return one
     * to the bag. Keep the leash until the write lands. A rollback clears it with no drop.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapUnleash(EntityUnleashEvent event) {
        if (snapLocksEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Runs last so a rollback's clear cannot be turned back into a dropped lead. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSnapUnleashDrop(EntityUnleashEvent event) {
        if (suppressLeadDrop) {
            event.setDropLeash(false);
        }
    }

    /**
     * A dropper, dispenser or crafter shoots the stack into the world. A campfire that finishes pops the
     * food as an item. The rollback then puts the snapshot back, so both copies exist. Hold the eject until
     * the write lands. Furnace smelts stay in the block, which the snapshot already covers.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapDispense(BlockDispenseEvent event) {
        if (snapLocksBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Armor equip is its own event, not a plain dispense, and still removes the stack from the dispenser. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapDispenseArmor(org.bukkit.event.block.BlockDispenseArmorEvent event) {
        if (snapLocksBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapCrafter(CrafterCraftEvent event) {
        if (snapLocksBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapCook(BlockCookEvent event) {
        Material type = event.getBlock().getType();
        if (type != Material.CAMPFIRE && type != Material.SOUL_CAMPFIRE) {
            return;
        }
        if (snapLocksBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * The snapshot is the block before the book, disc, pot item or crop change. Breaking it, pushing it,
     * burning it, or breaking the block it stands on drops that item, and a full rollback puts the snapshot
     * back too (a second copy). Runs before the snapshot is taken, so the click that starts the write is not cancelled.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!lockedBlock(block) && !heldUp(block)) {
            return;
        }
        event.setCancelled(true);
        tellHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Action action = event.getAction();
        boolean physical = action == Action.PHYSICAL;
        if (!physical && action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        // Trample hits the farmland under a snapshotted crop.
        boolean locked = lockedBlock(block) || (physical && lockedBlock(block.getRelative(BlockFace.UP)));
        if (!locked) {
            return;
        }
        event.setCancelled(true);
        if (!physical) {
            tellHeld(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapLectern(PlayerTakeLecternBookEvent event) {
        if (!lockedBlock(event.getLectern().getBlock())) {
            return;
        }
        event.setCancelled(true);
        tellHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapHarvest(PlayerHarvestBlockEvent event) {
        if (!lockedBlock(event.getHarvestedBlock())) {
            return;
        }
        event.setCancelled(true);
        tellHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!lockedBlock(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        tellHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapBucketFill(PlayerBucketFillEvent event) {
        if (!lockedBlock(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        tellHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapPiston(BlockPistonExtendEvent event) {
        if (movesLocked(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapPistonRetract(BlockPistonRetractEvent event) {
        if (movesLocked(event.getBlock(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapExplode(EntityExplodeEvent event) {
        if (!anySnaps()) {
            return;
        }
        event.blockList().removeIf(this::lockedBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapBlockExplode(BlockExplodeEvent event) {
        if (!anySnaps()) {
            return;
        }
        event.blockList().removeIf(this::lockedBlock);
    }

    /** Water into a snapshotted crop or lectern. The source the player just placed still flows elsewhere. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapFlow(BlockFromToEvent event) {
        if (lockedBlock(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    /** Island fire still burns. A lectern, bookshelf or the block under a pot would drop a second copy. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        if (lockedBlock(block) || heldUp(block)) {
            event.setCancelled(true);
        }
    }

    /** Farmland drying pops the crop above it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (lockedBlock(block) || heldUp(block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapDecay(LeavesDecayEvent event) {
        if (lockedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * A second click on a chest boat, minecart or animal can take the chest off. The inventory shrinks,
     * the cargo drops, and the size check then skips the rollback (bag copy and ground copy).
     * The click that took the snapshot is earlier than this priority, so it still applies.
     * A plain rearrange of the bag, with the carrier open, does not touch it and stays allowed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapCarrierClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !carrierClickLocked(event)) {
            return;
        }
        event.setCancelled(true);
        tellSaving(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapCarrierDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !carrierDragLocked(event)) {
            return;
        }
        event.setCancelled(true);
        tellSaving(player);
    }

    /**
     * Taking the chest off a llama or donkey drops the cargo after the click. Hold those drops with the
     * snapshot: a failed write puts the chest back, a landed write drops them once.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSnapCarrierDrop(EntityDropItemEvent event) {
        if (!snapLocksEntity(event.getEntity())) {
            return;
        }
        WorldSnap snap = carrierSnap(event.getEntity());
        ItemStack stack = event.getItemDrop().getItemStack();
        if (snap == null || stack == null || stack.getType().isAir()) {
            return;
        }
        snap.withheld.add(stack.clone());
        event.setCancelled(true);
    }

    /**
     * Place, take and rotate. The snapshot is taken on the interact, and the lock arms on this event's
     * monitor, so the change that started the write still applies. A later click waits.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapFrameTake(PlayerItemFrameChangeEvent event) {
        if (!snapLocksEntity(event.getItemFrame())) {
            return;
        }
        event.setCancelled(true);
        tellHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapFrameUse(PlayerInteractEntityEvent event) {
        if (snapLocksEntity(event.getRightClicked())) {
            event.setCancelled(true);
            tellHeld(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapFrameUseAt(PlayerInteractAtEntityEvent event) {
        if (snapLocksEntity(event.getRightClicked())) {
            event.setCancelled(true);
            tellHeld(event.getPlayer());
        }
    }

    /** Same timing as a frame: the swap that took the snapshot is not locked yet; the next one is. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnapStandUse(PlayerArmorStandManipulateEvent event) {
        if (snapLocksEntity(event.getRightClicked())) {
            event.setCancelled(true);
            tellHeld(event.getPlayer());
        }
    }

    private boolean worldContainer(Player player, Inventory top) {
        if (top == null || top.getHolder() instanceof Menu || plugin.hold().offchain(player)) {
            return false;
        }
        InventoryType type = top.getType();
        return type == InventoryType.ENDER_CHEST
                || (top.getLocation() != null && type != InventoryType.CRAFTING && type != InventoryType.WORKBENCH && type != InventoryType.PLAYER);
    }

    private boolean containerGasBlocked(Player player, Inventory top) {
        if (!worldContainer(player, top)) {
            return false;
        }
        Seat seat = seats.get(player.getUniqueId());
        return seat != null && seat.loaded && plugin.wallets().shortOnGas(plugin.profiles().get(player.getUniqueId()));
    }

    private void tellSaving(Player player) {
        player.sendActionBar(Component.text("That container is being saved. Wait a moment.", NamedTextColor.YELLOW));
    }

    private void tellHeld(Player player) {
        if (player != null) {
            player.sendActionBar(Component.text("That is being saved. Wait a moment.", NamedTextColor.YELLOW));
        }
    }

    /** True when a rollback snapshot already covers this block. The click that creates the snapshot is not locked yet. */
    private boolean lockedBlock(Block block) {
        return block != null && anySnaps() && snapLocksBlock(block);
    }

    /** A lectern, pot or crop is standing on this block. Breaking or burning it pops the snapshot. */
    private boolean heldUp(Block block) {
        return block != null && lockedBlock(block.getRelative(BlockFace.UP));
    }

    private boolean movesLocked(Block piston, java.util.List<Block> moved) {
        if (lockedBlock(piston) || heldUp(piston)) {
            return true;
        }
        if (moved == null) {
            return false;
        }
        for (Block block : moved) {
            if (lockedBlock(block) || heldUp(block)) {
                return true;
            }
        }
        return false;
    }

    /** Chest boat, minecart or animal chest whose rollback is still open. */
    private boolean carrierLocked(Inventory inventory) {
        if (inventory == null || !anySnaps()) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Entity entity && !(entity instanceof Player) && snapLocksEntity(entity);
    }

    /** Shift-click and any click in the carrier. A bag-only click does not change the carrier. */
    private boolean carrierClickLocked(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!carrierLocked(top)) {
            return false;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked != null && clicked.equals(top)) {
            return true;
        }
        return event.isShiftClick()
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR;
    }

    private boolean carrierDragLocked(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!carrierLocked(top)) {
            return false;
        }
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                return true;
            }
        }
        return false;
    }

    private WorldSnap carrierSnap(Entity entity) {
        if (entity == null || !anySnaps()) {
            return null;
        }
        UUID id = entity.getUniqueId();
        for (Seat seat : seats.values()) {
            if (!seatWriting(seat)) {
                continue;
            }
            for (WorldSnap snap : seat.snaps) {
                if (snap.kind == WorldSnap.CONTAINER && id.equals(snap.entity)) {
                    return snap;
                }
            }
        }
        return null;
    }

    /** Snapshot is only enforced while a bag write can still roll it back. A cancelled click must not freeze hoppers. */
    private static boolean seatWriting(Seat seat) {
        return seat.bagBusy || seat.task >= 0 || seat.trustLocal || seat.again || seat.trusted != null
                || !seat.breaks.isEmpty() || !seat.places.isEmpty() || !seat.drops.isEmpty();
    }

    private boolean anySnaps() {
        for (Seat seat : seats.values()) {
            if (!seat.snaps.isEmpty() && seatWriting(seat)) {
                return true;
            }
        }
        return false;
    }

    /** Another player's pending rollback owns this inventory. Their own clicks still go through. */
    private boolean lockedByOther(Player player, Inventory inventory) {
        if (player == null || inventory == null || !anySnaps()) {
            return false;
        }
        for (Map.Entry<UUID, Seat> entry : seats.entrySet()) {
            if (entry.getKey().equals(player.getUniqueId()) || !seatWriting(entry.getValue())) {
                continue;
            }
            for (WorldSnap snap : entry.getValue().snaps) {
                if (snapMatches(snap, inventory)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean snapLocks(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (Seat seat : seats.values()) {
            if (!seatWriting(seat)) {
                continue;
            }
            for (WorldSnap snap : seat.snaps) {
                if (snapMatches(snap, inventory)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean snapLocksBlock(Block block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        Location loc = block.getLocation();
        for (Seat seat : seats.values()) {
            if (!seatWriting(seat)) {
                continue;
            }
            for (WorldSnap snap : seat.snaps) {
                // A chest boat or minecart stores its own inventory. Its feet are not that block.
                if (snap.entity != null || snap.location == null || snap.location.getWorld() == null
                        || !snap.location.getWorld().equals(loc.getWorld())) {
                    continue;
                }
                if (sameBlock(snap.location, loc) || linkedChest(snap.location, loc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean snapLocksEntity(Entity entity) {
        if (entity == null || !anySnaps()) {
            return false;
        }
        UUID id = entity.getUniqueId();
        for (Seat seat : seats.values()) {
            if (!seatWriting(seat)) {
                continue;
            }
            for (WorldSnap snap : seat.snaps) {
                if (id.equals(snap.entity)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The fence knot of a leash whose bag write can still roll back. */
    private boolean hitchLeashesSnap(Entity hitch) {
        if (!(hitch instanceof LeashHitch) || !anySnaps()) {
            return false;
        }
        for (Seat seat : seats.values()) {
            if (!seatWriting(seat)) {
                continue;
            }
            for (WorldSnap snap : seat.snaps) {
                if (snap.kind != WorldSnap.LEASH || snap.entity == null) {
                    continue;
                }
                Entity mob = Bukkit.getEntity(snap.entity);
                if (mob instanceof Leashable leashable && leashable.isLeashed() && hitch.equals(leashable.getLeashHolder())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean snapMatches(WorldSnap snap, Inventory inventory) {
        if (snap.kind == WorldSnap.ENDER) {
            return inventory.getType() == InventoryType.ENDER_CHEST
                    && inventory.getHolder() instanceof Player holder
                    && snap.owner != null
                    && snap.owner.equals(holder.getUniqueId());
        }
        if (snap.entity != null) {
            return inventory.getHolder() instanceof Entity entity && snap.entity.equals(entity.getUniqueId());
        }
        Location loc = inventory.getLocation();
        if (snap.location == null || loc == null || loc.getWorld() == null || snap.location.getWorld() == null
                || !loc.getWorld().equals(snap.location.getWorld())) {
            return false;
        }
        return sameBlock(snap.location, loc) || linkedChest(snap.location, loc);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    /** The other half of a double chest. Hoppers address whichever half they touch. */
    private static boolean linkedChest(Location snapAt, Location other) {
        if (snapAt.getWorld() == null || other.getWorld() == null || !snapAt.getWorld().equals(other.getWorld())) {
            return false;
        }
        int dx = Math.abs(snapAt.getBlockX() - other.getBlockX());
        int dy = Math.abs(snapAt.getBlockY() - other.getBlockY());
        int dz = Math.abs(snapAt.getBlockZ() - other.getBlockZ());
        if (dx + dy + dz != 1) {
            return false;
        }
        Block a = snapAt.getBlock();
        Block b = other.getBlock();
        Material type = a.getType();
        if ((type != Material.CHEST && type != Material.TRAPPED_CHEST) || type != b.getType()) {
            return false;
        }
        if (!(a.getState() instanceof org.bukkit.block.Chest ca) || !(b.getState() instanceof org.bukkit.block.Chest cb)) {
            return false;
        }
        InventoryHolder ha = ca.getInventory().getHolder();
        InventoryHolder hb = cb.getInventory().getHolder();
        return ha != null && ha.equals(hb);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int source;
        switch (event.getAction()) {
            case DROP_ONE_SLOT, DROP_ALL_SLOT -> source = event.getClickedInventory() instanceof org.bukkit.inventory.PlayerInventory own && player.equals(own.getHolder())
                    && event.getSlot() >= 0 && event.getSlot() < BAG ? event.getSlot() : -1;
            case DROP_ONE_CURSOR, DROP_ALL_CURSOR -> source = -1;
            default -> {
                return;
            }
        }
        clickDrops.put(player.getUniqueId(), new int[] {source, Bukkit.getCurrentTick()});
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !(event.getView().getTopInventory().getHolder() instanceof Menu)) {
            // Crafts and moves to or from a chest, table, or furnace are server-validated: they change item totals on purpose.
            if (event instanceof CraftItemEvent || event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
                trust(player);
            }
            noteLocal(player);
        }
    }

    private void trust(Player player) {
        if (plugin.hold().offchain(player)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat != null && seat.loaded) {
            seat.trustLocal = true;
        }
    }

    /** True while ingredients sit in a crafting grid: they are out of the bag view but not gone. */
    private boolean craftGridBusy(Player player) {
        if (plugin.craft() != null && plugin.craft().gridBusy(player)) {
            return true;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top instanceof CraftingInventory crafting)) {
            return false;
        }
        for (ItemStack stack : crafting.getMatrix()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (!(event.getView().getTopInventory().getHolder() instanceof Menu)
                    && event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
                trust(player);
            }
            noteLocal(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || event.getView().getTopInventory().getHolder() instanceof Menu) {
            return;
        }
        if (workbench(event.getInventory())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    noteLocal(player);
                }
            });
            return;
        }
        noteLocal(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        noteLocal(event.getPlayer());
    }

    /**
     * A crafting table grid (not the player's own 2x2 grid). Paper returns the player's location for the 2x2 grid's
     * getLocation(), so the old "getLocation() != null" test matched the player's own inventory too and every
     * slot-only move (click, drag, shift, number keys, offhand swap) was never written to SkyWord.
     */
    private static boolean workbench(Inventory inventory) {
        return inventory instanceof CraftingInventory && inventory.getType() == InventoryType.WORKBENCH;
    }

    private void noteLocal(Player player) {
        if (plugin.hold().offchain(player) || painting.contains(player.getUniqueId())) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        if (workbench(player.getOpenInventory().getTopInventory())) {
            return;
        }
        if (seat.bagBusy) {
            seat.again = true;
        }
        schedule(player);
    }

    private void schedule(Player player) {
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || seat.task >= 0) {
            return;
        }
        seat.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            seat.task = -1;
            Player online = plugin.getServer().getPlayer(player.getUniqueId());
            if (online != null) {
                flush(online);
            }
        }, 2L).getTaskId();
    }

    private void flush(Player player) {
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded || plugin.hold().offchain(player)) {
            return;
        }
        if (seat.bagBusy) {
            seat.again = true;
            return;
        }
        if (seat.trustLocal || seat.trusted != null) {
            sendTrusted(player, seat);
            return;
        }
        if (!seat.breaks.isEmpty() || !seat.places.isEmpty() || !seat.drops.isEmpty()) {
            show(player, seat);
            sendWorld(player, seat);
            return;
        }
        sendClick(player, seat);
    }

    private void sendWorld(Player player, Seat seat) {
        BigInteger[] target = base(seat);
        List<PendingBreak> breaks = new ArrayList<>();
        List<PendingPlace> places = new ArrayList<>();
        List<PendingDrop> drops = new ArrayList<>();
        boolean toldFull = false;
        boolean rejected = false;
        for (PendingBreak pending : new ArrayList<>(seat.breaks)) {
            BigInteger[] trial = copy(target);
            if (!applyBreak(trial, pending)) {
                seat.breaks.remove(pending);
                seat.held.remove(pending.key);
                restoreBlock(pending.location, pending.rollback);
                rejected = true;
                if (!toldFull) {
                    tell(player, "Your inventory is full.", NamedTextColor.RED);
                    toldFull = true;
                }
                continue;
            }
            if (dirty(seat.words, trial).size() > 8) {
                break;
            }
            target = trial;
            breaks.add(pending);
        }
        for (PendingPlace pending : new ArrayList<>(seat.places)) {
            if (!breaks.isEmpty() && !places.isEmpty()) {
                break;
            }
            BigInteger[] trial = copy(target);
            if (!applyPlace(trial, pending)) {
                seat.places.remove(pending);
                seat.held.remove(pending.key);
                restoreBlock(pending.location, pending.replaced);
                rejected = true;
                tell(player, "That block is not in the signed inventory.", NamedTextColor.RED);
                continue;
            }
            if (dirty(seat.words, trial).size() > 8) {
                break;
            }
            target = trial;
            places.add(pending);
        }
        for (PendingDrop pending : new ArrayList<>(seat.drops)) {
            if (pending.slot >= 0 && pending.slot < BAG && isAotv(target[pending.slot])) {
                seat.drops.remove(pending);
                tell(player, "The sword stays in the bag.", NamedTextColor.YELLOW);
                continue;
            }
            BigInteger[] trial = copy(target);
            if (!applyDrop(trial, pending)) {
                seat.drops.remove(pending);
                rejected = true;
                tell(player, "That item is not signed into that slot yet.", NamedTextColor.RED);
                continue;
            }
            if (dirty(seat.words, trial).size() > 8) {
                break;
            }
            target = trial;
            drops.add(pending);
        }
        if (rejected) {
            show(player, seat);
        }
        if (breaks.isEmpty() && places.isEmpty() && drops.isEmpty()) {
            sendClick(player, seat);
            return;
        }
        if (dirty(seat.words, target).isEmpty()) {
            for (PendingBreak pending : breaks) {
                seat.breaks.remove(pending);
                seat.held.remove(pending.key);
                if (pending.items.length == 0) {
                    pending.location.getBlock().setType(Material.AIR);
                }
                if (pending.forgetPlaced) {
                    placed.remove(pending.key);
                }
            }
            for (PendingPlace pending : places) {
                seat.places.remove(pending);
                seat.held.remove(pending.key);
                if (pending.mark) {
                    placed.add(pending.key);
                }
            }
            seat.drops.removeAll(drops);
            show(player, seat);
            return;
        }
        seat.breaks.removeAll(breaks);
        seat.places.removeAll(places);
        seat.drops.removeAll(drops);
        sendLayout(player, seat, target, breaks, places, drops);
    }

    /**
     * Signs the live bag as it is now. The live bag already shows every pending break, place, and drop,
     * so they ride in the same write and settle with its receipt.
     */
    private void sendTrusted(Player player, Seat seat) {
        if (craftGridBusy(player)) {
            return;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            schedule(player);
            return;
        }
        BigInteger[] target = liveWords(player);
        seat.trusted = null;
        seat.trustLocal = false;
        if (!seat.hooks.isEmpty()) {
            if (seat.flying == null) {
                seat.flying = new ArrayList<>();
            }
            seat.flying.addAll(seat.hooks);
            seat.hooks.clear();
        }
        List<PendingBreak> breaks = new ArrayList<>(seat.breaks);
        List<PendingPlace> places = new ArrayList<>(seat.places);
        List<PendingDrop> drops = new ArrayList<>(seat.drops);
        seat.breaks.clear();
        seat.places.clear();
        seat.drops.clear();
        if (dirty(seat.words, target).isEmpty()) {
            if (!breaks.isEmpty() || !places.isEmpty() || !drops.isEmpty()) {
                finishLayout(player.getUniqueId(), target, List.of(), 0, null, breaks, places, drops, true);
            }
            dropSnaps(seat);
            seat.trustFails = 0;
            resolveHooks(seat, true);
            return;
        }
        sendLayout(player, seat, target, breaks, places, drops, true);
        if (!seat.bagBusy) {
            resolveHooks(seat, true); // nothing to sign after all
        }
    }

    private void sendClick(Player player, Seat seat) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            if (!seat.cursorNoted) {
                seat.cursorNoted = true;
                tell(player, "Put the cursor item into a slot.", NamedTextColor.YELLOW);
            }
            schedule(player);
            return;
        }
        seat.cursorNoted = false;
        if (craftGridBusy(player)) {
            return;
        }
        BigInteger[] live = liveWords(player);
        if (!sameTotals(seat.words, live)) {
            paint(player, seat.words);
            return;
        }
        if (dirty(seat.words, live).isEmpty()) {
            return;
        }
        if (dirty(seat.words, live).size() > 8) {
            tell(player, "Move fewer stacks at once.", NamedTextColor.YELLOW);
            paint(player, seat.words);
            return;
        }
        plugin.getLogger().info("Slot move write for " + player.getName() + ": " + dirty(seat.words, live).size() + " slot(s) " + dirty(seat.words, live) + ".");
        sendLayout(player, seat, live, List.of(), List.of(), List.of());
    }

    private void sendLayout(
            Player player,
            Seat seat,
            BigInteger[] target,
            List<PendingBreak> breaks,
            List<PendingPlace> places,
            List<PendingDrop> drops
    ) {
        sendLayout(player, seat, target, breaks, places, drops, false);
    }

    private void sendLayout(
            Player player,
            Seat seat,
            BigInteger[] target,
            List<PendingBreak> breaks,
            List<PendingPlace> places,
            List<PendingDrop> drops,
            boolean trusted
    ) {
        List<Batch> batches = batches(seat.words, target);
        if (batches.isEmpty()) {
            return;
        }
        seat.bagBusy = true;
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        BigInteger[] next = copy(target);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Exception failure = null;
            int landed = 0;
            try {
                for (Batch batch : batches) {
                    plugin.wallets().writeWords(profile, batch.slots, batch.words);
                    landed++;
                }
            } catch (Exception exception) {
                failure = exception;
            }
            int done = landed;
            Exception error = failure;
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    finishLayout(id, next, batches, done, error, breaks, places, drops, trusted));
        });
    }

    private void finishLayout(
            UUID id,
            BigInteger[] target,
            List<Batch> batches,
            int done,
            Exception error,
            List<PendingBreak> breaks,
            List<PendingPlace> places,
            List<PendingDrop> drops,
            boolean trusted
    ) {
        Seat seat = seats.get(id);
        if (seat == null) {
            return;
        }
        seat.bagBusy = false;
        Player online = plugin.getServer().getPlayer(id);
        if (trusted && done < batches.size() && online != null && !plugin.hold().offchain(online) && seat.trustFails < 2 && !noGas(error)) {
            // Retry before any rollback: keep the live bag and the pending world changes, sign again with a fresh nonce.
            for (int i = 0; i < done; i++) {
                applyBatch(seat.words, batches.get(i));
            }
            seat.trustFails++;
            if (seat.flying != null) {
                seat.hooks.addAll(0, seat.flying);
                seat.flying = null;
            }
            seat.breaks.addAll(0, breaks);
            seat.places.addAll(0, places);
            seat.drops.addAll(0, drops);
            seat.trustLocal = true;
            plugin.getLogger().warning("Bag write failed for " + online.getName() + " (try " + seat.trustFails + "), retrying: " + blame(error));
            tell(online, "The inventory signature did not land. Retrying...", NamedTextColor.YELLOW);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player again = plugin.getServer().getPlayer(id);
                if (again != null) {
                    schedule(again);
                }
            }, 30L);
            return;
        }
        if (trusted && done > 0 && done < batches.size()) {
            // Part of a trusted write landed (writes over 8 slots go in several batches). Telling the hooks "rolled
            // back" now would hand back things that are already in the signed bag (stash claim, refunds): a dupe.
            // Keep the live bag and sign the rest once the purse can pay; never report this write as rolled back.
            boolean gas = noGas(error);
            if (online != null && !plugin.hold().offchain(online) && (gas || seat.trustFails < 30)) {
                for (int i = 0; i < done; i++) {
                    applyBatch(seat.words, batches.get(i));
                }
                if (seat.trustFails == 0 || seat.trustFails % 6 == 0) {
                    tell(online, gas ? "Part of your inventory is signed. The rest waits for gas: use /deposit."
                            : "Part of your inventory is signed. Retrying the rest...", NamedTextColor.YELLOW);
                }
                seat.trustFails++;
                if (seat.flying != null) {
                    seat.hooks.addAll(0, seat.flying);
                    seat.flying = null;
                }
                seat.breaks.addAll(0, breaks);
                seat.places.addAll(0, places);
                seat.drops.addAll(0, drops);
                seat.trustLocal = true;
                plugin.getLogger().warning("Bag write for " + online.getName() + " landed " + done + "/" + batches.size()
                        + " batches, signing the rest later: " + blame(error));
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    Player again = plugin.getServer().getPlayer(id);
                    if (again != null) {
                        schedule(again);
                    }
                }, 200L);
                return;
            }
            int dropped = seat.flying == null ? 0 : seat.flying.size();
            seat.flying = null;
            plugin.getLogger().warning("Bag write for " + id + " landed " + done + "/" + batches.size() + " batches and cannot finish ("
                    + blame(error) + "); " + dropped + " waiting market callback(s) dropped instead of rolled back (no dupe). Check the stash by hand.");
        }
        if (trusted) {
            if (done < batches.size()) {
                plugin.getLogger().warning("Bag write failed for " + id + " after retries, rolling back: " + blame(error));
                seat.trustLocal = false;
            }
            seat.trustFails = 0;
            resolveHooks(seat, done >= batches.size());
        }
        for (PendingBreak pending : breaks) {
            seat.held.remove(pending.key);
        }
        for (PendingPlace pending : places) {
            seat.held.remove(pending.key);
        }
        Player player = plugin.getServer().getPlayer(id);
        if (done < batches.size()) {
            for (int i = 0; i < done; i++) {
                applyBatch(seat.words, batches.get(i));
            }
            if (done == 0) {
                for (PendingBreak pending : breaks) {
                    restoreBlock(pending.location, pending.rollback);
                }
                for (PendingPlace pending : places) {
                    restoreBlock(pending.location, pending.replaced);
                }
                restoreSnaps(seat, id);
            } else {
                dropSnaps(seat);
            }
            boolean wiping = seat.clearing;
            seat.clearing = false;
            if (player != null && !plugin.hold().offchain(player)) {
                show(player, seat);
                tell(player, wiping
                        ? "The purse could not clear the old bag. Rejoin when it can pay the gas."
                        : noGas(error)
                        ? gasFailMessage(id, breaks.isEmpty() && places.isEmpty())
                        : "Could not sign that inventory change: " + blame(error) + " Your bag is back to the last signed state.", NamedTextColor.RED);
            }
            if (seat.again || seat.trustLocal || seat.trusted != null || !seat.breaks.isEmpty()) {
                if (player != null) {
                    schedule(player);
                }
            }
            return;
        }
        System.arraycopy(target, 0, seat.words, 0, BAG);
        dropSnaps(seat);
        if (player != null && !plugin.hold().offchain(player) && !seat.trustLocal) {
            show(player, seat);
        }
        if (seat.clearing) {
            seat.clearing = false;
            Profile cleared = plugin.profiles().get(id);
            cleared.bagCleared = true;
            plugin.profiles().save(cleared);
            String wallet = cleared.wallet;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    BigInteger[] onchain = plugin.wallets().wordsOf(wallet);
                    int left = 0;
                    for (BigInteger word : onchain) {
                        if (word != null && word.signum() != 0) {
                            left++;
                        }
                    }
                    plugin.getLogger().info("Bag clear verified for " + wallet + ": SkyWord wordsOf has " + left + " non-empty slots" + (left == 0 ? " (empty)." : "."));
                } catch (Exception exception) {
                    plugin.getLogger().warning("Bag clear check failed for " + wallet + ": " + exception.getMessage());
                }
            });
            if (player != null && !plugin.hold().offchain(player)) {
                player.getEnderChest().clear();
                player.setItemOnCursor(null);
                tell(player, "The old bag is cleared. The cave chest has the starter tools.", NamedTextColor.GOLD);
            }
        }
        for (PendingBreak pending : breaks) {
            if (!pending.placeholder) {
                pending.location.getBlock().setType(Material.AIR);
            }
            if (pending.forgetPlaced) {
                placed.remove(pending.key);
            }
            if (pending.refill) {
                Location location = pending.location.clone();
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> refill(location), 8L);
            }
            plugin.hub().cleared(pending.location, player);
            if (pending.islandResource) {
                plugin.hub().islandRegrow(pending.location, pending.rollback);
            }
            if (player != null && pending.islandResource) {
                plugin.hub().islandCleared(player, pending.rollback);
            }
        }
        for (PendingPlace pending : places) {
            Block block = pending.location.getBlock();
            try {
                block.setBlockData(Bukkit.createBlockData(pending.data));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Could not place block data " + pending.data);
            }
            if (pending.mark) {
                placed.add(pending.key);
            }
        }
        if (player != null && (seat.again || seat.trustLocal || seat.trusted != null || !seat.breaks.isEmpty() || !seat.places.isEmpty() || !seat.drops.isEmpty())) {
            schedule(player);
        }
        seat.again = false;
    }

    private void finishCraft(UUID id, int from, int to, int have, TransactionReceipt receipt, Exception error) {
        Seat seat = seats.get(id);
        if (seat == null) {
            return;
        }
        seat.bagBusy = false;
        Player player = plugin.getServer().getPlayer(id);
        if (error != null || receipt == null) {
            if (player != null && !plugin.hold().offchain(player)) {
                paint(player, seat.words);
                tell(player, blame(error), NamedTextColor.RED);
            }
            if (player != null && (seat.again || seat.trustLocal || !seat.breaks.isEmpty() || seat.trusted != null)) {
                schedule(player);
            }
            return;
        }
        long serial = forgedSerial(receipt);
        if (serial <= 0) {
            if (player != null) {
                tell(player, "The craft landed without a serial. Reloading the bag.", NamedTextColor.YELLOW);
                login(player, false);
            }
            return;
        }
        int left = have - 1;
        if (to != from) {
            seat.words[from] = left <= 0 ? ZERO : stackWord(COBBLE, left);
        }
        seat.words[to] = aotvWord(serial);
        if (player != null && !plugin.hold().offchain(player) && seat.trustLocal) {
            BigInteger[] live = liveWords(player);
            live[to] = aotvWord(serial);
            paint(player, live);
            tell(player, "Voidstep Blade #" + serial + ".", NamedTextColor.DARK_PURPLE);
        } else if (player != null && !plugin.hold().offchain(player)) {
            paint(player, seat.words);
            tell(player, "Voidstep Blade #" + serial + ".", NamedTextColor.DARK_PURPLE);
        }
        if (player != null && (seat.again || seat.trustLocal || !seat.breaks.isEmpty() || seat.trusted != null)) {
            schedule(player);
        }
    }

    private void drainCasts(UUID id, Profile profile) {
        List<Hop> sent = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        List<Hop> rejected = new ArrayList<>();
        Exception reject = null;
        try {
            while (true) {
                Seat seat = seats.get(id);
                if (seat == null) {
                    break;
                }
                Hop hop = seat.castQueue.poll();
                if (hop == null) {
                    break;
                }
                try {
                    hashes.add(plugin.wallets().submitCast(profile, hop.slot));
                    sent.add(hop);
                } catch (Exception exception) {
                    reject = exception;
                    rejected.add(hop);
                    Hop extra;
                    while ((extra = seat.castQueue.poll()) != null) {
                        rejected.add(extra);
                    }
                    break;
                }
            }
            Exception refused = reject;
            for (Hop hop : rejected) {
                plugin.getServer().getScheduler().runTask(plugin, () -> completeHop(id, hop, refused));
            }
            for (int i = 0; i < sent.size(); i++) {
                Hop hop = sent.get(i);
                String hash = hashes.get(i);
                Exception failure = null;
                try {
                    plugin.wallets().confirmCast(hash);
                } catch (Exception exception) {
                    failure = exception;
                }
                Exception error = failure;
                plugin.getServer().getScheduler().runTask(plugin, () -> completeHop(id, hop, error));
            }
        } finally {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Seat seat = seats.get(id);
                if (seat == null) {
                    return;
                }
                seat.castDrain = false;
                if (!seat.castQueue.isEmpty()) {
                    seat.castDrain = true;
                    Profile again = plugin.profiles().get(id);
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> drainCasts(id, again));
                }
            });
        }
    }

    private void completeHop(UUID id, Hop hop, Exception error) {
        hop.done = true;
        hop.ok = error == null;
        hop.error = error;
        Seat seat = seats.get(id);
        if (seat == null) {
            return;
        }
        for (Hop pending : seat.hops) {
            if (!pending.done) {
                return;
            }
        }
        Location settle = null;
        boolean failed = false;
        for (Hop pending : seat.hops) {
            if (settle == null) {
                settle = pending.from;
            }
            if (pending.ok) {
                settle = pending.to;
            } else {
                failed = true;
            }
        }
        List<Hop> finished = new ArrayList<>(seat.hops);
        seat.hops.clear();
        Player player = plugin.getServer().getPlayer(id);
        if (!failed) {
            return;
        }
        if (player != null && !plugin.hold().offchain(player) && settle != null && unpaid(player, finished, settle)) {
            player.teleport(settle);
            player.setFallDistance(0);
            player.removePotionEffect(PotionEffectType.SPEED);
        } else if (player == null && settle != null) {
            seat.snapBack = settle;
        }
        Exception blame = null;
        for (Hop pending : finished) {
            if (!pending.ok) {
                blame = pending.error;
                break;
            }
        }
        if (player != null) {
            tell(player, blame(blame), NamedTextColor.RED);
        }
        Profile profile = plugin.profiles().get(id);
        Location back = settle;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BigInteger combat = null;
            try {
                combat = plugin.wallets().combatOf(profile.wallet);
            } catch (Exception exception) {
                plugin.getLogger().warning("Cast chain could not reread mana: " + trim(exception.getMessage()));
            }
            BigInteger landed = combat;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Seat current = seats.get(id);
                if (current != null && landed != null) {
                    current.combat = landed;
                }
                Player online = plugin.getServer().getPlayer(id);
                if (online == null || plugin.hold().offchain(online)) {
                    return;
                }
                if (back != null && unpaid(online, finished, back)) {
                    online.teleport(back);
                    online.setFallDistance(0);
                }
                online.removePotionEffect(PotionEffectType.SPEED);
                if (current != null) {
                    reapplySpeed(online, current.combat);
                }
            });
        });
    }

    private void loginLanded(Player player, boolean fromCreative, BigInteger[] words, BigInteger combat) {
        if (!player.isOnline()) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE && !fromCreative) {
            return;
        }
        Seat seat = seats.computeIfAbsent(player.getUniqueId(), ignored -> new Seat());
        for (int i = 0; i < BAG; i++) {
            seat.words[i] = i < words.length && words[i] != null ? words[i] : ZERO;
        }
        seat.combat = combat == null ? ZERO : combat;
        seat.loaded = true;
        seat.loginTries = 0;
        ItemStack[] ender = pendingEnder.remove(player.getUniqueId());
        if (ender != null) {
            player.getEnderChest().setContents(ender);
        }
        boolean pendingWorld = !seat.breaks.isEmpty() || !seat.places.isEmpty() || !seat.drops.isEmpty();
        if (!seat.bagBusy && !pendingWorld) {
            seat.held.clear();
            seat.trusted = null;
            seat.trustLocal = false;
            seat.trustFails = 0;
            seat.again = false;
        }
        BigInteger[] sane = sanitize(player, seat.words);
        boolean dropped = !Arrays.equals(sane, seat.words);
        paint(player, sane);
        if (dropped && !pendingWorld && !seat.bagBusy) {
            // Sign the cleaned layout so the chain matches what the server accepts.
            seat.trustLocal = true;
            schedule(player);
        }
        if (!seat.words[STAR].equals(ZERO)) {
            boolean moved = seat.hidden8.equals(ZERO);
            plugin.getLogger().info("SkyWord slot 8 (menu star) held item data for " + player.getName()
                    + (moved ? "; moved it to a free slot, signing the corrected layout." : "; bag full, kept on chain until a slot frees up."));
            if (moved && !pendingWorld && !seat.bagBusy) {
                schedule(player);
            }
        }
        if (pendingWorld) {
            show(player, seat);
            if (!seat.bagBusy) {
                schedule(player);
            }
        }
        if (seat.snapBack != null) {
            player.teleport(seat.snapBack);
            player.removePotionEffect(PotionEffectType.SPEED);
            seat.snapBack = null;
        }
        plugin.hold().releaseChain(player);
        long until = seat.combat.shiftRight(128).and(MASK64).longValue();
        long now = System.currentTimeMillis() / 1000L;
        if (until > now) {
            int ticks = (int) Math.min(60, (until - now) * 20L);
            if (ticks > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, false, true, true));
            }
        }
        tell(player, fromCreative
                ? "Survival. This inventory is the chain again."
                : "This inventory is the chain. Breaks and teleports happen now and roll back if the signature fails.", NamedTextColor.GREEN);
        freshBag(player, 0);
    }

    private void freshBag(Player player, int attempt) {
        if (!player.isOnline() || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.bagCleared) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        boolean busy = seat == null
                || !seat.loaded
                || seat.bagBusy
                || seat.clearing
                || !seat.breaks.isEmpty()
                || !seat.places.isEmpty()
                || !seat.drops.isEmpty();
        if (busy) {
            if (attempt < 20) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> freshBag(player, attempt + 1), 20L);
            }
            return;
        }
        player.getEnderChest().clear();
        player.setItemOnCursor(null);
        BigInteger[] empty = new BigInteger[BAG];
        Arrays.fill(empty, ZERO);
        if (dirty(seat.words, empty).isEmpty()) {
            profile.bagCleared = true;
            plugin.profiles().save(profile);
            paint(player, empty);
            return;
        }
        tell(player, "Clearing the old bag. The purse pays the gas.", NamedTextColor.YELLOW);
        seat.clearing = true;
        sendLayout(player, seat, empty, List.of(), List.of(), List.of());
    }

    private void loginFailed(Player player, boolean fromCreative, Exception exception) {
        if (!player.isOnline()) {
            return;
        }
        Seat seat = seats.computeIfAbsent(player.getUniqueId(), ignored -> new Seat());
        seat.loginTries++;
        plugin.getLogger().warning("Could not read the word bag: " + trim(exception.getMessage()));
        if (seat.loginTries < 3) {
            tell(player, "Shannon did not return the inventory. Trying again.", NamedTextColor.RED);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> login(player, fromCreative), 40L);
            return;
        }
        if (fromCreative) {
            plugin.hold().keepCreative(player);
            return;
        }
        tell(player, "Shannon did not return the inventory.", NamedTextColor.RED);
    }

    private void tickMana() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (plugin.hold().offchain(player)) {
                continue;
            }
            Seat seat = seats.get(player.getUniqueId());
            if (seat == null || !seat.loaded) {
                continue;
            }
            if (plugin.stats() != null) {
                plugin.stats().chainMana(player, manaNow(seat), MAX_MANA);
            } else {
                player.sendActionBar(Component.text("Mana " + manaNow(seat) + "/" + MAX_MANA, NamedTextColor.AQUA));
            }
        }
    }

    private void refill(Location location) {
        Block now = location.getBlock();
        if (!generator(now)) {
            return;
        }
        Material type = now.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA || type == Material.OBSIDIAN || type == Material.STONE || type == Material.COBBLESTONE) {
            now.setType(Material.COBBLESTONE);
        }
    }

    private boolean generator(Block block) {
        return plugin.islands().isGenerator(block);
    }

    private boolean applyBreak(BigInteger[] target, PendingBreak pending) {
        for (int i = 0; i < pending.items.length; i++) {
            if (!addItem(target, pending.items[i], pending.counts[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean addItem(BigInteger[] target, int item, int amount) {
        int left = amount;
        int max = maxStack(item);
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            if (slot == STAR) {
                continue;
            }
            BigInteger word = target[slot];
            if (!isFungible(word) || itemId(word) != item || count(word) <= 0) {
                continue;
            }
            int room = max - count(word);
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, left);
            target[slot] = stackWord(item, count(word) + move);
            left -= move;
        }
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            if (slot == STAR || !target[slot].equals(ZERO)) {
                continue;
            }
            int move = Math.min(max, left);
            target[slot] = stackWord(item, move);
            left -= move;
        }
        return left == 0;
    }

    private boolean applyPlace(BigInteger[] target, PendingPlace pending) {
        if (pending.slot < 0 || pending.slot >= BAG) {
            return false;
        }
        BigInteger word = target[pending.slot];
        if (!isFungible(word) || itemId(word) != pending.item || count(word) < 1) {
            return false;
        }
        int next = count(word) - 1;
        target[pending.slot] = next == 0 ? ZERO : stackWord(pending.item, next);
        return true;
    }

    private boolean applyDrop(BigInteger[] target, PendingDrop pending) {
        if (pending.slot < 0 || pending.slot >= BAG) {
            return false;
        }
        BigInteger word = target[pending.slot];
        if (count(word) < pending.amount || itemId(word) == 0) {
            return false;
        }
        if (isAotv(word)) {
            return false;
        }
        int next = count(word) - pending.amount;
        target[pending.slot] = next <= 0 ? ZERO : stackWord(itemId(word), next);
        return true;
    }

    private int maxStack(int item) {
        ItemStack sample = HoldItems.stack(plugin, item, 1, 0);
        if (sample == null) {
            return 64;
        }
        return Math.max(1, Math.min(64, sample.getMaxStackSize()));
    }

    private int cobbleSlot(BigInteger[] words) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == STAR) {
                continue;
            }
            BigInteger word = words[slot];
            if (isFungible(word) && itemId(word) == COBBLE && count(word) >= 1) {
                return slot;
            }
        }
        return -1;
    }

    private int emptySlot(BigInteger[] words, BigInteger[] shown) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot != STAR && words[slot].equals(ZERO) && shown[slot].equals(ZERO)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Load-time sanity check (never trust the chain blindly): a word must be a known item with a sane count,
     * or a Voidstep Blade of the configured collection. Anything else is dropped from the view and logged;
     * the next signed write clears it onchain.
     */
    private BigInteger[] sanitize(Player player, BigInteger[] words) {
        BigInteger[] out = Arrays.copyOf(words, words.length);
        for (int i = 0; i < out.length; i++) {
            BigInteger word = out[i];
            if (word == null || word.signum() == 0) {
                out[i] = ZERO;
                continue;
            }
            boolean ok;
            if (isFungible(word)) {
                int item = itemId(word);
                int n = count(word);
                ok = n > 0 && n <= maxStack(item) && HoldItems.stack(plugin, item, 1, 0) != null
                        && word.shiftRight(48).and(MASK16).signum() == 0 && word.shiftRight(224).signum() == 0;
            } else {
                ok = isAotv(word);
            }
            if (!ok) {
                plugin.getLogger().warning("Chain sanity: dropped slot " + i + " word 0x" + word.toString(16) + " for "
                        + player.getName() + " (not an item the server accepts).");
                out[i] = ZERO;
            }
        }
        return out;
    }

    /** /withdraw blade <address>: the Voidstep Blade in hand leaves the game for the player's main wallet (server-attested). */
    boolean withdrawBlade(Player player, String to) {
        Seat seat = seats.get(player.getUniqueId());
        if (plugin.hold().offchain(player) || seat == null || !seat.loaded) {
            tell(player, "The chain inventory is still loading.", NamedTextColor.RED);
            return false;
        }
        if (busy(player)) {
            tell(player, "A signature is already in flight. Try again in a moment.", NamedTextColor.YELLOW);
            return false;
        }
        int slot = player.getInventory().getHeldItemSlot();
        Long serial = swordSerial(player.getInventory().getItem(slot));
        if (serial == null || slot == STAR || !isAotv(seat.words[slot]) || serial(seat.words[slot]) != serial) {
            tell(player, "Hold the Voidstep Blade you want to withdraw.", NamedTextColor.RED);
            return false;
        }
        seat.bagBusy = true;
        tell(player, "Signing the withdraw of Voidstep Blade #" + serial + " to " + WalletService.shortAddress(to) + ".", NamedTextColor.GRAY);
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Exception failure = null;
            TransactionReceipt receipt = null;
            try {
                receipt = plugin.wallets().withdrawNft(profile, slot, to);
            } catch (Exception exception) {
                failure = exception;
            }
            Exception error = failure;
            String hash = receipt == null ? null : receipt.getTransactionHash();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Seat now = seats.get(id);
                if (now == null) {
                    return;
                }
                now.bagBusy = false;
                Player online = plugin.getServer().getPlayer(id);
                if (error != null) {
                    if (online != null) {
                        tell(online, noGas(error) ? "Not enough STT for gas, use /deposit." : "Withdraw failed: " + blame(error), NamedTextColor.RED);
                    }
                    return;
                }
                now.words[slot] = ZERO;
                if (online != null) {
                    ItemStack held = online.getInventory().getItem(slot);
                    Long still = swordSerial(held);
                    if (still != null && still.longValue() == serial.longValue()) {
                        online.getInventory().setItem(slot, null);
                    } else {
                        paint(online, now.words);
                    }
                    online.sendMessage(plugin.realm().txLine("Voidstep Blade #" + serial + " is now on " + WalletService.shortAddress(to) + ". /deposit-nft " + serial + " brings it back.", hash));
                }
                if (online != null && (now.again || now.trustLocal || !now.breaks.isEmpty() || now.trusted != null)) {
                    schedule(online);
                }
            });
        });
        return true;
    }

    /** /deposit-nft <serial>: a Voidstep Blade the game wallet owns again goes into a free slot (server-checked, attested). */
    boolean depositBlade(Player player, long serial) {
        Seat seat = seats.get(player.getUniqueId());
        if (plugin.hold().offchain(player) || seat == null || !seat.loaded) {
            tell(player, "The chain inventory is still loading.", NamedTextColor.RED);
            return false;
        }
        if (busy(player)) {
            tell(player, "A signature is already in flight. Try again in a moment.", NamedTextColor.YELLOW);
            return false;
        }
        BigInteger[] base = base(seat);
        int slot = emptySlot(seat.words, base);
        if (slot < 0 || player.getInventory().getItem(slot) != null) {
            tell(player, "Free an inventory slot first.", NamedTextColor.RED);
            return false;
        }
        seat.bagBusy = true;
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        tell(player, "Checking Voidstep Blade #" + serial + " on Somnia.", NamedTextColor.GRAY);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String problem = null;
            TransactionReceipt receipt = null;
            Exception failure = null;
            try {
                String owner = plugin.wallets().aotvOwner(serial);
                if (owner == null || !owner.equalsIgnoreCase(profile.wallet)) {
                    problem = "Send the blade to your game wallet " + WalletService.shortAddress(profile.wallet) + " first (/deposit shows it).";
                } else if (plugin.wallets().aotvLocked(serial)) {
                    problem = "That blade is already in the game.";
                } else {
                    receipt = plugin.wallets().depositNft(profile, slot, serial);
                }
            } catch (Exception exception) {
                failure = exception;
            }
            String why = problem;
            Exception error = failure;
            String hash = receipt == null ? null : receipt.getTransactionHash();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Seat now = seats.get(id);
                if (now == null) {
                    return;
                }
                now.bagBusy = false;
                Player online = plugin.getServer().getPlayer(id);
                if (why != null || error != null) {
                    if (online != null) {
                        tell(online, why != null ? why : noGas(error) ? "Not enough STT for gas, use /deposit." : "Deposit failed: " + blame(error), NamedTextColor.RED);
                    }
                    return;
                }
                now.words[slot] = aotvWord(serial);
                if (online != null) {
                    if (online.getInventory().getItem(slot) == null) {
                        online.getInventory().setItem(slot, aspect(serial));
                    } else {
                        paint(online, now.words);
                    }
                    online.sendMessage(plugin.realm().txLine("Voidstep Blade #" + serial + " is back in your inventory.", hash));
                }
                if (online != null && (now.again || now.trustLocal || !now.breaks.isEmpty() || now.trusted != null)) {
                    schedule(online);
                }
            });
        });
        return true;
    }

    /**
     * Slot 8 is the SkyBlock Menu star and is never an item slot. If chain data still maps an item to slot 8
     * (older layouts), show it in the first free slot (or on a matching stack) instead; the next write signs that move.
     * If the bag is truly full the word stays at 8 (kept on chain, hidden behind the star) until there is room.
     */
    static BigInteger[] reserve(BigInteger[] words, java.util.function.IntUnaryOperator maxStack) {
        BigInteger[] out = Arrays.copyOf(words, words.length);
        BigInteger word = out[STAR];
        if (word == null || word.signum() == 0) {
            return out;
        }
        if (isFungible(word)) {
            int item = itemId(word);
            int max = maxStack.applyAsInt(item);
            for (int slot = 0; slot < 36; slot++) {
                BigInteger other = out[slot];
                if (slot != STAR && isFungible(other) && itemId(other) == item && count(other) > 0
                        && count(other) + count(word) <= max) {
                    out[slot] = stackWord(item, count(other) + count(word));
                    out[STAR] = ZERO;
                    return out;
                }
            }
        }
        for (int slot = 0; slot < 36; slot++) {
            if (slot != STAR && out[slot].signum() == 0) {
                out[slot] = word;
                out[STAR] = ZERO;
                return out;
            }
        }
        return out;
    }

    private ItemStack starOrNull(ItemStack stack) {
        return SbMenu.isStar(plugin, stack) ? stack : null;
    }

    private BigInteger[] base(Seat seat) {
        return reserve(seat.words, this::maxStack);
    }

    private BigInteger[] liveWords(Player player) {
        BigInteger[] words = new BigInteger[BAG];
        Arrays.fill(words, ZERO);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length && i < 36; i++) {
            words[i] = pack(storage[i]);
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length && i < 4; i++) {
            words[36 + i] = pack(armor[i]);
        }
        words[40] = pack(player.getInventory().getItemInOffHand());
        // The star slot never carries item data; only a word that could not be moved out yet (full bag) stays there.
        Seat seat = seats.get(player.getUniqueId());
        words[STAR] = seat == null ? ZERO : seat.hidden8;
        return words;
    }

    private void paint(Player player, BigInteger[] words) {
        painting.add(player.getUniqueId());
        words = reserve(words, this::maxStack);
        Seat owner = seats.get(player.getUniqueId());
        if (owner != null) {
            owner.hidden8 = words[STAR] == null ? ZERO : words[STAR];
        }
        ItemStack[] storage = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 36; i++) {
            // Never place chain items into the star slot: it belongs to the SkyBlock Menu star.
            storage[i] = i == STAR ? starOrNull(player.getInventory().getItem(STAR)) : unpack(words[i]);
        }
        for (int i = 0; i < 4; i++) {
            armor[i] = unpack(words[36 + i]);
        }
        player.getInventory().setStorageContents(storage);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(unpack(words[40]));
        player.setItemOnCursor(null);
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            painting.remove(id);
            plugin.menu().give(player);
        });
    }

    private BigInteger pack(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return ZERO;
        }
        if (stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "craft-display"), PersistentDataType.BYTE)) {
            return ZERO;
        }
        if (SbMenu.isStar(plugin, stack)) {
            return ZERO;
        }
        Long serial = swordSerial(stack);
        if (serial != null) {
            return aotvWord(serial);
        }
        int item = HoldItems.id(stack, plugin);
        if (item == 0) {
            return ZERO;
        }
        return stackWord(item, Math.min(64, stack.getAmount()));
    }

    private ItemStack unpack(BigInteger word) {
        if (word == null || word.equals(ZERO)) {
            return null;
        }
        if (isAotv(word)) {
            return aspect(serial(word));
        }
        if (!isFungible(word)) {
            return null;
        }
        ItemStack stack = HoldItems.stack(plugin, itemId(word), count(word), 0);
        if (stack != null) {
            HoldItems.makeUnbreakable(stack);
        }
        return stack;
    }

    private ItemStack aspect(long serial) {
        ItemStack stack = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Lore.plain("Voidstep Blade", NamedTextColor.DARK_PURPLE));
        meta.lore(List.of(
                Lore.plain("Damage: ", NamedTextColor.GRAY).append(Component.text("+120", NamedTextColor.RED)),
                Lore.plain("Strength: ", NamedTextColor.GRAY).append(Component.text("+100", NamedTextColor.RED)),
                Component.empty(),
                Lore.plain("Ability: Void Step ", NamedTextColor.GOLD).append(Component.text("RIGHT CLICK", NamedTextColor.YELLOW).decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)),
                Lore.plain("Blink 8 blocks ahead and gain", NamedTextColor.GRAY),
                Lore.plain("+50 Speed for 3 seconds.", NamedTextColor.GRAY),
                Lore.plain("Mana Cost: ", NamedTextColor.DARK_GRAY).append(Component.text("45", NamedTextColor.DARK_AQUA)),
                Component.empty(),
                Lore.plain("Serial #" + serial + " on Somnia", NamedTextColor.DARK_GRAY),
                Component.empty(),
                Lore.footer(Gear.Rarity.EPIC, "SWORD")
        ));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "aotv"), PersistentDataType.LONG, serial);
        stack.setItemMeta(meta);
        HoldItems.makeUnbreakable(stack);
        return stack;
    }

    private Long swordSerial(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHERITE_SWORD || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "aotv"), PersistentDataType.LONG);
    }

    private BigInteger aotvWord(long serial) {
        String contract = plugin.wallets().aotvContract();
        BigInteger address = new BigInteger(contract.substring(2), 16);
        return BigInteger.ONE.shiftLeft(32)
                .or(address.shiftLeft(64))
                .or(BigInteger.valueOf(serial).shiftLeft(224));
    }

    private boolean isAotv(BigInteger word) {
        if (word == null || word.equals(ZERO)) {
            return false;
        }
        String contract = plugin.wallets().aotvContract();
        if (contract == null || contract.length() < 42) {
            return false;
        }
        BigInteger want = new BigInteger(contract.substring(2), 16);
        return word.shiftRight(64).and(MASK160).equals(want) && count(word) == 1 && serial(word) > 0;
    }

    private static boolean isFungible(BigInteger word) {
        return word.shiftRight(64).and(MASK160).equals(ZERO);
    }

    private static int itemId(BigInteger word) {
        return word.and(MASK32).intValue();
    }

    private static int count(BigInteger word) {
        return word.shiftRight(32).and(MASK16).intValue();
    }

    private static long serial(BigInteger word) {
        return word.shiftRight(224).and(MASK32).longValue();
    }

    private static BigInteger stackWord(int item, int count) {
        if (item <= 0 || count <= 0) {
            return ZERO;
        }
        return BigInteger.valueOf(item).or(BigInteger.valueOf(Math.min(count, 64)).shiftLeft(32));
    }

    private void show(Player player, Seat seat) {
        if (player == null || !player.isOnline() || plugin.hold().offchain(player)) {
            return;
        }
        paint(player, shown(seat));
    }

    private BigInteger[] shown(Seat seat) {
        BigInteger[] target = base(seat);
        for (PendingBreak pending : seat.breaks) {
            applyBreak(target, pending);
        }
        for (PendingPlace pending : seat.places) {
            applyPlace(target, pending);
        }
        for (PendingDrop pending : seat.drops) {
            applyDrop(target, pending);
        }
        return target;
    }

    /** Bottom half of a tall plant whose top half is being broken, or null. */
    private static Block lowerHalf(Block block) {
        if (!HubManager.isFlora(block.getType()) || !(block.getBlockData() instanceof org.bukkit.block.data.Bisected half)
                || half.getHalf() != org.bukkit.block.data.Bisected.Half.TOP) {
            return null;
        }
        Block lower = block.getRelative(BlockFace.DOWN);
        return lower.getType() == block.getType() ? lower : null;
    }

    /** Removes the bottom half quietly so vanilla never drops its loot as a world item (the bag write carries it). */
    private static void clearLowerHalf(Block lower) {
        if (lower != null) {
            lower.setType(Material.AIR, false);
        }
    }

    private void restoreBlock(Location location, String data) {
        if (location == null || data == null || data.isBlank()) {
            return;
        }
        try {
            BlockData restored = Bukkit.createBlockData(data);
            if (HubManager.isFlora(restored.getMaterial()) && restored instanceof org.bukkit.block.data.Bisected half) {
                // Tall grass / tall flowers: the vanilla break took both halves, put both back without physics.
                org.bukkit.block.data.Bisected other = (org.bukkit.block.data.Bisected) restored.clone();
                boolean top = half.getHalf() == org.bukkit.block.data.Bisected.Half.TOP;
                other.setHalf(top ? org.bukkit.block.data.Bisected.Half.BOTTOM : org.bukkit.block.data.Bisected.Half.TOP);
                Block pair = location.getBlock().getRelative(top ? BlockFace.DOWN : BlockFace.UP);
                if (pair.getType().isAir()) {
                    pair.setBlockData(other, false);
                }
                location.getBlock().setBlockData(restored, false);
                return;
            }
            location.getBlock().setBlockData(restored);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Could not restore block data " + data);
        }
    }

    private NamespacedKey recipeKey() {
        return new NamespacedKey(plugin, "aspect_of_the_void");
    }

    ItemStack voidResult() {
        ItemStack stack = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Voidstep Blade", NamedTextColor.DARK_PURPLE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Click to sign.", NamedTextColor.YELLOW).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("Spends 1 cobblestone.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("The number appears when the receipt lands.", NamedTextColor.DARK_GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "craft-display"), PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        HoldItems.makeUnbreakable(stack);
        return stack;
    }

    private boolean plainCobbleCraft(CraftingInventory inventory) {
        int stacks = 0;
        for (ItemStack item : inventory.getMatrix()) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            if (item.getType() != Material.COBBLESTONE || Items.isEnchanted(item, plugin) || swordSerial(item) != null) {
                return false;
            }
            stacks++;
        }
        return stacks == 1;
    }

    private boolean unpaid(Player player, List<Hop> hops, Location settle) {
        Location now = player.getLocation();
        if (near(now, settle)) {
            return false;
        }
        for (Hop hop : hops) {
            if (!hop.ok && near(now, hop.to)) {
                return true;
            }
        }
        return false;
    }

    private boolean near(Location now, Location there) {
        if (now == null || there == null || now.getWorld() == null || there.getWorld() == null) {
            return false;
        }
        if (!now.getWorld().equals(there.getWorld())) {
            return false;
        }
        return now.distanceSquared(there) <= 4.0;
    }

    private BigInteger spent(Seat seat) {
        int left = Math.max(0, manaNow(seat) - MANA_COST);
        long now = System.currentTimeMillis() / 1000L;
        long hp = 100;
        if (seat.combat != null && !seat.combat.equals(ZERO)) {
            long storedHp = seat.combat.shiftRight(96).and(MASK32).longValue();
            if (storedHp > 0) {
                hp = storedHp;
            }
        }
        long speedUntil = now + 3L;
        return BigInteger.valueOf(left)
                .or(BigInteger.valueOf(now).shiftLeft(32))
                .or(BigInteger.valueOf(hp).shiftLeft(96))
                .or(BigInteger.valueOf(speedUntil).shiftLeft(128));
    }

    private void reapplySpeed(Player player, BigInteger combat) {
        if (combat == null || combat.equals(ZERO)) {
            return;
        }
        long until = combat.shiftRight(128).and(MASK64).longValue();
        long now = System.currentTimeMillis() / 1000L;
        if (until <= now) {
            return;
        }
        int ticks = (int) Math.min(60, (until - now) * 20L);
        if (ticks > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, false, true, true));
        }
    }

    private int manaNow(Seat seat) {
        if (seat.combat == null || seat.combat.equals(ZERO)) {
            return MAX_MANA;
        }
        int stored = seat.combat.and(MASK32).intValue();
        long stamp = seat.combat.shiftRight(32).and(MASK64).longValue();
        long grown = stored + Math.max(0, System.currentTimeMillis() / 1000L - stamp) * 2L;
        if (grown > MAX_MANA) {
            return MAX_MANA;
        }
        return (int) Math.max(0, grown);
    }

    private Location aim(Player player) {
        org.bukkit.util.Vector dir = player.getLocation().getDirection();
        if (dir.lengthSquared() < 1.0E-6) {
            return null;
        }
        dir.normalize();
        if (pathBlocked(player.getEyeLocation(), dir) || pathBlocked(player.getLocation().clone().add(0, 0.9, 0), dir)) {
            return null;
        }
        Location dest = player.getLocation().clone().add(dir.clone().multiply(8.0));
        if (solid(dest.getBlock())) {
            Block above = dest.getBlock().getRelative(BlockFace.UP);
            if (solid(above) || solid(above.getRelative(BlockFace.UP))) {
                return null;
            }
            dest.setY(above.getY());
        }
        if (solid(dest.getBlock()) || solid(dest.clone().add(0, 1, 0).getBlock())) {
            return null;
        }
        return dest;
    }

    private boolean pathBlocked(Location start, org.bukkit.util.Vector dir) {
        if (start.getWorld() == null) {
            return true;
        }
        var hit = start.getWorld().rayTraceBlocks(start, dir, 8.0, org.bukkit.FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) {
            return false;
        }
        if (hit.getHitBlockFace() != BlockFace.UP) {
            return true;
        }
        Block above = hit.getHitBlock().getRelative(BlockFace.UP);
        return solid(above) || solid(above.getRelative(BlockFace.UP));
    }

    private boolean solid(Block block) {
        Material type = block.getType();
        if (type == Material.LAVA || type == Material.FIRE || type == Material.MAGMA_BLOCK) {
            return true;
        }
        return !block.isPassable();
    }

    private boolean sameTotals(BigInteger[] left, BigInteger[] right) {
        return fungibleTotals(left).equals(fungibleTotals(right)) && serials(left).equals(serials(right));
    }

    private Map<Integer, Integer> fungibleTotals(BigInteger[] words) {
        Map<Integer, Integer> totals = new HashMap<>();
        for (BigInteger word : words) {
            if (!isFungible(word)) {
                continue;
            }
            int item = itemId(word);
            int amount = count(word);
            if (item == 0 || amount <= 0) {
                continue;
            }
            totals.merge(item, amount, Integer::sum);
        }
        return totals;
    }

    private Set<Long> serials(BigInteger[] words) {
        Set<Long> serials = new HashSet<>();
        for (BigInteger word : words) {
            if (isAotv(word)) {
                serials.add(serial(word));
            }
        }
        return serials;
    }

    private static List<Integer> dirty(BigInteger[] from, BigInteger[] to) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < BAG; slot++) {
            if (!from[slot].equals(to[slot])) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static List<Batch> batches(BigInteger[] from, BigInteger[] to) {
        List<Integer> slots = dirty(from, to);
        List<Batch> batches = new ArrayList<>();
        if (slots.isEmpty()) {
            return batches;
        }
        if (slots.size() <= 8) {
            batches.add(batch(to, slots));
            return batches;
        }
        List<Integer> debits = new ArrayList<>();
        List<Integer> credits = new ArrayList<>();
        for (int slot : slots) {
            if (isDebit(from[slot], to[slot])) {
                debits.add(slot);
            } else {
                credits.add(slot);
            }
        }
        chunk(batches, to, debits);
        chunk(batches, to, credits);
        return batches;
    }

    private static boolean isDebit(BigInteger from, BigInteger to) {
        if (to.equals(ZERO)) {
            return true;
        }
        if (from.equals(ZERO)) {
            return false;
        }
        if (isFungible(from) && isFungible(to) && itemId(from) == itemId(to)) {
            return count(to) < count(from);
        }
        return true;
    }

    private static void chunk(List<Batch> batches, BigInteger[] to, List<Integer> slots) {
        for (int start = 0; start < slots.size(); start += 8) {
            batches.add(batch(to, slots.subList(start, Math.min(slots.size(), start + 8))));
        }
    }

    private static Batch batch(BigInteger[] to, List<Integer> slots) {
        int[] indexes = new int[slots.size()];
        BigInteger[] words = new BigInteger[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            indexes[i] = slots.get(i);
            words[i] = to[slots.get(i)];
        }
        return new Batch(indexes, words);
    }

    private static void applyBatch(BigInteger[] words, Batch batch) {
        for (int i = 0; i < batch.slots.length; i++) {
            words[batch.slots[i]] = batch.words[i];
        }
    }

    private long forgedSerial(TransactionReceipt receipt) {
        for (Log log : receipt.getLogs()) {
            List<String> topics = log.getTopics();
            if (topics.size() >= 3 && forgedTopic.equalsIgnoreCase(topics.get(0))) {
                return new BigInteger(topics.get(2).substring(2), 16).longValue();
            }
        }
        return -1L;
    }

    private boolean containerItem(Material type) {
        String name = type.name();
        return type == Material.CHEST
                || type == Material.TRAPPED_CHEST
                || type == Material.BARREL
                || type == Material.ENDER_CHEST
                || type == Material.HOPPER
                || type == Material.DROPPER
                || type == Material.DISPENSER
                || name.endsWith("SHULKER_BOX");
    }

    private boolean interactable(Block block) {
        if (block == null) {
            return false;
        }
        if (block.getState() instanceof Container || block.getType() == Material.ENDER_CHEST) {
            return true;
        }
        Material type = block.getType();
        return type == Material.CRAFTING_TABLE
                || type == Material.ENCHANTING_TABLE
                || type == Material.ANVIL
                || type == Material.CHIPPED_ANVIL
                || type == Material.DAMAGED_ANVIL
                || type == Material.SMITHING_TABLE
                || type == Material.GRINDSTONE
                || type == Material.STONECUTTER
                || type == Material.BREWING_STAND;
    }

    private long key(Block block) {
        int x = block.getX();
        int z = block.getZ();
        int y = block.getY();
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    private void tell(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text(text, color));
    }

    private void tellGas(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (plugin.wallets().sponsorCapReached(profile)) {
            tell(player, WalletService.sponsorCapMessage(), NamedTextColor.RED);
        } else {
            tell(player, "Not enough STT for gas, use /deposit.", NamedTextColor.RED);
        }
    }

    private String gasFailMessage(UUID id, boolean bagOnly) {
        Profile profile = plugin.profiles().get(id);
        if (plugin.wallets().sponsorCapReached(profile)) {
            return bagOnly
                    ? WalletService.sponsorCapMessage() + " Your items are safe; the bag layout will be signed on the next change."
                    : WalletService.sponsorCapMessage() + " The block was put back.";
        }
        return bagOnly
                ? "Not enough STT for gas, use /deposit. Your items are safe; the bag layout will be signed on the next change."
                : "Not enough STT for gas, use /deposit. The block was put back.";
    }

    void rememberContainer(Player player, Inventory top) {
        if (!worldContainer(player, top)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        if (top.getType() == InventoryType.ENDER_CHEST) {
            rememberSnap(seat, WorldSnap.ender(player.getUniqueId(), top.getContents()));
            return;
        }
        // Chest boats, chest minecarts and llama/donkey chests are entities. A block snapshot would write
        // the rollback into whatever chest happens to share that block, or into nothing, and leave the copy.
        InventoryHolder holder = top.getHolder();
        if (holder instanceof Entity entity && !(entity instanceof Player)) {
            rememberSnap(seat, WorldSnap.carrier(entity, top.getContents()));
            return;
        }
        Location location = top.getLocation();
        if (location == null) {
            return;
        }
        rememberSnap(seat, WorldSnap.container(location, top.getContents()));
    }

    void rememberFrame(Player player, ItemFrame frame) {
        if (frame == null || plugin.hold().offchain(player)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        rememberSnap(seat, WorldSnap.frame(frame));
    }

    void rememberStand(Player player, ArmorStand stand) {
        if (stand == null || !stand.getScoreboardTags().isEmpty() || plugin.hold().offchain(player)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        rememberSnap(seat, WorldSnap.stand(stand));
    }

    void rememberSpawned(Player player, Entity entity) {
        if (entity == null || plugin.hold().offchain(player)) {
            return;
        }
        if (entity instanceof ArmorStand stand && !stand.getScoreboardTags().isEmpty()) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        rememberSnap(seat, WorldSnap.spawned(entity));
    }

    /**
     * A leash, frame or block snapshot must hold breaks and hoppers in this tick. Waiting for the next
     * tick's commit left a window where the knot or the lectern could be broken and the rollback
     * then put a second copy in the bag.
     */
    void armSnaps(Player player) {
        if (player == null || plugin.hold().offchain(player)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded || seat.snaps.isEmpty()) {
            return;
        }
        seat.trustLocal = true;
    }

    /** The mob that just took a lead. A full rollback clears the leash without dropping another one. */
    void rememberLeash(Player player, Entity entity) {
        if (!(entity instanceof Leashable) || plugin.hold().offchain(player)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        rememberSnap(seat, WorldSnap.leash(entity));
    }

    /**
     * Lectern, jukebox, bookshelf, pot, campfire, composter, flower pot, hive, anchor, bucket and harvest.
     * The first click of a pending write remembers the block. A full rollback puts that block back, so the
     * item cannot sit in the block and in the bag.
     */
    void rememberBlock(Player player, Block block) {
        if (block == null || block.getWorld() == null || plugin.hold().offchain(player)) {
            return;
        }
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.loaded) {
            return;
        }
        rememberSnap(seat, WorldSnap.block(block));
    }

    private void rememberSnap(Seat seat, WorldSnap snap) {
        if (snap == null) {
            return;
        }
        for (WorldSnap have : seat.snaps) {
            if (have.key.equals(snap.key)) {
                return;
            }
        }
        seat.snaps.add(snap);
    }

    private void dropSnaps(Seat seat) {
        for (WorldSnap snap : seat.snaps) {
            snap.releaseWithheld();
        }
        seat.snaps.clear();
    }

    private void restoreSnaps(Seat seat, UUID playerId) {
        List<WorldSnap> snaps = new ArrayList<>(seat.snaps);
        seat.snaps.clear();
        snaps.sort(java.util.Comparator.comparingInt(s -> s.kind));
        for (WorldSnap snap : snaps) {
            try {
                snap.restore(this, playerId);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not restore world snapshot " + snap.key + ": " + exception.getMessage());
            }
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in == null ? 0 : in.length];
        for (int i = 0; i < out.length; i++) {
            ItemStack stack = in[i];
            out[i] = stack == null || stack.getType().isAir() ? null : stack.clone();
        }
        return out;
    }

    private static final class WorldSnap {
        static final int CONTAINER = 0;
        static final int ENDER = 1;
        static final int FRAME = 2;
        static final int STAND = 3;
        /** Before SPAWNED, so a rolled-back place unleashes before the entity is removed. */
        static final int LEASH = 4;
        static final int SPAWNED = 5;
        static final int BLOCK = 6;
        final int kind;
        final String key;
        final Location location;
        final UUID entity;
        final UUID owner;
        final ItemStack[] items;
        final String data;
        /** Cargo a chested animal dropped while this snapshot can still roll back. Spawned only if the write lands. */
        final List<ItemStack> withheld = new ArrayList<>();

        private WorldSnap(int kind, String key, Location location, UUID entity, UUID owner, ItemStack[] items) {
            this(kind, key, location, entity, owner, items, null);
        }

        private WorldSnap(int kind, String key, Location location, UUID entity, UUID owner, ItemStack[] items, String data) {
            this.kind = kind;
            this.key = key;
            this.location = location;
            this.entity = entity;
            this.owner = owner;
            this.items = items;
            this.data = data;
        }

        static WorldSnap container(Location location, ItemStack[] contents) {
            Location at = location.clone();
            return new WorldSnap(CONTAINER, "c:" + at.getWorld().getName() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ(),
                    at, null, null, cloneContents(contents));
        }

        /** Entity inventory (chest boat, minecart, llama, donkey, horse). Keyed by the entity, not the block under it. */
        static WorldSnap carrier(Entity entity, ItemStack[] contents) {
            return new WorldSnap(CONTAINER, "v:" + entity.getUniqueId(), entity.getLocation().clone(), entity.getUniqueId(), null,
                    cloneContents(contents));
        }

        static WorldSnap ender(UUID owner, ItemStack[] contents) {
            return new WorldSnap(ENDER, "e:" + owner, null, null, owner, cloneContents(contents));
        }

        static WorldSnap frame(ItemFrame frame) {
            ItemStack item = frame.getItem();
            return new WorldSnap(FRAME, "f:" + frame.getUniqueId(), frame.getLocation().clone(), frame.getUniqueId(), null,
                    new ItemStack[]{item == null || item.getType().isAir() ? null : item.clone()});
        }

        static WorldSnap stand(ArmorStand stand) {
            EntityEquipment eq = stand.getEquipment();
            ItemStack[] items = new ItemStack[STAND_SLOTS.length];
            if (eq != null) {
                for (int i = 0; i < STAND_SLOTS.length; i++) {
                    ItemStack stack = eq.getItem(STAND_SLOTS[i]);
                    items[i] = stack == null || stack.getType().isAir() ? null : stack.clone();
                }
            }
            return new WorldSnap(STAND, "s:" + stand.getUniqueId(), stand.getLocation().clone(), stand.getUniqueId(), null, items);
        }

        static WorldSnap spawned(Entity entity) {
            return new WorldSnap(SPAWNED, "n:" + entity.getUniqueId(), entity.getLocation().clone(), entity.getUniqueId(), null, null);
        }

        static WorldSnap leash(Entity entity) {
            return new WorldSnap(LEASH, "l:" + entity.getUniqueId(), entity.getLocation().clone(), entity.getUniqueId(), null, null);
        }

        /**
         * Block data, plus the live inventory when the block holds items (lectern, jukebox, bookshelf, pot).
         * Campfire items live on the snapshot and are applied with {@code update}. Placed inventories
         * ({@code getInventory} when the state is placed) write through, so they are not updated again.
         */
        static WorldSnap block(Block block) {
            Location at = block.getLocation().clone();
            String data = block.getBlockData().getAsString();
            org.bukkit.block.BlockState state = block.getState();
            ItemStack[] items = null;
            if (state instanceof org.bukkit.inventory.BlockInventoryHolder holder) {
                items = cloneContents(holder.getInventory().getContents());
            } else if (state instanceof org.bukkit.block.Campfire camp) {
                items = new ItemStack[camp.getSize()];
                for (int i = 0; i < items.length; i++) {
                    ItemStack stack = camp.getItem(i);
                    items[i] = stack == null || stack.getType().isAir() ? null : stack.clone();
                }
            }
            return new WorldSnap(BLOCK, "b:" + at.getWorld().getName() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ(),
                    at, null, null, items, data);
        }

        /** Entity ItemSync snapshotted because placing it took an item out of the bag. Tagged stands are minions and NPC labels. */
        private static boolean placedFromBag(Entity found) {
            if (found instanceof ArmorStand stand && !stand.getScoreboardTags().isEmpty()) {
                return false;
            }
            return found instanceof ItemFrame
                    || found instanceof ArmorStand
                    || found instanceof org.bukkit.entity.Vehicle
                    || found instanceof org.bukkit.entity.EnderCrystal
                    || found instanceof org.bukkit.entity.Hanging;
        }

        /** Cargo held back from a chest unequip. A landed write drops it once; a rollback puts it in the chest instead. */
        void releaseWithheld() {
            if (withheld.isEmpty()) {
                return;
            }
            Location at = location;
            if (entity != null) {
                Entity found = Bukkit.getEntity(entity);
                if (found != null) {
                    at = found.getLocation();
                }
            }
            if (at != null && at.getWorld() != null) {
                for (ItemStack stack : withheld) {
                    if (stack != null && !stack.getType().isAir()) {
                        at.getWorld().dropItemNaturally(at, stack.clone());
                    }
                }
            }
            withheld.clear();
        }

        void restore(WordChain words, UUID playerId) {
            if (kind == CONTAINER && entity != null) {
                Entity found = Bukkit.getEntity(entity);
                if (found == null) {
                    releaseWithheld();
                    return;
                }
                if (found instanceof InventoryHolder holder && items != null) {
                    // Unequipping the chest shrinks the inventory, which used to skip the restore and leave the drops.
                    if (found instanceof org.bukkit.entity.ChestedHorse horse
                            && !horse.isCarryingChest()
                            && items.length > horse.getInventory().getSize()) {
                        horse.setCarryingChest(true);
                    }
                    Inventory inv = holder.getInventory();
                    if (inv.getSize() == items.length) {
                        inv.setContents(cloneContents(items));
                        withheld.clear();
                    } else {
                        releaseWithheld();
                    }
                } else {
                    releaseWithheld();
                }
                return;
            }
            if (kind == CONTAINER && location != null && location.getWorld() != null) {
                if (!(location.getBlock().getState() instanceof Container container)) {
                    return;
                }
                Inventory inv = container.getInventory();
                if (items != null && inv.getSize() == items.length) {
                    inv.setContents(cloneContents(items));
                }
                return;
            }
            if (kind == ENDER && items != null) {
                UUID who = owner != null ? owner : playerId;
                Player online = who == null ? null : words.plugin.getServer().getPlayer(who);
                if (online != null && online.isOnline()) {
                    if (online.getEnderChest().getSize() == items.length) {
                        online.getEnderChest().setContents(cloneContents(items));
                    }
                } else if (who != null) {
                    words.pendingEnder.put(who, cloneContents(items));
                }
                return;
            }
            if (kind == FRAME && entity != null) {
                Entity found = Bukkit.getEntity(entity);
                if (found instanceof ItemFrame frame) {
                    frame.setItem(items != null && items.length > 0 ? items[0] : null, false);
                }
                return;
            }
            if (kind == STAND && entity != null) {
                Entity found = Bukkit.getEntity(entity);
                if (found instanceof ArmorStand stand && stand.getEquipment() != null && items != null) {
                    for (int i = 0; i < STAND_SLOTS.length && i < items.length; i++) {
                        stand.getEquipment().setItem(STAND_SLOTS[i], items[i] == null ? null : items[i].clone());
                    }
                }
                return;
            }
            if (kind == BLOCK && location != null && location.getWorld() != null) {
                Block block = location.getBlock();
                if (data != null) {
                    try {
                        block.setBlockData(Bukkit.createBlockData(data), false);
                    } catch (IllegalArgumentException ignored) {
                        // the saved string is no longer a block this server knows
                    }
                }
                if (items == null) {
                    return;
                }
                org.bukkit.block.BlockState state = block.getState();
                if (state instanceof org.bukkit.inventory.BlockInventoryHolder holder && holder.getInventory().getSize() == items.length) {
                    // Placed lectern / jukebox / bookshelf / pot: getInventory() is the live block entity.
                    holder.getInventory().setContents(cloneContents(items));
                    return;
                }
                if (state instanceof org.bukkit.block.Campfire camp) {
                    for (int i = 0; i < camp.getSize() && i < items.length; i++) {
                        camp.setItem(i, items[i] == null ? null : items[i].clone());
                    }
                    camp.update(true, false);
                }
                return;
            }
            if (kind == LEASH && entity != null) {
                Entity found = Bukkit.getEntity(entity);
                if (found instanceof Leashable leashable && leashable.isLeashed()) {
                    Entity holder = leashable.getLeashHolder();
                    words.suppressLeadDrop = true;
                    try {
                        leashable.setLeashHolder(null);
                        if (holder instanceof LeashHitch hitch && hitch.isValid()) {
                            hitch.remove();
                        }
                    } finally {
                        words.suppressLeadDrop = false;
                    }
                }
                return;
            }
            if (kind == SPAWNED && entity != null) {
                Entity found = Bukkit.getEntity(entity);
                // Placed from the bag: a failed write put the item back, so the entity must go too.
                // Frames and untagged stands were already covered. Boats, minecarts, crystals and paintings
                // were snapshotted by ItemSync and then left in the world (the item and the entity).
                if (found != null && placedFromBag(found)) {
                    if (found instanceof org.bukkit.inventory.InventoryHolder holder) {
                        holder.getInventory().clear();
                    }
                    found.remove();
                }
            }
        }
    }

    /** The purse cannot pay the gas (WalletService precheck or the node's insufficient-funds error). */
    static boolean noGas(Exception exception) {
        String m = exception == null || exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
        return m.contains("purse has") || m.contains("insufficient funds") || m.contains("insufficient balance");
    }

    private static String blame(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "Shannon rejected that signature.";
        }
        return trim(exception.getMessage());
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) {
            return "Shannon rejected that signature.";
        }
        String clean = message.replaceAll("0x[0-9a-fA-F]{64}", "0x…");
        return clean.length() > 140 ? clean.substring(0, 140) : clean;
    }

    private static BigInteger[] copy(BigInteger[] words) {
        return Arrays.copyOf(words, words.length);
    }

    private static final class Seat {
        final BigInteger[] words = new BigInteger[BAG];
        BigInteger hidden8 = ZERO;
        BigInteger combat = ZERO;
        boolean loaded;
        boolean bagBusy;
        boolean clearing;
        boolean castDrain;
        boolean again;
        int castTick = -1;
        boolean cursorNoted;
        Location snapBack;
        int task = -1;
        final Set<Long> held = new HashSet<>();
        int loginTries;
        BigInteger[] trusted;
        boolean trustLocal;
        int trustFails;
        /** World inventories/entities snapshotted at the first interaction of the pending trusted write. */
        final List<WorldSnap> snaps = new ArrayList<>();
        /** Callbacks waiting for the next trusted write, and the ones riding the write in flight. */
        final List<java.util.function.Consumer<Boolean>> hooks = new ArrayList<>();
        List<java.util.function.Consumer<Boolean>> flying;
        final List<PendingBreak> breaks = new ArrayList<>();
        final List<PendingPlace> places = new ArrayList<>();
        final List<PendingDrop> drops = new ArrayList<>();
        final List<Hop> hops = new ArrayList<>();
        final ConcurrentLinkedQueue<Hop> castQueue = new ConcurrentLinkedQueue<>();

        Seat() {
            Arrays.fill(words, ZERO);
        }
    }

    private static final class PendingBreak {
        final Location location;
        final int[] items;
        final int[] counts;
        final boolean islandResource;
        final boolean refill;
        final long key;
        final boolean forgetPlaced;
        final String rollback;
        boolean placeholder;

        PendingBreak(Location location, int[] items, int[] counts, boolean islandResource, boolean refill, long key, boolean forgetPlaced, String rollback) {
            this.location = location;
            this.items = items;
            this.counts = counts;
            this.islandResource = islandResource;
            this.refill = refill;
            this.key = key;
            this.forgetPlaced = forgetPlaced;
            this.rollback = rollback;
        }
    }

    private static final class PendingPlace {
        final Location location;
        final String data;
        final int slot;
        final int item;
        final long key;
        final boolean mark;
        final String replaced;

        PendingPlace(Location location, String data, int slot, int item, long key, boolean mark, String replaced) {
            this.location = location;
            this.data = data;
            this.slot = slot;
            this.item = item;
            this.key = key;
            this.mark = mark;
            this.replaced = replaced;
        }
    }

    private static final class PendingDrop {
        final int slot;
        final int amount;

        PendingDrop(int slot, int amount) {
            this.slot = slot;
            this.amount = amount;
        }
    }

    private static final class Hop {
        final Location from;
        final Location to;
        final int slot;
        boolean done;
        boolean ok;
        Exception error;

        Hop(Location from, Location to, int slot) {
            this.from = from;
            this.to = to;
            this.slot = slot;
        }
    }

    private static final class Batch {
        final int[] slots;
        final BigInteger[] words;

        Batch(int[] slots, BigInteger[] words) {
            this.slots = slots;
            this.words = words;
        }
    }
}
