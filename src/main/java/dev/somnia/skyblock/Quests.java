package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The intro quest line (v2), given by Wren on the island and the Quest Giver in the hub. It follows the order of the
 * wiki's starter quests: your island by hand (log, crafting, a wooden pickaxe, cobblestone), then the hub
 * (logs, crops, selling, ore, the Banker and your purse), then combat (a sword, the undead, armor, skills that give
 * Health/Defense/Strength, the wolves), then trading (Bazaar, Auction House) and exploring.
 *
 * Every finished step is worth 5 SkyBlock XP (wiki SkyBlock Levels: +5 per objective); Skills.skyblockXp counts
 * {@link #completed(Profile)}. Extra coin/skill rewards are only the wiki numbers of the matching starter quest
 * (Foraging Tutorial 1000 coins + 100 Foraging, First Harvest 10 Farming, Time to Mine 20 Mining, Time to Strike
 * 100 coins + 15 Combat, Fishing Tutorial 1000 coins + 10 Fishing and a rod, Explorer 50 coins). Skill XP rewards
 * go through Skills.add, so they are queued in the same onchain SkySkills batch as any other XP.
 *
 * Zone names in titles and hints are written as {id} and resolved from the hub's zone names at display time.
 *
 * Migration from v1 (15 steps, linear questStep): every v1 step a player finished stays finished. The player
 * resumes at the first v2 step they have not done; finished v1 steps that come later in the v2 order are kept in
 * Profile.questSkip and complete by themselves (no second reward) when the line reaches them. SkyBlock XP from
 * quests = 5 x (questStep + questSkip.size()), which right after migration equals the old 5 x questStep.
 */
final class Quests implements Listener {
    record Step(String event, int goal, long coins, Skills.Skill skill, long xp, String title, String hint) {
        @Override
        public String title() {
            return names(title);
        }

        @Override
        public String hint() {
            return names(hint);
        }

        String rewardText() {
            StringBuilder b = new StringBuilder("+" + SKYBLOCK_XP + " SkyBlock XP");
            if (skill != null && xp > 0) {
                b.append(", +").append(xp).append(' ').append(skill.label).append(" XP");
            }
            if (coins > 0) {
                b.append(", +").append(coins).append(" Coins");
            }
            if (event.equals("fish")) {
                b.append(", Fishing Rod");
            }
            return b.toString();
        }
    }

    /** Wiki SkyBlock Levels: +5 SkyBlock XP per objective completed. */
    static final int SKYBLOCK_XP = 5;
    /**
     * 2 = the 23-step line; 3 = the same without the Colosseum wave (the Colosseum is sealed, Arena.ENABLED = false);
     * 4 = also without "Place your Cobblestone Minion" (minions are SOON, Minions.ENABLED = false).
     */
    static final int VERSION = 4;
    /** Same address as the website footer's Donate button. */
    static final String DONATE = "0x85b7D75cf35efC7E636FbDf3E82C92c6ceB5AC9D";
    /** Hub districts counted by the exploring step. */
    static final List<String> DISTRICTS = List.of("plaza", "relics", "colosseum", "farm", "mine", "barrows",
            "spire", "ruins", "forest", "mountain", "lake", "wharf");

    static final List<Step> STEPS = List.of(
            new Step("talk_wren", 1, 0, null, 0, "Talk to Wren, your Island Guide",
                    "Wren stands next to your island spawn. Click her."),
            new Step("island_oak", 5, 0, null, 0, "Punch 5 Oak Logs off your tree",
                    "No axe yet: hold left click on the tree on your island. The logs go to your inventory."),
            new Step("open_menu", 1, 0, null, 0, "Open your SkyBlock Menu",
                    "Right-click the Nether Star in the last hotbar slot."),
            new Step("craft_pickaxe", 1, 0, null, 0, "Craft a Wooden Pickaxe",
                    "SkyBlock Menu > Crafting Table. Logs make planks, planks make sticks; 3 planks over 2 sticks make the pickaxe."),
            new Step("island_cobble", 10, 0, null, 0, "Mine 10 Cobblestone",
                    "Use your new pickaxe on the cobblestone generator. It regrows every block you mine."),
            new Step("talk_quest", 1, 0, null, 0, "Meet the Quest Giver in {plaza}",
                    "Take the island portal or type /hub. The Quest Giver waits south of the fountain."),
            new Step("hub_log", 20, 1000, Skills.Skill.FORAGING, 100, "Chop 20 logs in {forest}",
                    "Any log counts. Chopped trees grow back a little later. An axe from the Blacksmith or Woodward Fenn is faster."),
            new Step("hub_crop", 10, 0, Skills.Skill.FARMING, 10, "Harvest 10 crops in {farm}",
                    "Only ripe crops count: wheat, carrots, potatoes, melons, pumpkins and sugar cane. They grow back."),
            new Step("sell", 1, 0, null, 0, "Sell something to a Merchant",
                    "Merchant NPCs buy your logs, crops, ore and drops for coins."),
            new Step("hub_ore", 10, 0, Skills.Skill.MINING, 20, "Mine 10 ore in {mine}",
                    "Coal needs any pickaxe, iron and lapis a stone pickaxe. Mined ore grows back after a while."),
            new Step("talk_bank", 1, 0, null, 0, "Visit the Banker",
                    "The Banker shows your purse. Onchain actions pay a little STT gas from it; /deposit shows its address and the faucet."),
            new Step("forge_sword", 1, 0, null, 0, "Forge a sword at the Blacksmith",
                    "Don't fight bare-handed. The Undead Sword costs 150 coins at the Blacksmith in {plaza}."),
            new Step("kill_graveyard", 10, 100, Skills.Skill.COMBAT, 15, "Slay 10 undead in {barrows}",
                    "The undead roam the dark woods. More of them come out at night. Keep the Rotten Flesh."),
            new Step("forge_armor", 1, 0, null, 0, "Forge a piece of armor",
                    "The Blacksmith makes Graveyard armor from coins and Rotten Flesh. Armor gives Defense."),
            new Step("fish", 1, 1000, Skills.Skill.FISHING, 10, "Catch a fish",
                    "You got a Fishing Rod. Cast it into any water, {wharf} is the classic spot. Fishing gives Health."),
            new Step("level", 3, 0, null, 0, "Reach Combat 3",
                    "Every mob you slay gives Combat XP. Farming and Fishing add Health, Mining Defense, Foraging Strength."),
            new Step("kill_ruins", 5, 0, null, 0, "Hunt 5 beasts in {ruins}",
                    "Wolves and bone archers guard the ruins and hit hard. Come with a sword, armor and some skill levels."),
            new Step("bazaar", 1, 0, null, 0, "Make your first Bazaar trade",
                    "/bz: place a sell offer or a buy order, or fill one. Trades are onchain: your purse pays the STT gas, and at 0 STT the trade rolls back. /deposit"),
            new Step("auction", 1, 0, null, 0, "List or bid on an auction",
                    "Hold an item: /ah sell <start stt> [minutes], or bid in /ah. Bids, fees and gas come from your purse STT. /deposit"),
            new Step("level_farming", 5, 0, null, 0, "Reach Farming 5",
                    "Harvest crops in {farm} and wheat on your island."),
            new Step("discover", DISTRICTS.size(), 50, null, 0, "Discover every hub district",
                    "Walk into every district of the hub: farm, mine, woods, ruins, mountain, lake, wharf and more. /warp helps.")
    );

    /** The v1 line, for migrating saved progress. */
    private static final List<String> V1 = List.of("talk_wren", "island_oak", "island_cobble", "talk_quest", "hub_crop",
            "hub_ore", "hub_log", "sell", "kill_graveyard", "forge", "level", "kill_ruins", "bazaar", "auction", "level_farming");

    private static final Pattern TOKEN = Pattern.compile("\\{([a-z_]+)}");
    private static volatile UnaryOperator<String> zoneName = Zones::fallbackName;

    static String names(String text) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name;
            try {
                name = zoneName.apply(m.group(1));
            } catch (RuntimeException e) {
                name = Zones.fallbackName(m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(name == null ? m.group(1) : name));
        }
        m.appendTail(out);
        return out.toString();
    }

    private final SomniaSkyblockPlugin plugin;

    Quests(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        zoneName = id -> plugin.hub() == null ? Zones.fallbackName(id) : plugin.hub().name(id);
    }

    /** Steps finished: the ones behind questStep plus v1 steps already done further down the line. */
    static int completed(Profile profile) {
        return Math.min(STEPS.size(), profile.questStep + profile.questSkip.size());
    }

    /**
     * Steps that give SkyBlock XP: the finished ones plus finished steps that were later taken out of the line
     * (Profile.questRetired, e.g. a placed minion), so removing a step never takes XP away.
     */
    static int xpSteps(Profile profile) {
        return completed(profile) + Math.max(0, profile.questRetired);
    }

    static boolean done(Profile profile, int index) {
        return index < profile.questStep || (index < STEPS.size() && profile.questSkip.contains(STEPS.get(index).event()));
    }

    /**
     * Older quest lines -> the current one (v1, v2, v3 -> v4). Returns true when the profile changed. Pure (no Player),
     * safe to run while loading.
     */
    static boolean migrate(Profile profile) {
        if (profile.questVersion >= VERSION) {
            return false;
        }
        if (profile.questVersion == 2 || profile.questVersion == 3) {
            if (profile.questVersion == 2) {
                waveOut(profile);
            }
            minionOut(profile);
            profile.questVersion = VERSION;
            return true;
        }
        return fromV1(profile);
    }

    /** v2 -> v3 (questStep in v2 indexes). */
    private static void waveOut(Profile profile) {
        // The "wave" step (v2 index 17) is gone. It could never be finished (the Colosseum is sealed), so nobody is
        // past it; a player standing on it moves on to the next step with fresh progress.
        final int wave = 17;
        if (profile.questStep == wave) {
            profile.questProgress = 0;
        } else if (profile.questStep > wave) {
            profile.questStep--;
        }
        profile.questSkip.remove("wave");
    }

    /**
     * v3 -> v4 (questStep in v3 indexes): "place_minion" (v3 index 5) is gone. A player past it keeps its 5 SkyBlock
     * XP in questRetired and stays on the same next step; a player standing on it moves to the next step (it had no
     * reward yet, so nothing is lost or given twice).
     */
    private static void minionOut(Profile profile) {
        final int minion = 5;
        if (profile.questStep == minion) {
            profile.questProgress = 0;
        } else if (profile.questStep > minion) {
            profile.questStep--;
            profile.questRetired++;
        }
        if (profile.questSkip.remove("place_minion")) {
            profile.questRetired++;
        }
    }

    /** v1 -> current line. v1 had neither the wave nor the minion step. */
    private static boolean fromV1(Profile profile) {
        int oldStep = Math.max(0, Math.min(profile.questStep, V1.size()));
        int oldProgress = profile.questProgress;
        String oldCurrent = oldStep < V1.size() ? V1.get(oldStep) : null;
        Set<String> doneV2 = new java.util.HashSet<>();
        for (int i = 0; i < oldStep; i++) {
            String e = V1.get(i);
            if (e.equals("forge")) {
                doneV2.add("forge_sword");
                doneV2.add("forge_armor");
            } else {
                doneV2.add(e);
            }
        }
        int at = 0;
        while (at < STEPS.size() && doneV2.contains(STEPS.get(at).event())) {
            at++;
        }
        profile.questSkip.clear();
        for (int i = at + 1; i < STEPS.size(); i++) {
            if (doneV2.contains(STEPS.get(i).event())) {
                profile.questSkip.add(STEPS.get(i).event());
            }
        }
        profile.questStep = at;
        boolean same = at < STEPS.size() && oldCurrent != null && STEPS.get(at).event().equals(oldCurrent);
        profile.questProgress = same ? oldProgress : 0;
        profile.questVersion = VERSION;
        return true;
    }

    Step current(Profile profile) {
        return profile.questStep < STEPS.size() ? STEPS.get(profile.questStep) : null;
    }

    static int indexOf(String event) {
        for (int i = 0; i < STEPS.size(); i++) {
            if (STEPS.get(i).event().equals(event)) {
                return i;
            }
        }
        return -1;
    }

    String sidebarLine(Profile profile) {
        Step step = current(profile);
        if (step == null) {
            return "More quests SOON";
        }
        String text = step.title();
        if (text.length() > 26) {
            text = text.substring(0, 26);
        }
        return text;
    }

    /** "3/10" for counted steps; empty for single-action steps (talk, craft, place...). */
    String progress(Profile profile) {
        Step step = current(profile);
        if (step == null || step.goal() <= 1) {
            return "";
        }
        return Math.min(profile.questProgress, step.goal()) + "/" + step.goal();
    }

    /** " (3/10)" or "" — for callers that append the progress to a title. */
    String progressSuffix(Profile profile) {
        String p = progress(profile);
        return p.isEmpty() ? "" : " (" + p + ")";
    }

    private static boolean maxType(String event) {
        return event.equals("level") || event.equals("wave") || event.startsWith("level_") || event.equals("discover");
    }

    void hit(Player player, String event, int amount) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Step step = current(profile);
        if (step == null || !step.event().equals(event)) {
            return;
        }
        if (maxType(event)) {
            profile.questProgress = Math.max(profile.questProgress, amount);
        } else {
            profile.questProgress += amount;
        }
        plugin.stats().markDirty(player);
        if (profile.questProgress < step.goal()) {
            return;
        }
        profile.questStep++;
        profile.questProgress = 0;
        skipDone(profile);
        plugin.profiles().save(profile);
        Step next = current(profile);
        announce(player, step, next);
        // Rewards after the message: skill XP is queued in the onchain SkySkills batch by Skills.add.
        if (step.coins() > 0) {
            plugin.stats().addCoins(player, step.coins());
        }
        if (step.skill() != null && step.xp() > 0) {
            plugin.skills().add(player, step.skill(), step.xp());
        }
        plugin.getLogger().info("Quest step done for " + player.getName() + ": " + step.event() + " (" + step.rewardText() + ")");
        if (next != null) {
            started(player, profile, next);
        }
    }

    /** Steps the player already finished in the v1 line complete silently when reached (no second reward). */
    static void skipDone(Profile profile) {
        while (profile.questStep < STEPS.size() && profile.questSkip.remove(STEPS.get(profile.questStep).event())) {
            profile.questStep++;
            profile.questProgress = 0;
        }
    }

    /** A step became current: hand out its tool and complete it at once if it is already true. */
    private void started(Player player, Profile profile, Step next) {
        if (current(profile) != next) {
            return;
        }
        if (next.event().equals("fish")) {
            giveRod(player);
        }
        int have = already(player, profile, next.event());
        if (have > 0) {
            hit(player, next.event(), have);
        }
    }

    /** How much of a step is already true right now (levels, areas found, a pickaxe owned). */
    private int already(Player player, Profile profile, String event) {
        switch (event) {
            case "level":
                return Stats.combatLevel(profile.combatXp);
            case "discover":
                return explored(profile);
            case "craft_pickaxe":
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType().name().endsWith("_PICKAXE")) {
                        return 1;
                    }
                }
                return 0;
            default:
                if (event.startsWith("level_")) {
                    for (Skills.Skill skill : Skills.Skill.values()) {
                        if (event.equals("level_" + skill.name().toLowerCase(Locale.ROOT))) {
                            return Skills.level(profile, skill);
                        }
                    }
                }
                return 0;
        }
    }

    static int explored(Profile profile) {
        int n = 0;
        for (String id : DISTRICTS) {
            if (profile.discovered.contains(id)) {
                n++;
            }
        }
        return n;
    }

    /** On login: skip finished v1 steps, give the rod if the fishing step is current, apply what is already true. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(SbLoginEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Profile profile = plugin.profiles().get(player.getUniqueId());
            int before = profile.questStep;
            skipDone(profile);
            if (profile.questStep != before) {
                plugin.profiles().save(profile);
            }
            Step step = current(profile);
            if (step != null) {
                if (step.event().equals("fish") && !hasRod(player)) {
                    giveRod(player);
                }
                int have = already(player, profile, step.event());
                if (have > 0) {
                    hit(player, step.event(), have);
                }
            }
        }, 60L);
    }

    private static boolean hasRod(Player player) {
        return player.getInventory().contains(Material.FISHING_ROD);
    }

    private void giveRod(Player player) {
        if (hasRod(player)) {
            return;
        }
        plugin.market().giveOrStash(player.getUniqueId(), "minecraft:fishing_rod", 1, false);
        player.sendMessage(Component.text("You received a Fishing Rod.", NamedTextColor.GREEN));
    }

    /** OBJECTIVE COMPLETE / REWARDS / NEW OBJECTIVE chat block. */
    private void announce(Player player, Step done, Step next) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  OBJECTIVE COMPLETE", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        player.sendMessage(Component.text("  " + done.title(), NamedTextColor.WHITE));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  REWARDS", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        player.sendMessage(Component.text("    +" + SKYBLOCK_XP + " SkyBlock XP", NamedTextColor.AQUA));
        if (done.skill() != null && done.xp() > 0) {
            player.sendMessage(Component.text("    +" + done.xp() + " ", NamedTextColor.DARK_AQUA)
                    .append(Component.text(done.skill().label + " Experience", NamedTextColor.GRAY)));
        }
        if (done.coins() > 0) {
            player.sendMessage(Component.text("    +" + done.coins() + " Coins", NamedTextColor.GOLD));
        }
        player.sendMessage(Component.empty());
        if (next != null) {
            player.sendMessage(Component.text("  NEW OBJECTIVE", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            player.sendMessage(Component.text("  " + next.title(), NamedTextColor.WHITE));
            player.sendMessage(Component.text("  " + next.hint(), NamedTextColor.GRAY));
        } else {
            finale(player);
        }
        player.sendMessage(Component.empty());
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
    }

    /** End of the line: more quests SOON, and the donation address from the website footer. */
    static void finale(Player player) {
        player.sendMessage(Component.text("  You finished every quest we have so far. More quests SOON!", NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(Component.text("  Enjoying the server? Donations keep it running:", NamedTextColor.GRAY));
        // Same style as /deposit: underlined address, click copies it, hover says so, green [Copy].
        player.sendMessage(Component.text("  ")
                .append(Component.text(DONATE, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.copyToClipboard(DONATE))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to copy", NamedTextColor.YELLOW)))
                        .append(Component.text("  [Copy]", NamedTextColor.GREEN, TextDecoration.BOLD)
                                .decoration(TextDecoration.UNDERLINED, false))));
    }

    void show(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        player.sendMessage(Component.text("—— Quests (" + completed(profile) + "/" + STEPS.size() + ") ——", NamedTextColor.GOLD));
        for (int i = 0; i < STEPS.size(); i++) {
            Step step = STEPS.get(i);
            boolean done = done(profile, i);
            boolean now = i == profile.questStep;
            NamedTextColor color = done ? NamedTextColor.GREEN : now ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY;
            String mark = done ? "✔ " : now ? "➜ " : "· ";
            String extra = now ? progressSuffix(profile) + " — " + step.hint() : "";
            player.sendMessage(Component.text(mark + step.title() + "  " + step.rewardText() + extra, color));
        }
        if (current(profile) == null) {
            finale(player);
        } else if (current(profile).event().equals("fish") && !hasRod(player)) {
            giveRod(player);
        }
    }
}
