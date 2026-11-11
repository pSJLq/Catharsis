package dev.somnia.skyblock;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SkyBlock-structure sidebar (title, date, season/day, clock, zone, STT, objective, footer),
 * one persistent scoreboard per player updated through team prefixes so lines never flicker,
 * plus the top "Objective:" boss bar.
 */
final class Sidebar {
    /** Hub zone [name, kind] for a player (set by HubManager when the zones load); null outside the hub. */
    static volatile java.util.function.Function<Player, String> zone = player -> null;
    static volatile java.util.function.Function<Player, Boolean> zoneCombat = player -> false;
    private static final String[] SEASONS = {"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
            "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"};
    private static final String[] KEYS = {"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e"};
    private Quests quests;
    private SomniaSkyblockPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, String> stt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sttAt = new ConcurrentHashMap<>();

    void quests(Quests quests) {
        this.quests = quests;
    }

    void start(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (!plugin.auth().authed(player)) {
                    continue;
                }
                show(player, plugin.profiles().get(player.getUniqueId()));
            }
        }, 40L, 40L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickArrows, 43L, 3L);
    }

    /** Sidebar line of "Objective <arrow>" and the arrow last painted there, per player. */
    private final Map<UUID, Integer> arrowLine = new ConcurrentHashMap<>();
    private final Map<UUID, String> arrows = new ConcurrentHashMap<>();

    private static String header(String arrow) {
        return "§fObjective §e" + arrow;
    }

    /**
     * Every 3 ticks: recompute the objective arrow (8 directions relative to where the player looks) and repaint
     * only that one line, and only when the direction bucket changed.
     */
    private void tickArrows() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Integer line = arrowLine.get(id);
            Scoreboard board = boards.get(id);
            if (line == null || board == null || player.getScoreboard() != board) {
                continue;
            }
            Profile profile = plugin.profiles().get(id);
            Quests.Step step = quests == null ? null : quests.current(profile);
            String arrow = ObjectiveArrow.glyph(plugin, player, profile, step);
            if (arrow.equals(arrows.get(id))) {
                continue;
            }
            Team team = board.getTeam("l" + line);
            if (team != null) {
                team.prefix(LEGACY.deserialize(header(arrow)));
                arrows.put(id, arrow);
            }
        }
    }

    void forget(UUID id) {
        arrowLine.remove(id);
        arrows.remove(id);
        boards.remove(id);
        BossBar bar = bars.remove(id);
        stt.remove(id);
        sttAt.remove(id);
    }

    static String season(long fullTime) {
        long day = fullTime / 24000L;
        int month = (int) ((day / 31) % 12);
        int dom = (int) (day % 31) + 1;
        return SEASONS[month] + " " + dom + suffix(dom);
    }

    static String suffix(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return "th";
        }
        return switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** Minecraft time 0 = 6:00am; rounded down to 10 in-game minutes like the SkyBlock clock. */
    static String clock(long time) {
        long t = (time + 6000) % 24000;
        int hours = (int) (t / 1000);
        int minutes = (int) ((t % 1000) * 60 / 1000) / 10 * 10;
        int h12 = hours % 12 == 0 ? 12 : hours % 12;
        return h12 + ":" + String.format(java.util.Locale.ROOT, "%02d", minutes) + (hours < 12 ? "am" : "pm");
    }

    static boolean day(long time) {
        long t = time % 24000;
        return t < 12300 || t > 23850;
    }

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    /** The board is padded to this many times the widest text line (pixels), like the original. */
    private static final double WIDEN = 1.25;
    /** Objective titles up to this many characters stay on one line. */
    private static final int WRAP = 21;

    /** Approximate width in pixels of a character in the default Minecraft font (glyph + 1px spacing). */
    static int px(char c) {
        if ("!',.:;i|".indexOf(c) >= 0) {
            return 2;
        }
        if ("`l".indexOf(c) >= 0) {
            return 3;
        }
        if (" It[]".indexOf(c) >= 0) {
            return 4;
        }
        if ("\"()*<>fk{}".indexOf(c) >= 0) {
            return 5;
        }
        if (c == '@' || c == '~') {
            return 7;
        }
        return c > 0x2000 ? 8 : 6;
    }

    /** Width in pixels of a legacy-coded line (§l adds 1px per glyph). */
    static int px(String legacy) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(++i));
                bold = code == 'l' || (bold && "klmno".indexOf(code) >= 0);
                continue;
            }
            width += px(c) + (bold && c != ' ' ? 1 : 0);
        }
        return width;
    }

    /** Area name for the tab list (same rules as the board's ⏣ line). */
    String area(Player player) {
        String where = zone.apply(player);
        if (where != null) {
            return where;
        }
        boolean island = plugin != null && player.getWorld().equals(plugin.islands().world());
        return island ? "Your Island" : "None";
    }

    /** Last known STT balance text for the tab list. */
    String stt(UUID id) {
        return stt.getOrDefault(id, "…");
    }

    void show(Player player, Profile profile) {
        UUID id = player.getUniqueId();
        Scoreboard board = boards.computeIfAbsent(id, k -> create());
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
        refreshStt(player, profile);
        World world = player.getWorld();
        // Sidebar palette with legacy codes: §e (#FFFF55) title/objective/url, §7 date + time, §8 server id,
        // §f season and labels, §6 balance. Lines are padded so the board is as wide as the original.
        List<String> raw = new ArrayList<>();
        int online = plugin == null ? 1 : Math.max(1, plugin.getServer().getOnlinePlayers().size());
        raw.add("§7" + LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yy")) + " §8m" + online + "S");
        raw.add("");
        raw.add("§f " + season(world.getFullTime()));
        boolean daylight = day(world.getTime());
        raw.add("§7 " + clock(world.getTime()) + (daylight ? " §e☀" : " §b☽"));
        String where = zone.apply(player);
        if (where == null) {
            boolean island = plugin != null && player.getWorld().equals(plugin.islands().world());
            raw.add(" §a⏣ " + (island ? "Your Island" : "None"));
        } else {
            boolean combat = Boolean.TRUE.equals(zoneCombat.apply(player));
            String color = combat ? "§c" : "§b";
            raw.add(" " + color + "⏣ " + where);
        }
        raw.add("");
        raw.add("§fSTT: §6" + stt.getOrDefault(id, "…"));
        raw.add("");
        Quests.Step step = quests == null ? null : quests.current(profile);
        String arrow = plugin == null ? ObjectiveArrow.NONE : ObjectiveArrow.glyph(plugin, player, profile, step);
        arrowLine.put(id, raw.size());
        arrows.put(id, arrow);
        raw.add(header(arrow));
        if (step == null) {
            raw.add("§eChampion's path");
        } else {
            String title = step.title();
            String progress = " §7(" + quests.progress(profile) + ")";
            if (title.length() > WRAP) {
                int cut = title.lastIndexOf(' ', WRAP);
                cut = cut <= 0 ? WRAP : cut;
                raw.add("§e" + title.substring(0, cut));
                raw.add("§e" + title.substring(cut).trim() + progress);
            } else if (title.length() + progress.length() - 2 <= WRAP) {
                raw.add("§e" + title + progress);
            } else {
                raw.add("§e" + title);
                raw.add(progress.trim());
            }
        }
        raw.add("");
        raw.add("§esomnia.network");
        // No padding: the width comes from the longest real line (~21 chars).
        // Spacer lines stay (unique runs of 1..n spaces) so the height matches the original.
        List<Component> lines = new ArrayList<>();
        int blank = 0;
        for (String line : raw) {
            if (line.isEmpty()) {
                lines.add(Component.text(" ".repeat(++blank)));
                continue;
            }
            lines.add(LEGACY.deserialize(line));
        }
        paint(board, lines);
        bossbar(player, profile, step);
    }

    private Scoreboard create() {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("sky", Criteria.DUMMY,
                LEGACY.deserialize("§e§lSKYBLOCK"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        for (int i = 0; i < KEYS.length; i++) {
            Team team = board.registerNewTeam("l" + i);
            team.addEntry(KEYS[i]);
        }
        return board;
    }

    private void paint(Scoreboard board, List<Component> lines) {
        Objective objective = board.getObjective("sky");
        if (objective == null) {
            return;
        }
        for (int i = 0; i < KEYS.length; i++) {
            Team team = board.getTeam("l" + i);
            if (team == null) {
                continue;
            }
            if (i < lines.size()) {
                team.prefix(lines.get(i));
                objective.getScore(KEYS[i]).setScore(KEYS.length - i);
            } else {
                board.resetScores(KEYS[i]);
            }
        }
    }

    private void bossbar(Player player, Profile profile, Quests.Step step) {
        UUID id = player.getUniqueId();
        if (profile.bossbarOff || step == null) {
            BossBar old = bars.remove(id);
            if (old != null) {
                player.hideBossBar(old);
            }
            return;
        }
        float progress = Math.max(0f, Math.min(1f, profile.questProgress / (float) Math.max(1, step.goal())));
        Component name = Component.text("Objective: ", NamedTextColor.WHITE)
                .append(Component.text(step.title(), NamedTextColor.YELLOW))
                .append(Component.text(" (" + quests.progress(profile) + ")", NamedTextColor.GRAY));
        BossBar bar = bars.get(id);
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
        } else {
            bar.name(name);
            bar.progress(progress);
        }
        player.showBossBar(bar);
    }

    private void refreshStt(Player player, Profile profile) {
        if (plugin == null || profile.wallet == null || profile.wallet.isBlank()) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long at = sttAt.get(id);
        if (at != null && now - at < 60_000) {
            return;
        }
        sttAt.put(id, now);
        String address = profile.wallet;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String value = plugin.wallets().balance(address);
                stt.put(id, trim(value));
            } catch (Exception exception) {
                stt.putIfAbsent(id, "?");
            }
        });
    }

    private static String trim(String value) {
        if (value == null) {
            return "?";
        }
        String v = value.replace("STT", "").trim();
        try {
            return new java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return v;
        }
    }
}
