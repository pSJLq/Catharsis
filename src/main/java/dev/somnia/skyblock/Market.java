package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.web3j.abi.datatypes.Type;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * NPC merchant (coins), Blacksmith (coin + material sinks), Bazaar (STT sell offers and STT buy
 * orders settled by SkyRealm), Auction House (STT escrow in SkyRealm), and the relic bank.
 */
final class Market implements Listener {
    record Product(String id, String key, String name, int npc) {
    }

    record Recipe(String result, int qty, long coins, Map<String, Integer> mats, int level) {
    }

    static final class Offer {
        long id;
        UUID seller;
        String wallet;
        String key;
        int qty;
        BigInteger unit;
        boolean busy;
    }

    static final class Order {
        long id;
        UUID buyer;
        String key;
        int left;
        BigInteger unit;
        boolean busy;
    }

    static final class Lot {
        long id;
        UUID seller;
        String key;
        int qty;
        BigInteger start;
        BigInteger bid = BigInteger.ZERO;
        UUID bidder;
        long end;
        boolean busy;
        boolean done;
        int tries;
    }

    /** A Buy-It-Now listing settled by SkyBin; the item waits on the server until sold, cancelled or expired. */
    static final class Bin {
        long id;
        UUID seller;
        String key;
        int qty;
        BigInteger price;
        long end;
        boolean busy;
    }

    static final class View extends Menu {
        final Inventory inventory;
        final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
        /** Optional handler for clicks in the player's own inventory (slot index of PlayerInventory). */
        java.util.function.IntConsumer bottom;
        final String title;
        boolean merchant;

        View(int size, String title) {
            this.title = title;
            this.inventory = Bukkit.createInventory(this, size, Component.text(title));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void set(int slot, ItemStack stack, Consumer<ClickType> action) {
            inventory.setItem(slot, stack);
            if (action != null) {
                actions.put(slot, action);
            }
        }
    }

    static final Map<String, Product> PRODUCTS = new LinkedHashMap<>();
    static final List<Recipe> RECIPES = new ArrayList<>();

    static {
        // NPC sell prices: SkyBlock wiki |sell= values (2026-11-11). Beetroot, Pale Oak Log, Raw Iron / Gold,
        // Colosseum Sigil are ours. Order = merchant shop order.
        product("wheat", "minecraft:wheat", "Wheat", 6);
        product("carrot", "minecraft:carrot", "Carrot", 3);
        product("potato", "minecraft:potato", "Potato", 3);
        product("pumpkin", "minecraft:pumpkin", "Pumpkin", 10);
        product("melon", "minecraft:melon_slice", "Melon Slice", 2);
        product("sugar_cane", "minecraft:sugar_cane", "Sugar Cane", 4);
        product("cactus", "minecraft:cactus", "Cactus", 4);
        product("cocoa_beans", "minecraft:cocoa_beans", "Cocoa Beans", 3);
        product("nether_wart", "minecraft:nether_wart", "Nether Wart", 4);
        product("beetroot", "minecraft:beetroot", "Beetroot", 3);
        product("wheat_seeds", "minecraft:wheat_seeds", "Seeds", 3);
        product("melon_seeds", "minecraft:melon_seeds", "Melon Seeds", 1);
        product("pumpkin_seeds", "minecraft:pumpkin_seeds", "Pumpkin Seeds", 1);
        product("green_dye", "minecraft:green_dye", "Cactus Green", 4);
        product("cobblestone", "minecraft:cobblestone", "Cobblestone", 1);
        product("coal", "minecraft:coal", "Coal", 2);
        product("raw_iron", "minecraft:raw_iron", "Raw Iron", 3);
        product("iron_ingot", "minecraft:iron_ingot", "Iron Ingot", 2);
        product("raw_gold", "minecraft:raw_gold", "Raw Gold", 4);
        product("gold_ingot", "minecraft:gold_ingot", "Gold Ingot", 3);
        product("gold_block", "minecraft:gold_block", "Block of Gold", 27);
        product("diamond", "minecraft:diamond", "Diamond", 8);
        product("emerald", "minecraft:emerald", "Emerald", 4);
        product("lapis", "minecraft:lapis_lazuli", "Lapis Lazuli", 1);
        product("redstone", "minecraft:redstone", "Redstone Dust", 1);
        product("oak_log", "minecraft:oak_log", "Oak Log", 2);
        product("birch_log", "minecraft:birch_log", "Birch Log", 2);
        product("spruce_log", "minecraft:spruce_log", "Spruce Log", 2);
        product("jungle_log", "minecraft:jungle_log", "Jungle Log", 2);
        product("dark_oak_log", "minecraft:dark_oak_log", "Dark Oak Log", 2);
        product("pale_oak_log", "minecraft:pale_oak_log", "Pale Oak Log", 2);
        product("rotten_flesh", "minecraft:rotten_flesh", "Rotten Flesh", 2);
        product("bone", "minecraft:bone", "Bone", 2);
        product("string", "minecraft:string", "String", 3);
        product("spider_eye", "minecraft:spider_eye", "Spider Eye", 3);
        product("cod", "minecraft:cod", "Raw Cod", 6);
        product("salmon", "minecraft:salmon", "Raw Salmon", 10);
        product("pufferfish", "minecraft:pufferfish", "Pufferfish", 15);
        product("tropical_fish", "minecraft:tropical_fish", "Tropical Fish", 20);
        product("enchanted_cobble", "skyblock:enchanted_cobble", "Enchanted Cobblestone", 160);
        product("enchanted_oak", "skyblock:enchanted_oak", "Enchanted Oak Log", 320);
        product("enchanted_rotten_flesh", "skyblock:enchanted_rotten_flesh", "Enchanted Rotten Flesh", 320);
        product("revenant_flesh", "skyblock:revenant_flesh", "Barrow Flesh", 1);
        product("wolf_tooth", "skyblock:wolf_tooth", "Thornkeep Fang", 1);
        product("colosseum_sigil", "skyblock:colosseum_sigil", "Colosseum Sigil", 150);
        // Leftover Booster items only (purchase applies the buff directly). Traded here for STT only. npc 0 = merchants never buy it for coins.
        product(Cookies.ID, Cookies.KEY, "Booster", 0);

        recipe("undead_sword", 150, Map.of(), 0);
        recipe("cleaver", 400, Map.of("minecraft:iron_ingot", 4), 2);
        recipe("graveknight_blade", 2500, Map.of("skyblock:revenant_flesh", 12), 4);
        recipe("champion_blade", 15000, Map.of("skyblock:colosseum_sigil", 3, "skyblock:wolf_tooth", 8), 7);
        recipe("graveyard_helmet", 100, Map.of("minecraft:rotten_flesh", 10), 1);
        recipe("graveyard_chestplate", 180, Map.of("minecraft:rotten_flesh", 16), 1);
        recipe("graveyard_leggings", 160, Map.of("minecraft:rotten_flesh", 14), 1);
        recipe("graveyard_boots", 90, Map.of("minecraft:rotten_flesh", 8), 1);
        recipe("revenant_helmet", 900, Map.of("skyblock:revenant_flesh", 4, "skyblock:wolf_tooth", 1), 5);
        recipe("revenant_chestplate", 1600, Map.of("skyblock:revenant_flesh", 8, "skyblock:wolf_tooth", 2), 5);
        recipe("revenant_leggings", 1400, Map.of("skyblock:revenant_flesh", 7, "skyblock:wolf_tooth", 2), 5);
        recipe("revenant_boots", 800, Map.of("skyblock:revenant_flesh", 4, "skyblock:wolf_tooth", 1), 5);
        recipe("enchanted_rotten_flesh", 20, Map.of("minecraft:rotten_flesh", 160), 0);
    }

    private static void product(String id, String key, String name, int npc) {
        PRODUCTS.put(id, new Product(id, key, name, npc));
    }

    private static void recipe(String result, long coins, Map<String, Integer> mats, int level) {
        RECIPES.add(new Recipe("skyblock:" + result, 1, coins, mats, level));
    }

    private final SomniaSkyblockPlugin plugin;
    private final File file;
    private final Map<Long, Offer> offers = new LinkedHashMap<>();
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final Map<Long, Lot> lots = new LinkedHashMap<>();
    private final Map<Long, Bin> bins = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> stash = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private long nextOffer = 1;

    Market(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
        load();
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::settleTick, 200L, 200L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireBins, 400L, 200L);
    }

    java.util.Collection<Offer> offers() {
        return offers.values();
    }

    java.util.Collection<Order> orders() {
        return orders.values();
    }

    java.util.Collection<Lot> lots() {
        return lots.values();
    }

    java.util.Collection<Bin> bins() {
        return bins.values();
    }

    Map<String, Integer> stashOf(UUID id) {
        return stash.getOrDefault(id, Map.of());
    }

    // ---------- items ----------

