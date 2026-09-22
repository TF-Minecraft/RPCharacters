package net.tfminecraft.rpcharacters.chat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.rpcharacters.loaders.ChatLoader;

public final class ChatChannelPreferenceManager implements Listener {

	private static final ChatChannelPreferenceManager INSTANCE = new ChatChannelPreferenceManager();

	private final Map<UUID, String> switchedChannels = new ConcurrentHashMap<>();
	private final Map<UUID, Set<String>> hiddenChannels = new ConcurrentHashMap<>();

	private ChatChannelPreferenceManager() {}

	public static ChatChannelPreferenceManager get() {
		return INSTANCE;
	}

	public ChatChannel getActiveChannel(Player player) {
		String channelId = getSwitchedChannelId(player);
		if (channelId != null) {
			ChatChannel channel = ChatLoader.getChannel(channelId);
			if (channel != null) {
				return channel;
			}
		}
		return ChatLoader.getDefaultChannel();
	}

	public String getSwitchedChannelId(Player player) {
		if (player == null) {
			return null;
		}
		return switchedChannels.get(player.getUniqueId());
	}

	public boolean setSwitchedChannel(Player player, String channelId) {
		if (player == null || channelId == null) {
			return false;
		}
		switchedChannels.put(player.getUniqueId(), channelId.toLowerCase(Locale.ROOT));
		return true;
	}

	public boolean isChannelVisible(Player player, String channelId) {
		if (player == null || channelId == null) {
			return true;
		}
		Set<String> hidden = hiddenChannels.get(player.getUniqueId());
		if (hidden == null || hidden.isEmpty()) {
			return true;
		}
		return !hidden.contains(channelId.toLowerCase(Locale.ROOT));
	}

	public boolean toggleChannelVisibility(Player player, String channelId) {
		if (player == null || channelId == null) {
			return true;
		}
		String normalized = channelId.toLowerCase(Locale.ROOT);
		Set<String> hidden = hiddenChannels.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
		if (hidden.contains(normalized)) {
			hidden.remove(normalized);
			return true;
		}
		hidden.add(normalized);
		return false;
	}

	public void clear(Player player) {
		if (player == null) {
			return;
		}
		UUID id = player.getUniqueId();
		switchedChannels.remove(id);
		hiddenChannels.remove(id);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		clear(event.getPlayer());
	}
}
