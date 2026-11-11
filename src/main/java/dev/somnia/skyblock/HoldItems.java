package dev.somnia.skyblock;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class HoldItems {
    static final int BAG = 41;
    static final int CHEST = 54;

    private static final Map<Integer, String> KEYS = new HashMap<>();

    static {
        remember(custom(Items.COBBLE));
        remember(custom(Items.OAK));
        for (String kind : Gear.KINDS.keySet()) {
            remember(custom(kind));
        }
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir()) {
                continue;
            }
            remember(material.getKey().asString());
        }
    }

    private HoldItems() {
    }

    static int id(ItemStack stack, SomniaSkyblockPlugin plugin) {
        String key = key(stack, plugin);
        if (key == null) {
            return 0;
        }
        return fnv(key);
    }

    static int damage(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || !(stack.getItemMeta() instanceof Damageable damageable)) {
            return 0;
        }
        return Math.max(0, damageable.getDamage());
    }

    /** Normalizes a stack: no durability loss, and the SkyBlock tooltip layout ({@link Lore}). True when it changed. */
    static boolean makeUnbreakable(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        boolean changed = solid(stack);
        changed |= Lore.normalize(stack);
        return changed;
    }

    private static boolean solid(ItemStack stack) {
        if (stack.getType().getMaxDurability() <= 0) {
            return stripDurability(stack);
        }
        if (!(stack.getItemMeta() instanceof Damageable damageable)) {
            return false;
        }
        if (damageable.getDamage() == 0 && damageable.isUnbreakable()) {
            return false;
        }
        damageable.setDamage(0);
        damageable.setUnbreakable(true);
        stack.setItemMeta(damageable);
        return true;
    }

    static boolean compact(ItemStack[] contents) {
        boolean changed = false;
        if (contents == null) {
            return false;
        }
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0 || stack.getMaxStackSize() <= 1) {
                continue;
            }
            for (int j = 0; j < i && stack.getAmount() > 0; j++) {
                ItemStack into = contents[j];
                if (into == null || into.getAmount() <= 0 || into.getAmount() >= into.getMaxStackSize() || !into.isSimilar(stack)) {
                    continue;
                }
                int move = Math.min(into.getMaxStackSize() - into.getAmount(), stack.getAmount());
                if (move <= 0) {
                    continue;
                }
                into.setAmount(into.getAmount() + move);
                stack.setAmount(stack.getAmount() - move);
                changed = true;
            }
            if (stack.getAmount() <= 0) {
                contents[i] = null;
                changed = true;
            }
        }
        return changed;
    }

    private static boolean stripDurability(ItemStack stack) {
        if (!stack.hasItemMeta() || !(stack.getItemMeta() instanceof Damageable damageable)) {
            return false;
        }
        if (!damageable.isUnbreakable() && !damageable.hasDamageValue() && !damageable.hasMaxDamage()) {
            return false;
        }
        damageable.setUnbreakable(false);
        damageable.resetDamage();
        if (damageable.hasMaxDamage()) {
            damageable.setMaxDamage(null);
        }
        stack.setItemMeta(damageable);
        return true;
    }

    static boolean makeUnbreakable(ItemStack[] stacks) {
        boolean changed = false;
        if (stacks == null) {
            return false;
        }
        for (ItemStack stack : stacks) {
            changed |= makeUnbreakable(stack);
        }
        return changed;
    }

    static final class View {
        final int[] items;
        final int[] counts;
        final int[] damages;
        final long[] gears;

        View(int[] items, int[] counts, int[] damages, long[] gears) {
            this.items = items;
            this.counts = counts;
            this.damages = damages;
            this.gears = gears;
        }

        boolean any() {
            for (int count : counts) {
                if (count > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean unique(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getMaxStackSize() <= 1;
    }

    static long gearOf(SomniaSkyblockPlugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return 0L;
        }
        Long gear = stack.getItemMeta().getPersistentDataContainer().get(key(plugin, "gear"), PersistentDataType.LONG);
        return gear == null ? 0L : gear;
    }

    static void stamp(SomniaSkyblockPlugin plugin, ItemStack stack, long gear) {
        if (stack == null || gear <= 0) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(key(plugin, "gear"), PersistentDataType.LONG, gear);
        stack.setItemMeta(meta);
    }

    static String texture(ItemStack stack, SomniaSkyblockPlugin plugin) {
        String full = key(stack, plugin);
        if (full == null) {
            return "";
        }
        int colon = full.indexOf(':');
        return colon < 0 ? full : full.substring(colon + 1);
    }

    static String label(ItemStack stack, SomniaSkyblockPlugin plugin) {
        String texture = texture(stack, plugin);
        if (texture.isEmpty()) {
            return "Item";
        }
        String[] words = texture.split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                label.append(word.substring(1));
            }
        }
        return label.toString();
    }

    static String reforge(SomniaSkyblockPlugin plugin, ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return "";
        }
        String value = stack.getItemMeta().getPersistentDataContainer().get(key(plugin, "reforge"), PersistentDataType.STRING);
        return value == null ? "" : value;
    }

    static String enchants(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || stack.getEnchantments().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (var entry : stack.getEnchantments().entrySet()) {
            Enchantment enchantment = entry.getKey();
            String name = enchantment.getKey().getKey().replace('_', ' ');
            parts.add(Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + entry.getValue());
        }
        Collections.sort(parts);
        return String.join(", ", parts);
    }

    private static NamespacedKey key(SomniaSkyblockPlugin plugin, String name) {
        return new NamespacedKey(plugin, name);
    }

    static ItemStack stack(SomniaSkyblockPlugin plugin, int id, int count, int damage) {
        if (id == 0 || count <= 0) {
            return null;
        }
        String key = KEYS.get(id);
        if (key == null) {
            return null;
        }
        ItemStack stack;
        if (key.equals(custom(Items.COBBLE))) {
            stack = Items.enchantedCobble(plugin);
        } else if (key.equals(custom(Items.OAK))) {
            stack = Items.enchantedOak(plugin);
        } else if (key.startsWith("skyblock:") && Gear.has(key.substring(9))) {
            stack = Gear.create(plugin, key.substring(9), 1);
        } else {
            NamespacedKey namespaced = NamespacedKey.fromString(key);
            Material material = namespaced == null ? null : Registry.MATERIAL.get(namespaced);
            if (material == null || material.isAir()) {
                return null;
            }
            stack = new ItemStack(material);
        }
        stack.setAmount(Math.min(count, stack.getMaxStackSize()));
        makeUnbreakable(stack);
        return stack;
    }

    static String keyOf(ItemStack stack, SomniaSkyblockPlugin plugin) {
        return key(stack, plugin);
    }

    static int idOfKey(String key) {
        return fnv(key);
    }

    static ItemStack fromKey(SomniaSkyblockPlugin plugin, String key, int count) {
        return stack(plugin, fnv(key), count, 0);
    }

    private static String key(ItemStack stack, SomniaSkyblockPlugin plugin) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        String kind = Items.kind(stack, plugin);
        if (kind != null) {
            return custom(kind);
        }
        return stack.getType().getKey().asString();
    }

    private static String custom(String kind) {
        return "skyblock:" + kind;
    }

    private static void remember(String key) {
        int id = fnv(key);
        String previous = KEYS.putIfAbsent(id, key);
        if (previous != null && !previous.equals(key)) {
            throw new IllegalStateException("Item id collision for " + key + " and " + previous);
        }
    }

    private static int fnv(String key) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x01000193;
        }
        hash &= 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }
}
