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

    /** Holds an empty slot 9 with a placeholder while items are added; run the result to free it again. */
    static Runnable reserveStar(Player player) {
        ItemStack current = player.getInventory().getItem(STAR_SLOT);
        if (current != null && !current.getType().isAir()) {
            return () -> { };
        }
        ItemStack hold = new ItemStack(Material.BARRIER);
        player.getInventory().setItem(STAR_SLOT, hold);
        return () -> {
            ItemStack now = player.getInventory().getItem(STAR_SLOT);
            if (now != null && now.getType() == Material.BARRIER && !now.hasItemMeta()) {
                player.getInventory().setItem(STAR_SLOT, null);
            }
        };
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
            case "levels" -> openLevels(player);
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
        return (p == Math.floor(p) ? String.valueOf((int) p) : String.format(java.util.Locale.ROOT, "%.1f", p)) + "%";
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
        plugin.quests().hit(player, "open_menu", 1);
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
        sl.add(plain(String.format(java.util.Locale.ROOT, "%.1f", avg) + " Skill Avg.", NamedTextColor.GOLD));
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
        view.set(22, levelingIcon(profile), c -> openLevels(player));
        view.set(46, icon(Material.EMERALD, plain("Trades", NamedTextColor.GREEN), tl), c -> {
            player.closeInventory();
            player.sendMessage(Component.text("Stand next to a player and type /trade <name>. Both of you lock in before the swap.", NamedTextColor.YELLOW));
        });

        Quests.Step step = plugin.quests().current(profile);
        List<Component> ql = new ArrayList<>(wrap("View your active quests, progress, and rewards.", NamedTextColor.GRAY));
        ql.add(Component.empty());
        ql.add(plain("Completed: ", NamedTextColor.GRAY).append(plain(Quests.completed(profile) + "/" + Quests.STEPS.size(), NamedTextColor.YELLOW)));
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
                c -> plugin.craft().open(player));
        view.set(32, icon(Material.LEATHER_CHESTPLATE, plain("Wardrobe", NamedTextColor.GREEN), List.of(
                plain("See the armor you wear and", NamedTextColor.GRAY), plain("what each piece adds.", NamedTextColor.GRAY),
                Component.empty(), click("Click to view!"))),
                c -> openWardrobe(player));
        if (Minions.ENABLED) {
            view.set(33, icon(Material.COBBLESTONE, plain("Minions", NamedTextColor.GREEN), List.of(
                    plain("Minions work your island while", NamedTextColor.GRAY), plain("you are away.", NamedTextColor.GRAY),
                    Component.empty(), plain("Placed: ", NamedTextColor.GRAY).append(plain(String.valueOf(plugin.minions().count(player.getUniqueId())), NamedTextColor.YELLOW)),
                    plain("In storage: ", NamedTextColor.GRAY).append(plain(String.valueOf(profile.minionsHeld), NamedTextColor.YELLOW)),
                    Component.empty(), plain("Place one: /minion place", NamedTextColor.DARK_GRAY))), c -> {
                        player.closeInventory();
                        plugin.minions().place(player);
                    });
        } else {
            view.set(33, soon(Material.COBBLESTONE, "Minions", "Minions that work your island while you are away."), null);
        }

        view.set(28, soon(Material.BONE, "Pets", "Pets that follow you and add stats."), null);
        view.set(34, soon(Material.LEATHER, "Accessory Bag", "Talismans and accessories that add stats."), null);

        view.set(47, icon(Material.ENDER_EYE, plain("Fast Travel", NamedTextColor.AQUA), List.of(
                plain("Teleport to your island, the", NamedTextColor.GRAY), plain("hub and areas you discovered.", NamedTextColor.GRAY),
                Component.empty(), click("Click to pick a destination!"))), c -> openTravel(player));
        view.set(48, icon(Material.NAME_TAG, plain("Profile Management", NamedTextColor.GREEN), List.of(
                plain("Purse ", NamedTextColor.GRAY).append(plain(WalletService.shortAddress(profile.wallet), NamedTextColor.AQUA)),
                plain("SkyBlock Level ", NamedTextColor.GRAY).append(plain(String.valueOf(Skills.skyblockLevel(profile)), Skills.levelColor(Skills.skyblockLevel(profile)))),
                plain("Coins ", NamedTextColor.GRAY).append(plain(String.format(java.util.Locale.ROOT, "%,d", profile.coins), NamedTextColor.GOLD)),
                plain("SkyBlock Level rewards: ", NamedTextColor.GRAY).append(plain("SOON", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, true)),
                Component.empty(), click("Click to manage your profile!"))), c -> openProfile(player));
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
    private static Component statRow(String symbol, String name, NamedTextColor color, String value, boolean live) {
        Component row = plain((symbol.isEmpty() ? "" : symbol + " ") + name + ": ", color).append(plain(value, NamedTextColor.WHITE));
        return live ? row : row.append(plain(" SOON", NamedTextColor.DARK_GRAY));
    }

    private ItemStack category(Material material, String title, NamedTextColor color, String about, List<Component> rows) {
        List<Component> lore = new ArrayList<>(wrap(about, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.addAll(rows);
        lore.add(Component.empty());
        lore.add(click("Click for details!"));
        return icon(material, plain(title, color), lore);
    }

    void openStats(Player player) {
        Stats.Sheet sheet = plugin.stats().sheet(player);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Stats & Equipment");
        // Left column: armor (helmet at the top).
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
        // Middle column: equipment frames. Not in v1.
        int[] gearSlots = {11, 20, 29, 38};
        String[] gearNames = {"Necklace Slot", "Cloak Slot", "Belt Slot", "Gloves Slot"};
        for (int i = 0; i < 4; i++) {
            view.set(gearSlots[i], soon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, gearNames[i], "Equipment pieces will fit here and add their stats."), null);
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        view.set(47, hand.getType().isAir() || SbMenu.isStar(plugin, hand)
                ? icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, plain("Empty Hand", NamedTextColor.GRAY), List.of(plain("Hold a weapon to see its damage here.", NamedTextColor.GRAY)))
                : hand.clone(), null);
        // Right side: stat categories.
        long hit = (long) Math.floor((5 + sheet.damage()) * (1 + sheet.strength() / 100.0) * (1 + Stats.warrior(Stats.combatLevel(profile.combatXp)) / 100.0));
        view.set(14, category(Material.DIAMOND_SWORD, "Combat Stats", NamedTextColor.RED, "What keeps you alive and how hard you hit.", List.of(
                statRow("❤", "Health", NamedTextColor.RED, String.valueOf(sheet.health()), true),
                statRow("❈", "Defense", NamedTextColor.GREEN, String.valueOf(sheet.defense()), true),
                statRow("❁", "Strength", NamedTextColor.RED, String.valueOf(sheet.strength()), true),
                statRow("✎", "Intelligence", NamedTextColor.AQUA, String.valueOf(sheet.intelligence()), true),
                statRow("☣", "Crit Chance", NamedTextColor.BLUE, sheet.critChance() + "%", true),
                statRow("☠", "Crit Damage", NamedTextColor.BLUE, sheet.critDamage() + "%", true),
                statRow("⚔", "Bonus Attack Speed", NamedTextColor.YELLOW, "0%", false),
                statRow("", "True Defense", NamedTextColor.WHITE, "0", false),
                statRow("", "Ferocity", NamedTextColor.RED, "0", false),
                statRow("", "Swing Range", NamedTextColor.YELLOW, "3", false),
                statRow("", "Health Regen", NamedTextColor.RED, "100", true),
                statRow("", "Vitality", NamedTextColor.DARK_RED, "100", false),
                statRow("", "Mending", NamedTextColor.GREEN, "100", false),
                Component.empty(),
                plain("Hit: ", NamedTextColor.GRAY).append(plain(String.valueOf(hit), NamedTextColor.RED))
                        .append(plain("  Crit: ", NamedTextColor.GRAY)).append(plain(String.valueOf((long) Math.floor(hit * (1 + sheet.critDamage() / 100.0))), NamedTextColor.RED))
        )), c -> combatDetails(player));
        view.set(15, category(Material.DIAMOND_PICKAXE, "Gathering Stats", NamedTextColor.GOLD, "How fast you break blocks and how much they drop.", List.of(
                statRow("", "Mining Speed", NamedTextColor.GOLD, "0", false),
                statRow("", "Mining Fortune", NamedTextColor.GOLD, "0", false),
                statRow("", "Breaking Power", NamedTextColor.DARK_GREEN, "0", false)
        )), null);
        view.set(16, category(Material.ENCHANTED_BOOK, "Wisdom Stats", NamedTextColor.DARK_AQUA, "Extra skill XP from every source.", List.of(
                statRow("", "Combat Wisdom", NamedTextColor.DARK_AQUA, "0", false),
                statRow("", "Mining Wisdom", NamedTextColor.DARK_AQUA, "0", false),
                statRow("", "Farming Wisdom", NamedTextColor.DARK_AQUA, "0", false),
                statRow("", "Foraging Wisdom", NamedTextColor.DARK_AQUA, "0", false),
                statRow("", "Fishing Wisdom", NamedTextColor.DARK_AQUA, "0", false),
                statRow("", "Enchanting Wisdom", NamedTextColor.DARK_AQUA, "0", false),
                statRow("", "Carpentry Wisdom", NamedTextColor.DARK_AQUA, "0", false)
        )), null);
        view.set(23, category(Material.GOLDEN_HOE, "Farming Stats", NamedTextColor.GOLD, "Bigger harvests from every crop.", List.of(
                statRow("", "Farming Fortune", NamedTextColor.GOLD, "0", false)
        )), null);
        view.set(24, category(Material.OAK_LEAVES, "Foraging Stats", NamedTextColor.GOLD, "More logs from every tree.", List.of(
                statRow("", "Foraging Fortune", NamedTextColor.GOLD, "0", false)
        )), null);
        view.set(25, category(Material.FISHING_ROD, "Fishing Stats", NamedTextColor.AQUA, "Better luck at the water's edge.", List.of(
                statRow("", "Sea Creature Chance", NamedTextColor.DARK_AQUA, "0%", false),
                statRow("", "Fishing Speed", NamedTextColor.AQUA, "0", false),
                statRow("", "Treasure Chance", NamedTextColor.GOLD, "0%", false)
        )), null);
        view.set(32, category(Material.CLOCK, "Misc Stats", NamedTextColor.LIGHT_PURPLE, "Everything else that shapes your run.", List.of(
                statRow("✦", "Speed", NamedTextColor.WHITE, String.valueOf(sheet.speed()), true),
                statRow("✎", "Mana", NamedTextColor.AQUA, String.valueOf(plugin.stats().maxMana(player)), true),
                statRow("", "Magic Find", NamedTextColor.AQUA, "0", false),
                statRow("", "Pet Luck", NamedTextColor.LIGHT_PURPLE, "0", false)
        )), null);
        // Bottom row: back, close, active effects.
        List<Component> fx = new ArrayList<>();
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            String n = effect.getType().getKey().getKey().replace('_', ' ');
            fx.add(plain(Character.toUpperCase(n.charAt(0)) + n.substring(1) + " " + ProgressMath.numeral(effect.getAmplifier() + 1), NamedTextColor.GREEN)
                    .append(plain(effect.isInfinite() ? "" : " " + (effect.getDuration() / 20) + "s", NamedTextColor.GRAY)));
        }
        if (fx.isEmpty()) {
            fx.add(plain("No effects are active.", NamedTextColor.GRAY));
        }
        view.set(50, icon(Material.POTION, plain("Active Effects", NamedTextColor.GREEN), fx), null);
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    /** Where your combat numbers come from (Click for details on Combat Stats). */
    private void combatDetails(Player player) {
        Stats.Sheet sheet = plugin.stats().sheet(player);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        int sb = Skills.skyblockLevel(profile);
        int combat = Stats.combatLevel(profile.combatXp);
        View view = page("Combat Stats");
        view.set(13, icon(Material.RED_DYE, plain("❤ Health " + sheet.health(), NamedTextColor.RED), List.of(
                plain("Base: 100", NamedTextColor.GRAY),
                plain("SkyBlock Level " + sb + ": +" + sb * 5, NamedTextColor.GRAY),
                plain("Farming: +" + Skills.statTotal(Skills.Skill.FARMING, Skills.level(profile, Skills.Skill.FARMING)), NamedTextColor.GRAY),
                plain("Fishing: +" + Skills.statTotal(Skills.Skill.FISHING, Skills.level(profile, Skills.Skill.FISHING)), NamedTextColor.GRAY),
                plain("Carpentry: +" + Skills.statTotal(Skills.Skill.CARPENTRY, Skills.level(profile, Skills.Skill.CARPENTRY)), NamedTextColor.GRAY),
                plain("Armor and the rest: +" + Math.max(0, sheet.health() - 100 - sb * 5 - Skills.perk(profile, "❤ Health")), NamedTextColor.GRAY))), null);
        view.set(21, icon(Material.LIME_DYE, plain("❈ Defense " + sheet.defense(), NamedTextColor.GREEN), List.of(
                plain("Mining: +" + Skills.statTotal(Skills.Skill.MINING, Skills.level(profile, Skills.Skill.MINING)), NamedTextColor.GRAY),
                plain("Armor: +" + Math.max(0, sheet.defense() - Skills.perk(profile, "❈ Defense")), NamedTextColor.GRAY),
                Component.empty(),
                plain("Damage taken: × 100 / (100 + Defense)", NamedTextColor.DARK_GRAY))), null);
        view.set(22, icon(Material.BLAZE_POWDER, plain("❁ Strength " + sheet.strength(), NamedTextColor.RED), List.of(
                plain("SkyBlock Level " + sb + ": +" + sb / 5, NamedTextColor.GRAY),
                plain("Foraging: +" + Skills.statTotal(Skills.Skill.FORAGING, Skills.level(profile, Skills.Skill.FORAGING)), NamedTextColor.GRAY),
                plain("Gear: +" + Math.max(0, sheet.strength() - sb / 5 - Skills.perk(profile, "❁ Strength")), NamedTextColor.GRAY))), null);
        view.set(23, icon(Material.ARROW, plain("☣ Crit Chance " + sheet.critChance() + "%", NamedTextColor.BLUE), List.of(
                plain("Base: 30%", NamedTextColor.GRAY),
                plain("Combat " + combat + ": +" + combat / 2 + "%", NamedTextColor.GRAY))), null);
        view.set(31, icon(Material.IRON_SWORD, plain("Warrior", NamedTextColor.GOLD), List.of(
                plain("Combat " + combat + ": +" + Stats.warrior(combat) + "% damage to mobs", NamedTextColor.GRAY))), null);
        view.set(48, icon(Material.ARROW, plain("Go Back", NamedTextColor.GREEN), List.of(plain("To Stats & Equipment", NamedTextColor.GRAY))), c -> openStats(player));
        view.set(49, icon(Material.BARRIER, plain("Close", NamedTextColor.RED), List.of()), c -> player.closeInventory());
        player.openInventory(view.inventory);
    }

    // ------------------------------------------------------------------ SkyBlock Leveling
    /** SkyBlock XP earned from skill level-ups, out of the v1 maximum (all skills at the cap). */
    static int[] coreSkills(Profile profile) {
        int have = 0;
        int max = 0;
        for (Skills.Skill s : Skills.Skill.values()) {
            int level = Skills.level(profile, s);
            for (int l = 1; l <= Skills.max(s); l++) {
                int xp = Skills.sbXpFor(l);
                max += xp;
                if (l <= level) {
                    have += xp;
                }
            }
        }
        return new int[]{have, max};
    }

    static int[] coreCollections(Profile profile) {
        int have = 0;
        int max = 0;
        int tiers = ProgressMath.tiers().length - 1;
        for (Collects.Entry entry : Collects.ENTRIES) {
            have += 4 * ProgressMath.tier(Collects.shared(profile, entry));
            max += 4 * tiers;
        }
        return new int[]{have, max};
    }

    static int areasXp(Profile profile) {
        int xp = 0;
        for (String key : profile.discovered) {
            if (!key.contains(":")) {
                xp += 5;
            }
        }
        return xp;
    }

    ItemStack levelingIcon(Profile profile) {
        int xp = Skills.skyblockXp(profile);
        int level = xp / 100;
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Your SkyBlock Level: ", NamedTextColor.GRAY).append(plain("[", NamedTextColor.DARK_GRAY))
                .append(plain(String.valueOf(level), Skills.levelColor(level))).append(plain("]", NamedTextColor.DARK_GRAY)));
        lore.add(Component.empty());
        lore.addAll(wrap("Skills, collections and quests all add SkyBlock XP. Every 100 XP is one level.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(plain("Progress to Level " + (level + 1) + ": ", NamedTextColor.GRAY).append(plain(percent(xp % 100, 100), NamedTextColor.YELLOW)));
        lore.add(bar(xp % 100, 100));
        lore.add(Component.empty());
        lore.add(plain("Also accessible via /levels", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(click("Click to view!"));
        return icon(Material.EXPERIENCE_BOTTLE, plain("SkyBlock Leveling", NamedTextColor.GREEN), lore);
    }

    private ItemStack taskIcon(Material material, String name, String about, int have, int max) {
        List<Component> lore = new ArrayList<>(wrap(about, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(plain("Progress to Complete: ", NamedTextColor.GRAY).append(plain(percent(have, max), NamedTextColor.YELLOW)));
        lore.add(bar(have, max));
        lore.add(Component.empty());
        lore.add(click("Click to view!"));
        return icon(material, plain(name, NamedTextColor.GREEN), lore);
    }

    void openLevels(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        int xp = Skills.skyblockXp(profile);
        int level = xp / 100;
        View view = page("SkyBlock Leveling");
        int[] skills = coreSkills(profile);
        int[] cols = coreCollections(profile);
        int story = Quests.SKYBLOCK_XP * Quests.xpSteps(profile);
        int storyMax = Quests.SKYBLOCK_XP * (Quests.STEPS.size() + Math.max(0, profile.questRetired));
        int areas = areasXp(profile);
        int total = skills[1] + cols[1] + storyMax + areas;
        List<Component> rank = new ArrayList<>();
        rank.add(plain("Your level: ", NamedTextColor.GRAY).append(plain(String.valueOf(level), Skills.levelColor(level))));
        rank.add(plain("You have ", NamedTextColor.GRAY).append(plain(String.format(java.util.Locale.ROOT, "%,d", xp) + " XP", NamedTextColor.AQUA)));
        rank.add(plain("Tasks done: ", NamedTextColor.GRAY).append(plain(percent(xp, total), NamedTextColor.YELLOW)));
        rank.add(Component.empty());
        rank.addAll(wrap("A ranking against other players opens at SkyBlock Level 10.", NamedTextColor.DARK_GRAY));
        rank.add(Component.empty());
        rank.add(plain("SOON", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, true));
        view.set(4, icon(Material.PLAYER_HEAD, plain("Your SkyBlock Level Ranking", NamedTextColor.GOLD), rank), null);
        // Level path: finished green, current yellow, later red.
        int startLevel = Math.max(1, level - 2);
        int[] path = {19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < path.length; i++) {
            int lv = startLevel + i;
            Material pane;
            NamedTextColor color;
            List<Component> lore = new ArrayList<>();
            if (lv <= level) {
                pane = Material.LIME_STAINED_GLASS_PANE;
                color = NamedTextColor.GREEN;
            } else if (lv == level + 1) {
                pane = Material.YELLOW_STAINED_GLASS_PANE;
                color = NamedTextColor.YELLOW;
                lore.add(plain("Progress: ", NamedTextColor.GRAY).append(plain((xp % 100) + "/100 XP", NamedTextColor.YELLOW)));
                lore.add(bar(xp % 100, 100));
                lore.add(Component.empty());
            } else {
                pane = Material.RED_STAINED_GLASS_PANE;
                color = NamedTextColor.RED;
            }
            lore.add(plain("Rewards:", NamedTextColor.GRAY));
            lore.add(plain("  +5 ", NamedTextColor.DARK_GRAY).append(plain("❤ Health", NamedTextColor.RED)));
            if (lv % 5 == 0) {
                lore.add(plain("  +1 ", NamedTextColor.DARK_GRAY).append(plain("❁ Strength", NamedTextColor.RED)));
            }
            if (lv == 10 || lv == 40 || lv == 80 || lv == 120 || lv == 160 || lv == 200) {
                lore.add(plain("  New name colour: ", NamedTextColor.DARK_GRAY).append(plain("[" + lv + "]", Skills.levelColor(lv))));
            }
            ItemStack stack = icon(pane, plain("Level " + lv, color), lore);
            stack.setAmount(Math.max(1, Math.min(64, lv)));
            view.set(path[i], stack, null);
        }
        view.set(29, taskIcon(Material.NETHER_STAR, "Core Tasks", "Skill level-ups, collection milestones and the areas you discover.",
                skills[0] + cols[0] + areas, skills[1] + cols[1] + areas), c -> openCoreTasks(player));
        view.set(30, taskIcon(Material.WRITABLE_BOOK, "Story Tasks", "The intro quest line. Each objective you finish is worth +" + Quests.SKYBLOCK_XP + " XP.",
                story, storyMax), c -> openQuests(player));
        view.set(31, icon(Material.GOLD_BLOCK, plain("Leveling Rewards", NamedTextColor.GREEN), List.of(
                plain("Every level: ", NamedTextColor.GRAY).append(plain("+5 ❤ Health", NamedTextColor.RED)),
                plain("Every 5 levels: ", NamedTextColor.GRAY).append(plain("+1 ❁ Strength", NamedTextColor.RED)),
                plain("Name colour at 10, 40, 80, 120, 160, 200", NamedTextColor.GRAY),
                Component.empty(),
                plain("Earned now: ", NamedTextColor.GRAY).append(plain("+" + level * 5 + " ❤", NamedTextColor.RED))
                        .append(plain(" +" + level / 5 + " ❁", NamedTextColor.RED)))), null);
        view.set(32, soon(Material.CAKE, "Event Tasks", "SkyBlock XP from seasonal events."), null);
        view.set(33, soon(Material.WITHER_SKELETON_SKULL, "Dungeon Tasks", "SkyBlock XP from dungeon runs."), null);
        view.set(37, soon(Material.GLOWSTONE_DUST, "Essence Shop Tasks", "SkyBlock XP from essence upgrades."), null);
        view.set(38, soon(Material.ROTTEN_FLESH, "Slaying Tasks", "SkyBlock XP from boss slaying."), null);
        view.set(39, soon(Material.IRON_PICKAXE, "Mining Tasks", "SkyBlock XP from deep mining progress."), null);
        view.set(40, soon(Material.WHEAT, "Garden Tasks", "SkyBlock XP from a farming garden."), null);
        view.set(41, soon(Material.SALMON, "Trophy Fishing Tasks", "SkyBlock XP from rare catches."), null);
        view.set(42, soon(Material.MAP, "Miscellaneous Tasks", "SkyBlock XP from everything else."), null);
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private void openCoreTasks(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Core Tasks");
        int[] skills = coreSkills(profile);
        int[] cols = coreCollections(profile);
        List<Component> sl = new ArrayList<>(wrap("Reach new skill levels. Levels 1-10 give +5 XP, 11-25 give +10 XP.", NamedTextColor.GRAY));
        sl.add(Component.empty());
        for (Skills.Skill s : Skills.Skill.values()) {
            sl.add(plain(s.label + " " + Skills.level(profile, s) + "/" + Skills.max(s), NamedTextColor.GRAY));
        }
        sl.add(Component.empty());
        sl.add(plain("Earned: ", NamedTextColor.GRAY).append(plain(skills[0] + "/" + skills[1] + " XP", NamedTextColor.AQUA)));
        view.set(20, icon(Material.DIAMOND_SWORD, plain("Skill Level Up", NamedTextColor.GREEN), sl), c -> openSkills(player));
        List<Component> cl = new ArrayList<>(wrap("Each collection milestone you reach gives +4 XP.", NamedTextColor.GRAY));
        cl.add(Component.empty());
        cl.add(plain("Unlocked: ", NamedTextColor.GRAY).append(plain(Collects.unlocked(profile) + "/" + Collects.ENTRIES.size(), NamedTextColor.YELLOW)));
        cl.add(plain("Earned: ", NamedTextColor.GRAY).append(plain(cols[0] + "/" + cols[1] + " XP", NamedTextColor.AQUA)));
        view.set(22, icon(Material.PAINTING, plain("Collections", NamedTextColor.GREEN), cl), c -> openCollections(player));
        view.set(24, icon(Material.FILLED_MAP, plain("Areas Discovered", NamedTextColor.GREEN), List.of(
                plain("Walk into a new hub area for +5 XP.", NamedTextColor.GRAY),
                Component.empty(),
                plain("Earned: ", NamedTextColor.GRAY).append(plain(areasXp(profile) + " XP", NamedTextColor.AQUA)))), null);
        view.set(48, icon(Material.ARROW, plain("Go Back", NamedTextColor.GREEN), List.of(plain("To SkyBlock Leveling", NamedTextColor.GRAY))), c -> openLevels(player));
        view.set(49, icon(Material.BARRIER, plain("Close", NamedTextColor.RED), List.of()), c -> player.closeInventory());
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
            lore.add(plain("Onchain: " + String.format(java.util.Locale.ROOT, "%,d", Math.max(0, xp - pending)) + " XP"
                    + (pending > 0 ? " (+" + String.format(java.util.Locale.ROOT, "%,d", pending) + " queued)" : "")
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
            long amount = Collects.shared(profile, entry);
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
            lore.add(plain("Total Collected: ", NamedTextColor.GRAY).append(plain(String.format(java.util.Locale.ROOT, "%,d", amount), NamedTextColor.YELLOW)));
            List<Profile> group = Coops.group(profile);
            if (group.size() > 1) {
                lore.add(Component.empty());
                lore.add(plain("Co-op Contributions:", NamedTextColor.GRAY));
                for (Profile member : group) {
                    lore.add(plain(Coops.name(member.uuid) + ": ", NamedTextColor.AQUA)
                            .append(plain(String.format(java.util.Locale.ROOT, "%,d", Collects.amount(member, entry)), NamedTextColor.YELLOW)));
                }
            }
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
                lore.add(plain(String.format(java.util.Locale.ROOT, "%,d", recipe.coins()) + " coins", NamedTextColor.GOLD));
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
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < Quests.STEPS.size() && i < slots.length; i++) {
            Quests.Step step = Quests.STEPS.get(i);
            boolean done = Quests.done(profile, i);
            boolean now = i == profile.questStep;
            List<Component> lore = new ArrayList<>(wrap(step.hint(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            if (now && step.goal() > 1) {
                lore.add(plain("Progress: ", NamedTextColor.GRAY).append(plain(Math.min(profile.questProgress, step.goal()) + "/" + step.goal(), NamedTextColor.YELLOW)));
                lore.add(bar(Math.min(profile.questProgress, step.goal()), step.goal()));
            }
            lore.add(plain("Reward: ", NamedTextColor.GRAY).append(plain(step.rewardText(), NamedTextColor.GOLD)));
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
                    view.set(slots[i++], icon(Material.GRAY_DYE, plain(d.name(), NamedTextColor.RED), List.of(
                            plain("LOCKED", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                            plain("Visit this location first.", NamedTextColor.GRAY),
                            plain("Walk there once to unlock its warp.", NamedTextColor.DARK_GRAY))),
                            c -> player.sendMessage(Component.text("Visit " + d.name() + " first: walk there once to unlock its warp.", NamedTextColor.RED)));
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
        // Fast travel only to places this profile has visited on foot (checked again here: a menu can be stale).
        if (!plugin.profiles().get(player.getUniqueId()).discovered.contains(d.id())) {
            player.sendMessage(Component.text("Visit " + d.name() + " first: walk there once to unlock its warp.", NamedTextColor.RED));
            return;
        }
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

    // ------------------------------------------------------------------ profile management
    void openProfile(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        View view = page("Profile Management");
        view.set(13, icon(Material.NAME_TAG, plain("Your Profile", NamedTextColor.GREEN), List.of(
                plain("Purse ", NamedTextColor.GRAY).append(plain(WalletService.shortAddress(profile.wallet), NamedTextColor.AQUA)),
                plain("SkyBlock Level ", NamedTextColor.GRAY).append(plain(String.valueOf(Skills.skyblockLevel(profile)), Skills.levelColor(Skills.skyblockLevel(profile)))),
                plain("Coins ", NamedTextColor.GRAY).append(plain(String.format(java.util.Locale.ROOT, "%,d", profile.coins), NamedTextColor.GOLD)),
                plain("Profile ", NamedTextColor.GRAY).append(coopLabel(profile)))), null);
        view.set(31, icon(Material.PLAYER_HEAD, plain("Co-op", NamedTextColor.GREEN), List.of(
                plain("Play together on one island with", NamedTextColor.GRAY), plain("shared collections (up to " + Coops.MAX_MEMBERS + ").", NamedTextColor.GRAY),
                plain("Skills, purse and bag stay yours.", NamedTextColor.GRAY), Component.empty(),
                plain("Members: ", NamedTextColor.GRAY).append(plain(Coops.group(profile).size() + "/" + Coops.MAX_MEMBERS, NamedTextColor.YELLOW)),
                Component.empty(), click("Click to manage!"))), c -> openCoop(player));
        view.set(29, icon(Material.GOLD_INGOT, plain("Deposit Info", NamedTextColor.GOLD), List.of(
                plain("Your purse address and the", NamedTextColor.GRAY), plain("faucet for STT gas.", NamedTextColor.GRAY),
                Component.empty(), click("Click to show!"))), c -> { player.closeInventory(); player.performCommand("deposit"); });
        view.set(33, icon(Material.TNT, plain("Reset Profile", NamedTextColor.RED), List.of(
                plain("Start over from zero: skills,", NamedTextColor.GRAY), plain("collections, quests, coins, bag", NamedTextColor.GRAY),
                plain("and island are wiped, in game", NamedTextColor.GRAY), plain("and onchain. Your purse and its", NamedTextColor.GRAY),
                plain("STT stay yours.", NamedTextColor.GRAY), Component.empty(),
                plain("Your event leaderboard count", NamedTextColor.GRAY), plain("starts again from zero.", NamedTextColor.GRAY),
                Component.empty(), plain("Click to continue!", NamedTextColor.RED))), c -> openResetConfirm(player));
        footer(view, player, () -> openMain(player));
        player.openInventory(view.inventory);
    }

    private Component coopLabel(Profile profile) {
        int n = Coops.group(profile).size();
        return n > 1 ? plain("Co-op (" + n + "/" + Coops.MAX_MEMBERS + ")", NamedTextColor.AQUA) : plain("Solo", NamedTextColor.GREEN);
    }

    // ------------------------------------------------------------------ co-op
    void openCoop(Player player) {
        Coops coops = plugin.coops();
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Coops.Coop coop = coops.of(player.getUniqueId());
        boolean in = coop != null && coop.size() > 1;
        boolean owner = !in || coop.owner.equals(player.getUniqueId());
        View view = page("Co-op Management");
        List<Component> head = new ArrayList<>();
        head.add(plain("Profile ", NamedTextColor.GRAY).append(coopLabel(profile)));
        head.add(Component.empty());
        head.add(plain("Shared: island, collections", NamedTextColor.GRAY));
        head.add(plain("(each member's part is shown).", NamedTextColor.GRAY));
        head.add(plain("Your own: skills, purse, bag,", NamedTextColor.GRAY));
        head.add(plain("quests, coins and trades.", NamedTextColor.GRAY));
        head.add(Component.empty());
        head.add(plain("Co-op chat: /cc <message>", NamedTextColor.DARK_GRAY));
        view.set(4, icon(Material.PLAYER_HEAD, plain("Co-op", NamedTextColor.GREEN), head), null);
        List<java.util.UUID> members = in ? coop.all() : List.of(player.getUniqueId());
        int[] slots = {20, 21, 22, 23, 24};
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            java.util.UUID id = members.get(i);
            Profile mp = plugin.profiles().get(id);
            boolean online = org.bukkit.Bukkit.getPlayer(id) != null;
            boolean isOwner = !in || coop.owner.equals(id);
            List<Component> lore = new ArrayList<>();
            lore.add(plain(isOwner ? "Island Owner" : "Co-op Member", isOwner ? NamedTextColor.GOLD : NamedTextColor.AQUA));
            lore.add(plain("SkyBlock Level ", NamedTextColor.GRAY).append(plain(String.valueOf(Skills.skyblockLevel(mp)), Skills.levelColor(Skills.skyblockLevel(mp)))));
            lore.add(online ? plain("Online", NamedTextColor.GREEN) : plain("Last seen " + ago(org.bukkit.Bukkit.getOfflinePlayer(id).getLastSeen()), NamedTextColor.GRAY));
            Consumer<ClickType> action = null;
            if (in && !id.equals(player.getUniqueId()) && !isOwner) {
                int votes = coops.votesFor(coop.owner, id);
                lore.add(Component.empty());
                if (coop.size() == 2) {
                    lore.add(plain("Kickable after 7 days offline.", NamedTextColor.DARK_GRAY));
                } else {
                    lore.add(plain("Kick votes: " + votes + "/" + Coops.needed(coop.size()), NamedTextColor.DARK_GRAY));
                }
                lore.add(plain("Click to vote kick!", NamedTextColor.RED));
                action = c -> openCoopKick(player, id);
            }
            view.set(slots[i], icon(Material.PLAYER_HEAD, plain(Coops.name(id), online ? NamedTextColor.GREEN : NamedTextColor.GRAY), lore), action);
        }
        if (owner && members.size() < Coops.MAX_MEMBERS) {
            view.set(38, icon(Material.WRITABLE_BOOK, plain("Invite a Player", NamedTextColor.GREEN), List.of(
                    plain("Type /coop <player>.", NamedTextColor.GRAY), plain("They must be online; joining", NamedTextColor.GRAY),
                    plain("resets their own profile.", NamedTextColor.GRAY), Component.empty(),
                    plain("Invites last 5 minutes.", NamedTextColor.DARK_GRAY))), null);
        }
        java.util.Map<java.util.UUID, Long> mine = coops.invitesFor(player.getUniqueId());
        if (!mine.isEmpty()) {
            java.util.UUID from = mine.keySet().iterator().next();
            List<Component> lore = new ArrayList<>();
            for (java.util.UUID id : mine.keySet()) {
                lore.add(plain("From " + Coops.name(id), NamedTextColor.YELLOW));
            }
            lore.add(Component.empty());
            lore.add(click("Click to see " + Coops.name(from) + "'s!"));
            view.set(40, icon(Material.PAPER, plain("Co-op Invites (" + mine.size() + ")", NamedTextColor.AQUA), lore), c -> openCoopJoin(player, from));
        }
        if (in && !owner) {
            view.set(42, icon(Material.OAK_DOOR, plain("Leave Co-op", NamedTextColor.RED), List.of(
                    plain("Leave this island. Your profile", NamedTextColor.GRAY), plain("is reset onto a new starter", NamedTextColor.GRAY),
                    plain("island.", NamedTextColor.GRAY), Component.empty(), plain("Click to continue!", NamedTextColor.RED))), c -> openCoopLeave(player));
        }
        if (in) {
            List<Component> hist = new ArrayList<>();
            int from = Math.max(0, coop.history.size() - 8);
            for (String h : coop.history.subList(from, coop.history.size())) {
                String[] parts = h.split(" ", 3);
                if (parts.length == 3) {
                    hist.add(plain(parts[2] + " " + parts[1] + " (" + ago(Long.parseLong(parts[0])) + ")", NamedTextColor.GRAY));
                }
            }
            if (hist.isEmpty()) {
                hist.add(plain("Nobody yet.", NamedTextColor.GRAY));
            }
            view.set(46, icon(Material.BOOK, plain("Historical Members", NamedTextColor.GREEN), hist), null);
        }
        view.set(52, soon(Material.CHEST, "Co-op Salvage", "Claim a former member's items."), null);
        footer(view, player, () -> openProfile(player));
        player.openInventory(view.inventory);
    }

    private static String ago(long at) {
        if (at <= 0) {
            return "a while ago";
        }
        long m = Math.max(0, (System.currentTimeMillis() - at) / 60_000L);
        if (m < 60) {
            return m + "m ago";
        }
        if (m < 48 * 60) {
            return (m / 60) + "h ago";
        }
        return (m / 1440) + "d ago";
    }

    void openCoopJoin(Player player, java.util.UUID ownerId) {
        View view = page("Join Co-op?");
        String owner = Coops.name(ownerId);
        List<Component> warn = new ArrayList<>();
        warn.add(plain("Joining resets your profile.", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        warn.add(Component.empty());
        warn.add(plain("Wiped: skills, collections, quests,", NamedTextColor.GRAY));
        warn.add(plain("coins, discovered areas and every", NamedTextColor.GRAY));
        warn.add(plain("item in your bag and ender chest.", NamedTextColor.GRAY));
        warn.add(plain("Your own island is left behind.", NamedTextColor.GRAY));
        warn.add(plain("Onchain: your skills reset and the", NamedTextColor.GRAY));
        warn.add(plain("bag clears from your purse (gas).", NamedTextColor.GRAY));
        warn.add(plain("Your purse joins the co-op deed and", NamedTextColor.GRAY));
        warn.add(plain("your own island deed is released.", NamedTextColor.GRAY));
        warn.add(Component.empty());
        warn.add(plain("Then you play on " + owner + "'s island", NamedTextColor.AQUA));
        warn.add(plain("and share its collections.", NamedTextColor.AQUA));
        warn.add(plain("Kept: your purse, its STT and key.", NamedTextColor.GREEN));
        warn.add(plain("Event leaderboard: your count", NamedTextColor.GRAY));
        warn.add(plain("starts again for the new profile.", NamedTextColor.GRAY));
        warn.add(plain("You are disconnected; join again.", NamedTextColor.GRAY));
        view.set(13, icon(Material.PLAYER_HEAD, plain("Join " + owner + "'s co-op?", NamedTextColor.YELLOW), warn), null);
        view.set(30, icon(Material.LIME_TERRACOTTA, plain("Yes, reset and join", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true), List.of(
                plain("Resets this profile now.", NamedTextColor.GRAY), Component.empty(), plain("Click to join!", NamedTextColor.GREEN))),
                c -> { player.closeInventory(); plugin.coops().join(player, ownerId); });
        view.set(32, icon(Material.RED_TERRACOTTA, plain("No, keep my profile", NamedTextColor.RED), List.of(
                plain("Nothing changes.", NamedTextColor.GRAY), Component.empty(), click("Click to go back!"))), c -> openCoop(player));
        footer(view, player, () -> openCoop(player));
        player.openInventory(view.inventory);
    }

    void openCoopLeave(Player player) {
        View view = page("Leave Co-op?");
        List<Component> warn = new ArrayList<>();
        warn.add(plain("Leaving resets your profile.", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        warn.add(Component.empty());
        warn.add(plain("Your skills, collection part, quests,", NamedTextColor.GRAY));
        warn.add(plain("coins and bag are wiped; you start", NamedTextColor.GRAY));
        warn.add(plain("on a new starter island. The co-op", NamedTextColor.GRAY));
        warn.add(plain("island stays with the others.", NamedTextColor.GRAY));
        warn.add(Component.empty());
        warn.add(plain("Kept: your purse, its STT and key.", NamedTextColor.GREEN));
        warn.add(plain("You are disconnected; join again.", NamedTextColor.GRAY));
        view.set(13, icon(Material.OAK_DOOR, plain("Leave the co-op?", NamedTextColor.RED), warn), null);
        view.set(30, icon(Material.RED_TERRACOTTA, plain("Yes, leave and reset", NamedTextColor.RED).decoration(TextDecoration.BOLD, true), List.of(
                plain("Leaves the co-op now.", NamedTextColor.GRAY), Component.empty(), plain("Click to leave!", NamedTextColor.RED))),
                c -> { player.closeInventory(); plugin.coops().leave(player); });
        view.set(32, icon(Material.LIME_TERRACOTTA, plain("No, stay", NamedTextColor.GREEN), List.of(
                plain("Nothing changes.", NamedTextColor.GRAY), Component.empty(), click("Click to go back!"))), c -> openCoop(player));
        footer(view, player, () -> openCoop(player));
        player.openInventory(view.inventory);
    }

    private void openCoopKick(Player player, java.util.UUID target) {
        View view = page("Kick from Co-op?");
        String name = Coops.name(target);
        view.set(13, icon(Material.PLAYER_HEAD, plain("Vote to kick " + name + "?", NamedTextColor.RED), List.of(
                plain("A kicked member's profile is reset", NamedTextColor.GRAY), plain("onto a new starter island.", NamedTextColor.GRAY), Component.empty(),
                plain("3+ members: more than half of the", NamedTextColor.GRAY), plain("others must vote within 5 minutes.", NamedTextColor.GRAY),
                plain("2 members: only after 7 days offline.", NamedTextColor.GRAY))), null);
        view.set(30, icon(Material.RED_TERRACOTTA, plain("Yes, vote kick", NamedTextColor.RED).decoration(TextDecoration.BOLD, true), List.of(click("Click to vote!"))),
                c -> { player.closeInventory(); plugin.coops().kick(player, name); });
        view.set(32, icon(Material.LIME_TERRACOTTA, plain("No", NamedTextColor.GREEN), List.of(click("Click to go back!"))), c -> openCoop(player));
        footer(view, player, () -> openCoop(player));
        player.openInventory(view.inventory);
    }

    private void openResetConfirm(Player player) {
        View view = page("Reset Profile?");
        List<Component> warn = new ArrayList<>();
        warn.add(plain("This cannot be undone.", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        warn.add(Component.empty());
        warn.add(plain("Wiped: skills, collections, quests,", NamedTextColor.GRAY));
        warn.add(plain("coins, discovered areas, every item", NamedTextColor.GRAY));
        warn.add(plain("in your bag and ender chest, and your", NamedTextColor.GRAY));
        warn.add(plain("island (rebuilt as a new starter).", NamedTextColor.GRAY));
        warn.add(plain("Onchain: skills and collections are", NamedTextColor.GRAY));
        warn.add(plain("reset and the bag is cleared from", NamedTextColor.GRAY));
        warn.add(plain("your purse (a little STT gas).", NamedTextColor.GRAY));
        warn.add(Component.empty());
        warn.add(plain("Kept: your purse, its STT and key.", NamedTextColor.GREEN));
        warn.add(plain("You are disconnected; join again.", NamedTextColor.GRAY));
        view.set(13, icon(Material.TNT, plain("Reset your profile?", NamedTextColor.RED), warn), null);
        view.set(30, icon(Material.RED_TERRACOTTA, plain("Yes, reset everything", NamedTextColor.RED).decoration(TextDecoration.BOLD, true), List.of(
                plain("Wipes this profile now.", NamedTextColor.GRAY), Component.empty(), plain("Click to reset!", NamedTextColor.RED))),
                c -> { player.closeInventory(); plugin.islands().resetProfile(player); });
        view.set(32, icon(Material.LIME_TERRACOTTA, plain("No, keep my profile", NamedTextColor.GREEN), List.of(
                plain("Nothing changes.", NamedTextColor.GRAY), Component.empty(), click("Click to go back!"))), c -> openProfile(player));
        footer(view, player, () -> openProfile(player));
        player.openInventory(view.inventory);
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
