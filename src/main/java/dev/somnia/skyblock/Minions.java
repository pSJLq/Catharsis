package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cobblestone minions on private islands. A minion stands on its own block and works the 5×5 floor
 * around it: it places cobblestone on empty cells and mines the cobblestone it placed into its own
 * storage. It never touches blocks it did not place. While its chunk is unloaded it keeps producing
 * at half rate (no place animation), up to its storage cap.
 */
final class Minions implements Listener, CommandExecutor {
    static final int MAX_TIER = 5;
    private static final int[] DELAY = {0, 14, 13, 12, 11, 10};
    private static final long[] UPGRADE = {0, 250, 1000, 3000, 8000};
    private static final int[] STORAGE_SLOTS = {21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43};

    static final class Minion {
        final String id;
        final UUID owner;
        final String world;
        final int x;
        final int y;
        final int z;
        int tier = 1;
        long stored;
        long generated;
        long nextAt;
        boolean paused;
        UUID stand;
        final Set<Long> placed = new HashSet<>();
        boolean placeNext = true;

        Minion(String id, UUID owner, String world, int x, int y, int z) {
            this.id = id;
            this.owner = owner;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        int slots() {
            return Math.min(STORAGE_SLOTS.length, tier * 3 - 2);
        }

        long capacity() {
            return slots() * 64L;
        }
    }

    private final SomniaSkyblockPlugin plugin;
    private final File file;
    private final Map<String, Minion> minions = new LinkedHashMap<>();
    private boolean dirty;

    Minions(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "minions.yml");
    }

