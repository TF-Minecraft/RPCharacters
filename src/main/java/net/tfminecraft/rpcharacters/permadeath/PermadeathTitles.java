package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

public final class PermadeathTitles {

	private PermadeathTitles() {
	}

	public static void showInjury(Player player, Trait trait) {
		RPTexts.longTitle(player, TraitChangeService.resolveGainedMessage(trait), " ");
	}

	public static void showPermakill(Player player, String killedName, String replacementName, boolean fromZone) {
		RPTexts.longTitle(player, buildPermakillTitle(killedName), buildReplacementSubtitle(replacementName));
		RPTexts.send(player, buildPermakillChat(killedName, fromZone));
	}

	private static String buildPermakillTitle(String killedName) {
		return RPTexts.ERROR + killedName + RPTexts.ERROR + " has died!";
	}

	private static String buildPermakillChat(String killedName, boolean fromZone) {
		if (fromZone) {
			return RPTexts.ERROR + "Your character " + RPTexts.WARN + killedName
					+ RPTexts.ERROR + " has been permanently killed in a permadeath zone.";
		}
		return RPTexts.ERROR + "Your character " + RPTexts.WARN + killedName
				+ RPTexts.ERROR + " has been permanently killed.";
	}

	private static String buildReplacementSubtitle(String replacementName) {
		if (replacementName != null) {
			return RPTexts.MUTED + "Now playing as " + RPTexts.WARN + replacementName;
		}
		return RPTexts.ERROR + "You have no active character";
	}
}
