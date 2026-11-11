package dev.somnia.skyblock;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.web3j.abi.datatypes.Type;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Colosseum waves (PvE only). Practice runs cost coins. Ranked runs pay 0.005 STT into the Relic Pool,
 * the cleared wave is written on-chain and Somnia VRF rolls the loot and the relic chance.
 */
final class Arena implements Listener {
    private static final int Y = IslandManager.GROUND_Y;
    /** Floor centre of the Colosseum (META landmarks.colosseum.center), east of the plaza (floor at Y). */
    static final int CX = 165;
    static final int CZ = 5;
    static final int MAX_WAVE = 10;
    static final long PRACTICE_FEE = 50;

    private static final class Run {
        UUID player;
        boolean ranked;
        BigInteger runId;
        int wave;
        int kills;
        long coins;
        boolean entering;
        final Set<UUID> alive = new HashSet<>();
        BossBar bar;
        int total;
    }

    private final SomniaSkyblockPlugin plugin;
    private Run run;

    Arena(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!ENABLED) {
            return;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    /** True when the player stands in or near the Colosseum. */
    boolean near(Player player) {
        return plugin.hub().isHub(player.getWorld()) && player.getLocation().distanceSquared(center()) < 140 * 140;
    }

    boolean busy() {
        return run != null;
    }

    /** Colosseum wave fights are cancelled: the Colosseum is reserved as the future dungeon hub. The SkyRealm arena contract stays deployed but unused. */
    static final boolean ENABLED = false;

    private static void sealed(Player player) {
        player.sendMessage(Component.text("The Colosseum is sealed for now. It will open later as the gateway to dungeons.", NamedTextColor.GRAY));
    }

    void open(Player player) {
        if (!ENABLED) {
            sealed(player);
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Market.View view = new Market.View(27, "Colosseum — Arena Warden");
        ItemStack pane = Market.icon(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of());
        for (int i = 0; i < 27; i++) {
            view.getInventory().setItem(i, pane);
        }
        String status = run == null ? "+The arena is free." : "-Busy: " + name(run.player) + " is on wave " + run.wave;
        view.set(11, Market.icon(Material.IRON_SWORD, "Practice Run", NamedTextColor.GREEN, List.of(
                "$Entry: " + PRACTICE_FEE + " coins",
                "10 waves, a Champion boss on waves 5 and 10.",
                "Coins + Combat XP per wave. Off-chain.",
                status, "!Click to enter")), click -> {
            player.closeInventory();
            practice(player);
        });
        view.set(13, Market.icon(Material.BOOK, "Your record", NamedTextColor.YELLOW, List.of(
                "Best wave: " + profile.bestWave,
                "Kills: " + profile.kills,
                "Combat " + Stats.combatLevel(profile.combatXp),
                "Ranked best wave is stored in SkyRealm.bestWave.")), null);
        view.set(15, Market.icon(Material.NETHER_STAR, "Ranked Run (on-chain)", NamedTextColor.LIGHT_PURPLE, List.of(
                "~Entry: 0.005 STT from your purse → Relic Pool",
                "Your cleared wave + kills are written to SkyRealm.",
                "Clear wave 3+: Somnia VRF rolls your loot and",
                "a 17–45% chance at a Relic (a share of the pool).",
                "Loot: Champion's Blade 1%, Colosseum Sigil 5%,",
                "Revenant Flesh, Wolf Teeth or coins.",
                status, "!Click to sign the entry")), click -> {
            player.closeInventory();
            ranked(player);
        });
        view.set(22, Market.icon(Material.GOLD_NUGGET, "Relic Bank", NamedTextColor.GOLD, List.of("Check and cash in relics.")), click -> plugin.market().openBank(player));
        player.openInventory(view.getInventory());
    }

    private String name(UUID id) {
        Player player = plugin.getServer().getPlayer(id);
        return player == null ? "someone" : player.getName();
    }

    private boolean free(Player player) {
        if (run != null) {
            player.sendMessage(Component.text("The Colosseum is busy: " + name(run.player) + " is fighting wave " + run.wave + ". Watch from the stands!", NamedTextColor.RED));
            return false;
        }
        if (!plugin.hub().isHub(player.getWorld())) {
            player.sendMessage(Component.text("The " + plugin.hub().name("colosseum") + " is east of " + plugin.hub().name("plaza") + ": /arena takes you to its gate.", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    void practice(Player player) {
        if (!ENABLED) {
            sealed(player);
            return;
        }
        if (!free(player) || !plugin.stats().spendCoins(player, PRACTICE_FEE)) {
            return;
        }
        Run next = new Run();
        next.player = player.getUniqueId();
        run = next;
        begin(player);
    }

    void ranked(Player player) {
        if (!ENABLED) {
            sealed(player);
            return;
        }
        if (!free(player)) {
            return;
        }
        if (!plugin.realm().enabled() || plugin.hold().offchain(player)) {
            player.sendMessage(Component.text("Ranked runs need SkyRealm and a survival purse.", NamedTextColor.RED));
            return;
        }
        Run next = new Run();
        next.player = player.getUniqueId();
        next.ranked = true;
        next.entering = true;
        run = next;
        plugin.realm().send(player.getUniqueId(), "Ranked arena entry", RealmChain.enterArena(), RealmChain.ARENA_FEE,
                receipt -> {
                    BigInteger id = RealmChain.indexed(receipt, "ArenaEntered(uint256,address)", 1);
                    Player online = plugin.getServer().getPlayer(next.player);
                    if (id == null || online == null || run != next) {
                        if (run == next) {
                            run = null;
                        }
                        return;
                    }
                    next.runId = id;
                    next.entering = false;
                    online.sendMessage(Component.text("Ranked run #" + id + " is open on Somnia. Good luck!", NamedTextColor.LIGHT_PURPLE));
                    begin(online);
                },
                reason -> {
                    if (run == next) {
                        run = null;
                    }
                    Player online = plugin.getServer().getPlayer(next.player);
                    if (online != null) {
                        online.sendMessage(Component.text("Arena entry failed: " + reason, NamedTextColor.RED));
                    }
                });
    }

    /** Arena floor centre: META landmarks.colosseum when the hub has it, else the built-in spot. */
    private Location center() {
        Location meta = plugin.hub().arenaCenter();
        return meta != null ? meta : new Location(plugin.hub().world(), CX + 0.5, Y + 1, CZ + 0.5, 180f, 0f);
    }

    private Location exit() {
        return plugin.hub().gate();
    }

    private void begin(Player player) {
        player.teleport(center().clone().add(0, 0, -3));
        plugin.stats().heal(player);
        run.bar = BossBar.bossBar(Component.text("Colosseum — get ready", NamedTextColor.GOLD), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        player.showBossBar(run.bar);
        player.showTitle(Title.title(Component.text("COLOSSEUM", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                Component.text(run.ranked ? "Ranked run #" + run.runId : "Practice run", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(300))));
        Run current = run;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (run == current) {
                nextWave();
            }
        }, 60L);
    }

    private void nextWave() {
        Player player = plugin.getServer().getPlayer(run.player);
        if (player == null) {
            finish(false);
            return;
        }
        run.wave++;
        int wave = run.wave;
        World world = plugin.hub().world();
        double hpScale = 1 + 0.35 * (wave - 1);
        double dmgScale = 1 + 0.15 * (wave - 1);
        int level = 2 + wave * 3;
        boolean boss = wave % 5 == 0;
        int count = boss ? 1 + wave / 3 : Math.min(2 + wave, 10);
        run.alive.clear();
        for (int i = 0; i < count; i++) {
            Mobs.Type type;
            if (boss && i == 0) {
                type = Mobs.Type.CHAMPION;
            } else {
                int roll = (i + wave) % 4;
                type = roll == 0 || wave < 2 ? Mobs.Type.GLADIATOR
                        : roll == 1 ? Mobs.Type.ARCHER
                        : roll == 2 && wave >= 3 ? Mobs.Type.SPIDER
                        : wave >= 4 ? Mobs.Type.HOUND : Mobs.Type.GLADIATOR;
            }
            double angle = Math.PI * 2 * i / count;
            Location mid = center();
            Location at = new Location(world, mid.getX() + Math.sin(angle) * 5, mid.getY(), mid.getZ() + Math.cos(angle) * 5);
            double scale = type == Mobs.Type.CHAMPION ? (wave == 10 ? 2.0 : 1.0) : hpScale;
            LivingEntity mob = plugin.mobs().spawn(type, at, type == Mobs.Type.CHAMPION ? level + 5 : level, scale,
                    type == Mobs.Type.CHAMPION ? (wave == 10 ? 1.5 : 1.0) : dmgScale, run.player);
            if (mob != null) {
                run.alive.add(mob.getUniqueId());
                if (mob instanceof org.bukkit.entity.Mob ai) {
                    ai.setTarget(player);
                }
            }
        }
        run.total = run.alive.size();
        player.showTitle(Title.title(Component.text("Wave " + wave, boss ? NamedTextColor.GOLD : NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                Component.text(boss ? "The Colosseum Champion enters!" : run.total + " enemies", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))));
        player.playSound(player.getLocation(), boss ? Sound.ENTITY_WITHER_SPAWN : Sound.EVENT_RAID_HORN, 0.7f, 1f);
        updateBar();
    }

    private void updateBar() {
        if (run == null || run.bar == null) {
            return;
        }
        run.bar.name(Component.text("Wave " + run.wave + "/" + MAX_WAVE + " — " + run.alive.size() + " left — " + run.kills + " kills", NamedTextColor.GOLD));
        run.bar.progress(run.total == 0 ? 0f : Math.max(0f, Math.min(1f, run.alive.size() / (float) run.total)));
    }

    void onMobDeath(LivingEntity mob) {
        if (run == null || !run.alive.remove(mob.getUniqueId())) {
            return;
        }
        run.kills++;
        updateBar();
        if (!run.alive.isEmpty()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(run.player);
        if (player == null) {
            finish(false);
            return;
        }
        int wave = run.wave;
        long coins = 15L * wave;
        run.coins += coins;
        plugin.stats().addCoins(player, coins);
        plugin.stats().addCombatXp(player, 10L * wave);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (wave > profile.bestWave) {
            profile.bestWave = wave;
            plugin.profiles().save(profile);
        }
        player.sendMessage(Component.text("Wave " + wave + " cleared! +" + coins + " coins, +" + (10 * wave) + " Combat XP", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        plugin.quests().hit(player, "wave", wave);
        if (wave >= MAX_WAVE) {
            finish(true);
            return;
        }
        Run current = run;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (run == current) {
                nextWave();
            }
        }, 80L);
    }

    /** Called by Stats when a player's HP reaches 0. */
    boolean onDeath(Player player) {
        if (run == null || !run.player.equals(player.getUniqueId()) || run.entering) {
            return false;
        }
        player.sendMessage(Component.text("You fell on wave " + run.wave + ".", NamedTextColor.RED));
        finish(false);
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (run != null && run.player.equals(event.getPlayer().getUniqueId()) && !run.entering) {
            finish(false);
        }
    }

    void leave(Player player) {
        if (run != null && run.player.equals(player.getUniqueId()) && !run.entering) {
            player.sendMessage(Component.text("You left the Colosseum.", NamedTextColor.GRAY));
            finish(false);
        } else {
            player.sendMessage(Component.text("You are not in an arena run.", NamedTextColor.GRAY));
        }
    }

    private void tick() {
        if (run == null || run.entering) {
            return;
        }
        Player player = plugin.getServer().getPlayer(run.player);
        if (player == null) {
            finish(false);
            return;
        }
        if (!plugin.hub().isHub(player.getWorld()) || player.getLocation().distanceSquared(center()) > 18 * 18) {
            player.sendMessage(Component.text("You left the arena floor.", NamedTextColor.GRAY));
            finish(false);
            return;
        }
        World world = plugin.hub().world();
        run.alive.removeIf(id -> {
            Entity entity = world.getEntity(id);
            return entity == null || entity.isDead() || !entity.isValid();
        });
        for (UUID id : run.alive) {
            Entity entity = world.getEntity(id);
            if (entity instanceof org.bukkit.entity.Mob mob) {
                if (mob.getLocation().distanceSquared(center()) > 14 * 14) {
                    mob.teleport(center());
                }
                if (mob.getTarget() == null) {
                    mob.setTarget(player);
                }
            }
        }
        updateBar();
    }

    private void finish(boolean victory) {
        Run done = run;
        run = null;
        if (done == null) {
            return;
        }
        World world = plugin.hub().world();
        for (UUID id : done.alive) {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        int cleared = victory ? done.wave : Math.max(0, done.wave - 1);
        Player player = plugin.getServer().getPlayer(done.player);
        if (player != null) {
            if (done.bar != null) {
                player.hideBossBar(done.bar);
            }
            plugin.stats().heal(player);
            if (plugin.hub().isHub(player.getWorld())) {
                player.teleport(exit());
            }
            player.showTitle(Title.title(Component.text(victory ? "VICTORY" : "RUN OVER", victory ? NamedTextColor.GOLD : NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                    Component.text("Waves cleared: " + cleared + "  Kills: " + done.kills + "  Coins: " + done.coins, NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
        }
        if (!done.ranked || done.runId == null) {
            return;
        }
        UUID owner = done.player;
        BigInteger runId = done.runId;
        plugin.realm().send(owner, "Record ranked run #" + runId + " (wave " + cleared + ")", RealmChain.finishRun(runId, cleared, done.kills), BigInteger.ZERO,
                receipt -> {
                    if (cleared >= 3) {
                        message(owner, "Somnia VRF is rolling your loot (verifiable entropy, ~10-20 s)…", NamedTextColor.LIGHT_PURPLE);
                        poll(owner, runId, cleared, 0);
                    } else {
                        message(owner, "Run recorded. Clear 3+ waves for a VRF loot roll.", NamedTextColor.GRAY);
                    }
                },
                reason -> message(owner, "Run not recorded: " + reason, NamedTextColor.RED));
    }

    private void poll(UUID owner, BigInteger runId, int wave, int attempt) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.realm().view(RealmChain.run(runId), out -> {
            List<Type> fields = out;
            int status = ((BigInteger) fields.get(3).getValue()).intValue();
            if (status != 3) {
                if (attempt < 45) {
                    poll(owner, runId, wave, attempt + 1);
                } else {
                    message(owner, "VRF has not answered yet for run #" + runId + ". It stays claimable on-chain.", NamedTextColor.GRAY);
                }
                return;
            }
            int relics = ((BigInteger) fields.get(4).getValue()).intValue();
            int loot = ((BigInteger) fields.get(5).getValue()).intValue();
            loot(owner, runId, wave, relics, loot);
        }, reason -> {
            if (attempt < 45) {
                poll(owner, runId, wave, attempt + 1);
            }
        }), 40L);
    }

    private void loot(UUID owner, BigInteger runId, int wave, int relics, int roll) {
        String key;
        int qty;
        String rarity;
        if (roll < 10) {
            key = "skyblock:champion_blade";
            qty = 1;
            rarity = "LEGENDARY DROP!";
        } else if (roll < 60) {
            key = "skyblock:colosseum_sigil";
            qty = 1;
            rarity = "EPIC DROP!";
        } else if (roll < 300) {
            key = "skyblock:revenant_flesh";
            qty = 2 + wave / 3;
            rarity = "RARE DROP!";
        } else if (roll < 500) {
            key = "skyblock:wolf_tooth";
            qty = 2;
            rarity = "RARE DROP!";
        } else {
            key = null;
            qty = 0;
            rarity = "";
        }
        Player player = plugin.getServer().getPlayer(owner);
        if (key != null) {
            plugin.market().giveOrStash(owner, key, qty, true);
        } else if (player != null) {
            plugin.stats().addCoins(player, 40L * wave);
        } else {
            Profile profile = plugin.profiles().get(owner);
            profile.coins += 40L * wave;
            plugin.profiles().save(profile);
        }
        if (player == null) {
            return;
        }
        plugin.quests().hit(player, "vrf", 1);
        player.sendMessage(Component.text("⚄ Somnia VRF roll " + roll + "/1000 for run #" + runId + " (on-chain ArenaLoot event)", NamedTextColor.LIGHT_PURPLE));
        if (key != null) {
            player.sendMessage(Component.text(rarity + " ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    .append(Component.text(qty + "x " + Market.label(key), NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, false)));
            if (roll < 60) {
                plugin.getServer().broadcast(Component.text("✦ " + player.getName() + " rolled " + Market.label(key) + " in the Colosseum (Somnia VRF)!", NamedTextColor.GOLD));
            }
        } else {
            player.sendMessage(Component.text("+" + (40 * wave) + " bonus coins", NamedTextColor.GOLD));
        }
        if (relics > 0) {
            player.sendMessage(Component.text("✦ +" + relics + " Relic on-chain! Cash it in at the Banker for a share of the Relic Pool.", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        } else {
            player.sendMessage(Component.text("No relic this time (chance was " + Math.min(60, 5 + 4 * wave) + "%).", NamedTextColor.GRAY));
        }
        player.sendMessage(plugin.realm().contractLine());
    }

    private void message(UUID owner, String text, NamedTextColor color) {
        Player player = plugin.getServer().getPlayer(owner);
        if (player != null) {
            player.sendMessage(Component.text(text, color));
        }
    }
}
