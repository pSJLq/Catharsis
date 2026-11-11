package dev.somnia.skyblock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class MoveServer {
    private final SomniaSkyblockPlugin plugin;
    private HttpServer server;

    MoveServer(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8790), 0);
            server.createContext("/move", this::move);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "skyhold-move");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            plugin.getLogger().info("Inventory move API on 127.0.0.1:8790");
        } catch (IOException exception) {
            plugin.getLogger().warning("Inventory move API did not start: " + exception.getMessage());
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void move(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"ok\":false,\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int from = number(body, "from");
        int to = number(body, "to");
        if (from < 0 || to < 0) {
            write(exchange, 400, "{\"ok\":false,\"error\":\"Need from and to\"}");
            return;
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.hold().move(from, to, done));
        try {
            String hash = done.get(30, TimeUnit.SECONDS);
            String json = hash == null || hash.isBlank()
                    ? "{\"ok\":true,\"hash\":\"\"}"
                    : "{\"ok\":true,\"hash\":\"" + hash + "\"}";
            write(exchange, 200, json);
        } catch (TimeoutException exception) {
            write(exchange, 504, "{\"ok\":false,\"error\":\"Shannon timed out\"}");
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String message = cause.getMessage() == null ? "Move failed." : cause.getMessage();
            int status = message.contains("Sync in progress") ? 409 : 400;
            write(exchange, status, "{\"ok\":false,\"error\":\"" + escape(message) + "\"}");
        }
    }

    private static int number(String body, String name) {
        int at = body.indexOf("\"" + name + "\"");
        if (at < 0) {
            return -1;
        }
        int colon = body.indexOf(':', at);
        if (colon < 0) {
            return -1;
        }
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String escape(String message) {
        return message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
