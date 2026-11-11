package dev.somnia.skyblock;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Chest GUIs for the on-chain Bazaar (SkyRealm sell offers / buy orders) and the
 * Auction House (SkyRealm bid auctions + SkyBin Buy-It-Now). Prices are STT; every write is a
 * transaction from the player's own purse (see Market for the item/receipt flow).
 */
final class MarketGui implements Listener {
    enum Cat {
        FARMING("Farming", Material.GOLDEN_HOE, NamedTextColor.YELLOW, Material.YELLOW_STAINED_GLASS_PANE),
        MINING("Mining", Material.DIAMOND_PICKAXE, NamedTextColor.AQUA, Material.CYAN_STAINED_GLASS_PANE),
        COMBAT("Combat", Material.IRON_SWORD, NamedTextColor.RED, Material.RED_STAINED_GLASS_PANE),
        WOODS("Woods & Fishes", Material.FISHING_ROD, NamedTextColor.GOLD, Material.ORANGE_STAINED_GLASS_PANE),
        ODDITIES("Oddities", Material.ENCHANTED_BOOK, NamedTextColor.LIGHT_PURPLE, Material.PINK_STAINED_GLASS_PANE);

        final String label;
        final Material icon;
        final NamedTextColor color;
        final Material pane;

        Cat(String label, Material icon, NamedTextColor color, Material pane) {
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.pane = pane;
        }
    }

    private static final Map<String, Cat> CAT_OF = new HashMap<>();

    static {
        for (String id : List.of("wheat", "carrot", "potato")) CAT_OF.put(id, Cat.FARMING);
        for (String id : List.of("cobblestone", "coal", "raw_iron", "iron_ingot", "raw_gold", "gold_ingot", "gold_block", "enchanted_cobble")) CAT_OF.put(id, Cat.MINING);
        for (String id : List.of("rotten_flesh", "bone", "string", "spider_eye", "enchanted_rotten_flesh", "revenant_flesh", "wolf_tooth")) CAT_OF.put(id, Cat.COMBAT);
        for (String id : List.of("oak_log", "birch_log", "dark_oak_log", "cod", "salmon", "enchanted_oak")) CAT_OF.put(id, Cat.WOODS);
    }

    /** Fallback reference price when a product has no market yet: 0.0001 STT per merchant coin. */
    private static final BigInteger WEI_PER_COIN = new BigInteger("100000000000000");
    private static final int[] GRID = {11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 34, 38, 39, 40, 41, 42, 43};
    private static final int[] AH_GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int[] DURATIONS = {5, 60, 360, 720, 1440, 2880};

    /** Per-player Create Auction screen state. */
    static final class Draft {
        int slot = -1;
        boolean bin = true;
        BigInteger price = new BigInteger("10000000000000000"); // 0.01 STT
        int minutes = 360;
    }

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Map<UUID, int[]> browse = new HashMap<>(); // page, filter (0 all,1 bin,2 auction), sort (0 low,1 high,2 ending)

