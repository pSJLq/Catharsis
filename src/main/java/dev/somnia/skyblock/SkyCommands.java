package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class SkyCommands implements CommandExecutor, TabCompleter {
    private final SomniaSkyblockPlugin plugin;

    SkyCommands(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        plugin.wallets().ensure(profile);
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sb")) {
            if (!profile.islandReady) {
                plugin.islands().ensureIsland(profile);
            }
            plugin.profiles().save(profile);
            plugin.sidebar().show(player, profile);
            plugin.menu().openMain(player);
            tellBalance(player, profile);
            return true;
        }
        if (name.equals("deposit")) {
            plugin.profiles().save(profile);
            player.sendMessage(Component.text("Purse on Somnia Shannon (chain 50312):", NamedTextColor.GOLD));
            player.sendMessage(Component.text(profile.wallet, NamedTextColor.AQUA, net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(profile.wallet))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to copy", NamedTextColor.YELLOW)))
                    .append(Component.text("  [Copy]", NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.BOLD)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.UNDERLINED, false)));
            player.sendMessage(Component.text("Free test STT: ", NamedTextColor.GRAY)
                    .append(Component.text("[Open the Somnia faucet]", NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(FAUCET))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(FAUCET + "\nPaste your copied address there.", NamedTextColor.YELLOW)))));
            String hint = plugin.wordMode()
                    ? "Send STT here. Breaks and teleports happen now and roll back if the signature fails. You pay the gas. /withdraw <amount> <address>"
                    : "Send STT here. Breaks stay instant. You pay the gas. /withdraw <amount> <address>";
            player.sendMessage(Component.text(hint, NamedTextColor.GRAY));
            tellBalance(player, profile);
            return true;
        }
        if (name.equals("withdraw")) {
            return withdraw(player, profile, args);
        }
        if (name.equals("trade")) {
            return plugin.trades().command(player, args);
        }
        if (name.equals("island") && args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            return plugin.islands().reset(player, profile, args.length > 1 && args[1].equalsIgnoreCase("confirm"));
        }
        if (name.equals("island")) {
            plugin.islands().ensureIsland(profile);
            plugin.islands().teleport(player, profile);
            player.setRespawnLocation(plugin.islands().home(profile), true);
            plugin.profiles().save(profile);
            player.sendMessage(Component.text("You are on your island.", NamedTextColor.GREEN));
            return true;
        }
        if (name.equals("hub")) {
            plugin.islands().ensureIsland(profile);
            plugin.hub().teleport(player);
            plugin.hub().greet(player, profile);
            player.sendMessage(Component.text("You are in the hub. /is goes home.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Use /craft cobble, /craft oak, or /craft void.", NamedTextColor.YELLOW));
            return true;
        }
        String arg = args[0].toLowerCase(Locale.ROOT);
        if (arg.equals("void")) {
            if (!plugin.wordMode()) {
                player.sendMessage(Component.text("The void sword is not on this server yet.", NamedTextColor.RED));
                return true;
            }
            plugin.words().craftVoid(player);
            return true;
        }
        if (isCobble(arg)) {
            return craft(player, profile, true);
        }
        if (isOak(arg)) {
            return craft(player, profile, false);
        }
        player.sendMessage(Component.text("You can compress cobble or oak, or craft the void sword.", NamedTextColor.YELLOW));
        return true;
    }

    private boolean withdraw(Player player, Profile profile, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage: /withdraw <amount> <address>", NamedTextColor.YELLOW));
            return true;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(args[0]);
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text("Amount is a number of STT, for example 0.1.", NamedTextColor.RED));
            return true;
        }
        String to = args[1];
        plugin.profiles().save(profile);
        UUID id = player.getUniqueId();
        player.sendMessage(Component.text("Signing the withdraw from your purse.", NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = null;
            String error = null;
            try {
                hash = plugin.wallets().withdraw(profile, to, amount);
            } catch (Exception exception) {
                error = exception.getMessage();
            }
            String sent = hash;
            String line = sent == null ? error : "Sent. " + sent;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(id);
                if (online != null) {
                    online.sendMessage(Component.text(line == null ? "Withdraw failed." : line, sent == null ? NamedTextColor.RED : NamedTextColor.GREEN));
                }
            });
        });
        return true;
    }

    /** Official Shannon (chain 50312) faucet, per docs.somnia.network/blockchain/faucet. */
    static final String FAUCET = "https://explorer.testnet.somnia.network/faucet";

    private void tellBalance(Player player, Profile profile) {
        UUID id = player.getUniqueId();
        String address = profile.wallet;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String text;
            try {
                text = "Balance: " + plugin.wallets().balance(address) + " STT";
            } catch (Exception exception) {
                text = "Shannon did not answer.";
            }
            String line = text;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(id);
                if (online != null) {
                    online.sendMessage(Component.text(line, line.startsWith("Balance") ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("trade") && args.length == 1) {
            List<String> names = new java.util.ArrayList<>();
            names.add("accept");
            names.add("deny");
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                names.add(online.getName());
            }
            return names.stream()
                    .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (!command.getName().equalsIgnoreCase("craft") || args.length != 1) {
            return List.of();
        }
        return List.of("cobble", "oak", "void").stream()
                .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
    }

    void craftFromMenu(Player player, boolean cobble) {
        if (plugin.wordMode() && plugin.words().busy(player)) {
            player.sendMessage(Component.text("A signature is already in flight.", NamedTextColor.YELLOW));
            return;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        plugin.wallets().ensure(profile);
        craft(player, profile, cobble);
    }

    private boolean craft(Player player, Profile profile, boolean cobble) {
        if (plugin.hold().offchain(player)) {
            player.sendMessage(Component.text("Creative mode does not sign crafts. /gamemode 0 restores the chain inventory.", NamedTextColor.RED));
            return true;
        }
        long collected = Collects.amount(profile, Collects.ENTRIES.get(cobble ? 3 : 9));
        String name = cobble ? "cobblestone" : "oak";
        if (collected < ProgressMath.CRAFT_COLLECTION) {
            player.sendMessage(Component.text(
                    "Your " + name + " collection is " + collected + " of " + ProgressMath.CRAFT_COLLECTION + ".",
                    NamedTextColor.RED
            ));
            return true;
        }
        // The craft is signed as a SkyWord bag write (commitLocal in giveCraft), like every other inventory change.
        return giveCraft(player, profile, cobble);
    }

    private boolean giveCraft(Player player, Profile profile, boolean cobble) {
        Material material = cobble ? Material.COBBLESTONE : Material.OAK_LOG;
        if (!take(player, material, ProgressMath.CRAFT_COST)) {
            player.sendMessage(Component.text(
                    "You need " + ProgressMath.CRAFT_COST + " plain " + (cobble ? "cobblestone" : "oak") + " in your inventory.",
                    NamedTextColor.RED
            ));
            return false;
        }
        ItemStack reward = cobble ? Items.enchantedCobble(plugin) : Items.enchantedOak(plugin);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(reward);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        if (cobble) {
            profile.enchantedCobble++;
        } else {
            profile.enchantedOak++;
        }
        plugin.profiles().save(profile);
        plugin.sidebar().show(player, profile);
        plugin.hold().touch(player, true);
        if (plugin.wordMode()) {
            plugin.words().commitLocal(player);
        }
        player.sendMessage(Component.text(
                cobble ? "Compressed 160 cobblestone into Enchanted Cobblestone." : "Compressed 160 oak logs into an Enchanted Oak Log.",
                NamedTextColor.AQUA
        ));
        return true;
    }

    private boolean take(Player player, Material type, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int found = 0;
        for (ItemStack stack : contents) {
            if (plain(stack, type)) {
                found += stack.getAmount();
            }
        }
        if (found < amount) {
            return false;
        }
        int left = amount;
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!plain(stack, type)) {
                continue;
            }
            int taken = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - taken);
            if (stack.getAmount() <= 0) {
                contents[slot] = null;
            }
            left -= taken;
        }
        player.getInventory().setStorageContents(contents);
        return true;
    }

    private boolean plain(ItemStack stack, Material type) {
        return stack != null && stack.getType() == type && !Items.isEnchanted(stack, plugin);
    }

    private boolean isCobble(String arg) {
        return arg.equals("cobble") || arg.equals("cobblestone");
    }

    private boolean isOak(String arg) {
        return arg.equals("oak") || arg.equals("log");
    }
}
