package net.tfminecraft.rpcharacters.injuries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.loaders.InjuryProgressionLoader;
import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.objects.ProstheticReplacement;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

/**
 * How many injuries one body part can hold. A body part is a permanent injury, the healing injuries that turn
 * into it (injury-progression.yml) and the prosthetics that replace it (prosthetics.yml). Each one a character
 * owns fills a slot: arms have two (any two of Broken Arm, One-Handed and an arm prosthetic), eyes one (Half
 * Blind or Blind). Injuries outside the body, such as wild magic marks, share a body part through
 * {@code groups} in injury-progression.yml. {@code limits} there sets the slots; unset, a part a prosthetic
 * can replace is a pair of limbs (2) and any other part, groups included, has 1.
 */
public final class InjuryLimits {

	private InjuryLimits() {
	}

	/**
	 * The body part this trait fills a slot of: its injury group, else the permanent injury naming it. Null
	 * for traits outside any body part.
	 */
	public static String bodyPartOf(String traitId) {
		if (traitId == null || traitId.isBlank()) {
			return null;
		}
		String id = traitId.toLowerCase(Locale.ROOT);
		String group = InjuryProgressionLoader.getGroup(id);
		if (group != null) {
			return group;
		}
		String permanent = InjuryProgressionLoader.getPermanentId(id);
		if (permanent != null) {
			return permanent;
		}
		if (InjuryProgressionLoader.getProgressionMap().containsValue(id) || ProstheticLoader.getReplacement(id) != null) {
			return id;
		}
		ProstheticReplacement replacement = ProstheticLoader.getReplacementForProsthetic(id);
		return replacement != null ? replacement.getPermanentInjuryId() : null;
	}

	public static int slotsFor(String bodyPart) {
		int configured = InjuryProgressionLoader.getLimit(bodyPart);
		if (configured > 0) {
			return configured;
		}
		return ProstheticLoader.getReplacement(bodyPart) != null ? 2 : 1;
	}

	static int filledSlots(Collection<String> ownedIds, String bodyPart) {
		int filled = 0;
		for (String owned : ownedIds) {
			if (bodyPart.equals(bodyPartOf(owned))) {
				filled++;
			}
		}
		return filled;
	}

	/** True when the injury fits as an extra one: not owned yet and its body part has a free slot. */
	public static boolean canAdd(Collection<String> ownedIds, String traitId) {
		if (traitId == null || owns(ownedIds, traitId)) {
			return false;
		}
		String bodyPart = bodyPartOf(traitId);
		return bodyPart == null || filledSlots(ownedIds, bodyPart) < slotsFor(bodyPart);
	}

	/** The owned healing injury this permanent injury can take the place of (Half Blind for Blind), or null. */
	public static String healingToReplace(Collection<String> ownedIds, String permanentId) {
		if (permanentId == null || owns(ownedIds, permanentId)) {
			return null;
		}
		for (String owned : ownedIds) {
			if (permanentId.equalsIgnoreCase(InjuryProgressionLoader.getPermanentId(owned))) {
				return owned;
			}
		}
		return null;
	}

	/** True when {@link #give} would apply this injury. */
	public static boolean canReceive(Collection<String> ownedIds, String traitId) {
		return canAdd(ownedIds, traitId) || healingToReplace(ownedIds, traitId) != null;
	}

	/**
	 * Applies a new injury: added when its body part has room, otherwise in place of the healing injury that
	 * turns into it. Returns false, changing nothing, when it fits neither way.
	 */
	public static boolean give(Player player, RPCharacter character, Trait injury) {
		List<String> owned = ownedIds(character);
		if (canAdd(owned, injury.getId())) {
			add(player, character, injury);
			return true;
		}
		Trait healing = findOwned(character, healingToReplace(owned, injury.getId()));
		if (healing == null) {
			return false;
		}
		upgrade(player, character, healing, injury);
		return true;
	}

	/** Turns a healing injury into its permanent one, which takes the same slot. */
	public static void upgrade(Player player, RPCharacter character, Trait healing, Trait permanent) {
		TraitChangeService.removeTrait(player, character, healing);
		add(player, character, permanent);
	}

	private static void add(Player player, RPCharacter character, Trait injury) {
		boolean besideProsthetic = ownsProstheticFor(character, injury.getId());
		TraitChangeService.addTrait(player, character, injury);
		if (besideProsthetic) {
			// The prosthetic already stands for one limb, so this injury is the other one and must outlast
			// the login cleanup that drops injuries a prosthetic replaced.
			character.markSecondLimb(injury.getId());
			RPCharacters.getPlayerManager().savePlayer(player);
		}
	}

	private static boolean ownsProstheticFor(RPCharacter character, String injuryId) {
		ProstheticReplacement replacement = ProstheticLoader.getReplacement(injuryId);
		return replacement != null && replacement.ownedProstheticId(character.getTraits()) != null;
	}

	public static List<String> ownedIds(RPCharacter character) {
		List<String> ids = new ArrayList<>();
		if (character == null || character.getTraits() == null) {
			return ids;
		}
		for (Trait trait : character.getTraits()) {
			if (trait != null && trait.getId() != null) {
				ids.add(trait.getId().toLowerCase(Locale.ROOT));
			}
		}
		return ids;
	}

	private static Trait findOwned(RPCharacter character, String traitId) {
		if (traitId == null) {
			return null;
		}
		for (Trait trait : character.getTraits()) {
			if (trait != null && traitId.equalsIgnoreCase(trait.getId())) {
				return trait;
			}
		}
		return null;
	}

	private static boolean owns(Collection<String> ownedIds, String traitId) {
		for (String owned : ownedIds) {
			if (owned.equalsIgnoreCase(traitId)) {
				return true;
			}
		}
		return false;
	}
}
