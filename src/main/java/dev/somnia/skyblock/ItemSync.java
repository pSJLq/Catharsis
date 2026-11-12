package dev.somnia.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla item use that changes the bag without a block break/place or an inventory click: bone meal, composter
 * input, buckets, eating, thrown items, dyes/shears/name tags on mobs, armor equipped by right-click, picked-up
 * items. The server already applied the change to the live inventory (server-authoritative); this signs that live
 * bag into SkyWord right after (WordChain.commitLocal, the same trusted write as chest and merchant moves), so an
 * unrelated later slot check can no longer paint the old signed bag back (the bone meal / compost duplication).
 * A write is only sent when the bag really differs from the signed one.
 *
 * The composter's bone meal goes straight into the bag (stash when full) instead of popping onto the ground.
 */
final class ItemSync implements Listener {
    private final SomniaSkyblockPlugin plugin;
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    ItemSync(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean chainBag(Player player) {
        return plugin.wordMode() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.hold().offchain(player);
    }

    /** Next tick (after vanilla applied the use), sign the live bag once. */
    void sync(Player player) {
        if (!chainBag(player) || !queued.add(player.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            queued.remove(player.getUniqueId());
            if (player.isOnline() && chainBag(player)) {
                plugin.words().commitLocal(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onComposter(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.COMPOSTER || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (!chainBag(player) || !(block.getBlockData() instanceof Levelled level) || level.getLevel() < level.getMaximumLevel()) {
            return;
        }
        // Full composter: take the bone meal into the bag instead of dropping it on the ground.
        event.setCancelled(true);
        if (!plugin.words().ready(player)) {
            player.sendMessage(Component.text("The chain inventory is still loading.", NamedTextColor.RED));
            return;
        }
        level.setLevel(0);
        block.setBlockData(level);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_COMPOSTER_EMPTY, 1f, 1f);
        plugin.market().giveOrStash(player.getUniqueId(), "minecraft:bone_meal", 1, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY && event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block clicked = event.getClickedBlock();
        boolean composter = clicked != null && clicked.getType() == Material.COMPOSTER;
        org.bukkit.inventory.ItemStack hand = event.getItem();
        if (!composter && hand != null && hand.getType().isBlock()) {
            return; // block placing has its own signed path (WordChain.onPlace)
        }
        if (!composter && (hand == null || hand.getType().isAir()) && a == Action.RIGHT_CLICK_AIR) {
            return;
        }
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (event.getPlayer() != null) {
            sync(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        sync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            sync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            sync(player);
        }
    }
}
