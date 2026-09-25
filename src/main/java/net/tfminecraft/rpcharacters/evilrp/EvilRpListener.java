package net.tfminecraft.rpcharacters.evilrp;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.pvp.PvpStrikeService;

public final class EvilRpListener implements Listener {

	private static final long JOIN_REMINDER_DELAY_TICKS = 60L;

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		var player = event.getPlayer();
		PvpStrikeService.handleJoin(player);
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (player.isOnline()) {
				EvilRpService.sendJoinReminder(player);
			}
		}, JOIN_REMINDER_DELAY_TICKS);
	}
}
