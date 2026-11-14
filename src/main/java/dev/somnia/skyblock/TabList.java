package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SkyBlock-style tab list: 4 columns x 20 rows = 80 fixed list entries per viewer (Players, Players, Info, Info),
 * plus a header and a footer. Real players are unlisted so one person online still fills the whole grid.
 * The entries are sent as player-info packets through reflection on the server classes (no extra compile dependency);
 * if anything is missing at runtime the tab falls back to the plain list with header/footer only.
 * Entry names are short sort keys ("!00".."!79", at most 16 chars) so the 1.8 browser client keeps the order too.
 */
final class TabList implements Listener {
    private static final int SLOTS = 80;
    private static final UUID[] IDS = new UUID[SLOTS];
    private static final String[] NAMES = new String[SLOTS];

    static {
        for (int i = 0; i < SLOTS; i++) {
            IDS[i] = UUID.nameUUIDFromBytes(("catharsis-tab:" + i).getBytes(StandardCharsets.UTF_8));
            NAMES[i] = String.format(java.util.Locale.ROOT, "!%02d", i);
        }
    }

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, Component[]> shown = new ConcurrentHashMap<>();
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();
    private boolean broken;

    // reflection handles
    private Method getHandle;
    private java.lang.reflect.Field connection;
    private Method send;
    private Constructor<?> entryCtor;
    private Constructor<?> packetCtor;
    private Method asVanilla;
    private Method updateListed;
    private Class<?> actionClass;
    private Object survival;
    private Constructor<?> profileCtor;

    TabList(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        try {
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            connection = serverPlayer.getField("connection");
            Class<?> listener = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
            send = listener.getMethod("send", packet);
            Class<?> info = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class<?> entry = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            actionClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            Class<?> profile = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> gameType = Class.forName("net.minecraft.world.level.GameType");
            Class<?> chat = Class.forName("net.minecraft.network.chat.Component");
            Class<?> session = Class.forName("net.minecraft.network.chat.RemoteChatSession$Data");
            entryCtor = entry.getConstructor(UUID.class, profile, boolean.class, int.class, gameType, chat, boolean.class, int.class, session);
            packetCtor = info.getConstructor(EnumSet.class, List.class);
            updateListed = info.getMethod("updateListed", UUID.class, boolean.class);
            profileCtor = profile.getConstructor(UUID.class, String.class);
            survival = gameType.getField("SURVIVAL").get(null);
            asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure").getMethod("asVanilla", Component.class);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            broken = true;
            plugin.getLogger().warning("Tab list: fake entries disabled (" + exception + "). Header and footer still work.");
        }
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(SbLoginEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> setup(player), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Somebody new: hide the real entry from everyone who already has the SkyBlock grid.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::unlistAll, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
        ready.remove(event.getPlayer().getUniqueId());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EnumSet actions(String... names) {
        EnumSet set = EnumSet.noneOf((Class) actionClass);
        for (String name : names) {
            set.add(Enum.valueOf((Class) actionClass, name));
        }
        return set;
    }

    private void sendPacket(Player player, Object packet) throws ReflectiveOperationException {
        Object handle = getHandle.invoke(player);
        Object conn = connection.get(handle);
        if (conn != null) {
            send.invoke(conn, packet);
        }
    }

    private Object entry(int i, Component text) throws ReflectiveOperationException {
        Object profile = profileCtor.newInstance(IDS[i], NAMES[i]);
        return entryCtor.newInstance(IDS[i], profile, true, 0, survival, asVanilla.invoke(null, text), false, 0, null);
    }

    private void setup(Player player) {
        if (!player.isOnline()) {
            return;
        }
        if (!broken) {
            try {
                Component[] rows = rows(player);
                List<Object> entries = new ArrayList<>();
                for (int i = 0; i < SLOTS; i++) {
                    entries.add(entry(i, rows[i]));
                }
                sendPacket(player, packetCtor.newInstance(actions("ADD_PLAYER", "UPDATE_LISTED", "UPDATE_LATENCY", "UPDATE_GAME_MODE",
                        "UPDATE_DISPLAY_NAME", "UPDATE_LIST_ORDER"), entries));
                shown.put(player.getUniqueId(), rows);
                ready.add(player.getUniqueId());
            } catch (ReflectiveOperationException | RuntimeException exception) {
                broken = true;
                plugin.getLogger().warning("Tab list: fake entries disabled (" + exception + ").");
            }
        }
        unlistAll();
        header(player);
    }

    /** Real players stay known to every client (skins, chat) but are not listed: the 80 SkyBlock rows are the list. */
    private void unlistAll() {
        if (broken) {
            return;
        }
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        for (Player viewer : online) {
            if (!ready.contains(viewer.getUniqueId())) {
                continue;
            }
            for (Player other : online) {
                try {
                    if (other == viewer) {
                        sendPacket(viewer, updateListed.invoke(null, viewer.getUniqueId(), false));
                    } else if (viewer.isListed(other)) {
                        viewer.unlistPlayer(other);
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    plugin.getLogger().fine("Tab unlist: " + exception);
                }
            }
        }
    }

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!ready.contains(player.getUniqueId())) {
                continue;
            }
            header(player);
            if (broken) {
                continue;
            }
            Component[] now = rows(player);
            Component[] before = shown.get(player.getUniqueId());
            List<Object> changed = new ArrayList<>();
            try {
                for (int i = 0; i < SLOTS; i++) {
                    if (before == null || !now[i].equals(before[i])) {
                        changed.add(entry(i, now[i]));
                    }
                }
                if (!changed.isEmpty()) {
                    sendPacket(player, packetCtor.newInstance(actions("UPDATE_DISPLAY_NAME"), changed));
                }
                shown.put(player.getUniqueId(), now);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                broken = true;
                plugin.getLogger().warning("Tab list: fake entries disabled (" + exception + ").");
            }
        }
        unlistAll();
    }

