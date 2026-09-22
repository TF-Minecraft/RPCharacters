package net.tfminecraft.rpcharacters.party;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PartyListener implements Listener {

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		PartyManager.get().handleQuit(event.getPlayer());
	}
}
