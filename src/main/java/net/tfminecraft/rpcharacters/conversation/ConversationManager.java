package net.tfminecraft.rpcharacters.conversation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.identity.MaskService;

public class ConversationManager implements Listener {

	private static final Map<String, PendingConversation> pending = new HashMap<>();
	private static final Map<String, Long> activeSessions = new HashMap<>();

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onCharacterChat(CharacterChatEvent event) {
		String channelId = event.getChannel();
		if (channelId == null) {
			return;
		}
		String normalized = channelId.toLowerCase(Locale.ROOT);
		if (normalized.equals("looc") || normalized.equals("ooc")) {
			return;
		}
		if (event.isMasked()) {
			return;
		}

		Player player = event.getSender();
		if (player == null || player.getGameMode() != GameMode.SURVIVAL) {
			return;
		}

		ChatChannel channel = ChatLoader.getChannel(channelId);
		if (channel == null) {
			return;
		}

		recordChannelMessage(player, channel);
	}

	public static void recordChannelMessage(Player speaker, ChatChannel channel) {
		if (speaker == null || channel == null) {
			return;
		}

		PlayerData speakerData = PlayerManager.get(speaker);
		if (speakerData == null || !speakerData.hasActiveCharacter()) {
			return;
		}

		RPCharacter speakerCharacter = speakerData.getActiveCharacter();
		if (speakerCharacter == null) {
			return;
		}

		long now = System.currentTimeMillis();
		pruneExpired(now);

		Location origin = speaker.getLocation();
		int replyRange = channel.getRange();

		processReplies(speaker, speakerCharacter, origin, replyRange, now);

		int outboundRange = channel.getRange();
		if (outboundRange <= 0) {
			return;
		}
		double rangeSq = (double) outboundRange * outboundRange;
		long expiresAt = now + (Cache.conversationReplyTimeoutSeconds * 1000L);

		for (Player target : speaker.getWorld().getPlayers()) {
			if (target.equals(speaker) || target.getGameMode() != GameMode.SURVIVAL) {
				continue;
			}
			if (MaskService.isMasked(target)) {
				continue;
			}
			if (target.getLocation().distanceSquared(origin) > rangeSq) {
				continue;
			}

			PlayerData listenerData = PlayerManager.get(target);
			if (listenerData == null || !listenerData.hasActiveCharacter()) {
				continue;
			}
			RPCharacter listenerCharacter = listenerData.getActiveCharacter();
			if (listenerCharacter == null) {
				continue;
			}

			if (hasActiveSession(speakerCharacter, listenerCharacter, now)) {
				refreshActiveSession(speakerCharacter, listenerCharacter, now);
			}

			String key = PendingConversation.key(listenerCharacter.getId(), speakerCharacter.getId());
			pending.put(key, new PendingConversation(
					speakerCharacter.getId(),
					listenerCharacter.getId(),
					speaker.getUniqueId(),
					expiresAt));
		}
	}

	private static void processReplies(Player replier, RPCharacter replierCharacter, Location replyOrigin,
			int replyRange, long now) {
		if (replyRange <= 0) {
			return;
		}
		double rangeSq = (double) replyRange * replyRange;
		Iterator<Map.Entry<String, PendingConversation>> iterator = pending.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<String, PendingConversation> entry = iterator.next();
			PendingConversation pendingConversation = entry.getValue();

			if (!replierCharacter.getId().equals(pendingConversation.getListenerCharacterId())) {
				continue;
			}
			if (pendingConversation.isExpired(now)) {
				iterator.remove();
				continue;
			}

			Player speakerPlayer = Bukkit.getPlayer(pendingConversation.getSpeakerPlayerId());
			if (speakerPlayer == null || !speakerPlayer.isOnline()) {
				continue;
			}
			if (MaskService.isMasked(speakerPlayer)) {
				continue;
			}
			if (!speakerPlayer.getWorld().equals(replyOrigin.getWorld())) {
				continue;
			}
			if (speakerPlayer.getLocation().distanceSquared(replyOrigin) > rangeSq) {
				continue;
			}

			PlayerData speakerData = PlayerManager.get(speakerPlayer);
			if (speakerData == null || !speakerData.hasActiveCharacter()) {
				continue;
			}
			RPCharacter speakerCharacter = speakerData.getActiveCharacter();
			if (speakerCharacter == null
					|| !speakerCharacter.getId().equals(pendingConversation.getSpeakerCharacterId())) {
				continue;
			}

			refreshActiveSession(speakerCharacter, replierCharacter, now);
			iterator.remove();

			if (speakerCharacter.canCountConversationWith(replierCharacter, now)) {
				speakerCharacter.recordConversationWith(replierCharacter, now);
				replierCharacter.recordConversationWith(speakerCharacter, now);
			}
		}
	}

	public static boolean isInActiveConversation(RPCharacter character, long nowMs) {
		if (character == null) {
			return false;
		}
		String characterId = character.getId();
		if (characterId == null || characterId.isBlank()) {
			return false;
		}
		for (Map.Entry<String, Long> entry : activeSessions.entrySet()) {
			if (entry.getValue() <= nowMs) {
				continue;
			}
			if (pairInvolvesCharacter(entry.getKey(), characterId)) {
				return true;
			}
		}
		return false;
	}

	public static int countActiveSessions(RPCharacter character, long nowMs) {
		if (character == null) {
			return 0;
		}
		String characterId = character.getId();
		if (characterId == null || characterId.isBlank()) {
			return 0;
		}
		int count = 0;
		for (Map.Entry<String, Long> entry : activeSessions.entrySet()) {
			if (entry.getValue() <= nowMs) {
				continue;
			}
			if (pairInvolvesCharacter(entry.getKey(), characterId)) {
				count++;
			}
		}
		return count;
	}

	public static void refreshActiveSession(RPCharacter first, RPCharacter second, long nowMs) {
		if (first == null || second == null) {
			return;
		}
		String firstId = first.getId();
		String secondId = second.getId();
		if (firstId == null || secondId == null || firstId.isBlank() || secondId.isBlank()) {
			return;
		}
		long expiresAt = nowMs + (Cache.conversationReplyTimeoutSeconds * 1000L);
		activeSessions.put(pairKey(firstId, secondId), expiresAt);
	}

	private static boolean hasActiveSession(RPCharacter first, RPCharacter second, long nowMs) {
		if (first == null || second == null) {
			return false;
		}
		String firstId = first.getId();
		String secondId = second.getId();
		if (firstId == null || secondId == null) {
			return false;
		}
		Long expiresAt = activeSessions.get(pairKey(firstId, secondId));
		return expiresAt != null && expiresAt > nowMs;
	}

	static String pairKey(String idA, String idB) {
		if (idA.compareTo(idB) <= 0) {
			return idA + ":" + idB;
		}
		return idB + ":" + idA;
	}

	private static boolean pairInvolvesCharacter(String pairKey, String characterId) {
		int separator = pairKey.indexOf(':');
		if (separator < 0) {
			return false;
		}
		String left = pairKey.substring(0, separator);
		String right = pairKey.substring(separator + 1);
		return characterId.equals(left) || characterId.equals(right);
	}

	private static void pruneExpired(long now) {
		pending.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
		activeSessions.entrySet().removeIf(entry -> entry.getValue() <= now);
	}

	public static List<Map.Entry<String, Integer>> getTopPartners(RPCharacter character, int limit) {
		if (character == null || limit <= 0) {
			return List.of();
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(character.getConversationCounts().entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		if (sorted.size() <= limit) {
			return sorted;
		}
		return sorted.subList(0, limit);
	}
}