    // ------------------------------------------------------------------ content
    private static Component t(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    private static Component title(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.BOLD, true);
    }

    private static Component label(String name, String value, NamedTextColor valueColor) {
        return t(" " + name + ": ", NamedTextColor.WHITE).append(t(value, valueColor));
    }

    private static Component soon(String name) {
        return t(" " + name + ": ", NamedTextColor.WHITE).append(t("SOON", NamedTextColor.GRAY));
    }

    private Component playerRow(Player p) {
        int level = Skills.skyblockLevel(plugin.profiles().get(p.getUniqueId()));
        NamedTextColor name = plugin.stats() != null ? plugin.stats().nameColor(p) : NamedTextColor.GRAY;
        return t("[", NamedTextColor.DARK_GRAY).append(t(String.valueOf(level), Skills.levelColor(level)))
                .append(t("] ", NamedTextColor.DARK_GRAY)).append(t(p.getName(), name));
    }

    Component[] rows(Player viewer) {
        Component[] rows = new Component[SLOTS];
        Component empty = Component.empty();
        java.util.Arrays.fill(rows, empty);
        Profile profile = plugin.profiles().get(viewer.getUniqueId());
        // Columns 1-2: players, highest SkyBlock Level first.
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        players.removeIf(p -> plugin.auth() != null && !plugin.auth().authed(p));
        Map<UUID, Integer> lv = new HashMap<>();
        for (Player p : players) {
            lv.put(p.getUniqueId(), Skills.skyblockLevel(plugin.profiles().get(p.getUniqueId())));
        }
        players.sort((a, b) -> Integer.compare(lv.get(b.getUniqueId()), lv.get(a.getUniqueId())));
        rows[0] = title("Players (" + players.size() + ")", NamedTextColor.GREEN);
        rows[20] = title("Players", NamedTextColor.GREEN);
        int at = 1;
        for (Player p : players) {
            if (at == 20) {
                at = 21;
            }
            if (at >= 40) {
                break;
            }
            rows[at++] = playerRow(p);
        }
        // Column 3: info.
        int xp = Skills.skyblockXp(profile);
        int sb = xp / 100;
        String area = plugin.sidebar().area(viewer);
        int c = 40;
        rows[c++] = title("Info", NamedTextColor.AQUA);
        rows[c++] = label("Area", area, NamedTextColor.AQUA);
        rows[c++] = label("Server", "lumen-1", NamedTextColor.GRAY);
        rows[c++] = label("Profile", "Main", NamedTextColor.GREEN);
        rows[c++] = empty;
        rows[c++] = label("SB Level", String.valueOf(sb), Skills.levelColor(sb)).append(t(" (" + (xp % 100) + "/100)", NamedTextColor.AQUA));
        rows[c++] = label("Purse", String.format(java.util.Locale.ROOT, "%,d", profile.coins) + " Coins", NamedTextColor.GOLD);
        rows[c++] = label("STT", plugin.sidebar().stt(viewer.getUniqueId()), NamedTextColor.GOLD);
        rows[c++] = soon("Bank");
        rows[c++] = empty;
        rows[c++] = title("Minions", NamedTextColor.YELLOW);
        rows[c++] = Minions.ENABLED ? label("Placed", plugin.minions().count(viewer.getUniqueId()) + "", NamedTextColor.YELLOW)
                .append(t("  Stored: " + profile.minionsHeld, NamedTextColor.GRAY)) : t(" SOON", NamedTextColor.GRAY);
        rows[c++] = empty;
        rows[c++] = title("Pet", NamedTextColor.YELLOW);
        rows[c++] = t(" SOON", NamedTextColor.GRAY);
        rows[c++] = empty;
        rows[c++] = title("Election", NamedTextColor.YELLOW);
        rows[c++] = t(" SOON", NamedTextColor.GRAY);
        rows[c++] = empty;
        rows[c] = title("Event", NamedTextColor.YELLOW);
        // Column 4: skills and stats.
        Stats.Sheet sheet = plugin.stats().sheet(viewer);
        int d = 60;
        rows[d++] = title("Account Info", NamedTextColor.AQUA);
        rows[d++] = title("Skills", NamedTextColor.YELLOW);
        for (Skills.Skill s : Skills.Skill.values()) {
            int level = Skills.level(profile, s);
            String pct;
            if (level >= Skills.max(s)) {
                pct = "MAX";
            } else {
                long from = Skills.start(s, level);
                long to = Skills.start(s, level + 1);
                pct = SbMenu.percent(Skills.xp(profile, s) - from, to - from);
            }
            rows[d++] = t(" " + s.label + " " + level + ": ", NamedTextColor.WHITE).append(t(pct, NamedTextColor.GREEN));
        }
        rows[d++] = title("Stats", NamedTextColor.YELLOW);
        rows[d++] = t(" Speed: ", NamedTextColor.WHITE).append(t("✦" + sheet.speed(), NamedTextColor.WHITE));
        rows[d++] = t(" Strength: ", NamedTextColor.WHITE).append(t("❁" + sheet.strength(), NamedTextColor.RED));
        rows[d++] = t(" Crit Chance: ", NamedTextColor.WHITE).append(t("☣" + sheet.critChance(), NamedTextColor.BLUE));
        rows[d++] = t(" Crit Damage: ", NamedTextColor.WHITE).append(t("☠" + sheet.critDamage(), NamedTextColor.BLUE));
        rows[d++] = t(" Attack Speed: ", NamedTextColor.WHITE).append(t("⚔0", NamedTextColor.YELLOW));
        rows[d++] = empty;
        rows[d] = t(" Event: ", NamedTextColor.WHITE).append(t("SOON", NamedTextColor.GRAY));
        return rows;
    }

