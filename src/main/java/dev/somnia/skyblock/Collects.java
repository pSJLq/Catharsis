package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Item collections: first pickup unlocks, tiers at {@link ProgressMath} thresholds, framed chat notices. */
final class Collects {
    enum Category { FARMING, MINING, COMBAT, FORAGING, FISHING }

    record Entry(String key, String name, Material icon, Category category, String reward) {
    }

    static final List<Entry> ENTRIES = List.of(
            new Entry("wheat", "Wheat", Material.WHEAT, Category.FARMING, "Bread recipes and Farmer Odo's trades"),
            new Entry("carrot", "Carrot", Material.CARROT, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("potato", "Potato", Material.POTATO, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("cobblestone", "Cobblestone", Material.COBBLESTONE, Category.MINING, "Enchanted Cobblestone at tier II"),
            new Entry("coal", "Coal", Material.COAL, Category.MINING, "Blacksmith fuel discounts"),
            new Entry("iron_ingot", "Iron Ingot", Material.IRON_INGOT, Category.MINING, "Iron gear at the Blacksmith"),
            new Entry("gold_ingot", "Gold Ingot", Material.GOLD_INGOT, Category.MINING, "Gilded gear at the Blacksmith"),
            new Entry("rotten_flesh", "Rotten Flesh", Material.ROTTEN_FLESH, Category.COMBAT, "Barrow armor recipes"),
            new Entry("bone", "Bone", Material.BONE, Category.COMBAT, "Bone Archer loot table"),
            new Entry("oak_log", "Oak Log", Material.OAK_LOG, Category.FORAGING, "Enchanted Oak Log at tier II"),
            new Entry("birch_log", "Birch Log", Material.BIRCH_LOG, Category.FORAGING, "Bazaar listing unlocked"),
            new Entry("dark_oak_log", "Dark Oak Log", Material.DARK_OAK_LOG, Category.FORAGING, "Bazaar listing unlocked"),
            new Entry("cod", "Raw Cod", Material.COD, Category.FISHING, "Fisher Hale's trades"),
            new Entry("salmon", "Raw Salmon", Material.SALMON, Category.FISHING, "Fisher Hale's trades"),
            // Appended for the hub resource zones (append only: the index is the SkySkills collection id).
            new Entry("sugar_cane", "Sugar Cane", Material.SUGAR_CANE, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("melon", "Melon", Material.MELON_SLICE, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("pumpkin", "Pumpkin", Material.PUMPKIN, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("cactus", "Cactus", Material.CACTUS, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("nether_wart", "Nether Wart", Material.NETHER_WART, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("cocoa_beans", "Cocoa Beans", Material.COCOA_BEANS, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("beetroot", "Beetroot", Material.BEETROOT, Category.FARMING, "Bazaar listing unlocked"),
            new Entry("lapis", "Lapis Lazuli", Material.LAPIS_LAZULI, Category.MINING, "Bazaar listing unlocked"),
            new Entry("redstone", "Redstone", Material.REDSTONE, Category.MINING, "Bazaar listing unlocked"),
            new Entry("diamond", "Diamond", Material.DIAMOND, Category.MINING, "Bazaar listing unlocked"),
            new Entry("emerald", "Emerald", Material.EMERALD, Category.MINING, "Bazaar listing unlocked"),
            new Entry("spruce_log", "Spruce Log", Material.SPRUCE_LOG, Category.FORAGING, "Bazaar listing unlocked"),
            new Entry("jungle_log", "Jungle Log", Material.JUNGLE_LOG, Category.FORAGING, "Bazaar listing unlocked"),
            new Entry("pale_oak_log", "Pale Oak Log", Material.PALE_OAK_LOG, Category.FORAGING, "Bazaar listing unlocked"),
            // Appended last: chain collection slots are indexed by position.
            new Entry("flowers", "Flowers", Material.POPPY, Category.FORAGING, "Flower trades and dyes")
    );

    private Collects() {
    }

    static Entry of(Material type) {
        Material m = switch (type) {
            case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
            case IRON_ORE, DEEPSLATE_IRON_ORE, RAW_IRON -> Material.IRON_INGOT;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, RAW_GOLD -> Material.GOLD_INGOT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case MELON -> Material.MELON_SLICE;
            case COCOA -> Material.COCOA_BEANS;
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
            case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND;
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
            case STONE, COBBLESTONE, MOSSY_COBBLESTONE, DEEPSLATE, COBBLED_DEEPSLATE, ANDESITE, DIORITE, GRANITE, TUFF,
                 CALCITE, DRIPSTONE_BLOCK, BASALT, SMOOTH_BASALT, BLACKSTONE -> Material.COBBLESTONE;
            default -> {
                if (HubManager.isFlower(type)) {
                    yield Material.POPPY;
                }
                String n = type.name();
                Material log = n.endsWith("_WOOD") && !n.startsWith("STRIPPED_") ? Material.matchMaterial(n.substring(0, n.length() - 5) + "_LOG") : null;
                yield log != null ? log : type;
            }
        };
        for (Entry e : ENTRIES) {
            if (e.icon() == m) {
                return e;
            }
        }
        return null;
    }

    /**
     * This player's own contribution on their current profile (kept by the server; the co-op total is onchain in
     * SkyProfile). Shown per member in the collection menu and used for the event score.
     */
    static long amount(Profile p, Entry e) {
        long extra = p.collections.getOrDefault(e.key(), 0L);
        return switch (e.key()) {
            case "cobblestone" -> p.cobble + extra;
            case "oak_log" -> p.oak + extra;
            case "wheat" -> p.wheat + extra;
            default -> extra;
        };
    }

    /**
     * The collection as the player sees it: the island's shared counter in SkyProfile (one per item per island deed,
     * the whole co-op) plus what members still have queued. Before the island is read it falls back to every member's
     * own count added up.
     */
    static long shared(Profile p, Entry e) {
        SkillChain chain = SkillChain.get();
        if (chain != null) {
            Long island = chain.sharedCollection(p, ENTRIES.indexOf(e));
            if (island != null) {
                return island; // SkyProfile: one onchain counter per island deed, plus every member's queue
            }
        }
        List<Profile> group = Coops.group(p);
        if (group.size() < 2) {
            return amount(p, e);
        }
        long sum = 0;
        for (Profile m : group) {
            sum += amount(m, e);
        }
        return sum;
    }

    /** Island counters mirrored from SkyIsland (cobble/oak/wheat), part of the SkySkills collection total. */
    static long islandPart(Profile p, Entry e) {
        return switch (e.key()) {
            case "cobblestone" -> p.cobble;
            case "oak_log" -> p.oak;
            case "wheat" -> p.wheat;
            default -> 0L;
        };
    }

    static int unlocked(Profile p) {
        int n = 0;
        for (Entry e : ENTRIES) {
            if (shared(p, e) > 0) {
                n++;
            }
        }
        return n;
    }

    /** Count an item toward its collection (main thread). */
    static void add(SomniaSkyblockPlugin plugin, Player player, Material type, long amount) {
        Entry e = of(type);
        if (e == null || amount <= 0) {
            return;
        }
        Profile p = plugin.profiles().get(player.getUniqueId());
        long before = shared(p, e);
        p.collections.merge(e.key(), amount, Long::sum);
        plugin.skillChain().queueCollection(p, e, amount);
        long after = shared(p, e);
        for (Player who : onlineGroup(p, player)) {
            announce(who, plugin.profiles().get(who.getUniqueId()), e, before, after);
        }
        plugin.stats().markDirty(player);
    }

    /** The picker plus every online co-op partner (shared collections, shared SkyBlock XP). */
    private static List<Player> onlineGroup(Profile p, Player picker) {
        List<Player> out = new ArrayList<>();
        out.add(picker);
        for (Profile m : Coops.group(p)) {
            if (m.uuid.equals(picker.getUniqueId())) {
                continue;
            }
            Player online = Bukkit.getPlayer(m.uuid);
            if (online != null && online.isOnline()) {
                out.add(online);
            }
        }
        return out;
    }

    /** Called after any collection counter moved (also for the chain-backed island counters). */
    static void announce(Player player, Profile p, Entry e, long before, long after) {
        if (before <= 0 && after > 0 && p.discovered.add("c:" + e.key())) {
            Chat.separator(player, NamedTextColor.GOLD);
            player.sendMessage(Component.text("  ▌ COLLECTION UNLOCKED ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    .append(Component.text(e.name(), NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false)));
            player.sendMessage(Component.text("  View it in the SkyBlock Menu ➜ Collections or /collection.", NamedTextColor.GRAY));
            Chat.separator(player, NamedTextColor.GOLD);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
            return;
        }
        int t0 = ProgressMath.tier(before);
        int t1 = ProgressMath.tier(after);
        if (t1 > t0) {
            Chat.separator(player, NamedTextColor.GOLD);
            player.sendMessage(Component.text("  ▌ COLLECTION LEVEL UP ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                    .append(Component.text(e.name() + " ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
                    .append(Component.text(ProgressMath.roman(t0) + "➜", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                    .append(Component.text(ProgressMath.roman(t1), NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false)));
            player.sendMessage(Component.text("  REWARDS", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
            player.sendMessage(Component.text("    +4 SkyBlock XP", NamedTextColor.AQUA));
            player.sendMessage(Component.text("    " + e.reward() + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("SOON", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, true)));
            Chat.separator(player, NamedTextColor.GOLD);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
        }
    }
}
