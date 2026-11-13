package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player combat stats. Custom HP is mapped onto the vanilla hearts; strength, crit and
 * defense drive every PvE hit. There is no PvP anywhere.
 */
final class Stats implements Listener {
    /** Combat uses the same wiki skill curve as every other skill (cumulative XP per level, capped at v1 level 25). */
    static final long[] COMBAT = combatTable();

    private static long[] combatTable() {
        long[] out = new long[ProgressMath.V1_CAP + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = ProgressMath.levelStart(i);
        }
        return out;
    }

    record Sheet(int health, int defense, int strength, int critChance, int critDamage, int damage, Gear.Kind weapon, int intelligence, int speed) {
    }

    record Hit(double amount, boolean crit) {
    }

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, Double> hp = new HashMap<>();
    private final Map<UUID, Long> hurtAt = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private long ticks;
    private final Map<UUID, Double> mana = new HashMap<>();
    private final Map<UUID, int[]> chainMana = new HashMap<>();
    private final Map<UUID, Long> chainManaAt = new HashMap<>();
    private final Map<UUID, Component> flash = new HashMap<>();
    private final Map<UUID, Long> flashUntil = new HashMap<>();

    Stats(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    static int combatLevel(long xp) {
        return ProgressMath.level(xp);
    }

    static long nextLevelXp(int level) {
        return level + 1 < COMBAT.length ? COMBAT[level + 1] : -1;
    }

    Sheet sheet(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        int level = combatLevel(profile.combatXp);
        // Wiki base stats: 100 Health, 0 Defense, 0 Strength, 30% Crit Chance, 50% Crit Damage, 100 Speed.
        // SkyBlock Level: +5 Health per level, +1 Strength every 5 levels. Combat: +0.5% Crit Chance per level.
        int sbLevel = Skills.skyblockLevel(profile);
        int health = 100 + sbLevel * 5;
        int defense = 0;
        int strength = sbLevel / 5;
        int critChance = 30 + level / 2;
        int critDamage = 50;
        int damage = 0;
        health += Skills.perk(profile, "❤ Health");
        defense += Skills.perk(profile, "❈ Defense");
        strength += Skills.perk(profile, "❁ Strength");
        int intelligence = Skills.perk(profile, "✎ Intelligence");
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null || piece.getType().isAir()) {
                continue;
            }
            Gear.Kind kind = Gear.of(plugin, piece);
            if (kind != null && kind.armor()) {
                health += kind.health();
                defense += kind.defense();
                strength += kind.strength();
                critChance += kind.critChance();
                critDamage += kind.critDamage();
            } else if (kind == null) {
                defense += Gear.vanillaDefense(piece.getType());
            }
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Gear.Kind weapon = Gear.of(plugin, hand);
        if (weapon != null && weapon.weapon()) {
            damage += weapon.damage();
            strength += weapon.strength();
            critChance += weapon.critChance();
            critDamage += weapon.critDamage();
        } else {
            weapon = null;
            damage += Gear.vanillaDamage(plugin, hand);
            strength += Gear.vanillaStrength(plugin, hand);
        }
        return new Sheet(health, defense, strength, critChance, critDamage, damage, weapon, intelligence, 100);
    }

    /**
     * Wiki Damage Calculation: (5 + weapon) * (1 + strength/100), * (1 + critDamage/100) on a crit,
     * * additive (1 + Warrior 4% per Combat level), * multiplicative (undead bonus), rounded down.
     */
    Hit attack(Player player, boolean undead, double scale) {
        Sheet sheet = sheet(player);
        Profile profile = plugin.profiles().get(player.getUniqueId());
        double base = (5 + sheet.damage()) * (1 + sheet.strength() / 100.0);
        boolean crit = ThreadLocalRandom.current().nextInt(100) < sheet.critChance();
        if (crit) {
            base *= 1 + sheet.critDamage() / 100.0;
        }
        base *= 1 + warrior(combatLevel(profile.combatXp)) / 100.0;
        if (undead) {
            base *= Gear.undeadMultiplier(sheet.weapon());
        }
        return new Hit(Math.max(1, Math.floor(base * scale)), crit);
    }

    /** Warrior (Combat perk): +4% damage to mobs per level 1-50, +1% per level 51-60. */
    static int warrior(int combatLevel) {
        return 4 * Math.min(combatLevel, 50) + Math.max(0, combatLevel - 50);
    }

    double health(Player player) {
        return hp.getOrDefault(player.getUniqueId(), (double) sheet(player).health());
    }

