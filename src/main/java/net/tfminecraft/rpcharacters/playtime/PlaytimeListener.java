package net.tfminecraft.rpcharacters.playtime;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;

/**
 * Keeps the playtime index current at the two moments the accrual tick would
 * otherwise lag by up to a minute: a login, and a switch to another character.
 */
public class PlaytimeListener implements Listener {

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent e) {
		PlaytimeService.refresh(e.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onCharacterActivated(CharacterActivatedEvent e) {
		Player owner = e.getOwner();
		if (owner == null) return;
		PlaytimeService.refresh(owner);
	}
}
