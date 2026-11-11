package dev.somnia.skyblock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

final class CreativeBags {
    private final SomniaSkyblockPlugin plugin;

    CreativeBags(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isCreative(UUID id) {
        File file = file(id);
        if (!file.isFile()) {
            return false;
        }
        return "creative".equalsIgnoreCase(YamlConfiguration.loadConfiguration(file).getString("mode"));
    }

    void mode(UUID id, boolean creative) {
        YamlConfiguration yaml = read(id);
        yaml.set("mode", creative ? "creative" : "survival");
        write(id, yaml);
    }

    void save(Player player) {
        YamlConfiguration yaml = read(player.getUniqueId());
        yaml.set("mode", "creative");
        yaml.set("storage", null);
        yaml.set("armor", null);
        yaml.set("offhand", null);
        yaml.set("cursor", null);
        put(yaml, "storage", player.getInventory().getStorageContents());
        put(yaml, "armor", player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            yaml.set("offhand", offhand);
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            yaml.set("cursor", cursor);
        }
        write(player.getUniqueId(), yaml);
    }

    void load(Player player) {
        YamlConfiguration yaml = read(player.getUniqueId());
        ItemStack[] storage = take(yaml, "storage", 36);
        ItemStack[] armor = take(yaml, "armor", 4);
        ItemStack offhand = yaml.getItemStack("offhand");
        ItemStack cursor = yaml.getItemStack("cursor");
        HoldItems.makeUnbreakable(storage);
        HoldItems.makeUnbreakable(armor);
        HoldItems.makeUnbreakable(offhand);
        HoldItems.makeUnbreakable(cursor);
        player.getInventory().setStorageContents(storage);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(offhand);
        player.setItemOnCursor(cursor);
        player.updateInventory();
    }

    private ItemStack[] take(YamlConfiguration yaml, String key, int size) {
        ItemStack[] stacks = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            stacks[i] = yaml.getItemStack(key + "." + i);
        }
        return stacks;
    }

    private void put(YamlConfiguration yaml, String key, ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                yaml.set(key + "." + i, stack.clone());
            }
        }
    }

    private YamlConfiguration read(UUID id) {
        File file = file(id);
        if (!file.isFile()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void write(UUID id, YamlConfiguration yaml) {
        try {
            yaml.save(file(id));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save the creative inventory: " + exception.getMessage());
        }
    }

    private File file(UUID id) {
        File directory = new File(plugin.getDataFolder(), "creative");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            plugin.getLogger().warning("Could not create the creative inventory folder.");
        }
        return new File(directory, id + ".yml");
    }
}
