package dev.somnia.skyblock;

import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ChainClient {
    private final SomniaSkyblockPlugin plugin;
    private final boolean enabled;
    private final Set<UUID> sending = ConcurrentHashMap.newKeySet();

    ChainClient(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        boolean flag = plugin.getConfig().getBoolean("chain.enabled");
        String contract = plugin.getConfig().getString("chain.profile-contract", "");
        boolean ready = contract != null && !contract.isBlank();
        this.enabled = flag && ready;
        if (enabled) {
            plugin.getLogger().info("Island contract " + contract);
        } else if (flag) {
            plugin.getLogger().warning("chain.enabled is set, but the island contract address is empty. Progress stays on the server.");
        }
    }

    boolean enabled() {
        return enabled;
    }

    void ensureIsland(Profile profile) {
        if (!enabled || profile.wallet == null || profile.wallet.isBlank()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BigInteger existing = plugin.wallets().memberIsland(profile.wallet);
                if (existing.signum() > 0) {
                    return;
                }
                String hash = plugin.wallets().createIsland(profile);
                plugin.getLogger().info("Island deed for " + profile.wallet + " " + hash);
            } catch (Exception exception) {
                String why = String.valueOf(exception.getMessage());
                if (why.startsWith("Purse has")) {
                    plugin.getLogger().info("Island deed for " + profile.wallet + " waits for gas: " + why);
                } else {
                    plugin.getLogger().warning("Could not create the island deed: " + why);
                }
            }
        });
    }
}
