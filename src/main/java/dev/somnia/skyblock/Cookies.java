package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Booster. Bought with SOMI on Somnia mainnet from the player's own purse (the same address as on
 * Shannon; /cookie or /booster shows it and its SOMI balance). A confirmed purchase applies 4 days of buff
 * at once (stacks, counts down offline) with no bag item and no onchain bag write.
 * Buff: /ah /bz /bank anywhere, sell to the Merchant from the SkyBlock menu, flight on your own island, and the
 * admin wallet pays the player's Shannon gas (WalletService.sponsored). A leftover Booster item (stash or /dev give)
 * can still be eaten; merchants never buy it for coins.
 * Also shows the "Out of STT" center message to players whose Shannon purse is below the gas fee cap and who have no buff.
 */
final class Cookies implements Listener, CommandExecutor, TabCompleter {
    static final String ID = "booster_cookie";
    static final String KEY = "skyblock:" + ID;
    private static final long DAY = 86_400_000L;
    private static final TextColor SOFT = TextColor.color(0xC8C8C8);
    private static final TextColor SOFT_GOLD = TextColor.color(0xF2D388);
    private static final TextColor SOFT_AQUA = TextColor.color(0x9FE3F0);

    private final SomniaSkyblockPlugin plugin;
    private final Web3j main;
    private final long mainChainId;
    private final File file;
    private final Set<UUID> buying = ConcurrentHashMap.newKeySet();
    private final Set<UUID> flying = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> fallGrace = new ConcurrentHashMap<>();
    /** Shannon purse balance cache for the Out of STT message: wei and when it was read. */
    private final Map<UUID, long[]> sttSeen = new ConcurrentHashMap<>();
    private final Map<UUID, BigInteger> sttWei = new ConcurrentHashMap<>();
    private final Set<UUID> titled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> reading = ConcurrentHashMap.newKeySet();

