package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Craft Item: SkyBlock crafting window (SkyBlock Menu or a crafting table block).
 * The 3x3 grid on the left holds real stacks with normal cursor behaviour (place, take,
 * drag, right-click split). The result slot and the glass around it are a live preview
 * only: red + "Recipe Required" until the pattern matches, then lime/green + the output.
 * Click or shift-click the result to craft. Closing returns leftover grid items to the
 * bag (drops them if it is full). Bag writes go through SkyWord2 after the grid is clear
 * or the window closes. Slot 9 (the menu star) is never an ingredient or a landing slot.
 * One plain cobblestone still signs the Voidstep Blade.
 */
final class CraftGui implements Listener {
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int RESULT = 23;
    private static final int[] QUICK = {16, 25, 34};
    private static final int[] FRAME = {45, 46, 47, 50, 51, 52, 53};
    private static final int BACK = 48;
    private static final int CLOSE = 49;

    final class View extends Menu {
        final Inventory inventory;
        boolean locked;
        boolean returned;
        boolean dirty;

        View() {
            inventory = Bukkit.createInventory(this, 54, Component.text("Craft Item"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SomniaSkyblockPlugin plugin;
    private final NamespacedKey displayKey;
    private final Map<UUID, Long> lastCraft = new HashMap<>();

    CraftGui(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, "craft-display");
    }

    void open(Player player) {
        View view = new View();
        layout(view);
        refresh(player, view);
        player.openInventory(view.inventory);
    }

    /** True while real ingredients sit in this window (out of the bag view, but not gone). */
    boolean gridBusy(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof View view)) {
            return false;
        }
        for (ItemStack stack : gridOf(view)) {
            if (!empty(stack)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTable(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CRAFTING_TABLE || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        if (event.getPlayer().isSneaking() && !event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        open(event.getPlayer());
    }

    private void layout(View view) {
        Inventory inv = view.inventory;
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }
        for (int slot : GRID) {
            inv.setItem(slot, null);
        }
        inv.setItem(BACK, SbMenu.icon(Material.ARROW, SbMenu.plain("Go Back", NamedTextColor.GREEN), List.of(SbMenu.plain("To SkyBlock Menu", NamedTextColor.GRAY))));
        inv.setItem(CLOSE, SbMenu.icon(Material.BARRIER, SbMenu.plain("Close", NamedTextColor.RED), List.of()));
        ItemStack quick = SbMenu.icon(
                Material.RED_STAINED_GLASS_PANE,
                SbMenu.plain("Quick Crafting Slot", NamedTextColor.RED),
                List.of(
                        SbMenu.plain("Craft items you can make without laying out the recipe.", NamedTextColor.GRAY),
                        SbMenu.plain("SOON", NamedTextColor.RED)));
        for (int slot : QUICK) {
            inv.setItem(slot, quick);
        }
    }

    private void refresh(Player player, View view) {
        stripDisplay(player);
        boolean valid;
        if (voidCraft(view)) {
            valid = true;
            view.inventory.setItem(RESULT, markDisplay(plugin.words().voidResult()));
        } else {
            Recipe recipe = recipe(player, view);
            valid = recipe != null;
            if (valid) {
                ItemStack result = recipe.getResult().clone();
                HoldItems.makeUnbreakable(result);
                ItemMeta meta = result.getItemMeta();
                List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
                lore.add(Component.empty());
                lore.add(SbMenu.plain("Click to craft!", NamedTextColor.YELLOW));
                lore.add(SbMenu.plain("Shift-Click to craft as many as you can!", NamedTextColor.YELLOW));
                meta.lore(lore);
                result.setItemMeta(meta);
                view.inventory.setItem(RESULT, markDisplay(result));
            } else {
                view.inventory.setItem(RESULT, markDisplay(SbMenu.icon(
                        Material.BARRIER,
                        SbMenu.plain("Recipe Required", NamedTextColor.RED),
                        List.of(
                                SbMenu.plain("Add the items for a valid", NamedTextColor.GRAY),
                                SbMenu.plain("recipe in the crafting grid", NamedTextColor.GRAY),
                                SbMenu.plain("to the left!", NamedTextColor.GRAY)))));
            }
        }
        ItemStack edge = pane(valid ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        for (int slot : FRAME) {
            view.inventory.setItem(slot, edge);
        }
    }

    private void laterRefresh(Player player, View view) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == view) {
                refresh(player, view);
            }
        });
    }

