package net.tfminecraft.rpcharacters.evilrp;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class EvilRpListener implements Listener {

	private static final long JOIN_REMINDER_DELAY_TICKS = 60L;

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		var player = event.getPlayer();
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (player.isOnline()) {
				EvilRpService.sendJoinReminder(player);
			}
		}, JOIN_REMINDER_DELAY_TICKS);
	}

	// Before PlayerManager saves and unloads the player's characters.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onQuit(PlayerQuitEvent event) {
		EvilRpService.handleQuit(event.getPlayer());
	}
}