    Cookies(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        String rpc = plugin.getConfig().getString("cookie.mainnet-rpc", "https://api.infra.mainnet.somnia.network/");
        this.main = Web3j.build(new HttpService(rpc));
        this.mainChainId = plugin.getConfig().getLong("cookie.mainnet-chain-id", 5031L);
        this.file = new File(plugin.getDataFolder(), "cookies.yml");
        plugin.getLogger().info("Booster: " + priceText() + " SOMI on Somnia mainnet " + mainChainId + " (" + rpc + "), paid to " + receiver());
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    // ---------- config ----------

    BigInteger price() {
        return Convert.toWei(new BigDecimal(priceText()), Convert.Unit.ETHER).toBigIntegerExact();
    }

    String priceText() {
        String raw = plugin.getConfig().getString("cookie.price-somi", "25");
        try {
            return new BigDecimal(raw.trim()).stripTrailingZeros().toPlainString();
        } catch (Exception exception) {
            return "25";
        }
    }

    String receiver() {
        String to = plugin.getConfig().getString("cookie.receiver", "0x85b7D75cf35efC7E636FbDf3E82C92c6ceB5AC9D");
        return to == null ? "" : to.trim();
    }

    private int days() {
        return Math.max(1, plugin.getConfig().getInt("cookie.days", 4));
    }

    private boolean gate() {
        return plugin.getConfig().getBoolean("cookie.gate-commands", true);
    }

    // ---------- buff ----------

    boolean active(Profile profile) {
        return profile != null && profile.cookieUntil > System.currentTimeMillis();
    }

    boolean active(Player player) {
        return active(plugin.profiles().get(player.getUniqueId()));
    }

    static String left(long ms) {
        if (ms <= 0) {
            return "none";
        }
        long m = ms / 60_000L;
        long d = m / 1440;
        long h = (m % 1440) / 60;
        long mm = m % 60;
        return d > 0 ? d + "d " + h + "h " + mm + "m" : h > 0 ? h + "h " + mm + "m" : Math.max(1, mm) + "m";
    }

    /**
     * /ah, /bz, /bank (and the SkyBlock menu buttons that run them): as on Hypixel, only with the Booster buff.
     * Everyone else uses the hub NPC ({@code npc}: Auction Master, Bazaar Master, Banker), which opens the menu directly.
     */
    boolean allowAnywhere(Player player, String npc) {
        if (!gate() || active(player)) {
            return true;
        }
        player.sendMessage(Component.text("Visit the ", SOFT)
                .append(Component.text(npc, SOFT_GOLD))
                .append(Component.text(" in the Hub, or buy a ", SOFT))
                .append(Component.text("Booster", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/cookie"))
                        .hoverEvent(HoverEvent.showText(Component.text("Open /cookie", NamedTextColor.YELLOW))))
                .append(Component.text(" to use this anywhere.", SOFT)));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.1f);
        return false;
    }

    /** SkyBlock menu: sell to the Merchant (buff only). */
    void menuSell(Player player) {
        if (!active(player)) {
            player.closeInventory();
            player.sendMessage(Component.text("Selling from the menu needs the Booster buff. See /cookie.", NamedTextColor.RED));
            return;
        }
        plugin.market().openMerchant(player, "Merchant");
    }

    ItemStack menuIcon(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        List<Component> lore = new ArrayList<>();
        long left = profile.cookieUntil - System.currentTimeMillis();
        lore.add(SbMenu.plain("Buff: ", NamedTextColor.GRAY).append(left > 0
                ? SbMenu.plain(left(left), NamedTextColor.GREEN) : SbMenu.plain("not active", NamedTextColor.RED)));
        lore.add(SbMenu.plain("Price: ", NamedTextColor.GRAY).append(SbMenu.plain(priceText() + " SOMI", NamedTextColor.GOLD)));
        lore.add(Component.empty());
        lore.add(SbMenu.plain("Click to open!", NamedTextColor.YELLOW));
        return SbMenu.icon(Material.COOKIE, SbMenu.plain("Booster", NamedTextColor.GOLD), lore);
    }

    ItemStack sellIcon(Player player) {
        boolean on = active(player);
        List<Component> lore = new ArrayList<>(SbMenu.wrap("Sell items to the Merchant for coins from anywhere.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(on ? SbMenu.plain("Click to open!", NamedTextColor.YELLOW)
                : SbMenu.plain("Needs the Booster buff.", NamedTextColor.RED));
        return SbMenu.icon(Material.GOLD_INGOT, SbMenu.plain("Sell to Merchant", NamedTextColor.GOLD), lore);
    }

    // ---------- /cookie ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("buy")) {
            buy(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("address")) {
            tellAddress(player);
            return true;
        }
        open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("buy", "address") : List.of();
    }

    void open(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Market.View view = new Market.View(27, "Booster");
        ItemStack pane = Market.icon(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 27; i++) {
            view.getInventory().setItem(i, pane);
        }
        long left = profile.cookieUntil - System.currentTimeMillis();
        List<Component> info = new ArrayList<>();
        info.add(SbMenu.plain("Buying one applies " + days() + " days of buff at once.", NamedTextColor.GRAY));
        info.add(SbMenu.plain("Buying again adds more time; the", NamedTextColor.GRAY));
        info.add(SbMenu.plain("timer runs while you are offline too.", NamedTextColor.GRAY));
        info.add(Component.empty());
        info.add(SbMenu.plain("Buff:", NamedTextColor.GRAY));
        info.add(SbMenu.plain(" • /ah, /bz and /bank from anywhere", NamedTextColor.GREEN));
        info.add(SbMenu.plain(" • Sell to the Merchant from the menu", NamedTextColor.GREEN));
        info.add(SbMenu.plain(" • Fly on your own island", NamedTextColor.GREEN));
        info.add(SbMenu.plain(" • Free gas: the server pays your STT gas", NamedTextColor.GREEN));
        info.add(Component.empty());
        info.add(SbMenu.plain("Time left: ", NamedTextColor.GRAY).append(left > 0
                ? SbMenu.plain(left(left), NamedTextColor.GREEN) : SbMenu.plain("not active", NamedTextColor.RED)));
        view.set(11, SbMenu.icon(Material.COOKIE, SbMenu.plain("Booster", NamedTextColor.GOLD), info), null);

        List<Component> bal = new ArrayList<>();
        bal.add(SbMenu.plain("Your purse on Somnia mainnet:", NamedTextColor.GRAY));
        bal.add(SbMenu.plain(WalletService.shortAddress(profile.wallet), NamedTextColor.AQUA));
        bal.add(SbMenu.plain("SOMI balance: ", NamedTextColor.GRAY).append(SbMenu.plain("loading...", NamedTextColor.DARK_GRAY)));
        bal.add(Component.empty());
        bal.add(SbMenu.plain("Send SOMI on Somnia mainnet (chain " + mainChainId + ")", NamedTextColor.GRAY));
        bal.add(SbMenu.plain("to this address to top up.", NamedTextColor.GRAY));
        bal.add(Component.empty());
        bal.add(SbMenu.plain("Click to show the address with copy!", NamedTextColor.YELLOW));
        view.set(13, SbMenu.icon(Material.SUNFLOWER, SbMenu.plain("SOMI Balance", NamedTextColor.GOLD), bal), c -> {
            player.closeInventory();
            tellAddress(player);
        });
        view.set(15, buyIcon(null), c -> {
            player.closeInventory();
            buy(player);
        });
        view.set(22, Market.icon(Material.BARRIER, "Close", NamedTextColor.RED, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
        String wallet = profile.wallet;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String shown;
            BigInteger wei = null;
            try {
                wei = somi(wallet);
                shown = fmt(wei) + " SOMI";
            } catch (Exception exception) {
                shown = "could not read";
            }
            String text = shown;
            BigInteger have = wei;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Market.View open) || open != view) {
                    return;
                }
                List<Component> lines = new ArrayList<>(bal);
                lines.set(2, SbMenu.plain("SOMI balance: ", NamedTextColor.GRAY).append(SbMenu.plain(text, NamedTextColor.GOLD)));
                view.getInventory().setItem(13, SbMenu.icon(Material.SUNFLOWER, SbMenu.plain("SOMI Balance", NamedTextColor.GOLD), lines));
                view.getInventory().setItem(15, buyIcon(have));
            });
        });
    }

    private ItemStack buyIcon(BigInteger have) {
        List<Component> lore = new ArrayList<>();
        lore.add(SbMenu.plain("Cost: ", NamedTextColor.GRAY).append(SbMenu.plain(priceText() + " SOMI", NamedTextColor.GOLD)));
        lore.add(SbMenu.plain("plus a small SOMI network fee.", NamedTextColor.GRAY));
        lore.add(SbMenu.plain("Paid from your purse on Somnia mainnet.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        if (have != null && have.compareTo(price()) < 0) {
            lore.add(SbMenu.plain("Not enough SOMI yet.", NamedTextColor.RED));
        } else {
            lore.add(SbMenu.plain("Click to buy one!", NamedTextColor.YELLOW));
        }
        return SbMenu.icon(Material.EMERALD, SbMenu.plain("Buy a Booster", NamedTextColor.GREEN), lore);
    }

    private void tellAddress(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        String wallet = profile.wallet;
        player.sendMessage(Component.text("Purse on Somnia mainnet (chain " + mainChainId + ", SOMI):", NamedTextColor.GOLD));
        player.sendMessage(Component.text(wallet, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(wallet))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.YELLOW)))
                .append(Component.text("  [Copy]", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.UNDERLINED, false)));
        player.sendMessage(Component.text("Send SOMI here on Somnia mainnet, then buy a Booster in /cookie or /booster for " + priceText() + " SOMI. The buff applies as soon as the payment lands. This is the same address as your STT purse.", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String text;
            try {
                text = "SOMI balance: " + fmt(somi(wallet)) + " SOMI";
            } catch (Exception exception) {
                text = "SOMI balance could not be read right now.";
            }
            String line = text;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(Component.text(line, NamedTextColor.YELLOW));
                }
            });
        });
    }

    private BigInteger somi(String wallet) throws Exception {
        return main.ethGetBalance(wallet, DefaultBlockParameterName.LATEST).send().getBalance();
    }

    static String fmt(BigInteger wei) {
        BigDecimal v = Convert.fromWei(new BigDecimal(wei), Convert.Unit.ETHER);
        return v.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    /** Pays the price from the purse on mainnet; the 4-day buff is applied as soon as the receipt says status 1. No bag item. */
    void buy(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        UUID id = player.getUniqueId();
        if (!plugin.wallets().canSign(profile)) {
            player.sendMessage(Component.text("Log in first.", NamedTextColor.RED));
            return;
        }
        if (!receiver().matches("(?i)0x[0-9a-f]{40}")) {
            player.sendMessage(Component.text("Booster sales are not set up on this server yet.", NamedTextColor.RED));
            return;
        }
        if (!buying.add(id)) {
            player.sendMessage(Component.text("Your Booster purchase is already on its way.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Paying " + priceText() + " SOMI on Somnia mainnet...", NamedTextColor.GRAY));
        BigInteger price = price();
        String to = receiver();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = null;
            try {
                hash = plugin.wallets().mainnetPay(profile, main, mainChainId, to, price);
                record(hash, profile, "sent");
                TransactionReceipt receipt = null;
                for (int i = 0; i < 240 && receipt == null; i++) {
                    receipt = main.ethGetTransactionReceipt(hash).send().getTransactionReceipt().orElse(null);
                    if (receipt == null) {
                        Thread.sleep(250L);
                    }
                }
                if (receipt == null) {
                    throw new IllegalStateException("No mainnet receipt yet for " + hash + ". If it lands, ask an admin; nothing was given yet.");
                }
                if (!receipt.isStatusOK()) {
                    record(hash, profile, "reverted");
                    throw new IllegalStateException("The SOMI payment reverted (" + hash + "). Only the network fee was spent.");
                }
                record(hash, profile, "paid");
                String done = hash;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    buying.remove(id);
                    applyBuff(profile, Bukkit.getPlayer(id));
                    record(done, profile, "applied");
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) {
                        online.sendMessage(Component.text("You bought a ", NamedTextColor.GREEN)
                                .append(Component.text("Booster", NamedTextColor.GOLD))
                                .append(Component.text("! Buff time left: " + left(Math.max(0, profile.cookieUntil - System.currentTimeMillis())), NamedTextColor.GREEN)));
                        online.sendMessage(Component.text("Mainnet tx: ", NamedTextColor.DARK_GRAY)
                                .append(Component.text(done, NamedTextColor.GRAY).clickEvent(ClickEvent.openUrl("https://explorer.somnia.network/tx/" + done))
                                        .hoverEvent(HoverEvent.showText(Component.text("Open in the Somnia explorer", NamedTextColor.YELLOW)))));
                        online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                    }
                });
            } catch (Exception exception) {
                String why = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                plugin.getLogger().warning("Booster purchase for " + profile.wallet + " failed: " + why);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    buying.remove(id);
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) {
                        online.sendMessage(Component.text("Booster not bought: " + why, NamedTextColor.RED));
                    }
                });
            }
        });
    }

    private synchronized void record(String hash, Profile profile, String state) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String path = "purchases." + hash;
        yaml.set(path + ".player", profile.uuid.toString());
        yaml.set(path + ".wallet", profile.wallet);
        yaml.set(path + ".state", state);
        yaml.set(path + ".at", System.currentTimeMillis());
        try {
            yaml.save(file);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not save cookies.yml: " + exception.getMessage());
        }
    }

    /** Adds days() of buff (stacks; counts down offline). No bag write. */
    void applyBuff(Profile profile, Player online) {
        long now = System.currentTimeMillis();
        profile.cookieUntil = Math.max(now, profile.cookieUntil) + days() * DAY;
        plugin.profiles().save(profile);
        if (online != null && online.isOnline()) {
            clearOutOfStt(online);
            flight(online, profile, true);
            if (plugin.stats() != null) {
                plugin.stats().refreshListName(online);
            }
        }
    }

    // ---------- eating ----------

    static boolean isCookie(SomniaSkyblockPlugin plugin, ItemStack stack) {
        return ID.equals(Items.kind(stack, plugin));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEat(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isCookie(plugin, hand)) {
            return;
        }
        event.setCancelled(true);
        if (plugin.words() != null && !plugin.words().ready(player)) {
            player.sendMessage(Component.text("The chain inventory is still loading.", NamedTextColor.RED));
            return;
        }
        int slot = player.getInventory().getHeldItemSlot();
        hand.setAmount(hand.getAmount() - 1);
        player.getInventory().setItem(slot, hand.getAmount() <= 0 ? null : hand);
        UUID id = player.getUniqueId();
        java.util.function.Consumer<Boolean> done = ok -> {
            Player online = Bukkit.getPlayer(id);
            if (!ok) {
                plugin.market().giveOrStash(id, KEY, 1, false);
                if (online != null) {
                    online.sendMessage(Component.text("The Booster stayed in your bag: the bag write did not land.", NamedTextColor.RED));
                }
                return;
            }
            Profile profile = plugin.profiles().get(id);
            applyBuff(profile, online);
            if (online != null) {
                online.playSound(online.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
                online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                online.sendMessage(Component.text("You ate a Booster! ", NamedTextColor.GOLD)
                        .append(Component.text("Buff time left: " + left(Math.max(0, profile.cookieUntil - System.currentTimeMillis())), NamedTextColor.GREEN)));
            }
        };
        if (plugin.words() != null) {
            plugin.words().commitLocal(player, done);
        } else {
            done.accept(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isCookie(plugin, event.getItem())) {
            event.setCancelled(true);
        }
    }

    // ---------- island flight + Out of STT message ----------

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = plugin.profiles().get(player.getUniqueId());
            boolean buff = active(profile);
            flight(player, profile, buff);
            outOfStt(player, profile, buff, now);
            if (plugin.stats() != null) {
                plugin.stats().refreshListName(player);
            }
        }
    }

    private boolean ownIsland(Player player, Profile profile, Location at) {
        if (plugin.visits() != null) {
            return plugin.visits().allowedHere(player, profile, at);
        }
        return profile.islandReady && plugin.islands().contains(profile, at);
    }

    private void flight(Player player, Profile profile, boolean buff) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            flying.remove(player.getUniqueId());
            return;
        }
        boolean allowed = buff && plugin.wallets().canSign(profile) && ownIsland(player, profile, player.getLocation());
        UUID id = player.getUniqueId();
        if (allowed && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            flying.add(id);
            player.sendActionBar(Component.text("Island flight on: double-tap jump to fly.", SOFT_GOLD));
        } else if (!allowed && flying.remove(id)) {
            if (player.isFlying()) {
                fallGrace.put(id, System.currentTimeMillis() + 10_000L);
            }
            player.setFlying(false);
            player.setAllowFlight(false);
            player.setFallDistance(0f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!flying.contains(player.getUniqueId()) || !player.isFlying() || event.getTo() == null) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (ownIsland(player, profile, event.getFrom()) && !ownIsland(player, profile, event.getTo())) {
            Location back = event.getFrom().clone();
            back.setYaw(event.getTo().getYaw());
            back.setPitch(event.getTo().getPitch());
            event.setTo(back);
            player.sendActionBar(Component.text(
                    plugin.visits() != null && plugin.visits().isGuest(player)
                            ? "Island flight stops at the edge of the island."
                            : "Island flight stops at the edge of your island.", SOFT_GOLD));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            Long until = fallGrace.get(player.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) {
                event.setCancelled(true);
                fallGrace.remove(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        flying.remove(id);
        titled.remove(id);
        sttSeen.remove(id);
        sttWei.remove(id);
    }

    /** Balance read every 10 s (async); the title is re-sent every 2 s with a 4 s stay, so it never fades or flickers. */
    private void outOfStt(Player player, Profile profile, boolean buff, long now) {
        UUID id = player.getUniqueId();
        if (buff || !plugin.wallets().canSign(profile) || profile.wallet == null || profile.wallet.isBlank()) {
            clearOutOfStt(player);
            return;
        }
        long[] seen = sttSeen.get(id);
        if ((seen == null || now - seen[0] > 10_000L) && reading.add(id)) {
            String wallet = profile.wallet;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    sttWei.put(id, plugin.wallets().balanceWei(wallet));
                } catch (Exception ignored) {
                    // keep the last value
                } finally {
                    sttSeen.put(id, new long[]{System.currentTimeMillis()});
                    reading.remove(id);
                }
            });
        }
        BigInteger wei = sttWei.get(id);
        if (wei == null) {
            wei = plugin.wallets().cachedBalance(profile.wallet);
        }
        if (wei == null || wei.compareTo(plugin.wallets().gasFeeCap()) >= 0) {
            clearOutOfStt(player);
            return;
        }
        if (titled.add(id) || now / 1000L % 2L == 0L) {
            Title title = Title.title(
                    Component.text("Out of STT", SOFT_GOLD),
                    Component.text("/deposit", SOFT_AQUA).append(Component.text(" to top up  •  ", SOFT))
                            .append(Component.text("Booster", SOFT_GOLD))
                            .append(Component.text(" " + priceText() + " SOMI = free gas", SOFT)),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ofMillis(300)));
            player.showTitle(title);
        }
    }

    void clearOutOfStt(Player player) {
        if (titled.remove(player.getUniqueId())) {
            player.clearTitle();
        }
    }

    // ---------- dev tools (/dev cookie; dev accounts only) ----------

    /** Test tools for dev accounts: clear or apply the buff (profile only, nothing onchain), or take a cookie item. */
    void openDev(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Market.View view = new Market.View(27, "Dev: Booster");
        ItemStack pane = Market.icon(Material.RED_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 27; i++) {
            view.getInventory().setItem(i, pane);
        }
        long left = profile.cookieUntil - System.currentTimeMillis();
        String now = left > 0 ? left(left) : "not active";
        view.set(4, Market.icon(Material.COOKIE, "Your buff: " + now, NamedTextColor.GOLD, List.of(
                "~Dev account only. Nothing here spends SOMI.")), null);
        view.set(11, Market.icon(Material.BARRIER, "Clear my Booster buff", NamedTextColor.RED, List.of(
                "Sets the time left to 0: commands, menu", "selling, flight and free gas stop now.",
                "~Profile only, no onchain effect.", "!Click to clear")), c -> {
            profile.cookieUntil = 0L;
            plugin.profiles().save(profile);
            flight(player, profile, false);
            if (plugin.stats() != null) {
                plugin.stats().refreshListName(player);
            }
            plugin.getLogger().info("Dev: " + player.getName() + " cleared their Booster buff.");
            player.sendMessage(Component.text("[DEV] ", NamedTextColor.RED).append(Component.text("Booster buff cleared.", NamedTextColor.GRAY)));
            openDev(player);
        });
        view.set(13, Market.icon(Material.CLOCK, "Apply a " + days() + "-day Booster buff", NamedTextColor.GREEN, List.of(
                "Sets the time left to exactly " + days() + " days.", "~Profile only, no onchain effect.", "!Click to apply")), c -> {
            profile.cookieUntil = System.currentTimeMillis() + days() * DAY;
            plugin.profiles().save(profile);
            clearOutOfStt(player);
            flight(player, profile, true);
            if (plugin.stats() != null) {
                plugin.stats().refreshListName(player);
            }
            plugin.getLogger().info("Dev: " + player.getName() + " applied a " + days() + "-day Booster buff.");
            player.sendMessage(Component.text("[DEV] ", NamedTextColor.RED).append(Component.text("Booster buff set to " + days() + " days.", NamedTextColor.GRAY)));
            openDev(player);
        });
        view.set(15, Market.icon(Material.CHEST, "Give me a Booster item", NamedTextColor.GOLD, List.of(
                "One cookie item, no SOMI spent.", "~It lands in your bag like any item,", "~so the next bag write records it.", "!Click to take one")), c -> {
            plugin.market().giveOrStash(player.getUniqueId(), KEY, 1, true);
            plugin.getLogger().info("Dev: " + player.getName() + " took a Booster item.");
            player.sendMessage(Component.text("[DEV] ", NamedTextColor.RED).append(Component.text("Booster item added to your inventory (needs a bag write).", NamedTextColor.GRAY)));
        });
        view.set(22, Market.icon(Material.ARROW, "Close", NamedTextColor.GRAY, List.of()), c -> player.closeInventory());
        player.openInventory(view.getInventory());
    }
}
