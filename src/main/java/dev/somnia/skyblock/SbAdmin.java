package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

/**
 * /sbadmin stop | save-off | save-on | save | status (console or permission somnia.admin, default op).
 * The same actions can be triggered without a console by creating an empty file in
 * plugins/SomniaSkyblock/control/ named stop, save-off, save-on or save; the plugin checks once a second,
 * deletes the file and writes the result to control/last.txt. Nothing listens on the network for this.
 */
final class SbAdmin implements CommandExecutor, TabCompleter {
    private static final List<String> ACTIONS = List.of("stop", "save-off", "save-on", "save", "status", "unregister", "deploy-skills", "deploy-skills2", "reset-skills");
    private final SomniaSkyblockPlugin plugin;
    private final File control;
    private boolean stopping;

    SbAdmin(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.control = new File(plugin.getDataFolder(), "control");
    }

    void start() {
        control.mkdirs();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 40L, 20L);
    }

    private void poll() {
        File deploy2 = new File(control, "deploy-skills2");
        if (deploy2.exists()) {
            String who = "";
            try {
                who = Files.readString(deploy2.toPath()).trim();
            } catch (IOException ignored) {
            }
            if (deploy2.delete()) {
                plugin.getLogger().info("Control file: deploy-skills2 " + who);
                plugin.skillChain().deploy(who, line -> note("deploy-skills2: " + line), true);
            }
        }
        File reset = new File(control, "reset-skills");
        if (reset.exists()) {
            String who = "";
            try {
                who = Files.readString(reset.toPath()).trim();
            } catch (IOException ignored) {
            }
            if (reset.delete()) {
                plugin.getLogger().info("Control file: reset-skills " + who);
                plugin.skillChain().adminReset(who, line -> note("reset-skills: " + line));
            }
        }
        File warn = new File(control, "warn");
        if (warn.exists()) {
            String text = "";
            try {
                text = Files.readString(warn.toPath()).trim();
            } catch (IOException ignored) {
            }
            if (warn.delete()) {
                // English text only; ASCII printable, one line, short.
                String clean = text.replaceAll("[^\\x20-\\x7E]", "").trim();
                if (clean.isEmpty()) {
                    clean = "Server restart in 60 seconds. Brief downtime.";
                }
                if (clean.length() > 120) {
                    clean = clean.substring(0, 120);
                }
                plugin.getServer().broadcast(net.kyori.adventure.text.Component.text(clean, net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                int online = plugin.getServer().getOnlinePlayers().size();
                plugin.getLogger().info("Control file: warn (" + online + " online): " + clean);
                note("warn: sent to " + online + " online: " + clean);
            }
        }
        File deploy = new File(control, "deploy-skills");
        if (deploy.exists()) {
            String who = "";
            try {
                who = Files.readString(deploy.toPath()).trim();
            } catch (IOException ignored) {
            }
            if (deploy.delete()) {
                plugin.getLogger().info("Control file: deploy-skills " + who);
                plugin.skillChain().deploy(who, line -> note("deploy-skills: " + line));
            }
        }
        for (String action : List.of("stop", "save-off", "save-on", "save", "status")) {
            File flag = new File(control, action);
            if (flag.exists()) {
                if (!flag.delete()) {
                    plugin.getLogger().warning("Could not delete control file " + flag);
                    continue;
                }
                plugin.getLogger().info("Control file: " + action);
                String result = run(action, plugin.getServer().getConsoleSender());
                note(action + ": " + result);
            }
        }
    }

    private void note(String line) {
        try {
            Files.writeString(new File(control, "last.txt").toPath(), LocalDateTime.now() + " " + line + System.lineSeparator());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write control/last.txt: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("somnia.admin")) {
            sender.sendMessage(Component.text("You don't have permission for /sbadmin.", NamedTextColor.RED));
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        if ((action.equals("dev") || action.equals("user")) && args.length == 2) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage(Component.text("Only the console can switch someone else's mode.", NamedTextColor.RED));
                return true;
            }
            plugin.auth().consoleMode(sender, args[1], action.equals("dev"));
            return true;
        }
        if (action.equals("deploy-skills")) {
            String who = args.length >= 2 ? args[1] : sender instanceof org.bukkit.entity.Player self ? self.getName() : null;
            plugin.getLogger().info(sender.getName() + " ran /sbadmin deploy-skills " + who);
            plugin.skillChain().deploy(who, line -> sender.sendMessage(Component.text(line, NamedTextColor.GREEN)));
            return true;
        }
        if (action.equals("deploy-skills2")) {
            String who = args.length >= 2 ? args[1] : sender instanceof org.bukkit.entity.Player self ? self.getName() : null;
            plugin.getLogger().info(sender.getName() + " ran /sbadmin deploy-skills2 " + who);
            plugin.skillChain().deploy(who, line -> sender.sendMessage(Component.text(line, NamedTextColor.GREEN)), true);
            return true;
        }
        if (action.equals("reset-skills")) {
            if (args.length != 2) {
                sender.sendMessage(Component.text("Usage: /sbadmin reset-skills <name>  (zeroes skills and collections onchain)", NamedTextColor.RED));
                return true;
            }
            plugin.getLogger().info(sender.getName() + " ran /sbadmin reset-skills " + args[1]);
            plugin.skillChain().adminReset(args[1], line -> sender.sendMessage(Component.text(line, NamedTextColor.GREEN)));
            return true;
        }
        if (action.equals("unregister")) {
            if (args.length != 2) {
                sender.sendMessage(Component.text("Usage: /sbadmin unregister <name>  (wipes the login; the player's purse is lost unless recovered)", NamedTextColor.RED));
                return true;
            }
            plugin.getLogger().info(sender.getName() + " ran /sbadmin unregister " + args[1]);
            plugin.auth().unregister(sender, args[1]);
            return true;
        }
        if (!ACTIONS.contains(action)) {
            sender.sendMessage(Component.text("Usage: /sbadmin <stop|save-off|save-on|save|status>", NamedTextColor.RED));
            return true;
        }
        plugin.getLogger().info(sender.getName() + " ran /sbadmin " + action);
        sender.sendMessage(Component.text(run(action, sender), NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return ACTIONS.stream().filter(a -> a.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }

    private String run(String action, CommandSender sender) {
        switch (action) {
            case "save" -> {
                saveAll();
                return "Saved all worlds and profiles.";
            }
            case "save-off" -> {
                saveAll();
                for (World world : plugin.getServer().getWorlds()) {
                    world.setAutoSave(false);
                }
                plugin.getLogger().info("Autosave is OFF (backups can copy the world folders now). Run /sbadmin save-on afterwards.");
                return "Worlds saved, autosave OFF. Copy the world folders now, then /sbadmin save-on.";
            }
            case "save-on" -> {
                for (World world : plugin.getServer().getWorlds()) {
                    world.setAutoSave(true);
                }
                plugin.getLogger().info("Autosave is ON.");
                return "Autosave ON.";
            }
            case "stop" -> {
                if (stopping) {
                    return "Already stopping.";
                }
                stopping = true;
                plugin.getServer().broadcast(Component.text("The server restarts in a few seconds.", NamedTextColor.YELLOW));
                saveAll();
                for (World world : plugin.getServer().getWorlds()) {
                    world.setAutoSave(true);
                }
                // Bukkit.shutdown saves worlds and players again and calls every onDisable.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.getServer().shutdown(), 40L);
                plugin.getLogger().info("Clean stop requested: worlds saved, shutting down in 2 s.");
                return "Worlds saved. Shutting down in 2 seconds.";
            }
            default -> {
                StringBuilder b = new StringBuilder("Hub: ").append(plugin.hub().pasting() ? plugin.hub().pasteStatus() : "ready (layout " + HubManager.HUB_LAYOUT + ")");
                for (World world : plugin.getServer().getWorlds()) {
                    b.append(" | ").append(world.getName()).append(" autosave ").append(world.isAutoSave() ? "on" : "off");
                }
                return b.toString();
            }
        }
    }

    private void saveAll() {
        plugin.getServer().savePlayers();
        plugin.profiles().saveAll();
        plugin.minions().save();
        for (World world : plugin.getServer().getWorlds()) {
            world.save();
        }
    }
}
