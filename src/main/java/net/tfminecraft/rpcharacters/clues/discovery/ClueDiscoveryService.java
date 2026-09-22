package net.tfminecraft.rpcharacters.clues.discovery;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.MagnifyingGlassDefinition;

public final class ClueDiscoveryService {

	private ClueDiscoveryService() {}

	public static boolean tryPassiveDiscovery(Player player, RPCharacter character, SpawnedClue clue) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (!settings.isPassiveDiscoveryEnabled()) return false;
		return tryDiscovery(player, character, clue, settings.getPassiveBaseChance(), 0.0, settings.getPassiveMinPotency(), true);
	}

	public static boolean tryActiveDiscovery(Player player, RPCharacter character, SpawnedClue clue,
			MagnifyingGlassDefinition glass) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (!settings.isActiveDiscoveryEnabled()) return false;
		double bonus = glass != null ? glass.getDiscoveryBonus() : 0.0;
		return tryDiscovery(player, character, clue, settings.getActiveBaseChance(), bonus,
				settings.getActiveMinPotency(), false);
	}

	private static boolean tryDiscovery(Player player, RPCharacter character, SpawnedClue clue,
			double baseChance, double toolBonus, double minPotency, boolean passive) {
		if (player == null || character == null || clue == null) return false;
		if (clue.shouldRemove()) return false;
		if (clue.getPotency() < minPotency) return false;

		UUID charUuid = parseCharacterUuid(character.getId());
		if (charUuid == null || clue.isDiscoveredBy(charUuid)) return false;

		double chance = computeChance(character, baseChance, toolBonus, clue.getPotency());
		if (ThreadLocalRandom.current().nextDouble() >= chance) return false;

		clue.markDiscovered(charUuid);
		SpawnedClueManager.get().markDirty();

		String msg = ClueDiscoveryLoader.getSettings().getMessageDiscovered();
		if (msg != null && !msg.isBlank()) {
			RPTexts.send(player, msg);
		}

		ClueDiscoveryVisualManager.get().refreshViewer(player);
		return true;
	}

	public static double computeChance(RPCharacter character, double baseChance, double toolBonus, double potency) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		int wisdom = character.getAttributeData().getAmount(new AttributeModifier("wisdom", 0));
		int intelligence = character.getAttributeData().getAmount(new AttributeModifier("intelligence", 0));
		double score = baseChance
				+ (wisdom * settings.getWisdomWeight())
				+ (intelligence * settings.getIntelligenceWeight())
				+ toolBonus;
		return Math.max(0, Math.min(1, score * potency));
	}

	private static UUID parseCharacterUuid(String characterId) {
		if (characterId == null) return null;
		try {
			return UUID.fromString(characterId);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
