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
        weapon("undead_sword", Material.IRON_SWORD, "Undead Sword", Rarity.COMMON, 30, 0, 0, 0,
                "Deals +100% damage to undead.");
        weapon("cleaver", Material.GOLDEN_SWORD, "Cleaver", Rarity.UNCOMMON, 40, 10, 0, 0,
                "A heavy butcher blade from the Hub smithy.");
        weapon("graveknight_blade", Material.IRON_SWORD, "Graveknight Blade", Rarity.RARE, 70, 25, 5, 20,
                "Forged from Revenant Flesh. Deals +50% damage to undead.");
        weapon("champion_blade", Material.DIAMOND_SWORD, "Champion's Blade", Rarity.LEGENDARY, 120, 60, 10, 50,
                "Rolled by Somnia VRF in the Colosseum, or forged from sigils.");
        armor("graveyard_helmet", Material.CHAINMAIL_HELMET, "Barrow Helmet", Rarity.COMMON, Slot.HELMET, 10, 10, 0);
        armor("graveyard_chestplate", Material.CHAINMAIL_CHESTPLATE, "Barrow Chestplate", Rarity.COMMON, Slot.CHESTPLATE, 25, 20, 0);
        armor("graveyard_leggings", Material.CHAINMAIL_LEGGINGS, "Barrow Leggings", Rarity.COMMON, Slot.LEGGINGS, 20, 15, 0);
        armor("graveyard_boots", Material.CHAINMAIL_BOOTS, "Barrow Boots", Rarity.COMMON, Slot.BOOTS, 10, 10, 0);
        armor("revenant_helmet", Material.IRON_HELMET, "Revenant Helmet", Rarity.RARE, Slot.HELMET, 30, 25, 5);
        armor("revenant_chestplate", Material.IRON_CHESTPLATE, "Revenant Chestplate", Rarity.RARE, Slot.CHESTPLATE, 70, 50, 5);
        armor("revenant_leggings", Material.IRON_LEGGINGS, "Revenant Leggings", Rarity.RARE, Slot.LEGGINGS, 55, 40, 5);
        armor("revenant_boots", Material.IRON_BOOTS, "Revenant Boots", Rarity.RARE, Slot.BOOTS, 30, 25, 5);
        material("revenant_flesh", Material.ROTTEN_FLESH, "Revenant Flesh", Rarity.UNCOMMON,
                "A rare drop from the Ashen Barrows and Hollow Crypts undead.");
        material("enchanted_rotten_flesh", Material.ROTTEN_FLESH, "Enchanted Rotten Flesh", Rarity.UNCOMMON,
                "160 rotten flesh compacted at the Blacksmith.");
        material("wolf_tooth", Material.GHAST_TEAR, "Wolf Tooth", Rarity.UNCOMMON,
                "Pulled from the Thornkeep Wolves.");
        material("colosseum_sigil", Material.HEART_OF_THE_SEA, "Colosseum Sigil", Rarity.EPIC,
                "Proof of a ranked run. Its roll is on Somnia.");
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
        meta.displayName(Component.text(kind.name(), kind.rarity().color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        stat(lore, "Damage", kind.damage(), "", NamedTextColor.RED);
        stat(lore, "Strength", kind.strength(), "", NamedTextColor.RED);
        stat(lore, "Crit Chance", kind.critChance(), "%", NamedTextColor.RED);
        stat(lore, "Crit Damage", kind.critDamage(), "%", NamedTextColor.RED);
        stat(lore, "Health", kind.health(), " HP", NamedTextColor.GREEN);
        stat(lore, "Defense", kind.defense(), "", NamedTextColor.GREEN);
        if (!kind.lore().isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(gray(kind.lore()));
        }
        lore.add(Component.empty());
        lore.add(Component.text(kind.rarity().name() + " " + typeName(kind.slot()), kind.rarity().color)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Stored in your SkyWord bag on Somnia", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (kind.rarity().ordinal() >= Rarity.RARE.ordinal() || kind.id().startsWith("enchanted_")) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "item"), PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
        return stack;
    }

    private static void stat(List<Component> lore, String name, int value, String suffix, NamedTextColor color) {
        if (value == 0) {
            return;
        }
        lore.add(Component.text(name + ": ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("+" + value + suffix, color)));
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static String typeName(Slot slot) {
        return switch (slot) {
            case WEAPON -> "SWORD";
            case HELMET -> "HELMET";
            case CHESTPLATE -> "CHESTPLATE";
            case LEGGINGS -> "LEGGINGS";
            case BOOTS -> "BOOTS";
            case MATERIAL -> "MATERIAL";
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
            case WOODEN_AXE, GOLDEN_AXE -> 15;
            case STONE_AXE -> 20;
            case IRON_AXE -> 25;
            case DIAMOND_AXE -> 30;
            case NETHERITE_AXE -> 35;
            case BOW -> 30;
            default -> 0;
        };
    }

    static int vanillaStrength(SomniaSkyblockPlugin plugin, ItemStack stack) {
        return isAspect(plugin, stack) ? 100 : 0;
    }

    static boolean isAspect(SomniaSkyblockPlugin plugin, ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_SWORD && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "aotv"), PersistentDataType.LONG);
    }

    static int vanillaDefense(Material type) {
        String name = type.name();
        int tier;
        if (name.startsWith("LEATHER_")) {
            tier = 1;
        } else if (name.startsWith("GOLDEN_") || name.startsWith("CHAINMAIL_")) {
            tier = 2;
        } else if (name.startsWith("IRON_")) {
            tier = 3;
        } else if (name.startsWith("DIAMOND_")) {
            tier = 5;
        } else if (name.startsWith("NETHERITE_")) {
            tier = 6;
        } else {
            return 0;
        }
        if (name.endsWith("_CHESTPLATE")) {
            return tier * 8;
        }
        if (name.endsWith("_LEGGINGS")) {
            return tier * 6;
        }
        if (name.endsWith("_HELMET") || name.endsWith("_BOOTS")) {
            return tier * 3;
        }
        return 0;
    }

    static List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(KINDS.keySet()));
    }
}
