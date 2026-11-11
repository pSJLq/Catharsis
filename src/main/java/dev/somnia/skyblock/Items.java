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

    private Items() {
    }

    static ItemStack enchantedCobble(SomniaSkyblockPlugin plugin) {
        return enchanted(
                plugin,
                Material.COBBLESTONE,
                COBBLE,
                "Enchanted Cobblestone",
                "160 cobblestone compressed into one item."
        );
    }

    static ItemStack enchantedOak(SomniaSkyblockPlugin plugin) {
        return enchanted(
                plugin,
                Material.OAK_LOG,
                OAK,
                "Enchanted Oak Log",
                "160 oak logs compressed into one item."
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
        meta.displayName(Component.text(name, NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text(lore, NamedTextColor.GRAY),
                Component.text("SkyBlock purse · Somnia", NamedTextColor.DARK_GRAY)
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private static NamespacedKey key(SomniaSkyblockPlugin plugin) {
        return new NamespacedKey(plugin, "item");
    }
}
