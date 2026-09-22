package net.tfminecraft.rpcharacters.managers;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.clues.discovery.CluePotencyService;

public final class ClueDisturbanceListener implements Listener {

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		Block block = event.getClickedBlock();
		if (block == null) return;

		Player player = event.getPlayer();
		for (SpawnedClue clue : SpawnedClueManager.get().getCluesLinkedToBlock(block.getLocation())) {
			CluePotencyService.applyTargetInteractDisturbance(clue);
		}
	}
}
