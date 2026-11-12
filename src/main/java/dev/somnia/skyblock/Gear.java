package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom SkyBlock items. Each one is a "skyblock:<id>" key in the on-chain SkyWord bag,
 * so a sword or a drop is stored on Somnia like any other slot.
 */
final class Gear {
    enum Rarity {
        COMMON(NamedTextColor.WHITE),
        UNCOMMON(NamedTextColor.GREEN),
        RARE(NamedTextColor.BLUE),
        EPIC(NamedTextColor.DARK_PURPLE),
        LEGENDARY(NamedTextColor.GOLD);

        final NamedTextColor color;

        Rarity(NamedTextColor color) {
            this.color = color;
        }
    }

    enum Slot { WEAPON, HELMET, CHESTPLATE, LEGGINGS, BOOTS, MATERIAL }

    record Kind(String id, Material material, String name, Rarity rarity, Slot slot,
                int damage, int strength, int critChance, int critDamage, int health, int defense,
                String lore) {
        boolean weapon() {
            return slot == Slot.WEAPON;
        }

        boolean armor() {
            return slot == Slot.HELMET || slot == Slot.CHESTPLATE || slot == Slot.LEGGINGS || slot == Slot.BOOTS;
        }

        String key() {
            return "skyblock:" + id;
        }
    }

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    static {
        weapon("undead_sword", Material.IRON_SWORD, "Barrow Blade", Rarity.COMMON, 30, 0, 0, 0,
                "Hits the restless dead twice as hard.");
        weapon("cleaver", Material.GOLDEN_SWORD, "Butcher's Blade", Rarity.UNCOMMON, 40, 10, 0, 0,
                "A heavy butcher blade from the Hub smithy.");
        weapon("graveknight_blade", Material.IRON_SWORD, "Graveknight Blade", Rarity.RARE, 70, 25, 5, 20,
                "Forged from Barrow Flesh. Hits undead 50% harder.");
        weapon("champion_blade", Material.DIAMOND_SWORD, "Champion's Blade", Rarity.LEGENDARY, 120, 60, 10, 50,
                "Rolled by Somnia VRF in the Colosseum, or forged from sigils.");
        armor("graveyard_helmet", Material.CHAINMAIL_HELMET, "Barrow Helmet", Rarity.COMMON, Slot.HELMET, 10, 10, 0);
        armor("graveyard_chestplate", Material.CHAINMAIL_CHESTPLATE, "Barrow Chestplate", Rarity.COMMON, Slot.CHESTPLATE, 25, 20, 0);
        armor("graveyard_leggings", Material.CHAINMAIL_LEGGINGS, "Barrow Leggings", Rarity.COMMON, Slot.LEGGINGS, 20, 15, 0);
        armor("graveyard_boots", Material.CHAINMAIL_BOOTS, "Barrow Boots", Rarity.COMMON, Slot.BOOTS, 10, 10, 0);
        armor("revenant_helmet", Material.IRON_HELMET, "Hollowguard Helmet", Rarity.RARE, Slot.HELMET, 30, 25, 5);
        armor("revenant_chestplate", Material.IRON_CHESTPLATE, "Hollowguard Chestplate", Rarity.RARE, Slot.CHESTPLATE, 70, 50, 5);
        armor("revenant_leggings", Material.IRON_LEGGINGS, "Hollowguard Leggings", Rarity.RARE, Slot.LEGGINGS, 55, 40, 5);
        armor("revenant_boots", Material.IRON_BOOTS, "Hollowguard Boots", Rarity.RARE, Slot.BOOTS, 30, 25, 5);
        material("revenant_flesh", Material.ROTTEN_FLESH, "Barrow Flesh", Rarity.UNCOMMON,
                "A rare drop from the Ashen Barrows and Hollow Crypts undead.");
        material("enchanted_rotten_flesh", Material.ROTTEN_FLESH, "Enchanted Rotten Flesh", Rarity.UNCOMMON,
                "160 rotten flesh compacted at the Blacksmith.");
        material("wolf_tooth", Material.GHAST_TEAR, "Thornkeep Fang", Rarity.UNCOMMON,
                "Pulled from the Thornkeep Wolves.");
        material("colosseum_sigil", Material.HEART_OF_THE_SEA, "Colosseum Sigil", Rarity.EPIC,
                "Proof of a ranked run. Its roll is on Somnia.");
        material(Cookies.ID, Material.COOKIE, "Booster Cookie", Rarity.LEGENDARY,
                "Right-click to eat: 4 days of /ah, /bz and /bank anywhere, selling from the menu, flight on your island and free gas. Bought with SOMI in /cookie. Resell it on the Bazaar for STT.");
    }

    private Gear() {
    }

    private static void weapon(String id, Material material, String name, Rarity rarity, int damage, int strength, int cc, int cd, String lore) {
        KINDS.put(id, new Kind(id, material, name, rarity, Slot.WEAPON, damage, strength, cc, cd, 0, 0, lore));
    }

    private static void armor(String id, Material material, String name, Rarity rarity, Slot slot, int defense, int health, int strength) {
        KINDS.put(id, new Kind(id, material, name, rarity, slot, 0, strength, 0, 0, health, defense, ""));
    }