    void start() {
        load();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) {
                save();
            }
        }, 1200L, 1200L);
    }

    int count(UUID owner) {
        int n = 0;
        for (Minion m : minions.values()) {
            if (m.owner.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ persistence
    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("minions");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                Minion m = new Minion(id, UUID.fromString(s.getString("owner")), s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                m.tier = Math.max(1, Math.min(MAX_TIER, s.getInt("tier", 1)));
                m.stored = s.getLong("stored");
                m.generated = s.getLong("generated");
                m.paused = s.getBoolean("paused");
                for (long key : s.getLongList("placed")) {
                    m.placed.add(key);
                }
                minions.put(id, m);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Skipping a broken minion entry " + id + ": " + exception.getMessage());
            }
        }
        plugin.getLogger().info("Minions: " + minions.size() + " loaded.");
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Minion m : minions.values()) {
            String p = "minions." + m.id + ".";
            yaml.set(p + "owner", m.owner.toString());
            yaml.set(p + "world", m.world);
            yaml.set(p + "x", m.x);
            yaml.set(p + "y", m.y);
            yaml.set(p + "z", m.z);
            yaml.set(p + "tier", m.tier);
            yaml.set(p + "stored", m.stored);
            yaml.set(p + "generated", m.generated);
            yaml.set(p + "paused", m.paused);
            yaml.set(p + "placed", new ArrayList<>(m.placed));
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save minions: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ placing and the stand
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(SbLoginEvent event) {
        Player player = event.getPlayer();
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (!profile.starterMinion) {
            profile.starterMinion = true;
            profile.minionsHeld++;
            plugin.profiles().save(profile);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Chat.separator(player, NamedTextColor.GREEN);
                player.sendMessage(Component.text("  You received a ", NamedTextColor.YELLOW).append(Component.text("Cobblestone Minion I", NamedTextColor.GREEN))
                        .append(Component.text("!", NamedTextColor.YELLOW)));
                player.sendMessage(Component.text("  Stand on your island, look at a block and type /minion place.", NamedTextColor.GRAY));
                Chat.separator(player, NamedTextColor.GREEN);
            }, 100L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("place")) {
            place(player);
            return true;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        player.sendMessage(Component.text("Minions placed: " + count(player.getUniqueId()) + "   in storage: " + profile.minionsHeld + " (tiers: " + storedTiers(profile) + ")", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/minion place — put one on the block you look at (your island). Right-click a minion to manage it.", NamedTextColor.GRAY));
        return true;
    }

    void place(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.minionsHeld <= 0) {
            player.sendMessage(Component.text("You have no minion in storage. Pick one up from its menu first.", NamedTextColor.RED));
            return;
        }
        World islands = plugin.islands().world();
        if (islands == null || !player.getWorld().equals(islands) || !plugin.islands().contains(profile, player.getLocation())) {
            player.sendMessage(Component.text("Minions can only be placed on your own island.", NamedTextColor.RED));
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || !target.getType().isSolid()) {
            player.sendMessage(Component.text("Look at a solid block within 6 blocks.", NamedTextColor.RED));
            return;
        }
        Block feet = target.getRelative(0, 1, 0);
        if (!feet.getType().isAir() || !feet.getRelative(0, 1, 0).getType().isAir()) {
            player.sendMessage(Component.text("The minion needs two free blocks above that block.", NamedTextColor.RED));
            return;
        }
        if (!plugin.islands().contains(profile, feet.getLocation())) {
            player.sendMessage(Component.text("That spot is outside your island.", NamedTextColor.RED));
            return;
        }
        for (Minion m : minions.values()) {
            if (m.world.equals(islands.getName()) && Math.abs(m.x - feet.getX()) <= 2 && Math.abs(m.z - feet.getZ()) <= 2 && Math.abs(m.y - feet.getY()) <= 2) {
                player.sendMessage(Component.text("Too close to another minion.", NamedTextColor.RED));
                return;
            }
        }
        int tier = takeTier(profile);
        profile.minionsHeld--;
        plugin.profiles().save(profile);
        Minion m = new Minion(UUID.randomUUID().toString().substring(0, 8), player.getUniqueId(), islands.getName(), feet.getX(), feet.getY(), feet.getZ());
        m.tier = tier;
        m.nextAt = System.currentTimeMillis() + DELAY[m.tier] * 1000L;
        minions.put(m.id, m);
        dirty = true;
        save();
        spawnStand(m, islands);
        player.playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 1f);
        player.sendMessage(Component.text("You placed a Cobblestone Minion " + ProgressMath.numeral(m.tier) + ". It works the 5×5 floor around it.", NamedTextColor.GREEN));
        plugin.quests().hit(player, "place_minion", 1);
    }

    /** Minions this player has placed (anywhere). */
    long countOn(Profile profile) {
        return minions.values().stream().filter(m -> m.owner.equals(profile.uuid)).count();
    }

    /** Highest stored tier (picked-up minions keep their tier); tier I when none is recorded. */
    static int takeTier(Profile profile) {
        while (profile.heldTiers.size() > profile.minionsHeld) {
            profile.heldTiers.remove(profile.heldTiers.size() - 1);
        }
        if (profile.heldTiers.isEmpty()) {
            return 1;
        }
        int best = 0;
        for (int i = 1; i < profile.heldTiers.size(); i++) {
            if (profile.heldTiers.get(i) > profile.heldTiers.get(best)) {
                best = i;
            }
        }
        return Math.max(1, Math.min(MAX_TIER, profile.heldTiers.remove(best)));
    }

    static String storedTiers(Profile profile) {
        List<String> out = new java.util.ArrayList<>();
        for (int t : profile.heldTiers) {
            out.add(ProgressMath.numeral(t));
        }
        for (int i = profile.heldTiers.size(); i < profile.minionsHeld; i++) {
            out.add("I");
        }
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    private ArmorStand stand(Minion m, World world) {
        if (m.stand != null) {
            Entity e = world.getEntity(m.stand);
            if (e instanceof ArmorStand a && a.isValid()) {
                return a;
            }
        }
        Location at = new Location(world, m.x + 0.5, m.y, m.z + 0.5);
        if (!at.isChunkLoaded()) {
            return null;
        }
        for (Entity e : at.getNearbyEntities(0.6, 1.2, 0.6)) {
            if (e instanceof ArmorStand a && e.getScoreboardTags().contains("sb-minion:" + m.id)) {
                m.stand = a.getUniqueId();
                return a;
            }
        }
        return spawnStand(m, world);
    }

    private ArmorStand spawnStand(Minion m, World world) {
        Location at = new Location(world, m.x + 0.5, m.y, m.z + 0.5, 180f, 0f);
        ArmorStand a = world.spawn(at, ArmorStand.class, s -> {
            s.setSmall(true);
            s.setArms(true);
            s.setBasePlate(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setPersistent(true);
            s.customName(name(m));
            s.setCustomNameVisible(true);
            s.addScoreboardTag("sb-minion");
            s.addScoreboardTag("sb-minion:" + m.id);
            s.getEquipment().setHelmet(new ItemStack(Material.COBBLESTONE));
            s.getEquipment().setChestplate(dyed(Material.LEATHER_CHESTPLATE));
            s.getEquipment().setLeggings(dyed(Material.LEATHER_LEGGINGS));
            s.getEquipment().setBoots(dyed(Material.LEATHER_BOOTS));
            s.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_PICKAXE));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    s.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
                    s.addEquipmentLock(slot, ArmorStand.LockType.ADDING);
                } catch (IllegalArgumentException ignored) {
                    // body / saddle slots do not apply to armor stands
                }
            }
        });
        m.stand = a.getUniqueId();
        return a;
    }

    private static ItemStack dyed(Material material) {
        ItemStack stack = new ItemStack(material);
        if (stack.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(0x7A7A7A));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Component name(Minion m) {
        return Component.text("Cobblestone Minion " + ProgressMath.numeral(m.tier), NamedTextColor.GREEN)
                .append(Component.text(m.stored >= m.capacity() ? "  FULL" : "", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains("sb-minion")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains("sb-minion")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        Entity e = event.getRightClicked();
        if (!e.getScoreboardTags().contains("sb-minion")) {
            return;
        }
        event.setCancelled(true);
        Minion m = byStand(e);
        if (m == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!m.owner.equals(player.getUniqueId()) && !plugin.auth().isDev(player)) {
            player.sendMessage(Component.text("This minion belongs to someone else.", NamedTextColor.RED));
            return;
        }
        open(player, m);
    }

    /** Keeps players from breaking a minion's own block out from under it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        for (Minion m : minions.values()) {
            if (m.x == b.getX() && m.z == b.getZ() && m.y - 1 == b.getY() && m.world.equals(b.getWorld().getName())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(Component.text("A minion stands on this block. Pick it up first.", NamedTextColor.RED));
                return;
            }
            if (m.world.equals(b.getWorld().getName())) {
                m.placed.remove(key(b.getX(), b.getY(), b.getZ()));
            }
        }
    }

    private Minion byStand(Entity e) {
        for (String tag : e.getScoreboardTags()) {
            if (tag.startsWith("sb-minion:")) {
                return minions.get(tag.substring("sb-minion:".length()));
            }
        }
        return null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ------------------------------------------------------------------ work loop
    private void tick() {
        long now = System.currentTimeMillis();
        for (Minion m : minions.values()) {
            if (m.paused || now < m.nextAt) {
                continue;
            }
            m.nextAt = now + DELAY[m.tier] * 1000L;
            World world = plugin.getServer().getWorld(m.world);
            if (world == null) {
                continue;
            }
            boolean loaded = world.isChunkLoaded(m.x >> 4, m.z >> 4);
            if (m.stored >= m.capacity()) {
                if (loaded) {
                    ArmorStand a = stand(m, world);
                    if (a != null) {
                        a.customName(name(m));
                    }
                }
                continue;
            }
            if (!loaded) {
                m.placeNext = !m.placeNext;
                if (m.placeNext) {
                    produce(m, 1);
                }
                continue;
            }
            ArmorStand a = stand(m, world);
            if (a == null) {
                continue;
            }
            work(m, world, a);
        }
    }

    private void work(Minion m, World world, ArmorStand a) {
        List<Block> empty = new ArrayList<>();
        List<Block> mine = new ArrayList<>();
        int fy = m.y - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Block b = world.getBlockAt(m.x + dx, fy, m.z + dz);
                long k = key(b.getX(), b.getY(), b.getZ());
                if (b.getType().isAir() && b.getRelative(0, 1, 0).getType().isAir()) {
                    empty.add(b);
                } else if (b.getType() == Material.COBBLESTONE && m.placed.contains(k)) {
                    mine.add(b);
                } else if (m.placed.contains(k)) {
                    m.placed.remove(k);
                }
            }
        }
        Block target;
        boolean placing;
        if (!mine.isEmpty() && (empty.isEmpty() || !m.placeNext)) {
            target = mine.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(mine.size()));
            placing = false;
        } else if (!empty.isEmpty()) {
            target = empty.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(empty.size()));
            placing = true;
        } else {
            a.customName(Component.text("This location isn't perfect! :(", NamedTextColor.RED));
            return;
        }
        m.placeNext = !m.placeNext;
        Location look = a.getLocation();
        look.setDirection(target.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(look.toVector()));
        a.setRotation(look.getYaw(), 0f);
        a.swingMainHand();
        if (placing) {
            target.setType(Material.COBBLESTONE, false);
            m.placed.add(key(target.getX(), target.getY(), target.getZ()));
            world.playSound(target.getLocation(), Sound.BLOCK_STONE_PLACE, 0.4f, 1f);
        } else {
            world.spawnParticle(Particle.BLOCK, target.getLocation().add(0.5, 0.6, 0.5), 12, 0.25, 0.25, 0.25, Material.COBBLESTONE.createBlockData());
            target.setType(Material.AIR, false);
            m.placed.remove(key(target.getX(), target.getY(), target.getZ()));
            world.playSound(target.getLocation(), Sound.BLOCK_STONE_BREAK, 0.4f, 1f);
            produce(m, 1);
        }
        a.customName(name(m));
        dirty = true;
    }

    private void produce(Minion m, long amount) {
        long room = m.capacity() - m.stored;
        long add = Math.min(room, amount);
        if (add <= 0) {
            return;
        }
        m.stored += add;
        m.generated += add;
        dirty = true;
        Profile profile = plugin.profiles().get(m.owner);
        Collects.Entry cobble = Collects.of(Material.COBBLESTONE);
        long before = Collects.amount(profile, cobble);
        profile.collections.merge("cobblestone", add, Long::sum);
        plugin.skillChain().queueCollection(profile, cobble, add);
        Player online = plugin.getServer().getPlayer(m.owner);
        if (online != null) {
            Collects.announce(online, profile, cobble, before, Collects.amount(profile, cobble));
        }
    }

    // ------------------------------------------------------------------ GUI
    void open(Player player, Minion m) {
        SbMenu.View view = new SbMenu.View(54, "Cobblestone Minion " + ProgressMath.numeral(m.tier));
        ItemStack glass = SbMenu.icon(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            view.inventory.setItem(i, glass);
        }
        view.set(4, SbMenu.icon(Material.COBBLESTONE, SbMenu.plain("Cobblestone Minion " + ProgressMath.numeral(m.tier), NamedTextColor.GREEN), List.of(
                SbMenu.plain("Place this minion and it will start", NamedTextColor.GRAY),
                SbMenu.plain("generating and mining cobblestone!", NamedTextColor.GRAY),
                SbMenu.plain("Requires an open 5×5 floor around it.", NamedTextColor.GRAY),
                Component.empty(),
                SbMenu.plain("Time Between Actions: ", NamedTextColor.GRAY).append(SbMenu.plain(DELAY[m.tier] + "s", NamedTextColor.GREEN)),
                SbMenu.plain("Max Storage: ", NamedTextColor.GRAY).append(SbMenu.plain(String.valueOf(m.capacity()), NamedTextColor.YELLOW)),
                SbMenu.plain("Resources Generated: ", NamedTextColor.GRAY).append(SbMenu.plain(String.format(java.util.Locale.ROOT, "%,d", m.generated), NamedTextColor.AQUA)))), null);
        view.set(3, SbMenu.icon(Material.REDSTONE_TORCH, SbMenu.plain("Production: ", NamedTextColor.GREEN)
                .append(m.paused ? SbMenu.plain("PAUSED", NamedTextColor.RED) : SbMenu.plain("RUNNING", NamedTextColor.GREEN)), List.of(
                SbMenu.plain("Pause or resume this minion.", NamedTextColor.GRAY), Component.empty(), SbMenu.plain("Click to toggle!", NamedTextColor.YELLOW))), c -> {
                    m.paused = !m.paused;
                    dirty = true;
                    open(player, m);
                });
        view.set(5, SbMenu.icon(Material.GOLD_INGOT, SbMenu.plain("Ideal Layout", NamedTextColor.GOLD), List.of(
                SbMenu.plain("Leave the 24 floor cells around the", NamedTextColor.GRAY),
                SbMenu.plain("minion empty, with air above them.", NamedTextColor.GRAY),
                SbMenu.plain("It only mines cobblestone it placed.", NamedTextColor.GRAY))), null);
        view.set(10, SbMenu.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, SbMenu.plain("Minion Skin Slot", NamedTextColor.AQUA), List.of(
                SbMenu.plain("Skins change how a minion looks.", NamedTextColor.GRAY), SbMenu.plain("Skins arrive with the Item contract.", NamedTextColor.DARK_GRAY))), null);
        view.set(19, SbMenu.icon(Material.ORANGE_STAINED_GLASS_PANE, SbMenu.plain("Fuel", NamedTextColor.GOLD), List.of(
                SbMenu.plain("Fuel speeds up a minion.", NamedTextColor.GRAY), SbMenu.plain("This minion runs without fuel.", NamedTextColor.DARK_GRAY))), null);
        long npc = Market.PRODUCTS.containsKey("cobblestone") ? Market.PRODUCTS.get("cobblestone").npc() : 1;
        view.set(28, SbMenu.icon(Material.LIME_STAINED_GLASS_PANE, SbMenu.plain("Sell to Merchant", NamedTextColor.GREEN), List.of(
                SbMenu.plain("Sell everything in storage for", NamedTextColor.GRAY),
                SbMenu.plain(npc + " coin each.", NamedTextColor.GOLD),
                Component.empty(),
                SbMenu.plain("Stored: ", NamedTextColor.GRAY).append(SbMenu.plain(String.valueOf(m.stored), NamedTextColor.YELLOW)),
                SbMenu.plain("Click to sell!", NamedTextColor.YELLOW))), c -> {
                    long coins = m.stored * npc;
                    if (coins <= 0) {
                        return;
                    }
                    m.stored = 0;
                    dirty = true;
                    plugin.stats().addCoins(player, coins);
                    plugin.profiles().save(plugin.profiles().get(player.getUniqueId()));
                    player.sendMessage(Component.text("Sold minion storage for " + coins + " coins.", NamedTextColor.GOLD));
                    open(player, m);
                });
        view.set(37, SbMenu.icon(Material.YELLOW_STAINED_GLASS_PANE, SbMenu.plain("Upgrade Slot", NamedTextColor.YELLOW), List.of(
                SbMenu.plain("Tier upgrades: more storage,", NamedTextColor.GRAY), SbMenu.plain("faster actions. See the diamond.", NamedTextColor.GRAY))), null);
        long left = m.stored;
        for (int i = 0; i < STORAGE_SLOTS.length; i++) {
            if (i >= m.slots()) {
                int need = (i + 2) / 3 + 1;
                view.set(STORAGE_SLOTS[i], SbMenu.icon(Material.WHITE_STAINED_GLASS_PANE, SbMenu.plain("Storage unlocked at tier " + ProgressMath.numeral(Math.min(MAX_TIER, need)), NamedTextColor.YELLOW), List.of()), null);
                continue;
            }
            int amount = (int) Math.min(64, left);
            left -= amount;
            if (amount <= 0) {
                view.set(STORAGE_SLOTS[i], null, null);
                continue;
            }
            ItemStack stack = new ItemStack(Material.COBBLESTONE, amount);
            view.set(STORAGE_SLOTS[i], stack, c -> collect(player, m, amount));
        }
        view.set(48, SbMenu.icon(Material.CHEST, SbMenu.plain("Collect All", NamedTextColor.GREEN), List.of(
                SbMenu.plain("Moves everything into your bag.", NamedTextColor.GRAY), Component.empty(), SbMenu.plain("Click to collect!", NamedTextColor.YELLOW))),
                c -> collect(player, m, m.stored));
        if (m.tier < MAX_TIER) {
            long cost = UPGRADE[m.tier];
            view.set(50, SbMenu.icon(Material.DIAMOND, SbMenu.plain("Next Tier", NamedTextColor.GREEN), List.of(
                    SbMenu.plain("Cobblestone Minion " + ProgressMath.numeral(m.tier + 1), NamedTextColor.GRAY),
                    SbMenu.plain("Time Between Actions: ", NamedTextColor.GRAY).append(SbMenu.plain(DELAY[m.tier] + "s ➜ " + DELAY[m.tier + 1] + "s", NamedTextColor.GREEN)),
                    SbMenu.plain("Storage: ", NamedTextColor.GRAY).append(SbMenu.plain(m.capacity() + " ➜ " + Math.min(STORAGE_SLOTS.length, (m.tier + 1) * 3 - 2) * 64, NamedTextColor.YELLOW)),
                    Component.empty(),
                    SbMenu.plain("Cost: ", NamedTextColor.GRAY).append(SbMenu.plain(String.format(java.util.Locale.ROOT, "%,d", cost) + " coins", NamedTextColor.GOLD)),
                    SbMenu.plain("Click to upgrade!", NamedTextColor.YELLOW))), c -> {
                        if (plugin.stats().spendCoins(player, cost)) {
                            m.tier++;
                            dirty = true;
                            save();
                            World w = plugin.getServer().getWorld(m.world);
                            if (w != null) {
                                ArmorStand a = stand(m, w);
                                if (a != null) {
                                    a.customName(name(m));
                                }
                            }
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
                            player.sendMessage(Component.text("Upgraded to Cobblestone Minion " + ProgressMath.numeral(m.tier) + "!", NamedTextColor.GREEN));
                            open(player, m);
                        }
                    });
        } else {
            view.set(50, SbMenu.icon(Material.DIAMOND, SbMenu.plain("Max Tier", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), List.of()), null);
        }
        view.set(49, SbMenu.icon(Material.BARRIER, SbMenu.plain("Close", NamedTextColor.RED), List.of()), c -> player.closeInventory());
        view.set(53, SbMenu.icon(Material.BEDROCK, SbMenu.plain("Pickup Minion", NamedTextColor.GREEN), List.of(
                SbMenu.plain("Puts the minion back in storage", NamedTextColor.GRAY), SbMenu.plain("and collects its items.", NamedTextColor.GRAY),
                Component.empty(), SbMenu.plain("Click to pick up!", NamedTextColor.YELLOW))), c -> pickup(player, m));
        player.openInventory(view.inventory);
    }

    private void collect(Player player, Minion m, long amount) {
        long give = Math.min(amount, m.stored);
        long moved = 0;
        while (give > 0) {
            int n = (int) Math.min(64, give);
            Map<Integer, ItemStack> rest = player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, n));
            int back = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
            moved += n - back;
            give -= n;
            if (back > 0) {
                player.sendMessage(Component.text("Your bag is full.", NamedTextColor.RED));
                break;
            }
        }
        m.stored -= moved;
        dirty = true;
        if (moved > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
        }
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof SbMenu.View) {
            open(player, m);
        }
    }

    private void pickup(Player player, Minion m) {
        collect(player, m, m.stored);
        if (m.stored > 0) {
            player.sendMessage(Component.text("Empty the minion's storage first.", NamedTextColor.RED));
            return;
        }
        World w = plugin.getServer().getWorld(m.world);
        if (w != null) {
            ArmorStand a = stand(m, w);
            if (a != null) {
                a.remove();
            }
            for (long k : m.placed) {
                int y = (int) (k & 0xFFF);
                int z = (int) ((k >> 12) & 0x3FFFFFF);
                int x = (int) ((k >> 38) & 0x3FFFFFF);
                x = (x << 6) >> 6;
                z = (z << 6) >> 6;
                Block b = w.getBlockAt(x, y, z);
                if (b.getType() == Material.COBBLESTONE) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        minions.remove(m.id);
        Profile profile = plugin.profiles().get(m.owner);
        while (profile.heldTiers.size() > profile.minionsHeld) {
            profile.heldTiers.remove(profile.heldTiers.size() - 1);
        }
        while (profile.heldTiers.size() < profile.minionsHeld) {
            profile.heldTiers.add(1);
        }
        profile.heldTiers.add(m.tier);
        profile.minionsHeld++;
        plugin.profiles().save(profile);
        dirty = true;
        save();
        player.closeInventory();
        player.sendMessage(Component.text("You picked up your Cobblestone Minion " + ProgressMath.numeral(m.tier) + ". It keeps its tier; /minion place puts it back.", NamedTextColor.GREEN));
    }
}
