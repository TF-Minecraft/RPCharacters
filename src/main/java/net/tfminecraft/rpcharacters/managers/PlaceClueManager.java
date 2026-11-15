package net.tfminecraft.rpcharacters.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.ClueGiver;

public final class PlaceClueManager implements Listener {

	private static final long AWAIT_TIMEOUT_MS = 30_000L;
	/** How far /rpcharacter removeclue reaches for the player's own placed clues. */
	public static final double REMOVE_RADIUS = 10.0;

	private final Map<UUID, String> awaiting = new HashMap<>();
	private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();
	private final Map<UUID, Integer> placedOnTick = new HashMap<>();

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
		UUID playerId = player.getUniqueId();
		if (event.getHand() == EquipmentSlot.OFF_HAND) {
			// The off-hand half of the click that placed a clue must not also use the off-hand item.
			Integer placedTick = placedOnTick.remove(playerId);
			if (placedTick != null && placedTick == Bukkit.getCurrentTick()) {
				event.setCancelled(true);
			}
			return;
		}
		String pending = awaiting.get(playerId);
		if (pending == null) {
			return;
		}
		Block clicked = event.getClickedBlock();
		if (clicked == null) {
			return;
		}
		if (!Permissions.canPlaceClues(player)) {
			event.setCancelled(true);
			endAwaiting(playerId);
			RPTexts.send(player, RPTexts.ERROR + "You can no longer place clues.");
			return;
		}
		// Knockouts, the selection room, locks and region protection deny the click before us; respect them.
		// Graves act on cancelled clicks too, so a click on one stays a grave click.
		if (event.useInteractedBlock() == Event.Result.DENY || GraveManager.get().getAt(clicked) != null) {
			if (event.getItem() != null && player.hasCooldown(event.getItem().getType())) {
				// Paper also denies the block while the held item recovers.
				RPTexts.send(player, RPTexts.ERROR + "Wait for your held item to recover, or switch items.");
			} else {
				RPTexts.send(player, RPTexts.ERROR + "You can't place a clue there. Right-click another block.");
			}
			return;
		}

		event.setCancelled(true);
		endAwaiting(playerId);
		if (CreationManager.activeCreators.containsKey(player)) {
			RPTexts.send(player, RPTexts.ERROR + "You are busy creating a character");
			return;
		}
		placedOnTick.put(playerId, Bukkit.getCurrentTick());
		placeWorldClue(player, clicked, pending);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		endAwaiting(event.getPlayer().getUniqueId());
		placedOnTick.remove(event.getPlayer().getUniqueId());
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

		SpawnedClue spawned = ClueGiver.spawnPlacedClue(anchor, targetBlock.getLocation(), player, text);
		if (spawned == null) {
			RPTexts.send(player, RPTexts.ERROR + "Could not spawn world clue. Ensure you have an active character.");
			return;
		}
		RPTexts.send(player, RPTexts.SUCCESS + "Placed a clue linked to that block.");
		RPTexts.send(player, RPTexts.MUTED + "Remove your clues nearby with " + RPTexts.COMMAND
				+ "/rpcharacter removeclue" + RPTexts.MUTED + ".");
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
