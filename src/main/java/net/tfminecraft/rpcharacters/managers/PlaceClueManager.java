package net.tfminecraft.rpcharacters.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.ClueGiver;

public final class PlaceClueManager implements Listener {

	private static final long AWAIT_TIMEOUT_MS = 30_000L;

	private final Map<UUID, String> awaiting = new HashMap<>();
	private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();

	public boolean startAwaiting(Player player, String text) {
		String validationError = ClueFormatter.validate(text);
		if (validationError != null) {
			RPTexts.send(player, validationError);
			return false;
		}

		UUID playerId = player.getUniqueId();
		endAwaiting(playerId);
		awaiting.put(playerId, ClueFormatter.format(text));
		RPTexts.send(player, RPTexts.WARN + "Right-click a target block to place this clue.");
		RPTexts.send(player, RPTexts.MUTED + "A spawned clue marker will appear near the target.");
		scheduleTimeout(player);
		return true;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		Player player = event.getPlayer();
		String pending = awaiting.get(player.getUniqueId());
		if (pending == null) {
			return;
		}
		Block clicked = event.getClickedBlock();
		if (clicked == null) {
			return;
		}

		event.setCancelled(true);
		endAwaiting(player.getUniqueId());
		placeWorldClue(player, clicked, pending);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		endAwaiting(event.getPlayer().getUniqueId());
	}

	private void placeWorldClue(Player player, Block targetBlock, String text) {
		Location searchFrom = targetBlock.getLocation().add(0.5, 1.0, 0.5);
		Location anchor = ClueGiver.getRandomValidClueLocation(searchFrom);
		if (anchor == null) {
			anchor = ClueGiver.getRandomValidClueLocation(player.getLocation());
		}
		if (anchor == null) {
			RPTexts.send(player, RPTexts.ERROR + "No valid clue anchor found near that block. Try another location.");
			return;
		}

		SpawnedClue spawned = ClueGiver.spawnClueWithText(anchor, targetBlock.getLocation(), player, text, false);
		if (spawned == null) {
			RPTexts.send(player, RPTexts.ERROR + "Could not spawn world clue. Ensure you have an active character.");
			return;
		}
		RPTexts.send(player, RPTexts.SUCCESS + "Spawned world clue linked to that block.");
	}

	private void scheduleTimeout(Player player) {
		UUID playerId = player.getUniqueId();
		BukkitTask task = RPCharacters.plugin.getServer().getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (awaiting.remove(playerId) != null) {
				Player online = RPCharacters.plugin.getServer().getPlayer(playerId);
				if (online != null) {
					RPTexts.send(online, RPTexts.MUTED + "Clue placement timed out.");
				}
			}
			timeoutTasks.remove(playerId);
		}, AWAIT_TIMEOUT_MS / 50L);
		timeoutTasks.put(playerId, task);
	}

	private void endAwaiting(UUID playerId) {
		awaiting.remove(playerId);
		BukkitTask task = timeoutTasks.remove(playerId);
		if (task != null) {
			task.cancel();
		}
	}
}