    private void header(Player player) {
        Component header = Component.text("You are playing on ", NamedTextColor.AQUA)
                .append(Component.text("CATHARSIS", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true));
        if (plugin.auth() != null && plugin.auth().isDev(player)) {
            header = header.append(Component.text("  [DEV]", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        }
        header = header.append(Component.newline());
        List<Component> parts = new ArrayList<>();
        if (plugin.cookies() != null && plugin.cookies().active(player)) {
            Profile profile = plugin.profiles().get(player.getUniqueId());
            long remain = profile.cookieUntil - System.currentTimeMillis();
            parts.add(Component.text("Booster: " + Cookies.left(remain), NamedTextColor.AQUA));
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            String n = effect.getType().getKey().getKey().replace('_', ' ');
            parts.add(Component.text(Character.toUpperCase(n.charAt(0)) + n.substring(1), NamedTextColor.GRAY));
        }
        Component effects;
        if (parts.isEmpty()) {
            effects = Component.text("No effects are active.", NamedTextColor.GRAY);
        } else {
            effects = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                effects = effects.append(Component.text(", ", NamedTextColor.GRAY)).append(parts.get(i));
            }
        }
        int ping = player.getPing();
        NamedTextColor pingColor = ping < 100 ? NamedTextColor.GREEN
                : ping < 200 ? NamedTextColor.YELLOW
                : NamedTextColor.RED;
        Component footer = Component.newline()
                .append(Component.text("Active Effects", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
                .append(Component.newline()).append(effects)
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Ping: ", NamedTextColor.GRAY))
                .append(Component.text(ping + " ms", pingColor))
                .append(Component.newline())
                .append(Component.text("somnia.network", NamedTextColor.AQUA));
        player.sendPlayerListHeaderAndFooter(header, footer);
    }
}
