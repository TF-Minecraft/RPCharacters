package net.tfminecraft.rpcharacters.identity;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.MaskLoader;

public final class MaskService {

	private MaskService() {}

	public static boolean isMasked(Player player) {
		if (player == null) {
			return false;
		}
		return resolveMask(player) != null;
	}

	public static boolean isMaskItem(ItemStack item) {
		return MaskLoader.resolveMask(item) != null;
	}

	public static MaskDefinition resolveMask(Player player) {
		if (player == null) {
			return null;
		}
		return MaskLoader.resolveMask(player.getInventory().getHelmet());
	}

	public static String getMaskedLabel() {
		String label = Cache.maskedLabel;
		return label != null ? label : "Masked";
	}
}
