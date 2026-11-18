package net.tfminecraft.rpcharacters.wardrobe;

import java.util.Map;
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
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Chat prompt for a skin description, started by shift-clicking a skin in the wardrobe menu
 * or by the describe command without text. The next chat line is the description (or
 * "clear" / "cancel"); it never reaches any chat channel.
 */
public final class WardrobeDescriptionInput implements Listener {

	static final long TIMEOUT_MILLIS = 60L * 1000L;

	private record Pending(String characterId, String slot, long startedAt) {}

	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	public static void begin(Player player, String slot) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return;
		}
		PENDING.put(player.getUniqueId(),
			new Pending(pd.getActiveCharacter().getId(), slot, System.currentTimeMillis()));
		String label = WardrobeService.labelForSlot(WardrobeCache.get(player), slot);
		RPTexts.send(player, RPTexts.WARN + "Type the description others read while you wear "
			+ label + " in chat.");
		RPTexts.send(player, RPTexts.MUTED + "Between " + RPTexts.WARN + Cache.characterDescriptionMinLength
			+ RPTexts.MUTED + " and " + RPTexts.WARN + Cache.characterDescriptionMaxLength
			+ RPTexts.MUTED + " characters. Type " + RPTexts.WARN + "clear" + RPTexts.MUTED
			+ " to use your character description, or " + RPTexts.WARN + "cancel" + RPTexts.MUTED + ".");
	}

	public static boolean isPending(Player player) {
		if (player == null) {
			return false;
		}
		Pending pending = PENDING.get(player.getUniqueId());
		return pending != null && !expired(pending);
	}

	public static void cancel(Player player) {
		if (player != null) {
			PENDING.remove(player.getUniqueId());
		}
	}

	private static boolean expired(Pending pending) {
		return System.currentTimeMillis() - pending.startedAt() > TIMEOUT_MILLIS;
	}

	// Same Bukkit chat event the clue prompt uses; LOWEST so no chat channel sees the line.
	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.LOWEST)
	public void onChat(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		Pending pending = PENDING.remove(player.getUniqueId());
		if (pending == null || expired(pending)) {
			return;
		}
		event.setCancelled(true);
		String message = event.getMessage();
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> handle(player, pending, message));
	}

	private static void handle(Player player, Pending pending, String message) {
		if (!player.isOnline()) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()
				|| !pd.getActiveCharacter().getId().equalsIgnoreCase(pending.characterId())) {
			RPTexts.send(player, RPTexts.ERROR + "Your active character changed. Open the wardrobe again.");
			return;
		}
		String text = message == null ? "" : message.trim();
		if (text.equalsIgnoreCase("cancel")) {
			RPTexts.send(player, RPTexts.MUTED + "Description unchanged.");
			return;
		}
		if (!WardrobeDescriptions.submit(player, pending.slot(), text)) {
			// The text was refused: ask again, like the clue prompt does.
			PENDING.put(player.getUniqueId(),
				new Pending(pending.characterId(), pending.slot(), System.currentTimeMillis()));
			RPTexts.send(player, RPTexts.MUTED + "Type it again, or " + RPTexts.WARN + "cancel" + RPTexts.MUTED + ".");
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		PENDING.remove(event.getPlayer().getUniqueId());
	}
}
