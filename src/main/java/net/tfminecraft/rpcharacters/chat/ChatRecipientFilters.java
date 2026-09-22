package net.tfminecraft.rpcharacters.chat;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.entity.Player;

/**
 * Shared read-perm and channel-visibility filtering for chat recipient resolution.
 */
public final class ChatRecipientFilters {

	private ChatRecipientFilters() {}

	public static boolean canReceive(Player player, ChatChannel channel) {
		if (player == null || channel == null) {
			return false;
		}
		String readPerm = channel.getReadPermission();
		if (readPerm != null && !readPerm.isBlank() && !player.hasPermission(readPerm)) {
			return false;
		}
		return ChatChannelPreferenceManager.get().isChannelVisible(player, channel.getId());
	}

	public static Set<Player> filterCandidates(Player sender, ChatChannel channel, Collection<Player> candidates) {
		Set<Player> recipients = new HashSet<>();
		if (sender != null && canReceive(sender, channel)) {
			recipients.add(sender);
		}
		if (candidates == null) {
			return recipients;
		}
		for (Player candidate : candidates) {
			if (candidate == null || candidate.equals(sender)) {
				continue;
			}
			if (canReceive(candidate, channel)) {
				recipients.add(candidate);
			}
		}
		return recipients;
	}
}
