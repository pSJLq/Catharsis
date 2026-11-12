package dev.somnia.skyblock;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The player's real IP for every per-IP rule (registration cap, login lockout, event multi-account tag).
 *
 * Direct connections (Java clients, browser clients on ws://host:25565) use the socket address as before.
 * Browser clients behind a reverse proxy (Caddy on the VDS, wss) reach EaglerXServer from the proxy's
 * address; then, and only then, the X-Real-IP header (or the last X-Forwarded-For hop) of the WebSocket
 * upgrade is used. A header from any other address is ignored, so nobody can fake an IP by sending it
 * straight to the game port. EaglerXServer's own forward_ip can stay off (local play needs no header).
 *
 * The header is read in EaglerXServer's WebSocket-open event (bound by reflection, no compile dependency)
 * and kept per socket (ip:port) until the player leaves. Paper reports the same socket for the player, so
 * pre-login, join and register all see the same real IP.
 */
final class RealIp implements Listener {
    private static final Map<String, Entry> BY_SOCKET = new ConcurrentHashMap<>();
    private static final Pattern V4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern V6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static volatile Set<String> trusted = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1");
    private static volatile boolean debug = true;
    private static volatile boolean bound;
    private static volatile java.util.logging.Logger log = java.util.logging.Logger.getLogger("SomniaSkyblock");

    private record Entry(String ip, long at) {
    }

    private final SomniaSkyblockPlugin plugin;

    RealIp(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        log = plugin.getLogger();
        reload();
    }

    void reload() {
        List<String> list = plugin.getConfig().getStringList("network.trusted-proxies");
        if (list.isEmpty()) {
            list = List.of("127.0.0.1", "::1");
        }
        Set<String> set = ConcurrentHashMap.newKeySet();
        for (String s : list) {
            String n = literal(s == null ? "" : s.trim());
            if (n != null) {
                set.add(n);
            }
        }
        trusted = Set.copyOf(set);
        debug = plugin.getConfig().getBoolean("network.log-real-ip", true);
    }

    /** Hook EaglerXServer's WebSocket-open event once the server has started (it loads after us). */
    void bind() {
        if (bound) {
            return;
        }
        Plugin eagler = plugin.getServer().getPluginManager().getPlugin("EaglercraftXServer");
        if (eagler == null || !eagler.isEnabled()) {
            plugin.getLogger().info("Real IP: EaglerXServer not running; socket addresses are used.");
            return;
        }
        try {
            ClassLoader cl = eagler.getClass().getClassLoader();
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(
                    "net.lax1dude.eaglercraft.backend.server.api.bukkit.event.EaglercraftWebSocketOpenEvent", true, cl);
            Class<?> api = Class.forName("net.lax1dude.eaglercraft.backend.server.api.event.IEaglercraftWebSocketOpenEvent", true, cl);
            Method header = api.getMethod("getRawHeader", String.class);
            Method connection = api.getMethod("getConnection");
            Method socket = Class.forName("net.lax1dude.eaglercraft.backend.server.api.INettyChannel", true, cl).getMethod("getSocketAddress");
            plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) {
                    return;
                }
                try {
                    Object conn = connection.invoke(event);
                    SocketAddress raw = conn == null ? null : (SocketAddress) socket.invoke(conn);
                    opened(raw, (String) header.invoke(event, "X-Real-IP"), (String) header.invoke(event, "X-Forwarded-For"));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().warning("Real IP: could not read a WebSocket header (" + e.getClass().getSimpleName() + ").");
                }
            }, plugin, false);
            bound = true;
            plugin.getLogger().info("Real IP: X-Real-IP / X-Forwarded-For accepted only from " + trusted + ".");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Real IP: EaglerXServer event API not found (" + e + "); socket addresses are used.");
        }
    }

    /** A WebSocket upgrade from {@code raw}; remembers the forwarded IP when the peer is a trusted proxy. */
    static void opened(SocketAddress raw, String realIpHeader, String forwardedFor) {
        if (!(raw instanceof InetSocketAddress socket) || socket.getAddress() == null) {
            return;
        }
        String peer = socket.getAddress().getHostAddress();
        if (!trusted.contains(peer)) {
            return;
        }
        String ip = literal(realIpHeader == null ? null : realIpHeader.trim());
        if (ip == null && forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            ip = literal(hops[hops.length - 1].trim());
        }
        if (ip == null) {
            return;
        }
        BY_SOCKET.put(key(socket), new Entry(ip, System.currentTimeMillis()));
        if (debug) {
            log.info("Real IP " + ip + " for proxy socket " + key(socket));
        }
    }

    static String of(Player player) {
        return player == null ? "?" : of(player.getAddress());
    }

    static String of(AsyncPlayerPreLoginEvent event) {
        SocketAddress raw = event.getRawSocketAddress();
        if (raw instanceof InetSocketAddress socket) {
            return of(socket);
        }
        return event.getAddress() == null ? "?" : event.getAddress().getHostAddress();
    }

    static String of(InetSocketAddress socket) {
        if (socket == null || socket.getAddress() == null) {
            return "?";
        }
        Entry e = BY_SOCKET.get(key(socket));
        return e != null ? e.ip() : socket.getAddress().getHostAddress();
    }

    /** The real IP as an address (null when unknown). */
    static InetAddress inet(Player player) {
        String ip = of(player);
        String lit = literal(ip);
        if (lit == null) {
            return null;
        }
        try {
            return InetAddress.getByName(lit);
        } catch (Exception e) {
            return null;
        }
    }

    static void purge(java.util.Collection<? extends Player> online) {
        long cut = System.currentTimeMillis() - 600_000L;
        Set<String> keep = new java.util.HashSet<>();
        for (Player p : online) {
            if (p.getAddress() != null) {
                keep.add(key(p.getAddress()));
            }
        }
        BY_SOCKET.entrySet().removeIf(e -> e.getValue().at() < cut && !keep.contains(e.getKey()));
    }

    static String key(InetSocketAddress socket) {
        return socket.getAddress().getHostAddress() + "|" + socket.getPort();
    }

    /** A plain IPv4/IPv6 literal in canonical form, or null (never resolves host names). */
    static String literal(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        var m = V4.matcher(s);
        if (m.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) {
                    return null;
                }
            }
        } else if (!(s.indexOf(':') >= 0 && V6.matcher(s).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(s).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    static int size() {
        return BY_SOCKET.size();
    }

    void unbind() {
        HandlerList.unregisterAll(this);
        bound = false;
        BY_SOCKET.clear();
    }
}