    private static ItemStack pane(Material material) {
        return SbMenu.icon(material, SbMenu.plain(" ", NamedTextColor.GRAY), List.of());
    }

    private ItemStack[] gridOf(View view) {
        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            ItemStack stack = view.inventory.getItem(GRID[i]);
            matrix[i] = empty(stack) || isDisplay(stack) ? null : stack.clone();
        }
        return matrix;
    }

    private boolean voidCraft(View view) {
        if (plugin.words() == null) {
            return false;
        }
        int n = 0;
        for (ItemStack stack : gridOf(view)) {
            if (empty(stack)) {
                continue;
            }
            if (stack.getType() != Material.COBBLESTONE || Items.isEnchanted(stack, plugin)) {
                return false;
            }
            n++;
        }
        return n == 1;
    }

    /** Vanilla recipe the grid matches, or null. Complex recipes and our signed recipe are skipped. */
    private Recipe recipe(Player player, View view) {
        ItemStack[] matrix = gridOf(view);
        boolean any = false;
        for (ItemStack stack : matrix) {
            any |= !empty(stack);
        }
        if (!any) {
            return null;
        }
        Recipe recipe;
        try {
            recipe = Bukkit.getCraftingRecipe(matrix, player.getWorld());
        } catch (RuntimeException exception) {
            return null;
        }
        if (recipe == null || recipe instanceof ComplexRecipe) {
            return null;
        }
        if (recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().getNamespace().equals("somniaskyblock")) {
            return null;
        }
        ItemStack result = recipe.getResult();
        return result == null || result.getType().isAir() ? null : recipe;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (view.locked) {
            event.setCancelled(true);
            return;
        }
        int raw = event.getRawSlot();
        Inventory clicked = event.getClickedInventory();
        ClickType click = event.getClick();
        InventoryAction action = event.getAction();

        if (starClick(event, player) || isDisplay(event.getCursor()) || isDisplay(event.getCurrentItem())) {
            event.setCancelled(true);
            stripDisplay(player);
            return;
        }
        if (action == InventoryAction.COLLECT_TO_CURSOR || raw < 0) {
            event.setCancelled(true);
            return;
        }
        if ((click == ClickType.DROP || click == ClickType.CONTROL_DROP) && raw < 54) {
            event.setCancelled(true);
            return;
        }
        if (click == ClickType.NUMBER_KEY && event.getHotbarButton() == SbMenu.STAR_SLOT) {
            event.setCancelled(true);
            return;
        }
        if (clicked == null) {
            event.setCancelled(true);
            return;
        }

        if (clicked == event.getView().getTopInventory()) {
            if (isGrid(raw)) {
                if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                    event.setCancelled(true);
                    shiftFromGrid(player, view, raw);
                    refresh(player, view);
                    return;
                }
                if (isDisplay(event.getCursor()) || SbMenu.isStar(plugin, event.getCursor())) {
                    event.setCancelled(true);
                    return;
                }
                view.dirty = true;
                laterRefresh(player, view);
                return;
            }
            event.setCancelled(true);
            if (raw == RESULT) {
                if (click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    craft(player, view, event.isShiftClick());
                }
            } else if (raw == BACK) {
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.menu().openMain(player));
            } else if (raw == CLOSE) {
                plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
            }
            return;
        }

        if (!(clicked instanceof PlayerInventory)) {
            event.setCancelled(true);
            return;
        }
        int slot = event.getSlot();
        if (slot == SbMenu.STAR_SLOT || SbMenu.isStar(plugin, event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            if (slot >= 0 && slot < 36) {
                shiftIntoGrid(player, view, slot);
                refresh(player, view);
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (view.locked || isDisplay(event.getOldCursor()) || SbMenu.isStar(plugin, event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < 54 && !isGrid(raw)) {
                event.setCancelled(true);
                return;
            }
            if (raw >= 54) {
                int bottom = event.getView().convertSlot(raw);
                if (bottom == SbMenu.STAR_SLOT) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        view.dirty = true;
        laterRefresh(player, view);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !(event.getView().getTopInventory().getHolder() instanceof View view)) {
            return;
        }
        giveBack(player, view);
        stripDisplay(player);
        if (plugin.words() != null && view.dirty) {
            plugin.words().commitLocal(player);
            view.dirty = false;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof View view) {
            giveBack(player, view);
            stripDisplay(player);
            if (plugin.words() != null && view.dirty) {
                plugin.words().commitLocal(player);
                view.dirty = false;
            }
        }
    }

    private void shiftIntoGrid(Player player, View view, int slot) {
        ItemStack source = player.getInventory().getItem(slot);
        if (empty(source) || SbMenu.isStar(plugin, source) || isDisplay(source)) {
            return;
        }
        ItemStack moving = source.clone();
        for (int pass = 0; pass < 2 && moving.getAmount() > 0; pass++) {
            for (int i = 0; i < 9 && moving.getAmount() > 0; i++) {
                ItemStack cell = view.inventory.getItem(GRID[i]);
                if (pass == 0) {
                    if (empty(cell) || !cell.isSimilar(moving)) {
                        continue;
                    }
                    int room = cell.getMaxStackSize() - cell.getAmount();
                    if (room <= 0) {
                        continue;
                    }
                    int used = Math.min(room, moving.getAmount());
                    cell.setAmount(cell.getAmount() + used);
                    view.inventory.setItem(GRID[i], cell);
                    moving.setAmount(moving.getAmount() - used);
                } else if (empty(cell) || isDisplay(cell)) {
                    view.inventory.setItem(GRID[i], moving.clone());
                    moving.setAmount(0);
                }
            }
        }
        if (moving.getAmount() <= 0) {
            player.getInventory().setItem(slot, null);
        } else {
            source.setAmount(moving.getAmount());
            player.getInventory().setItem(slot, source);
        }
        view.dirty = true;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    private void shiftFromGrid(Player player, View view, int raw) {
        ItemStack cell = view.inventory.getItem(raw);
        if (empty(cell) || isDisplay(cell)) {
            return;
        }
        ItemStack moving = cell.clone();
        view.inventory.setItem(raw, null);
        Runnable free = SbMenu.reserveStar(player);
        Map<Integer, ItemStack> over = player.getInventory().addItem(moving);
        free.run();
        if (!over.isEmpty()) {
            ItemStack left = over.values().iterator().next();
            view.inventory.setItem(raw, left);
        }
        view.dirty = true;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }

    private void giveBack(Player player, View view) {
        if (view.returned) {
            return;
        }
        view.returned = true;
        List<ItemStack> give = new ArrayList<>();
        for (int slot : GRID) {
            ItemStack stack = view.inventory.getItem(slot);
            view.inventory.setItem(slot, null);
            if (!empty(stack) && !isDisplay(stack)) {
                give.add(stack);
                view.dirty = true;
            }
        }
        if (give.isEmpty()) {
            return;
        }
        Runnable free = SbMenu.reserveStar(player);
        Map<Integer, ItemStack> over = player.getInventory().addItem(give.toArray(ItemStack[]::new));
        free.run();
        for (ItemStack left : over.values()) {
            if (!empty(left)) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }

    private void craft(Player player, View view, boolean max) {
        long now = System.currentTimeMillis();
        Long last = lastCraft.put(player.getUniqueId(), now);
        if (last != null && now - last < 150) {
            return;
        }
        if (plugin.words() != null && plugin.words().busy(player)) {
            player.sendMessage(Component.text("A signature is already in flight.", NamedTextColor.YELLOW));
            return;
        }
        if (voidCraft(view)) {
            view.dirty = true;
            view.locked = true;
            giveBack(player, view);
            player.closeInventory();
            plugin.words().craftVoid(player);
            return;
        }
        Recipe recipe = recipe(player, view);
        if (recipe == null) {
            return;
        }
        ItemStack[] beforeGrid = gridOf(view);
        ItemStack[] beforeBag = snapshotBag(player);
        ItemStack result = recipe.getResult().clone();
        HoldItems.makeUnbreakable(result);
        int per = Math.max(1, result.getAmount());
        int times = 1;
        if (max) {
            times = craftsPossible(beforeGrid);
        }
        if (times <= 0) {
            return;
        }
        times = fitTimes(player, beforeGrid, result, per, times);
        if (times <= 0) {
            player.sendMessage(Component.text("Not enough room for the result. Free some space.", NamedTextColor.RED));
            return;
        }
        view.locked = true;
        List<ItemStack> leftovers = consume(view, times);
        result.setAmount(1);
        List<ItemStack> give = split(result, per * times);
        give.addAll(leftovers);
        Runnable free = SbMenu.reserveStar(player);
        Map<Integer, ItemStack> over = player.getInventory().addItem(give.toArray(ItemStack[]::new));
        free.run();
        if (!over.isEmpty()) {
            restoreBag(player, beforeBag);
            restoreGrid(view, beforeGrid);
            view.locked = false;
            player.sendMessage(Component.text("Not enough room for the result. Free some space.", NamedTextColor.RED));
            refresh(player, view);
            return;
        }
        view.dirty = true;
        player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.2f);
        UUID id = player.getUniqueId();
        String label = HoldItems.label(result, plugin);
        int amount = per * times;
        java.util.function.Consumer<Boolean> done = ok -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null) {
                return;
            }
            if (ok) {
                plugin.skills().add(online, Skills.Skill.CARPENTRY, 2L * amount);
                if (result.getType().name().endsWith("_PICKAXE")) {
                    plugin.quests().hit(online, "craft_pickaxe", 1);
                }
            } else {
                if (online.getOpenInventory().getTopInventory().getHolder() instanceof View open && open == view) {
                    restoreGrid(open, beforeGrid);
                } else {
                    restoreBag(online, beforeBag);
                    online.sendMessage(Component.text("Craft rolled back: the bag write did not land.", NamedTextColor.RED));
                    return;
                }
                restoreBag(online, beforeBag);
                online.sendMessage(Component.text("Craft rolled back: the bag write did not land.", NamedTextColor.RED));
            }
            view.locked = false;
            if (online.getOpenInventory().getTopInventory().getHolder() instanceof View open && open == view) {
                refresh(online, open);
            }
        };
        // Write now only if the grid is empty so leftovers are not dropped from the bag snapshot.
        // Otherwise the close/quit path signs inventory + returned leftovers together.
        player.sendMessage(Component.text("Crafted ", NamedTextColor.GREEN).append(Component.text(label + " x" + amount, NamedTextColor.WHITE)));
        if (plugin.words() != null && !gridBusy(player)) {
            plugin.words().commitLocal(player, done);
            refresh(player, view);
            return;
        }
        done.accept(true);
    }

    private int craftsPossible(ItemStack[] grid) {
        int times = Integer.MAX_VALUE;
        boolean any = false;
        for (ItemStack stack : grid) {
            if (empty(stack)) {
                continue;
            }
            any = true;
            times = Math.min(times, stack.getAmount());
        }
        return any ? Math.max(1, times) : 0;
    }

    private int fitTimes(Player player, ItemStack[] grid, ItemStack result, int per, int want) {
        for (int times = want; times >= 1; times--) {
            Inventory probe = Bukkit.createInventory(null, 36);
            ItemStack[] storage = player.getInventory().getStorageContents();
            for (int i = 0; i < storage.length && i < 36; i++) {
                if (i == SbMenu.STAR_SLOT) {
                    probe.setItem(i, new ItemStack(Material.BARRIER));
                    continue;
                }
                probe.setItem(i, storage[i] == null ? null : storage[i].clone());
            }
            ItemStack[] trial = cloneAll(grid);
            List<ItemStack> leftovers = consumeStacks(trial, times);
            List<ItemStack> give = split(result, per * times);
            give.addAll(leftovers);
            if (probe.addItem(give.toArray(ItemStack[]::new)).isEmpty()) {
                return times;
            }
        }
        return 0;
    }

    private List<ItemStack> consume(View view, int times) {
        ItemStack[] grid = gridOf(view);
        List<ItemStack> leftovers = consumeStacks(grid, times);
        restoreGrid(view, grid);
        return leftovers;
    }

    private static List<ItemStack> consumeStacks(ItemStack[] grid, int times) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (int i = 0; i < grid.length; i++) {
            ItemStack stack = grid[i];
            if (empty(stack)) {
                continue;
            }
            Material rest = stack.getType().getCraftingRemainingItem();
            if (rest != null && !rest.isAir()) {
                leftovers.add(new ItemStack(rest, times));
            }
            int left = stack.getAmount() - times;
            if (left <= 0) {
                grid[i] = null;
            } else {
                stack.setAmount(left);
                grid[i] = stack;
            }
        }
        return leftovers;
    }

    private static List<ItemStack> split(ItemStack result, int amount) {
        List<ItemStack> give = new ArrayList<>();
        int left = amount;
        int max = Math.max(1, result.getMaxStackSize());
        while (left > 0) {
            ItemStack part = result.clone();
            part.setAmount(Math.min(left, max));
            left -= part.getAmount();
            give.add(part);
        }
        return give;
    }

    private static ItemStack[] snapshotBag(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) {
            copy[i] = storage[i] == null ? null : storage[i].clone();
        }
        return copy;
    }

    private static void restoreBag(Player player, ItemStack[] storage) {
        player.getInventory().setStorageContents(storage);
    }

    private static void restoreGrid(View view, ItemStack[] grid) {
        for (int i = 0; i < 9; i++) {
            view.inventory.setItem(GRID[i], grid[i] == null ? null : grid[i].clone());
        }
    }

    private static ItemStack[] cloneAll(ItemStack[] grid) {
        ItemStack[] copy = new ItemStack[grid.length];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i] == null ? null : grid[i].clone();
        }
        return copy;
    }

    private static boolean isGrid(int raw) {
        for (int slot : GRID) {
            if (slot == raw) {
                return true;
            }
        }
        return false;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    private boolean isDisplay(ItemStack stack) {
        if (empty(stack) || !stack.hasItemMeta()) {
            return false;
        }
        Byte mark = stack.getItemMeta().getPersistentDataContainer().get(displayKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private ItemStack markDisplay(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private void stripDisplay(Player player) {
        if (isDisplay(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        ItemStack[] storage = player.getInventory().getStorageContents();
        boolean changed = false;
        for (int i = 0; i < storage.length; i++) {
            if (i != SbMenu.STAR_SLOT && isDisplay(storage[i])) {
                storage[i] = null;
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setStorageContents(storage);
        }
    }

    private boolean starClick(InventoryClickEvent event, Player player) {
        return SbMenu.isStar(plugin, event.getCurrentItem())
                || SbMenu.isStar(plugin, event.getCursor())
                || (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && SbMenu.isStar(plugin, player.getInventory().getItem(event.getHotbarButton())))
                || (event.getClick() == ClickType.SWAP_OFFHAND && SbMenu.isStar(plugin, event.getCurrentItem()));
    }
}
