package net.tfminecraft.rpcharacters.utils;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;

public final class PlaytimeGate {

	private PlaytimeGate() {}

	public static boolean canSelectTrait(Player player, Trait trait) {
		if (player == null || trait == null) {
			return false;
		}
		int required = trait.getTraitData().getRequiredAccountPlaytimeSeconds();
		if (required <= 0) {
			return true;
		}
		PlayerData data = PlayerManager.get(player);
		if (data == null) {
			return false;
		}
		return data.getAgeSeconds() >= required;
	}

	public static String denialMessage(Player player, Trait trait) {
		int required = trait.getTraitData().getRequiredAccountPlaytimeSeconds();
		PlayerData data = PlayerManager.get(player);
		int have = data != null ? data.getAgeSeconds() : 0;
		int remaining = Math.max(0, required - have);
		return RPTexts.formatDisplay(RPTexts.ERROR + "Need " + RPTexts.WARN + AgeFormatter.formatAge(required)
				+ RPTexts.ERROR + " playtime. " + RPTexts.WARN + AgeFormatter.formatAge(remaining)
				+ RPTexts.ERROR + " left.");
	}
}
