package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** /bz /ah /arena /stats /relic /quest */
final class EconomyCommands implements CommandExecutor, TabCompleter {
    private final SomniaSkyblockPlugin plugin;

    EconomyCommands(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        try {
            switch (command.getName()) {
                case "bazaar" -> {
                    if (plugin.cookies().allowAnywhere(player, "Bazaar")) {
                        bazaar(player, args);
                    }
                }
                case "auction" -> {
                    if (plugin.cookies().allowAnywhere(player, "Auction House")) {
                        auction(player, args);
                    }
                }
                case "bank" -> {
                    if (plugin.cookies().allowAnywhere(player, "Bank")) {
                        plugin.market().openBank(player);
                    }
                }
                case "arena" -> {
                    if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
                        plugin.arena().leave(player);
                    } else if (!plugin.arena().near(player)) {
                        plugin.hub().toColosseum(player);
                    } else {
                        plugin.arena().open(player);
                    }
                }
                case "stats" -> plugin.stats().show(player);
                case "relic" -> plugin.market().openBank(player);
                case "quest" -> plugin.quests().show(player);
                default -> {
                    return false;
                }
            }
        } catch (IllegalArgumentException | ArithmeticException exception) {
            player.sendMessage(Component.text(exception.getMessage() == null ? "Bad number." : exception.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private void bazaar(Player player, String[] args) {
        if (args.length == 0) {
            plugin.market().openBazaar(player);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "sell", "order" -> {
                if (args.length < 4) {
                    player.sendMessage(Component.text("/bz " + args[0] + " <item> <qty> <stt each>", NamedTextColor.RED));
                    return;
                }
                Market.Product product = Market.product(args[1]);
                if (product == null) {
                    player.sendMessage(Component.text("Unknown item. Try: " + String.join(", ", Market.PRODUCTS.keySet()), NamedTextColor.RED));
                    return;
                }
                int qty = Integer.parseInt(args[2]);
                BigInteger unit = RealmChain.wei(args[3]);
                if (args[0].equalsIgnoreCase("sell")) {
                    plugin.market().createOffer(player, product, qty, unit);
                } else {
                    plugin.market().createOrder(player, product, qty, unit);
                }
            }
            case "cancel" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("/bz cancel <id>", NamedTextColor.RED));
                    return;
                }
                plugin.market().cancel(player, Long.parseLong(args[1].replace("#", "")));
            }
            case "orders", "mine" -> plugin.market().listMine(player);
            case "claim" -> plugin.market().claim(player);
            case "merchant" -> plugin.market().openMerchant(player);
            default -> {
                Market.Product product = Market.product(args[0]);
                if (product != null) {
                    plugin.market().openProduct(player, product);
                } else {
                    plugin.market().openBazaar(player);
                }
            }
        }
    }

    private void auction(Player player, String[] args) {
        if (args.length == 0) {
            plugin.market().openAuctions(player);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "create", "gui" -> {
                if (args.length == 1) {
                    plugin.marketGui().create(player);
                    return;
                }
            }
            case "bin" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("/ah bin <price stt> [minutes]  (sells the held stack)", NamedTextColor.RED));
                    return;
                }
                plugin.market().createBin(player, player.getInventory().getHeldItemSlot(), RealmChain.wei(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 360);
                return;
            }
            case "buy" -> {
                Market.Bin bin = args.length < 2 ? null : plugin.market().bins().stream()
                        .filter(b -> b.id == Long.parseLong(args[1].replace("#", ""))).findFirst().orElse(null);
                if (bin == null) {
                    player.sendMessage(Component.text("No open BIN with that id.", NamedTextColor.RED));
                } else {
                    plugin.market().buyBin(player, bin);
                }
                return;
            }
            case "bid" -> {
                Market.Lot lot = args.length < 2 ? null : plugin.market().lots().stream()
                        .filter(l -> !l.done && l.id == Long.parseLong(args[1].replace("#", ""))).findFirst().orElse(null);
                if (lot == null) {
                    player.sendMessage(Component.text("No running auction with that id.", NamedTextColor.RED));
                } else if (args.length > 2) {
                    plugin.market().bid(player, lot, RealmChain.wei(args[2]));
                } else {
                    plugin.market().bid(player, lot);
                }
                return;
            }
            case "cancel" -> {
                Market.Bin bin = args.length < 2 ? null : plugin.market().bins().stream()
                        .filter(b -> b.id == Long.parseLong(args[1].replace("#", ""))).findFirst().orElse(null);
                if (bin == null) {
                    player.sendMessage(Component.text("No BIN of yours with that id.", NamedTextColor.RED));
                } else {
                    plugin.market().cancelBin(player, bin);
                }
                return;
            }
            case "list" -> {
                plugin.market().bins().forEach(b -> player.sendMessage(Component.text("BIN #" + b.id + " " + b.qty + "x " + Market.label(b.key)
                        + " " + RealmChain.stt(b.price) + " STT", NamedTextColor.GOLD)));
                plugin.market().lots().stream().filter(l -> !l.done).forEach(l -> player.sendMessage(Component.text("Auction #" + l.id + " " + l.qty + "x "
                        + Market.label(l.key) + " bid " + RealmChain.stt(l.bid.signum() == 0 ? l.start : l.bid) + " STT", NamedTextColor.LIGHT_PURPLE)));
                return;
            }
            default -> {
            }
        }
        if (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("create")) {
            if (args.length < 2) {
                player.sendMessage(Component.text("/ah sell <start stt> [minutes]   /ah bin <price> [minutes]   /ah create", NamedTextColor.RED));
                return;
            }
            BigInteger start = RealmChain.wei(args[1]);
            int minutes = args.length > 2 ? Integer.parseInt(args[2]) : 10;
            plugin.market().createAuction(player, start, minutes);
            return;
        }
        plugin.market().openAuctions(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equals("bazaar")) {
            if (args.length == 1) {
                for (String option : List.of("sell", "order", "cancel", "orders", "claim", "merchant")) {
                    if (option.startsWith(args[0].toLowerCase())) {
                        out.add(option);
                    }
                }
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("order"))) {
                for (String id : Market.PRODUCTS.keySet()) {
                    if (id.startsWith(args[1].toLowerCase())) {
                        out.add(id);
                    }
                }
            }
        } else if (command.getName().equals("auction") && args.length == 1) {
            out.add("sell");
        } else if (command.getName().equals("arena") && args.length == 1) {
            out.add("leave");
        }
        return out;
    }
}