    static String label(String key) {
        for (Product product : PRODUCTS.values()) {
            if (product.key().equals(key)) {
                return product.name();
            }
        }
        if (key.startsWith("skyblock:")) {
            Gear.Kind kind = Gear.KINDS.get(key.substring(9));
            if (kind != null) {
                return kind.name();
            }
        }
        String bare = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        StringBuilder out = new StringBuilder();
        for (String word : bare.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    static Product product(String id) {
        if (id == null) {
            return null;
        }
        Product direct = PRODUCTS.get(id.toLowerCase());
        if (direct != null) {
            return direct;
        }
        for (Product product : PRODUCTS.values()) {
            if (product.key().equalsIgnoreCase(id)) {
                return product;
            }
        }
        return null;
    }

    int count(Player player, String key) {
        int total = 0;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (ItemStack stack : storage) {
            if (key.equals(HoldItems.keyOf(stack, plugin))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    boolean take(Player player, String key, int qty) {
        if (count(player, key) < qty) {
            return false;
        }
        ItemStack[] storage = player.getInventory().getStorageContents();
        int left = qty;
        for (int i = 0; i < storage.length && left > 0; i++) {
            ItemStack stack = storage[i];
            if (!key.equals(HoldItems.keyOf(stack, plugin))) {
                continue;
            }
            int use = Math.min(left, stack.getAmount());
            left -= use;
            if (use >= stack.getAmount()) {
                storage[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - use);
            }
        }
        player.getInventory().setStorageContents(storage);
        return true;
    }

    /** Gives into the live inventory (then the SkyWord bag write) or parks the items in the stash. */
    void giveOrStash(UUID owner, String key, int qty, boolean commitNow) {
        Player player = plugin.getServer().getPlayer(owner);
        if (player == null || (plugin.words() != null && !plugin.words().ready(player))) {
            stash(owner, key, qty);
            return;
        }
        int left = qty;
        int placed = 0;
        while (left > 0) {
            ItemStack stack = HoldItems.fromKey(plugin, key, left);
            if (stack == null) {
                plugin.getLogger().warning("Unknown item key " + key);
                return;
            }
            int amount = stack.getAmount();
            Runnable free = SbMenu.reserveStar(player);
            Map<Integer, ItemStack> rest = player.getInventory().addItem(stack);
            free.run();
            int unplaced = 0;
            for (ItemStack r : rest.values()) {
                unplaced += r.getAmount();
            }
            left -= amount - unplaced;
            placed += amount - unplaced;
            if (unplaced > 0) {
                stash(owner, key, left);
                player.sendMessage(Component.text("Inventory full: " + left + "x " + label(key) + " waits in your stash. /bz claim", NamedTextColor.YELLOW));
                break;
            }
        }
        if (commitNow) {
            int given = placed;
            afterBag(player, ok -> {
                if (!ok && given > 0) {
                    // The bag write was rolled back, so the live bag no longer shows them: keep them safe in the stash.
                    stash(owner, key, given);
                    message(owner, given + "x " + label(key) + " could not be signed into your bag and wait in your stash. /bz claim");
                }
            });
        }
    }

    /**
     * Signs the live bag, then runs {@code done} once with true when the write landed (or there is no chain bag),
     * false when it was rolled back. Items taken for a listing only become a listing after true; on false the
     * rollback has already put them back.
     */
    void afterBag(Player player, Consumer<Boolean> done) {
        afterBag(player, done, null);
    }

    /**
     * As above; with {@code lateLanded} set, a write that gives no answer in 120 s counts as not signed (the lock
     * is freed, nothing is listed), and if it still lands later {@code lateLanded} runs so the taken items are
     * not lost.
     */
    void afterBag(Player player, Consumer<Boolean> done, Runnable lateLanded) {
        if (plugin.words() == null) {
            done.accept(true);
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        String who = player.getName();
        plugin.words().commitLocal(player, ok -> {
            if (fired.compareAndSet(false, true)) {
                done.accept(ok);
            } else if (ok && lateLanded != null) {
                plugin.getLogger().warning("Market: late bag write for " + who + " landed after the listing was dropped; items go to the stash.");
                lateLanded.run();
            }
        });
        if (lateLanded == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (fired.compareAndSet(false, true)) {
                plugin.getLogger().warning("Market: bag write for " + who + " gave no answer in 120 s; nothing listed.");
                done.accept(false);
            }
        }, 20L * 120);
    }

    void commit(Player player) {
        if (plugin.words() != null) {
            plugin.words().commitLocal(player);
        }
    }

    private void stash(UUID owner, String key, int qty) {
        if (qty <= 0) {
            return;
        }
        stash.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).merge(key, qty, Integer::sum);
        save();
    }

    void claim(Player player) {
        Map<String, Integer> mine = stash.remove(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            player.sendMessage(Component.text("Your stash is empty.", NamedTextColor.GRAY));
            return;
        }
        save();
        Map<String, Integer> before = new LinkedHashMap<>();
        Map<String, Integer> s0 = stash.get(player.getUniqueId());
        if (s0 != null) {
            before.putAll(s0);
        }
        for (Map.Entry<String, Integer> entry : mine.entrySet()) {
            player.sendMessage(Component.text("Stash → " + entry.getValue() + "x " + label(entry.getKey()), NamedTextColor.GREEN));
            giveOrStash(player.getUniqueId(), entry.getKey(), entry.getValue(), false);
        }
        UUID id = player.getUniqueId();
        afterBag(player, ok -> {
            if (ok) {
                return;
            }
            // Rolled back: what went into the bag (claimed minus what overflowed back into the stash) returns to the stash.
            Map<String, Integer> now = stash.getOrDefault(id, Map.of());
            for (Map.Entry<String, Integer> entry : mine.entrySet()) {
                int overflow = now.getOrDefault(entry.getKey(), 0) - before.getOrDefault(entry.getKey(), 0);
                int back = entry.getValue() - Math.max(0, overflow);
                if (back > 0) {
                    stash(id, entry.getKey(), back);
                }
            }
            message(id, "The bag write did not land; your items stay in the stash. /bz claim");
        });
    }

    @EventHandler
    public void onJoin(SbLoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.realm().claimRefund(id), 200L);
        if (!stash.containsKey(id)) {
            return;
        }
        waitThenClaim(id, 0);
    }

    private void waitThenClaim(UUID id, int attempt) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || attempt > 20) {
                return;
            }
            if (plugin.words() != null && !plugin.words().ready(player)) {
                waitThenClaim(id, attempt + 1);
                return;
            }
            claim(player);
        }, 60L);
    }

    private boolean lock(Player player) {
        if (!pending.add(player.getUniqueId())) {
            player.sendMessage(Component.text("Wait for your last Somnia transaction to land.", NamedTextColor.GRAY));
            return false;
        }
        return true;
    }

    private void unlock(UUID id) {
        pending.remove(id);
    }

    boolean chainReady(Player player) {
        if (!plugin.realm().enabled()) {
            player.sendMessage(Component.text("SkyRealm is not configured on this server.", NamedTextColor.RED));
            return false;
        }
        if (plugin.hold().offchain(player)) {
            player.sendMessage(Component.text("Creative and sandbox players cannot trade on-chain.", NamedTextColor.RED));
            return false;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.wallet == null || profile.wallet.isBlank()) {
            player.sendMessage(Component.text("You have no purse yet.", NamedTextColor.RED));
            return false;
        }
        if (plugin.words() != null && !plugin.words().ready(player)) {
            player.sendMessage(Component.text("Your bag is still loading from the chain. Try again in a moment.", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    // ---------- GUI ----------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != null && event.getClickedInventory() == event.getView().getBottomInventory()
                && view.bottom != null && event.getWhoClicked() instanceof Player clicker) {
            clicker.playSound(clicker.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            view.bottom.accept(event.getSlot());
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != view.getInventory()) {
            return;
        }
        Consumer<ClickType> action = view.actions.get(event.getRawSlot());
        if (action != null && event.getWhoClicked() instanceof Player player) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            action.accept(event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    static ItemStack icon(Material material, String name, NamedTextColor color, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        decorate(stack, name, color, lore);
        return stack;
    }

    static void decorate(ItemStack stack, String name, NamedTextColor color, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (name != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lines = new ArrayList<>();
        List<Component> old = meta.lore();
        if (old != null && name == null) {
            lines.addAll(old);
            lines.add(Component.empty());
        }
        for (String line : lore) {
            NamedTextColor tone = NamedTextColor.GRAY;
            String text = line;
            if (line.startsWith("!")) {
                tone = NamedTextColor.YELLOW;
                text = line.substring(1);
            } else if (line.startsWith("$")) {
                tone = NamedTextColor.GOLD;
                text = line.substring(1);
            } else if (line.startsWith("~")) {
                tone = NamedTextColor.AQUA;
                text = line.substring(1);
            } else if (line.startsWith("-")) {
                tone = NamedTextColor.RED;
                text = line.substring(1);
            } else if (line.startsWith("+")) {
                tone = NamedTextColor.GREEN;
                text = line.substring(1);
            }
            lines.add(Component.text(text, tone).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        stack.setItemMeta(meta);
    }

    ItemStack itemIcon(String key, int qty) {
        ItemStack stack = HoldItems.fromKey(plugin, key, Math.max(1, Math.min(64, qty)));
        return stack == null ? new ItemStack(Material.BARRIER) : stack;
    }

    private static void frame(View view) {
        ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < view.getInventory().getSize(); i++) {
            view.getInventory().setItem(i, pane);
        }
    }

    // ---------- Merchant (coins) ----------

    /** Last stack sold to a merchant, per player: click the buyback slot to buy it back at the same price. */
    private record Sold(String key, int qty, long coins) {
    }

    private final Map<UUID, Sold> buyback = new HashMap<>();

    /** What a merchant stocks: item key and the wiki |buy= price (coins each). */
    private record Stock(String key, int buy) {
    }

    private static List<Stock> stockOf(String title) {
        return switch (title) {
            case "Farmer Odo" -> List.of(new Stock("minecraft:wheat", 10));
            case "Woodward Fenn" -> List.of(new Stock("minecraft:oak_log", 5));
            case "Fisher Hale" -> List.of();
            default -> List.of(new Stock("minecraft:cobblestone", 3), new Stock("minecraft:wheat", 10), new Stock("minecraft:oak_log", 5));
        };
    }

    void openMerchant(Player player) {
        openMerchant(player, "Merchant");
    }

    /**
     * Shop in the SkyBlock layout: gray border, the merchant's stock inside, buyback on the bottom row.
     * Clicking a stack in your own inventory sells the whole stack. The stack leaves through the SkyWord
     * bag write and coins are paid only after that write lands; a failed write puts the stack back.
     */
    void openMerchant(Player player, String title) {
        View view = new View(54, title == null || title.isBlank() ? "Merchant" : title);
        view.merchant = true;
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 54; i++) {
            view.getInventory().setItem(i, pane);
        }
        int[] inner = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        List<Stock> stock = stockOf(view.title);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        for (int i = 0; i < inner.length; i++) {
            view.getInventory().setItem(inner[i], null);
        }
        for (int i = 0; i < stock.size() && i < inner.length; i++) {
            Stock item = stock.get(i);
            ItemStack shown = itemIcon(item.key(), 1);
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            lore.add(Lore.plain("Cost", NamedTextColor.GRAY));
            lore.add(Lore.plain(String.format(java.util.Locale.ROOT, "%,d", item.buy()) + " Coins", NamedTextColor.GOLD));
            lore.add(Component.empty());
            lore.add(Lore.plain("Click to trade!", NamedTextColor.YELLOW));
            lore.add(Lore.plain("Right-Click for more trading options!", NamedTextColor.YELLOW));
            meta.lore(lore);
            shown.setItemMeta(meta);
            view.set(inner[i], shown, click -> {
                int qty = click.isRightClick() ? 64 : click.isShiftClick() ? 16 : 1;
                buyFromMerchant(player, view.title, item, qty);
            });
        }
        if (stock.isEmpty()) {
            view.set(22, SbMenu.soon(Material.FISHING_ROD, "Tackle and bait", "This merchant will stock fishing gear later. You can still sell your catch here."), null);
        }
        Sold last = buyback.get(player.getUniqueId());
        if (last != null) {
            ItemStack back = itemIcon(last.key(), last.qty());
            ItemMeta meta = back.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            lore.add(Lore.plain("Cost", NamedTextColor.GRAY));
            lore.add(Lore.plain(String.format(java.util.Locale.ROOT, "%,d", last.coins()) + " Coins", NamedTextColor.GOLD));
            lore.add(Component.empty());
            lore.add(Lore.plain("Click to buyback!", NamedTextColor.YELLOW));
            meta.lore(lore);
            back.setItemMeta(meta);
            view.set(49, back, click -> buyBack(player, view.title));
        } else {
            view.set(49, icon(Material.HOPPER, "Sell Item", NamedTextColor.GREEN, List.of(
                    "Click items in your inventory to", "sell them to this shop!", "",
                    "You have " + String.format(java.util.Locale.ROOT, "%,d", profile.coins) + " coins.")), null);
        }
        view.bottom = slot -> sellSlot(player, view.title, slot);
        player.openInventory(view.getInventory());
        sellMode(player, true);
    }

    /** Sell-mode tooltips on the player's own items while a merchant is open (Lore.sellView), plain again on close. */
    void sellMode(Player player, boolean on) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        boolean changed = false;
        for (int i = 0; i < storage.length; i++) {
            ItemStack stack = storage[i];
            if (stack == null || stack.getType().isAir() || i == SbMenu.STAR_SLOT) {
                continue;
            }
            if (on) {
                Product product = productOf(stack);
                if (product != null && product.npc() > 0) {
                    changed |= Lore.sellView(stack, product.npc());
                }
            } else {
                changed |= Lore.normalize(stack);
            }
        }
        if (changed) {
            player.getInventory().setStorageContents(storage);
        }
    }

    Product productOf(ItemStack stack) {
        String key = HoldItems.keyOf(stack, plugin);
        if (key == null) {
            return null;
        }
        for (Product product : PRODUCTS.values()) {
            if (product.key().equals(key)) {
                return product;
            }
        }
        return null;
    }

    private void sellSlot(Player player, String title, int slot) {
        if (slot == SbMenu.STAR_SLOT || slot < 0 || slot >= 36) {
            return; // the menu star and armor are never sold
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir() || SbMenu.isStar(plugin, stack)) {
            return;
        }
        Product product = productOf(stack);
        if (product == null || product.npc() <= 0) {
            player.sendMessage(Component.text("That item can't be sold to this shop.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        if (plugin.words() != null && !plugin.words().ready(player)) {
            player.sendMessage(Component.text("The chain inventory is still loading.", NamedTextColor.RED));
            return;
        }
        int qty = stack.getAmount();
        long coins = (long) qty * product.npc();
        String key = product.key();
        UUID id = player.getUniqueId();
        player.getInventory().setItem(slot, null);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
        java.util.function.Consumer<Boolean> pay = ok -> {
            Player online = plugin.getServer().getPlayer(id);
            if (!ok) {
                if (online != null) {
                    online.sendMessage(Component.text("Sale cancelled: the bag write did not land, your " + product.name() + " stayed.", NamedTextColor.RED));
                }
                return;
            }
            Profile profile = plugin.profiles().get(id);
            if (online != null) {
                plugin.stats().addCoins(online, coins);
                online.sendMessage(Component.text("You sold ", NamedTextColor.GREEN)
                        .append(Component.text(product.name(), NamedTextColor.WHITE))
                        .append(Component.text(" x" + qty + " for ", NamedTextColor.GRAY))
                        .append(Component.text(String.format(java.util.Locale.ROOT, "%,d", coins) + " Coins", NamedTextColor.GOLD))
                        .append(Component.text("!", NamedTextColor.GRAY)));
                plugin.quests().hit(online, "sell", 1);
            } else {
                profile.coins += coins;
            }
            plugin.profiles().save(profile);
            buyback.put(id, new Sold(key, qty, coins));
            if (online != null && online.getOpenInventory().getTopInventory().getHolder() instanceof View open && open.merchant) {
                openMerchant(online, open.title);
            }
        };
        if (plugin.words() != null) {
            plugin.words().commitLocal(player, pay);
        } else {
            pay.accept(true);
        }
    }

    private void buyBack(Player player, String title) {
        Sold last = buyback.get(player.getUniqueId());
        if (last == null) {
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            player.sendMessage(Component.text("Free an inventory slot first.", NamedTextColor.RED));
            return;
        }
        if (!plugin.stats().spendCoins(player, last.coins())) {
            return;
        }
        buyback.remove(player.getUniqueId());
        deliver(player, last.key(), last.qty(), last.coins(), "Bought back");
        openMerchant(player, title);
    }

    private void buyFromMerchant(Player player, String title, Stock item, int qty) {
        long cost = (long) item.buy() * qty;
        if (player.getInventory().firstEmpty() < 0 && count(player, item.key()) % 64 == 0) {
            player.sendMessage(Component.text("Free an inventory slot first.", NamedTextColor.RED));
            return;
        }
        if (!plugin.stats().spendCoins(player, cost)) {
            return;
        }
        deliver(player, item.key(), qty, cost, "Bought");
        openMerchant(player, title);
    }

    /** Puts bought items in the bag; if the bag write is rolled back the coins come back. */
    private void deliver(Player player, String key, int qty, long coins, String verb) {
        UUID id = player.getUniqueId();
        giveOrStash(id, key, qty, false);
        java.util.function.Consumer<Boolean> done = ok -> {
            Player online = plugin.getServer().getPlayer(id);
            if (ok) {
                if (online != null) {
                    online.sendMessage(Component.text(verb + " ", NamedTextColor.GREEN).append(Component.text(label(key) + " x" + qty, NamedTextColor.WHITE))
                            .append(Component.text(" for ", NamedTextColor.GRAY)).append(Component.text(String.format(java.util.Locale.ROOT, "%,d", coins) + " Coins", NamedTextColor.GOLD)));
                }
                return;
            }
            Profile profile = plugin.profiles().get(id);
            if (online != null) {
                plugin.stats().addCoins(online, coins);
                online.sendMessage(Component.text("Purchase refunded: the bag write did not land.", NamedTextColor.RED));
            } else {
                profile.coins += coins;
            }
            plugin.profiles().save(profile);
        };
        if (plugin.words() != null) {
            plugin.words().commitLocal(player, done);
        } else {
            done.accept(true);
        }
    }

    @EventHandler
    public void onMerchantClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View view && view.merchant && event.getPlayer() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof View open && open.merchant)) {
                    sellMode(player, false);
                }
            });
        }
    }

    void sellToMerchant(Player player, Product product) {
        if (product.npc() <= 0) {
            player.sendMessage(Component.text("Merchants don't buy " + product.name() + ". Sell it on the Bazaar for STT.", NamedTextColor.RED));
            return;
        }
        int have = count(player, product.key());
        if (have <= 0 || !take(player, product.key(), have)) {
            return;
        }
        long coins = (long) have * product.npc();
        UUID id = player.getUniqueId();
        java.util.function.Consumer<Boolean> pay = ok -> {
            Player online = plugin.getServer().getPlayer(id);
            if (!ok || online == null) {
                return;
            }
            plugin.stats().addCoins(online, coins);
            online.sendMessage(Component.text("Sold " + have + "x " + product.name() + " for " + String.format(java.util.Locale.ROOT, "%,d", coins) + " coins.", NamedTextColor.GOLD));
            plugin.quests().hit(online, "sell", 1);
        };
        if (plugin.words() != null) {
            plugin.words().commitLocal(player, pay);
        } else {
            pay.accept(true);
        }
    }

    // ---------- Blacksmith (sinks) ----------

    void openShop(Player player) {
        View view = new View(54, "Blacksmith — forge gear");
        frame(view);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        int level = Stats.combatLevel(profile.combatXp);
        int[] slots = {10, 11, 12, 13, 19, 20, 21, 22, 28, 29, 30, 31, 15};
        for (int i = 0; i < RECIPES.size() && i < slots.length; i++) {
            Recipe recipe = RECIPES.get(i);
            ItemStack stack = itemIcon(recipe.result(), recipe.qty());
            List<String> lore = new ArrayList<>();
            lore.add("!Cost");
            if (recipe.coins() > 0) {
                lore.add((profile.coins >= recipe.coins() ? "+" : "-") + String.format(java.util.Locale.ROOT, "%,d", recipe.coins()) + " coins");
            }
            for (Map.Entry<String, Integer> mat : recipe.mats().entrySet()) {
                int have = count(player, mat.getKey());
                lore.add((have >= mat.getValue() ? "+" : "-") + mat.getValue() + "x " + label(mat.getKey()) + " (" + have + ")");
            }
            if (recipe.level() > 0) {
                lore.add((level >= recipe.level() ? "+" : "-") + "Requires Combat " + recipe.level());
            }
            lore.add("!Click to forge");
            decorate(stack, null, NamedTextColor.WHITE, lore);
            view.set(slots[i], stack, click -> {
                forge(player, recipe);
                openShop(player);
            });
        }
        view.set(49, icon(Material.BOOK, "How gear works", NamedTextColor.YELLOW, List.of(
                "Damage, Strength, Crit and Defense change every hit.",
                "Barrow Blade: double damage to the undead.",
                "Gear lives in your on-chain SkyWord bag.",
                "Coins and materials spent here leave the game (sink).")), null);
        player.openInventory(view.getInventory());
    }

    private void forge(Player player, Recipe recipe) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (Stats.combatLevel(profile.combatXp) < recipe.level()) {
            player.sendMessage(Component.text("You need Combat " + recipe.level() + ".", NamedTextColor.RED));
            return;
        }
        for (Map.Entry<String, Integer> mat : recipe.mats().entrySet()) {
            if (count(player, mat.getKey()) < mat.getValue()) {
                player.sendMessage(Component.text("You need " + mat.getValue() + "x " + label(mat.getKey()) + ".", NamedTextColor.RED));
                return;
            }
        }
        if (player.getInventory().firstEmpty() < 0) {
            player.sendMessage(Component.text("Free an inventory slot first.", NamedTextColor.RED));
            return;
        }
        if (!plugin.stats().spendCoins(player, recipe.coins())) {
            return;
        }
        for (Map.Entry<String, Integer> mat : recipe.mats().entrySet()) {
            take(player, mat.getKey(), mat.getValue());
        }
        giveOrStash(player.getUniqueId(), recipe.result(), recipe.qty(), true);
        player.sendMessage(Component.text("Forged " + label(recipe.result()) + "!", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1f);
        String forged = recipe.result();
        if (forged.endsWith("_helmet") || forged.endsWith("_chestplate") || forged.endsWith("_leggings") || forged.endsWith("_boots")) {
            plugin.quests().hit(player, "forge_armor", 1);
        } else if (forged.endsWith("_sword") || forged.endsWith("_blade") || forged.endsWith("cleaver")) {
            plugin.quests().hit(player, "forge_sword", 1);
        }
    }

    // ---------- Bazaar ----------

    void openBazaar(Player player) {
        plugin.marketGui().bazaar(player, MarketGui.Cat.FARMING);
    }

    void openBazaarClassic(Player player) {
        View view = new View(54, "Bazaar — STT market");
        frame(view);
        int slot = 10;
        for (Product product : PRODUCTS.values()) {
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 44) {
                break;
            }
            Offer best = bestOffer(product.key(), null);
            Order top = bestOrder(product.key(), null);
            ItemStack stack = itemIcon(product.key(), 1);
            decorate(stack, null, NamedTextColor.WHITE, List.of(
                    "~Buy price: " + (best == null ? "no sell offers" : RealmChain.stt(best.unit) + " STT (" + supply(product.key()) + " on sale)"),
                    "~Sell price: " + (top == null ? "no buy orders" : RealmChain.stt(top.unit) + " STT (" + demand(product.key()) + " wanted)"),
                    "$Merchant: " + product.npc() + " coins",
                    "!Click to trade"));
            view.set(slot, stack, click -> openProduct(player, product));
            slot++;
        }
        view.set(48, icon(Material.CHEST, "Your orders & stash", NamedTextColor.YELLOW, List.of(
                "Offers: " + offers.values().stream().filter(o -> o.seller.equals(player.getUniqueId())).count()
                        + "   Buy orders: " + orders.values().stream().filter(o -> o.buyer.equals(player.getUniqueId())).count(),
                "Stash: " + stash.getOrDefault(player.getUniqueId(), Map.of()).size() + " kinds",
                "!Click to list them (/bz orders)")), click -> {
            player.closeInventory();
            listMine(player);
            if (stash.containsKey(player.getUniqueId())) {
                claim(player);
            }
        });
        view.set(49, icon(Material.EMERALD, "How the Bazaar works", NamedTextColor.GREEN, List.of(
                "Prices are in STT on Somnia Shannon (50312).",
                "Sell offer: your items wait here, buyers pay STT",
                "  straight to your purse through SkyRealm.",
                "Buy order: your STT is escrowed in SkyRealm until filled.",
                "Tax 1.25% of each fill goes to the Relic Pool.",
                "~/bz sell <item> <qty> <stt each>",
                "~/bz order <item> <qty> <stt each>",
                "~/bz cancel <id>   /bz claim")), null);
        view.set(50, icon(Material.GOLD_INGOT, "Merchant (coins)", NamedTextColor.GOLD, List.of("Sell raw items for coins.")), click -> openMerchant(player));
        player.openInventory(view.getInventory());
    }

    void openProduct(Player player, Product product) {
        plugin.marketGui().product(player, product);
    }

    void openProductClassic(Player player, Product product) {
        View view = new View(36, "Bazaar ➜ " + product.name());
        frame(view);
        int have = count(player, product.key());
        Offer best = bestOffer(product.key(), player.getUniqueId());
        Order top = bestOrder(product.key(), player.getUniqueId());
        ItemStack info = itemIcon(product.key(), Math.max(1, have));
        List<String> lore = new ArrayList<>();
        lore.add("You have: " + have);
        lore.add("!Top sell offers");
        offers.values().stream().filter(o -> o.key.equals(product.key())).sorted(Comparator.comparing(o -> o.unit)).limit(5)
                .forEach(o -> lore.add("~" + o.qty + "x @ " + RealmChain.stt(o.unit) + " STT  #" + o.id + " " + name(o.seller)));
        lore.add("!Top buy orders");
        orders.values().stream().filter(o -> o.key.equals(product.key()) && o.left > 0).sorted(Comparator.comparing((Order o) -> o.unit).reversed()).limit(5)
                .forEach(o -> lore.add("~" + o.left + "x @ " + RealmChain.stt(o.unit) + " STT  #" + o.id + " " + name(o.buyer)));
        decorate(info, null, NamedTextColor.WHITE, lore);
        view.set(4, info, null);
        view.set(10, icon(Material.EMERALD, "Buy instantly", NamedTextColor.GREEN, best == null
                ? List.of("-No sell offers from other players.")
                : List.of("~Cheapest: " + best.qty + "x @ " + RealmChain.stt(best.unit) + " STT",
                "!Left click: buy up to 64 (" + RealmChain.stt(best.unit.multiply(BigInteger.valueOf(Math.min(64, best.qty)))) + " STT)",
                "!Right click: buy 1",
                "STT goes from your purse to the seller via SkyRealm.fillOffer")), click -> {
            if (best != null) {
                player.closeInventory();
                buyOffer(player, best, click.isRightClick() ? 1 : Math.min(64, best.qty));
            }
        });
        view.set(12, icon(Material.HOPPER, "Sell instantly", NamedTextColor.GOLD, top == null
                ? List.of("-No buy orders from other players.")
                : List.of("~Best order: " + top.left + "x @ " + RealmChain.stt(top.unit) + " STT",
                "!Click: sell " + Math.min(have, Math.min(64, top.left)) + " into it",
                "STT is released from escrow by SkyRealm.fillBuyOrder")), click -> {
            if (top != null) {
                player.closeInventory();
                sellToOrder(player, top, Math.min(count(player, product.key()), Math.min(64, top.left)));
            }
        });
        view.set(14, icon(Material.GOLD_INGOT, "Sell to Merchant", NamedTextColor.GOLD, product.npc() <= 0
                ? List.of("-Merchants don't buy this item.", "~Sell it here for STT instead.")
                : List.of("$" + product.npc() + " coins each (" + String.format(java.util.Locale.ROOT, "%,d", (long) have * product.npc()) + " total)", "!Click to sell all")), click -> {
            sellToMerchant(player, product);
            openProduct(player, product);
        });
        view.set(16, icon(Material.WRITABLE_BOOK, "Create offer / order", NamedTextColor.YELLOW, List.of(
                "~/bz sell " + product.id() + " <qty> <stt each>",
                "  list your items; buyers pay STT to your purse",
                "~/bz order " + product.id() + " <qty> <stt each>",
                "  escrow STT; sellers fill it with items",
                "Example: /bz sell " + product.id() + " 32 0.0005")), click -> {
            player.closeInventory();
            player.sendMessage(Component.text("/bz sell " + product.id() + " <qty> <stt each>   or   /bz order " + product.id() + " <qty> <stt each>", NamedTextColor.YELLOW));
        });
        view.set(31, icon(Material.ARROW, "Back", NamedTextColor.GRAY, List.of()), click -> openBazaar(player));
        player.openInventory(view.getInventory());
    }

    String name(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "?" : name;
    }

    Offer bestOffer(String key, UUID notFrom) {
        Offer best = null;
        for (Offer offer : offers.values()) {
            if (!offer.key.equals(key) || offer.busy || offer.seller.equals(notFrom)) {
                continue;
            }
            if (best == null || offer.unit.compareTo(best.unit) < 0) {
                best = offer;
            }
        }
        return best;
    }

    Order bestOrder(String key, UUID notFrom) {
        Order best = null;
        for (Order order : orders.values()) {
            if (!order.key.equals(key) || order.busy || order.left <= 0 || order.buyer.equals(notFrom)) {
                continue;
            }
            if (best == null || order.unit.compareTo(best.unit) > 0) {
                best = order;
            }
        }
        return best;
    }

    int supply(String key) {
        return offers.values().stream().filter(o -> o.key.equals(key)).mapToInt(o -> o.qty).sum();
    }

    int demand(String key) {
        return orders.values().stream().filter(o -> o.key.equals(key)).mapToInt(o -> o.left).sum();
    }

    void createOffer(Player player, Product product, int qty, BigInteger unit) {
        if (!chainReady(player)) {
            return;
        }
        if (qty <= 0 || qty > 2240) {
            player.sendMessage(Component.text("Quantity must be 1..2240.", NamedTextColor.RED));
            return;
        }
        if (!lock(player)) {
            return;
        }
        if (!take(player, product.key(), qty)) {
            unlock(player.getUniqueId());
            player.sendMessage(Component.text("You do not have " + qty + "x " + product.name() + ".", NamedTextColor.RED));
            return;
        }
        UUID me = player.getUniqueId();
        afterBag(player, ok -> {
            unlock(me);
            Player now = plugin.getServer().getPlayer(me);
            if (!ok) {
                // The rollback put the items back in the bag; no offer is made from items that are still there.
                message(me, "Sell offer not created: the bag write did not land (Not enough STT for gas? use /deposit). Your items stay in your bag.");
                return;
            }
            if (now != null) {
                listOffer(now, product, qty, unit);
            } else {
                giveOrStash(me, product.key(), qty, false);
            }
        }, () -> stash(me, product.key(), qty));
    }

    private void listOffer(Player player, Product product, int qty, BigInteger unit) {
        Offer offer = new Offer();
        offer.id = nextOffer++;
        offer.seller = player.getUniqueId();
        offer.wallet = plugin.profiles().get(player.getUniqueId()).wallet;
        offer.key = product.key();
        offer.qty = qty;
        offer.unit = unit;
        offers.put(offer.id, offer);
        save();
        player.sendMessage(Component.text("Sell offer #" + offer.id + ": " + qty + "x " + product.name() + " @ " + RealmChain.stt(unit)
                + " STT. Buyers pay your purse on-chain (minus 1.25% tax). /bz cancel " + offer.id, NamedTextColor.GREEN));
        plugin.quests().hit(player, "bazaar", 1);
    }

    void buyOffer(Player player, Offer offer, int qty) {
        if (!chainReady(player) || qty <= 0) {
            return;
        }
        // The menu may be older than the offer: it can be sold out, partly sold or cancelled since.
        if (offers.get(offer.id) != offer || qty > offer.qty) {
            player.sendMessage(Component.text("That offer changed: " + (offers.get(offer.id) == offer ? "only " + offer.qty + "x left." : "it is gone.") + " Open the Bazaar again.", NamedTextColor.RED));
            return;
        }
        if (offer.seller.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("That is your own offer.", NamedTextColor.RED));
            return;
        }
        if (offer.busy) {
            player.sendMessage(Component.text("Someone is buying that offer right now. Try again in a moment.", NamedTextColor.GRAY));
            return;
        }
        if (!lock(player)) {
            return;
        }
        offer.busy = true;
        BigInteger cost = offer.unit.multiply(BigInteger.valueOf(qty));
        int item = HoldItems.idOfKey(offer.key);
        UUID buyer = player.getUniqueId();
        plugin.realm().send(buyer, "Bazaar buy " + qty + "x " + label(offer.key), RealmChain.fillOffer(offer.id, offer.wallet, item, qty), cost,
                receipt -> {
                    unlock(buyer);
                    offer.busy = false;
                    offer.qty -= qty;
                    if (offer.qty <= 0) {
                        offers.remove(offer.id);
                    }
                    save();
                    giveOrStash(buyer, offer.key, qty, true);
                    questHit(buyer, "bazaar");
                    Player seller = plugin.getServer().getPlayer(offer.seller);
                    if (seller != null) {
                        seller.sendMessage(plugin.realm().txLine("Bazaar: sold " + qty + "x " + label(offer.key) + " for " + RealmChain.stt(cost) + " STT (−1.25% tax).", receipt.getTransactionHash()));
                    }
                },
                reason -> {
                    unlock(buyer);
                    offer.busy = false;
                    Player online = plugin.getServer().getPlayer(buyer);
                    if (online != null) {
                        online.sendMessage(Component.text("Bazaar buy failed: " + reason, NamedTextColor.RED));
                    }
                });
    }

    void createOrder(Player player, Product product, int qty, BigInteger unit) {
        if (!chainReady(player) || !lock(player)) {
            return;
        }
        if (qty <= 0 || qty > 2240) {
            unlock(player.getUniqueId());
            player.sendMessage(Component.text("Quantity must be 1..2240.", NamedTextColor.RED));
            return;
        }
        BigInteger escrow = unit.multiply(BigInteger.valueOf(qty));
        UUID buyer = player.getUniqueId();
        plugin.realm().send(buyer, "Bazaar buy order " + qty + "x " + product.name(), RealmChain.placeBuyOrder(HoldItems.idOfKey(product.key()), qty, unit), escrow,
                receipt -> {
                    unlock(buyer);
                    BigInteger id = RealmChain.indexed(receipt, "BuyOrderPlaced(uint256,address,uint32,uint32,uint256)", 1);
                    if (id == null) {
                        plugin.getLogger().warning("BuyOrderPlaced event missing in " + receipt.getTransactionHash());
                        return;
                    }
                    Order order = new Order();
                    order.id = id.longValueExact();
                    order.buyer = buyer;
                    order.key = product.key();
                    order.left = qty;
                    order.unit = unit;
                    orders.put(order.id, order);
                    save();
                    questHit(buyer, "bazaar");
                    Player online = plugin.getServer().getPlayer(buyer);
                    if (online != null) {
                        online.sendMessage(Component.text("Buy order #" + order.id + " is live: " + RealmChain.stt(escrow) + " STT escrowed in SkyRealm. /bz cancel " + order.id + " refunds the rest.", NamedTextColor.GREEN));
                    }
                },
                reason -> {
                    unlock(buyer);
                    Player online = plugin.getServer().getPlayer(buyer);
                    if (online != null) {
                        online.sendMessage(Component.text("Buy order failed: " + reason, NamedTextColor.RED));
                    }
                });
    }

    void sellToOrder(Player player, Order order, int qty) {
        if (!chainReady(player) || order.busy || qty <= 0) {
            if (qty <= 0) {
                player.sendMessage(Component.text("You have none to sell.", NamedTextColor.RED));
            }
            return;
        }
        if (orders.get(order.id) != order || qty > order.left) {
            player.sendMessage(Component.text("That buy order changed: " + (orders.get(order.id) == order ? "only " + order.left + "x left." : "it is gone.") + " Open the Bazaar again.", NamedTextColor.RED));
            return;
        }
        if (order.buyer.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("That is your own buy order.", NamedTextColor.RED));
            return;
        }
        if (!lock(player)) {
            return;
        }
        if (!take(player, order.key, qty)) {
            unlock(player.getUniqueId());
            return;
        }
        order.busy = true;
        UUID seller = player.getUniqueId();
        afterBag(player, ok -> {
            if (!ok) {
                unlock(seller);
                order.busy = false;
                message(seller, "Not sold: the bag write did not land (Not enough STT for gas? use /deposit). Your items stay in your bag.");
                return;
            }
            fillOrder(seller, order, qty);
        }, () -> stash(seller, order.key, qty));
    }

    private void fillOrder(UUID seller, Order order, int qty) {
        plugin.realm().send(seller, "Bazaar sell " + qty + "x " + label(order.key), RealmChain.fillBuyOrder(order.id, qty), BigInteger.ZERO,
                receipt -> {
                    unlock(seller);
                    order.busy = false;
                    order.left -= qty;
                    if (order.left <= 0) {
                        orders.remove(order.id);
                    }
                    save();
                    giveOrStash(order.buyer, order.key, qty, true);
                    questHit(seller, "bazaar");
                    Player buyer = plugin.getServer().getPlayer(order.buyer);
                    if (buyer != null) {
                        buyer.sendMessage(Component.text("Bazaar: your order #" + order.id + " received " + qty + "x " + label(order.key) + ".", NamedTextColor.GREEN));
                    }
                },
                reason -> {
                    unlock(seller);
                    order.busy = false;
                    giveOrStash(seller, order.key, qty, true);
                    Player online = plugin.getServer().getPlayer(seller);
                    if (online != null) {
                        online.sendMessage(Component.text("Sell failed, items returned: " + reason, NamedTextColor.RED));
                    }
                });
    }

    void cancel(Player player, long id) {
        Offer offer = offers.get(id);
        if (offer != null && offer.seller.equals(player.getUniqueId())) {
            if (offer.busy) {
                player.sendMessage(Component.text("Someone is buying it right now.", NamedTextColor.GRAY));
                return;
            }
            offers.remove(id);
            save();
            giveOrStash(player.getUniqueId(), offer.key, offer.qty, true);
            player.sendMessage(Component.text("Offer #" + id + " cancelled, items returned.", NamedTextColor.GREEN));
            return;
        }
        Order order = orders.get(id);
        if (order != null && order.buyer.equals(player.getUniqueId())) {
            if (order.busy || !lock(player)) {
                return;
            }
            order.busy = true;
            plugin.realm().send(player.getUniqueId(), "Cancel buy order #" + id, RealmChain.cancelBuyOrder(id), BigInteger.ZERO,
                    receipt -> {
                        unlock(order.buyer);
                        orders.remove(id);
                        save();
                    },
                    reason -> {
                        unlock(order.buyer);
                        order.busy = false;
                        player.sendMessage(Component.text("Cancel failed: " + reason, NamedTextColor.RED));
                    });
            return;
        }
        player.sendMessage(Component.text("No offer or order #" + id + " of yours.", NamedTextColor.RED));
    }

    /** Open Bazaar offers/orders, live auctions (as seller or top bidder), BIN listings and stash kinds of this player. */
    int openTrades(UUID me) {
        long n = offers.values().stream().filter(o -> me.equals(o.seller)).count()
                + orders.values().stream().filter(o -> me.equals(o.buyer)).count()
                + lots.values().stream().filter(l -> !l.done && (me.equals(l.seller) || me.equals(l.bidder))).count()
                + bins.values().stream().filter(b -> me.equals(b.seller)).count();
        return (int) n;
    }

    boolean hasStash(UUID me) {
        Map<String, Integer> mine = stash.get(me);
        return mine != null && !mine.isEmpty();
    }

    void listMine(Player player) {
        UUID me = player.getUniqueId();
        player.sendMessage(Component.text("—— Your Bazaar ——", NamedTextColor.GOLD));
        offers.values().stream().filter(o -> o.seller.equals(me)).forEach(o -> player.sendMessage(Component.text(
                "Offer #" + o.id + ": " + o.qty + "x " + label(o.key) + " @ " + RealmChain.stt(o.unit) + " STT", NamedTextColor.AQUA)));
        orders.values().stream().filter(o -> o.buyer.equals(me)).forEach(o -> player.sendMessage(Component.text(
                "Order #" + o.id + ": " + o.left + "x " + label(o.key) + " @ " + RealmChain.stt(o.unit) + " STT (escrowed)", NamedTextColor.AQUA)));
        lots.values().stream().filter(l -> l.seller.equals(me) && !l.done).forEach(l -> player.sendMessage(Component.text(
                "Auction #" + l.id + ": " + l.qty + "x " + label(l.key) + " bid " + RealmChain.stt(l.bid) + " STT", NamedTextColor.LIGHT_PURPLE)));
        Map<String, Integer> mine = stash.get(me);
        if (mine != null) {
            mine.forEach((k, v) -> player.sendMessage(Component.text("Stash: " + v + "x " + label(k) + " (/bz claim)", NamedTextColor.YELLOW)));
        }
    }

    // ---------- Auction House ----------

    void openAuctions(Player player) {
        plugin.marketGui().auctionHome(player);
    }

    void openAuctionsClassic(Player player) {
        View view = new View(54, "Auction House — STT escrow");
        frame(view);
        int slot = 10;
        long now = System.currentTimeMillis();
        for (Lot lot : lots.values()) {
            if (lot.done) {
                continue;
            }
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 44) {
                break;
            }
            BigInteger next = RealmChain.minBid(lot.start, lot.bid);
            long left = Math.max(0, lot.end - now) / 1000;
            ItemStack stack = itemIcon(lot.key, lot.qty);
            boolean mine = lot.seller.equals(player.getUniqueId());
            decorate(stack, null, NamedTextColor.WHITE, List.of(
                    "Seller: " + name(lot.seller),
                    "~" + (lot.bid.signum() == 0 ? "Starting bid: " + RealmChain.stt(lot.start) : "Top bid: " + RealmChain.stt(lot.bid) + " by " + name(lot.bidder)) + " STT",
                    left > 0 ? "Ends in " + (left / 60) + "m " + (left % 60) + "s" : "-Ended, settling on-chain…",
                    mine ? "-Your auction" : left > 0 ? "!Click to bid " + RealmChain.stt(next) + " STT" : "",
                    "Bids are escrowed in SkyRealm; outbid STT is refunded."));
            view.set(slot, stack, mine || left <= 0 ? null : click -> {
                player.closeInventory();
                bid(player, lot);
            });
            slot++;
        }
        view.set(49, icon(Material.GOLDEN_HORSE_ARMOR, "Create an auction", NamedTextColor.GOLD, List.of(
                "Hold the item, then:",
                "~/ah sell <start stt> [minutes]",
                "Listing fee: 1% of start (min 0.001 STT) → Relic Pool",
                "Tax at settlement: 2% → Relic Pool",
                "Bids +5% min, 30s anti-snipe, refunds on-chain.")), null);
        player.openInventory(view.getInventory());
    }

    void createAuction(Player player, BigInteger start, int minutes) {
        createAuction(player, player.getInventory().getHeldItemSlot(), start, minutes);
    }

    /** Bid auction (SkyRealm) for the stack in an inventory slot. The item leaves the inventory first and comes back if the tx fails. */
    void createAuction(Player player, int slotIndex, BigInteger start, int minutes) {
        if (!chainReady(player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItem(slotIndex);
        String key = HoldItems.keyOf(hand, plugin);
        if (key == null) {
            player.sendMessage(Component.text("Hold the item you want to auction.", NamedTextColor.RED));
            return;
        }
        if (minutes < 1 || minutes > 2880) {
            player.sendMessage(Component.text("Duration is 1..2880 minutes.", NamedTextColor.RED));
            return;
        }
        if (!lock(player)) {
            return;
        }
        int qty = hand.getAmount();
        player.getInventory().setItem(slotIndex, null);
        UUID seller = player.getUniqueId();
        afterBag(player, ok -> {
            if (!ok) {
                unlock(seller);
                message(seller, "Auction not created: the bag write did not land (Not enough STT for gas? use /deposit). The item stays in your bag.");
                return;
            }
            listAuction(seller, key, qty, start, minutes);
        }, () -> stash(seller, key, qty));
    }

    private void listAuction(UUID seller, String key, int qty, BigInteger start, int minutes) {
        BigInteger fee = RealmChain.listingFee(start);
        plugin.realm().send(seller, "Auction " + qty + "x " + label(key) + " (fee " + RealmChain.stt(fee) + " STT)",
                RealmChain.createAuction(HoldItems.idOfKey(key), qty, start, minutes * 60), fee,
                receipt -> {
                    unlock(seller);
                    BigInteger id = RealmChain.indexed(receipt, "AuctionCreated(uint256,address,uint32,uint32,uint256,uint64,uint256)", 1);
                    if (id == null) {
                        giveOrStash(seller, key, qty, true);
                        return;
                    }
                    Lot lot = new Lot();
                    lot.id = id.longValueExact();
                    lot.seller = seller;
                    lot.key = key;
                    lot.qty = qty;
                    lot.start = start;
                    lot.end = System.currentTimeMillis() + minutes * 60_000L;
                    lots.put(lot.id, lot);
                    save();
                    questHit(seller, "auction");
                    Bukkit.broadcast(Component.text("[AH] " + name(seller) + " listed " + qty + "x " + label(key) + " from " + RealmChain.stt(start) + " STT (" + minutes + "m). /ah", NamedTextColor.LIGHT_PURPLE));
                },
                reason -> {
                    unlock(seller);
                    giveOrStash(seller, key, qty, true);
                    Player online = plugin.getServer().getPlayer(seller);
                    if (online != null) {
                        online.sendMessage(Component.text("Auction not created, item returned: " + reason, NamedTextColor.RED));
                    }
                });
    }

    void bid(Player player, Lot lot) {
        bid(player, lot, RealmChain.minBid(lot.start, lot.bid));
    }

    void bid(Player player, Lot lot, BigInteger amount) {
        if (!chainReady(player) || lot.busy || lot.done || lots.get(lot.id) != lot) {
            return;
        }
        if (lot.seller.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You cannot bid on your own auction.", NamedTextColor.RED));
            return;
        }
        if (System.currentTimeMillis() >= lot.end) {
            player.sendMessage(Component.text("That auction has ended.", NamedTextColor.RED));
            return;
        }
        if (amount.compareTo(RealmChain.minBid(lot.start, lot.bid)) < 0) {
            player.sendMessage(Component.text("The minimum bid is " + RealmChain.stt(RealmChain.minBid(lot.start, lot.bid)) + " STT.", NamedTextColor.RED));
            return;
        }
        if (!lock(player)) {
            return;
        }
        UUID bidder = player.getUniqueId();
        UUID previous = lot.bidder;
        lot.busy = true;
        plugin.realm().send(bidder, "Bid " + RealmChain.stt(amount) + " STT on auction #" + lot.id, RealmChain.bid(lot.id), amount,
                receipt -> {
                    unlock(bidder);
                    lot.busy = false;
                    lot.bid = amount;
                    lot.bidder = bidder;
                    questHit(bidder, "auction");
                    long now = System.currentTimeMillis();
                    if (lot.end - now < 30_000) {
                        lot.end = now + 30_000;
                    }
                    save();
                    if (previous != null && !previous.equals(bidder)) {
                        Player old = plugin.getServer().getPlayer(previous);
                        if (old != null) {
                            old.sendMessage(Component.text("[AH] You were outbid on " + label(lot.key) + ". Your STT is coming back to your purse.", NamedTextColor.YELLOW));
                        }
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.realm().claimRefund(previous), 40L);
                    }
                    Player seller = plugin.getServer().getPlayer(lot.seller);
                    if (seller != null) {
                        seller.sendMessage(Component.text("[AH] New bid " + RealmChain.stt(amount) + " STT on your " + label(lot.key) + ".", NamedTextColor.GOLD));
                    }
                },
                reason -> {
                    unlock(bidder);
                    lot.busy = false;
                    Player online = plugin.getServer().getPlayer(bidder);
                    if (online != null) {
                        online.sendMessage(Component.text("Bid failed: " + reason, NamedTextColor.RED));
                    }
                });
    }

    // ---------- Buy-It-Now (SkyBin) ----------

    /** Lists the stack in an inventory slot as BIN. The item leaves first and is returned if the listing tx fails. */
    void createBin(Player player, int slotIndex, BigInteger price, int minutes) {
        if (!chainReady(player)) {
            return;
        }
        if (!plugin.realm().binEnabled()) {
            player.sendMessage(Component.text("Buy-It-Now is not configured on this server.", NamedTextColor.RED));
            return;
        }
        ItemStack stack = player.getInventory().getItem(slotIndex);
        String key = HoldItems.keyOf(stack, plugin);
        if (key == null) {
            player.sendMessage(Component.text("Pick an item to sell first.", NamedTextColor.RED));
            return;
        }
        if (minutes < 1 || minutes > 20160) {
            player.sendMessage(Component.text("Duration is 1 minute .. 14 days.", NamedTextColor.RED));
            return;
        }
        if (!lock(player)) {
            return;
        }
        int qty = stack.getAmount();
        player.getInventory().setItem(slotIndex, null);
        UUID seller = player.getUniqueId();
        afterBag(player, ok -> {
            if (!ok) {
                unlock(seller);
                message(seller, "BIN not listed: the bag write did not land (Not enough STT for gas? use /deposit). The item stays in your bag.");
                return;
            }
            listBin(seller, key, qty, price, minutes);
        }, () -> stash(seller, key, qty));
    }

    private void listBin(UUID seller, String key, int qty, BigInteger price, int minutes) {
        BigInteger fee = RealmChain.binFee(price);
        plugin.realm().sendBin(seller, "BIN " + qty + "x " + label(key) + " for " + RealmChain.stt(price) + " STT (fee " + RealmChain.stt(fee) + ")",
                RealmChain.binList(HoldItems.idOfKey(key), qty, price, minutes * 60), fee,
                receipt -> {
                    unlock(seller);
                    BigInteger id = RealmChain.indexed(receipt, "BinListed(uint256,address,uint32,uint32,uint256,uint64,uint256)", 1);
                    if (id == null) {
                        plugin.getLogger().warning("BinListed event missing in " + receipt.getTransactionHash());
                        giveOrStash(seller, key, qty, true);
                        return;
                    }
                    Bin bin = new Bin();
                    bin.id = id.longValueExact();
                    bin.seller = seller;
                    bin.key = key;
                    bin.qty = qty;
                    bin.price = price;
                    bin.end = System.currentTimeMillis() + minutes * 60_000L;
                    bins.put(bin.id, bin);
                    save();
                    questHit(seller, "auction");
                    Bukkit.broadcast(Component.text("[AH] " + name(seller) + " listed " + qty + "x " + label(key) + " BIN " + RealmChain.stt(price) + " STT. /ah", NamedTextColor.LIGHT_PURPLE));
                },
                reason -> {
                    unlock(seller);
                    giveOrStash(seller, key, qty, true);
                    message(seller, "BIN not listed, item returned: " + reason);
                });
    }

    void buyBin(Player player, Bin bin) {
        if (!chainReady(player) || bin.busy || !bins.containsKey(bin.id)) {
            return;
        }
        if (bin.seller.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("That is your own listing.", NamedTextColor.RED));
            return;
        }
        if (System.currentTimeMillis() >= bin.end) {
            player.sendMessage(Component.text("That listing has expired.", NamedTextColor.RED));
            return;
        }
        if (!lock(player)) {
            return;
        }
        bin.busy = true;
        UUID buyer = player.getUniqueId();
        plugin.realm().sendBin(buyer, "Buy " + bin.qty + "x " + label(bin.key) + " (BIN #" + bin.id + ")", RealmChain.binBuy(bin.id), bin.price,
                receipt -> {
                    unlock(buyer);
                    bins.remove(bin.id);
                    save();
                    giveOrStash(buyer, bin.key, bin.qty, true);
                    questHit(buyer, "auction");
                    message(buyer, "[AH] You bought " + bin.qty + "x " + label(bin.key) + " for " + RealmChain.stt(bin.price) + " STT.");
                    Player seller = plugin.getServer().getPlayer(bin.seller);
                    if (seller != null) {
                        seller.sendMessage(plugin.realm().txLine("[AH] " + name(buyer) + " bought your " + label(bin.key) + " for "
                                + RealmChain.stt(bin.price) + " STT (−2% tax: half burned, half to the Relic Pool).", receipt.getTransactionHash()));
                    }
                },
                reason -> {
                    unlock(buyer);
                    bin.busy = false;
                    message(buyer, "Purchase failed, nothing was charged: " + reason);
                });
    }

    /** Cancels an open BIN on-chain; the item comes back after the receipt. */
    void cancelBin(Player player, Bin bin) {
        if (!bin.seller.equals(player.getUniqueId()) || bin.busy || !lock(player)) {
            return;
        }
        bin.busy = true;
        UUID seller = player.getUniqueId();
        if (System.currentTimeMillis() >= bin.end) {
            // Expired listings cannot be bought on-chain any more; return without a transaction.
            unlock(seller);
            bins.remove(bin.id);
            save();
            giveOrStash(seller, bin.key, bin.qty, true);
            message(seller, "[AH] Expired BIN #" + bin.id + " collected.");
            return;
        }
        plugin.realm().sendBin(seller, "Cancel BIN #" + bin.id, RealmChain.binCancel(bin.id), BigInteger.ZERO,
                receipt -> {
                    unlock(seller);
                    bins.remove(bin.id);
                    save();
                    giveOrStash(seller, bin.key, bin.qty, true);
                    message(seller, "[AH] BIN #" + bin.id + " cancelled, item returned (the listing fee is not refunded).");
                },
                reason -> {
                    unlock(seller);
                    bin.busy = false;
                    message(seller, "Cancel failed: " + reason);
                });
    }

    private void expireBins() {
        long now = System.currentTimeMillis();
        for (Bin bin : new ArrayList<>(bins.values())) {
            if (!bin.busy && now >= bin.end + 5000) {
                bins.remove(bin.id);
                giveOrStash(bin.seller, bin.key, bin.qty, true);
                message(bin.seller, "[AH] Your BIN #" + bin.id + " (" + label(bin.key) + ") expired unsold and was returned.");
                save();
            }
        }
    }

    private void settleTick() {
        long now = System.currentTimeMillis();
        for (Lot lot : new ArrayList<>(lots.values())) {
            if (lot.done || lot.busy || now < lot.end + 3000) {
                continue;
            }
            lot.busy = true;
            UUID payer = lot.tries % 2 == 1 && lot.bidder != null ? lot.bidder : lot.seller;
            lot.tries++;
            plugin.realm().send(payer, "Settle auction #" + lot.id, RealmChain.settle(lot.id), BigInteger.ZERO,
                    receipt -> {
                        lot.busy = false;
                        lot.done = true;
                        lots.remove(lot.id);
                        save();
                        if (lot.bidder != null) {
                            giveOrStash(lot.bidder, lot.key, lot.qty, true);
                            message(lot.bidder, "[AH] You won " + lot.qty + "x " + label(lot.key) + " for " + RealmChain.stt(lot.bid) + " STT.");
                            message(lot.seller, "[AH] Sold " + label(lot.key) + " for " + RealmChain.stt(lot.bid) + " STT (−2% tax), paid by SkyRealm.");
                        } else {
                            giveOrStash(lot.seller, lot.key, lot.qty, true);
                            message(lot.seller, "[AH] No bids on " + label(lot.key) + ". Item returned.");
                        }
                    },
                    reason -> {
                        lot.busy = false;
                        if (reason.contains("running")) {
                            lot.end = System.currentTimeMillis() + 15_000;
                        } else {
                            lot.end = System.currentTimeMillis() + 60_000;
                        }
                        if (reason.contains("closed") || reason.contains("bad")) {
                            plugin.getLogger().warning("Auction " + lot.id + " settle: " + reason);
                        }
                    });
        }
    }

    void questHit(UUID id, String event) {
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            plugin.quests().hit(player, event, 1);
        }
    }

    void message(UUID id, String text) {
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            player.sendMessage(Component.text(text, NamedTextColor.LIGHT_PURPLE));
        }
    }

    // ---------- Relic bank ----------

    void openBank(Player player) {
        if (!chainReady(player)) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        player.sendMessage(Component.text("Reading the Relic Pool from Somnia…", NamedTextColor.DARK_AQUA));
        plugin.realm().view(RealmChain.relicsOf(profile.wallet), mine -> plugin.realm().view(RealmChain.uintView("relicValue"), value ->
                plugin.realm().view(RealmChain.uintView("pool"), pool -> plugin.realm().view(RealmChain.uintView("totalRelics"), total -> {
                    long relics = ((BigInteger) mine.get(0).getValue()).longValue();
                    BigInteger each = (BigInteger) value.get(0).getValue();
                    View view = new View(27, "Banker — Relic Pool");
                    frame(view);
                    view.set(11, icon(Material.NETHER_STAR, "Your relics: " + relics, NamedTextColor.LIGHT_PURPLE, List.of(
                            "~Worth now: " + RealmChain.stt(each) + " STT each",
                            "Relics are rolled by Somnia VRF after ranked",
                            "Colosseum runs (wave 3+).",
                            relics > 0 ? "!Click to cash in all (" + RealmChain.stt(each.multiply(BigInteger.valueOf(relics))) + " STT)" : "-Win a ranked run to earn relics")),
                            relics <= 0 ? null : click -> {
                                player.closeInventory();
                                cashIn(player, relics);
                            });
                    view.set(15, icon(Material.GOLD_BLOCK, "Relic Pool", NamedTextColor.GOLD, List.of(
                            "~Pool: " + RealmChain.stt((BigInteger) pool.get(0).getValue()) + " STT",
                            "~Relics in circulation: " + total.get(0).getValue(),
                            "Funded only by player fees: bazaar tax 1.25%,",
                            "auction fee 1% + tax 2%, ranked entry 0.005 STT.",
                            "Value = pool × 90% / (relics + 10). The server mints nothing.")), null);
                    view.set(22, icon(Material.PAPER, "Purse " + WalletService.shortAddress(profile.wallet), NamedTextColor.AQUA, List.of(
                            "Gas and trades are paid from this purse.", "/deposit shows the full address.")), null);
                    player.openInventory(view.getInventory());
                }, this::bankFail), this::bankFail), this::bankFail), reason -> player.sendMessage(Component.text("Relic Pool read failed: " + reason, NamedTextColor.RED)));
    }

    private void bankFail(String reason) {
        plugin.getLogger().warning("Bank view failed: " + reason);
    }

    void cashIn(Player player, long relics) {
        if (!lock(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        plugin.realm().send(id, "Cash in " + relics + " relic(s)", RealmChain.cashIn(relics), BigInteger.ZERO,
                receipt -> unlock(id),
                reason -> {
                    unlock(id);
                    message(id, "Cash-in failed: " + reason);
                });
    }

    // ---------- persistence ----------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextOffer = yaml.getLong("next-offer", 1);
        ConfigurationSection o = yaml.getConfigurationSection("offers");
        if (o != null) {
            for (String k : o.getKeys(false)) {
                ConfigurationSection s = o.getConfigurationSection(k);
                Offer offer = new Offer();
                offer.id = Long.parseLong(k);
                offer.seller = UUID.fromString(s.getString("seller"));
                offer.wallet = s.getString("wallet");
                offer.key = s.getString("key");
                offer.qty = s.getInt("qty");
                offer.unit = new BigInteger(s.getString("unit"));
                offers.put(offer.id, offer);
            }
        }
        ConfigurationSection b = yaml.getConfigurationSection("orders");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                ConfigurationSection s = b.getConfigurationSection(k);
                Order order = new Order();
                order.id = Long.parseLong(k);
                order.buyer = UUID.fromString(s.getString("buyer"));
                order.key = s.getString("key");
                order.left = s.getInt("left");
                order.unit = new BigInteger(s.getString("unit"));
                orders.put(order.id, order);
            }
        }
        ConfigurationSection a = yaml.getConfigurationSection("auctions");
        if (a != null) {
            for (String k : a.getKeys(false)) {
                ConfigurationSection s = a.getConfigurationSection(k);
                Lot lot = new Lot();
                lot.id = Long.parseLong(k);
                lot.seller = UUID.fromString(s.getString("seller"));
                lot.key = s.getString("key");
                lot.qty = s.getInt("qty");
                lot.start = new BigInteger(s.getString("start"));
                lot.bid = new BigInteger(s.getString("bid", "0"));
                String bidder = s.getString("bidder", "");
                lot.bidder = bidder.isEmpty() ? null : UUID.fromString(bidder);
                lot.end = s.getLong("end");
                lots.put(lot.id, lot);
            }
        }
        ConfigurationSection bn = yaml.getConfigurationSection("bins");
        if (bn != null) {
            for (String k : bn.getKeys(false)) {
                ConfigurationSection s = bn.getConfigurationSection(k);
                Bin bin = new Bin();
                bin.id = Long.parseLong(k);
                bin.seller = UUID.fromString(s.getString("seller"));
                bin.key = s.getString("key");
                bin.qty = s.getInt("qty");
                bin.price = new BigInteger(s.getString("price"));
                bin.end = s.getLong("end");
                bins.put(bin.id, bin);
            }
        }
        ConfigurationSection st = yaml.getConfigurationSection("stash");
        if (st != null) {
            for (String k : st.getKeys(false)) {
                ConfigurationSection s = st.getConfigurationSection(k);
                Map<String, Integer> items = new LinkedHashMap<>();
                for (String item : s.getKeys(false)) {
                    items.put(item.replace('|', ':'), s.getInt(item));
                }
                stash.put(UUID.fromString(k), items);
            }
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-offer", nextOffer);
        for (Offer offer : offers.values()) {
            String p = "offers." + offer.id + ".";
            yaml.set(p + "seller", offer.seller.toString());
            yaml.set(p + "wallet", offer.wallet);
            yaml.set(p + "key", offer.key);
            yaml.set(p + "qty", offer.qty);
            yaml.set(p + "unit", offer.unit.toString());
        }
        for (Order order : orders.values()) {
            String p = "orders." + order.id + ".";
            yaml.set(p + "buyer", order.buyer.toString());
            yaml.set(p + "key", order.key);
            yaml.set(p + "left", order.left);
            yaml.set(p + "unit", order.unit.toString());
        }
        for (Lot lot : lots.values()) {
            String p = "auctions." + lot.id + ".";
            yaml.set(p + "seller", lot.seller.toString());
            yaml.set(p + "key", lot.key);
            yaml.set(p + "qty", lot.qty);
            yaml.set(p + "start", lot.start.toString());
            yaml.set(p + "bid", lot.bid.toString());
            yaml.set(p + "bidder", lot.bidder == null ? "" : lot.bidder.toString());
            yaml.set(p + "end", lot.end);
        }
        for (Bin bin : bins.values()) {
            String p = "bins." + bin.id + ".";
            yaml.set(p + "seller", bin.seller.toString());
            yaml.set(p + "key", bin.key);
            yaml.set(p + "qty", bin.qty);
            yaml.set(p + "price", bin.price.toString());
            yaml.set(p + "end", bin.end);
        }
        for (Map.Entry<UUID, Map<String, Integer>> entry : stash.entrySet()) {
            for (Map.Entry<String, Integer> item : entry.getValue().entrySet()) {
                yaml.set("stash." + entry.getKey() + "." + item.getKey().replace(':', '|'), item.getValue());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save market.yml: " + exception.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private static List<Type> none() {
        return List.of();
    }
}
