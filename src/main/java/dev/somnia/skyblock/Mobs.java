package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Hub combat zones (Ashen Barrows, Hollow Crypts, Thornkeep Ruins): mobs live only at fixed spawn points. Also the mob model the Colosseum reuses. */
final class Mobs implements Listener {
    private static final int Y = IslandManager.GROUND_Y;
    static final String TAG = "sb-mob";
    static final String ARENA_TAG = "sb-arena";

    record Drop(String key, int min, int max, double chance) {
    }

    // Hub mob numbers (level / HP / damage / Combat XP / coins) follow the wiki role counterparts:
    // Graveyard Zombie, Zombie Villager, Crypt Ghoul, Golden Ghoul, Wolf. Alpha Direwolf, Bone Archer and the arena pack are ours.
    enum Type {
        GRAVEYARD_ZOMBIE("Barrow Shambler", EntityType.ZOMBIE, 1, 100, 20, 5, 1, 1, true,
                List.of(new Drop("minecraft:rotten_flesh", 1, 2, 1.0), new Drop("skyblock:revenant_flesh", 1, 1, 0.03))),
        ZOMBIE_VILLAGER("Hollow Villager", EntityType.ZOMBIE_VILLAGER, 2, 120, 24, 5, 1, 1, true,
                List.of(new Drop("minecraft:rotten_flesh", 1, 3, 1.0), new Drop("skyblock:revenant_flesh", 1, 1, 0.04))),
        CRYPT_GHOUL("Crypt Wight", EntityType.ZOMBIE, 33, 2000, 350, 49, 11, 11, true,
                List.of(new Drop("minecraft:rotten_flesh", 2, 4, 1.0), new Drop("skyblock:revenant_flesh", 1, 2, 0.30))),
        GILDED_GHOUL("Gilded Wight", EntityType.ZOMBIE, 65, 20000, 500, 97, 21, 21, true,
                List.of(new Drop("skyblock:revenant_flesh", 2, 5, 1.0), new Drop("minecraft:gold_ingot", 2, 6, 1.0), new Drop("minecraft:gold_block", 1, 1, 0.10))),
        OLD_WOLF("Thornkeep Wolf", EntityType.WOLF, 11, 500, 75, 16, 3, 3, false,
                List.of(new Drop("minecraft:bone", 1, 2, 1.0), new Drop("skyblock:wolf_tooth", 1, 1, 0.20))),
        DIRE_ALPHA("Alpha Direwolf", EntityType.WOLF, 14, 1800, 80, 70, 40, 90, false,
                List.of(new Drop("skyblock:wolf_tooth", 2, 4, 1.0), new Drop("minecraft:bone", 3, 6, 1.0))),
        RUINS_SKELETON("Bone Archer", EntityType.SKELETON, 4, 160, 30, 9, 3, 6, true,
                List.of(new Drop("minecraft:bone", 1, 3, 1.0), new Drop("minecraft:string", 1, 1, 0.3))),
        GLADIATOR("Arena Gladiator", EntityType.ZOMBIE, 3, 150, 15, 8, 2, 4, true,
                List.of(new Drop("minecraft:rotten_flesh", 1, 2, 0.6))),
        ARCHER("Arena Archer", EntityType.SKELETON, 3, 120, 15, 8, 2, 4, true,
                List.of(new Drop("minecraft:bone", 1, 2, 0.6))),
        SPIDER("Arena Spider", EntityType.SPIDER, 4, 180, 30, 9, 2, 5, false,
                List.of(new Drop("minecraft:string", 1, 2, 0.6), new Drop("minecraft:spider_eye", 1, 1, 0.3))),
        HOUND("Arena Hound", EntityType.WOLF, 5, 200, 35, 10, 3, 6, false,
                List.of(new Drop("skyblock:wolf_tooth", 1, 1, 0.08))),
        CHAMPION("Colosseum Champion", EntityType.ZOMBIE, 10, 1500, 90, 120, 60, 120, true,
                List.of(new Drop("skyblock:revenant_flesh", 2, 4, 1.0), new Drop("skyblock:wolf_tooth", 1, 2, 0.5)));

        final String title;
        final EntityType entity;
        final int level;
        final int health;
        final int damage;
        final int xp;
        final int coinMin;
        final int coinMax;
        final boolean undead;
        final List<Drop> drops;

        Type(String title, EntityType entity, int level, int health, int damage, int xp, int coinMin, int coinMax, boolean undead, List<Drop> drops) {
            this.title = title;
            this.entity = entity;
            this.level = level;
            this.health = health;
            this.damage = damage;
            this.xp = xp;
            this.coinMin = coinMin;
            this.coinMax = coinMax;
            this.undead = undead;
            this.drops = drops;
        }
    }