    MarketGui(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private Market m() {
        return plugin.market();
    }

    // ---------- helpers ----------

    private static ItemStack icon(Material material, String name, NamedTextColor color, List<String> lore) {
        return Market.icon(material, name, color, lore);
    }

    private static ItemStack glint(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Market.View view(int rows, String title, Material pane) {
        Market.View view = new Market.View(rows * 9, title);
        ItemStack filler = icon(pane, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < rows * 9; i++) {
            view.getInventory().setItem(i, filler);
        }
        return view;
    }

    private static String stt(BigInteger wei) {
        return RealmChain.stt(wei) + " STT";
    }

    private static Cat catOf(Market.Product p) {
        return CAT_OF.getOrDefault(p.id(), Cat.ODDITIES);
    }

    private BigInteger reference(Market.Product p) {
        return WEI_PER_COIN.multiply(BigInteger.valueOf(Math.max(1, p.npc())));
    }

    private static BigInteger tick(BigInteger unit) {
        BigInteger step = unit.divide(BigInteger.valueOf(1000));
        BigInteger min = new BigInteger("1000000000000"); // 0.000001 STT
        return step.compareTo(min) < 0 ? min : step;
    }

    private void close(Player player, Market.View view) {
        view.set(view.getInventory().getSize() - 5, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
    }

    private void back(Player player, Market.View view, int slot, String to, Runnable action) {
        view.set(slot, icon(Material.ARROW, "Go Back", NamedTextColor.GREEN, List.of("To " + to)), c -> action.run());
    }

    /** Asks for a value in chat (chat works on every client). */
    void ask(Player player, String question, Consumer<String> answer) {
        player.closeInventory();
        prompts.put(player.getUniqueId(), answer);
        player.sendMessage(Component.text("▶ " + question + " ", NamedTextColor.YELLOW)
                .append(Component.text("(type it in chat, or 'cancel')", NamedTextColor.GRAY)));
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (prompts.remove(id, answer)) {
                Player p = plugin.getServer().getPlayer(id);
                if (p != null) {
                    p.sendMessage(Component.text("Input timed out.", NamedTextColor.GRAY));
                }
            }
        }, 20L * 60);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Consumer<String> answer = prompts.remove(event.getPlayer().getUniqueId());
        if (answer == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
                return;
            }
            try {
                answer.accept(text);
            } catch (IllegalArgumentException | ArithmeticException bad) {
                player.sendMessage(Component.text("Not a valid value: " + (bad.getMessage() == null ? text : bad.getMessage()), NamedTextColor.RED));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
        drafts.remove(event.getPlayer().getUniqueId());
        browse.remove(event.getPlayer().getUniqueId());
    }

    // ================= BAZAAR =================

    void bazaar(Player player, Cat cat) {
        Market.View view = view(6, "Bazaar ➜ " + cat.label, cat.pane);
        Cat[] cats = Cat.values();
        for (int i = 0; i < cats.length; i++) {
            Cat c = cats[i];
            ItemStack stack = icon(c.icon, c.label, c.color, List.of("Category", "", c == cat ? "+Currently viewing!" : "!Click to view!"));
            view.set(i * 9, c == cat ? glint(stack) : stack, click -> bazaar(player, c));
        }
        int g = 0;
        for (Market.Product product : Market.PRODUCTS.values()) {
            if (catOf(product) != cat || g >= GRID.length) {
                continue;
            }
            Market.Offer best = m().bestOffer(product.key(), null);
            Market.Order top = m().bestOrder(product.key(), null);
            ItemStack stack = m().itemIcon(product.key(), 1);
            Market.decorate(stack, product.name(), NamedTextColor.GREEN, List.of(
                    "Buy price: " + (best == null ? "-none" : "$" + stt(best.unit)),
                    "Sell price: " + (top == null ? "-none" : "$" + stt(top.unit)),
                    "",
                    "~" + m().supply(product.key()) + " on sale · " + m().demand(product.key()) + " wanted",
                    "",
                    "!Click to view details!"));
            view.set(GRID[g++], stack, click -> product(player, product));
        }
        view.set(47, icon(Material.CHEST, "Claim Stash", NamedTextColor.YELLOW, List.of(
                "Items bought while you were offline", "or with a full inventory wait here.", "",
                "Kinds waiting: " + m().stashOf(player.getUniqueId()).size(), "", "!Click to claim!")), click -> {
            player.closeInventory();
            m().claim(player);
        });
        view.set(49, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        view.set(50, icon(Material.BOOK, "Manage Orders", NamedTextColor.GREEN, List.of(
                "View your sell offers and", "buy orders, cancel them.", "", "!Click to manage!")), c -> manage(player));
        view.set(51, icon(Material.OAK_SIGN, "How the Bazaar works", NamedTextColor.GOLD, List.of(
                "Prices are STT on Somnia Shannon.",
                "Buy Instantly pays the cheapest seller",
                "  straight from your purse (SkyRealm.fillOffer).",
                "Buy Orders escrow STT in SkyRealm.",
                "-Tax: 1.25% of every fill → Relic Pool.",
                "You pay your own gas.")), null);
        player.openInventory(view.getInventory());
    }

    void product(Player player, Market.Product product) {
        Cat cat = catOf(product);
        Market.View view = view(4, cat.label + " ➜ " + product.name(), Material.GRAY_STAINED_GLASS_PANE);
        Market.Offer best = m().bestOffer(product.key(), player.getUniqueId());
        Market.Order top = m().bestOrder(product.key(), player.getUniqueId());
        int have = m().count(player, product.key());
        view.set(10, icon(Material.GOLDEN_HORSE_ARMOR, "Buy Instantly", NamedTextColor.GREEN, best == null
                ? List.of("-No sell offers from other players.", "", "Create a Buy Order instead.")
                : List.of("Price per unit: $" + stt(best.unit),
                "Stack price: $" + stt(best.unit.multiply(BigInteger.valueOf(64))),
                "Cheapest offer: " + best.qty + "x by " + m().name(best.seller), "", "!Click to pick amount!")),
                click -> {
                    if (best != null) {
                        buyAmount(player, product);
                    }
                });
        view.set(11, icon(Material.HOPPER, "Sell Instantly", NamedTextColor.GOLD, top == null
                ? List.of("-No buy orders from other players.", "", "Create a Sell Offer instead.")
                : List.of("Inventory: " + have + " items", "",
                "Amount: " + Math.min(have, top.left) + "x",
                "Price per unit: $" + stt(top.unit),
                "Total: $" + stt(top.unit.multiply(BigInteger.valueOf(Math.min(have, top.left)))) + " (−1.25% tax)", "",
                have > 0 ? "!Click to sell!" : "-You have none.")),
                click -> {
                    if (top != null && have > 0) {
                        player.closeInventory();
                        m().sellToOrder(player, top, Math.min(m().count(player, product.key()), top.left));
                    }
                });
        ItemStack info = m().itemIcon(product.key(), 1);
        List<String> lore = new ArrayList<>();
        lore.add("You have: " + have);
        lore.add("");
        lore.add("!Top sell offers:");
        m().offers().stream().filter(o -> o.key.equals(product.key())).sorted(Comparator.comparing((Market.Offer o) -> o.unit)).limit(5)
                .forEach(o -> lore.add("~▶ " + stt(o.unit) + " each · " + o.qty + "x · " + m().name(o.seller)));
        lore.add("!Top buy orders:");
        m().orders().stream().filter(o -> o.key.equals(product.key()) && o.left > 0).sorted(Comparator.comparing((Market.Order o) -> o.unit).reversed()).limit(5)
                .forEach(o -> lore.add("~▶ " + stt(o.unit) + " each · " + o.left + "x · " + m().name(o.buyer)));
        Market.decorate(info, product.name(), NamedTextColor.GREEN, lore);
        view.set(13, info, null);
        view.set(15, icon(Material.FILLED_MAP, "Create Buy Order", NamedTextColor.GREEN, List.of(
                top == null ? "-No buy orders yet." : "Top order: $" + stt(top.unit),
                "Your STT is escrowed in SkyRealm", "until sellers fill the order.", "", "!Click to setup buy order!")),
                click -> orderAmount(player, product));
        view.set(16, icon(Material.MAP, "Create Sell Offer", NamedTextColor.GOLD, List.of(
                best == null ? "-No sell offers yet." : "Best offer: $" + stt(best.unit),
                "Inventory: " + have + " items", "Buyers pay your purse directly.", "",
                have > 0 ? "!Click to pick a price!" : "-You have none to sell.")),
                click -> {
                    if (have > 0) {
                        offerPrice(player, product, have);
                    }
                });
        back(player, view, 30, "Bazaar", () -> bazaar(player, cat));
        view.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }

    private void buyAmount(Player player, Market.Product product) {
        Market.Offer best = m().bestOffer(product.key(), player.getUniqueId());
        if (best == null) {
            product(player, product);
            return;
        }
        Market.View view = view(4, product.name() + " ➜ Instant Buy", Material.GRAY_STAINED_GLASS_PANE);
        int free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                free += 64;
            }
        }
        int fill = Math.max(1, Math.min(free, best.qty));
        int[][] choices = {{10, 1}, {12, Math.min(64, best.qty)}, {14, fill}};
        String[] names = {"Buy only one!", "Buy a stack!", "Fill my inventory!"};
        Material[] mats = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        for (int i = 0; i < choices.length; i++) {
            int qty = choices[i][1];
            view.set(choices[i][0], icon(mats[i], names[i], NamedTextColor.GREEN, List.of(
                    "Amount: " + qty + "x", "Price per unit: $" + stt(best.unit), "Total: $" + stt(best.unit.multiply(BigInteger.valueOf(qty))), "",
                    "From the cheapest offer (" + best.qty + "x left).", "", "!Click to buy now!")), c -> {
                player.closeInventory();
                m().buyOffer(player, best, qty);
            });
        }
        view.set(16, icon(Material.OAK_SIGN, "Custom Amount", NamedTextColor.GREEN, List.of("Buy up to " + best.qty + "x.", "", "!Click to type an amount!")),
                c -> ask(player, "How many " + product.name() + " to buy (max " + best.qty + ")?", text -> {
                    int qty = Integer.parseInt(text);
                    if (qty <= 0 || qty > best.qty) {
                        throw new IllegalArgumentException("1.." + best.qty);
                    }
                    m().buyOffer(player, best, qty);
                }));
        back(player, view, 30, product.name(), () -> product(player, product));
        view.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }

    private void orderAmount(Player player, Market.Product product) {
        Market.View view = view(4, "How many do you want?", Material.GRAY_STAINED_GLASS_PANE);
        int[][] choices = {{10, 64}, {12, 160}, {14, 1024}};
        Material[] mats = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        for (int i = 0; i < choices.length; i++) {
            int qty = choices[i][1];
            view.set(choices[i][0], icon(mats[i], "Buy " + (qty == 64 ? "a stack" : qty + "x") + "!", NamedTextColor.GREEN, List.of("Amount: " + qty + "x", "", "!Click to pick a price!")),
                    c -> orderPrice(player, product, qty));
        }
        view.set(16, icon(Material.OAK_SIGN, "Custom Amount", NamedTextColor.GREEN, List.of("Order 1..2240 items.", "", "!Click to type an amount!")),
                c -> ask(player, "How many " + product.name() + " do you want to order (1..2240)?", text -> {
                    int qty = Integer.parseInt(text);
                    if (qty <= 0 || qty > 2240) {
                        throw new IllegalArgumentException("1..2240");
                    }
                    orderPrice(player, product, qty);
                }));
        back(player, view, 30, product.name(), () -> product(player, product));
        view.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }

    private void orderPrice(Player player, Market.Product product, int qty) {
        Market.Order top = m().bestOrder(product.key(), null);
        Market.Offer best = m().bestOffer(product.key(), null);
        BigInteger same = top != null ? top.unit : best != null ? best.unit.multiply(BigInteger.valueOf(9)).divide(BigInteger.TEN) : reference(product);
        BigInteger plus = same.add(tick(same));
        BigInteger spread = best != null && top != null && best.unit.compareTo(top.unit) > 0
                ? top.unit.add(best.unit.subtract(top.unit).divide(BigInteger.valueOf(20))) : plus;
        Market.View view = view(4, "At what price are you buying?", Material.GRAY_STAINED_GLASS_PANE);
        priceChoice(player, view, 10, Material.GOLD_NUGGET, top == null ? "Market reference" : "Same as Top Order", same, qty, u -> confirmOrder(player, product, qty, u));
        priceChoice(player, view, 12, Material.GOLD_INGOT, "Top Order +0.1%", plus, qty, u -> confirmOrder(player, product, qty, u));
        priceChoice(player, view, 14, Material.GOLDEN_HORSE_ARMOR, "5% of Spread", spread, qty, u -> confirmOrder(player, product, qty, u));
        view.set(16, icon(Material.OAK_SIGN, "Custom Price", NamedTextColor.GREEN, List.of("Set the STT price per unit.", "", "!Click to type a price!")),
                c -> ask(player, "Price per " + product.name() + " in STT (e.g. 0.0005)?", text -> confirmOrder(player, product, qty, RealmChain.wei(text))));
        back(player, view, 30, "amount", () -> orderAmount(player, product));
        view.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }

    private void priceChoice(Player player, Market.View view, int slot, Material mat, String name, BigInteger unit, int qty, Consumer<BigInteger> pick) {
        view.set(slot, icon(mat, name, NamedTextColor.GREEN, List.of(
                "Price per unit: $" + stt(unit), "Total: $" + stt(unit.multiply(BigInteger.valueOf(qty))), "", "!Click to use this price!")), c -> pick.accept(unit));
    }

    private void confirmOrder(Player player, Market.Product product, int qty, BigInteger unit) {
        Market.View view = view(3, "Confirm Buy Order", Material.GRAY_STAINED_GLASS_PANE);
        BigInteger total = unit.multiply(BigInteger.valueOf(qty));
        view.set(11, icon(Material.LIME_TERRACOTTA, "Confirm Buy Order", NamedTextColor.GREEN, List.of(
                "Order: " + qty + "x " + product.name(), "Price per unit: $" + stt(unit), "", "Escrow: $" + stt(total),
                "+ gas, paid from your purse.", "", "!Click to sign on Somnia!")), c -> {
            player.closeInventory();
            m().createOrder(player, product, qty, unit);
        });
        view.set(13, m().itemIcon(product.key(), Math.min(64, qty)), null);
        view.set(15, icon(Material.RED_TERRACOTTA, "Cancel", NamedTextColor.RED, List.of()), c -> product(player, product));
        player.openInventory(view.getInventory());
    }

    private void offerPrice(Player player, Market.Product product, int qty) {
        Market.Offer best = m().bestOffer(product.key(), null);
        Market.Order top = m().bestOrder(product.key(), null);
        BigInteger same = best != null ? best.unit : top != null ? top.unit.multiply(BigInteger.valueOf(11)).divide(BigInteger.TEN) : reference(product);
        BigInteger minus = same.subtract(tick(same)).max(BigInteger.ONE);
        BigInteger spread = best != null && top != null && best.unit.compareTo(top.unit) > 0
                ? best.unit.subtract(best.unit.subtract(top.unit).divide(BigInteger.TEN)) : minus;
        Market.View view = view(4, "At what price are you selling?", Material.GRAY_STAINED_GLASS_PANE);
        priceChoice(player, view, 10, Material.GOLD_NUGGET, best == null ? "Market reference" : "Same as Best Offer", same, qty, u -> confirmOffer(player, product, qty, u));
        priceChoice(player, view, 12, Material.GOLD_INGOT, "Best Offer -0.1%", minus, qty, u -> confirmOffer(player, product, qty, u));
        priceChoice(player, view, 14, Material.GOLDEN_HORSE_ARMOR, "10% of Spread", spread, qty, u -> confirmOffer(player, product, qty, u));
        view.set(16, icon(Material.OAK_SIGN, "Custom Price", NamedTextColor.GREEN, List.of("Set the STT price per unit.", "", "!Click to type a price!")),
                c -> ask(player, "Price per " + product.name() + " in STT (e.g. 0.0005)?", text -> confirmOffer(player, product, qty, RealmChain.wei(text))));
        back(player, view, 30, product.name(), () -> product(player, product));
        view.set(31, icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }

    private void confirmOffer(Player player, Market.Product product, int qty, BigInteger unit) {
        Market.View view = view(3, "Confirm Sell Offer", Material.GRAY_STAINED_GLASS_PANE);
        BigInteger total = unit.multiply(BigInteger.valueOf(qty));
        BigInteger tax = total.multiply(BigInteger.valueOf(125)).divide(BigInteger.valueOf(10000));
        view.set(11, icon(Material.LIME_TERRACOTTA, "Confirm Sell Offer", NamedTextColor.GREEN, List.of(
                "Selling: " + qty + "x " + product.name(), "Price per unit: $" + stt(unit), "",
                "You earn: $" + stt(total.subtract(tax)), "-Tax 1.25%: " + stt(tax), "",
                "Items wait on the server until bought.", "!Click to list!")), c -> {
            player.closeInventory();
            m().createOffer(player, product, qty, unit);
        });
        view.set(13, m().itemIcon(product.key(), Math.min(64, qty)), null);
        view.set(15, icon(Material.RED_TERRACOTTA, "Cancel", NamedTextColor.RED, List.of()), c -> product(player, product));
        player.openInventory(view.getInventory());
    }

    void manage(Player player) {
        Market.View view = view(6, "Your Bazaar Orders", Material.GRAY_STAINED_GLASS_PANE);
        UUID me = player.getUniqueId();
        int g = 0;
        for (Market.Offer o : m().offers()) {
            if (!o.seller.equals(me) || g >= AH_GRID.length) {
                continue;
            }
            ItemStack stack = m().itemIcon(o.key, Math.min(64, o.qty));
            Market.decorate(stack, "SELL " + Market.label(o.key), NamedTextColor.GOLD, List.of(
                    "Offer #" + o.id, "Amount: " + o.qty + "x", "Price per unit: $" + stt(o.unit), "", "!Click to cancel and get the items back!"));
            view.set(AH_GRID[g++], stack, c -> {
                player.closeInventory();
                m().cancel(player, o.id);
            });
        }
        for (Market.Order o : m().orders()) {
            if (!o.buyer.equals(me) || o.left <= 0 || g >= AH_GRID.length) {
                continue;
            }
            ItemStack stack = m().itemIcon(o.key, Math.min(64, o.left));
            Market.decorate(stack, "BUY " + Market.label(o.key), NamedTextColor.GREEN, List.of(
                    "Order #" + o.id, "Still wanted: " + o.left + "x", "Price per unit: $" + stt(o.unit),
                    "Escrowed: $" + stt(o.unit.multiply(BigInteger.valueOf(o.left))), "", "!Click to cancel (SkyRealm refunds the STT)!"));
            view.set(AH_GRID[g++], stack, c -> {
                player.closeInventory();
                m().cancel(player, o.id);
            });
        }
        if (g == 0) {
            view.set(22, icon(Material.BARRIER, "No orders", NamedTextColor.RED, List.of("You have no offers or buy orders.")), null);
        }
        back(player, view, 48, "Bazaar", () -> bazaar(player, Cat.FARMING));
        close(player, view);
        player.openInventory(view.getInventory());
    }

    // ================= AUCTION HOUSE =================

    void auctionHome(Player player) {
        Market.View view = view(4, "Auction House", Material.GRAY_STAINED_GLASS_PANE);
        long open = m().lots().stream().filter(l -> !l.done).count() + m().bins().size();
        view.set(11, icon(Material.GOLD_BLOCK, "Auctions Browser", NamedTextColor.GOLD, List.of(
                "Find items for sale by players", "across Lumenhold.", "", "Open listings: " + open, "", "!Click to browse!")),
                c -> browser(player));
        long bids = m().lots().stream().filter(l -> !l.done && player.getUniqueId().equals(l.bidder)).count();
        view.set(13, icon(Material.GOLDEN_CARROT, "View Bids", NamedTextColor.YELLOW, List.of(
                "You are the top bidder on " + bids + " auction(s).", "Outbid STT is refunded on-chain.", "", "!Click to view!")),
                c -> bids(player));
        long mine = m().lots().stream().filter(l -> !l.done && l.seller.equals(player.getUniqueId())).count()
                + m().bins().stream().filter(b -> b.seller.equals(player.getUniqueId())).count();
        view.set(15, icon(Material.GOLDEN_HORSE_ARMOR, mine > 0 ? "Manage Auctions" : "Create Auction", NamedTextColor.GREEN, List.of(
                "Your listings: " + mine, "", "!Click to " + (mine > 0 ? "manage!" : "create one!"))),
                c -> {
                    if (mine > 0) {
                        manageAuctions(player);
                    } else {
                        create(player);
                    }
                });
        view.set(22, icon(Material.OAK_SIGN, "Fees (STT sink)", NamedTextColor.GOLD, List.of(
                "BIN (SkyBin): 1% listing fee + 2% tax.",
                "  Half of every BIN fee is burned,",
                "  half goes to the Relic Pool.",
                "Bids (SkyRealm): 1% listing fee + 2% tax",
                "  → Relic Pool. +5% min raise, 30 s anti-snipe.",
                "Min listing fee 0.001 STT. You pay your own gas.")), null);
        close(player, view);
        player.openInventory(view.getInventory());
    }

    private record Entry(Market.Bin bin, Market.Lot lot, BigInteger price, long end) {
    }

    void browser(Player player) {
        int[] st = browse.computeIfAbsent(player.getUniqueId(), k -> new int[]{0, 0, 0});
        List<Entry> all = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (st[1] != 2) {
            for (Market.Bin b : m().bins()) {
                if (b.end > now) {
                    all.add(new Entry(b, null, b.price, b.end));
                }
            }
        }
        if (st[1] != 1) {
            for (Market.Lot l : m().lots()) {
                if (!l.done) {
                    all.add(new Entry(null, l, l.bid.signum() == 0 ? l.start : l.bid, l.end));
                }
            }
        }
        Comparator<Entry> order = switch (st[2]) {
            case 1 -> Comparator.comparing(Entry::price).reversed();
            case 2 -> Comparator.comparingLong(Entry::end);
            default -> Comparator.comparing(Entry::price);
        };
        all.sort(order);
        int pages = Math.max(1, (all.size() + AH_GRID.length - 1) / AH_GRID.length);
        st[0] = Math.min(st[0], pages - 1);
        Market.View view = view(6, "Auctions Browser (" + (st[0] + 1) + "/" + pages + ")", Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < AH_GRID.length; i++) {
            int index = st[0] * AH_GRID.length + i;
            if (index >= all.size()) {
                break;
            }
            Entry e = all.get(index);
            view.set(AH_GRID[i], listingIcon(player, e, now), c -> {
                if (e.bin() != null) {
                    confirmBin(player, e.bin());
                } else {
                    auctionView(player, e.lot());
                }
            });
        }
        if (st[0] > 0) {
            view.set(46, icon(Material.ARROW, "Previous Page", NamedTextColor.GREEN, List.of()), c -> {
                st[0]--;
                browser(player);
            });
        }
        if (st[0] < pages - 1) {
            view.set(53, icon(Material.ARROW, "Next Page", NamedTextColor.GREEN, List.of()), c -> {
                st[0]++;
                browser(player);
            });
        }
        String[] sorts = {"Lowest Price", "Highest Price", "Ending Soon"};
        String[] filters = {"Show All", "BIN Only", "Auctions Only"};
        view.set(50, icon(Material.HOPPER, "Sort", NamedTextColor.GREEN, List.of(
                (st[2] == 0 ? "~▶ " : "  ") + sorts[0], (st[2] == 1 ? "~▶ " : "  ") + sorts[1], (st[2] == 2 ? "~▶ " : "  ") + sorts[2], "", "!Click to switch!")), c -> {
            st[2] = (st[2] + 1) % 3;
            browser(player);
        });
        view.set(51, icon(Material.GOLD_BLOCK, "BIN Filter", NamedTextColor.GOLD, List.of(
                (st[1] == 0 ? "~▶ " : "  ") + filters[0], (st[1] == 1 ? "~▶ " : "  ") + filters[1], (st[1] == 2 ? "~▶ " : "  ") + filters[2], "", "!Click to switch!")), c -> {
            st[1] = (st[1] + 1) % 3;
            st[0] = 0;
            browser(player);
        });
        back(player, view, 48, "Auction House", () -> auctionHome(player));
        close(player, view);
        player.openInventory(view.getInventory());
    }

    private ItemStack listingIcon(Player player, Entry e, long now) {
        long left = Math.max(0, e.end() - now) / 1000;
        String ends = left >= 3600 ? (left / 3600) + "h " + (left % 3600 / 60) + "m" : (left / 60) + "m " + (left % 60) + "s";
        List<String> lore = new ArrayList<>();
        lore.add("-------------------");
        if (e.bin() != null) {
            Market.Bin b = e.bin();
            lore.add("Seller: " + m().name(b.seller));
            lore.add("Buy it now: $" + stt(b.price));
            lore.add("");
            lore.add("Ends in: " + ends);
            lore.add("");
            lore.add(b.seller.equals(player.getUniqueId()) ? "+This is your own listing!" : "!Click to inspect!");
            ItemStack stack = m().itemIcon(b.key, b.qty);
            Market.decorate(stack, null, NamedTextColor.WHITE, lore);
            return stack;
        }
        Market.Lot l = e.lot();
        lore.add("Seller: " + m().name(l.seller));
        lore.add(l.bid.signum() == 0 ? "Starting bid: $" + stt(l.start) : "Top bid: $" + stt(l.bid));
        if (l.bidder != null) {
            lore.add("Bidder: " + m().name(l.bidder));
        }
        lore.add("");
        lore.add(left > 0 ? "Ends in: " + ends : "-Ended, settling on-chain…");
        lore.add("");
        lore.add(l.seller.equals(player.getUniqueId()) ? "+This is your own auction!" : "!Click to inspect!");
        ItemStack stack = m().itemIcon(l.key, l.qty);
        Market.decorate(stack, null, NamedTextColor.WHITE, lore);
        return stack;
    }

    private void confirmBin(Player player, Market.Bin bin) {
        if (bin.seller.equals(player.getUniqueId())) {
            manageAuctions(player);
            return;
        }
        Market.View view = view(3, "Confirm Purchase", Material.GRAY_STAINED_GLASS_PANE);
        view.set(11, icon(Material.LIME_TERRACOTTA, "Confirm", NamedTextColor.GREEN, List.of(
                "Purchasing: " + bin.qty + "x " + Market.label(bin.key), "Cost: $" + stt(bin.price), "+ gas, paid from your purse.", "",
                "SkyBin pays the seller; 2% tax (half burned).", "", "!Click to sign on Somnia!")), c -> {
            player.closeInventory();
            m().buyBin(player, bin);
        });
        view.set(13, m().itemIcon(bin.key, bin.qty), null);
        view.set(15, icon(Material.RED_TERRACOTTA, "Cancel", NamedTextColor.RED, List.of()), c -> browser(player));
        player.openInventory(view.getInventory());
    }

    private void auctionView(Player player, Market.Lot lot) {
        Market.View view = view(6, "Auction View", Material.GRAY_STAINED_GLASS_PANE);
        BigInteger min = RealmChain.minBid(lot.start, lot.bid);
        ItemStack item = m().itemIcon(lot.key, lot.qty);
        Market.decorate(item, null, NamedTextColor.WHITE, List.of("Seller: " + m().name(lot.seller),
                lot.bid.signum() == 0 ? "Starting bid: $" + stt(lot.start) : "Top bid: $" + stt(lot.bid)));
        view.set(13, item, null);
        boolean mine = lot.seller.equals(player.getUniqueId());
        boolean top = player.getUniqueId().equals(lot.bidder);
        view.set(29, icon(Material.GOLD_NUGGET, "Submit Bid", NamedTextColor.GOLD, mine
                ? List.of("-You cannot bid on your own auction.")
                : top ? List.of("+You already have the top bid.", "Bid: $" + stt(lot.bid))
                : List.of("New bid: $" + stt(min), "Escrowed in SkyRealm;", "refunded if you are outbid.", "", "!Click to bid!")), c -> {
            if (!mine && !top) {
                player.closeInventory();
                m().bid(player, lot, min);
            }
        });
        view.set(31, icon(Material.OAK_SIGN, "Custom Bid", NamedTextColor.GREEN, List.of("Minimum: $" + stt(min), "", "!Click to type an amount!")), c -> {
            if (!mine) {
                ask(player, "Your bid in STT (min " + RealmChain.stt(min) + ")?", text -> m().bid(player, lot, RealmChain.wei(text)));
            }
        });
        view.set(33, icon(Material.MAP, "Bid History", NamedTextColor.WHITE, lot.bidder == null
                ? List.of("No bids yet.") : List.of("Top: " + m().name(lot.bidder) + " · $" + stt(lot.bid), "Full history: SkyRealm Bid events on the explorer.")), null);
        back(player, view, 48, "Auctions Browser", () -> browser(player));
        close(player, view);
        player.openInventory(view.getInventory());
    }

    private void bids(Player player) {
        Market.View view = view(6, "Your Bids", Material.GRAY_STAINED_GLASS_PANE);
        long now = System.currentTimeMillis();
        int g = 0;
        for (Market.Lot l : m().lots()) {
            if (l.done || !player.getUniqueId().equals(l.bidder) || g >= AH_GRID.length) {
                continue;
            }
            view.set(AH_GRID[g++], listingIcon(player, new Entry(null, l, l.bid, l.end), now), c -> auctionView(player, l));
        }
        if (g == 0) {
            view.set(22, icon(Material.BARRIER, "No bids", NamedTextColor.RED, List.of("You are not the top bidder anywhere.")), null);
        }
        back(player, view, 48, "Auction House", () -> auctionHome(player));
        close(player, view);
        player.openInventory(view.getInventory());
    }

    private void manageAuctions(Player player) {
        Market.View view = view(6, "Manage Auctions", Material.GRAY_STAINED_GLASS_PANE);
        long now = System.currentTimeMillis();
        int g = 0;
        for (Market.Bin b : m().bins()) {
            if (!b.seller.equals(player.getUniqueId()) || g >= AH_GRID.length) {
                continue;
            }
            ItemStack stack = listingIcon(player, new Entry(b, null, b.price, b.end), now);
            Market.decorate(stack, null, NamedTextColor.WHITE, List.of("BIN #" + b.id, b.end > now ? "!Click to cancel (item comes back)!" : "!Click to collect!"));
            view.set(AH_GRID[g++], stack, c -> {
                player.closeInventory();
                m().cancelBin(player, b);
            });
        }
        for (Market.Lot l : m().lots()) {
            if (l.done || !l.seller.equals(player.getUniqueId()) || g >= AH_GRID.length) {
                continue;
            }
            view.set(AH_GRID[g++], listingIcon(player, new Entry(null, l, l.bid, l.end), now), c -> auctionView(player, l));
        }
        view.set(50, icon(Material.GOLDEN_HORSE_ARMOR, "Create Auction", NamedTextColor.GREEN, List.of("Sell an item: BIN or bids.", "", "!Click to create!")), c -> create(player));
        back(player, view, 48, "Auction House", () -> auctionHome(player));
        close(player, view);
        player.openInventory(view.getInventory());
    }

    void create(Player player) {
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        ItemStack chosen = d.slot >= 0 ? player.getInventory().getItem(d.slot) : null;
        if (chosen != null && HoldItems.keyOf(chosen, plugin) == null) {
            chosen = null;
            d.slot = -1;
        }
        Market.View view = view(6, d.bin ? "Create BIN Auction" : "Create Auction", Material.GRAY_STAINED_GLASS_PANE);
        view.bottom = raw -> {
            ItemStack s = player.getInventory().getItem(raw);
            if (s == null || HoldItems.keyOf(s, plugin) == null || SbMenu.isStar(plugin, s)) {
                player.sendMessage(Component.text("That item cannot be auctioned.", NamedTextColor.RED));
                return;
            }
            d.slot = raw;
            create(player);
        };
        if (chosen == null) {
            view.set(13, icon(Material.STONE_BUTTON, "Click an item in your inventory!", NamedTextColor.YELLOW, List.of("Selects it for this auction.")), null);
        } else {
            ItemStack shown = chosen.clone();
            Market.decorate(shown, null, NamedTextColor.WHITE, List.of("!Click to pick it back up", "(or click another item below)"));
            view.set(13, shown, c -> {
                d.slot = -1;
                create(player);
            });
        }
        BigInteger fee = RealmChain.listingFee(d.price);
        String price = (d.bin ? "Item price: " : "Starting bid: ") + stt(d.price);
        boolean ready = chosen != null;
        view.set(29, icon(ready ? Material.LIME_TERRACOTTA : Material.RED_TERRACOTTA, d.bin ? "Create BIN Auction" : "Create Auction",
                ready ? NamedTextColor.GREEN : NamedTextColor.RED, List.of(
                        ready ? "Item: " + chosen.getAmount() + "x " + Market.label(HoldItems.keyOf(chosen, plugin)) : "-No item selected!",
                        price, "Duration: " + duration(d.minutes), "",
                        "Listing fee: $" + stt(fee) + (d.bin ? " (half burned)" : " → Relic Pool"),
                        "Tax when sold: 2%", "", ready ? "!Click to sign on Somnia!" : "")), c -> {
                    if (player.getInventory().getItem(d.slot) == null) {
                        return;
                    }
                    player.closeInventory();
                    int slot = d.slot;
                    drafts.remove(player.getUniqueId());
                    if (d.bin) {
                        m().createBin(player, slot, d.price, d.minutes);
                    } else {
                        m().createAuction(player, slot, d.price, d.minutes);
                    }
                });
        view.set(31, icon(Material.GOLD_INGOT, d.bin ? "Item Price" : "Starting Bid", NamedTextColor.GOLD, List.of(
                stt(d.price), "", "!Click to type a price!")), c -> ask(player, (d.bin ? "BIN price" : "Starting bid") + " in STT (e.g. 0.05)?", text -> {
                    d.price = RealmChain.wei(text);
                    create(player);
                }));
        view.set(33, icon(Material.CLOCK, "Duration", NamedTextColor.YELLOW, List.of(
                duration(d.minutes), "", "!Click to cycle: 5m, 1h, 6h, 12h, 24h, 48h")), c -> {
            int i = 0;
            while (i < DURATIONS.length && DURATIONS[i] != d.minutes) {
                i++;
            }
            d.minutes = DURATIONS[(i + 1) % DURATIONS.length];
            create(player);
        });
        view.set(48, icon(d.bin ? Material.GOLDEN_CARROT : Material.GOLD_BLOCK, d.bin ? "Switch to Auction" : "Switch to BIN", NamedTextColor.GREEN, List.of(
                d.bin ? "Let players bid; highest bid wins." : "Sell for a fixed Buy-It-Now price.", "", "!Click to switch!")), c -> {
            d.bin = !d.bin;
            create(player);
        });
        back(player, view, 45, "Auction House", () -> auctionHome(player));
        close(player, view);
        player.openInventory(view.getInventory());
    }

    private static String duration(int minutes) {
        return minutes >= 60 ? (minutes / 60) + " Hour" + (minutes >= 120 ? "s" : "") : minutes + " Minutes";
    }
}
