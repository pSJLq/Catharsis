package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Co-op profiles, after the Hypixel SkyBlock Co-op page: up to 5 players share one Private Island and its
 * collections (each member's own contribution is kept and shown); skills, purse, bag, quests and Bazaar/AH trades
 * stay per member. The island owner invites with /coop &lt;player&gt; (the player must be online); accepting
 * resets the joiner's own profile (confirm page) and moves them onto the owner's island. /coopkick starts a vote
 * (more than half of the other members); with only two members the other one can be kicked after 7 days offline.
 * Leaving or being kicked resets the profile again onto a new starter island.
 *
 * Onchain: SkyIsland2.invite(member) from the owner's purse (the owner pays the gas; the server attests). It
 * records the member under the owner's deed when the joiner holds no deed yet; SkyIsland2 has no leave/kick, so
 * those (and joins of players who already hold a deed) are kept in coops.yml only and logged.
 */
final class Coops implements CommandExecutor, TabCompleter, Listener {
    /** Hypixel SkyBlock: up to 5 players on a co-op profile (SkyIsland2.MAX_MEMBERS is the same 5). */
    static final int MAX_MEMBERS = 5;
    static final long INVITE_MS = 5 * 60_000L;
    static final long VOTE_MS = 5 * 60_000L;
    /** Hypixel: with two members the other one can be kicked only after a week of inactivity. */
    static final long KICK_INACTIVE_MS = 7L * 24 * 3600_000L;
    /** While a website event runs: at most one self-made profile reset (reset, join, leave) per 24 hours. */
    static final long EVENT_RESET_GAP_MS = 24L * 3600_000L;

    private static volatile Coops instance;

    static final class Coop {
        final UUID owner;
        final List<UUID> members = new ArrayList<>();
        long created;
        long deed;
        final Set<UUID> onchain = new LinkedHashSet<>();
        final Map<UUID, int[]> homes = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();

        Coop(UUID owner) {
            this.owner = owner;
        }

        List<UUID> all() {
            List<UUID> out = new ArrayList<>();
            out.add(owner);
            out.addAll(members);
            return out;
        }

        int size() {
            return 1 + members.size();
        }
    }

    private record Vote(UUID target, Set<UUID> yes, long at) {
    }

    private final SomniaSkyblockPlugin plugin;
    private final File file;
    private final Map<UUID, Coop> byOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Coop> byMember = new ConcurrentHashMap<>();
    /** invitee -> (owner -> time) */
    private final Map<UUID, Map<UUID, Long>> invites = new ConcurrentHashMap<>();
    /** owner -> open kick vote */
    private final Map<UUID, Vote> votes = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    Coops(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "coops.yml");
        load();
        instance = this;
    }

    // ------------------------------------------------------------------ lookups (any thread)

    static Coops get() {
        return instance;
    }

    Coop of(UUID uuid) {
        return uuid == null ? null : byMember.get(uuid);
    }

    /** True when the player shares an island with at least one other player. */
    boolean inCoop(UUID uuid) {
        Coop c = of(uuid);
        return c != null && c.size() > 1;
    }

    /** A co-op member who is not the island owner. */
    boolean isGuestMember(UUID uuid) {
        Coop c = of(uuid);
        return c != null && !c.owner.equals(uuid);
    }

    /** The profiles whose collections add up for this player: the whole co-op, or just the player. */
    static List<Profile> group(Profile p) {
        Coops c = instance;
        if (c == null || p == null) {
            return p == null ? List.of() : List.of(p);
        }
        Coop coop = c.of(p.uuid);
        if (coop == null || coop.size() < 2) {
            return List.of(p);
        }
        List<Profile> out = new ArrayList<>(coop.size());
        for (UUID id : coop.all()) {
            out.add(id.equals(p.uuid) ? p : c.profile(id));
        }
        return out;
    }

    /** The profile that owns the island this player plays on (the co-op owner, or the player). */
    static Profile host(Profile p) {
        Coops c = instance;
        if (c == null || p == null) {
            return p;
        }
        Coop coop = c.of(p.uuid);
        if (coop == null || coop.owner.equals(p.uuid)) {
            return p;
        }
        return c.profile(coop.owner);
    }

    private Profile profile(UUID id) {
        return testProfiles != null ? testProfiles.apply(id) : plugin.profiles().get(id);
    }

    static String name(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    /** Pending invites for this player: owner -> time sent (expired ones dropped). */
    Map<UUID, Long> invitesFor(UUID me) {
        Map<UUID, Long> m = invites.get(me);
        if (m == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        m.values().removeIf(t -> now - t > INVITE_MS);
        return new LinkedHashMap<>(m);
    }

    int votesFor(UUID owner, UUID target) {
        Vote v = votes.get(owner);
        if (v == null || !v.target.equals(target) || System.currentTimeMillis() - v.at > VOTE_MS) {
            return 0;
        }
        return v.yes.size();
    }

    /** Yes votes a kick needs: more than half of the other members (everyone but the target). */
    static int needed(int size) {
        int others = size - 1;
        return others / 2 + 1;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "coopadd" -> {
                if (args.length < 1) {
                    tell(player, "Usage: /coopadd <player>", NamedTextColor.RED);
                } else {
                    invite(player, args[0]);
                }
                return true;
            }
            case "coopkick" -> {
                if (args.length < 1) {
                    tell(player, "Usage: /coopkick <player>", NamedTextColor.RED);
                } else {
                    kick(player, args[0]);
                }
                return true;
            }
            case "coopchat" -> {
                chat(player, String.join(" ", args));
                return true;
            }
            case "coopclear" -> {
                clear(player);
                return true;
            }
            case "coopsalvage" -> {
                tell(player, "Co-op salvage is coming SOON.", NamedTextColor.YELLOW);
                return true;
            }
            default -> {
            }
        }
        if (args.length == 0 || label.equalsIgnoreCase("coopview")) {
            plugin.menu().openCoop(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String who = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "accept" -> {
                UUID from = pickInvite(player, who);
                if (from != null) {
                    plugin.menu().openCoopJoin(player, from);
                }
            }
            case "deny", "decline" -> deny(player, who);
            case "leave" -> plugin.menu().openCoopLeave(player);
            case "kick" -> {
                if (who == null) {
                    tell(player, "Usage: /coop kick <player>", NamedTextColor.RED);
                } else {
                    kick(player, who);
                }
            }
            case "invite", "add" -> {
                if (who == null) {
                    tell(player, "Usage: /coop <player>", NamedTextColor.RED);
                } else {
                    invite(player, who);
                }
            }
            case "view", "list", "menu" -> plugin.menu().openCoop(player);
            case "chat" -> chat(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            case "clear" -> clear(player);
            case "salvage" -> tell(player, "Co-op salvage is coming SOON.", NamedTextColor.YELLOW);
            default -> {
                for (String name : args) {
                    invite(player, name);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (cmd.equals("coop") && args.length == 1) {
            for (String s : List.of("accept", "deny", "leave", "kick", "view", "chat", "clear")) {
                if (s.startsWith(last)) {
                    out.add(s);
                }
            }
        }
        if (!cmd.equals("coopchat")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(sender) && p.getName().toLowerCase(Locale.ROOT).startsWith(last)) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ invite / accept

    void invite(Player owner, String targetName) {
        Profile me = plugin.profiles().get(owner.getUniqueId());
        if (!me.islandReady || me.wallet == null || me.wallet.isBlank()) {
            tell(owner, "Your profile is not set up yet.", NamedTextColor.RED);
            return;
        }
        Coop coop = of(owner.getUniqueId());
        if (coop != null && !coop.owner.equals(owner.getUniqueId())) {
            tell(owner, "Only " + name(coop.owner) + ", the island owner, can add co-op members.", NamedTextColor.RED);
            return;
        }
        int size = coop == null ? 1 : coop.size();
        if (size >= MAX_MEMBERS) {
            tell(owner, "Your co-op is full (" + size + "/" + MAX_MEMBERS + " members).", NamedTextColor.RED);
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            tell(owner, targetName + " is not online. Co-op invites need the player online in SkyBlock.", NamedTextColor.RED);
            return;
        }
        if (target.equals(owner)) {
            tell(owner, "You cannot invite yourself.", NamedTextColor.RED);
            return;
        }
        if (of(target.getUniqueId()) != null && inCoop(target.getUniqueId())) {
            tell(owner, target.getName() + " is already in a co-op.", NamedTextColor.RED);
            return;
        }
        Profile them = plugin.profiles().get(target.getUniqueId());
        if (!them.islandReady || them.wallet == null || them.wallet.isBlank()) {
            tell(owner, target.getName() + " has no profile set up yet.", NamedTextColor.RED);
            return;
        }
        invites.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>()).put(owner.getUniqueId(), System.currentTimeMillis());
        tell(owner, "Co-op invite sent to " + target.getName() + ". It is valid for 5 minutes.", NamedTextColor.GREEN);
        if (coop != null) {
            for (UUID id : coop.members) {
                Player m = Bukkit.getPlayer(id);
                if (m != null) {
                    tell(m, owner.getName() + " invited " + target.getName() + " to your co-op.", NamedTextColor.AQUA);
                }
            }
        }
        Chat.separator(target, NamedTextColor.AQUA);
        target.sendMessage(Component.text(owner.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" invited you to their co-op (" + (size + 1) + "/" + MAX_MEMBERS + " members).", NamedTextColor.AQUA)));
        target.sendMessage(Component.text("Joining shares their island and collections. Your own profile is reset first: skills, collections, quests, coins, bag and island start again from zero.", NamedTextColor.GRAY));
        target.sendMessage(Component.text("[ACCEPT]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/coop accept " + owner.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Open the confirm page", NamedTextColor.YELLOW)))
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(Component.text("[DENY]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/coop deny " + owner.getName()))));
        Chat.separator(target, NamedTextColor.AQUA);
        plugin.getLogger().info("Co-op invite " + owner.getName() + " -> " + target.getName() + ".");
    }

    private UUID pickInvite(Player player, String fromName) {
        Map<UUID, Long> mine = invitesFor(player.getUniqueId());
        if (mine.isEmpty()) {
            tell(player, "You have no co-op invites (they last 5 minutes).", NamedTextColor.RED);
            return null;
        }
        if (fromName == null) {
            if (mine.size() > 1) {
                tell(player, "You have several invites. /coop accept <player>", NamedTextColor.RED);
                return null;
            }
            return mine.keySet().iterator().next();
        }
        for (UUID id : mine.keySet()) {
            if (name(id).equalsIgnoreCase(fromName)) {
                return id;
            }
        }
        tell(player, "No co-op invite from " + fromName + ".", NamedTextColor.RED);
        return null;
    }

    private void deny(Player player, String fromName) {
        UUID from = pickInvite(player, fromName);
        if (from == null) {
            return;
        }
        invites.getOrDefault(player.getUniqueId(), new ConcurrentHashMap<>()).remove(from);
        tell(player, "Invite from " + name(from) + " declined.", NamedTextColor.GRAY);
        Player o = Bukkit.getPlayer(from);
        if (o != null) {
            tell(o, player.getName() + " declined your co-op invite.", NamedTextColor.GRAY);
        }
    }

    private void clear(Player player) {
        invites.remove(player.getUniqueId());
        for (Map<UUID, Long> m : invites.values()) {
            m.remove(player.getUniqueId());
        }
        tell(player, "Your pending co-op invites were cleared.", NamedTextColor.GRAY);
    }

    /** Self-made profile resets during a running website event: one per 24 hours. Null when allowed. */
    String resetLimit(Profile p) {
        if (!EventTally.eventRunning()) {
            return null;
        }
        long left = p.lastResetAt + EVENT_RESET_GAP_MS - System.currentTimeMillis();
        if (p.lastResetAt <= 0 || left <= 0) {
            return null;
        }
        long h = left / 3600_000L;
        long m = (left % 3600_000L) / 60_000L;
        return "During the event a profile can be reset once every 24 hours. Try again in " + h + "h " + m + "m.";
    }

    /** Checks shared by the self-made resets (join and leave). Null when the player may go on. */
    String selfResetBlock(Player player, Profile p) {
        if (!p.islandReady || p.wallet == null || p.wallet.isBlank()) {
            return "Your profile is not set up yet.";
        }
        if (busy.contains(p.uuid)) {
            return "A co-op change is already running for you.";
        }
        if (plugin.words() != null && plugin.words().busy(player)) {
            return "A signature is still in flight. Try again in a few seconds.";
        }
        int trades = plugin.market() == null ? 0 : plugin.market().openTrades(p.uuid);
        if (trades > 0) {
            return "You have " + trades + " open Bazaar/Auction House trade(s). Cancel or finish them first (/bz orders, /ah).";
        }
        if (plugin.market() != null && plugin.market().hasStash(p.uuid)) {
            return "Items wait in your stash. Claim them first (/bz claim).";
        }
        String limit = resetLimit(p);
        if (limit != null) {
            return limit;
        }
        return null;
    }

    /** Confirm page "Yes": joins the owner's co-op (onchain invite first, then the reset). */
    void join(Player player, UUID ownerId) {
        UUID me = player.getUniqueId();
        Long sent = invitesFor(me).get(ownerId);
        if (sent == null) {
            tell(player, "That invite expired. Ask for a new one.", NamedTextColor.RED);
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) {
            tell(player, name(ownerId) + " must be online for you to join.", NamedTextColor.RED);
            return;
        }
        Profile p = plugin.profiles().get(me);
        Profile op = plugin.profiles().get(ownerId);
        Coop mine = of(me);
        if (mine != null && mine.size() > 1) {
            tell(player, "You are already in a co-op.", NamedTextColor.RED);
            return;
        }
        Coop target = of(ownerId);
        if (target != null && !target.owner.equals(ownerId)) {
            tell(player, name(ownerId) + " is no longer an island owner.", NamedTextColor.RED);
            return;
        }
        if (target != null && target.size() >= MAX_MEMBERS) {
            tell(player, "That co-op is full (" + MAX_MEMBERS + "/" + MAX_MEMBERS + ").", NamedTextColor.RED);
            return;
        }
        String block = selfResetBlock(player, p);
        if (block != null) {
            tell(player, block, NamedTextColor.RED);
            return;
        }
        if (busy.contains(ownerId)) {
            tell(player, "The co-op is busy with another change. Try again in a moment.", NamedTextColor.RED);
            return;
        }
        if (!op.islandReady || op.wallet == null || op.wallet.isBlank()) {
            tell(player, "The owner's profile is not set up yet.", NamedTextColor.RED);
            return;
        }
        busy.add(me);
        busy.add(ownerId);
        tell(player, "Joining " + owner.getName() + "'s co-op...", NamedTextColor.YELLOW);
        String joinerWallet = p.wallet;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Onchain result = recordInvite(op, joinerWallet);
            Bukkit.getScheduler().runTask(plugin, () -> finishJoin(me, ownerId, result));
        });
    }

    record Onchain(boolean ok, boolean recorded, long deed, String note) {
    }

    /** SkyIsland2: the owner's purse sends invite(joiner). Runs off the main thread. */
    private Onchain recordInvite(Profile owner, String joiner) {
        if (plugin.chain() == null || !plugin.chain().enabled()) {
            return new Onchain(true, false, 0, "chain off");
        }
        try {
            BigInteger joinerDeed = plugin.wallets().memberIsland(joiner);
            BigInteger ownerDeed = plugin.wallets().memberIsland(owner.wallet);
            if (joinerDeed.signum() > 0) {
                // SkyIsland2 has no leave: a deed member cannot join another deed. Kept in coops.yml only.
                return new Onchain(true, false, ownerDeed.longValue(), "joiner already holds island deed #" + joinerDeed);
            }
            if (ownerDeed.signum() == 0) {
                String hash = plugin.wallets().createIsland(owner);
                plugin.getLogger().info("Co-op: island deed for " + owner.wallet + " " + hash);
                ownerDeed = plugin.wallets().memberIsland(owner.wallet);
            }
            String deedOwner = plugin.wallets().islandOwner(ownerDeed);
            if (deedOwner == null || !deedOwner.equalsIgnoreCase(owner.wallet)) {
                return new Onchain(true, false, ownerDeed.longValue(), "owner is a member of deed #" + ownerDeed + ", not its owner");
            }
            String hash = plugin.wallets().inviteToIsland(owner, joiner);
            plugin.getLogger().info("Co-op: SkyIsland2.invite(" + joiner + ") from " + owner.wallet + " deed #" + ownerDeed + " " + hash);
            return new Onchain(true, true, ownerDeed.longValue(), hash);
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            boolean gas = why.startsWith("Purse has") || why.toLowerCase(Locale.ROOT).contains("insufficient funds");
            plugin.getLogger().warning("Co-op invite onchain failed for " + joiner + " (owner " + owner.wallet + "): " + why);
            return new Onchain(false, false, 0, gas ? "gas" : why);
        }
    }

    private void finishJoin(UUID me, UUID ownerId, Onchain result) {
        Player player = Bukkit.getPlayer(me);
        Player owner = Bukkit.getPlayer(ownerId);
        if (!result.ok()) {
            busy.remove(me);
            busy.remove(ownerId);
            String text = "gas".equals(result.note())
                    ? "Not enough STT for gas, use /deposit"
                    : "The co-op could not be recorded onchain, nothing changed: " + result.note();
            if (owner != null) {
                tell(owner, "gas".equals(result.note()) ? text + " (the island owner's purse pays the co-op invite)." : text, NamedTextColor.RED);
            }
            if (player != null) {
                tell(player, "gas".equals(result.note()) ? "The island owner's purse has not enough STT for gas; nothing changed." : text, NamedTextColor.RED);
            }
            return;
        }
        Profile p = plugin.profiles().get(me);
        int[] home = {p.islandX, p.islandZ};
        invites.remove(me);
        if (player != null) {
            player.kick(Component.text("Joining " + name(ownerId) + "'s co-op: your profile is being reset. Join again to play on the co-op island.", NamedTextColor.YELLOW));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                Player again = Bukkit.getPlayer(me);
                if (again != null) {
                    again.kick(Component.text("Your co-op profile is being set up. Join again in a moment.", NamedTextColor.YELLOW));
                }
                Coop coop = byOwner.computeIfAbsent(ownerId, Coop::new);
                if (coop.created == 0) {
                    coop.created = System.currentTimeMillis();
                }
                byMember.put(ownerId, coop);
                if (!coop.members.contains(me)) {
                    coop.members.add(me);
                }
                byMember.put(me, coop);
                coop.homes.put(me, home);
                if (result.recorded()) {
                    coop.onchain.add(me);
                }
                if (result.deed() > 0) {
                    coop.deed = result.deed();
                }
                coop.history.add(System.currentTimeMillis() + " joined " + name(me));
                save();
                Profile owned = plugin.profiles().get(ownerId);
                Profile fresh = plugin.profiles().get(me);
                fresh.lastResetAt = System.currentTimeMillis();
                plugin.islands().joinCoop(fresh, owned);
                plugin.getLogger().info("Co-op: " + name(me) + " (" + fresh.wallet + ") joined " + name(ownerId) + "'s island at "
                        + owned.islandX + "," + owned.islandZ + " (" + coop.size() + "/" + MAX_MEMBERS + "); onchain: "
                        + (result.recorded() ? "SkyIsland2.invite " + result.note() : "not recorded (" + result.note() + ")")
                        + "; old island slot " + home[0] + "," + home[1] + " kept for a later leave.");
                Player o = Bukkit.getPlayer(ownerId);
                if (o != null) {
                    tell(o, name(me) + " joined your co-op (" + coop.size() + "/" + MAX_MEMBERS + ").", NamedTextColor.GREEN);
                }
            } finally {
                busy.remove(me);
                busy.remove(ownerId);
            }
        }, 2L);
    }

    // ------------------------------------------------------------------ leave / kick

    /** Confirm page "Yes": a member (not the owner) leaves and starts again on a new starter island. */
    void leave(Player player) {
        UUID me = player.getUniqueId();
        Coop coop = of(me);
        if (coop == null || coop.size() < 2) {
            tell(player, "You are not in a co-op.", NamedTextColor.RED);
            return;
        }
        if (coop.owner.equals(me)) {
            tell(player, "You own this co-op island (and its onchain deed). Kick the other members first (/coopkick).", NamedTextColor.RED);
            return;
        }
        Profile p = plugin.profiles().get(me);
        String block = selfResetBlock(player, p);
        if (block != null) {
            tell(player, block, NamedTextColor.RED);
            return;
        }
        busy.add(me);
        player.kick(Component.text("You left the co-op. Your profile is being reset; join again to start on a new island.", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                remove(coop, me, "left", true);
            } finally {
                busy.remove(me);
            }
        }, 2L);
    }

    void kick(Player player, String targetName) {
        UUID me = player.getUniqueId();
        Coop coop = of(me);
        if (coop == null || coop.size() < 2) {
            tell(player, "You are not in a co-op.", NamedTextColor.RED);
            return;
        }
        UUID target = null;
        for (UUID id : coop.all()) {
            if (name(id).equalsIgnoreCase(targetName)) {
                target = id;
            }
        }
        if (target == null) {
            tell(player, targetName + " is not in your co-op.", NamedTextColor.RED);
            return;
        }
        if (target.equals(me)) {
            tell(player, "Use /coop leave to leave.", NamedTextColor.RED);
            return;
        }
        if (target.equals(coop.owner)) {
            tell(player, "The island owner holds the onchain deed and cannot be kicked.", NamedTextColor.RED);
            return;
        }
        if (busy.contains(target)) {
            tell(player, "A co-op change is already running for " + name(target) + ".", NamedTextColor.RED);
            return;
        }
        if (coop.size() == 2) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(target);
            long last = Math.max(off.getLastSeen(), off.getLastLogin());
            if (off.isOnline() || System.currentTimeMillis() - last < KICK_INACTIVE_MS) {
                tell(player, "With two members, the other one can only be kicked after 7 days offline.", NamedTextColor.RED);
                return;
            }
            doKick(coop, target, player.getName() + " (inactive 7+ days)");
            return;
        }
        long now = System.currentTimeMillis();
        Vote v = votes.get(coop.owner);
        if (v == null || !v.target.equals(target) || now - v.at > VOTE_MS) {
            v = new Vote(target, ConcurrentHashMap.newKeySet(), now);
            votes.put(coop.owner, v);
        }
        v.yes.add(me);
        int need = needed(coop.size());
        for (UUID id : coop.all()) {
            Player m = Bukkit.getPlayer(id);
            if (m != null && !id.equals(target)) {
                m.sendMessage(Component.text(player.getName() + " voted to kick " + name(target) + " (" + v.yes.size() + "/" + need + " votes). ", NamedTextColor.YELLOW)
                        .append(Component.text("[VOTE KICK]", NamedTextColor.RED, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/coopkick " + name(target)))));
            }
        }
        if (v.yes.size() >= need) {
            votes.remove(coop.owner);
            doKick(coop, target, "vote " + v.yes.size() + "/" + need);
        }
    }

    private void doKick(Coop coop, UUID target, String why) {
        busy.add(target);
        Player t = Bukkit.getPlayer(target);
        if (t != null) {
            t.kick(Component.text("You were removed from the co-op. Your profile is being reset; join again to start on a new island.", NamedTextColor.YELLOW));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                remove(coop, target, "kicked (" + why + ")", false);
            } finally {
                busy.remove(target);
            }
        }, 2L);
    }

    /** Takes a member off the co-op and resets them onto their old (or a new) island slot. Main thread. */
    private void remove(Coop coop, UUID id, String how, boolean self) {
        Player again = Bukkit.getPlayer(id);
        if (again != null) {
            again.kick(Component.text("Your profile is being reset. Join again in a moment.", NamedTextColor.YELLOW));
        }
        int[] home = coop.homes.remove(id);
        coop.members.remove(id);
        byMember.remove(id);
        boolean wasOnchain = coop.onchain.remove(id);
        coop.history.add(System.currentTimeMillis() + " " + how.split(" ")[0] + " " + name(id));
        if (coop.members.isEmpty()) {
            byOwner.remove(coop.owner);
            byMember.remove(coop.owner);
        }
        save();
        Profile p = plugin.profiles().get(id);
        if (self) {
            p.lastResetAt = System.currentTimeMillis();
        }
        plugin.islands().leaveCoop(p, home);
        plugin.getLogger().info("Co-op: " + name(id) + " " + how + " from " + name(coop.owner) + "'s co-op; profile reset onto island "
                + p.islandX + "," + p.islandZ + (wasOnchain ? "; SkyIsland2 still lists them under deed #" + coop.deed + " (the contract has no leave)" : "") + ".");
        for (UUID m : coop.all()) {
            Player online = Bukkit.getPlayer(m);
            if (online != null) {
                tell(online, name(id) + " " + (self ? "left" : "was removed from") + " the co-op.", NamedTextColor.YELLOW);
            }
        }
    }

    /** After the owner's island was rebuilt (new spawn/NPC spots): every member follows. */
    void islandRebuilt(Profile owner) {
        Coop coop = byOwner.get(owner.uuid);
        if (coop == null) {
            return;
        }
        for (UUID id : coop.members) {
            Profile m = plugin.profiles().get(id);
            IslandManager.copyIsland(owner, m);
            plugin.profiles().save(m);
        }
    }

    // ------------------------------------------------------------------ chat

    private void chat(Player player, String text) {
        Coop coop = of(player.getUniqueId());
        if (coop == null || coop.size() < 2) {
            tell(player, "You are not in a co-op.", NamedTextColor.RED);
            return;
        }
        if (text == null || text.isBlank()) {
            tell(player, "Usage: /cc <message>", NamedTextColor.RED);
            return;
        }
        String clean = text.length() > 200 ? text.substring(0, 200) : text;
        Component line = Component.text("Co-op > ", NamedTextColor.AQUA).append(Component.text(player.getName() + ": ", NamedTextColor.YELLOW))
                .append(Component.text(clean, NamedTextColor.WHITE));
        for (UUID id : coop.all()) {
            Player m = Bukkit.getPlayer(id);
            if (m != null) {
                m.sendMessage(line);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Invites need both players online (Hypixel); a leaving owner's invites go.
        for (Map<UUID, Long> m : invites.values()) {
            m.remove(id);
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("coops");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection s = root.getConfigurationSection(key);
                Coop c = new Coop(UUID.fromString(key));
                c.created = s.getLong("created");
                c.deed = s.getLong("deed");
                for (String m : s.getStringList("members")) {
                    c.members.add(UUID.fromString(m));
                }
                for (String m : s.getStringList("onchain")) {
                    c.onchain.add(UUID.fromString(m));
                }
                ConfigurationSection homes = s.getConfigurationSection("homes");
                if (homes != null) {
                    for (String m : homes.getKeys(false)) {
                        List<Integer> xz = homes.getIntegerList(m);
                        if (xz.size() == 2) {
                            c.homes.put(UUID.fromString(m), new int[]{xz.get(0), xz.get(1)});
                        }
                    }
                }
                c.history.addAll(s.getStringList("history"));
                if (c.members.isEmpty()) {
                    continue;
                }
                byOwner.put(c.owner, c);
                byMember.put(c.owner, c);
                for (UUID m : c.members) {
                    byMember.put(m, c);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("coops.yml: skipped " + key + " (" + e.getMessage() + ")");
            }
        }
        plugin.getLogger().info("Co-ops: " + byOwner.size() + " loaded.");
    }

    synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Coop c : byOwner.values()) {
            String k = "coops." + c.owner;
            yaml.set(k + ".created", c.created);
            yaml.set(k + ".deed", c.deed);
            yaml.set(k + ".members", c.members.stream().map(UUID::toString).toList());
            yaml.set(k + ".onchain", c.onchain.stream().map(UUID::toString).toList());
            for (var e : c.homes.entrySet()) {
                yaml.set(k + ".homes." + e.getKey(), List.of(e.getValue()[0], e.getValue()[1]));
            }
            yaml.set(k + ".history", new ArrayList<>(c.history));
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save coops.yml: " + e.getMessage());
        }
    }

    private static void tell(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text(text, color));
    }

    // ------------------------------------------------------------------ test hooks (no server needed)

    /** Builds a co-op in memory (tests only). */
    static Coops testInstance(Map<UUID, List<UUID>> coops, java.util.function.Function<UUID, Profile> profiles) {
        Coops c = new Coops(profiles);
        for (var e : coops.entrySet()) {
            Coop coop = new Coop(e.getKey());
            coop.members.addAll(e.getValue());
            c.byOwner.put(e.getKey(), coop);
            c.byMember.put(e.getKey(), coop);
            for (UUID m : e.getValue()) {
                c.byMember.put(m, coop);
            }
        }
        instance = c;
        return c;
    }

    private java.util.function.Function<UUID, Profile> testProfiles;

    private Coops(java.util.function.Function<UUID, Profile> profiles) {
        this.plugin = null;
        this.file = null;
        this.testProfiles = profiles;
    }

    static void clearTestInstance() {
        instance = null;
    }
}
