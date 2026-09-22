package net.tfminecraft.rpcharacters.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.ProstheticReplacement;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;

public final class ProstheticTraitRules {

	public enum InstallAction {
		NONE,
		INSTALL,
		REPLACE,
		ALREADY_OWNED
	}

	private ProstheticTraitRules() {
	}

	public static InstallAction resolveInstall(boolean hasInjury, String ownedProstheticId, String targetTraitId) {
		if (targetTraitId == null || targetTraitId.isBlank()) {
			return InstallAction.NONE;
		}
		String target = targetTraitId.toLowerCase(Locale.ROOT);
		String owned = ownedProstheticId == null || ownedProstheticId.isBlank()
				? null
				: ownedProstheticId.toLowerCase(Locale.ROOT);
		if (target.equals(owned)) {
			return InstallAction.ALREADY_OWNED;
		}
		if (owned != null) {
			return InstallAction.REPLACE;
		}
		if (hasInjury) {
			return InstallAction.INSTALL;
		}
		return InstallAction.NONE;
	}

	/**
	 * Removes permanent backstory injuries superseded by an owned prosthetic trait.
	 *
	 * @return {@code true} if at least one injury trait was removed
	 */
	public static boolean stripReplacedInjuries(RPCharacter character) {
		if (character == null || character.getTraits() == null) {
			return false;
		}
		Set<String> injuryIdsToRemove = injuriesSupersededByProsthetics(
				character.getTraits().stream().map(Trait::getId).toList(),
				ProstheticTraitRules::permanentInjuryForOwnedTrait);
		if (injuryIdsToRemove.isEmpty()) {
			return false;
		}
		List<Trait> toRemove = new ArrayList<>();
		for (Trait trait : character.getTraits()) {
			if (injuryIdsToRemove.contains(trait.getId().toLowerCase(Locale.ROOT))) {
				toRemove.add(trait);
			}
		}
		for (Trait trait : toRemove) {
			character.removeTrait(trait);
		}
		return !toRemove.isEmpty();
	}

	/**
	 * Drops superseded injuries on every character for this player.
	 *
	 * @return {@code true} if at least one injury trait was removed
	 */
	public static boolean sanitize(PlayerData playerData) {
		if (playerData == null || playerData.getCharacters() == null) {
			return false;
		}
		boolean changed = false;
		for (RPCharacter character : playerData.getCharacters()) {
			if (stripReplacedInjuries(character)) {
				changed = true;
			}
		}
		return changed;
	}

	static Set<String> injuriesSupersededByProsthetics(
			List<String> ownedTraitIds,
			Function<String, String> permanentInjuryForTraitId) {
		Set<String> injuriesToRemove = new HashSet<>();
		for (String traitId : ownedTraitIds) {
			String injuryId = permanentInjuryForTraitId.apply(traitId);
			if (injuryId != null && !injuryId.isBlank()) {
				injuriesToRemove.add(injuryId.toLowerCase(Locale.ROOT));
			}
		}
		return injuriesToRemove;
	}

	static List<String> withoutTraitIds(List<String> ownedTraitIds, Set<String> traitIdsToRemove) {
		if (traitIdsToRemove.isEmpty()) {
			return ownedTraitIds;
		}
		List<String> kept = new ArrayList<>(ownedTraitIds.size());
		for (String traitId : ownedTraitIds) {
			if (!traitIdsToRemove.contains(traitId.toLowerCase(Locale.ROOT))) {
				kept.add(traitId);
			}
		}
		return kept;
	}

	private static String permanentInjuryForOwnedTrait(String traitId) {
		Trait trait = TraitLoader.getByString(traitId);
		if (trait == null || !trait.getTraitData().isProstheticKey()) {
			return null;
		}
		ProstheticReplacement replacement = ProstheticLoader.getReplacementForProsthetic(traitId);
		if (replacement == null) {
			return null;
		}
		return replacement.getPermanentInjuryId();
	}
}