    void heal(Player player) {
        hp.put(player.getUniqueId(), (double) sheet(player).health());
        paint(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.SUICIDE
                || cause == EntityDamageEvent.DamageCause.WORLD_BORDER) {
            return;
        }
        double amount;
        String source = cause.name().toLowerCase().replace('_', ' ');
        Sheet sheet = sheet(player);
        if (event instanceof EntityDamageByEntityEvent by) {
            Entity damager = by.getDamager();
            Entity origin = damager;
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                origin = shooter;
            }
            if (origin instanceof Player) {
                // No PvP in any world. Combat is PvE only.
                event.setCancelled(true);
                return;
            }
            Double mob = plugin.mobs().damageOf(origin);
            amount = mob != null ? mob : event.getDamage() * 5;
            amount = amount * 100.0 / (100.0 + sheet.defense());
            String name = plugin.mobs().nameOf(origin);
            source = name != null ? name : origin.getType().name().toLowerCase().replace('_', ' ');
        } else {
            amount = event.getDamage() * 5;
        }
        event.setDamage(0.0);
        damage(player, amount, source);
        if (health(player) <= 0) {
            event.setCancelled(true);
            die(player, source);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(SbLoginEvent event) {
        hp.remove(event.getPlayer().getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> heal(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hp.remove(event.getPlayer().getUniqueId());
        hurtAt.remove(event.getPlayer().getUniqueId());
        dirty.remove(event.getPlayer().getUniqueId());
        UUID gone = event.getPlayer().getUniqueId();
        mana.remove(gone);
        chainMana.remove(gone);
        chainManaAt.remove(gone);
        flash.remove(gone);
        flashUntil.remove(gone);
        plugin.sidebar().forget(gone);
    }

    private void damage(Player player, double amount, String source) {
        UUID id = player.getUniqueId();
        double now = health(player) - amount;
        hp.put(id, now);
        hurtAt.put(id, System.currentTimeMillis());
        if (now > 0) {
            paint(player);
        }
    }

    void die(Player player, String source) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        long lost = profile.coins / 10;
        profile.coins -= lost;
        plugin.profiles().save(profile);
        heal(player);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
        boolean arena = plugin.arena().onDeath(player);
        if (!arena) {
            if (plugin.hub().isHub(player.getWorld())) {
                plugin.hub().teleport(player);
            } else if (profile.islandReady) {
                plugin.islands().teleport(player, profile);
            }
        }
        player.sendMessage(Component.text("☠ You were killed by " + source + (lost > 0 ? " and lost " + String.format(java.util.Locale.ROOT, "%,d", lost) + " coins." : "."), NamedTextColor.RED));
        markDirty(player);
    }

    void addCoins(Player player, long coins) {
        if (coins == 0) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        profile.coins = Math.max(0, profile.coins + coins);
        markDirty(player);
    }

    boolean spendCoins(Player player, long coins) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.coins < coins) {
            player.sendMessage(Component.text("You need " + String.format(java.util.Locale.ROOT, "%,d", coins) + " coins (you have " + String.format(java.util.Locale.ROOT, "%,d", profile.coins) + ").", NamedTextColor.RED));
            return false;
        }
        profile.coins -= coins;
        plugin.profiles().save(profile);
        markDirty(player);
        return true;
    }

    void addCombatXp(Player player, long xp) {
        plugin.skills().add(player, Skills.Skill.COMBAT, xp);
    }

    /** Shows a message in the middle of the action bar for a short time ("+6 Foraging (18/50)", "-45 Mana (...)"). */
    void flash(Player player, Component text, long millis) {
        flash.put(player.getUniqueId(), text);
        flashUntil.put(player.getUniqueId(), System.currentTimeMillis() + millis);
        player.sendActionBar(bar(player, null));
    }

    /** The word contract owns the mana of signed-in players; it reports it here each second. */
    void chainMana(Player player, int current, int max) {
        chainMana.put(player.getUniqueId(), new int[]{current, max});
        chainManaAt.put(player.getUniqueId(), System.currentTimeMillis());
    }

    int maxMana(Player player) {
        return 100 + sheet(player).intelligence();
    }

    /** Local mana for abilities that are not on chain. */
    boolean spendMana(Player player, int cost, String ability) {
        UUID id = player.getUniqueId();
        double cur = mana.getOrDefault(id, (double) maxMana(player));
        if (cur < cost) {
            player.sendActionBar(Component.text("Not enough mana!", NamedTextColor.RED));
            return false;
        }
        mana.put(id, cur - cost);
        flash(player, Component.text("-" + cost + " Mana (", NamedTextColor.AQUA).append(Component.text(ability, NamedTextColor.GOLD))
                .append(Component.text(")", NamedTextColor.AQUA)), 1500);
        return true;
    }

