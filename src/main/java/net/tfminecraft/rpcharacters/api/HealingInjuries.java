package net.tfminecraft.rpcharacters.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.injuries.InjuryHealingService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

/**
 * Healing (non-permanent) injuries on a player's active character, for treatment plugins such as Surgery.
 */
public final class HealingInjuries {

	public record HealingInjury(String traitId, String displayName, long remainingMs) {
	}

	private HealingInjuries() {
	}

	public static List<HealingInjury> list(Player player) {
		RPCharacter character = activeCharacter(player);
		if (character == null) {
			return Collections.emptyList();
		}
		List<HealingInjury> injuries = new ArrayList<>();
		for (Trait trait : character.getTraits()) {
			if (isHealingInjury(trait)) {
				injuries.add(new HealingInjury(trait.getId(), TraitEffectResolver.resolveDisplayName(character, trait),
						remainingMs(character, trait)));
			}
		}
		return injuries;
	}

	/**
	 * Removes the healing injury and sends its lost message.
	 *
	 * @return false when the player's active character does not have that healing injury
	 */
	public static boolean cure(Player player, String traitId) {
		RPCharacter character = activeCharacter(player);
		Trait trait = findHealingInjury(character, traitId);
		if (trait == null) {
			return false;
		}
		TraitChangeService.removeTrait(player, character, trait);
		TraitChangeService.sendLostMessage(player, trait);
		return true;
	}

	/**
	 * Adds time to a healing injury's remaining duration.
	 *
	 * @return the new remaining milliseconds, or -1 when the character does not have that healing injury
	 */
	public static long extend(Player player, String traitId, long extraMs) {
		RPCharacter character = activeCharacter(player);
		Trait trait = findHealingInjury(character, traitId);
		if (trait == null) {
			return -1L;
		}
		long remaining = remainingMs(character, trait) + Math.max(0L, extraMs);
		character.setDurationRemainingMs(trait.getId(), remaining);
		InjuryHealingService.refreshCharacter(player, character);
		RPCharacters.getPlayerManager().savePlayer(player);
		return remaining;
	}

	private static RPCharacter activeCharacter(Player player) {
		if (player == null) {
			return null;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return null;
		}
		return pd.getActiveCharacter();
	}

	private static Trait findHealingInjury(RPCharacter character, String traitId) {
		if (character == null || traitId == null) {
			return null;
		}
		for (Trait trait : character.getTraits()) {
			if (trait.getId().equalsIgnoreCase(traitId) && isHealingInjury(trait)) {
				return trait;
			}
		}
		return null;
	}

	private static boolean isHealingInjury(Trait trait) {
		return trait.hasDuration() && trait.getTraitData().isInjuryKey();
	}

	private static long remainingMs(RPCharacter character, Trait trait) {
		long remaining = character.getDurationRemainingMs(trait.getId());
		return remaining < 0L ? trait.getDurationMs() : remaining;
	}
}
