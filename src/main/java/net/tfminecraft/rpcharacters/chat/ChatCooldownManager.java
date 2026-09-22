package net.tfminecraft.rpcharacters.chat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ChatCooldownManager implements Listener {

	private static final ChatCooldownManager INSTANCE = new ChatCooldownManager();
	private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

	private ChatCooldownManager() {}

	public static ChatCooldownManager get() {
		return INSTANCE;
	}

	public boolean isOnCooldown(Player player, String channelId, int cooldownSeconds) {
		if (player == null || cooldownSeconds <= 0) {
			return false;
		}
		Map<String, Long> playerCooldowns = cooldowns.get(player.getUniqueId());
		if (playerCooldowns == null) {
			return false;
		}
		Long expiresAt = playerCooldowns.get(channelId);
		if (expiresAt == null) {
			return false;
		}
		return System.currentTimeMillis() < expiresAt;
	}

	public int getRemainingSeconds(Player player, String channelId) {
		Map<String, Long> playerCooldowns = cooldowns.get(player.getUniqueId());
		if (playerCooldowns == null) {
			return 0;
		}
		Long expiresAt = playerCooldowns.get(channelId);
		if (expiresAt == null) {
			return 0;
		}
		long remainingMs = expiresAt - System.currentTimeMillis();
		if (remainingMs <= 0) {
			return 0;
		}
		return (int) Math.ceil(remainingMs / 1000.0);
	}

	public void applyCooldown(Player player, String channelId, int cooldownSeconds) {
		if (player == null || cooldownSeconds <= 0) {
			return;
		}
		cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
				.put(channelId, System.currentTimeMillis() + cooldownSeconds * 1000L);
	}

	public void clear(Player player) {
		if (player == null) {
			return;
		}
		cooldowns.remove(player.getUniqueId());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		clear(event.getPlayer());
	}
}