    /** Live mobs and the earliest next spawn for each hub spawn point (index from the build file). */
    private static final class PointState {
        final Set<UUID> alive = new HashSet<>();
        long nextAt;
        long watchedAt;
    }

    private final SomniaSkyblockPlugin plugin;
    private final Map<Integer, PointState> points = new HashMap<>();
    private final NamespacedKey keyPoint;
    private final NamespacedKey keyType;
    private final NamespacedKey keyHp;
    private final NamespacedKey keyMax;
    private final NamespacedKey keyDamage;
    private final NamespacedKey keyLevel;
    private final NamespacedKey keyOwner;
    private boolean spawnFixed;

    Mobs(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        keyType = new NamespacedKey(plugin, "mob");
        keyHp = new NamespacedKey(plugin, "mob-hp");
        keyMax = new NamespacedKey(plugin, "mob-max");
        keyDamage = new NamespacedKey(plugin, "mob-dmg");
        keyLevel = new NamespacedKey(plugin, "mob-lvl");
        keyOwner = new NamespacedKey(plugin, "mob-owner");
        keyPoint = new NamespacedKey(plugin, "mob-point");
    }

    void start() {
        World hub = plugin.hub().world();
        if (hub != null) {
            for (Entity entity : hub.getEntities()) {
                if (entity.getScoreboardTags().contains(TAG) || entity.getScoreboardTags().contains("sb-dmg")) {
                    entity.remove();
                }
            }
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    // ---------- model ----------

    boolean isMob(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains(TAG);
    }

    Type typeOf(Entity entity) {
        if (!isMob(entity)) {
            return null;
        }
        String name = entity.getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        try {
            return name == null ? null : Type.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    Double damageOf(Entity entity) {
        if (!isMob(entity)) {
            return null;
        }
        return entity.getPersistentDataContainer().get(keyDamage, PersistentDataType.DOUBLE);
    }

    String nameOf(Entity entity) {
        Type type = typeOf(entity);
        return type == null ? null : type.title;
    }

    UUID ownerOf(Entity entity) {
        String value = entity.getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
        return value == null ? null : UUID.fromString(value);
    }

    LivingEntity spawn(Type type, Location at, int level, double hpScale, double dmgScale, UUID arenaOwner) {
        World world = at.getWorld();
        Entity raw = world.spawnEntity(at, type.entity, false);
        if (!raw.isValid() && !spawnFixed) {
            // The spawn_monsters rule also blocks plugin spawns on some builds. Natural spawning stays off
            // (doMobSpawning false + CreatureSpawnEvent filter), so allow monsters and retry once.
            spawnFixed = true;
            world.setGameRule(org.bukkit.GameRule.SPAWN_MONSTERS, true);
            plugin.getLogger().info("Hub monsters enabled for plugin spawns only.");
            raw = world.spawnEntity(at, type.entity, false);
        }
        if (!(raw instanceof LivingEntity mob) || !raw.isValid()) {
            raw.remove();
            return null;
        }
        double max = Math.round(type.health * hpScale);
        mob.addScoreboardTag(TAG);
        if (arenaOwner != null) {
            mob.addScoreboardTag(ARENA_TAG);
            mob.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, arenaOwner.toString());
        }
        PersistentDataContainer data = mob.getPersistentDataContainer();
        data.set(keyType, PersistentDataType.STRING, type.name());
        data.set(keyHp, PersistentDataType.DOUBLE, max);
        data.set(keyMax, PersistentDataType.DOUBLE, max);
        data.set(keyDamage, PersistentDataType.DOUBLE, (double) Math.round(type.damage * dmgScale));
        data.set(keyLevel, PersistentDataType.INTEGER, level);
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(false);
        mob.setCanPickupItems(false);
        if (mob instanceof Ageable ageable) {
            ageable.setAdult();
        }
        if (mob instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
        if (mob instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
        if (mob instanceof Wolf wolf) {
            wolf.setAngry(true);
        }
        EntityEquipment gear = mob.getEquipment();
        if (gear != null) {
            gear.clear();
            switch (type) {
                case CRYPT_GHOUL -> {
                    gear.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
                    gear.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    gear.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                }
                case GILDED_GHOUL -> {
                    gear.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    gear.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
                    gear.setLeggings(new ItemStack(Material.GOLDEN_LEGGINGS));
                    gear.setBoots(new ItemStack(Material.GOLDEN_BOOTS));
                    gear.setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
                }
                case RUINS_SKELETON, ARCHER -> gear.setItemInMainHand(new ItemStack(Material.BOW));
                case GLADIATOR -> {
                    gear.setHelmet(new ItemStack(Material.IRON_HELMET));
                    gear.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                }
                case CHAMPION -> {
                    gear.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    gear.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
                    gear.setLeggings(new ItemStack(Material.GOLDEN_LEGGINGS));
                    gear.setBoots(new ItemStack(Material.GOLDEN_BOOTS));
                    gear.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                }
                default -> {
                }
            }
            gear.setHelmetDropChance(0f);
            gear.setChestplateDropChance(0f);
            gear.setLeggingsDropChance(0f);
            gear.setBootsDropChance(0f);
            gear.setItemInMainHandDropChance(0f);
            gear.setItemInOffHandDropChance(0f);
        }
        name(mob, type, level, max, max);
        return mob;
    }

    private void name(LivingEntity mob, Type type, int level, double hp, double max) {
        boolean boss = type == Type.CHAMPION || type == Type.GILDED_GHOUL || type == Type.DIRE_ALPHA;
        Component name = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("Lv" + level, NamedTextColor.GRAY))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(type.title, boss ? NamedTextColor.GOLD : NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, boss))
                .append(Component.text(" " + compact(hp), hp > max / 2 ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                .append(Component.text("/", NamedTextColor.WHITE))
                .append(Component.text(compact(max), NamedTextColor.GREEN))
                .append(Component.text("❤", NamedTextColor.RED));
        mob.customName(name);
        mob.setCustomNameVisible(true);
    }

    private static String compact(double value) {
        long v = Math.max(0, Math.round(value));
        if (v >= 10_000) {
            return (v / 1000) + "k";
        }
        return Long.toString(v);
    }

    // ---------- events ----------

    @EventHandler(ignoreCancelled = true)
    public void onNatural(CreatureSpawnEvent event) {
        if (!plugin.hub().isHub(event.getEntity().getWorld())) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM || reason == CreatureSpawnEvent.SpawnReason.DEFAULT) {
            return;
        }
        if (event.getEntity() instanceof org.bukkit.entity.Monster || event.getEntity() instanceof Wolf) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent event) {
        if (isMob(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!isMob(event.getEntity())) {
            return;
        }
        Entity target = event.getTarget();
        if (target == null) {
            return;
        }
        if (!(target instanceof Player player) || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }
        UUID owner = ownerOf(event.getEntity());
        if (owner != null && !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || !isMob(mob)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) {
            return;
        }
        Player attacker = null;
        boolean projectile = false;
        if (event instanceof EntityDamageByEntityEvent by) {
            Entity damager = by.getDamager();
            if (damager instanceof Player player) {
                attacker = player;
            } else if (damager instanceof Projectile shot && shot.getShooter() instanceof Player player) {
                attacker = player;
                projectile = true;
            }
        }
        if (attacker == null) {
            event.setCancelled(true);
            return;
        }
        UUID owner = ownerOf(mob);
        if (owner != null && !owner.equals(attacker.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text("That is someone else's arena run.", NamedTextColor.GRAY));
            return;
        }
        Type type = typeOf(mob);
        double scale = 1.0;
        if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            scale = 0.5;
        } else if (!projectile) {
            float cooldown = attacker.getAttackCooldown();
            if (cooldown < 0.9f) {
                scale = Math.max(0.2, cooldown * cooldown);
            }
        }
        Stats.Hit hit = plugin.stats().attack(attacker, type != null && type.undead, scale);
        PersistentDataContainer data = mob.getPersistentDataContainer();
        double hp = data.getOrDefault(keyHp, PersistentDataType.DOUBLE, 1.0) - hit.amount();
        double max = data.getOrDefault(keyMax, PersistentDataType.DOUBLE, 1.0);
        data.set(keyHp, PersistentDataType.DOUBLE, Math.max(0, hp));
        indicator(mob, hit);
        if (hp <= 0) {
            event.setDamage(mob.getHealth() + 10_000);
            return;
        }
        event.setDamage(0.0);
        if (type != null) {
            name(mob, type, data.getOrDefault(keyLevel, PersistentDataType.INTEGER, type.level), hp, max);
        }
        if (mob instanceof Mob ai && ai.getTarget() == null) {
            ai.setTarget(attacker);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (!isMob(mob)) {
            islandKill(event);
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Integer point = mob.getPersistentDataContainer().get(keyPoint, PersistentDataType.INTEGER);
        if (point != null) {
            PointState state = points.get(point);
            Zones.Point spot = pointAt(point);
            if (state != null) {
                state.alive.remove(mob.getUniqueId());
                if (spot != null) {
                    state.nextAt = Math.max(state.nextAt, System.currentTimeMillis() + spot.respawnSeconds() * 1000L);
                }
            }
        }
        Type type = typeOf(mob);
        Player killer = mob.getKiller();
        if (type != null && killer != null) {
            reward(killer, mob, type);
        }
        plugin.arena().onMobDeath(mob);
    }

    /**
     * Natural island hostiles: no world drops or vanilla XP orbs (those would skip the bag).
     * Combat XP goes through {@link Stats#addCombatXp} (gas-gated). Loot goes through the bag or stash.
     * Hub and arena tagged mobs stay on {@link #reward}.
     */
    private void islandKill(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (plugin.islands() == null || plugin.islands().world() == null || !mob.getWorld().equals(plugin.islands().world())) {
            return;
        }
        if (!(mob instanceof Monster)) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player killer = mob.getKiller();
        if (killer == null) {
            return;
        }
        long xp = islandCombatXp(mob);
        if (xp > 0) {
            plugin.stats().addCombatXp(killer, xp);
        }
        boolean changed = false;
        for (ItemStack stack : drops) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            String key = stack.getType().getKey().toString();
            plugin.market().giveOrStash(killer.getUniqueId(), key, stack.getAmount(), false);
            Collects.add(plugin, killer, stack.getType(), stack.getAmount());
            changed = true;
        }
        if (changed) {
            plugin.market().commit(killer);
        }
    }

    /** Lv 1 values from current Hypixel SkyBlock wiki mob pages (Combat XP). */
    private static long islandCombatXp(LivingEntity mob) {
        return switch (mob.getType()) {
            case ENDERMAN -> 8;
            case CREEPER, SKELETON, STRAY, ZOMBIE, DROWNED, HUSK, SPIDER, CAVE_SPIDER, WITCH, SLIME -> 6;
            default -> 5;
        };
    }

    private void reward(Player killer, LivingEntity mob, Type type) {
        Profile profile = plugin.profiles().get(killer.getUniqueId());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int level = mob.getPersistentDataContainer().getOrDefault(keyLevel, PersistentDataType.INTEGER, type.level);
        double scale = Math.max(1.0, level / (double) type.level);
        long coins = Math.round((type.coinMin + random.nextInt(type.coinMax - type.coinMin + 1)) * Math.sqrt(scale));
        long xp = Math.round(type.xp * Math.sqrt(scale));
        profile.kills++;
        plugin.stats().addCoins(killer, coins);
        plugin.stats().addCombatXp(killer, xp);
        List<String> got = new ArrayList<>();
        boolean changed = false;
        for (Drop drop : type.drops) {
            if (random.nextDouble() >= drop.chance()) {
                continue;
            }
            int qty = drop.min() + random.nextInt(drop.max() - drop.min() + 1);
            plugin.market().giveOrStash(killer.getUniqueId(), drop.key(), qty, false);
            Material dropped = drop.key().startsWith("minecraft:") ? Material.matchMaterial(drop.key()) : null;
            if (dropped != null) {
                Collects.add(plugin, killer, dropped, qty);
            }
            changed = true;
            if (drop.chance() < 0.35) {
                got.add(Market.label(drop.key()) + (qty > 1 ? " x" + qty : ""));
            }
        }
        if (changed) {
            plugin.market().commit(killer);
        }
        if (type == Type.GRAVEYARD_ZOMBIE || type == Type.ZOMBIE_VILLAGER || type == Type.CRYPT_GHOUL || type == Type.GILDED_GHOUL) {
            plugin.quests().hit(killer, "kill_graveyard", 1);
        } else if (type == Type.OLD_WOLF || type == Type.RUINS_SKELETON || type == Type.DIRE_ALPHA) {
            plugin.quests().hit(killer, "kill_ruins", 1);
        }
        for (String rare : got) {
            killer.sendMessage(Component.text("RARE DROP! ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    .append(Component.text(rare, NamedTextColor.BLUE).decoration(TextDecoration.BOLD, false)));
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.6f);
        }
    }

    private void indicator(LivingEntity mob, Stats.Hit hit) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = mob.getLocation().add(random.nextDouble(-0.6, 0.6), mob.getHeight() + 0.2 + random.nextDouble(0.4), random.nextDouble(-0.6, 0.6));
        long value = Math.round(hit.amount());
        Component text;
        if (hit.crit()) {
            String digits = "✧" + String.format(java.util.Locale.ROOT, "%,d", value) + "✧";
            NamedTextColor[] colors = {NamedTextColor.WHITE, NamedTextColor.YELLOW, NamedTextColor.GOLD, NamedTextColor.RED};
            Component built = Component.empty();
            for (int i = 0; i < digits.length(); i++) {
                built = built.append(Component.text(String.valueOf(digits.charAt(i)), colors[i % colors.length]));
            }
            text = built;
        } else {
            text = Component.text(String.format(java.util.Locale.ROOT, "%,d", value), NamedTextColor.GRAY);
        }
        TextDisplay display = mob.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.addScoreboardTag("sb-dmg");
        });
        plugin.getServer().getScheduler().runTaskLater(plugin, display::remove, 18L);
    }

    // ---------- spawn points ----------

    private Zones.Point pointAt(int index) {
        List<Zones.Point> all = plugin.hub().zones().points();
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    private void tick() {
        World hub = plugin.hub().world();
        if (hub == null || plugin.hub().pasting()) {
            return;
        }
        List<Player> players = new ArrayList<>();
        for (Player player : hub.getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                players.add(player);
            }
        }
        long now = System.currentTimeMillis();
        long time = hub.getTime();
        for (Zones.Point point : plugin.hub().zones().points()) {
            if (plugin.hub().zones().safeAt(new Location(hub, point.x() + 0.5, point.y(), point.z() + 0.5))) {
                continue;
            }
            PointState state = points.computeIfAbsent(point.index(), ignored -> new PointState());
            state.alive.removeIf(id -> {
                Entity entity = hub.getEntity(id);
                return entity == null || !entity.isValid() || entity.isDead();
            });
            Location home = new Location(hub, point.x() + 0.5, point.y(), point.z() + 0.5);
            boolean watched = false;
            for (Player player : players) {
                if (player.getLocation().distanceSquared(home) < 64 * 64) {
                    watched = true;
                    break;
                }
            }
            if (watched) {
                state.watchedAt = now;
            } else if (!state.alive.isEmpty() && now - state.watchedAt > 60_000) {
                // Nobody near for a minute: put the mobs away so idle zones cost nothing.
                for (UUID id : state.alive) {
                    Entity entity = hub.getEntity(id);
                    if (entity != null) {
                        entity.remove();
                    }
                }
                state.alive.clear();
                continue;
            }
            for (UUID id : state.alive) {
                if (hub.getEntity(id) instanceof Mob mob) {
                    leash(point, home, mob, players);
                }
            }
            if (!watched || !home.isChunkLoaded() || state.alive.size() >= point.max() || now < state.nextAt || !point.activeAt(time)) {
                continue;
            }
            Type type = pick(point);
            LivingEntity mob = spawn(type, near(home), type.level, 1.0, 1.0, null);
            if (mob != null) {
                mob.getPersistentDataContainer().set(keyPoint, PersistentDataType.INTEGER, point.index());
                state.alive.add(mob.getUniqueId());
            }
        }
    }

    /** Keep a mob inside its spawn zone; only players inside that combat zone are targets. */
    private void leash(Zones.Point point, Location home, Mob mob, List<Player> players) {
        Location at = mob.getLocation();
        String[] here = at.getWorld().equals(home.getWorld()) ? plugin.hub().zones().at(at) : null;
        if (here == null || !point.zone().equals(here[0])) {
            mob.setTarget(null);
            mob.teleport(home);
            return;
        }
        if (mob.getTarget() instanceof Player current && !point.zone().equals(plugin.hub().zoneId(current))) {
            mob.setTarget(null);
        }
        if (mob.getTarget() == null) {
            Player best = null;
            double bestDistance = 10 * 10;
            for (Player player : players) {
                if (player.getGameMode() == GameMode.CREATIVE || !point.zone().equals(plugin.hub().zoneId(player))) {
                    continue;
                }
                double distance = player.getLocation().distanceSquared(at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = player;
                }
            }
            if (best != null) {
                mob.setTarget(best);
            }
        }
    }

    private static Type pick(Zones.Point point) {
        int total = 0;
        for (int weight : point.weights()) {
            total += weight;
        }
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (int i = 0; i < point.types().size(); i++) {
            roll -= point.weights()[i];
            if (roll < 0) {
                return point.types().get(i);
            }
        }
        return point.types().get(0);
    }

    /** The spawn point itself, or a clear spot up to two blocks away so pairs do not stack. */
    private static Location near(Location home) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 6; attempt++) {
            Location at = home.clone().add(random.nextInt(-2, 3), 0, random.nextInt(-2, 3));
            Block feet = at.getBlock();
            if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(0, 1, 0).isPassable() && feet.getRelative(0, -1, 0).getType().isSolid()) {
                at.setYaw(random.nextFloat() * 360f);
                return at;
            }
        }
        Location at = home.clone();
        at.setYaw(random.nextFloat() * 360f);
        return at;
    }
}
