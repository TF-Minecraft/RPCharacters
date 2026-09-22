package net.tfminecraft.rpcharacters.profile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ProfileViewCooldownManager implements Listener {

	private static final ProfileViewCooldownManager INSTANCE = new ProfileViewCooldownManager();
	private final Map<UUID, Long> cooldowns = new HashMap<>();

	private ProfileViewCooldownManager() {}

	public static ProfileViewCooldownManager get() {
		return INSTANCE;
	}

	public boolean isOnCooldown(Player player, int cooldownSeconds) {
		if (player == null || cooldownSeconds <= 0) {
			return false;
		}
		Long expiresAt = cooldowns.get(player.getUniqueId());
		if (expiresAt == null) {
			return false;
		}
		return System.currentTimeMillis() < expiresAt;
	}

	public int getRemainingSeconds(Player player) {
		Long expiresAt = cooldowns.get(player.getUniqueId());
		if (expiresAt == null) {
			return 0;
		}
		long remainingMs = expiresAt - System.currentTimeMillis();
		if (remainingMs <= 0) {
			return 0;
		}
		return (int) Math.ceil(remainingMs / 1000.0);
	}

	public void applyCooldown(Player player, int cooldownSeconds) {
		if (player == null || cooldownSeconds <= 0) {
			return;
		}
		cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
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
