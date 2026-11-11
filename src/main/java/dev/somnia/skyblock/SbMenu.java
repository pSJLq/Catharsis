package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The SkyBlock Menu: a locked Nether Star in the last hotbar slot opens a 6-row menu with Skills,
 * Collections, Stats, Quest Log, Calendar, Fast Travel, Bazaar/Auction shortcuts and Settings.
 * The star never goes into the on-chain bag (WordChain/HoldSync treat it as empty).
 */
final class SbMenu implements Listener, CommandExecutor {
    static final int STAR_SLOT = 8;
    private static final int[] PATH = {9, 18, 27, 36, 37, 38, 29, 20, 11, 12, 13, 22, 31, 40, 41, 42, 33, 24, 15, 16, 17, 26, 35, 44};
    private final SomniaSkyblockPlugin plugin;

    SbMenu(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** A menu page; extends Menu so the bag sync and the island listener treat it as a GUI. */
    static final class View extends Menu {
        final Inventory inventory;
        final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

        View(int size, String title) {
            inventory = Bukkit.createInventory(this, size, Component.text(title));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void set(int slot, ItemStack stack, Consumer<ClickType> action) {
            inventory.setItem(slot, stack);
            if (action != null) {
                actions.put(slot, action);
            } else {
                actions.remove(slot);
            }
        }
    }

    // ------------------------------------------------------------------ the star
    private NamespacedKey starKey() {
        return new NamespacedKey(plugin, "sb-menu");
    }

    static boolean isStar(SomniaSkyblockPlugin plugin, ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHER_STAR && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "sb-menu"), PersistentDataType.BYTE);
    }

