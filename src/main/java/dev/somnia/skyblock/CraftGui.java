package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Craft Item: the SkyBlock crafting window (SkyBlock Menu ➜ Crafting Table, or any crafting table block).
 * The 3×3 grid holds references to items still in your bag: click a stack in your inventory to place one of it,
 * click a grid cell to take it back. Nothing leaves the bag until you click the result; then the ingredients and the
 * result go through one SkyWord bag write, and Carpentry XP is paid when it lands. The menu star is never an ingredient.
 * A single plain cobblestone still signs the onchain Voidstep Blade, like the crafting table did.
 */
final class CraftGui implements Listener {
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int RESULT = 23;
    private static final int[] FRAME = {16, 25, 34, 45, 46, 47, 50, 51, 52, 53};

    final class View extends Menu {
        final Inventory inventory;
        final String[] cells = new String[9];

        View() {
            inventory = Bukkit.createInventory(this, 54, Component.text("Craft Item"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, Long> lastCraft = new HashMap<>();

    CraftGui(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void open(Player player) {
        View view = new View();
        paint(player, view);
        player.openInventory(view.inventory);
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
            return; // sneak + block in hand places a block, as in vanilla
        }
        event.setCancelled(true);
        open(event.getPlayer());
    }

    private static ItemStack pane(Material material, String name) {
        return SbMenu.icon(material, SbMenu.plain(name, NamedTextColor.GRAY), List.of());
    }

    private ItemStack ghost(String key) {
        ItemStack stack = HoldItems.fromKey(plugin, key, 1);
        return stack == null ? new ItemStack(Material.BARRIER) : stack;
    }

    private ItemStack[] matrix(View view) {
        ItemStack[] m = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            m[i] = view.cells[i] == null ? null : ghost(view.cells[i]);
        }
        return m;
    }

    private boolean voidCraft(View view) {
        int n = 0;
        for (String cell : view.cells) {
            if (cell != null) {
                if (!cell.equals("minecraft:cobblestone")) {
                    return false;
                }
                n++;
            }
        }
        return n == 1 && plugin.words() != null;
    }

    /** The vanilla recipe the grid matches, or null. Complex recipes (dye, banner copy...) and our signed recipe are skipped. */
    private Recipe recipe(Player player, View view) {
        boolean any = false;
        for (String cell : view.cells) {
            any |= cell != null;
        }
        if (!any) {
            return null;
        }
        Recipe recipe;
        try {
            recipe = Bukkit.getCraftingRecipe(matrix(view), player.getWorld());
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

    private void paint(Player player, View view) {
        Inventory inv = view.inventory;
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 0; i < 9; i++) {
            if (view.cells[i] == null) {
                inv.setItem(GRID[i], null);
            } else {
                ItemStack g = ghost(view.cells[i]);
                ItemMeta meta = g.getItemMeta();
                List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
                lore.add(Component.empty());
                lore.add(SbMenu.plain("Click to take it out of the grid.", NamedTextColor.YELLOW));
                meta.lore(lore);
                g.setItemMeta(meta);
                inv.setItem(GRID[i], g);
            }
        }
        boolean valid;
        if (voidCraft(view)) {
            valid = true;
            inv.setItem(RESULT, plugin.words().voidResult());
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
                inv.setItem(RESULT, result);
            } else {
                inv.setItem(RESULT, SbMenu.icon(Material.BARRIER, SbMenu.plain("Recipe Required", NamedTextColor.RED), List.of(
                        SbMenu.plain("Add the items for a valid", NamedTextColor.GRAY),
                        SbMenu.plain("recipe in the crafting grid", NamedTextColor.GRAY),
                        SbMenu.plain("to the left!", NamedTextColor.GRAY))));
            }
        }
        ItemStack edge = pane(valid ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, " ");
        for (int slot : FRAME) {
            inv.setItem(slot, edge);
        }
        inv.setItem(48, SbMenu.icon(Material.ARROW, SbMenu.plain("Go Back", NamedTextColor.GREEN), List.of(SbMenu.plain("To SkyBlock Menu", NamedTextColor.GRAY))));
        inv.setItem(49, SbMenu.icon(Material.BARRIER, SbMenu.plain("Close", NamedTextColor.RED), List.of()));
    }

    private int used(View view, String key) {
        int n = 0;
        for (String cell : view.cells) {
            if (key.equals(cell)) {
                n++;
            }
        }
        return n;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        if (clicked == event.getView().getBottomInventory()) {
            int slot = event.getSlot();
            if (slot == SbMenu.STAR_SLOT || slot < 0 || slot >= 36) {
                return;
            }
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir() || SbMenu.isStar(plugin, stack)) {
                return;
            }
            String key = HoldItems.keyOf(stack, plugin);
            if (key == null) {
                return;
            }
            int have = plugin.market().count(player, key);
            boolean fill = event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT;
            boolean placed = false;
            for (int i = 0; i < 9; i++) {
                if (view.cells[i] == null && used(view, key) < have) {
                    view.cells[i] = key;
                    placed = true;
                    if (!fill) {
                        break;
                    }
                }
            }
            if (placed) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                paint(player, view);
            }
            return;
        }
        int raw = event.getRawSlot();
        for (int i = 0; i < 9; i++) {
            if (GRID[i] == raw && view.cells[i] != null) {
                view.cells[i] = null;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                paint(player, view);
                return;
            }
        }
        if (raw == 48) {
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.menu().openMain(player));
        } else if (raw == 49) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        } else if (raw == RESULT) {
            craft(player, view, event.isShiftClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    private void craft(Player player, View view, boolean max) {
        long now = System.currentTimeMillis();
        Long last = lastCraft.put(player.getUniqueId(), now);
        if (last != null && now - last < 150) {
            return;
        }
        if (voidCraft(view)) {
            player.closeInventory();
            plugin.words().craftVoid(player);
            return;
        }
        Recipe recipe = recipe(player, view);
        if (recipe == null) {
            return;
        }
        Map<String, Integer> need = new HashMap<>();
        for (String cell : view.cells) {
            if (cell != null) {
                need.merge(cell, 1, Integer::sum);
            }
        }
        ItemStack result = recipe.getResult().clone();
        int per = Math.max(1, result.getAmount());
        int times = 1;
        if (max) {
            times = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                times = Math.min(times, plugin.market().count(player, e.getKey()) / e.getValue());
            }
            times = Math.max(1, Math.min(times, Math.max(1, 64 / per)));
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            if (plugin.market().count(player, e.getKey()) < e.getValue() * times) {
                player.sendMessage(Component.text("You no longer have the items for that recipe.", NamedTextColor.RED));
                clearMissing(player, view);
                paint(player, view);
                return;
            }
        }
        ItemStack[] before = player.getInventory().getStorageContents().clone();
        for (int i = 0; i < before.length; i++) {
            before[i] = before[i] == null ? null : before[i].clone();
        }
        List<ItemStack> leftovers = new ArrayList<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            plugin.market().take(player, e.getKey(), e.getValue() * times);
            ItemStack sample = ghost(e.getKey());
            Material rest = sample.getType().getCraftingRemainingItem();
            if (rest != null && !rest.isAir()) {
                leftovers.add(new ItemStack(rest, e.getValue() * times));
            }
        }
        int amount = per * times;
        result.setAmount(1);
        HoldItems.makeUnbreakable(result);
        List<ItemStack> give = new ArrayList<>();
        int left = amount;
        while (left > 0) {
            ItemStack part = result.clone();
            part.setAmount(Math.min(left, result.getMaxStackSize()));
            left -= part.getAmount();
            give.add(part);
        }
        give.addAll(leftovers);
        Runnable free = SbMenu.reserveStar(player);
        Map<Integer, ItemStack> over = player.getInventory().addItem(give.toArray(new ItemStack[0]));
        free.run();
        if (!over.isEmpty()) {
            player.getInventory().setStorageContents(before);
            player.sendMessage(Component.text("Not enough room for the result. Free some space.", NamedTextColor.RED));
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.2f);
        UUID id = player.getUniqueId();
        String label = HoldItems.label(result, plugin);
        java.util.function.Consumer<Boolean> done = ok -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null) {
                return;
            }
            if (ok) {
                plugin.skills().add(online, Skills.Skill.CARPENTRY, 2L * amount);
            } else {
                online.sendMessage(Component.text("Craft rolled back: the bag write did not land.", NamedTextColor.RED));
            }
            if (online.getOpenInventory().getTopInventory().getHolder() instanceof View open) {
                clearMissing(online, open);
                paint(online, open);
            }
        };
        if (plugin.words() != null) {
            plugin.words().commitLocal(player, done);
        } else {
            done.accept(true);
        }
        player.sendMessage(Component.text("Crafted ", NamedTextColor.GREEN).append(Component.text(label + " x" + amount, NamedTextColor.WHITE)));
        clearMissing(player, view);
        paint(player, view);
    }

    /** Drops grid cells whose item is no longer in the bag (after a craft used them up). */
    private void clearMissing(Player player, View view) {
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            String key = view.cells[i];
            if (key == null) {
                continue;
            }
            int n = seen.merge(key, 1, Integer::sum);
            if (plugin.market().count(player, key) < n) {
                view.cells[i] = null;
            }
        }
    }
}
