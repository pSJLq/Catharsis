package dev.somnia.skyblock;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login with a limbo. Until a player logs in they sit frozen and alone in an empty void world: no movement,
 * chat, commands (except the auth ones), interaction or damage. Their purse key exists only as an Argon2id +
 * AES-256-GCM blob in the profile; /register or /login decrypts it into RAM, quit wipes it (after a short
 * drain so the quit save can finish). A remembered session (same name + IP) keeps the decrypted key in RAM only,
 * so a restart always asks for the password again.
 * TODO phase 2: recovery by a signature from the player's main wallet (/link, /recover).
 */
final class Auth implements Listener, CommandExecutor, TabCompleter {
    static final String LIMBO_WORLD = "sb_limbo";
    private static final Set<String> ALLOWED = Set.of("register", "reg", "login", "l", "changepassword");
    private static final Set<String> DEV_CMDS = Set.of("dev", "devmode", "user", "usermode");

    private final SomniaSkyblockPlugin plugin;
    private World limbo;
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, byte[]> keys = new ConcurrentHashMap<>();
    private final Map<UUID, Long> drainUntil = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Wait> waiting = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingMigration = ConcurrentHashMap.newKeySet();
    private final File migrationFile;
    private byte[] migrationKey;
    private final File namesFile;
    private final YamlConfiguration names;

    private record Session(UUID uuid, byte[] key, long expires) {
    }

    private static final class Wait {
        BossBar bar;
        long deadline;
        Location back;
        boolean register;
    }

