package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * One tooltip builder for every item, vanilla and custom, in the SkyBlock layout:
 * name in rarity colour, stat lines (gray label, red / green value), an optional gray description,
 * "Collection Item" for collection materials, then the bold "RARITY TYPE" line last. Wording is ours.
 * Applied idempotently through {@link HoldItems#makeUnbreakable(ItemStack)}, which every bag paint, craft,
 * pickup and inventory open already calls; a version stamp keeps it from rewriting a stack twice.
 */
final class Lore {
    static final int VERSION = 1;
    private static final NamespacedKey MARK = NamespacedKey.fromString("somniaskyblock:lore");
    private static final NamespacedKey ITEM = NamespacedKey.fromString("somniaskyblock:item");
    private static final NamespacedKey SELL = NamespacedKey.fromString("somniaskyblock:sellview");

    private Lore() {
    }

    // ------------------------------------------------------------------ rarity and type words
    static Gear.Rarity rarity(Material m) {
        String n = m.name();
        if (n.startsWith("DIAMOND_") && (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || armorPiece(m))) {
            return Gear.Rarity.UNCOMMON;
        }
        if (n.startsWith("CHAINMAIL_") || n.startsWith("NETHERITE_") && (armorPiece(m) || tool(m))) {
            return Gear.Rarity.UNCOMMON;
        }
        return Gear.Rarity.COMMON;
    }

    private static boolean armorPiece(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private static boolean tool(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
    }

    static String typeWord(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD")) return "SWORD";
        if (n.endsWith("_PICKAXE")) return "PICKAXE";
        if (n.endsWith("_AXE")) return "AXE";
        if (n.endsWith("_SHOVEL")) return "SHOVEL";
        if (n.endsWith("_HOE")) return "HOE";
        if (n.endsWith("_HELMET")) return "HELMET";
        if (n.endsWith("_CHESTPLATE")) return "CHESTPLATE";
        if (n.endsWith("_LEGGINGS")) return "LEGGINGS";
        if (n.endsWith("_BOOTS")) return "BOOTS";
        if (m == Material.BOW || m == Material.CROSSBOW) return "BOW";
        if (m == Material.FISHING_ROD) return "FISHING ROD";
        return m.isBlock() ? "BLOCK" : "ITEM";
    }

    /** True for a stack type that is itself a collection entry (Cobblestone, Carrot, Oak Log, any flower...). */
    static boolean collectionItem(Material m) {
        for (Collects.Entry entry : Collects.ENTRIES) {
            if (entry.icon() == m) {
                return true;
            }
        }
        return HubManager.isFlower(m);
    }

    // ------------------------------------------------------------------ line helpers
    static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static void stat(List<Component> lore, String name, int value, String suffix, NamedTextColor color) {
        if (value == 0) {
            return;
        }
        lore.add(plain(name + ": ", NamedTextColor.GRAY).append(Component.text("+" + value + suffix, color)));
    }

    static Component footer(Gear.Rarity rarity, String type) {
        return plain(type == null || type.isEmpty() ? rarity.name() : rarity.name() + " " + type, rarity.color).decoration(TextDecoration.BOLD, true);
    }

    // ------------------------------------------------------------------ builders
    /** Custom gear and drops (Gear.KINDS). */
    static void gear(ItemMeta meta, Gear.Kind kind) {
        meta.displayName(plain(kind.name(), kind.rarity().color));
        List<Component> lore = new ArrayList<>();
        stat(lore, "Damage", kind.damage(), "", NamedTextColor.RED);
        stat(lore, "Strength", kind.strength(), "", NamedTextColor.RED);
        stat(lore, "Crit Chance", kind.critChance(), "%", NamedTextColor.RED);
        stat(lore, "Crit Damage", kind.critDamage(), "%", NamedTextColor.RED);
        stat(lore, "Health", kind.health(), "", NamedTextColor.GREEN);
        stat(lore, "Defense", kind.defense(), "", NamedTextColor.GREEN);
        if (!kind.lore().isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            for (Component line : SbMenu.wrap(kind.lore(), NamedTextColor.GRAY)) {
                lore.add(line);
            }
        }
        lore.add(Component.empty());
        lore.add(footer(kind.rarity(), Gear.typeName(kind.slot())));
        meta.lore(lore);
        mark(meta);
    }

    /** Enchanted Cobblestone / Oak Log (Items). */
    static void enchanted(ItemMeta meta, String name, String description) {
        meta.displayName(plain(name, Gear.Rarity.UNCOMMON.color));
        List<Component> lore = new ArrayList<>(SbMenu.wrap(description, NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(footer(Gear.Rarity.UNCOMMON, "ITEM"));
        meta.lore(lore);
        mark(meta);
    }

    /** Any vanilla item: wiki stats for swords, axes, pickaxes, shovels, bows and armor. */
    static void vanilla(ItemStack stack, ItemMeta meta) {
        Material m = stack.getType();
        Gear.Rarity rarity = rarity(m);
        meta.displayName(Component.translatable(stack.translationKey()).color(rarity.color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        stat(lore, "Damage", Gear.vanillaDamage(null, stack), "", NamedTextColor.RED);
        stat(lore, "Defense", Gear.vanillaDefense(m), "", NamedTextColor.GREEN);
        if (collectionItem(m)) {
            lore.add(plain("Collection Item", NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        lore.add(footer(rarity, typeWord(m)));
        meta.lore(lore);
        mark(meta);
    }

    private static void mark(ItemMeta meta) {
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.INTEGER, VERSION);
    }

    /**
     * Merchant sell-mode tooltip, only while a shop is open: "Name xN", Collection Item, rarity, Sell Price, coins, Click to sell!
     * Undone by {@link #normalize(ItemStack)} (the shop close, any bag repaint, or the next login).
     */
    static boolean sellView(ItemStack stack, long unit) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        String kind = meta.getPersistentDataContainer().get(ITEM, PersistentDataType.STRING);
        Gear.Rarity rarity = rarityOf(stack);
        Component name;
        if (kind != null && Gear.KINDS.containsKey(kind)) {
            name = plain(Gear.KINDS.get(kind).name(), rarity.color);
        } else if (Items.COBBLE.equals(kind)) {
            name = plain("Enchanted Cobblestone", rarity.color);
        } else if (Items.OAK.equals(kind)) {
            name = plain("Enchanted Oak Log", rarity.color);
        } else if (kind == null) {
            name = Component.translatable(stack.translationKey()).color(rarity.color).decoration(TextDecoration.ITALIC, false);
        } else {
            return false;
        }
        int amount = stack.getAmount();
        if (amount > 1) {
            name = name.append(Component.text(" x" + amount, NamedTextColor.DARK_GRAY));
        }
        List<Component> lore = new ArrayList<>();
        if (kind == null && collectionItem(stack.getType())) {
            lore.add(plain("Collection Item", NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
        }
        lore.add(footer(rarity, ""));
        lore.add(Component.empty());
        lore.add(plain("Sell Price", NamedTextColor.GRAY));
        lore.add(plain(String.format(java.util.Locale.ROOT, "%,d", unit * amount) + " Coins", NamedTextColor.GOLD));
        lore.add(Component.empty());
        lore.add(plain("Click to sell!", NamedTextColor.YELLOW));
        Component oldName = meta.displayName();
        List<Component> oldLore = meta.lore();
        if (name.equals(oldName) && lore.equals(oldLore) && meta.getPersistentDataContainer().has(SELL, PersistentDataType.BYTE)) {
            return false;
        }
        meta.displayName(name);
        meta.lore(lore);
        meta.getPersistentDataContainer().set(SELL, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return true;
    }

    /** Rarity of any stack (custom kinds use their own). */
    static Gear.Rarity rarityOf(ItemStack stack) {
        String kind = kindOf(stack);
        if (kind != null) {
            Gear.Kind k = Gear.KINDS.get(kind);
            if (k != null) {
                return k.rarity();
            }
            return Gear.Rarity.UNCOMMON; // enchanted cobble / oak
        }
        return rarity(stack.getType());
    }

    static String kindOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(ITEM, PersistentDataType.STRING);
    }

    /**
     * Brings a stack's name and tooltip to the current layout. Returns true when the meta changed.
     * Never touches menu icons, the menu star, renamed items, books, potions, heads, maps or other special items.
     */
    static boolean normalize(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.getType().isItem()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Integer version = meta.getPersistentDataContainer().get(MARK, PersistentDataType.INTEGER);
        boolean selling = meta.getPersistentDataContainer().has(SELL, PersistentDataType.BYTE);
        if (selling) {
            meta.getPersistentDataContainer().remove(SELL);
            version = 0;
        }
        if (version != null && version == VERSION) {
            return false;
        }
        String kind = meta.getPersistentDataContainer().get(ITEM, PersistentDataType.STRING);
        if (kind != null) {
            Gear.Kind gear = Gear.KINDS.get(kind);
            if (gear != null) {
                gear(meta, gear);
            } else if (kind.equals(Items.COBBLE)) {
                enchanted(meta, "Enchanted Cobblestone", Items.COBBLE_TEXT);
            } else if (kind.equals(Items.OAK)) {
                enchanted(meta, "Enchanted Oak Log", Items.OAK_TEXT);
            } else {
                return false;
            }
            stack.setItemMeta(meta);
            return true;
        }
        if (selling) {
            vanilla(stack, meta);
            stack.setItemMeta(meta);
            return true;
        }
        if (version == null && (meta.hasDisplayName() || meta.hasLore() || meta.hasItemName())) {
            return false; // menu icons, the star, renamed or scripted items
        }
        if (meta instanceof PotionMeta || meta instanceof BookMeta || meta instanceof EnchantmentStorageMeta || meta instanceof SkullMeta
                || meta instanceof BannerMeta || meta instanceof MapMeta || meta instanceof FireworkMeta || meta instanceof BundleMeta
                || meta instanceof SuspiciousStewMeta || meta instanceof BlockStateMeta && !stack.getType().isBlock()
                || meta instanceof BlockStateMeta && stack.getType().name().endsWith("SHULKER_BOX")) {
            return false;
        }
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (!key.equals(MARK) && key.getNamespace().equals("somniaskyblock")) {
                return false; // aotv, craft previews and other plugin-owned stacks
            }
        }
        vanilla(stack, meta);
        stack.setItemMeta(meta);
        return true;
    }
}