    private int[] manaOf(Player player) {
        UUID id = player.getUniqueId();
        Long at = chainManaAt.get(id);
        if (at != null && System.currentTimeMillis() - at < 3000) {
            return chainMana.get(id);
        }
        int max = maxMana(player);
        return new int[]{(int) Math.floor(mana.getOrDefault(id, (double) max)), max};
    }

    /** Writes a progress checkpoint to SkyRealm.stats(player). The player pays the gas. */
    void checkpoint(Player player, int milestone) {
        if (!plugin.realm().enabled() || plugin.hold().offchain(player)) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        BigInteger packed = packed(profile);
        plugin.realm().send(player.getUniqueId(), "Combat checkpoint (level " + combatLevel(profile.combatXp) + ")",
                RealmChain.checkpoint(packed, milestone), BigInteger.ZERO,
                receipt -> {
                    profile.lastCheckpoint = combatLevel(profile.combatXp);
                    plugin.profiles().save(profile);
                },
                reason -> {
                    Player online = plugin.getServer().getPlayer(profile.uuid);
                    if (online != null) {
                        online.sendMessage(Component.text("Checkpoint not written: " + reason, NamedTextColor.GRAY));
                    }
                });
    }

    /** level | kills<<8 | bestWave<<40 | combatXp<<56 | coins<<120 */
    static BigInteger packed(Profile profile) {
        BigInteger value = BigInteger.valueOf(combatLevel(profile.combatXp) & 0xff);
        value = value.or(BigInteger.valueOf(profile.kills & 0xffffffffL).shiftLeft(8));
        value = value.or(BigInteger.valueOf(profile.bestWave & 0xffff).shiftLeft(40));
        value = value.or(BigInteger.valueOf(Math.max(0, profile.combatXp)).shiftLeft(56));
        value = value.or(BigInteger.valueOf(Math.max(0, profile.coins)).shiftLeft(120));
        return value;
    }

    void markDirty(Player player) {
        dirty.add(player.getUniqueId());
    }

