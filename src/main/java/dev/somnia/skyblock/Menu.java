package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

class Menu implements InventoryHolder {
    private Inventory inventory;

    static void open(Player player, Profile profile) {
        Menu holder = new Menu();
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("SkyBlock Menu"));
        holder.inventory = inventory;

        ItemStack glass = pane();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, glass);
        }

        inventory.setItem(4, item(Material.BOOK, "Your island", List.of(
                "Mine cobblestone, oak, and ripe wheat.",
                "Collection counts when the receipt lands.",
                "Open a crafting table for recipes.",
                "Enchanted craft cost: 160 plain items.",
                "/island takes you home."
        )));
        inventory.setItem(10, skill(Material.IRON_PICKAXE, "Mining", profile.miningXp));
        inventory.setItem(12, skill(Material.WHEAT, "Farming", profile.farmingXp));
        inventory.setItem(14, skill(Material.OAK_LOG, "Foraging", profile.foragingXp));
        inventory.setItem(19, collection(Material.COBBLESTONE, "Cobblestone", Collects.shared(profile, Collects.ENTRIES.get(3)), "Enchanted craft at 100"));
        inventory.setItem(21, collection(Material.OAK_LOG, "Oak", Collects.shared(profile, Collects.ENTRIES.get(9)), "Enchanted craft at 100"));
        inventory.setItem(23, collection(Material.WHEAT, "Wheat", Collects.shared(profile, Collects.ENTRIES.get(0)), "Collection only for now"));
        inventory.setItem(16, item(Material.GOLD_INGOT, "Purse", List.of(
                profile.wallet == null || profile.wallet.isBlank() ? "No purse yet" : profile.wallet,
                "You pay gas from this balance.",
                "/deposit and /withdraw",
                "Enchanted cobble: " + profile.enchantedCobble,
                "Enchanted oak: " + profile.enchantedOak
        )));
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private static ItemStack skill(Material material, String name, long xp) {
        int level = ProgressMath.level(xp);
        long next = ProgressMath.nextLevelXp(level);
        return item(material, name + " " + level, List.of(
                "XP: " + xp,
                level >= ProgressMath.maxLevel() ? "Top of this track." : "Next level: " + next
        ));
    }

    private static ItemStack collection(Material material, String name, long amount, String gate) {
        int tier = ProgressMath.tier(amount);
        return item(material, name + " " + ProgressMath.roman(tier), List.of(
                "Collected: " + amount,
                gate
        ));
    }

    private static ItemStack pane() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(" "));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        stack.setItemMeta(meta);
        return stack;
    }
}
