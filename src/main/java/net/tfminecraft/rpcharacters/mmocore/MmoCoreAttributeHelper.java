package net.tfminecraft.rpcharacters.mmocore;

import java.util.Locale;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttribute;
import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Resolve MMOCore attribute definitions for creation GUI / validation.
 */
public final class MmoCoreAttributeHelper {

	private MmoCoreAttributeHelper() {}

	public static boolean exists(String attributeId) {
		String id = normalize(attributeId);
		if (id.isEmpty()) {
			return false;
		}
		try {
			return MMOCore.plugin.attributeManager.has(id);
		} catch (Throwable t) {
			RPCharacters.plugin.getLogger().warning(
				"[attributes] could not query MMOCore attributeManager: " + t.getMessage()
			);
			return false;
		}
	}

	public static String displayName(String attributeId) {
		String id = normalize(attributeId);
		if (id.isEmpty()) {
			return "";
		}
		try {
			PlayerAttribute attr = MMOCore.plugin.attributeManager.get(id);
			if (attr != null && attr.getName() != null && !attr.getName().isBlank()) {
				return attr.getName();
			}
		} catch (Throwable ignored) {
			// fall through
		}
		if (id.isEmpty()) {
			return id;
		}
		return Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	private static String normalize(String attributeId) {
		return attributeId == null ? "" : attributeId.trim().toLowerCase(Locale.ROOT);
	}
}