    Auth(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        File dir = new File(plugin.getDataFolder(), "auth");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create the auth folder");
        }
        this.migrationFile = new File(dir, "migration.key");
        this.namesFile = new File(dir, "names.yml");
        this.names = YamlConfiguration.loadConfiguration(namesFile);
        this.ipsFile = new File(dir, "ips.yml");
        this.ips = YamlConfiguration.loadConfiguration(ipsFile);
    }

    // ------------------------------------------------------------------ accounts per IP
    private final File ipsFile;
    private final YamlConfiguration ips;

    private int maxAccountsPerIp() {
        return Math.max(0, plugin.getConfig().getInt("auth.max-accounts-per-ip", 2));
    }

    private static String ipKey(String ip) {
        return ip.replace('.', '_').replace(':', '-');
    }

    /** Names that registered a new purse from this IP (recorded since the limit was added). */
    private java.util.List<String> registeredFrom(String ip) {
        return new java.util.ArrayList<>(ips.getStringList(ipKey(ip)));
    }

    private void recordRegistration(String ip, String name) {
        java.util.List<String> list = registeredFrom(ip);
        String n = name.toLowerCase(Locale.ROOT);
        if (!list.contains(n)) {
            list.add(n);
            ips.set(ipKey(ip), list);
            try {
                ips.save(ipsFile);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Could not save auth/ips.yml: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ config
    private int sessionHours() {
        return Math.max(0, plugin.getConfig().getInt("auth.session-hours", 12));
    }

    private int timeoutSeconds() {
        return Math.max(15, plugin.getConfig().getInt("auth.timeout-seconds", 60));
    }

    private int maxAttempts() {
        return Math.max(1, plugin.getConfig().getInt("auth.max-attempts", 5));
    }

    private int lockoutMinutes() {
        return Math.max(1, plugin.getConfig().getInt("auth.lockout-minutes", 15));
    }

    private int minLength() {
        return Math.max(8, plugin.getConfig().getInt("auth.min-password-length", 8));
    }

    private KeyVault.Params params() {
        return new KeyVault.Params(plugin.getConfig().getInt("auth.argon2.memory-kb", 65536),
                plugin.getConfig().getInt("auth.argon2.iterations", 3), plugin.getConfig().getInt("auth.argon2.lanes", 1));
    }

    // ------------------------------------------------------------------ key access
    boolean authed(Player player) {
        return player != null && authed.contains(player.getUniqueId());
    }

    boolean authed(UUID id) {
        return authed.contains(id);
    }

    /** The decrypted purse key while the player is logged in (or for a short drain after quit), else null: no onchain call. */
    byte[] key(Profile profile) {
        UUID id = profile.uuid;
        if (!authed.contains(id)) {
            Long until = drainUntil.get(id);
            if (until == null || until < System.currentTimeMillis()) {
                return null;
            }
        }
        return keys.get(id);
    }

    // ------------------------------------------------------------------ migration of plaintext keys
    /** Called by ProfileStore when a profile still has a plaintext wallet-key: seals it under the server migration key. */
    String migrate(Profile profile, String plainKey) {
        try {
            byte[] mk = migrationKey(true);
            byte[] secret = org.web3j.utils.Numeric.hexStringToByteArray(plainKey);
            try {
                String blob = KeyVault.sealRaw(secret, mk, KeyVault.aad(profile.uuid, profile.wallet));
                pendingMigration.add(profile.uuid);
                return blob;
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not seal the purse key of " + profile.uuid + ": " + e.getClass().getSimpleName());
        }
    }

    void notePending(UUID id) {
        pendingMigration.add(id);
    }

    private byte[] migrationKey(boolean create) throws IOException {
        if (migrationKey != null) {
            return migrationKey;
        }
        if (migrationFile.isFile()) {
            migrationKey = java.util.Base64.getDecoder().decode(Files.readString(migrationFile.toPath()).trim());
            return migrationKey;
        }
        if (!create) {
            return null;
        }
        migrationKey = KeyVault.random(32);
        Files.writeString(migrationFile.toPath(), java.util.Base64.getEncoder().encodeToString(migrationKey));
        return migrationKey;
    }

    /** Loads every profile once (plaintext keys get sealed and saved) and drops the plaintext deployer copy. */
    void migrateAll() {
        File dir = new File(plugin.getDataFolder(), "players");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        int sealed = 0;
        if (files != null) {
            for (File file : files) {
                try {
                    UUID id = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
                    Profile profile = plugin.profiles().get(id);
                    if (profile.walletPending != null) {
                        pendingMigration.add(id);
                    }
                    if (profile.migratedNow) {
                        profile.migratedNow = false;
                        plugin.profiles().save(profile);
                        sealed++;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        File secrets = new File(plugin.getDataFolder(), "secrets.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(secrets);
        String dk = yaml.getString("deployer-key", "");
        if (dk != null && !dk.isBlank() && yaml.getBoolean("deployer-claimed")) {
            yaml.set("deployer-key", null);
            try {
                yaml.save(secrets);
                plugin.getLogger().info("Removed the plaintext deployer key from secrets.yml (that purse is a player's purse and is now sealed in the profile).");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not rewrite secrets.yml: " + e.getMessage());
            }
        }
        if (sealed > 0) {
            plugin.getLogger().info("Sealed " + sealed + " plaintext purse key(s); those players set a password with /register on next join.");
        }
        plugin.getLogger().info("Auth: " + pendingMigration.size() + " profile(s) waiting for /register to finish key migration.");
        dropMigrationKeyIfDone();
    }

    private void dropMigrationKeyIfDone() {
        if (!pendingMigration.isEmpty() || !migrationFile.isFile()) {
            return;
        }
        try {
            long len = Math.max(64, migrationFile.length());
            Files.write(migrationFile.toPath(), new byte[(int) len]);
        } catch (IOException ignored) {
        }
        if (migrationFile.delete()) {
            plugin.getLogger().info("Key migration finished: migration.key deleted.");
        }
        if (migrationKey != null) {
            Arrays.fill(migrationKey, (byte) 0);
            migrationKey = null;
        }
    }

    // ------------------------------------------------------------------ limbo world and ticker
    void start() {
        limbo = plugin.getServer().getWorld(LIMBO_WORLD);
        if (limbo == null) {
            WorldCreator creator = new WorldCreator(LIMBO_WORLD);
            creator.generator(new VoidGenerator());
            creator.generateStructures(false);
            limbo = creator.createWorld();
        }
        if (limbo != null) {
            limbo.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            limbo.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            limbo.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            limbo.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            limbo.setTime(18000);
            limbo.setAutoSave(true);
            // a small invisible barrier cell: players stand still on their own, no move corrections needed
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = 99; y <= 103; y++) {
                        boolean shell = y == 99 || y == 103 || Math.abs(x) == 2 || Math.abs(z) == 2;
                        Material want = shell ? Material.BARRIER : Material.AIR;
                        if (limbo.getBlockAt(x, y, z).getType() != want) {
                            limbo.getBlockAt(x, y, z).setType(want, false);
                        }
                    }
                }
            }
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        deopDevAccounts();
    }

    private Location limboSpot() {
        return new Location(limbo, 0.5, 100, 0.5, 0f, 0f);
    }

    boolean inLimbo(World world) {
        return world != null && limbo != null && world.equals(limbo);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Wait> e : waiting.entrySet()) {
            Player player = plugin.getServer().getPlayer(e.getKey());
            Wait w = e.getValue();
            if (player == null) {
                continue;
            }
            long left = Math.max(0, (w.deadline - now + 999) / 1000);
            if (left <= 0) {
                player.kick(Component.text("You took too long to log in.", NamedTextColor.RED));
                continue;
            }
            w.bar.name(Component.text(w.register ? "Register: " : "Log in: ", NamedTextColor.YELLOW)
                    .append(Component.text(w.register ? "/register <password> <password>" : "/login <password>", NamedTextColor.GREEN))
                    .append(Component.text("  " + left + "s", NamedTextColor.RED)));
            w.bar.progress(Math.max(0f, Math.min(1f, left / (float) timeoutSeconds())));
            if (left % 15 == 0) {
                prompt(player, w.register);
            }
        }
        drainUntil.entrySet().removeIf(e -> {
            if (authed.contains(e.getKey())) {
                return true;
            }
            if (e.getValue() < now) {
                wipe(e.getKey());
                return true;
            }
            return false;
        });
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().expires() < now) {
                Arrays.fill(e.getValue().key(), (byte) 0);
                return true;
            }
            return false;
        });
        lockedUntil.entrySet().removeIf(e -> e.getValue() < now);
        devTick();
    }

    private void wipe(UUID id) {
        byte[] k = keys.remove(id);
        if (k != null) {
            Arrays.fill(k, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ join and quit
    private static String sessionKey(String name, String ip) {
        return name.toLowerCase(Locale.ROOT) + "|" + ip;
    }

    private static String ip(Player player) {
        return RealIp.of(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        long now = System.currentTimeMillis();
        String n = event.getName().toLowerCase(Locale.ROOT);
        String ip = RealIp.of(event);
        Long until = Math.max(lockedUntil.getOrDefault("n:" + n, 0L), lockedUntil.getOrDefault("i:" + ip, 0L));
        if (until > now) {
            long min = Math.max(1, (until - now + 59_999) / 60_000);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Too many wrong passwords. Try again in " + min + " min.", NamedTextColor.RED));
            return;
        }
        String known = names.getString(n);
        if (known != null && !known.equals(event.getName())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("This name is registered as " + known + ". Join with exactly that name.", NamedTextColor.RED));
        }
    }

    private final Map<UUID, Location> spawnBack = new ConcurrentHashMap<>();

    /** Spawn straight into the limbo cell (no join-time teleport, which makes clients send two moves in one tick). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent event) {
        if (limbo == null) {
            return;
        }
        UUID id = event.getConnection().getProfile().getId();
        if (id == null) {
            return;
        }
        Location l = event.getSpawnLocation();
        if (l != null && l.getWorld() != null && !inLimbo(l.getWorld())) {
            spawnBack.put(id, l.clone());
        } else {
            spawnBack.remove(id);
        }
        event.setSpawnLocation(limboSpot());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        authed.remove(id);
        Location back = spawnBack.remove(id);
        if (back == null) {
            back = player.getLocation();
        }
        if (inLimbo(back.getWorld()) || back.getWorld() == null) {
            back = profile.lastLocation(plugin.getServer());
        }
        Session s = sessions.get(sessionKey(player.getName(), ip(player)));
        if (s != null && s.uuid().equals(id) && s.expires() > System.currentTimeMillis() && profile.walletEnc != null) {
            keys.put(id, s.key().clone());
            authed.add(id);
            drainUntil.remove(id);
            Location to = back;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Welcome back! Your session was remembered.", NamedTextColor.GREEN));
                    plugin.getServer().getPluginManager().callEvent(new SbLoginEvent(player, to, false));
                }
            });
            return;
        }
        enterLimbo(player, back, profile.walletEnc == null);
    }

    private void enterLimbo(Player player, Location back, boolean register) {
        Wait w = new Wait();
        w.back = back;
        w.register = register;
        w.deadline = System.currentTimeMillis() + timeoutSeconds() * 1000L;
        w.bar = BossBar.bossBar(Component.text("Log in", NamedTextColor.YELLOW), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        waiting.put(player.getUniqueId(), w);
        if (limbo != null && (!inLimbo(player.getWorld()) || player.getLocation().distanceSquared(limboSpot()) > 2.25)) {
            player.teleport(limboSpot());
        }
        player.setFallDistance(0);
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }
        player.showBossBar(w.bar);
        player.showTitle(Title.title(
                Component.text("SOMNIA SKYBLOCK", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                Component.text(register ? "/register <password> <password>" : "/login <password>", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(timeoutSeconds()), Duration.ofMillis(300))));
        prompt(player, register);
        player.updateCommands();
    }

    private void prompt(Player player, boolean register) {
        Chat.separator(player, NamedTextColor.GOLD);
        player.sendMessage(Component.text("  SOMNIA SKYBLOCK", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        if (register) {
            player.sendMessage(Component.text("  Welcome! Pick a password for your account:", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  /register <password> <password>", NamedTextColor.GREEN));
            player.sendMessage(Component.text("  At least " + minLength() + " characters. It locks your purse key; nobody, not even an admin, can reset it.", NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("  Welcome back! Log in to unlock your purse:", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  /login <password>", NamedTextColor.GREEN));
        }
        Chat.separator(player, NamedTextColor.GOLD);
    }

    private void finish(Player player, boolean registered) {
        Wait w = waiting.remove(player.getUniqueId());
        if (w != null) {
            player.hideBossBar(w.bar);
        }
        player.clearTitle();
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player) && authed(other)) {
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            }
        }
        player.updateCommands();
        Location back = w == null ? null : w.back;
        if (back != null && back.getWorld() != null && !inLimbo(back.getWorld())) {
            moving.add(player.getUniqueId());
            try {
                player.teleport(back);
            } finally {
                moving.remove(player.getUniqueId());
            }
            player.setFallDistance(0);
        }
        plugin.getServer().getPluginManager().callEvent(new SbLoginEvent(player, back, registered));
    }

    private final Set<UUID> moving = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Wait w = waiting.remove(id);
        if (w != null) {
            player.hideBossBar(w.bar);
        }
        busy.remove(id);
        devQuit(player);
        if (!authed.remove(id)) {
            return;
        }
        Profile profile = plugin.profiles().get(id);
        if (!inLimbo(player.getWorld())) {
            profile.setLastLocation(player.getLocation());
            plugin.profiles().save(profile);
        }
        byte[] k = keys.get(id);
        if (k != null && sessionHours() > 0) {
            Session old = sessions.put(sessionKey(player.getName(), ip(player)),
                    new Session(id, k.clone(), System.currentTimeMillis() + sessionHours() * 3_600_000L));
            if (old != null) {
                Arrays.fill(old.key(), (byte) 0);
            }
        }
        // The quit save (inventory on chain) still needs the key for a moment; then it is wiped from RAM.
        drainUntil.put(id, System.currentTimeMillis() + 60_000L);
    }

    void logoutAll() {
        for (UUID id : keys.keySet()) {
            wipe(id);
        }
        for (Session s : sessions.values()) {
            Arrays.fill(s.key(), (byte) 0);
        }
        sessions.clear();
        authed.clear();
    }

    // ------------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "register" -> register(player, args);
            case "login" -> login(player, args);
            case "changepassword" -> changePassword(player, args);
            case "dev" -> {
                // Dev accounts only (after /login): /dev cookie opens the Booster Cookie test tools; also /dev while already in dev mode.
                boolean tools = (args.length > 0 && args[0].equalsIgnoreCase("cookie")) || (args.length == 0 && isDev(player));
                if (tools && isDevAccount(player) && authed.contains(player.getUniqueId())) {
                    plugin.cookies().openDev(player);
                } else {
                    devCommand(player, true);
                }
            }
            case "user" -> devCommand(player, false);
            default -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void err(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private void register(Player player, String[] args) {
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        if (authed.contains(id)) {
            err(player, "You are already logged in. /changepassword <old> <new> changes it.");
            return;
        }
        if (profile.walletEnc != null) {
            err(player, "You already have an account. Use /login <password>.");
            return;
        }
        if (args.length != 2) {
            err(player, "Usage: /register <password> <password>");
            return;
        }
        if (!args[0].equals(args[1])) {
            err(player, "The two passwords are not the same.");
            return;
        }
        String problem = weak(player, args[0]);
        if (problem != null) {
            err(player, problem);
            return;
        }
        String fromIp = ip(player);
        if (profile.walletPending == null && maxAccountsPerIp() > 0) {
            java.util.List<String> already = registeredFrom(fromIp);
            if (!already.contains(player.getName().toLowerCase(Locale.ROOT)) && already.size() >= maxAccountsPerIp()) {
                plugin.getLogger().warning("Register refused for " + player.getName() + ": " + already.size() + " accounts already registered from " + fromIp + ".");
                err(player, "Too many accounts were created from your connection. Log in to one of them instead.");
                return;
            }
        }
        if (!busy.add(id)) {
            return;
        }
        String password = args[0];
        player.sendMessage(Component.text("Locking your purse with your password...", NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] secret = null;
            String address;
            String blob;
            boolean fresh = false;
            try {
                if (profile.walletPending != null) {
                    secret = KeyVault.openRaw(profile.walletPending, migrationKey(false), KeyVault.aad(id, profile.wallet));
                    address = Credentials.create(ECKeyPair.create(secret)).getAddress();
                    if (!address.equalsIgnoreCase(profile.wallet)) {
                        throw new GeneralSecurityException("pending key does not match the purse address");
                    }
                } else {
                    secret = plugin.wallets().newPurseKey();
                    address = Credentials.create(ECKeyPair.create(secret)).getAddress();
                    fresh = true;
                    if (profile.wallet != null && !profile.wallet.isBlank()) {
                        plugin.getLogger().warning("Profile " + id + " had purse " + profile.wallet + " without a key; a new purse is made.");
                    }
                }
                blob = KeyVault.seal(secret, password, params(), KeyVault.aad(id, address));
            } catch (Exception e) {
                if (secret != null) {
                    Arrays.fill(secret, (byte) 0);
                }
                plugin.getLogger().warning("Register failed for " + player.getName() + ": " + e.getClass().getSimpleName());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    err(player, "Could not create your account. Ask an admin.");
                });
                return;
            }
            byte[] key = secret;
            boolean created = fresh;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                busy.remove(id);
                if (!player.isOnline()) {
                    Arrays.fill(key, (byte) 0);
                    return;
                }
                profile.wallet = address;
                profile.walletEnc = blob;
                profile.walletPending = null;
                plugin.profiles().save(profile);
                if (pendingMigration.remove(id)) {
                    plugin.getLogger().info(player.getName() + " registered; the purse key is now sealed with their password.");
                } else {
                    plugin.getLogger().info(player.getName() + " registered" + (created ? " with a new purse " + address : "") + ".");
                }
                dropMigrationKeyIfDone();
                names.set(player.getName().toLowerCase(Locale.ROOT), player.getName());
                saveNames();
                if (created) {
                    recordRegistration(fromIp, player.getName());
                }
                accept(player, key);
                Chat.separator(player, NamedTextColor.GREEN);
                player.sendMessage(Component.text("  Account created!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
                player.sendMessage(Component.text("  Your purse " + address + " is locked with your password.", NamedTextColor.GRAY));
                player.sendMessage(Component.text("  Don't forget it: there is no reset without it.", NamedTextColor.RED));
                Chat.separator(player, NamedTextColor.GREEN);
                finish(player, true);
            });
        });
    }

    private String weak(Player player, String password) {
        if (password.length() < minLength()) {
            return "Your password needs at least " + minLength() + " characters.";
        }
        if (password.length() > 64) {
            return "Your password can have at most 64 characters.";
        }
        if (password.equalsIgnoreCase(player.getName())) {
            return "Don't use your name as the password.";
        }
        return null;
    }

    private void accept(Player player, byte[] key) {
        UUID id = player.getUniqueId();
        byte[] old = keys.put(id, key);
        if (old != null && old != key) {
            Arrays.fill(old, (byte) 0);
        }
        authed.add(id);
        drainUntil.remove(id);
        fails.remove("n:" + player.getName().toLowerCase(Locale.ROOT));
        fails.remove("i:" + ip(player));
        if (sessionHours() > 0) {
            Session prev = sessions.put(sessionKey(player.getName(), ip(player)),
                    new Session(id, key.clone(), System.currentTimeMillis() + sessionHours() * 3_600_000L));
            if (prev != null) {
                Arrays.fill(prev.key(), (byte) 0);
            }
        }
    }

    private void login(Player player, String[] args) {
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        if (authed.contains(id)) {
            err(player, "You are already logged in.");
            return;
        }
        if (profile.walletEnc == null) {
            err(player, "You don't have an account yet. Use /register <password> <password>.");
            return;
        }
        if (args.length != 1) {
            err(player, "Usage: /login <password>");
            return;
        }
        if (!busy.add(id)) {
            return;
        }
        String password = args[0];
        String blob = profile.walletEnc;
        String wallet = profile.wallet;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] secret = null;
            boolean ok;
            try {
                secret = KeyVault.open(blob, password, KeyVault.aad(id, wallet));
                ok = Credentials.create(ECKeyPair.create(secret)).getAddress().equalsIgnoreCase(wallet);
            } catch (GeneralSecurityException | RuntimeException e) {
                ok = false;
            }
            boolean good = ok;
            byte[] key = secret;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                busy.remove(id);
                if (!good) {
                    if (key != null) {
                        Arrays.fill(key, (byte) 0);
                    }
                    if (player.isOnline()) {
                        wrong(player);
                    }
                    return;
                }
                if (!player.isOnline()) {
                    Arrays.fill(key, (byte) 0);
                    return;
                }
                names.set(player.getName().toLowerCase(Locale.ROOT), player.getName());
                saveNames();
                accept(player, key);
                player.sendMessage(Component.text("Logged in. Welcome back!", NamedTextColor.GREEN));
                plugin.getLogger().info(player.getName() + " logged in.");
                finish(player, false);
            });
        });
    }

    private void wrong(Player player) {
        String n = "n:" + player.getName().toLowerCase(Locale.ROOT);
        String i = "i:" + ip(player);
        int count = Math.max(fails.computeIfAbsent(n, k -> new int[1])[0] += 1, fails.computeIfAbsent(i, k -> new int[1])[0] += 1);
        plugin.getLogger().info("Wrong password for " + player.getName() + " (" + count + "/" + maxAttempts() + ").");
        if (count >= maxAttempts()) {
            long until = System.currentTimeMillis() + lockoutMinutes() * 60_000L;
            lockedUntil.put(n, until);
            lockedUntil.put(i, until);
            fails.remove(n);
            fails.remove(i);
            plugin.getLogger().info("Locked " + player.getName() + " for " + lockoutMinutes() + " min after " + count + " wrong passwords.");
            player.kick(Component.text("Too many wrong passwords. Try again in " + lockoutMinutes() + " min.", NamedTextColor.RED));
            return;
        }
        err(player, "Wrong password. " + (maxAttempts() - count) + " attempt(s) left.");
    }

    /**
     * Re-checks the password of a logged-in player (same attempt counter and lockout as /login) and hands the
     * decrypted purse key to {@code ok} on the main thread. The caller must wipe the array. Nothing is logged.
     */
    void verifyPassword(Player player, String password, java.util.function.Consumer<byte[]> ok) {
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        if (!authed.contains(id) || profile.walletEnc == null || profile.wallet == null || profile.wallet.isBlank()) {
            err(player, "Log in first.");
            return;
        }
        long until = Math.max(lockedUntil.getOrDefault("n:" + player.getName().toLowerCase(Locale.ROOT), 0L),
                lockedUntil.getOrDefault("i:" + ip(player), 0L));
        if (until > System.currentTimeMillis()) {
            err(player, "Too many wrong passwords. Try again later.");
            return;
        }
        if (!busy.add(id)) {
            return;
        }
        String blob = profile.walletEnc;
        String wallet = profile.wallet;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] secret = null;
            boolean good;
            try {
                secret = KeyVault.open(blob, password, KeyVault.aad(id, wallet));
                good = Credentials.create(ECKeyPair.create(secret)).getAddress().equalsIgnoreCase(wallet);
            } catch (GeneralSecurityException | RuntimeException e) {
                good = false;
            }
            boolean pass = good;
            byte[] key = secret;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                busy.remove(id);
                if (!pass || !player.isOnline()) {
                    if (key != null) {
                        Arrays.fill(key, (byte) 0);
                    }
                    if (!pass && player.isOnline()) {
                        wrong(player);
                    }
                    return;
                }
                ok.accept(key);
            });
        });
    }

    private void changePassword(Player player, String[] args) {
        UUID id = player.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        if (!authed.contains(id) || profile.walletEnc == null) {
            err(player, "Log in first.");
            return;
        }
        if (args.length != 2) {
            err(player, "Usage: /changepassword <old password> <new password>");
            return;
        }
        String problem = weak(player, args[1]);
        if (problem != null) {
            err(player, problem);
            return;
        }
        long lockedFor = Math.max(lockedUntil.getOrDefault("n:" + player.getName().toLowerCase(Locale.ROOT), 0L),
                lockedUntil.getOrDefault("i:" + ip(player), 0L));
        if (lockedFor > System.currentTimeMillis()) {
            err(player, "Too many wrong passwords. Try again later.");
            return;
        }
        if (!busy.add(id)) {
            return;
        }
        String oldPw = args[0];
        String newPw = args[1];
        String blob = profile.walletEnc;
        String wallet = profile.wallet;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String sealed = null;
            byte[] secret = null;
            try {
                secret = KeyVault.open(blob, oldPw, KeyVault.aad(id, wallet));
                sealed = KeyVault.seal(secret, newPw, params(), KeyVault.aad(id, wallet));
            } catch (GeneralSecurityException | RuntimeException ignored) {
            } finally {
                if (secret != null) {
                    Arrays.fill(secret, (byte) 0);
                }
            }
            String result = sealed;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                busy.remove(id);
                if (result == null) {
                    // Same attempt counter and lockout as /login.
                    if (player.isOnline()) {
                        wrong(player);
                    }
                    return;
                }
                profile.walletEnc = result;
                plugin.profiles().save(profile);
                plugin.getLogger().info(player.getName() + " changed their password.");
                player.sendMessage(Component.text("Password changed. Your purse is locked with the new one.", NamedTextColor.GREEN));
            });
        });
    }

    /** /sbadmin unregister <name>: wipes the login only. The sealed key is kept but unusable; the old purse is lost. */
    void unregister(CommandSender sender, String name) {
        org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayer(name);
        UUID id = target.getUniqueId();
        Profile profile = plugin.profiles().get(id);
        if (profile.walletEnc == null) {
            sender.sendMessage(Component.text(name + " has no password set.", NamedTextColor.RED));
            return;
        }
        String oldWallet = profile.wallet;
        profile.walletOrphaned = profile.walletEnc + "|" + oldWallet;
        profile.walletEnc = null;
        profile.wallet = "";
        plugin.profiles().save(profile);
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().uuid().equals(id)) {
                Arrays.fill(e.getValue().key(), (byte) 0);
                return true;
            }
            return false;
        });
        authed.remove(id);
        wipe(id);
        names.set(name.toLowerCase(Locale.ROOT), null);
        saveNames();
        Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            online.kick(Component.text("Your account was reset by an admin. Join again and /register.", NamedTextColor.RED));
        }
        plugin.getLogger().warning("Unregistered " + name + ". Purse " + oldWallet + " is LOST unless recovered: its sealed key is kept as wallet-enc-orphaned "
                + "(phase 2 TODO: recovery by main-wallet signature). Next /register makes a new purse.");
        sender.sendMessage(Component.text("Unregistered " + name + ". WARNING: purse " + oldWallet + " is lost unless the player recovers it "
                + "(their old password still opens the kept blob; signature recovery is phase 2). Their next /register makes a new purse.", NamedTextColor.GOLD));
    }

    private void saveNames() {
        try {
            names.save(namesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save auth/names.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ dev / user mode
    // Dev accounts (config dev-accounts) play as normal users by default. /dev grants op + somnia.admin for this
    // session only, and only after /login (in offline mode the name alone proves nothing). Quit, restart and /user revert it.
    private final Set<UUID> dev = ConcurrentHashMap.newKeySet();
    private final Map<UUID, org.bukkit.permissions.PermissionAttachment> devPerms = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.GameMode> devMode = new ConcurrentHashMap<>();

    boolean isDevAccount(Player player) {
        for (String n : plugin.getConfig().getStringList("dev-accounts")) {
            if (n.equals(player.getName())) {
                return true;
            }
        }
        return false;
    }

    boolean isDev(Player player) {
        return player != null && dev.contains(player.getUniqueId()) && authed.contains(player.getUniqueId());
    }

    /** At start: no dev account keeps a permanent op (ops.json); op only ever comes from /dev. */
    void deopDevAccounts() {
        for (String n : plugin.getConfig().getStringList("dev-accounts")) {
            org.bukkit.OfflinePlayer p = plugin.getServer().getOfflinePlayer(n);
            if (p.isOp()) {
                p.setOp(false);
                plugin.getLogger().info("Removed the permanent op of " + n + " (op only through /dev now).");
            }
        }
    }

    private void devCommand(Player player, boolean on) {
        if (!isDevAccount(player) || !authed.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Unknown or incomplete command, see below for error", NamedTextColor.RED));
            return;
        }
        if (on) {
            setDev(player, true, player.getName());
        } else {
            setUser(player, true, player.getName());
        }
    }

    void setDev(Player player, boolean announce, String by) {
        UUID id = player.getUniqueId();
        if (!dev.add(id)) {
            player.sendMessage(Component.text("You are already in dev mode. /user goes back.", NamedTextColor.YELLOW));
            return;
        }
        org.bukkit.permissions.PermissionAttachment a = player.addAttachment(plugin);
        a.setPermission("somnia.admin", true);
        devPerms.put(id, a);
        player.setOp(true);
        org.bukkit.GameMode prev = devMode.remove(id);
        if (prev != null && prev != player.getGameMode()) {
            player.setGameMode(prev);
        }
        player.updateCommands();
        player.sendPlayerListHeader(Component.text("[DEV]", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        player.sendMessage(Component.text("[DEV] ", NamedTextColor.RED).append(Component.text("Dev mode on: op and admin until /user, quit or restart.", NamedTextColor.GRAY)));
        plugin.getLogger().info("Mode switch: " + player.getName() + " -> dev (by " + by + ").");
    }

    /** User mode: no op, no extra permission, survival, no flight. The default at every login. */
    void setUser(Player player, boolean announce, String by) {
        UUID id = player.getUniqueId();
        boolean was = dev.remove(id);
        org.bukkit.permissions.PermissionAttachment a = devPerms.remove(id);
        if (a != null) {
            try {
                player.removeAttachment(a);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (player.isOp()) {
            player.setOp(false);
        }
        if (isDevAccount(player) && player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            devMode.put(id, player.getGameMode());
        }
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setInvisible(false);
        player.sendPlayerListHeader(Component.empty());
        player.updateCommands();
        if (announce) {
            player.sendMessage(Component.text("User mode: you play like everyone else.", NamedTextColor.GREEN));
        }
        if (was || announce) {
            plugin.getLogger().info("Mode switch: " + player.getName() + " -> user (by " + by + ").");
        }
    }

    /** Every login starts in user mode (before the game's own login handlers run). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLoginUser(SbLoginEvent event) {
        Player player = event.getPlayer();
        if (isDevAccount(player) || player.isOp()) {
            setUser(player, false, "login");
        }
    }

    private void devTick() {
        for (UUID id : dev) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                p.sendActionBar(Component.text("[DEV]", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
            }
        }
    }

    private void devQuit(Player player) {
        UUID id = player.getUniqueId();
        if (dev.remove(id)) {
            plugin.getLogger().info("Mode switch: " + player.getName() + " -> user (quit).");
        }
        devPerms.remove(id);
        if (player.isOp() && isDevAccount(player)) {
            player.setOp(false);
        }
    }

    /** Console: /sbadmin dev|user <name> for a logged-in player. */
    void consoleMode(CommandSender sender, String name, boolean on) {
        Player p = plugin.getServer().getPlayerExact(name);
        if (p == null || !authed.contains(p.getUniqueId())) {
            sender.sendMessage(Component.text(name + " is not online and logged in.", NamedTextColor.RED));
            return;
        }
        if (on) {
            setDev(p, true, "console");
        } else {
            setUser(p, true, "console");
        }
        sender.sendMessage(Component.text(name + " is now in " + (on ? "dev" : "user") + " mode.", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ the limbo: block everything until login
    private boolean blocked(Player player) {
        return player != null && !authed.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blocked(event.getPlayer())) {
            return;
        }
        String cmd = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');
        if (colon >= 0) {
            cmd = cmd.substring(colon + 1);
        }
        if (!ALLOWED.contains(cmd)) {
            event.setCancelled(true);
            Wait w = waiting.get(event.getPlayer().getUniqueId());
            err(event.getPlayer(), w != null && w.register ? "Register first: /register <password> <password>" : "Log in first: /login <password>");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandList(org.bukkit.event.player.PlayerCommandSendEvent event) {
        if (!isDevAccount(event.getPlayer()) || blocked(event.getPlayer())) {
            event.getCommands().removeIf(c -> DEV_CMDS.contains(c.toLowerCase(Locale.ROOT).replaceFirst("^[a-z0-9_]+:", "")));
        }
        if (blocked(event.getPlayer())) {
            event.getCommands().removeIf(c -> !ALLOWED.contains(c.toLowerCase(Locale.ROOT).replaceFirst("^[a-z0-9_]+:", "")));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
            event.viewers().clear();
            err(event.getPlayer(), "Chat opens after you log in.");
            return;
        }
        // players still in limbo don't read the chat either
        event.viewers().removeIf(a -> a instanceof Player p && blocked(p));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        // Inside the limbo cell small moves are harmless (correcting every step makes clients resend positions
        // and get kicked); only someone outside the cell (or not in limbo at all) is pulled back.
        Player player = event.getPlayer();
        if (!blocked(player) || event.getTo() == null) {
            return;
        }
        Location to = event.getTo();
        boolean inCell = inLimbo(to.getWorld()) && Math.abs(to.getX() - 0.5) < 2 && Math.abs(to.getZ() - 0.5) < 2
                && to.getY() >= 99.5 && to.getY() < 103;
        if (inCell) {
            return;
        }
        if (event.getFrom().getX() != to.getX() || event.getFrom().getY() != to.getY() || event.getFrom().getZ() != to.getZ()) {
            Location stay = inLimbo(event.getFrom().getWorld()) ? limboSpot() : event.getFrom().clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            event.setTo(stay);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (blocked(player) && waiting.containsKey(player.getUniqueId()) && !inLimbo(event.getTo().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && blocked(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Another player joining must not see (or be seen by) players still in the limbo. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOtherJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (UUID id : waiting.keySet()) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && !p.equals(joined)) {
                joined.hidePlayer(plugin, p);
                p.hidePlayer(plugin, joined);
            }
        }
    }
}
