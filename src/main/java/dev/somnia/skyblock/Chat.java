package dev.somnia.skyblock;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** SkyBlock-style chat: framed notices, the welcome block, area discovery and the [level] chat prefix. */
final class Chat implements Listener {
    private static final String BAR = "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";
    private final SomniaSkyblockPlugin plugin;

    Chat(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private static final java.util.Map<java.util.UUID, Long> talking = new java.util.concurrent.ConcurrentHashMap<>();
    static final long NPC_LINE_TICKS = 25L;

    /**
     * NPC dialogue: one chat line every ~1.25 s as "[NPC] Name: line" (yellow tag and name, white text).
     * Returns false (and says nothing) while this player is still in another dialogue. The optional
     * after-task runs on the main thread once the last line is shown.
     */
    static boolean npc(SomniaSkyblockPlugin plugin, Player player, String name, java.util.List<String> lines, Runnable after) {
        long now = System.currentTimeMillis();
        Long until = talking.get(player.getUniqueId());
        if (until != null && until > now) {
            return false;
        }
        talking.put(player.getUniqueId(), now + lines.size() * NPC_LINE_TICKS * 50L);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean last = i == lines.size() - 1;
            Runnable say = () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.sendMessage(Component.text("[NPC] ", NamedTextColor.YELLOW)
                        .append(Component.text(name, NamedTextColor.YELLOW))
                        .append(Component.text(": ", NamedTextColor.WHITE))
                        .append(Component.text(line, NamedTextColor.WHITE)));
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.2f);
                if (last && after != null) {
                    after.run();
                }
            };
            if (i == 0) {
                say.run();
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, say, i * NPC_LINE_TICKS);
            }
        }
        return true;
    }

    static void separator(Player player, NamedTextColor color) {
        player.sendMessage(Component.text(BAR, color).decoration(TextDecoration.STRIKETHROUGH, false));
    }

    /** Pads a line so it sits roughly centred in the default 320 px chat box. */
    static Component centred(String text, TextColor color, boolean bold) {
        int width = 0;
        for (char c : text.toCharArray()) {
            width += c == ' ' ? 4 : (c == 'i' || c == 'l' || c == '!' || c == '.' || c == ',') ? 2 : 6;
            if (bold && c != ' ') {
                width++;
            }
        }
        int pad = Math.max(0, (154 - width / 2) / 4);
        return Component.text(" ".repeat(pad)).append(Component.text(text, color).decoration(TextDecoration.BOLD, bold));
    }

    void welcome(Player player, Profile profile) {
        separator(player, NamedTextColor.GREEN);
        player.sendMessage(centred("Welcome to Somnia SkyBlock!", NamedTextColor.YELLOW, true));
        player.sendMessage(Component.empty());
        player.sendMessage(centred("Your island, purse and bag live on Somnia.", NamedTextColor.YELLOW, false));
        player.sendMessage(centred("Right-click the Nether Star for the SkyBlock Menu.", NamedTextColor.GRAY, false));
        Quests.Step step = plugin.quests().current(profile);
        if (step != null) {
            player.sendMessage(centred("Objective: " + step.title(), NamedTextColor.GOLD, false));
        }
        player.sendMessage(Component.empty());
        player.sendMessage(centred("Have fun!", NamedTextColor.GREEN, true));
        separator(player, NamedTextColor.GREEN);
    }

    static void newStat(Player player, String symbol, String name, NamedTextColor color, String explain) {
        separator(player, NamedTextColor.GREEN);
        player.sendMessage(Component.text("  NEW STAT DISCOVERED! ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text(symbol + " " + name, color).decoration(TextDecoration.BOLD, false)));
        player.sendMessage(Component.text("  " + explain, NamedTextColor.GRAY));
        separator(player, NamedTextColor.GREEN);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
    }

    static void voidDeath(Player player) {
        Component line = Component.text("☠ ", NamedTextColor.RED).append(Component.text(player.getName() + " fell into the void.", NamedTextColor.GRAY));
        for (Player other : player.getWorld().getPlayers()) {
            other.sendMessage(line);
        }
    }

    static final Map<String, List<String>> TIPS = Map.ofEntries(
            Map.entry("plaza", List.of("Trade at the Grand Bazaar and the Auction House.", "The Bank keeps your Relic Pool shares.", "The portal north of the fountain leads to your island. /warp for the rest.")),
            Map.entry("mine", List.of("Coal needs any pickaxe; iron and lapis a stone pick; gold, redstone and diamond an iron pick.", "The Mine Merchant buys your ore.", "The Deep Mines open soon.")),
            Map.entry("crypts", List.of("Crypt Wights hit hard. Wear armor.", "A Gilded Wight guards each crypt hall (rare gold).")),
            Map.entry("barrows", List.of("Barrow Shamblers rise between the graves.", "Hollow Villagers walk at night only.", "Collect Rotten Flesh for Barrow armor.")),
            Map.entry("ruins", List.of("Wolves drop Wolf Teeth.", "Bone Archers guard the walls at night.", "Beware the Alpha Direwolf.")),
            Map.entry("farm", List.of("Harvest ripe wheat. It grows back.", "The Farm Merchant buys your crops.")),
            Map.entry("forest", List.of("Oak, birch, spruce, dark oak and jungle groves.", "The Lumber Merchant buys timber.")),
            Map.entry("mountain", List.of("Follow the trail up to the Stargazer Tower.", "The Enchanter and the Stargazer Wizard live in the tower.")),
            Map.entry("lake", List.of("Fish from the shore for Fishing XP.", "Quiet water, good for a rest.")),
            Map.entry("wharf", List.of("The Fish Merchant buys your catch.", "Cast from the pier for Fishing XP.")),
            Map.entry("colosseum", List.of("The Colosseum is sealed for now.", "It will open later as the gateway to dungeons.")),
            Map.entry("relics", List.of("The Archivist explains relics and the Relic Pool.")),
            Map.entry("spire", List.of("The Stargazer Wizard watches the stars from the tower.")),
            Map.entry("mine_tunnels", List.of("Ore veins line the tunnel walls."))
    );

    /** First visit: big title + "NEW AREA DISCOVERED!", bullet tips and the current objective. */
    void discovered(Player player, Profile profile, String id, String name, boolean combat) {
        NamedTextColor color = combat ? NamedTextColor.RED : NamedTextColor.AQUA;
        if (!profile.discovered.add(id)) {
            player.showTitle(Title.title(Component.empty(), Component.text("⏣ " + name, color),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(300))));
            return;
        }
        plugin.profiles().save(profile);
        player.showTitle(Title.title(Component.text(name, color),
                Component.text("NEW AREA DISCOVERED!", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        separator(player, NamedTextColor.GREEN);
        player.sendMessage(Component.text("  NEW AREA DISCOVERED! ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text(name, color).decoration(TextDecoration.BOLD, false)));
        player.sendMessage(Component.empty());
        for (String tip : TIPS.getOrDefault(id, List.of())) {
            player.sendMessage(Component.text("   ■ ", NamedTextColor.WHITE).append(Component.text(tip, NamedTextColor.GRAY)));
        }
        if (combat) {
            player.sendMessage(Component.text("   ■ ", NamedTextColor.WHITE).append(Component.text("Combat zone: mobs spawn here. No PvP.", NamedTextColor.RED)));
        }
        Quests.Step step = plugin.quests().current(profile);
        if (step != null) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  NEW OBJECTIVE", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            player.sendMessage(Component.text("  " + step.title(), NamedTextColor.YELLOW));
        }
        separator(player, NamedTextColor.GREEN);
        plugin.stats().markDirty(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player source = event.getPlayer();
        Profile profile = plugin.profiles().get(source.getUniqueId());
        int level = Skills.skyblockLevel(profile);
        Component prefix = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text(level, Skills.levelColor(level)))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY));
        boolean dev = plugin.auth() != null && plugin.auth().isDev(source);
        boolean boost = !dev && plugin.cookies() != null && plugin.cookies().active(source);
        Component rank = (dev || source.isOp()) ? Component.text("[ADMIN] ", NamedTextColor.RED) : Component.empty();
        NamedTextColor nameColor = dev ? NamedTextColor.RED : boost ? NamedTextColor.AQUA : NamedTextColor.GRAY;
        // Hypixel: ranked chat body is white, unranked is gray. Booster and /dev count as ranked here.
        NamedTextColor body = dev || boost || source.isOp() ? NamedTextColor.WHITE : NamedTextColor.GRAY;
        event.renderer((player, displayName, message, viewer) -> prefix.append(rank)
                .append(Component.text(player.getName(), nameColor))
                .append(Component.text(": ", body))
                .append(message.colorIfAbsent(body)));
    }
}
