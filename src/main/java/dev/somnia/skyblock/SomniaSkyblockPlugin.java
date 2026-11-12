package dev.somnia.skyblock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class SomniaSkyblockPlugin extends JavaPlugin {
    private ProfileStore profiles;
    private IslandManager islands;
    private Coops coops;
    private HubManager hub;
    private ChainClient chain;
    private Sidebar sidebar;
    private WalletService wallets;
    private HoldSync hold;
    private WordChain words;
    private TradeManager trades;
    private MoveServer moves;
    private SkyCommands commands;
    private RealmChain realm;
    private Stats stats;
    private Mobs mobs;
    private Arena arena;
    private Market market;
    private MarketGui marketGui;
    private Quests quests;
    private Skills skills;
    private SkillChain skillChain;
    private Chat chat;
    private SbMenu menu;
    private CraftGui craft;

    CraftGui craft() {
        return craft;
    }
    private Minions minions;
    private IslandGuide guide;
    private SbAdmin admin;
    private Auth auth;
    private EventTally tally;
    private RealIp realIp;

    Auth auth() {
        return auth;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        realIp = new RealIp(this);
        auth = new Auth(this);
        profiles = new ProfileStore(this);
        auth.migrateAll();
        getServer().getPluginManager().registerEvents(auth, this);
        for (String name : new String[]{"register", "login", "changepassword", "dev", "user"}) {
            if (getCommand(name) == null) {
                throw new IllegalStateException("plugin.yml has no command /" + name);
            }
            getCommand(name).setExecutor(auth);
            getCommand(name).setTabCompleter(auth);
        }
        islands = new IslandManager(this);
        coops = new Coops(this);
        getServer().getPluginManager().registerEvents(coops, this);
        for (String name : new String[]{"coop", "coopadd", "coopkick", "coopchat", "coopclear", "coopsalvage"}) {
            if (getCommand(name) == null) {
                throw new IllegalStateException("plugin.yml has no command /" + name);
            }
            getCommand(name).setExecutor(coops);
            getCommand(name).setTabCompleter(coops);
        }
        hub = new HubManager(this);
        getServer().getPluginManager().registerEvents(hub, this);
        chain = new ChainClient(this);
        wallets = new WalletService(this);
        if (chain.enabled()) {
            // coops.yml is a cache: compare it with SkyIsland3 once the server is up (the chain wins).
            getServer().getScheduler().runTaskLaterAsynchronously(this, coops::reconcile, 100L);
        }
        hold = new HoldSync(this);
        if (wallets.wordEnabled()) {
            words = new WordChain(this);
            getServer().getPluginManager().registerEvents(words, this);
            getLogger().info("Word contract " + wallets.wordContract());
            getLogger().info("AOTV contract " + wallets.aotvContract());
        }
        moves = new MoveServer(this);
        moves.start();
        trades = new TradeManager(this);
        sidebar = new Sidebar();
        realm = new RealmChain(this);
        stats = new Stats(this);
        mobs = new Mobs(this);
        arena = new Arena(this);
        market = new Market(this);
        marketGui = new MarketGui(this);
        quests = new Quests(this);
        getServer().getPluginManager().registerEvents(quests, this);
        sidebar.quests(quests);
        skills = new Skills(this);
        skillChain = new SkillChain(this);
        chat = new Chat(this);
        menu = new SbMenu(this);
        minions = new Minions(this);
        guide = new IslandGuide(this);
        admin = new SbAdmin(this);
        if (getCommand("sbadmin") != null) {
            getCommand("sbadmin").setExecutor(admin);
            getCommand("sbadmin").setTabCompleter(admin);
        }
        admin.start();
        getServer().getPluginManager().registerEvents(guide, this);
        getServer().getPluginManager().registerEvents(skills, this);
        getServer().getPluginManager().registerEvents(skillChain, this);
        getServer().getPluginManager().registerEvents(chat, this);
        getServer().getPluginManager().registerEvents(menu, this);
        TabList tab = new TabList(this);
        getServer().getPluginManager().registerEvents(tab, this);
        tab.start();
        craft = new CraftGui(this);
        getServer().getPluginManager().registerEvents(craft, this);
        getServer().getPluginManager().registerEvents(minions, this);
        getServer().getPluginManager().registerEvents(new ItemSync(this), this);
        for (String name : new String[]{"sbmenu", "skills", "collection", "calendar", "warp", "levels"}) {
            if (getCommand(name) == null) {
                throw new IllegalStateException("plugin.yml has no command /" + name);
            }
            getCommand(name).setExecutor(menu);
        }
        if (getCommand("minion") == null) {
            throw new IllegalStateException("plugin.yml has no command /minion");
        }
        getCommand("minion").setExecutor(minions);
        getServer().getPluginManager().registerEvents(stats, this);
        getServer().getPluginManager().registerEvents(mobs, this);
        getServer().getPluginManager().registerEvents(arena, this);
        getServer().getPluginManager().registerEvents(market, this);
        getServer().getPluginManager().registerEvents(marketGui, this);
        EconomyCommands economy = new EconomyCommands(this);
        for (String name : new String[]{"bazaar", "auction", "arena", "stats", "relic", "quest"}) {
            if (getCommand(name) == null) {
                throw new IllegalStateException("plugin.yml has no command /" + name);
            }
            getCommand(name).setExecutor(economy);
            getCommand(name).setTabCompleter(economy);
        }

        SkyListener listener = new SkyListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(hold, this);
        getServer().getPluginManager().registerEvents(trades, this);

        commands = new SkyCommands(this);
        bind("sb", commands);
        bind("island", commands);
        bind("hub", commands);
        bind("craft", commands);
        bind("deposit", commands);
        bind("withdraw", commands);
        bind("deposit-nft", commands);
        bind("trade", commands);
        if (getCommand("exportkey") == null) {
            throw new IllegalStateException("plugin.yml has no command /exportkey");
        }
        KeyExport keyExport = new KeyExport(this);
        getCommand("exportkey").setExecutor(keyExport);
        getCommand("exportkey").setTabCompleter(keyExport);
        getServer().getPluginManager().registerEvents(keyExport, this);
        tally = new EventTally(this);
        getServer().getPluginManager().registerEvents(tally, this);

        getServer().getScheduler().runTask(this, this::startWorld);
    }

    private int worldAttempts;

    private void startWorld() {
        if (getServer().getWorld(IslandManager.WORLD_NAME) == null && worldAttempts++ < 20) {
            getServer().getScheduler().runTaskLater(this, this::startWorld, 10L);
            return;
        }
        realIp.bind();
        getServer().getScheduler().runTaskTimer(this, () -> RealIp.purge(getServer().getOnlinePlayers()), 1200L, 1200L);
        auth.start();
        islands.load();
        hub.load();
        stats.start();
        sidebar.start(this);
        minions.start();
        guide.start();
        mobs.start();
        arena.start();
        market.start();
        tally.start();
        getLogger().info("SkyRealm " + (realm.enabled() ? realm.address() : "off") + ": bazaar, auctions, arena VRF, relic pool.");
        long period = Math.max(1, getConfig().getInt("batch-seconds", 4)) * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            flushBatches();
            skillChain.flush();
        }, period, period);
        if (words != null) {
            words.start();
            getLogger().info("Somnia SkyBlock is on. The world moves first and rolls back if the receipt fails. Each player pays gas from their own purse.");
        } else {
            getLogger().info("Somnia SkyBlock is on. Breaks are instant. Each player pays gas from their own purse.");
        }
    }

    @Override
    public void onDisable() {
        if (tally != null) {
            tally.stop();
        }
        if (hold != null) {
            hold.saveSandbox();
        }
        if (moves != null) {
            moves.stop();
        }
        if (hub != null) {
            hub.restoreRegen();
        }
        flushBatches();
        if (market != null) {
            market.save();
        }
        if (minions != null) {
            minions.save();
        }
        if (profiles != null) {
            profiles.saveAll();
        }
        if (words != null) {
            words.stop();
        }
        if (wallets != null) {
            wallets.close();
        }
        if (auth != null) {
            auth.logoutAll();
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new VoidGenerator();
    }

    ProfileStore profiles() {
        return profiles;
    }

    IslandGuide guide() {
        return guide;
    }

    Coops coops() {
        return coops;
    }

    IslandManager islands() {
        return islands;
    }

    HubManager hub() {
        return hub;
    }

    ChainClient chain() {
        return chain;
    }

    Sidebar sidebar() {
        return sidebar;
    }

    WalletService wallets() {
        return wallets;
    }

    HoldSync hold() {
        return hold;
    }

    boolean wordMode() {
        return words != null;
    }

    Skills skills() {
        return skills;
    }

    SkillChain skillChain() {
        return skillChain;
    }

    Chat chat() {
        return chat;
    }

    SbMenu menu() {
        return menu;
    }

    Minions minions() {
        return minions;
    }

    WordChain words() {
        return words;
    }

    RealmChain realm() {
        return realm;
    }

    Stats stats() {
        return stats;
    }

    Mobs mobs() {
        return mobs;
    }

    Arena arena() {
        return arena;
    }

    MarketGui marketGui() {
        return marketGui;
    }

    Market market() {
        return market;
    }

    Quests quests() {
        return quests;
    }

    SkyCommands commands() {
        return commands;
    }

    TradeManager trades() {
        return trades;
    }

    /**
     * The separate SkyIsland per-resource batches (applyBatch) are gone: island breaks now go through the same path
     * as the hub (SkyWord bag write + SkySkills XP/collection batch every 4 s). Kept as a hook for the timer.
     */
    void flushBatches() {
    }

    private void bind(String name, SkyCommands commands) {
        if (getCommand(name) == null) {
            throw new IllegalStateException("plugin.yml has no command /" + name);
        }
        getCommand(name).setExecutor(commands);
        getCommand(name).setTabCompleter(commands);
    }
}
