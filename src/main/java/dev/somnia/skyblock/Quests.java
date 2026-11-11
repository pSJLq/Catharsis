package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The intro quest line, given by Wren on the island and the Quest Giver in Lumenhold:
 * island basics → the hub resource zones → selling → combat → gear → bazaar and auctions.
 * Each finished step is worth 10 SkyBlock XP (Skills.skyblockXp counts questStep) plus the listed reward.
 * Skill XP rewards go through Skills.add, so they are queued in the same onchain SkySkills batch as any other XP.
 */
final class Quests {
    record Step(String event, int goal, long coins, Skills.Skill skill, long xp, String title, String hint) {
        String rewardText() {
            StringBuilder b = new StringBuilder("+" + SKYBLOCK_XP + " SkyBlock XP");
            if (skill != null && xp > 0) {
                b.append(", +").append(xp).append(' ').append(skill.label).append(" XP");
            }
            if (coins > 0) {
                b.append(", +").append(coins).append(" Coins");
            }
            return b.toString();
        }
    }

    static final int SKYBLOCK_XP = 10;

    static final List<Step> STEPS = List.of(
            new Step("talk_wren", 1, 25, null, 0, "Talk to Wren, your Island Guide", "Wren stands next to your island spawn. Click her."),
            new Step("island_oak", 5, 0, Skills.Skill.FORAGING, 30, "Chop 5 Oak Logs on your island", "Hit the tree on your island with the axe from the starter chest."),
            new Step("island_cobble", 10, 0, Skills.Skill.MINING, 30, "Mine 10 Cobblestone", "Your cobblestone generator regrows every block you mine."),
            new Step("talk_quest", 1, 50, null, 0, "Meet the Quest Giver in Lumenhold", "Take the island portal or type /hub. The Quest Giver waits south of the fountain."),
            new Step("hub_crop", 10, 0, Skills.Skill.FARMING, 100, "Harvest 10 crops in Goldmeadow", "Ripe wheat, carrots, potatoes, melons, pumpkins and sugar cane all count. They grow back."),
            new Step("hub_ore", 10, 0, Skills.Skill.MINING, 100, "Mine 10 ore in Darkvein Mine", "Coal needs any pickaxe, iron and lapis a stone pick. Mined ore and stone become bedrock at once and grow back after a while."),
            new Step("hub_log", 10, 0, Skills.Skill.FORAGING, 100, "Chop 10 logs in Elderglade", "Chopped trees disappear completely and grow back a little later."),
            new Step("sell", 1, 100, null, 0, "Sell something to a Merchant", "Any merchant NPC buys your items for coins."),
            new Step("kill_graveyard", 10, 0, Skills.Skill.COMBAT, 150, "Slay 10 undead in Dusk Thicket", "The undead roam the dark woods. More of them come out at night."),
            new Step("forge", 1, 100, null, 0, "Forge gear at the Blacksmith", "The Blacksmith in Lumenhold forges swords and armor for coins and drops."),
            new Step("level", 3, 200, null, 0, "Reach Combat 3", "Every mob you slay gives Combat XP."),
            new Step("kill_ruins", 5, 0, Skills.Skill.COMBAT, 200, "Hunt 5 beasts in Greyfang Citadel", "Wolves and bone archers guard the ruined citadel."),
            new Step("bazaar", 1, 200, null, 0, "Make your first Bazaar trade", "Place a sell offer or buy order, or fill one. /bz"),
            new Step("auction", 1, 200, null, 0, "List or bid on an auction", "Hold an item: /ah sell <start stt> [minutes]. Or bid in /ah."),
            new Step("level_farming", 5, 500, null, 0, "Reach Farming 5", "Harvest crops in Goldmeadow and wheat on your island.")
    );

    private final SomniaSkyblockPlugin plugin;

    Quests(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
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
            return "Quest: Champion's path";
        }
        String text = step.title();
        if (text.length() > 26) {
            text = text.substring(0, 26);
        }
        return text;
    }

    String progress(Profile profile) {
        Step step = current(profile);
        if (step == null) {
            return "";
        }
        return Math.min(profile.questProgress, step.goal()) + "/" + step.goal();
    }

    void hit(Player player, String event, int amount) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        Step step = current(profile);
        if (step == null || !step.event().equals(event)) {
            return;
        }
        if (event.equals("level") || event.equals("wave") || event.startsWith("level_")) {
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
        if (next != null && current(profile) == next) {
            if (next.event().equals("level")) {
                hit(player, "level", Stats.combatLevel(profile.combatXp));
            } else if (next.event().startsWith("level_")) {
                for (Skills.Skill skill : Skills.Skill.values()) {
                    if (next.event().equals("level_" + skill.name().toLowerCase(java.util.Locale.ROOT))) {
                        hit(player, next.event(), Skills.level(profile, skill));
                    }
                }
            }
        }
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
            player.sendMessage(Component.text("  You finished the intro path. Keep leveling your skills and collections!", NamedTextColor.LIGHT_PURPLE));
        }
        player.sendMessage(Component.empty());
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
    }

    void show(Player player) {
        Profile profile = plugin.profiles().get(player.getUniqueId());
        player.sendMessage(Component.text("—— Quests ——", NamedTextColor.GOLD));
        for (int i = 0; i < STEPS.size(); i++) {
            Step step = STEPS.get(i);
            NamedTextColor color = i < profile.questStep ? NamedTextColor.GREEN : i == profile.questStep ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY;
            String mark = i < profile.questStep ? "✔ " : i == profile.questStep ? "➜ " : "· ";
            String extra = i == profile.questStep ? " (" + progress(profile) + ") — " + step.hint() : "";
            player.sendMessage(Component.text(mark + step.title() + "  " + step.rewardText() + extra, color));
        }
    }
}
