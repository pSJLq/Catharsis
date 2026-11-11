package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

final class Items {
    static final String COBBLE = "enchanted_cobble";
    static final String OAK = "enchanted_oak";
    static final String COBBLE_TEXT = "160 cobblestone pressed into one dense block.";
    static final String OAK_TEXT = "160 oak logs bound into one heavy bundle.";

    private Items() {
    }

    static ItemStack enchantedCobble(SomniaSkyblockPlugin plugin) {
        return enchanted(
                plugin,
                Material.COBBLESTONE,
                COBBLE,
                "Enchanted Cobblestone",
                COBBLE_TEXT
        );
    }

    static ItemStack enchantedOak(SomniaSkyblockPlugin plugin) {
        return enchanted(
                plugin,
                Material.OAK_LOG,
                OAK,
                "Enchanted Oak Log",
                OAK_TEXT
        );
    }

    static boolean isEnchanted(ItemStack stack, SomniaSkyblockPlugin plugin) {
        return kind(stack, plugin) != null;
    }

    static String kind(ItemStack stack, SomniaSkyblockPlugin plugin) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
    }

    private static ItemStack enchanted(SomniaSkyblockPlugin plugin, Material material, String id, String name, String lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, id);
        Lore.enchanted(meta, name, lore);
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        return stack;
    }

    private static NamespacedKey key(SomniaSkyblockPlugin plugin) {
        return new NamespacedKey(plugin, "item");
    }
}
