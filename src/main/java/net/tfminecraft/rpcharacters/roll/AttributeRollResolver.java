package net.tfminecraft.rpcharacters.roll;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.loaders.RollLoader;

public final class AttributeRollResolver {

	private AttributeRollResolver() {}

	public static int resolveModifier(Player player, String attributeId) {
		if (player == null || attributeId == null || attributeId.isBlank()) {
			return 0;
		}
		net.Indyuce.mmocore.api.player.PlayerData data =
				net.Indyuce.mmocore.api.player.PlayerData.get(player);
		if (data == null) {
			return 0;
		}
		AttributeInstance instance = data.getAttributes().getInstance(attributeId);
		if (instance == null) {
			return 0;
		}
		int value = (int) Math.round(instance.getBase());
		return RollLoader.getModifier(attributeId, value);
	}
}