    private ItemStack star() {
        ItemStack stack = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain("SkyBlock Menu ", NamedTextColor.GREEN).append(plain("(Click)", NamedTextColor.GRAY)));
        meta.lore(List.of(
                plain("View all of your SkyBlock progress,", NamedTextColor.GRAY),
                plain("including your Skills, Collections,", NamedTextColor.GRAY),
                plain("Recipes, and more!", NamedTextColor.GRAY),
                Component.empty(),
                plain("Click to open!", NamedTextColor.YELLOW)));
        meta.getPersistentDataContainer().set(starKey(), PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Slot 9 (index 8) always belongs to the star: puts it there, moves any other item out of that slot into a
     * free slot (or drops it at the player's feet if the bag is full) and removes duplicates elsewhere.
     * With the star turned off in settings the slot stays empty but still reserved.
     */
    void give(Player player) {
        if (player == null || !player.isOnline() || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (i != STAR_SLOT && isStar(plugin, storage[i])) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isStar(plugin, player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
        ItemStack intruder = player.getInventory().getItem(STAR_SLOT);
        if (intruder != null && !intruder.getType().isAir() && !isStar(plugin, intruder)) {
            // Hold the slot with the star while the intruder is re-added, so it can only land elsewhere.
            player.getInventory().setItem(STAR_SLOT, star());
            for (ItemStack left : player.getInventory().addItem(intruder).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        if (profile.menuItemOff) {
            if (isStar(plugin, player.getInventory().getItem(STAR_SLOT))) {
                player.getInventory().setItem(STAR_SLOT, null);
            }
            return;
        }
        ItemStack current = player.getInventory().getItem(STAR_SLOT);
        if (current == null || current.getType().isAir()) {
            player.getInventory().setItem(STAR_SLOT, star());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(SbLoginEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> give(player), 60L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> give(player), 200L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> give(player), 2L);
    }

    /** Pickups never fill slot 9: when it is empty (star turned off) the star holds it for this tick. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.player.PlayerAttemptPickupItemEvent event) {
        holdSlot(event.getPlayer());
    }

    private void holdSlot(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }
        ItemStack current = player.getInventory().getItem(STAR_SLOT);
        if (current == null || current.getType().isAir()) {
            player.getInventory().setItem(STAR_SLOT, star());
            plugin.getServer().getScheduler().runTask(plugin, () -> give(player));
        }
    }

    private static boolean starSlot(Player player, Inventory clicked, int slot) {
        return clicked instanceof PlayerInventory && clicked.equals(player.getInventory()) && slot == STAR_SLOT;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> give(player), 5L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(stack -> isStar(plugin, stack));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (!isStar(plugin, event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            openMain(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isStar(plugin, event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isStar(plugin, event.getMainHandItem()) || isStar(plugin, event.getOffHandItem())
                || event.getPlayer().getInventory().getHeldItemSlot() == STAR_SLOT) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof View) {
            event.setCancelled(true);
            return;
        }
        if (isStar(plugin, event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            for (int raw : event.getRawSlots()) {
                if (starSlot(player, event.getView().getInventory(raw), event.getView().convertSlot(raw))) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean starClick = isStar(plugin, event.getCurrentItem()) || isStar(plugin, event.getCursor())
                || (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && isStar(plugin, player.getInventory().getItem(event.getHotbarButton())))
                || (event.getClick() == ClickType.SWAP_OFFHAND && isStar(plugin, event.getCurrentItem()));
        if (event.getView().getTopInventory().getHolder() instanceof View view) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(view.getInventory())) {
                Consumer<ClickType> action = view.actions.get(event.getRawSlot());
                if (action != null) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                    plugin.getServer().getScheduler().runTask(plugin, () -> action.accept(event.getClick()));
                }
            } else if (starClick) {
                plugin.getServer().getScheduler().runTask(plugin, () -> openMain(player));
            }
            return;
        }
        if (starClick) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> openMain(player));
            return;
        }
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        boolean intoSlot = starSlot(player, event.getClickedInventory(), event.getSlot())
                || (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == STAR_SLOT);
        if (intoSlot) {
            // Slot 9 is the star's: nothing moves in or out of it (click, number key 9, offhand swap, shift-click).
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            // A shift-click must not land in an empty slot 9 (star turned off): hold it for this click.
            holdSlot(player);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (command.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "skills" -> openSkills(player);
            case "collection" -> openCollections(player);
            case "calendar" -> openCalendar(player);
            case "warp" -> openTravel(player);
            default -> openMain(player);
        }
        return true;
    }

    // ------------------------------------------------------------------ item helpers
    static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> fixed = new ArrayList<>();
        for (Component line : lore) {
            fixed.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(fixed);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }

    static List<Component> wrap(String text, NamedTextColor color) {
        List<Component> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() > 34 && line.length() > 0) {
                out.add(plain(line.toString().trim(), color));
                line.setLength(0);
            }
            line.append(word).append(' ');
        }
        if (line.length() > 0) {
            out.add(plain(line.toString().trim(), color));
        }
        return out;
    }

    /** "━━━━━━━━━━━━━━━━━━━━ 18/50" with the done part green. */
    static Component bar(long have, long need) {
        double f = need <= 0 ? 1 : Math.max(0, Math.min(1, have / (double) need));
        int done = (int) Math.round(f * 20);
        return Component.text("━".repeat(done), NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("━".repeat(20 - done), NamedTextColor.WHITE))
                .append(Component.text(" " + Skills.compact(have), NamedTextColor.YELLOW))
                .append(Component.text("/", NamedTextColor.GOLD))
                .append(Component.text(Skills.compact(need), NamedTextColor.YELLOW));
    }

    static String percent(long have, long need) {
        double p = need <= 0 ? 100 : 100.0 * have / need;
        return (p == Math.floor(p) ? String.valueOf((int) p) : String.format("%.1f", p)) + "%";
    }

    private View page(String title) {
        View view = new View(54, title);
        for (int i = 0; i < 54; i++) {
            view.inventory.setItem(i, filler());
        }
        return view;
    }

    private void footer(View view, Player player, Runnable back) {
        if (back != null) {
            view.set(48, icon(Material.ARROW, plain("Go Back", NamedTextColor.GREEN), List.of(plain("To SkyBlock Menu", NamedTextColor.GRAY))), c -> back.run());
        }
        view.set(49, icon(Material.BARRIER, plain("Close", NamedTextColor.RED), List.of()), c -> player.closeInventory());
    }

    /** A feature that is not in this version: gray name and a gray SOON line, never clickable. */
    static ItemStack soon(Material material, String name, String what) {
        List<Component> lore = new ArrayList<>(wrap(what, NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(plain("SOON", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, true));
        return icon(material, plain(name, NamedTextColor.GRAY), lore);
    }

    private static Component click(String what) {
        return plain(what, NamedTextColor.YELLOW);
    }

    private static Component stat(String symbol, String name, NamedTextColor color, String value) {
        return plain(" " + symbol + " " + name + " ", color).append(plain(value, NamedTextColor.WHITE));
    }

    // ------------------------------------------------------------------ main menu
    void openMain(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Stats.Sheet sheet = plugin.stats().sheet(player);
        View view = page("SkyBlock Menu");
        // profile head
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skull = (SkullMeta) head.getItemMeta();
        skull.setOwningPlayer(player);
        head.setItemMeta(skull);
        ItemMeta hm = head.getItemMeta();
        hm.displayName(plain("Your SkyBlock Profile", NamedTextColor.GREEN));
        List<Component> statsLore = new ArrayList<>(wrap("View your equipment, stats, and more!", NamedTextColor.GRAY));
        statsLore.add(Component.empty());
        statsLore.addAll(statLines(player, sheet));
        statsLore.add(plain(" and more...", NamedTextColor.DARK_GRAY));
        statsLore.add(Component.empty());
        statsLore.add(plain("Also accessible via /stats", NamedTextColor.DARK_GRAY));
        statsLore.add(Component.empty());
        statsLore.add(click("Click to view!"));
        hm.lore(statsLore);
        head.setItemMeta(hm);
        view.set(13, head, c -> openStats(player));

        // skills
        double avg = 0;
        for (Skills.Skill s : Skills.Skill.values()) {
            avg += Skills.level(profile, s);
        }
        avg /= Skills.Skill.values().length;
        List<Component> sl = new ArrayList<>(wrap("View your Skill progression and rewards.", NamedTextColor.GRAY));
        sl.add(Component.empty());
        sl.add(plain(String.format("%.1f", avg) + " Skill Avg.", NamedTextColor.GOLD));
        sl.add(Component.empty());
        sl.add(plain("Also accessible via /skills", NamedTextColor.DARK_GRAY));
        sl.add(Component.empty());
        sl.add(click("Click to view!"));
        view.set(19, icon(Material.DIAMOND_SWORD, plain("Your Skills", NamedTextColor.GREEN), sl), c -> openSkills(player));

        int unlocked = Collects.unlocked(profile);
        int total = Collects.ENTRIES.size();
        List<Component> cl = new ArrayList<>(wrap("View all of the items available in SkyBlock. Collect more of an item to unlock rewards on your way to becoming a master of SkyBlock!", NamedTextColor.GRAY));
        cl.add(Component.empty());
        cl.add(plain("Collections Unlocked: ", NamedTextColor.GRAY).append(plain(percent(unlocked, total), NamedTextColor.YELLOW)));
        cl.add(bar(unlocked, total));
        cl.add(Component.empty());
        cl.add(plain("Also accessible via /collection", NamedTextColor.DARK_GRAY));
        cl.add(Component.empty());
        cl.add(click("Click to view!"));
        view.set(20, icon(Material.PAINTING, plain("Collections", NamedTextColor.GREEN), cl), c -> openCollections(player));

        List<Component> rl = new ArrayList<>(wrap("Every forge recipe and compression craft you can make, with what it costs.", NamedTextColor.GRAY));
        rl.add(Component.empty());
        rl.add(plain("Recipes: ", NamedTextColor.GRAY).append(plain(String.valueOf(Market.RECIPES.size() + 2), NamedTextColor.YELLOW)));
        rl.add(Component.empty());
        rl.add(click("Click to view!"));
        view.set(21, icon(Material.BOOK, plain("Recipe Book", NamedTextColor.GREEN), rl), c -> openRecipes(player));

        List<Component> tl = new ArrayList<>(wrap("Trade items safely with another player. Both sides confirm before anything moves.", NamedTextColor.GRAY));
        tl.add(Component.empty());
        tl.add(plain("Use ", NamedTextColor.GRAY).append(plain("/trade <player>", NamedTextColor.YELLOW)));
        tl.add(Component.empty());
        tl.add(click("Click for help!"));
        view.set(22, icon(Material.EMERALD, plain("Trades", NamedTextColor.GREEN), tl), c -> {
            player.closeInventory();
            player.sendMessage(Component.text("Stand next to a player and type /trade <name>. Both of you lock in before the swap.", NamedTextColor.YELLOW));
        });

        Quests.Step step = plugin.quests().current(profile);
        List<Component> ql = new ArrayList<>(wrap("View your active quests, progress, and rewards.", NamedTextColor.GRAY));
        ql.add(Component.empty());
        ql.add(plain("Completed: ", NamedTextColor.GRAY).append(plain(profile.questStep + "/" + Quests.STEPS.size(), NamedTextColor.YELLOW)));
        if (step != null) {
            ql.add(plain("Next: ", NamedTextColor.GRAY).append(plain(step.title(), NamedTextColor.YELLOW)));
        }
        ql.add(Component.empty());
        ql.add(click("Click to view!"));
        view.set(23, glint(icon(Material.ENCHANTED_BOOK, plain("Quest Log", NamedTextColor.GREEN), ql)), c -> openQuests(player));

        World w = player.getWorld();
        List<Component> kl = new ArrayList<>(wrap("View the SkyBlock calendar, the time of day and upcoming events.", NamedTextColor.GRAY));
        kl.add(Component.empty());
        kl.add(plain(Sidebar.season(w.getFullTime()) + ", " + Sidebar.clock(w.getTime()), NamedTextColor.YELLOW));
        kl.add(Component.empty());
        kl.add(click("Click to view!"));
        view.set(24, icon(Material.CLOCK, plain("Calendar and Events", NamedTextColor.GREEN), kl), c -> openCalendar(player));

        List<Component> stl = new ArrayList<>(wrap("Your bag is your storage: all 36 slots, armor and off hand are written to your Somnia bag contract and come back on any server.", NamedTextColor.GRAY));
        stl.add(Component.empty());
        stl.add(plain("Purse: ", NamedTextColor.GRAY).append(plain(WalletService.shortAddress(profile.wallet), NamedTextColor.AQUA)));
        view.set(25, icon(Material.CHEST, plain("Storage", NamedTextColor.GREEN), stl), null);

        view.set(29, icon(Material.GOLD_BLOCK, plain("Bazaar", NamedTextColor.GOLD), List.of(
                plain("Buy and sell resources with", NamedTextColor.GRAY), plain("orders and offers in STT.", NamedTextColor.GRAY),
                Component.empty(), plain("Also accessible via /bz", NamedTextColor.DARK_GRAY), Component.empty(), click("Click to open!"))),
                c -> { player.closeInventory(); player.performCommand("bz"); });
        view.set(30, icon(Material.GOLDEN_HORSE_ARMOR, plain("Auction House", NamedTextColor.GOLD), List.of(
                plain("Bid on unique gear. Escrow is", NamedTextColor.GRAY), plain("held by the Somnia contract.", NamedTextColor.GRAY),
                Component.empty(), plain("Also accessible via /ah", NamedTextColor.DARK_GRAY), Component.empty(), click("Click to open!"))),
                c -> { player.closeInventory(); player.performCommand("ah"); });
        view.set(31, icon(Material.CRAFTING_TABLE, plain("Crafting Table", NamedTextColor.GREEN), List.of(
                plain("Opens the crafting grid.", NamedTextColor.GRAY), plain("Crafting earns Carpentry XP.", NamedTextColor.GRAY),
                Component.empty(), click("Click to open!"))),
                c -> player.openWorkbench(null, true));
        view.set(32, icon(Material.LEATHER_CHESTPLATE, plain("Wardrobe", NamedTextColor.GREEN), List.of(
                plain("See the armor you wear and", NamedTextColor.GRAY), plain("what each piece adds.", NamedTextColor.GRAY),
                Component.empty(), click("Click to view!"))),
                c -> openWardrobe(player));
        view.set(33, icon(Material.COBBLESTONE, plain("Minions", NamedTextColor.GREEN), List.of(
                plain("Minions work your island while", NamedTextColor.GRAY), plain("you are away.", NamedTextColor.GRAY),
                Component.empty(), plain("Placed: ", NamedTextColor.GRAY).append(plain(String.valueOf(plugin.minions().count(player.getUniqueId())), NamedTextColor.YELLOW)),
                plain("In storage: ", NamedTextColor.GRAY).append(plain(String.valueOf(profile.minionsHeld), NamedTextColor.YELLOW)),
                Component.empty(), plain("Place one: /minion place", NamedTextColor.DARK_GRAY))), c -> {
                    player.closeInventory();
                    plugin.minions().place(player);
                });

        view.set(28, soon(Material.BONE, "Pets", "Pets that follow you and add stats."), null);
        view.set(34, soon(Material.LEATHER, "Accessory Bag", "Talismans and accessories that add stats."), null);

        view.set(47, icon(Material.ENDER_EYE, plain("Fast Travel", NamedTextColor.AQUA), List.of(
                plain("Teleport to your island, the", NamedTextColor.GRAY), plain("hub and areas you discovered.", NamedTextColor.GRAY),
                Component.empty(), click("Click to pick a destination!"))), c -> openTravel(player));
        view.set(48, icon(Material.NAME_TAG, plain("Profile Management", NamedTextColor.GREEN), List.of(
                plain("Purse ", NamedTextColor.GRAY).append(plain(WalletService.shortAddress(profile.wallet), NamedTextColor.AQUA)),
                plain("SkyBlock Level ", NamedTextColor.GRAY).append(plain(String.valueOf(Skills.skyblockLevel(profile)), Skills.levelColor(Skills.skyblockLevel(profile)))),
                plain("Coins ", NamedTextColor.GRAY).append(plain(String.format("%,d", profile.coins), NamedTextColor.GOLD)),
                plain("SkyBlock Level rewards: ", NamedTextColor.GRAY).append(plain("SOON", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, true)),
                Component.empty(), click("Click to show deposit info!"))), c -> { player.closeInventory(); player.performCommand("deposit"); });
        view.set(49, icon(Material.BARRIER, plain("Close", NamedTextColor.RED), List.of()), c -> player.closeInventory());
        view.set(50, icon(Material.REDSTONE_TORCH, plain("Settings", NamedTextColor.GREEN), List.of(
                plain("Toggle the objective bar and", NamedTextColor.GRAY), plain("the menu star.", NamedTextColor.GRAY),
                Component.empty(), click("Click to open!"))), c -> openSettings(player));
        player.openInventory(view.inventory);
    }

    private static ItemStack glint(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        return stack;
    }

    private List<Component> statLines(Player player, Stats.Sheet sheet) {
        List<Component> out = new ArrayList<>();
        int[] mana = {plugin.stats().maxMana(player)};
        out.add(stat("❤", "Health", NamedTextColor.RED, String.valueOf(sheet.health())));
        out.add(stat("❈", "Defense", NamedTextColor.GREEN, String.valueOf(sheet.defense())));
        out.add(stat("✦", "Speed", NamedTextColor.WHITE, String.valueOf(sheet.speed())));
        out.add(stat("❁", "Strength", NamedTextColor.RED, String.valueOf(sheet.strength())));
        out.add(stat("✎", "Intelligence", NamedTextColor.AQUA, String.valueOf(sheet.intelligence())));
        out.add(stat("☣", "Crit Chance", NamedTextColor.BLUE, sheet.critChance() + "%"));
        out.add(stat("☠", "Crit Damage", NamedTextColor.BLUE, sheet.critDamage() + "%"));
        out.add(stat("✎", "Mana", NamedTextColor.AQUA, String.valueOf(mana[0])));
        return out;
    }

    // ------------------------------------------------------------------ stats & equipment
    void openStats(Player player) {
        Stats.Sheet sheet = plugin.stats().sheet(player);
        View view = page("Your Equipment and Stats");
        ItemStack[] armor = player.getInventory().getArmorContents();
        int[] armorSlots = {37, 28, 19, 10}; // boots, legs, chest, helmet
        String[] empty = {"Empty Boots Slot", "Empty Leggings Slot", "Empty Chestplate Slot", "Empty Helmet Slot"};
        for (int i = 0; i < 4; i++) {
            ItemStack piece = armor.length > i ? armor[i] : null;
            if (piece == null || piece.getType().isAir()) {
                view.set(armorSlots[i], icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, plain(empty[i], NamedTextColor.GRAY), List.of()), null);
            } else {
                view.set(armorSlots[i], piece.clone(), null);
            }
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        view.set(46, hand.getType().isAir() || SbMenu.isStar(plugin, hand)
                ? icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, plain("Empty Hand", NamedTextColor.GRAY), List.of()) : hand.clone(), null);
        record S(Material m, String sym, String name, NamedTextColor color, String value, String text) {
        }
        List<S> list = List.of(
                new S(Material.GOLDEN_APPLE, "❤", "Health", NamedTextColor.RED, String.valueOf(sheet.health()), "Your maximum health. Regenerates over time, faster out of combat."),
                new S(Material.IRON_CHESTPLATE, "❈", "Defense", NamedTextColor.GREEN, String.valueOf(sheet.defense()), "Reduces damage taken: damage × 100 / (100 + Defense)."),
                new S(Material.BLAZE_POWDER, "❁", "Strength", NamedTextColor.RED, String.valueOf(sheet.strength()), "Raises your damage: (5 + weapon) × (1 + Strength / 100)."),
                new S(Material.SUGAR, "✦", "Speed", NamedTextColor.WHITE, String.valueOf(sheet.speed()), "Your walk speed. 100 is the base walking speed."),
                new S(Material.ENCHANTED_BOOK, "✎", "Intelligence", NamedTextColor.AQUA, String.valueOf(sheet.intelligence()), "SOON: Intelligence will raise your maximum Mana. Item abilities use the fixed 100 Mana pool for now."),
                new S(Material.ARROW, "☣", "Crit Chance", NamedTextColor.BLUE, sheet.critChance() + "%", "Chance for a hit to critically strike."),
                new S(Material.DIAMOND, "☠", "Crit Damage", NamedTextColor.BLUE, sheet.critDamage() + "%", "Extra damage dealt by a critical hit."),
                new S(Material.IRON_SWORD, "⚔", "Weapon Damage", NamedTextColor.RED, String.valueOf(sheet.damage()), "Base damage of the item in your hand.")
        );
        int[] slots = {13, 14, 15, 16, 22, 23, 24, 25};
        for (int i = 0; i < list.size(); i++) {
            S s = list.get(i);
            List<Component> lore = new ArrayList<>(wrap(s.text(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(plain("Total: ", NamedTextColor.GRAY).append(plain(s.value(), s.color())));
            view.set(slots[i], icon(s.m(), plain(s.sym() + " " + s.name() + " ", s.color()).append(plain(s.value(), NamedTextColor.WHITE)), lore), null);
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        long hit = Math.round((5 + sheet.damage()) * (1 + sheet.strength() / 100.0));
        view.set(31, icon(Material.TARGET, plain("Damage per hit", NamedTextColor.GOLD), List.of(
                plain("Normal: ", NamedTextColor.GRAY).append(plain(String.valueOf(hit), NamedTextColor.RED)),
                plain("Critical: ", NamedTextColor.GRAY).append(plain(String.valueOf(Math.round(hit * (1 + sheet.critDamage() / 100.0))), NamedTextColor.RED)),
                plain("Kills: ", NamedTextColor.GRAY).append(plain(String.valueOf(profile.kills), NamedTextColor.YELLOW)),
                plain("Best arena wave: ", NamedTextColor.GRAY).append(plain(String.valueOf(profile.bestWave), NamedTextColor.YELLOW)))), null);
        view.set(34, soon(Material.GOLDEN_PICKAXE, "More Stats", "Mining Speed, Mining Fortune, Farming Fortune, Foraging Fortune, Magic Find, Sea Creature Chance, Ferocity, Attack Speed and True Defense."), null);
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private void openWardrobe(Player player) {
        View view = page("Wardrobe");
        ItemStack[] armor = player.getInventory().getArmorContents();
        int[] col = {40, 31, 22, 13};
        for (int i = 0; i < 4; i++) {
            ItemStack piece = armor.length > i ? armor[i] : null;
            view.set(col[i], piece == null || piece.getType().isAir()
                    ? icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, plain("Empty", NamedTextColor.GRAY), List.of(plain("Equip armor from your bag.", NamedTextColor.DARK_GRAY)))
                    : piece.clone(), null);
        }
        view.set(4, icon(Material.LEATHER_CHESTPLATE, plain("Current set", NamedTextColor.GREEN), List.of(
                plain("Your worn armor is part of your", NamedTextColor.GRAY), plain("on-chain bag (slots 36-39).", NamedTextColor.GRAY))), null);
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    // ------------------------------------------------------------------ skills
    void openSkills(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Your Skills");
        view.set(4, icon(Material.DIAMOND_SWORD, plain("Your Skills", NamedTextColor.GREEN), wrap("View your Skill progression and rewards.", NamedTextColor.GRAY)), null);
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        Skills.Skill[] all = Skills.Skill.values();
        for (int i = 0; i < all.length; i++) {
            Skills.Skill skill = all[i];
            view.set(slots[i], skillIcon(profile, skill), c -> openSkill(player, skill, 0));
        }
        view.set(30, soon(Material.BREWING_STAND, "Alchemy", "Brew potions to earn Alchemy XP."), null);
        view.set(31, soon(Material.LEAD, "Taming", "Level up pets to earn Taming XP."), null);
        view.set(32, soon(Material.MAGMA_CREAM, "Runecrafting", "Craft runes to earn Runecrafting XP."), null);
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private ItemStack skillIcon(Profile profile, Skills.Skill skill) {
        int level = Skills.level(profile, skill);
        int max = Skills.max(skill);
        long xp = Skills.xp(profile, skill);
        List<Component> lore = new ArrayList<>(wrap(skill.blurb, NamedTextColor.GRAY));
        if (plugin.skillChain().enabled()) {
            long pending = plugin.skillChain().pending(profile, skill);
            boolean synced = plugin.skillChain().loaded(profile.uuid);
            lore.add(plain("Onchain: " + String.format("%,d", Math.max(0, xp - pending)) + " XP"
                    + (pending > 0 ? " (+" + String.format("%,d", pending) + " queued)" : "")
                    + (synced ? "" : " · syncing"), NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        if (level >= max) {
            lore.add(plain("MAX LEVEL", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        } else {
            long from = Skills.start(skill, level);
            long to = Skills.start(skill, level + 1);
            lore.add(plain("Progress to Level " + ProgressMath.numeral(level + 1) + ": ", NamedTextColor.GRAY)
                    .append(plain(percent(xp - from, to - from), NamedTextColor.YELLOW)));
            lore.add(bar(xp - from, to - from));
            lore.add(Component.empty());
            lore.add(plain("Level " + ProgressMath.numeral(level + 1) + " Rewards:", NamedTextColor.GRAY));
            for (Component line : Skills.rewards(skill, level + 1)) {
                lore.add(plain("  ", NamedTextColor.GRAY).append(line));
            }
        }
        lore.add(Component.empty());
        lore.add(click("Click to view!"));
        String name = skill.label + (level > 0 ? " " + ProgressMath.numeral(level) : "");
        return icon(skill.icon, plain(name, NamedTextColor.GREEN), lore);
    }

    void openSkill(Player player, Skills.Skill skill, int pageIndex) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        int level = Skills.level(profile, skill);
        int max = Skills.max(skill);
        long xp = Skills.xp(profile, skill);
        View view = page(skill.label + " Skill");
        view.set(0, skillIcon(profile, skill), null);
        int first = pageIndex * PATH.length + 1;
        for (int i = 0; i < PATH.length; i++) {
            int lv = first + i;
            if (lv > max) {
                break;
            }
            Material pane;
            NamedTextColor color;
            if (lv <= level) {
                pane = Material.LIME_STAINED_GLASS_PANE;
                color = NamedTextColor.GREEN;
            } else if (lv == level + 1) {
                pane = Material.YELLOW_STAINED_GLASS_PANE;
                color = NamedTextColor.YELLOW;
            } else {
                pane = Material.RED_STAINED_GLASS_PANE;
                color = NamedTextColor.RED;
            }
            List<Component> lore = new ArrayList<>();
            if (lv == level + 1) {
                long from = Skills.start(skill, level);
                long to = Skills.start(skill, lv);
                lore.add(plain("Progress: ", NamedTextColor.GRAY).append(plain(percent(xp - from, to - from), NamedTextColor.YELLOW)));
                lore.add(bar(xp - from, to - from));
                lore.add(Component.empty());
            }
            lore.add(plain("Rewards:", NamedTextColor.GRAY));
            for (Component line : Skills.rewards(skill, lv)) {
                lore.add(plain("  ", NamedTextColor.GRAY).append(line));
            }
            lore.add(Component.empty());
            lore.add(lv <= level ? plain("UNLOCKED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
                    : plain("Requires " + Skills.compact(Skills.start(skill, lv)) + " total XP", NamedTextColor.DARK_GRAY));
            ItemStack stack = icon(pane, plain(skill.label + " Level " + ProgressMath.numeral(lv), color), lore);
            stack.setAmount(Math.max(1, Math.min(64, lv)));
            view.set(PATH[i], stack, null);
        }
        int pages = (max + PATH.length - 1) / PATH.length;
        if (pageIndex > 0) {
            view.set(45, icon(Material.ARROW, plain("Previous Page", NamedTextColor.GREEN), List.of(plain("Page " + pageIndex, NamedTextColor.YELLOW))),
                    c -> openSkill(player, skill, pageIndex - 1));
        }
        if (pageIndex + 1 < pages) {
            view.set(53, icon(Material.ARROW, plain("Next Page", NamedTextColor.GREEN), List.of(plain("Page " + (pageIndex + 2), NamedTextColor.YELLOW))),
                    c -> openSkill(player, skill, pageIndex + 1));
        }
        view.set(48, icon(Material.ARROW, plain("Go Back", NamedTextColor.GREEN), List.of(plain("To Your Skills", NamedTextColor.GRAY))), c -> openSkills(player));
        view.set(49, icon(Material.BARRIER, plain("Close", NamedTextColor.RED), List.of()), c -> player.closeInventory());
        player.openInventory(view.inventory);
    }

    // ------------------------------------------------------------------ collections
    void openCollections(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Collections");
        int unlocked = Collects.unlocked(profile);
        view.set(4, icon(Material.PAINTING, plain("Collections", NamedTextColor.GREEN), List.of(
                plain("Collections Unlocked: ", NamedTextColor.GRAY).append(plain(percent(unlocked, Collects.ENTRIES.size()), NamedTextColor.YELLOW)),
                bar(unlocked, Collects.ENTRIES.size()))), null);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43, 46, 47, 50, 51, 52};
        int i = 0;
        int[] tiers = ProgressMath.tiers();
        for (Collects.Entry entry : Collects.ENTRIES) {
            if (i >= slots.length) {
                break;
            }
            long amount = Collects.amount(profile, entry);
            if (amount <= 0) {
                view.set(slots[i++], icon(Material.GRAY_DYE, plain(entry.name(), NamedTextColor.RED), List.of(
                        plain(cap(entry.category().name()) + " Collection", NamedTextColor.DARK_GRAY),
                        Component.empty(),
                        plain("Find this item to unlock", NamedTextColor.GRAY), plain("its collection!", NamedTextColor.GRAY))), null);
                continue;
            }
            int tier = ProgressMath.tier(amount);
            List<Component> lore = new ArrayList<>();
            lore.add(plain(cap(entry.category().name()) + " Collection", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            lore.add(plain("Total Collected: ", NamedTextColor.GRAY).append(plain(String.format("%,d", amount), NamedTextColor.YELLOW)));
            if (tier + 1 < tiers.length) {
                lore.add(plain("Progress to " + entry.name() + " " + ProgressMath.roman(tier + 1) + ": ", NamedTextColor.GRAY)
                        .append(plain(percent(amount, tiers[tier + 1]), NamedTextColor.YELLOW)));
                lore.add(bar(amount, tiers[tier + 1]));
            } else {
                lore.add(plain("MAXED OUT", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            }
            lore.add(Component.empty());
            lore.add(plain("Tier rewards:", NamedTextColor.GRAY));
            lore.add(plain("+4 SkyBlock XP per tier", NamedTextColor.AQUA));
            lore.addAll(wrap(entry.reward(), NamedTextColor.DARK_GRAY));
            lore.add(plain("SOON", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, true));
            view.set(slots[i++], icon(entry.icon(), plain(entry.name() + " " + ProgressMath.roman(tier), NamedTextColor.YELLOW), lore), null);
        }
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ recipes, quests, calendar
    private void openRecipes(Player player) {
        View view = page("Recipe Book");
        int slot = 10;
        view.set(slot++, icon(Material.COBBLESTONE, plain("Enchanted Cobblestone", NamedTextColor.GREEN), List.of(
                plain("160× Cobblestone", NamedTextColor.GRAY), plain("Requires Cobblestone collection " + ProgressMath.CRAFT_COLLECTION, NamedTextColor.DARK_GRAY),
                Component.empty(), plain("/craft cobble", NamedTextColor.YELLOW))), null);
        view.set(slot++, icon(Material.OAK_LOG, plain("Enchanted Oak Log", NamedTextColor.GREEN), List.of(
                plain("160× Oak Log", NamedTextColor.GRAY), plain("Requires Oak collection " + ProgressMath.CRAFT_COLLECTION, NamedTextColor.DARK_GRAY),
                Component.empty(), plain("/craft oak", NamedTextColor.YELLOW))), null);
        for (Market.Recipe recipe : Market.RECIPES) {
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 44) {
                break;
            }
            List<Component> lore = new ArrayList<>();
            if (recipe.coins() > 0) {
                lore.add(plain(String.format("%,d", recipe.coins()) + " coins", NamedTextColor.GOLD));
            }
            for (Map.Entry<String, Integer> mat : recipe.mats().entrySet()) {
                lore.add(plain(mat.getValue() + "× " + pretty(mat.getKey()), NamedTextColor.GRAY));
            }
            if (recipe.level() > 0) {
                lore.add(plain("Requires Combat " + recipe.level(), NamedTextColor.RED));
            }
            lore.add(Component.empty());
            lore.add(plain("Forge at the Blacksmith (plaza).", NamedTextColor.YELLOW));
            view.set(slot++, icon(recipeIcon(recipe.result()), plain(pretty(recipe.result()), NamedTextColor.GREEN), lore), null);
        }
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private static Material recipeIcon(String key) {
        if (key.contains("helmet")) return Material.LEATHER_HELMET;
        if (key.contains("chestplate")) return Material.LEATHER_CHESTPLATE;
        if (key.contains("leggings")) return Material.LEATHER_LEGGINGS;
        if (key.contains("boots")) return Material.LEATHER_BOOTS;
        if (key.contains("flesh")) return Material.ROTTEN_FLESH;
        if (key.contains("cleaver")) return Material.IRON_AXE;
        return Material.IRON_SWORD;
    }

    private static String pretty(String key) {
        String k = key.substring(key.indexOf(':') + 1).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : k.split(" ")) {
            if (!w.isEmpty()) {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }

    private void openQuests(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Quest Log");
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < Quests.STEPS.size() && i < slots.length; i++) {
            Quests.Step step = Quests.STEPS.get(i);
            boolean done = i < profile.questStep;
            boolean now = i == profile.questStep;
            List<Component> lore = new ArrayList<>(wrap(step.hint(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            if (now) {
                lore.add(plain("Progress: ", NamedTextColor.GRAY).append(plain(Math.min(profile.questProgress, step.goal()) + "/" + step.goal(), NamedTextColor.YELLOW)));
                lore.add(bar(Math.min(profile.questProgress, step.goal()), step.goal()));
            }
            lore.add(plain("Reward: ", NamedTextColor.GRAY).append(plain(step.rewardText(), NamedTextColor.GOLD)));
            lore.add(plain("+10 SkyBlock XP", NamedTextColor.AQUA));
            Material m = done ? Material.LIME_DYE : now ? Material.WRITABLE_BOOK : Material.GRAY_DYE;
            view.set(slots[i], icon(m, plain((done ? "✔ " : now ? "➜ " : "") + step.title(), done ? NamedTextColor.GREEN : now ? NamedTextColor.YELLOW : NamedTextColor.RED), lore), null);
        }
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    void openCalendar(Player player) {
        World w = plugin.hub().world() != null ? plugin.hub().world() : player.getWorld();
        long t = w.getTime() % 24000;
        View view = page("Calendar and Events");
        long toNight = (13000 - t + 24000) % 24000;
        long toDay = (23000 - t + 24000) % 24000;
        boolean night = t >= 13000 && t < 23000;
        view.set(13, icon(Material.CLOCK, plain(Sidebar.season(w.getFullTime()), NamedTextColor.GREEN), List.of(
                plain("Time: ", NamedTextColor.GRAY).append(plain(Sidebar.clock(t), NamedTextColor.YELLOW)),
                plain("A SkyBlock day lasts 20 minutes.", NamedTextColor.DARK_GRAY),
                plain("A season lasts 31 days.", NamedTextColor.DARK_GRAY))), null);
        view.set(20, icon(Material.ZOMBIE_HEAD, plain("Nightfall in the Barrows", NamedTextColor.RED), List.of(
                plain(night ? "Happening now!" : "Starts in " + minutes(toNight), night ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                plain("Hollow Villagers rise in Ashen Barrows;", NamedTextColor.GRAY),
                plain("Bone Archers guard Thornkeep Ruins.", NamedTextColor.GRAY))), null);
        view.set(22, icon(Material.SUNFLOWER, plain("Daybreak", NamedTextColor.YELLOW), List.of(
                plain(night ? "In " + minutes(toDay) : "It is day.", NamedTextColor.YELLOW),
                plain("Night-only mobs leave at dawn.", NamedTextColor.GRAY))), null);
        view.set(24, icon(Material.GOLD_NUGGET, plain("Elite respawns", NamedTextColor.GOLD), List.of(
                plain("Gilded Wight (Hollow Crypts): every 2 min", NamedTextColor.GRAY),
                plain("Alpha Direwolf (Thornkeep): every 2 min", NamedTextColor.GRAY),
                plain("Timers start when the elite dies.", NamedTextColor.DARK_GRAY))), null);
        view.set(31, icon(Material.NETHER_STAR, plain("Colosseum ranked runs", NamedTextColor.LIGHT_PURPLE), List.of(
                plain("Any time. Loot rolls use Somnia VRF.", NamedTextColor.GRAY))), null);
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private static String minutes(long ticks) {
        long seconds = ticks / 20;
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    // ------------------------------------------------------------------ fast travel, settings
    void openTravel(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Fast Travel");
        view.set(10, icon(Material.GRASS_BLOCK, plain("Private Island", NamedTextColor.GREEN), List.of(plain("Your home island.", NamedTextColor.GRAY), Component.empty(), click("Click to warp!"))),
                c -> { player.closeInventory(); player.performCommand("island"); });
        view.set(11, icon(Material.NETHER_STAR, plain("Hub", NamedTextColor.GREEN), List.of(plain(plugin.hub().name("plaza") + " spawn.", NamedTextColor.GRAY), Component.empty(), click("Click to warp!"))),
                c -> { player.closeInventory(); player.performCommand("hub"); });
        Zones zones = plugin.hub().zones();
        int[] slots = {12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30};
        int i = 0;
        if (zones != null) {
            for (Zones.District d : zones.districts()) {
                if (i >= slots.length) {
                    break;
                }
                boolean known = profile.discovered.contains(d.id());
                if (!known) {
                    view.set(slots[i++], icon(Material.GRAY_DYE, plain(d.name(), NamedTextColor.RED), List.of(plain("Discover this area on foot", NamedTextColor.GRAY), plain("to unlock its warp.", NamedTextColor.GRAY))), null);
                    continue;
                }
                Material m = d.combat() ? Material.IRON_SWORD : Material.MAP;
                view.set(slots[i++], icon(m, plain(d.name(), d.combat() ? NamedTextColor.RED : NamedTextColor.AQUA), List.of(
                        plain(d.combat() ? "Combat zone" : "Peaceful zone", NamedTextColor.GRAY), Component.empty(), click("Click to warp!"))),
                        c -> warp(player, d));
            }
        }
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private void warp(Player player, Zones.District d) {
        World world = plugin.hub().world();
        if (world == null) {
            return;
        }
        player.closeInventory();
        if ("colosseum".equals(d.id())) {
            plugin.hub().toColosseum(player);
            return;
        }
        Location at = plugin.hub().warpPoint(d.id());
        if (at == null) {
            int y = world.getHighestBlockYAt(d.cx(), d.cz());
            at = new Location(world, d.cx() + 0.5, y + 1, d.cz() + 0.5);
        }
        player.teleport(at);
        player.setFallDistance(0);
        player.sendMessage(Component.text("Warped to " + d.name() + ".", NamedTextColor.GREEN));
    }

    private void openSettings(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Settings");
        view.set(20, icon(profile.bossbarOff ? Material.GRAY_DYE : Material.LIME_DYE, plain("Objective Bar", NamedTextColor.GREEN), List.of(
                plain("Shows your next objective at the", NamedTextColor.GRAY), plain("top of the screen.", NamedTextColor.GRAY), Component.empty(),
                profile.bossbarOff ? plain("DISABLED", NamedTextColor.RED) : plain("ENABLED", NamedTextColor.GREEN), click("Click to toggle!"))), c -> {
                    profile.bossbarOff = !profile.bossbarOff;
                    plugin.profiles().save(profile);
                    plugin.sidebar().show(player, profile);
                    openSettings(player);
                });
        view.set(24, icon(profile.menuItemOff ? Material.GRAY_DYE : Material.LIME_DYE, plain("SkyBlock Menu Star", NamedTextColor.GREEN), List.of(
                plain("Keep the Nether Star in your last", NamedTextColor.GRAY), plain("hotbar slot. /sbmenu always works.", NamedTextColor.GRAY), Component.empty(),
                profile.menuItemOff ? plain("DISABLED", NamedTextColor.RED) : plain("ENABLED", NamedTextColor.GREEN), click("Click to toggle!"))), c -> {
                    profile.menuItemOff = !profile.menuItemOff;
                    plugin.profiles().save(profile);
                    give(player);
                    openSettings(player);
                });
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }
}