    private static void material(String id, Material material, String name, Rarity rarity, String lore) {
        KINDS.put(id, new Kind(id, material, name, rarity, Slot.MATERIAL, 0, 0, 0, 0, 0, 0, lore));
    }

    static boolean has(String id) {
        return KINDS.containsKey(id);
    }

    static Kind of(SomniaSkyblockPlugin plugin, ItemStack stack) {
        String kind = Items.kind(stack, plugin);
        return kind == null ? null : KINDS.get(kind);
    }

    static boolean undeadBonus(Kind kind) {
        return kind != null && (kind.id().equals("undead_sword") || kind.id().equals("graveknight_blade"));
    }

    static double undeadMultiplier(Kind kind) {
        if (kind == null) {
            return 1.0;
        }
        if (kind.id().equals("undead_sword")) {
            return 2.0;
        }
        if (kind.id().equals("graveknight_blade")) {
            return 1.5;
        }
        return 1.0;
    }

    static ItemStack create(SomniaSkyblockPlugin plugin, String id, int amount) {
        Kind kind = KINDS.get(id);
        if (kind == null) {
            return null;
        }
        ItemStack stack = new ItemStack(kind.material());
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "item"), PersistentDataType.STRING, id);
        Lore.gear(meta, kind);
        if (kind.rarity().ordinal() >= Rarity.RARE.ordinal() || kind.id().startsWith("enchanted_")) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
        return stack;
    }

    static String typeName(Slot slot) {
        return switch (slot) {
            case WEAPON -> "SWORD";
            case HELMET -> "HELMET";
            case CHESTPLATE -> "CHESTPLATE";
            case LEGGINGS -> "LEGGINGS";
            case BOOTS -> "BOOTS";
            case MATERIAL -> "ITEM";
        };
    }

    /** Vanilla weapons keep familiar base damage so starter tools still matter. */
    static int vanillaDamage(SomniaSkyblockPlugin plugin, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        if (isAspect(plugin, stack)) {
            return 120;
        }
        return switch (stack.getType()) {
            case WOODEN_SWORD, GOLDEN_SWORD -> 20;
            case STONE_SWORD -> 25;
            case IRON_SWORD -> 30;
            case DIAMOND_SWORD -> 35;
            case NETHERITE_SWORD -> 40;
            // Wiki Axes: Wooden 10, Stone 15, Golden 20, Iron 25, Diamond 30. Netherite is ours.
            case WOODEN_AXE -> 10;
            case STONE_AXE -> 15;
            case GOLDEN_AXE -> 20;
            case IRON_AXE -> 25;
            case DIAMOND_AXE -> 30;
            case NETHERITE_AXE -> 35;
            // Wiki pickaxe / shovel infobox damage: Wooden and Golden 15, Stone 20, Iron 25, Diamond 30. Netherite is ours.
            case WOODEN_PICKAXE, GOLDEN_PICKAXE, WOODEN_SHOVEL, GOLDEN_SHOVEL -> 15;
            case STONE_PICKAXE, STONE_SHOVEL -> 20;
            case IRON_PICKAXE, IRON_SHOVEL -> 25;
            case DIAMOND_PICKAXE, DIAMOND_SHOVEL -> 30;
            case NETHERITE_PICKAXE, NETHERITE_SHOVEL -> 35;
            case BOW -> 30;
            default -> 0;
        };
    }

    static int vanillaStrength(SomniaSkyblockPlugin plugin, ItemStack stack) {
        return isAspect(plugin, stack) ? 100 : 0;
    }

    static boolean isAspect(SomniaSkyblockPlugin plugin, ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_SWORD && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(NamespacedKey.fromString("somniaskyblock:aotv"), PersistentDataType.LONG);
    }

    /** Wiki Armor table (Leather 6/15/10/5, Gold 10/25/15/5, Chainmail 12/30/20/7, Iron 12/30/25/10, Diamond 15/40/30/15). Netherite is ours. */
    static int vanillaDefense(Material type) {
        String name = type.name();
        int[] set; // helmet, chestplate, leggings, boots
        if (name.startsWith("LEATHER_")) {
            set = new int[]{6, 15, 10, 5};
        } else if (name.startsWith("GOLDEN_")) {
            set = new int[]{10, 25, 15, 5};
        } else if (name.startsWith("CHAINMAIL_")) {
            set = new int[]{12, 30, 20, 7};
        } else if (name.startsWith("IRON_")) {
            set = new int[]{12, 30, 25, 10};
        } else if (name.startsWith("DIAMOND_")) {
            set = new int[]{15, 40, 30, 15};
        } else if (name.startsWith("NETHERITE_")) {
            set = new int[]{20, 50, 40, 20};
        } else {
            return 0;
        }
        if (name.endsWith("_HELMET")) return set[0];
        if (name.endsWith("_CHESTPLATE")) return set[1];
        if (name.endsWith("_LEGGINGS")) return set[2];
        if (name.endsWith("_BOOTS")) return set[3];
        return 0;
    }

    static List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(KINDS.keySet()));
    }
}
