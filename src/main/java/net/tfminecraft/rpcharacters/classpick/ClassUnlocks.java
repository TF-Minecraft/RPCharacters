package net.tfminecraft.rpcharacters.classpick;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Per-character access to hidden MMOCore classes ({@code options.display: false}) for other
 * plugins. An unlocked class is offered in the class picker after the displayed base classes and
 * costs what any other class change costs. Unlocks belong to one character and are saved in its
 * file under "unlocked-classes". Class ids are matched like MMOCore ids: upper case, with dashes
 * and spaces as underscores. Call from the main thread; changes write the character file at once.
 */
public final class ClassUnlocks {

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private ClassUnlocks() {}

	/**
	 * Unlocks the class for the player's active living character and saves. False without an
	 * active character, for an unknown MMOCore class id, or when the save failed (nothing changes
	 * then). Idempotent (true if already unlocked).
	 */
	public static boolean unlock(Player player, String classId) {
		RPCharacter character = activeCharacter(player);
		String id = normalise(classId);
		if (character == null || id == null || MMOCore.plugin.classManager.get(id) == null) {
			return false;
		}
		return character.getUnlockedClasses().contains(id) || change(player, character, id, true);
	}

	/**
	 * Removes the unlock and saves. Does not change the character's current class. False without an
	 * active character or when the save failed (the unlock stays then); true if it was not unlocked.
	 */
	public static boolean lock(Player player, String classId) {
		RPCharacter character = activeCharacter(player);
		String id = normalise(classId);
		if (character == null || id == null) {
			return false;
		}
		return !character.getUnlockedClasses().contains(id) || change(player, character, id, false);
	}

	/** Unlocked class ids (upper case) of the active character; empty set without one. */
	public static Set<String> unlocked(Player player) {
		RPCharacter character = activeCharacter(player);
		return character == null ? Set.of()
				: Collections.unmodifiableSet(new LinkedHashSet<>(character.getUnlockedClasses()));
	}

	public static boolean isUnlocked(Player player, String classId) {
		String id = normalise(classId);
		return id != null && unlocked(player).contains(id);
	}

	/** The id as MMOCore stores it, or null for a blank one. */
	static String normalise(String classId) {
		if (classId == null || classId.isBlank()) {
			return null;
		}
		return classId.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
	}

	static RPCharacter activeCharacter(Player player) {
		PlayerData pd = player == null ? null : PlayerManager.get(player);
		RPCharacter character = pd == null ? null : pd.getActiveCharacter();
		return character != null && character.getStatus() == Status.ALIVE ? character : null;
	}

	/** Applies the change and writes the character file; a failed write puts the old state back. */
	private static boolean change(Player player, RPCharacter character, String id, boolean unlock) {
		if (unlock) {
			character.addUnlockedClass(id);
		} else {
			character.removeUnlockedClass(id);
		}
		String what = "class " + id + " for " + character.getName() + " (" + character.getId() + ") of "
				+ player.getName();
		if (RPCharacters.getPlayerManager().saveCharacterNow(player.getUniqueId(), character)) {
			LOG.info("[RPCharacters] " + (unlock ? "Unlocked " : "Locked ") + what + ".");
			return true;
		}
		if (unlock) {
			character.removeUnlockedClass(id);
		} else {
			character.addUnlockedClass(id);
		}
		// A failed save may already have replaced the file, so write the old state back.
		RPCharacters.getPlayerManager().saveCharacterNow(player.getUniqueId(), character);
		LOG.warning("[RPCharacters] Could not save the character file, so " + what + " stays "
				+ (unlock ? "locked." : "unlocked."));
		return false;
	}
}
