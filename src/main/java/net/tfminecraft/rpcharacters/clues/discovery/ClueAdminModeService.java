package net.tfminecraft.rpcharacters.clues.discovery;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Permissions;

public final class ClueAdminModeService {

	private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

	private ClueAdminModeService() {}

	public static boolean isEnabled(Player player) {
		return player != null && ENABLED.contains(player.getUniqueId());
	}

	public static boolean canUse(Player player) {
		return player != null && Permissions.isAdmin(player);
	}

	public static boolean setEnabled(Player player, boolean enabled) {
		if (!canUse(player)) {
			return false;
		}
		if (enabled) {
			ENABLED.add(player.getUniqueId());
		} else {
			ENABLED.remove(player.getUniqueId());
		}
		return true;
	}

	public static void clear(Player player) {
		if (player != null) {
			ENABLED.remove(player.getUniqueId());
		}
	}
}
