package net.tfminecraft.rpcharacters.managers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.enums.ClueAddResult;
import net.tfminecraft.rpcharacters.enums.CreationGuiContext;

public class ClueInputManager implements Listener {

	private static final Map<UUID, String> pendingCharacterId = new ConcurrentHashMap<>();
	private static final Set<UUID> creationSummaryClueInput = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> skipConversationTracking = ConcurrentHashMap.newKeySet();

	public static boolean consumeConversationSkip(UUID playerId) {
		return skipConversationTracking.remove(playerId);
	}

	public static void beginInput(Player player, String characterId) {
		beginInput(player, characterId, false);
	}

	public static void beginInput(Player player, String characterId, boolean fromCreationSummary) {
		UUID id = player.getUniqueId();
		pendingCharacterId.put(id, characterId);
		if (fromCreationSummary) {
			creationSummaryClueInput.add(id);
		}
		player.closeInventory();
		RPTexts.send(player, RPTexts.WARN + "Type your clue in chat.");
		RPTexts.send(player, RPTexts.MUTED + "Clues must be between " + RPTexts.WARN + Cache.clueMinLength
				+ RPTexts.MUTED + " and " + RPTexts.WARN + Cache.clueMaxLength + RPTexts.MUTED + " characters.");
	}

	public static String getPendingCharacterId(Player player) {
		return pendingCharacterId.get(player.getUniqueId());
	}

	public static boolean isPending(Player player) {
		return pendingCharacterId.containsKey(player.getUniqueId());
	}

	public static void cancel(Player player) {
		UUID id = player.getUniqueId();
		pendingCharacterId.remove(id);
		creationSummaryClueInput.remove(id);
	}

	// Retain Bukkit chat-event ordering and String message semantics for existing integrations.
	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onChat(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		UUID id = player.getUniqueId();
		if (!pendingCharacterId.containsKey(id)) {
			return;
		}

		creationSummaryClueInput.remove(id);
		skipConversationTracking.add(id);
		event.setCancelled(true);
		String message = event.getMessage();
		String characterId = pendingCharacterId.remove(id);

		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> handleClueInput(player, characterId, message));
	}

	private static void handleClueInput(Player player, String characterId, String message) {
		RPCharacter character = CreationManager.resolveCharacter(player, characterId);
		if (character == null) {
			PlayerData pd = PlayerManager.get(player);
			if (pd != null) {
				character = pd.getCharacterById(characterId);
			}
		}
		if (character == null) {
			RPTexts.send(player, RPTexts.ERROR + "Could not find that character.");
			return;
		}
		if (!character.getOwner().equals(player)) {
			RPTexts.send(player, RPTexts.ERROR + "You can only add clues to your own characters.");
			return;
		}

		ClueAddResult result = character.addPlayerClue(message);
		if (result != ClueAddResult.SUCCESS) {
			RPTexts.send(player, character.getClueAddErrorMessage(result));
			UUID id = player.getUniqueId();
			pendingCharacterId.put(id, characterId);
			if (CreationManager.isDraftCharacter(player, characterId)) {
				creationSummaryClueInput.add(id);
			}
			return;
		}

		boolean draft = CreationManager.isDraftCharacter(player, characterId);
		CharacterCreation cc = CreationManager.activeCreators.get(player);
		boolean editing = cc != null && cc.isEditing();
		if (!draft) {
			RPCharacters.getPlayerManager().savePlayer(player);
			RPCharacters.getPlayerManager().reevaluateFreeze(player);
		} else if (editing) {
			cc.persistEdits();
		}

		InventoryManager inv = new InventoryManager();
		if (cc != null && (draft || editing)) {
			CreationGuiContext context = editing ? CreationGuiContext.EDIT_SUMMARY : CreationGuiContext.CREATION_SUMMARY;
			inv.cluesView(player, character, context, cc);
		} else {
			inv.cluesView(player, character);
		}
		player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		cancel(event.getPlayer());
	}
}
