package net.tfminecraft.rpcharacters.wardrobe;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Apply Mojang-signed textures via Paper PlayerProfile (reflection so we compile
 * against spigot-api).
 */
public final class SkinApplyHelper {

	private SkinApplyHelper() {}

	public static SkinTextures readTextures(Player player) {
		if (player == null) {
			return null;
		}
		try {
			Object profile = player.getClass().getMethod("getPlayerProfile").invoke(player);
			if (profile == null) {
				return null;
			}
			Method getProperties = profile.getClass().getMethod("getProperties");
			Object props = getProperties.invoke(profile);
			if (!(props instanceof Collection<?>)) {
				return null;
			}
			for (Object prop : (Collection<?>) props) {
				if (prop == null) {
					continue;
				}
				String name = String.valueOf(
					prop.getClass().getMethod("getName").invoke(prop)
				);
				if (!"textures".equals(name)) {
					continue;
				}
				String value = String.valueOf(
					prop.getClass().getMethod("getValue").invoke(prop)
				);
				Object sigObj = prop.getClass().getMethod("getSignature").invoke(prop);
				String signature = sigObj == null ? null : String.valueOf(sigObj);
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
			Object profile = player.getClass().getMethod("getPlayerProfile").invoke(player);
			if (profile == null) {
				return false;
			}
			Class<?> propertyClass = Class.forName(
				"com.destroystokyo.paper.profile.ProfileProperty"
			);
			Object property = propertyClass
				.getConstructor(String.class, String.class, String.class)
				.newInstance("textures", value, signature);

			try {
				profile.getClass()
					.getMethod("removeProperty", String.class)
					.invoke(profile, "textures");
			} catch (NoSuchMethodException ignored) {
				// older Paper: setProperty may replace
			}

			profile.getClass()
				.getMethod("setProperty", propertyClass)
				.invoke(profile, property);

			Class<?> profileIface = Class.forName(
				"com.destroystokyo.paper.profile.PlayerProfile"
			);
			player.getClass()
				.getMethod("setPlayerProfile", profileIface)
				.invoke(player, profile);
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
