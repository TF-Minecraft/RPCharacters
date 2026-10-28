package net.tfminecraft.rpcharacters.wardrobe;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.ticxo.modelengine.api.ModelEngineAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Apply Mojang-signed textures through the supported Paper profile API.
 */
public final class SkinApplyHelper {

	private record PendingSkin(Player player, SkinTextures textures) {}
	private static final Map<UUID, PendingSkin> PENDING = new HashMap<>();
	private static BukkitTask pendingTask;

	private SkinApplyHelper() {}

	public static SkinTextures readTextures(Player player) {
		if (player == null) {
			return null;
		}
		try {
			var profile = player.getPlayerProfile();
			for (ProfileProperty prop : profile.getProperties()) {
				if (!"textures".equals(prop.getName())) {
					continue;
				}
				String value = prop.getValue();
				String signature = prop.getSignature();
				SkinTextures textures = new SkinTextures(value, signature);
				return textures.isValid() ? textures : null;
			}
		} catch (Throwable t) {
			RPCharacters.plugin.getLogger().log(
				Level.FINE,
				"Could not read player textures for " + player.getName(),
				t
			);
		}
		return null;
	}

	public static boolean apply(Player player, SkinTextures textures) {
		if (player == null || textures == null || !textures.isValid()) {
			return false;
		}
		return apply(player, textures.getValue(), textures.getSignature());
	}

	public static boolean apply(Player player, String value, String signature) {
		if (player == null || value == null || value.isEmpty()
			|| signature == null || signature.isEmpty()) {
			return false;
		}
		SkinTextures next = new SkinTextures(value, signature);
		PENDING.remove(player.getUniqueId());
		SkinTextures last = WardrobeCache.getLastApplied(player);
		if (last != null && last.sameTextures(next)) {
			return true;
		}
		try {
			// Paper respawns the client on profile changes, losing its virtual seat attachment.
			if (player.isInsideVehicle()
				|| Bukkit.getPluginManager().isPluginEnabled("ModelEngine")
					&& ModelEngineAPI.getMountPairManager().getController(player.getUniqueId()) != null) {
				PENDING.put(player.getUniqueId(), new PendingSkin(player, next));
				if (pendingTask == null) {
					pendingTask = Bukkit.getScheduler().runTaskTimer(
						RPCharacters.plugin, SkinApplyHelper::retryPending, 1L, 5L);
				}
				return false;
			}
			var profile = player.getPlayerProfile();
			profile.removeProperty("textures");
			profile.setProperty(new ProfileProperty("textures", value, signature));
			player.setPlayerProfile(profile);
			// The respawn shows a full hunger bar until food changes; Paper resends health, not food.
			player.sendHealthUpdate();
			WardrobeCache.setLastApplied(player, next);
			return true;
		} catch (Throwable t) {
			RPCharacters.plugin.getLogger().log(
				Level.WARNING,
				"Failed to apply wardrobe skin for " + player.getName()
					+ ": " + t.getMessage(),
				t
			);
			return false;
		}
	}

	private static void retryPending() {
		for (PendingSkin pending : new ArrayList<>(PENDING.values())) {
			Player player = pending.player();
			if (!player.isOnline() || player.isDead()) {
				PENDING.remove(player.getUniqueId());
			} else {
				apply(player, pending.textures());
			}
		}
		if (PENDING.isEmpty()) stopPending();
	}

	static void clearPending(UUID playerId) {
		PENDING.remove(playerId);
		if (PENDING.isEmpty()) stopPending();
	}

	public static void stopPending() {
		PENDING.clear();
		if (pendingTask != null) {
			pendingTask.cancel();
			pendingTask = null;
		}
	}
}
