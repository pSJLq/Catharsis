package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /exportkey: hands a player the private key of their game wallet (two modes, SkyKeys).
 * Order: password re-entry -> SkyKeys.optIn() from the player's own purse -> the chain confirms the flag ->
 * only then the key is shown, to that player only. It is never logged, broadcast or stored in plaintext.
 * From then on every Somnia write from that purse needs a server attestation (mode B, one-way).
 */
final class KeyExport implements CommandExecutor, TabCompleter, org.bukkit.event.Listener {
    private final SomniaSkyblockPlugin plugin;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    KeyExport(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Profile profile = plugin.profiles().get(player.getUniqueId());
        if (profile.wallet == null || profile.wallet.isBlank() || profile.walletEnc == null) {
            tell(player, "You need a purse first. /register sets one up.", NamedTextColor.RED);
            return true;
        }
        if (plugin.wallets().exportBlocked(profile.wallet)) {
            tell(player, "This purse is a server purse and its key can't be exported. Ask an admin to move you to a fresh purse first.", NamedTextColor.RED);
            return true;
        }
        if (plugin.wallets().keysContract().isBlank()) {
            tell(player, "Key export is not available on this server yet.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("confirm")) {
            confirm(player, profile, args[1]);
            return true;
        }
        explain(player, profile);
        return true;
    }

    private void explain(Player player, Profile profile) {
        player.sendMessage(Component.text("Export your game wallet key", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Purse " + profile.wallet, NamedTextColor.GRAY));
        if (profile.keyExported) {
            player.sendMessage(Component.text("You exported this key before. Showing it again needs your password.", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("- You get the private key and can use this wallet in any Somnia wallet app.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- From then on every game write from this wallet carries a server signature, so each one costs a bit more gas.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- This can't be undone. Anyone with the key can spend your STT, so keep it secret.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("- First a small transaction from your purse marks the wallet on Somnia. You pay its gas.", NamedTextColor.GRAY));
        }
        player.sendMessage(Component.text("Type: ", NamedTextColor.YELLOW)
                .append(Component.text("/exportkey confirm <password>", NamedTextColor.GREEN)));
    }

    private void confirm(Player player, Profile profile, String password) {
        UUID id = player.getUniqueId();
        if (!running.add(id)) {
            tell(player, "Your key export is already running.", NamedTextColor.YELLOW);
            return;
        }
        plugin.auth().verifyPassword(player, password, key -> {
            if (key == null) {
                running.remove(id);
                return;
            }
            tell(player, profile.keyExported ? "Password OK. Checking your wallet on Somnia." : "Password OK. Marking your wallet on Somnia (you pay the gas).", NamedTextColor.GRAY);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Exception failure = null;
                TransactionReceipt receipt = null;
                try {
                    receipt = plugin.wallets().optInExport(profile);
                } catch (Exception exception) {
                    failure = exception;
                }
                Exception error = failure;
                String hash = receipt == null ? null : receipt.getTransactionHash();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    running.remove(id);
                    try {
                        Player online = plugin.getServer().getPlayer(id);
                        if (online == null) {
                            return;
                        }
                        if (error != null) {
                            tell(online, WordChain.noGas(error) ? "Not enough STT for gas, use /deposit." : "Key export stopped: " + short80(error.getMessage()), NamedTextColor.RED);
                            return;
                        }
                        profile.keyExported = true;
                        plugin.profiles().save(profile);
                        plugin.getLogger().info(online.getName() + " exported the purse key for " + profile.wallet + " (mode B, SkyKeys flag set).");
                        if (hash != null) {
                            online.sendMessage(plugin.realm().txLine("Your wallet is now marked on Somnia: every write needs a server signature.", hash));
                        }
                        show(online, key);
                    } finally {
                        Arrays.fill(key, (byte) 0);
                    }
                });
            });
        });
    }

    /** Shown only to this player: chat line plus click-to-copy. Never logged. */
    private void show(Player player, byte[] key) {
        String hex = Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(key), 64);
        player.sendMessage(Component.text("Your game wallet private key (only you can see this):", NamedTextColor.GOLD));
        player.sendMessage(Component.text(hex, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard(hex))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy. Never share it.", NamedTextColor.YELLOW))));
        player.sendMessage(Component.text("Import it into a wallet app on Somnia Shannon (chain 50312). Clear your chat log if others use this computer.", NamedTextColor.GRAY));
    }

    private static String short80(String message) {
        if (message == null || message.isBlank()) {
            return "Somnia rejected the transaction.";
        }
        String clean = message.replaceAll("0x[0-9a-fA-F]{64}", "0x…");
        return clean.length() > 80 ? clean.substring(0, 80) + "…" : clean;
    }

    private static void tell(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text(text, color));
    }

    /** Warms the mode cache (SkyKeys.exported) right after login, off the main thread. */
    @org.bukkit.event.EventHandler
    public void onLogin(SbLoginEvent event) {
        Profile profile = plugin.profiles().get(event.getPlayer().getUniqueId());
        String wallet = profile.wallet;
        if (wallet == null || wallet.isBlank()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.wallets().loadMode(wallet));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("confirm") : List.of();
    }
}
