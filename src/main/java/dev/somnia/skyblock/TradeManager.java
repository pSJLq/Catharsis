package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TradeManager implements Listener {
    private static final int[] OFFER = {0, 1, 2, 3, 9, 10, 11, 12};
    private static final int[] THEIRS = {5, 6, 7, 8, 14, 15, 16, 17};
    private static final int ACCEPT = 39;
    private static final int CANCEL = 41;

    private final SomniaSkyblockPlugin plugin;
    private final Map<UUID, UUID> incoming = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    TradeManager(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(Player player, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("accept")) {
            return acceptRequest(player);
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("deny")) {
            UUID from = incoming.remove(player.getUniqueId());
            player.sendMessage(Component.text(from == null ? "No trade request." : "Trade request denied.", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(Component.text("Use /trade <player>, /trade accept, or /trade deny.", NamedTextColor.YELLOW));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || target.equals(player)) {
            player.sendMessage(Component.text("That player is not online.", NamedTextColor.RED));
            return true;
        }
        if (sessions.containsKey(player.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            player.sendMessage(Component.text("One of you is already trading.", NamedTextColor.RED));
            return true;
        }
        if (plugin.wordMode()) {
            player.sendMessage(Component.text("Trades are not on this bag yet.", NamedTextColor.RED));
            return true;
        }
        if (plugin.hold().offchain(player) || plugin.hold().offchain(target)) {
            player.sendMessage(Component.text("Creative mode does not trade chain items.", NamedTextColor.RED));
            return true;
        }
        if (!plugin.hold().canTrade(player) || !plugin.hold().canTrade(target)) {
            player.sendMessage(Component.text("Wait until the inventory signature finishes.", NamedTextColor.RED));
            return true;
        }
        incoming.put(target.getUniqueId(), player.getUniqueId());
        player.sendMessage(Component.text("Trade request sent to " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text(player.getName() + " wants to trade. /trade accept", NamedTextColor.GOLD));
        return true;
    }

    private boolean acceptRequest(Player player) {
        UUID fromId = incoming.remove(player.getUniqueId());
        if (fromId == null) {
            player.sendMessage(Component.text("No trade request.", NamedTextColor.YELLOW));
            return true;
        }
        Player from = plugin.getServer().getPlayer(fromId);
        if (plugin.wordMode()) {
            player.sendMessage(Component.text("Trades are not on this bag yet.", NamedTextColor.RED));
            return true;
        }
        if (from == null || plugin.hold().offchain(player) || plugin.hold().offchain(from)) {
            player.sendMessage(Component.text("That trade is no longer available.", NamedTextColor.RED));
            return true;
        }
        if (!plugin.hold().canTrade(player) || !plugin.hold().canTrade(from)) {
            player.sendMessage(Component.text("That trade is no longer available.", NamedTextColor.RED));
            return true;
        }
        Session session = new Session(from.getUniqueId(), player.getUniqueId());
        sessions.put(from.getUniqueId(), session);
        sessions.put(player.getUniqueId(), session);
        plugin.hold().pause(from);
        plugin.hold().pause(player);
        open(from, session);
        open(player, session);
        return true;
    }

    private void open(Player player, Session session) {
        TradeHolder holder = new TradeHolder(session, player.getUniqueId());
        Inventory view = Bukkit.createInventory(holder, 54, Component.text("Trade"));
        holder.inventory = view;
        if (player.getUniqueId().equals(session.first)) {
            session.viewA = view;
        } else {
            session.viewB = view;
        }
        paint(view, session, player.getUniqueId());
        player.openInventory(view);
    }

    private void paint(Inventory view, Session session, UUID viewer) {
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < view.getSize(); slot++) {
            if (!isOffer(slot) && slot != ACCEPT && slot != CANCEL) {
                view.setItem(slot, pane);
            }
        }
        ItemStack[] mine = viewer.equals(session.first) ? session.offerA : session.offerB;
        ItemStack[] theirs = viewer.equals(session.first) ? session.offerB : session.offerA;
        for (int i = 0; i < OFFER.length; i++) {
            view.setItem(OFFER[i], mine[i] == null ? null : mine[i].clone());
            view.setItem(THEIRS[i], theirs[i] == null ? null : theirs[i].clone());
        }
        boolean mineAccepted = viewer.equals(session.first) ? session.acceptA : session.acceptB;
        boolean theirAccepted = viewer.equals(session.first) ? session.acceptB : session.acceptA;
        view.setItem(ACCEPT, named(
                mineAccepted ? Material.LIME_DYE : Material.GRAY_DYE,
                mineAccepted ? "Accepted" : "Accept",
                theirAccepted ? "The other player accepted." : "The other player has not accepted."
        ));
        view.setItem(CANCEL, named(Material.RED_DYE, "Cancel"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeHolder holder)) {
            return;
        }
        if (holder.session.busy) {
            event.setCancelled(true);
            return;
        }
        int raw = event.getRawSlot();
        if (raw < holder.inventory.getSize() && !isOffer(raw)) {
            event.setCancelled(true);
            if (raw == ACCEPT) {
                toggle(player, holder.session);
            } else if (raw == CANCEL) {
                abort(holder.session, player.getName() + " cancelled the trade.");
            }
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!sessions.containsKey(player.getUniqueId())) {
                return;
            }
            readOffer(holder);
            holder.session.acceptA = false;
            holder.session.acceptB = false;
            refresh(holder.session);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeHolder holder) || holder.session.busy) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < holder.inventory.getSize() && !isOffer(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            readOffer(holder);
            holder.session.acceptA = false;
            holder.session.acceptB = false;
            refresh(holder.session);
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeHolder holder)) {
            return;
        }
        Session session = holder.session;
        if (session.busy || session.closing || !sessions.containsKey(player.getUniqueId())) {
            return;
        }
        abort(session, player.getName() + " closed the trade.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        incoming.remove(event.getPlayer().getUniqueId());
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !session.busy) {
            abort(session, event.getPlayer().getName() + " left.");
        }
    }

    private void toggle(Player player, Session session) {
        if (player.getUniqueId().equals(session.first)) {
            session.acceptA = !session.acceptA;
        } else {
            session.acceptB = !session.acceptB;
        }
        refresh(session);
        if (session.acceptA && session.acceptB) {
            settle(session);
        }
    }

    private void readOffer(TradeHolder holder) {
        ItemStack[] offer = holder.viewer.equals(holder.session.first) ? holder.session.offerA : holder.session.offerB;
        for (int i = 0; i < OFFER.length; i++) {
            ItemStack stack = holder.inventory.getItem(OFFER[i]);
            offer[i] = stack == null || stack.getType().isAir() ? null : stack.clone();
        }
    }

    private void refresh(Session session) {
        Player first = plugin.getServer().getPlayer(session.first);
        Player second = plugin.getServer().getPlayer(session.second);
        if (first != null && session.viewA != null) {
            paint(session.viewA, session, session.first);
        }
        if (second != null && session.viewB != null) {
            paint(session.viewB, session, session.second);
        }
    }

    private void settle(Session session) {
        Player first = plugin.getServer().getPlayer(session.first);
        Player second = plugin.getServer().getPlayer(session.second);
        if (first == null || second == null) {
            abort(session, "The other player left.");
            return;
        }
        session.busy = true;
        Profile profileA = plugin.profiles().get(session.first);
        Profile profileB = plugin.profiles().get(session.second);
        Map<Integer, Integer> fromA = sums(session.offerA);
        Map<Integer, Integer> fromB = sums(session.offerB);
        first.sendMessage(Component.text("Signing the trade from both purses.", NamedTextColor.GRAY));
        second.sendMessage(Component.text("Signing the trade from both purses.", NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<int[]> doneA = new ArrayList<>();
            List<int[]> doneB = new ArrayList<>();
            try {
                for (Map.Entry<Integer, Integer> entry : fromA.entrySet()) {
                    plugin.wallets().transfer(profileA, profileB.wallet, entry.getKey(), entry.getValue());
                    doneA.add(new int[]{entry.getKey(), entry.getValue()});
                }
                for (Map.Entry<Integer, Integer> entry : fromB.entrySet()) {
                    plugin.wallets().transfer(profileB, profileA.wallet, entry.getKey(), entry.getValue());
                    doneB.add(new int[]{entry.getKey(), entry.getValue()});
                }
                HoldItems.View bagA = plugin.wallets().bagOf(profileA.wallet);
                HoldItems.View bagB = plugin.wallets().bagOf(profileB.wallet);
                plugin.getServer().getScheduler().runTask(plugin, () -> finish(session, first, second, bagA, bagB, null));
            } catch (Exception exception) {
                try {
                    for (int[] moved : doneA) {
                        plugin.wallets().transfer(profileB, profileA.wallet, moved[0], moved[1]);
                    }
                    for (int[] moved : doneB) {
                        plugin.wallets().transfer(profileA, profileB.wallet, moved[0], moved[1]);
                    }
                } catch (Exception rollback) {
                    plugin.getLogger().warning("Trade rollback failed: " + rollback.getMessage());
                }
                String message = exception.getMessage() == null ? "Trade was rejected." : exception.getMessage();
                plugin.getServer().getScheduler().runTask(plugin, () -> finish(session, first, second, null, null, message));
            }
        });
    }

    private void finish(Session session, Player first, Player second, HoldItems.View bagA, HoldItems.View bagB, String error) {
        session.busy = false;
        if (error != null) {
            session.acceptA = false;
            session.acceptB = false;
            if (first == null || !first.isOnline() || second == null || !second.isOnline()) {
                abort(session, error);
                return;
            }
            refresh(session);
            tell(first, error, NamedTextColor.RED);
            tell(second, error, NamedTextColor.RED);
            return;
        }
        session.closing = true;
        clearOffers(session);
        release(session.first, first, bagA);
        release(session.second, second, bagB);
        sessions.remove(session.first);
        sessions.remove(session.second);
    }

    private void release(UUID id, Player player, HoldItems.View bag) {
        if (player != null && player.isOnline()) {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                player.setItemOnCursor(null);
            }
            plugin.hold().showChain(player, bag);
            player.closeInventory();
            player.sendMessage(Component.text("Trade signed.", NamedTextColor.GREEN));
            plugin.hold().resume(player);
            return;
        }
        plugin.hold().resumeOffline(id);
    }

    private void abort(Session session, String reason) {
        if (session.busy) {
            return;
        }
        session.closing = true;
        sessions.remove(session.first);
        sessions.remove(session.second);
        end(plugin.getServer().getPlayer(session.first), session.first, session.offerA, reason);
        end(plugin.getServer().getPlayer(session.second), session.second, session.offerB, reason);
    }

    private void end(Player player, UUID id, ItemStack[] offer, String reason) {
        if (player == null || !player.isOnline()) {
            plugin.hold().resumeOffline(id);
            return;
        }
        List<ItemStack> extra = new ArrayList<>();
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            player.setItemOnCursor(null);
            extra.add(cursor.clone());
        }
        returnOffer(player, offer, extra);
        player.closeInventory();
        player.sendMessage(Component.text(reason, NamedTextColor.YELLOW));
    }

    private void returnOffer(Player player, ItemStack[] offer, List<ItemStack> extra) {
        List<ItemStack> waiting = new ArrayList<>(extra);
        for (ItemStack stack : offer) {
            if (stack != null && !stack.getType().isAir()) {
                waiting.add(stack.clone());
            }
        }
        for (int i = 0; i < offer.length; i++) {
            offer[i] = null;
        }
        deliver(player, waiting);
    }

    private void deliver(Player player, List<ItemStack> waiting) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack stack : waiting) {
            left.addAll(player.getInventory().addItem(stack).values());
        }
        if (left.isEmpty()) {
            plugin.hold().resume(player);
            return;
        }
        player.sendMessage(Component.text("Free an inventory slot. The trade items are waiting.", NamedTextColor.RED));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                deliver(player, left);
            } else {
                plugin.hold().resumeOffline(player.getUniqueId());
            }
        }, 20L);
    }

    private void clearOffers(Session session) {
        for (int i = 0; i < OFFER.length; i++) {
            session.offerA[i] = null;
            session.offerB[i] = null;
        }
    }

    private Map<Integer, Integer> sums(ItemStack[] offer) {
        Map<Integer, Integer> sums = new LinkedHashMap<>();
        for (ItemStack stack : offer) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            int id = HoldItems.id(stack, plugin);
            if (id == 0) {
                continue;
            }
            sums.merge(id, stack.getAmount(), Integer::sum);
        }
        return sums;
    }

    private void tell(Player player, String text, NamedTextColor color) {
        if (player != null && player.isOnline()) {
            player.sendMessage(Component.text(text, color));
        }
    }

    private static boolean isOffer(int slot) {
        for (int offer : OFFER) {
            if (offer == slot) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack named(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class Session {
        private final UUID first;
        private final UUID second;
        private final ItemStack[] offerA = new ItemStack[OFFER.length];
        private final ItemStack[] offerB = new ItemStack[OFFER.length];
        private Inventory viewA;
        private Inventory viewB;
        private boolean acceptA;
        private boolean acceptB;
        private boolean busy;
        private boolean closing;

        private Session(UUID first, UUID second) {
            this.first = first;
            this.second = second;
        }
    }

    private static final class TradeHolder implements InventoryHolder {
        private final Session session;
        private final UUID viewer;
        private Inventory inventory;

        private TradeHolder(Session session, UUID viewer) {
            this.session = session;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
