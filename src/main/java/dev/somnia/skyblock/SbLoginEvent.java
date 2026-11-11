package dev.somnia.skyblock;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Fired once a joining player has logged in (password or remembered session). Game join logic listens to this, not PlayerJoinEvent. */
public final class SbLoginEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location back;
    private final boolean registered;

    SbLoginEvent(Player player, Location back, boolean registered) {
        super(player);
        this.back = back;
        this.registered = registered;
    }

    /** Where the player was when they last played (null: send them to their island). */
    Location back() {
        return back;
    }

    /** True right after /register (first login). */
    boolean registered() {
        return registered;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
