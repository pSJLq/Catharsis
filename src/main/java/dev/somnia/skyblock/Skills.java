package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.entity.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * All SkyBlock skills in one place: XP tables, per-level stat perks, coin and SkyBlock XP rewards,
 * the "+6 Foraging (18/50)" action bar flash and the framed SKILL LEVEL UP chat block.
 * Every skill uses the wiki curve in {@link ProgressMath} ({@link Stats#COMBAT} is the same table).
 */
final class Skills implements Listener {
    enum Skill {
        COMBAT("Combat", Material.STONE_SWORD, "Bladewise", "Fight mobs in the hub's combat zones to earn Combat XP.", "❤ Health", 2, NamedTextColor.RED,
                "+0.5% ☣ Crit Chance", "Deal more damage to every mob."),
        MINING("Mining", Material.STONE_PICKAXE, "Deepdelver", "Mine ore in Darkvein Mine and cobblestone on your island.", "❈ Defense", 1, NamedTextColor.GREEN,
                "+1 ❈ Defense", "Ore and cobble drops count toward your collections."),
        FARMING("Farming", Material.GOLDEN_HOE, "Harvestkin", "Harvest ripe wheat on Goldmeadow Farm and your island.", "❤ Health", 2, NamedTextColor.RED,
                "+2 ❤ Health", "Crops regrow faster in your memory, not on the server."),
        FORAGING("Foraging", Material.STONE_AXE, "Timberheart", "Chop logs in Elderwood and the meadows.", "❁ Strength", 1, NamedTextColor.RED,
                "+1 ❁ Strength", "Hit harder with every swing."),
        FISHING("Fishing", Material.FISHING_ROD, "Tidecaller", "Cast a line at Driftwood Wharf or any water.", "❤ Health", 2, NamedTextColor.RED,
                "+2 ❤ Health", "Every catch earns coins and Fishing XP."),
        ENCHANTING("Enchanting", Material.ENCHANTING_TABLE, "Runebinder", "Enchant items at an enchanting table to earn Enchanting XP.", "✎ Intelligence", 1, NamedTextColor.AQUA,
                "+1 ✎ Intelligence", "More mana for item abilities."),
        CARPENTRY("Carpentry", Material.CRAFTING_TABLE, "Truegrain", "Craft items at a crafting table to earn Carpentry XP.", "❤ Health", 1, NamedTextColor.RED,
                "+1 ❤ Health", "Every crafted stack teaches you something.");

        final String label;
        final Material icon;
        final String perk;
        final String blurb;
        final String stat;
        final int statPerLevel;
        final NamedTextColor statColor;
        final String statLine;
        final String note;

        Skill(String label, Material icon, String perk, String blurb, String stat, int statPerLevel, NamedTextColor statColor, String statLine, String note) {
            this.label = label;
            this.icon = icon;
            this.perk = perk;
            this.blurb = blurb;
            this.stat = stat;
            this.statPerLevel = statPerLevel;
            this.statColor = statColor;
            this.statLine = statLine;
            this.note = note;
        }
    }

    private final SomniaSkyblockPlugin plugin;

    Skills(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    static long xp(Profile p, Skill s) {
        return switch (s) {
            case COMBAT -> p.combatXp;
            case MINING -> p.miningXp;
            case FARMING -> p.farmingXp;
            case FORAGING -> p.foragingXp;
            case FISHING -> p.fishingXp;
            case ENCHANTING -> p.enchantingXp;
            case CARPENTRY -> p.carpentryXp;
        };
    }

    static void put(Profile p, Skill s, long v) {
        switch (s) {
            case COMBAT -> p.combatXp = v;
            case MINING -> p.miningXp = v;
            case FARMING -> p.farmingXp = v;
            case FORAGING -> p.foragingXp = v;
            case FISHING -> p.fishingXp = v;
            case ENCHANTING -> p.enchantingXp = v;
            case CARPENTRY -> p.carpentryXp = v;
        }
    }

    static int max(Skill s) {
        return s == Skill.COMBAT ? Stats.COMBAT.length - 1 : ProgressMath.maxLevel();
    }

    static int level(Profile p, Skill s) {
        return levelOf(s, xp(p, s));
    }

    static int levelOf(Skill s, long xp) {
        return s == Skill.COMBAT ? Stats.combatLevel(xp) : ProgressMath.level(xp);
    }

    /** Total XP needed to reach a level. */
    static long start(Skill s, int level) {
        if (s == Skill.COMBAT) {
            return Stats.COMBAT[Math.max(0, Math.min(level, Stats.COMBAT.length - 1))];
        }
        return ProgressMath.levelStart(level);
    }

    static long coinsFor(int level) {
        return ProgressMath.coinsFor(level);
    }

    static int sbXpFor(int level) {
        return ProgressMath.sbXpFor(level);
    }

    /**
     * Stat granted by reaching one level of a skill (wiki skill reward tables):
     * Farming / Fishing Health +2 (1-14), +3 (15-19), +4 (20-25), +5 (26+); Carpentry Health +1;
     * Mining Defense, Foraging Strength, Enchanting Intelligence +1 (1-14), +2 (15+). Combat grants Crit Chance (Stats.sheet).
     */
    static int statGain(Skill s, int level) {
        if (level <= 0) {
            return 0;
        }
        return switch (s) {
            case FARMING, FISHING -> level <= 14 ? 2 : level <= 19 ? 3 : level <= 25 ? 4 : 5;
            case CARPENTRY -> 1;
            case MINING, FORAGING, ENCHANTING -> level <= 14 ? 1 : 2;
            case COMBAT -> 0;
        };
    }

    static int statTotal(Skill s, int level) {
        int total = 0;
        for (int l = 1; l <= level; l++) {
            total += statGain(s, l);
        }
        return total;
    }

    /** Sum of stat perks a profile has earned from skills. */
    static int perk(Profile p, String stat) {
        int total = 0;
        for (Skill s : Skill.values()) {
            if (s == Skill.COMBAT) {
                continue; // Combat perks live in Stats.sheet
            }
            if (s.stat.equals(stat)) {
                total += statTotal(s, level(p, s));
            }
        }
        return total;
    }

    /** SkyBlock XP: skill levels, collection tiers, quests and discovered areas. Level = XP / 100. */
    /** SkyBlock XP as shown in game: collection tiers count the whole co-op's collection (shared, like Hypixel). */
    static int skyblockXp(Profile p) {
        return skyblockXp(p, true);
    }

    /** The same XP with only this player's own collection contributions; the event leaderboard ranks on this. */
    static int skyblockXpOwn(Profile p) {
        return skyblockXp(p, false);
    }

    static int skyblockXp(Profile p, boolean shared) {
        int xp = 0;
        for (Skill s : Skill.values()) {
            int level = level(p, s);
            for (int l = 1; l <= level; l++) {
                xp += sbXpFor(l);
            }
        }
        for (Collects.Entry entry : Collects.ENTRIES) {
            xp += 4 * ProgressMath.tier(shared ? Collects.shared(p, entry) : Collects.amount(p, entry));
        }
        xp += Quests.SKYBLOCK_XP * Quests.xpSteps(p);
        for (String key : p.discovered) {
            if (!key.contains(":")) {
                xp += 5;
            }
        }
        return xp;
    }

    static int skyblockLevel(Profile p) {
        return skyblockXp(p) / 100;
    }

    static NamedTextColor levelColor(int level) {
        if (level >= 200) return NamedTextColor.AQUA;
        if (level >= 160) return NamedTextColor.DARK_GREEN;
        if (level >= 120) return NamedTextColor.GREEN;
        if (level >= 80) return NamedTextColor.YELLOW;
        if (level >= 40) return NamedTextColor.WHITE;
        if (level >= 10) return NamedTextColor.GRAY;
        return NamedTextColor.DARK_GRAY;
    }

    /**
     * Adds XP in tenths (pumpkin is +4.5 Farming). Whole XP goes to the profile and the onchain batch at once;
     * the leftover tenth stays on the profile until it adds up to a whole point, so the chain value stays an integer.
     */
    void addTenths(Player player, Skill skill, long tenths) {
        if (tenths <= 0) {
            return;
        }
        if (tenths % 10 == 0) {
            add(player, skill, tenths / 10, null);
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        long whole;
        synchronized (profile) {
            long sum = profile.xpTenths[skill.ordinal()] + tenths;
            whole = sum / 10;
            profile.xpTenths[skill.ordinal()] = (int) (sum % 10);
        }
        String shown = (tenths / 10) + "." + (tenths % 10);
        if (whole > 0) {
            add(player, skill, whole, shown);
        } else {
            plugin.stats().flash(player, Component.text("+" + shown + " " + skill.label, NamedTextColor.DARK_AQUA), 2000);
        }
    }

    /** Adds XP to a skill (main thread), flashes the action bar and announces level-ups. */
    void add(Player player, Skill skill, long amount) {
        add(player, skill, amount, null);
    }

    private void add(Player player, Skill skill, long amount, String shownAmount) {
        if (amount <= 0) {
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        int before;
        int after;
        long now;
        synchronized (profile) {
            long old = xp(profile, skill);
            before = levelOf(skill, old);
            now = old + amount;
            put(profile, skill, now);
            after = levelOf(skill, now);
            plugin.skillChain().queueXp(profile, skill, amount);
        }
        int max = max(skill);
        String progress;
        if (after >= max) {
            progress = "MAX";
        } else {
            long from = start(skill, after);
            long to = start(skill, after + 1);
            progress = String.format(java.util.Locale.ROOT, "%,d", now - from) + "/" + compact(to - from);
        }
        plugin.stats().flash(player, Component.text("+" + (shownAmount != null ? shownAmount : String.valueOf(amount)) + " " + skill.label + " (" + progress + ")", NamedTextColor.DARK_AQUA), 2000);
        plugin.stats().markDirty(player);
        if (after > before) {
            levelUp(player, profile, skill, before, after);
        }
    }

    static String compact(long v) {
        if (v >= 1_000_000) return (v % 1_000_000 == 0 ? String.valueOf(v / 1_000_000) : String.format(java.util.Locale.ROOT, "%.1f", v / 1_000_000.0)) + "M";
        if (v >= 10_000) return (v % 1000 == 0 ? String.valueOf(v / 1000) : String.format(java.util.Locale.ROOT, "%.1f", v / 1000.0)) + "k";
        return String.format(java.util.Locale.ROOT, "%,d", v);
    }

    private void levelUp(Player player, Profile profile, Skill skill, int before, int after) {
        long coins = 0;
        for (int l = before + 1; l <= after; l++) {
            coins += coinsFor(l);
        }
        plugin.stats().addCoins(player, coins);
        plugin.profiles().save(profile);
        Chat.separator(player, NamedTextColor.DARK_AQUA);
        player.sendMessage(Component.text("  SKILL LEVEL UP ", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true)
                .append(Component.text(skill.label + " ", NamedTextColor.DARK_AQUA).decoration(TextDecoration.BOLD, false))
                .append(Component.text(before == 0 ? "" : ProgressMath.numeral(before), NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(before == 0 ? "" : "➜", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(ProgressMath.numeral(after), NamedTextColor.DARK_AQUA).decoration(TextDecoration.BOLD, false)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  REWARDS", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        for (Component line : rewards(skill, after)) {
            player.sendMessage(Component.text("    ").append(line));
        }
        Chat.separator(player, NamedTextColor.DARK_AQUA);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        if (skill == Skill.COMBAT) {
            plugin.stats().checkpoint(player, after);
            plugin.quests().hit(player, "level", after);
        }
        plugin.quests().hit(player, "level_" + skill.name().toLowerCase(java.util.Locale.ROOT), after);
    }

    /** Reward lines for reaching a level (shared by chat and the GUIs). */
    static List<Component> rewards(Skill skill, int level) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(skill.perk + " " + ProgressMath.numeral(level), NamedTextColor.YELLOW));
        if (skill == Skill.COMBAT) {
            out.add(Component.text("  Deal ", NamedTextColor.WHITE).append(Component.text((level - 1) * 4 + "➜", NamedTextColor.DARK_GRAY))
                    .append(Component.text(level * 4 + "%", NamedTextColor.GREEN)).append(Component.text(" more damage to mobs.", NamedTextColor.WHITE)));
            out.add(Component.text("  +0.5% ", NamedTextColor.DARK_GRAY).append(Component.text("☣ Crit Chance", NamedTextColor.BLUE)));
        } else {
            out.add(Component.text("  +" + statTotal(skill, level - 1) + "➜" + statTotal(skill, level) + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(skill.stat, skill.statColor)));
        }
        out.add(Component.text("+", NamedTextColor.DARK_GRAY).append(Component.text(String.format(java.util.Locale.ROOT, "%,d", coinsFor(level)), NamedTextColor.GOLD))
                .append(Component.text(" Coins", NamedTextColor.GRAY)));
        out.add(Component.text("+" + sbXpFor(level) + " SkyBlock XP", NamedTextColor.AQUA));
        return out;
    }

    // ---------------------------------------------------------------- XP sources for the newer skills
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Player player = event.getPlayer();
        Material caught = event.getCaught() instanceof Item item ? item.getItemStack().getType() : null;
        add(player, Skill.FISHING, fishingXp(caught));
        plugin.quests().hit(player, "fish", 1);
        long coins = 3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(6);
        plugin.stats().addCoins(player, coins);
        if (event.getCaught() instanceof Item item) {
            Collects.add(plugin, player, item.getItemStack().getType(), item.getItemStack().getAmount());
        }
    }

    /** Fishing XP by catch (wiki item pages): Raw Cod 25, Pufferfish 50; Salmon / Tropical Fish 25 (provisional); anything else 12 (ours). */
    static long fishingXp(Material caught) {
        if (caught == null) {
            return 12;
        }
        return switch (caught) {
            case COD, SALMON, TROPICAL_FISH -> 25;
            case PUFFERFISH -> 50;
            default -> 12;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        add(event.getEnchanter(), Skill.ENCHANTING, 10L + event.getExpLevelCost() * 4L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && event.getRecipe() != null) {
            add(player, Skill.CARPENTRY, 2L * Math.max(1, event.getRecipe().getResult().getAmount()));
            if (event.getRecipe().getResult().getType().name().endsWith("_PICKAXE")) {
                plugin.quests().hit(player, "craft_pickaxe", 1);
            }
        }
    }
}
