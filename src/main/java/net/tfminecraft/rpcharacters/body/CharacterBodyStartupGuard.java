package net.tfminecraft.rpcharacters.body;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

/** Keeps a configured body server closed when its storage or migration configuration is unreadable. */
public final class CharacterBodyStartupGuard implements Listener {
    private static final String MESSAGE = "Character storage is unavailable. Please contact staff.";

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MESSAGE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PlayerLoginEvent event) {
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, MESSAGE);
    }
}
