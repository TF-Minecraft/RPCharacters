package net.tfminecraft.rpcharacters.wardrobe;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Apply Mojang-signed textures through the supported Paper profile API.
 */
public final class SkinApplyHelper {

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
		SkinTextures last = WardrobeCache.getLastApplied(player);
		if (last != null && last.sameTextures(next)) {
			return true;
		}
		try {
			var profile = player.getPlayerProfile();
			profile.removeProperty("textures");
			profile.setProperty(new ProfileProperty("textures", value, signature));
			player.setPlayerProfile(profile);
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
}
