package net.tfminecraft.rpcharacters.utils;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;

public final class TraitChangeService {

	private TraitChangeService() {
	}

	public static void addTrait(Player player, RPCharacter character, Trait trait) {
		if (character.isActive()) {
			Integrator integrator = new Integrator();
			integrator.remove(player, character, false);
			character.addTrait(trait);
			character.update();
			integrator.integrate(player, character);
			AttributePointService.refreshAfterCreationLayerChange(player, character);
		} else {
			character.addTrait(trait);
			character.update();
		}

		RPCharacters.getPlayerManager().savePlayer(player);
		RPCharacters.getPlayerManager().reevaluateFreeze(player);
	}

	public static void removeTrait(Player player, RPCharacter character, Trait trait) {
		if (character.isActive()) {
			Integrator integrator = new Integrator();
			integrator.remove(player, character, false);
			character.removeTrait(trait);
			character.update();
			integrator.integrate(player, character);
			AttributePointService.refreshAfterCreationLayerChange(player, character);
		} else {
			character.removeTrait(trait);
			character.update();
		}

		RPCharacters.getPlayerManager().savePlayer(player);
		RPCharacters.getPlayerManager().reevaluateFreeze(player);
	}

	public static void sendGainedMessage(Player player, Trait trait) {
		RPTexts.send(player, resolveGainedMessage(trait));
	}

	public static String resolveGainedMessage(Trait trait) {
		String message = trait.getGainedMessage();
		if (message != null && !message.isBlank()) {
			return message;
		}
		return RPTexts.WARN + "You gained the trait " + trait.getName() + RPTexts.WARN + ".";
	}

	public static void sendLostMessage(Player player, Trait trait) {
		RPTexts.send(player, resolveLostMessage(trait));
	}

	public static String resolveLostMessage(Trait trait) {
		String message = trait.getLostMessage();
		if (message != null && !message.isBlank()) {
			return message;
		}
		return RPTexts.ERROR + "You lost the trait " + trait.getName() + RPTexts.ERROR + ".";
	}

	public static void sendRemedyCuredMessage(Player player, Trait trait) {
		RPTexts.send(player, resolveLostMessage(trait));
	}

	public static boolean replaceInjuryWithProsthetic(Player player, RPCharacter character, String injuryId,
			String prostheticId) {
		Trait injury = findOwnedTrait(character, injuryId);
		Trait prosthetic = TraitLoader.getByString(prostheticId);
		if (injury == null || prosthetic == null) {
			return false;
		}

		removeTrait(player, character, injury);
		sendLostMessage(player, injury);
		addTrait(player, character, prosthetic);
		sendGainedMessage(player, prosthetic);
		return true;
	}

	public static boolean replaceProsthetic(Player player, RPCharacter character, String fromId, String toId) {
		Trait fromTrait = findOwnedTrait(character, fromId);
		Trait toTrait = TraitLoader.getByString(toId);
		if (fromTrait == null || toTrait == null) {
			return false;
		}

		removeTrait(player, character, fromTrait);
		sendLostMessage(player, fromTrait);
		addTrait(player, character, toTrait);
		sendGainedMessage(player, toTrait);
		return true;
	}

	private static Trait findOwnedTrait(RPCharacter character, String traitId) {
		if (traitId == null || traitId.isBlank()) {
			return null;
		}
		for (Trait trait : character.getTraits()) {
			if (trait.getId().equalsIgnoreCase(traitId)) {
				return trait;
			}
		}
		return null;
	}
}