    /**
     * List/chat name color: red while /dev is on, aqua while a Booster buff is active, otherwise gray
     * (Hypixel unranked). Call this when the buff or dev mode changes so the tab list reverts in the same tick.
     * The custom tab grid in {@link TabList} is what the client actually shows.
     */
    void refreshListName(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Profile tab = plugin.profiles().get(player.getUniqueId());
        int sbLevel = Skills.skyblockLevel(tab);
        Component listName = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text(String.valueOf(sbLevel), Skills.levelColor(sbLevel)))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.getName(), nameColor(player)));
        if (!listName.equals(player.playerListName())) {
            player.playerListName(listName);
        }
    }

    /** Dev red wins over Booster aqua. Normal players stay gray (Hypixel unranked). */
    NamedTextColor nameColor(Player player) {
        if (plugin.auth() != null && plugin.auth().isDev(player)) {
            return NamedTextColor.RED;
        }
        if (plugin.cookies() != null && plugin.cookies().active(player)) {
            return NamedTextColor.AQUA;
        }
        return NamedTextColor.GRAY;
    }

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Sheet sheet = sheet(player);
            double max = sheet.health();
            double cur = hp.getOrDefault(id, max);
            if (ticks % 4 == 0 && cur < max) {
                Long hurt = hurtAt.get(id);
                double regen = 1.5 + max * 0.01;
                if (hurt == null || now - hurt > 5000) {
                    regen *= 3;
                }
                cur += regen;
            }
            cur = Math.min(cur, max);
            hp.put(id, cur);
            int maxMana = 100 + sheet.intelligence();
            double m = mana.getOrDefault(id, (double) maxMana);
            mana.put(id, Math.min(maxMana, m + maxMana * 0.01));
            paint(player);
            player.sendActionBar(bar(player, null));
            if (ticks % 4 == 1) {
                discoverStats(player, sheet);
            }
            if (ticks % 4 == 2) {
                // Speed works like the menu says: 100 = vanilla walk speed (0.2).
                float walk = (float) Math.max(0.05, Math.min(1.0, 0.2 * sheet.speed() / 100.0));
                if (Math.abs(player.getWalkSpeed() - walk) > 0.0001f) {
                    player.setWalkSpeed(walk);
                }
                // Tab list: [SkyBlock Level] name. Name is red in /dev, gold with a Booster, otherwise white.
                refreshListName(player);
            }
            if (ticks % 4 == 0 && dirty.remove(id)) {
                Profile profile = plugin.profiles().get(id);
                plugin.sidebar().show(player, profile);
                plugin.profiles().save(profile);
            }
        }
    }

    private void paint(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
            return;
        }
        double max = sheet(player).health();
        double cur = hp.getOrDefault(player.getUniqueId(), max);
        double hearts = Math.max(1.0, Math.min(20.0, 20.0 * cur / max));
        try {
            player.setHealth(hearts);
        } catch (IllegalArgumentException ignored) {
            // max health attribute changed by something else
        }
    }

    Component bar(Player player, String extra) {
        Sheet sheet = sheet(player);
        UUID id = player.getUniqueId();
        double cur = hp.getOrDefault(id, (double) sheet.health());
        int[] m = manaOf(player);
        Component middle;
        Long until = flashUntil.get(id);
        if (until != null && until > System.currentTimeMillis() && flash.get(id) != null) {
            middle = flash.get(id);
        } else if (extra != null && !extra.isEmpty()) {
            middle = Component.text(extra, NamedTextColor.AQUA);
        } else {
            middle = Component.text(sheet.defense() + "❈ Defense", NamedTextColor.GREEN);
        }
        return Component.text((int) Math.ceil(Math.max(0, cur)) + "/" + sheet.health() + "❤", NamedTextColor.RED)
                .append(Component.text("     "))
                .append(middle)
                .append(Component.text("     "))
                .append(Component.text(m[0] + "/" + m[1] + "✎ Mana", NamedTextColor.AQUA));
    }

    private void discoverStats(Player player, Sheet sheet) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (sheet.defense() > 0 && profile.discovered.add("s:defense")) {
            Chat.newStat(player, "❈", "Defense", NamedTextColor.GREEN, "Defense reduces the damage you take from mobs: damage × 100 / (100 + Defense).");
            plugin.profiles().save(profile);
        } else if (sheet.strength() > 0 && profile.discovered.add("s:strength")) {
            Chat.newStat(player, "❁", "Strength", NamedTextColor.RED, "Strength raises every hit: (5 + weapon damage) × (1 + Strength / 100).");
            plugin.profiles().save(profile);
        } else if (sheet.intelligence() > 0 && profile.discovered.add("s:intelligence")) {
            Chat.newStat(player, "✎", "Intelligence", NamedTextColor.AQUA, "Intelligence raises your maximum Mana for item abilities.");
            plugin.profiles().save(profile);
        }
    }

    void show(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        plugin.menu().openStats(player);
        Sheet sheet = sheet(player);
        int level = combatLevel(profile.combatXp);
        long next = nextLevelXp(level);
        player.sendMessage(Component.text("—— Your SkyBlock stats ——", NamedTextColor.GOLD));
        player.sendMessage(Component.text("❤ Health " + sheet.health() + "   ❈ Defense " + sheet.defense(), NamedTextColor.GREEN));
        player.sendMessage(Component.text("❁ Strength " + sheet.strength() + "   ☣ Crit Chance " + sheet.critChance() + "%   ☠ Crit Damage " + sheet.critDamage() + "%", NamedTextColor.RED));
        player.sendMessage(Component.text("⚔ Weapon damage " + sheet.damage() + "   Hit ≈ " + Math.round((5 + sheet.damage()) * (1 + sheet.strength() / 100.0))
                + " (crit " + Math.round((5 + sheet.damage()) * (1 + sheet.strength() / 100.0) * (1 + sheet.critDamage() / 100.0)) + ")", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Combat " + level + " (" + profile.combatXp + (next > 0 ? "/" + next : "") + " XP)   Kills " + profile.kills + "   Best arena wave " + profile.bestWave, NamedTextColor.AQUA));
        StringBuilder skills = new StringBuilder();
        for (Skills.Skill skill : Skills.Skill.values()) {
            if (skill == Skills.Skill.COMBAT) {
                continue;
            }
            skills.append(skill.label).append(' ').append(Skills.level(profile, skill)).append("  ");
        }
        player.sendMessage(Component.text(skills.toString().trim(), NamedTextColor.DARK_AQUA));
        player.sendMessage(Component.text("SkyBlock Level " + Skills.skyblockLevel(profile) + "   Collections " + Collects.unlocked(profile) + "/" + Collects.ENTRIES.size(), NamedTextColor.AQUA));
        player.sendMessage(Component.text("Coins " + String.format(java.util.Locale.ROOT, "%,d", profile.coins) + "   Purse " + WalletService.shortAddress(profile.wallet), NamedTextColor.GOLD));
        if (plugin.realm().enabled()) {
            player.sendMessage(plugin.realm().contractLine());
        }
    }
}
