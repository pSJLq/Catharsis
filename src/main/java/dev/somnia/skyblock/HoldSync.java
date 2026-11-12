package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class HoldSync implements Listener {
    private static final UUID OWNER = UUID.fromString("7bb994c9-c759-3d23-8352-90bc1ad2649e");

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, Cell[]> bagAck = new ConcurrentHashMap<>();
    private final Map<Long, Cell[]> chestAck = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> tasks = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> retry = ConcurrentHashMap.newKeySet();
    private final Set<UUID> applying = ConcurrentHashMap.newKeySet();
    private final Set<UUID> paused = ConcurrentHashMap.newKeySet();
    private final Set<UUID> revertOnce = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<ItemStack>> doomed = new ConcurrentHashMap<>();
    private final Map<UUID, List<ChestSnap>> forcedChests = new ConcurrentHashMap<>();
    private final Map<UUID, List<ChestSnap>> waitingChests = new ConcurrentHashMap<>();
    private final CreativeBags creativeBags;
    private final Set<UUID> sandbox = ConcurrentHashMap.newKeySet();
    private final Set<UUID> restoring = ConcurrentHashMap.newKeySet();
    private boolean suppressMode;

    HoldSync(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.creativeBags = new CreativeBags(plugin);
        if (plugin.wallets().holdEnabled()) {
            plugin.getLogger().info("Item contract " + plugin.wallets().holdContract());
            String gear = plugin.getConfig().getString("chain.gear-contract", "");
            if (gear != null && !gear.isBlank()) {
                plugin.getLogger().info("Gear contract " + gear.trim());
            }
        } else {
            plugin.getLogger().warning("No item contract. Inventory stays in the Minecraft world.");
        }
    }

    void pause(Player player) {
        paused.add(player.getUniqueId());
    }

    void resume(Player player) {
        paused.remove(player.getUniqueId());
        if (player.isOnline()) {
            touch(player, true);
        }
    }

    void resumeOffline(UUID id) {
        paused.remove(id);
    }

    boolean offchain(Player player) {
        if (player == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        return sandbox.contains(id) || player.getGameMode() == GameMode.CREATIVE;
    }

    boolean canTrade(Player player) {
        if (plugin.wordMode()) {
            return false;
        }
        UUID id = player.getUniqueId();
        return plugin.wallets().holdEnabled() && !offchain(player) && !busy.contains(id) && !paused.contains(id);
    }

    void releaseChain(Player player) {
        UUID id = player.getUniqueId();
        restoring.remove(id);
        sandbox.remove(id);
        creativeBags.mode(id, false);
    }

    void keepCreative(Player player) {
        stayCreative(player);
    }

    void showChain(Player player, HoldItems.View view) {
        Cell[] cells = cells(view, HoldItems.BAG);
        bagAck.put(player.getUniqueId(), cells);
        applyBag(player, cells);
    }

    void touch(Player player, boolean fast) {
        if (plugin.wordMode()) {
            return;
        }
        if (!plugin.wallets().holdEnabled() || player == null || paused.contains(player.getUniqueId()) || offchain(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (applying.contains(id)) {
            retry.add(id);
            return;
        }
        Integer existing = tasks.remove(id);
        if (existing != null) {
            plugin.getServer().getScheduler().cancelTask(existing);
        }
        int task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            tasks.remove(id);
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) {
                flush(online);
            }
        }, fast ? 1L : 10L).getTaskId();
        tasks.put(id, task);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(SbLoginEvent event) {
        Player player = event.getPlayer();
        if (plugin.wordMode()) {
            UUID id = player.getUniqueId();
            if (creativeBags.isCreative(id) || player.getGameMode() == GameMode.CREATIVE) {
                if (occupied(snapRaw(player))) {
                    creativeBags.save(player);
                }
                beginRestore(player);
                return;
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.words().login(player, false), 10L);
            return;
        }
        if (!plugin.wallets().holdEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (restoring.contains(id)) {
            return;
        }
        if (creativeBags.isCreative(id) || player.getGameMode() == GameMode.CREATIVE) {
            if (occupied(snapRaw(player))) {
                creativeBags.save(player);
            }
            beginRestore(player);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> login(player), 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (plugin.auth().key(plugin.profiles().get(id)) == null) {
            return; // never logged in this time: nothing to save, and no key to sign with
        }
        if (restoring.contains(id)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || (sandbox.contains(id) && player.getGameMode() != GameMode.SURVIVAL)) {
            creativeBags.save(player);
            return;
        }
        if (sandbox.contains(id)) {
            return;
        }
        creativeBags.mode(id, false);
        touch(player, true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onNumericMode(PlayerCommandPreprocessEvent event) {
        if (!plugin.auth().isDev(event.getPlayer())) {
            return;
        }
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("/gamemode")) {
            return;
        }
        GameMode mode = switch (parts[1]) {
            case "0" -> GameMode.SURVIVAL;
            case "1" -> GameMode.CREATIVE;
            default -> null;
        };
        if (mode == null) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().setGameMode(mode);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent event) {
        if (suppressMode || !event.getPlayer().isOnline()) {
            return;
        }
        Player player = event.getPlayer();
        boolean nextCreative = event.getNewGameMode() == GameMode.CREATIVE;
        boolean nowCreative = player.getGameMode() == GameMode.CREATIVE;
        if (nextCreative == nowCreative) {
            return;
        }
        if (nextCreative && !parkCursor(player)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Free a slot before creative. The cursor item stays with the chain inventory.", NamedTextColor.RED));
            return;
        }
        if (nextCreative) {
            enterCreative(player);
        } else {
            enterSurvival(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !(event.getView().getTopInventory().getHolder() instanceof Menu)) {
            touch(player, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            touch(player, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && !(event.getView().getTopInventory().getHolder() instanceof Menu)) {
            touch(player, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);
        if (sandbox.contains(player.getUniqueId()) || paused.contains(player.getUniqueId())) {
            return;
        }
        doomed.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).add(event.getItemDrop().getItemStack().clone());
        player.sendMessage(Component.text("Destroying that item when Shannon signs it. It will not drop.", NamedTextColor.GRAY));
        touch(player, true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            ItemStack stack = event.getItem().getItemStack();
            if (HoldItems.makeUnbreakable(stack)) {
                event.getItem().setItemStack(stack);
            }
            return;
        }
        // SkyWord bag: picking up is allowed once the signed bag is loaded (fish, wool, composter or sapling drops);
        // ItemSync signs the bag right after. Before it loads (or in the sandbox / while paused) items stay on the ground.
        if (plugin.wordMode() && plugin.words().ready(player) && !sandbox.contains(player.getUniqueId()) && !paused.contains(player.getUniqueId())) {
            ItemStack stack = event.getItem().getItemStack();
            if (HoldItems.makeUnbreakable(stack)) {
                event.getItem().setItemStack(stack);
            }
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.wordMode() && player.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (sandbox.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (paused.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        touch(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.wordMode() && player.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        if (sandbox.contains(player.getUniqueId()) && player.getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (paused.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getBlock().getDrops(player.getInventory().getItemInMainHand(), player));
        BlockState state = event.getBlock().getState();
        Inventory half = null;
        ChestSnap forced = null;
        if (state instanceof Chest chest) {
            half = chest.getBlockInventory();
            Inventory full = chest.getInventory();
            if (full.getLocation() != null) {
                forced = chestAfterRemoval(full, half, drops);
            } else {
                for (ItemStack stack : half.getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        drops.add(stack.clone());
                    }
                }
            }
        }
        coalesce(player);
        for (ItemStack drop : drops) {
            HoldItems.makeUnbreakable(drop);
        }
        if (!fits(player, drops)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Your inventory is full.", NamedTextColor.RED));
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (half != null) {
            half.clear();
        }
        for (ItemStack drop : drops) {
            player.getInventory().addItem(drop);
        }
        if (forced != null) {
            forcedChests.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).add(forced);
        }
        touch(player, false);
    }

    private ChestSnap chestAfterRemoval(Inventory full, Inventory half, List<ItemStack> drops) {
        ItemStack[] contents = full.getContents();
        Cell[] cells = empty(HoldItems.CHEST);
        for (int i = 0; i < contents.length && i < cells.length; i++) {
            cells[i] = cell(contents[i]);
        }
        for (ItemStack stack : half.getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            drops.add(stack.clone());
            boolean found = false;
            for (int i = 0; i < contents.length && i < cells.length; i++) {
                if (contents[i] == stack) {
                    cells[i] = Cell.EMPTY;
                    found = true;
                    break;
                }
            }
            if (!found) {
                subtract(cells, stack);
            }
        }
        return new ChestSnap(chestId(full.getLocation()), full.getLocation().clone(), cells, true);
    }

    private void enterCreative(Player player) {
        UUID id = player.getUniqueId();
        if (!sandbox.contains(id)) {
            parkCursor(player);
            flush(player);
        }
        sandbox.add(id);
        restoring.remove(id);
        Integer existing = tasks.remove(id);
        if (existing != null) {
            plugin.getServer().getScheduler().cancelTask(existing);
        }
        retry.remove(id);
        doomed.remove(id);
        player.closeInventory();
        creativeBags.load(player);
        creativeBags.mode(id, true);
        player.sendMessage(Component.text("Creative. This inventory stays off Shannon. /gamemode 0 restores the chain inventory.", NamedTextColor.GOLD));
    }

    private void enterSurvival(Player player) {
        if (restoring.contains(player.getUniqueId())) {
            return;
        }
        creativeBags.save(player);
        beginRestore(player);
    }

    private void beginRestore(Player player) {
        if (plugin.wordMode()) {
            creativeBags.save(player);
            player.closeInventory();
            if (player.getGameMode() != GameMode.SURVIVAL) {
                suppressMode = true;
                try {
                    player.setGameMode(GameMode.SURVIVAL);
                } finally {
                    suppressMode = false;
                }
            }
            plugin.words().login(player, true);
            return;
        }
        UUID id = player.getUniqueId();
        if (!restoring.add(id)) {
            return;
        }
        sandbox.add(id);
        wipe(player);
        player.closeInventory();
        if (player.getGameMode() != GameMode.SURVIVAL) {
            suppressMode = true;
            try {
                player.setGameMode(GameMode.SURVIVAL);
            } finally {
                suppressMode = false;
            }
        }
        login(player);
    }

    void saveSandbox() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (restoring.contains(id)) {
                continue;
            }
            if (player.getGameMode() == GameMode.CREATIVE) {
                creativeBags.save(player);
            }
        }
    }

    private boolean parkCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return true;
        }
        Inventory temp = Bukkit.createInventory(null, 36);
        temp.setContents(player.getInventory().getStorageContents());
        if (!temp.addItem(cursor.clone()).isEmpty()) {
            return false;
        }
        player.getInventory().addItem(cursor.clone());
        player.setItemOnCursor(null);
        return true;
    }

    private void wipe(Player player) {
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    private boolean clientFollowsChain(Player player) {
        return player != null
                && player.isOnline()
                && player.getGameMode() != GameMode.CREATIVE
                && !sandbox.contains(player.getUniqueId());
    }

    private void stayCreative(Player player) {
        UUID id = player.getUniqueId();
        restoring.remove(id);
        sandbox.add(id);
        creativeBags.load(player);
        creativeBags.mode(id, true);
        if (player.getGameMode() != GameMode.CREATIVE) {
            suppressMode = true;
            try {
                player.setGameMode(GameMode.CREATIVE);
            } finally {
                suppressMode = false;
            }
        }
        player.sendMessage(Component.text("Could not read Shannon. Staying in creative.", NamedTextColor.RED));
    }

    private void login(Player player) {
        if (plugin.wordMode()) {
            plugin.words().login(player, restoring.contains(player.getUniqueId()));
            return;
        }
        if (!player.isOnline() || !plugin.wallets().holdEnabled()) {
            restoring.remove(player.getUniqueId());
            sandbox.remove(player.getUniqueId());
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        List<ChestSnap> chests = scanChests(profile);
        UUID id = player.getUniqueId();
        boolean restore = restoring.contains(id);
        Cell[] beforeBag = restore ? empty(HoldItems.BAG) : snapBag(player);
        if (!busy.add(id)) {
            if (restore) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> login(player), 10L);
            } else {
                retry.add(id);
            }
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HoldItems.View chainBag = plugin.wallets().bagOf(profile.wallet);
                List<HoldItems.View> chainChests = new ArrayList<>();
                for (ChestSnap chest : chests) {
                    chainChests.add(plugin.wallets().chestOf(chest.id));
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (!player.isOnline() || player.getGameMode() == GameMode.CREATIVE) {
                        restoring.remove(id);
                        return;
                    }
                    if (restore) {
                        Cell[] cells = cells(chainBag, HoldItems.BAG);
                        bagAck.put(id, cells);
                        profile.holdReady = true;
                        plugin.profiles().save(profile);
                        for (int i = 0; i < chests.size(); i++) {
                            ChestSnap chest = chests.get(i);
                            HoldItems.View view = chainChests.get(i);
                            if (view.any()) {
                                Cell[] stored = cells(view, HoldItems.CHEST);
                                chestAck.put(chest.id, stored);
                                applyChest(chest.location, stored);
                            }
                        }
                        restoring.remove(id);
                        sandbox.remove(id);
                        creativeBags.mode(id, false);
                        retry.remove(id);
                        applyBag(player, cells);
                        if (worn(cells)) {
                            retry.add(id);
                        }
                        player.sendMessage(Component.text("Survival. This inventory is the chain again.", NamedTextColor.GREEN));
                        return;
                    }
                    if (chainBag.any()) {
                        Cell[] cells = cells(chainBag, HoldItems.BAG);
                        Cell[] merged = merge(cells, beforeBag, snapBag(player));
                        bagAck.put(id, cells);
                        applyBag(player, merged);
                        profile.holdReady = true;
                        plugin.profiles().save(profile);
                        if (!same(merged, cells) || worn(cells)) {
                            retry.add(id);
                        }
                    }
                    List<ChestSnap> pending = new ArrayList<>();
                    for (int i = 0; i < chests.size(); i++) {
                        ChestSnap chest = chests.get(i);
                        HoldItems.View view = chainChests.get(i);
                        if (view.any()) {
                            Cell[] stored = cells(view, HoldItems.CHEST);
                            chestAck.put(chest.id, stored);
                            applyChest(chest.location, stored);
                        } else if (occupied(chest.cells)) {
                            pending.add(fresh(chest));
                        } else {
                            chestAck.put(chest.id, empty(HoldItems.CHEST));
                        }
                    }
                    if (!chainBag.any() && occupied(beforeBag)) {
                        profile.holdReady = false;
                    }
                    if (!profile.holdReady || !pending.isEmpty()) {
                        submit(player, profile, !profile.holdReady, pending);
                    } else if (retry.remove(id)) {
                        flush(player);
                    }
                });
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    plugin.getLogger().warning("Could not read the item contract: " + exception.getMessage());
                    if (!player.isOnline()) {
                        restoring.remove(id);
                        return;
                    }
                    if (restoring.contains(id) && player.getGameMode() != GameMode.CREATIVE) {
                        stayCreative(player);
                        return;
                    }
                    restoring.remove(id);
                    player.sendMessage(Component.text("Could not read your items from Shannon.", NamedTextColor.RED));
                });
            }
        });
    }

    private void flush(Player player) {
        if (plugin.wordMode()) {
            return;
        }
        if (!plugin.wallets().holdEnabled() || player == null || offchain(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (applying.contains(id)) {
            retry.add(id);
            return;
        }
        if (busy.contains(id)) {
            retry.add(id);
            return;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            touch(player, true);
            return;
        }
        coalesce(player);
        Profile profile = plugin.profiles().get(id);
        Cell[] bag = snapBag(player);
        int[] bagSlots = diff(bag, bagAck.get(id));
        List<ChestSnap> forced = forcedChests.remove(id);
        ChestSnap chest;
        int[] chestSlots;
        if (forced != null && !forced.isEmpty()) {
            chest = forced.remove(0);
            chestSlots = allSlots(chest.cells.length);
            if (!forced.isEmpty()) {
                waitingChests.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(0, forced);
            }
        } else {
            chest = openChest(player);
            chestSlots = chest == null ? new int[0] : diff(chest.cells, chestAck.get(chest.id));
        }
        if (bagSlots.length == 0 && chestSlots.length == 0) {
            if (doomed.containsKey(id)) {
                applyBag(player, bag);
                doomed.remove(id);
            }
            if (!profile.holdReady) {
                profile.holdReady = true;
                plugin.profiles().save(profile);
            }
            return;
        }
        submitDiff(player, profile, bag, bagSlots, chest, chestSlots, null);
    }

    void move(int from, int to, CompletableFuture<String> done) {
        if (!plugin.wallets().holdEnabled()) {
            fail(done, "Inventory sync is off.");
            return;
        }
        if (from == to) {
            finish(done, "");
            return;
        }
        if (from < 0 || to < 0 || from >= HoldItems.BAG || to >= HoldItems.BAG) {
            fail(done, "That slot is not in the inventory.");
            return;
        }
        Player player = null;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            player = online;
            break;
        }
        if (player == null || offchain(player)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> moveOffline(from, to, done));
            return;
        }
        UUID id = player.getUniqueId();
        if (applying.contains(id) || busy.contains(id)) {
            fail(done, "Sync in progress");
            return;
        }
        if (bagAck.get(id) == null) {
            fail(done, "Inventory is still loading.");
            return;
        }
        Cell[] next = relocate(snapBag(player), from, to);
        if (next == null) {
            fail(done, "That slot is empty.");
            return;
        }
        int[] slots = diff(next, bagAck.get(id));
        if (slots.length == 0) {
            finish(done, "");
            return;
        }
        submitDiff(player, plugin.profiles().get(id), next, slots, null, new int[0], done);
    }

    private void moveOffline(int from, int to, CompletableFuture<String> done) {
        try {
            Profile profile = onlyProfile();
            if (profile == null || profile.wallet == null || profile.wallet.isBlank()) {
                fail(done, "Join the world first");
                return;
            }
            Cell[] now = cells(plugin.wallets().bagOf(profile.wallet), HoldItems.BAG);
            Cell[] next = relocate(now, from, to);
            if (next == null) {
                fail(done, "That slot is empty.");
                return;
            }
            int[] slots = diff(next, now);
            if (slots.length == 0) {
                finish(done, "");
                return;
            }
            String hash = plugin.wallets().syncHold(
                    profile,
                    slots,
                    pick(next, slots, 0),
                    pick(next, slots, 1),
                    pick(next, slots, 2),
                    pickGear(next, slots),
                    pickText(next, slots, 0),
                    pickText(next, slots, 1),
                    pickText(next, slots, 2),
                    pickText(next, slots, 3),
                    0L,
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    new long[0],
                    new String[0],
                    new String[0],
                    new String[0],
                    new String[0]
            );
            finish(done, hash);
        } catch (Exception exception) {
            fail(done, exception.getMessage() == null ? "Move failed." : exception.getMessage());
        }
    }

    private Profile onlyProfile() {
        File directory = new File(plugin.getDataFolder(), "players");
        File[] files = directory.listFiles((ignored, name) -> name.endsWith(".yml"));
        if (files == null) {
            return null;
        }
        Profile found = null;
        for (File file : files) {
            String name = file.getName();
            UUID id;
            try {
                id = UUID.fromString(name.substring(0, name.length() - 4));
            } catch (IllegalArgumentException exception) {
                continue;
            }
            Profile profile = plugin.profiles().get(id);
            if (profile.wallet == null || profile.wallet.isBlank()) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = profile;
        }
        return found;
    }

    private void submit(Player player, Profile profile, boolean includeBag, List<ChestSnap> chests) {
        Cell[] bag = snapBag(player);
        int[] bagSlots = includeBag ? diff(bag, bagAck.get(player.getUniqueId())) : new int[0];
        ChestSnap chosen = null;
        int[] chestSlots = new int[0];
        List<ChestSnap> rest = new ArrayList<>();
        for (ChestSnap chest : chests) {
            ChestSnap live = fresh(chest);
            int[] slots = live.overwrite ? allSlots(live.cells.length) : diff(live.cells, chestAck.get(live.id));
            if (slots.length == 0) {
                chestAck.put(live.id, live.cells);
                continue;
            }
            if (chosen == null) {
                chosen = live;
                chestSlots = slots;
            } else {
                rest.add(live);
            }
        }
        if (bagSlots.length == 0 && chosen == null) {
            profile.holdReady = true;
            plugin.profiles().save(profile);
            if (retry.remove(player.getUniqueId())) {
                flush(player);
            }
            return;
        }
        waitingChests.put(player.getUniqueId(), rest);
        submitDiff(player, profile, bag, bagSlots, chosen, chestSlots, null);
    }

    private void submitDiff(Player player, Profile profile, Cell[] bag, int[] bagSlots, ChestSnap chest, int[] chestSlots, CompletableFuture<String> done) {
        UUID id = player.getUniqueId();
        if (!busy.add(id)) {
            retry.add(id);
            if (chest != null && chest.overwrite) {
                forcedChests.computeIfAbsent(id, ignored -> new ArrayList<>()).add(0, chest);
            }
            fail(done, "Sync in progress");
            return;
        }
        Cell[] liveAtSend = snapRaw(player);
        List<ItemStack> doomedNow = doomed.get(id);
        int doomedCount = doomedNow == null ? 0 : doomedNow.size();
        int[] bagItems = pick(bag, bagSlots, 0);
        int[] bagCounts = pick(bag, bagSlots, 1);
        int[] bagDamages = pick(bag, bagSlots, 2);
        long[] bagGear = pickGear(bag, bagSlots);
        String[] bagKeys = pickText(bag, bagSlots, 0);
        String[] bagLabels = pickText(bag, bagSlots, 1);
        String[] bagReforges = pickText(bag, bagSlots, 2);
        String[] bagEnchants = pickText(bag, bagSlots, 3);
        long chestId = chest == null || chestSlots.length == 0 ? 0L : chest.id;
        int[] chestItems = chest == null ? new int[0] : pick(chest.cells, chestSlots, 0);
        int[] chestCounts = chest == null ? new int[0] : pick(chest.cells, chestSlots, 1);
        int[] chestDamages = chest == null ? new int[0] : pick(chest.cells, chestSlots, 2);
        long[] chestGear = chest == null ? new long[0] : pickGear(chest.cells, chestSlots);
        String[] chestKeys = chest == null ? new String[0] : pickText(chest.cells, chestSlots, 0);
        String[] chestLabels = chest == null ? new String[0] : pickText(chest.cells, chestSlots, 1);
        String[] chestReforges = chest == null ? new String[0] : pickText(chest.cells, chestSlots, 2);
        String[] chestEnchants = chest == null ? new String[0] : pickText(chest.cells, chestSlots, 3);
        int[] sentChestSlots = chestId == 0 ? new int[0] : chestSlots;
        if (chestId == 0) {
            chestGear = new long[0];
            chestKeys = new String[0];
            chestLabels = new String[0];
            chestReforges = new String[0];
            chestEnchants = new String[0];
        }
        long[] sentChestGear = chestGear;
        String[] sentChestKeys = chestKeys;
        String[] sentChestLabels = chestLabels;
        String[] sentChestReforges = chestReforges;
        String[] sentChestEnchants = chestEnchants;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String hash = plugin.wallets().syncHold(
                        profile,
                        bagSlots,
                        bagItems,
                        bagCounts,
                        bagDamages,
                        bagGear,
                        bagKeys,
                        bagLabels,
                        bagReforges,
                        bagEnchants,
                        chestId,
                        sentChestSlots,
                        chestItems,
                        chestCounts,
                        chestDamages,
                        sentChestGear,
                        sentChestKeys,
                        sentChestLabels,
                        sentChestReforges,
                        sentChestEnchants
                );
                HoldItems.View chainBag = plugin.wallets().bagOf(profile.wallet);
                HoldItems.View chainChest = chestId == 0 ? null : plugin.wallets().chestOf(chestId);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    revertOnce.remove(id);
                    Cell[] chainCells = cells(chainBag, HoldItems.BAG);
                    bagAck.put(id, acknowledge(bag, chainCells));
                    List<ItemStack> burned = new ArrayList<>();
                    List<ItemStack> pending = doomed.get(id);
                    if (pending != null) {
                        for (int i = 0; i < doomedCount && !pending.isEmpty(); i++) {
                            burned.add(pending.remove(0));
                        }
                        if (pending.isEmpty()) {
                            doomed.remove(id);
                        }
                    }
                    if (clientFollowsChain(player)) {
                        Cell[] shown = merge(chainCells, liveAtSend, snapRaw(player));
                        trimBurned(shown, chainCells, burned);
                        applyBag(player, shown);
                        if (!same(shown, chainCells)) {
                            retry.add(id);
                        }
                        player.sendMessage(Component.text("Inventory signed. " + hash, NamedTextColor.AQUA));
                    }
                    if (chainChest != null && chest != null) {
                        Cell[] stored = cells(chainChest, HoldItems.CHEST);
                        chestAck.put(chestId, acknowledge(chest.cells, stored));
                        if (!chest.overwrite) {
                            Cell[] mergedChest = merge(stored, chest.cells, fresh(chest).cells);
                            applyChest(chest.location, mergedChest);
                        }
                    }
                    profile.holdReady = true;
                    plugin.profiles().save(profile);
                    plugin.getLogger().info("Inventory " + hash);
                    finish(done, hash);
                    List<ChestSnap> more = waitingChests.remove(id);
                    if (more != null && !more.isEmpty() && clientFollowsChain(player)) {
                        submit(player, profile, false, more);
                    } else if (retry.remove(id) && clientFollowsChain(player)) {
                        flush(player);
                    }
                });
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    plugin.getLogger().warning("Inventory was not accepted: " + exception.getMessage());
                    String message = exception.getMessage();
                    fail(done, message == null ? "Inventory sync failed." : message);
                    boolean reverted = message != null && message.contains("reverted");
                    if (clientFollowsChain(player) && reverted) {
                        Cell[] local = snapRaw(player);
                        Cell[] signed = bagAck.get(id);
                        Map<Long, ItemStack> kept = indexGear(bagStacks(player));
                        if (signed != null) {
                            applyBag(player, signed);
                        }
                        doomed.remove(id);
                        if (revertOnce.add(id)) {
                            if (signed != null) {
                                giveExcess(player, local, signed, kept);
                            }
                            if (chest != null && chest.overwrite) {
                                forcedChests.computeIfAbsent(id, ignored -> new ArrayList<>()).add(0, chest);
                            }
                            touch(player, true);
                        } else {
                            revertOnce.remove(id);
                        }
                    }
                    if (clientFollowsChain(player)) {
                        player.sendMessage(Component.text(
                                message == null ? "Inventory sync failed." : message,
                                NamedTextColor.RED
                        ));
                    }
                    if (retry.remove(id) && clientFollowsChain(player)) {
                        touch(player, true);
                    }
                });
            }
        });
    }

    private List<ChestSnap> scanChests(Profile profile) {
        World world = plugin.islands().world();
        if (world == null || !profile.islandReady) {
            return List.of();
        }
        Set<Inventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ChestSnap> found = new ArrayList<>();
        int y0 = IslandManager.GROUND_Y - 5;
        int y1 = IslandManager.GROUND_Y + 20;
        for (int x = profile.islandX - IslandManager.RADIUS; x <= profile.islandX + IslandManager.RADIUS; x++) {
            for (int z = profile.islandZ - IslandManager.RADIUS; z <= profile.islandZ + IslandManager.RADIUS; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockState state = world.getBlockAt(x, y, z).getState();
                    if (!(state instanceof Chest chest)) {
                        continue;
                    }
                    Inventory inventory = chest.getInventory();
                    if (!seen.add(inventory) || inventory.getLocation() == null) {
                        continue;
                    }
                    found.add(snap(inventory));
                }
            }
        }
        return found;
    }

    private ChestSnap openChest(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        Object holder = top.getHolder();
        if ((!(holder instanceof Chest) && !(holder instanceof DoubleChest)) || top.getLocation() == null) {
            return null;
        }
        return snap(top);
    }

    private ChestSnap fresh(ChestSnap chest) {
        if (chest.location.getWorld() == null) {
            return chest;
        }
        BlockState state = chest.location.getBlock().getState();
        if (state instanceof Chest block) {
            return snap(block.getInventory());
        }
        return chest;
    }

    private ChestSnap snap(Inventory inventory) {
        Cell[] cells = empty(HoldItems.CHEST);
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && i < cells.length; i++) {
            cells[i] = cell(contents[i]);
        }
        return new ChestSnap(chestId(inventory.getLocation()), inventory.getLocation().clone(), cells, false);
    }

    private Cell[] snapBag(Player player) {
        Cell[] cells = snapRaw(player);
        List<ItemStack> extra = doomed.get(player.getUniqueId());
        if (extra != null) {
            for (ItemStack stack : extra) {
                subtract(cells, stack);
            }
        }
        return cells;
    }

    private boolean fits(Player player, Collection<ItemStack> drops) {
        if (drops.isEmpty()) {
            return true;
        }
        Inventory temp = Bukkit.createInventory(null, 36);
        temp.setContents(player.getInventory().getStorageContents());
        return temp.addItem(drops.toArray(ItemStack[]::new)).isEmpty();
    }

    private void trimBurned(Cell[] shown, Cell[] chain, List<ItemStack> burned) {
        for (ItemStack stack : burned) {
            int item = HoldItems.id(stack, plugin);
            int damage = HoldItems.damage(stack);
            int excess = Math.max(0, total(shown, item, damage) - total(chain, item, damage));
            subtractAmount(shown, item, damage, Math.min(stack.getAmount(), excess));
        }
    }

    private void giveExcess(Player player, Cell[] local, Cell[] signed, Map<Long, ItemStack> kept) {
        Set<Long> signedGear = new HashSet<>();
        if (signed != null) {
            for (Cell cell : signed) {
                if (cell.gear > 0) {
                    signedGear.add(cell.gear);
                }
            }
        }
        if (local != null && kept != null) {
            for (Cell cell : local) {
                if (cell.gear > 0 && !signedGear.contains(cell.gear)) {
                    ItemStack stack = kept.get(cell.gear);
                    if (stack != null && !player.getInventory().addItem(stack.clone()).isEmpty()) {
                        return;
                    }
                }
            }
        }
        Map<Long, Integer> totals = new HashMap<>();
        addTotals(totals, local, 1);
        addTotals(totals, signed, -1);
        for (Map.Entry<Long, Integer> entry : totals.entrySet()) {
            int left = entry.getValue();
            if (left <= 0) {
                continue;
            }
            int item = (int) (entry.getKey() >> 32);
            int damage = (int) (entry.getKey() & 0xffffffffL);
            while (left > 0) {
                int amount = Math.min(64, left);
                ItemStack stack = HoldItems.stack(plugin, item, amount, damage);
                if (stack == null || !player.getInventory().addItem(stack).isEmpty()) {
                    return;
                }
                left -= amount;
            }
        }
    }

    private static void addTotals(Map<Long, Integer> totals, Cell[] cells, int sign) {
        if (cells == null) {
            return;
        }
        for (Cell cell : cells) {
            if (cell.gear != 0 || cell.item == 0 || cell.count <= 0) {
                continue;
            }
            long key = ((long) cell.item << 32) | (cell.damage & 0xffffffffL);
            totals.merge(key, sign * cell.count, Integer::sum);
        }
    }

    private static int total(Cell[] cells, int item, int damage) {
        int sum = 0;
        for (Cell cell : cells) {
            if (cell.gear == 0 && cell.item == item && cell.damage == damage) {
                sum += cell.count;
            }
        }
        return sum;
    }

    private static void subtractAmount(Cell[] cells, int item, int damage, int left) {
        for (int i = 0; i < cells.length && left > 0; i++) {
            if (cells[i].gear != 0 || cells[i].item != item || cells[i].damage != damage || cells[i].count <= 0) {
                continue;
            }
            int take = Math.min(left, cells[i].count);
            int next = cells[i].count - take;
            cells[i] = next <= 0 ? Cell.EMPTY : cells[i].withCount(next);
            left -= take;
        }
    }

    private static int[] allSlots(int size) {
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private void subtract(Cell[] cells, ItemStack stack) {
        int item = HoldItems.id(stack, plugin);
        int damage = HoldItems.damage(stack);
        if (HoldItems.unique(stack)) {
            long gear = HoldItems.gearOf(plugin, stack);
            for (int i = 0; i < cells.length; i++) {
                boolean match = gear > 0
                        ? cells[i].gear == gear
                        : cells[i].gear <= 0 && cells[i].item == item && cells[i].damage == damage && cells[i].count > 0;
                if (!match) {
                    continue;
                }
                int next = cells[i].count - Math.min(cells[i].count, stack.getAmount());
                cells[i] = next <= 0 ? Cell.EMPTY : cells[i].withCount(next);
                return;
            }
            return;
        }
        int left = stack.getAmount();
        for (int i = 0; i < cells.length && left > 0; i++) {
            if (cells[i].gear != 0 || cells[i].item != item || cells[i].damage != damage || cells[i].count <= 0) {
                continue;
            }
            int take = Math.min(left, cells[i].count);
            int next = cells[i].count - take;
            cells[i] = next <= 0 ? Cell.EMPTY : cells[i].withCount(next);
            left -= take;
        }
    }

    private boolean coalesce(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        boolean changed = HoldItems.makeUnbreakable(storage);
        changed |= HoldItems.compact(storage);
        if (changed) {
            player.getInventory().setStorageContents(storage);
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (HoldItems.makeUnbreakable(armor)) {
            player.getInventory().setArmorContents(armor);
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (HoldItems.makeUnbreakable(offhand)) {
            player.getInventory().setItemInOffHand(offhand);
        }
        return changed;
    }

    private Cell[] snapRaw(Player player) {
        Cell[] cells = empty(HoldItems.BAG);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length && i < 36; i++) {
            cells[i] = cell(storage[i]);
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length && i < 4; i++) {
            cells[36 + i] = cell(armor[i]);
        }
        cells[40] = cell(player.getInventory().getItemInOffHand());
        return cells;
    }

    private void applyBag(Player player, Cell[] cells) {
        applying.add(player.getUniqueId());
        Map<Long, ItemStack> kept = indexGear(bagStacks(player));
        ItemStack[] storage = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 36; i++) {
            storage[i] = item(cells[i], kept);
        }
        for (int i = 0; i < 4; i++) {
            armor[i] = item(cells[36 + i], kept);
        }
        player.getInventory().setStorageContents(storage);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(item(cells[40], kept));
        if (coalesce(player)) {
            retry.add(player.getUniqueId());
        }
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            applying.remove(id);
            if (!retry.remove(id)) {
                return;
            }
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) {
                flush(online);
            }
        });
    }

    private void applyChest(Location location, Cell[] cells) {
        Inventory inventory = inventoryAt(location);
        if (inventory == null) {
            return;
        }
        Map<Long, ItemStack> kept = indexGear(inventory.getContents());
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        for (int i = 0; i < contents.length && i < cells.length; i++) {
            contents[i] = item(cells[i], kept);
        }
        inventory.setContents(contents);
    }

    private Inventory inventoryAt(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockState state = location.getWorld().getBlockAt(
                        location.getBlockX() + dx,
                        location.getBlockY(),
                        location.getBlockZ() + dz
                ).getState();
                if (state instanceof Chest chest) {
                    return chest.getInventory();
                }
            }
        }
        return null;
    }

    private Cell cell(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0 || SbMenu.isStar(plugin, stack)) {
            return Cell.EMPTY;
        }
        int item = HoldItems.id(stack, plugin);
        int damage = HoldItems.damage(stack);
        if (!HoldItems.unique(stack)) {
            return new Cell(item, stack.getAmount(), damage, 0L, "", "", "", "");
        }
        long gear = HoldItems.gearOf(plugin, stack);
        if (gear <= 0L) {
            gear = -1L;
        }
        return new Cell(
                item,
                stack.getAmount(),
                damage,
                gear,
                HoldItems.texture(stack, plugin),
                HoldItems.label(stack, plugin),
                HoldItems.reforge(plugin, stack),
                HoldItems.enchants(stack)
        );
    }

    private ItemStack item(Cell cell, Map<Long, ItemStack> kept) {
        if (cell.item == 0 || cell.count <= 0) {
            return null;
        }
        if (cell.gear > 0 && kept != null) {
            ItemStack existing = kept.remove(cell.gear);
            if (existing != null) {
                ItemStack copy = existing.clone();
                copy.setAmount(1);
                HoldItems.makeUnbreakable(copy);
                HoldItems.stamp(plugin, copy, cell.gear);
                return copy;
            }
        }
        ItemStack built = HoldItems.stack(plugin, cell.item, cell.count, cell.damage);
        if (built != null && cell.gear > 0) {
            HoldItems.stamp(plugin, built, cell.gear);
        }
        return built;
    }

    private ItemStack[] bagStacks(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack[] all = new ItemStack[HoldItems.BAG];
        for (int i = 0; i < storage.length && i < 36; i++) {
            all[i] = storage[i];
        }
        for (int i = 0; i < armor.length && i < 4; i++) {
            all[36 + i] = armor[i];
        }
        all[40] = player.getInventory().getItemInOffHand();
        return all;
    }

    private Map<Long, ItemStack> indexGear(ItemStack[] stacks) {
        Map<Long, ItemStack> found = new HashMap<>();
        if (stacks == null) {
            return found;
        }
        for (ItemStack stack : stacks) {
            long gear = HoldItems.gearOf(plugin, stack);
            if (gear > 0) {
                found.putIfAbsent(gear, stack.clone());
            }
        }
        return found;
    }

    private static boolean same(Cell[] left, Cell[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!left[i].same(right[i])) {
                return false;
            }
        }
        return true;
    }

    private static Cell[] merge(Cell[] chain, Cell[] before, Cell[] local) {
        Cell[] merged = new Cell[chain.length];
        for (int i = 0; i < chain.length; i++) {
            Cell was = i < before.length ? before[i] : Cell.EMPTY;
            Cell now = i < local.length ? local[i] : Cell.EMPTY;
            merged[i] = now.same(was) ? chain[i] : now;
        }
        return merged;
    }

    private static int[] diff(Cell[] now, Cell[] ack) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < now.length; i++) {
            Cell previous = ack == null || i >= ack.length ? Cell.EMPTY : ack[i];
            if (!now[i].same(previous)) {
                slots.add(i);
            }
        }
        int[] values = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            values[i] = slots.get(i);
        }
        return values;
    }

    private static int[] pick(Cell[] cells, int[] slots, int field) {
        int[] values = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Cell cell = cells[slots[i]];
            values[i] = field == 0 ? cell.item : field == 1 ? cell.count : cell.damage;
        }
        return values;
    }

    private static long[] pickGear(Cell[] cells, int[] slots) {
        long[] values = new long[slots.length];
        for (int i = 0; i < slots.length; i++) {
            values[i] = cells[slots[i]].gear;
        }
        return values;
    }

    private static String[] pickText(Cell[] cells, int[] slots, int field) {
        String[] values = new String[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Cell cell = cells[slots[i]];
            String value = switch (field) {
                case 0 -> cell.key;
                case 1 -> cell.label;
                case 2 -> cell.reforge;
                default -> cell.enchants;
            };
            values[i] = value == null ? "" : value;
        }
        return values;
    }

    private static Cell[] acknowledge(Cell[] sent, Cell[] chain) {
        Cell[] acked = new Cell[sent.length];
        for (int i = 0; i < sent.length; i++) {
            Cell cell = sent[i];
            long gear = i < chain.length ? chain[i].gear : 0L;
            if (cell.gear <= 0 && gear > 0) {
                acked[i] = cell.withGear(gear);
            } else {
                acked[i] = cell;
            }
        }
        return acked;
    }

    private static Cell[] relocate(Cell[] bag, int from, int to) {
        Cell source = bag[from];
        if (source.item == 0 || source.count <= 0) {
            return null;
        }
        Cell[] next = bag.clone();
        Cell target = bag[to];
        if (target.item == 0 || target.count <= 0) {
            next[to] = source;
            next[from] = Cell.EMPTY;
            return next;
        }
        if (source.gear == 0 && target.gear == 0 && source.item == target.item && source.damage == target.damage) {
            int room = 64 - target.count;
            if (room > 0) {
                int moved = Math.min(room, source.count);
                next[to] = target.withCount(target.count + moved);
                int left = source.count - moved;
                next[from] = left <= 0 ? Cell.EMPTY : source.withCount(left);
                return next;
            }
        }
        next[from] = target;
        next[to] = source;
        return next;
    }

    private static void finish(CompletableFuture<String> done, String hash) {
        if (done != null && !done.isDone()) {
            done.complete(hash == null ? "" : hash);
        }
    }

    private static void fail(CompletableFuture<String> done, String message) {
        if (done != null && !done.isDone()) {
            done.completeExceptionally(new IllegalStateException(message == null ? "Move failed." : message));
        }
    }

    private static Cell[] cells(HoldItems.View view, int size) {
        Cell[] cells = empty(size);
        long[] gears = view.gears == null ? new long[0] : view.gears;
        for (int i = 0; i < size; i++) {
            int count = view.counts[i];
            long gear = i < gears.length ? gears[i] : 0L;
            if (count <= 0 || view.items[i] == 0) {
                cells[i] = Cell.EMPTY;
                continue;
            }
            cells[i] = new Cell(view.items[i], count, view.damages[i], gear, "", "", "", gear > 0 ? "-" : "");
        }
        return cells;
    }

    private static Cell[] empty(int size) {
        Cell[] cells = new Cell[size];
        for (int i = 0; i < size; i++) {
            cells[i] = Cell.EMPTY;
        }
        return cells;
    }

    private static boolean worn(Cell[] cells) {
        if (cells == null) {
            return false;
        }
        for (Cell cell : cells) {
            if (cell.damage > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean occupied(Cell[] cells) {
        for (Cell cell : cells) {
            if (cell.count > 0 && cell.item != 0) {
                return true;
            }
        }
        return false;
    }

    static long chestId(Location location) {
        long mixed = (location.getBlockX() * 341873128712L)
                ^ (location.getBlockY() * 132897987541L)
                ^ (location.getBlockZ() * 805308927L);
        mixed &= Long.MAX_VALUE;
        return mixed == 0 ? 1 : mixed;
    }

    private record ChestSnap(long id, Location location, Cell[] cells, boolean overwrite) {
    }

    private record Cell(int item, int count, int damage, long gear, String key, String label, String reforge, String enchants) {
        private static final Cell EMPTY = new Cell(0, 0, 0, 0L, "", "", "", "");

        private boolean same(Cell other) {
            return item == other.item
                    && count == other.count
                    && damage == other.damage
                    && gear == other.gear
                    && reforge.equals(other.reforge)
                    && enchantsMatch(enchants, other.enchants);
        }

        private static boolean enchantsMatch(String left, String right) {
            if (left.equals(right)) {
                return true;
            }
            return ("-".equals(left) && right.isEmpty()) || ("-".equals(right) && left.isEmpty());
        }

        private Cell withCount(int next) {
            return new Cell(item, next, damage, gear, key, label, reforge, enchants);
        }

        private Cell withGear(long nextGear) {
            return new Cell(item, count, damage, nextGear, key, label, reforge, enchants);
        }
    }
}
