package net.tfminecraft.rpcharacters.classpick;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Hidden MMOCore classes ({@code options.display: false}) that other plugins assign to one character,
 * such as Magic's wild magic classes. Players never pick them: the class picker doesn't list them, and
 * {@link #assign} puts the character into the class for free. While a character plays an assigned
 * class it is bound to it, so the class picker refuses any change until staff release it
 * ({@link #lock}). Assigned classes are saved in the character file under "unlocked-classes". Class
 * ids are matched like MMOCore ids: upper case, with dashes and spaces as underscores. Call from the
 * main thread; changes write the character file at once.
 */
public final class ClassUnlocks {

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private ClassUnlocks() {}

	/**
	 * Records the class as assigned to the player's active living character and saves, without
	 * switching to it ({@link #assign} does both). False without an active character, for an unknown
	 * MMOCore class id, or when the save failed (nothing changes then). Idempotent.
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
	 * Records the class for the active living character and switches the character into it, for free
	 * and without the picker. False without an active character, for an unknown class id, when the
	 * save failed or when MMOCore would not apply the class (the character keeps its class then; the
	 * record stays, so staff can retry). Idempotent.
	 */
	public static boolean assign(Player player, String classId) {
		if (!unlock(player, classId)) {
			return false;
		}
		RPCharacter character = activeCharacter(player);
		String id = normalise(classId);
		String oldClassId = character.getMMOClass();
		if (id.equalsIgnoreCase(oldClassId) && ClassService.isOnClass(player, id)) {
			return true;
		}
		character.setMMOClass(id);
		boolean applied;
		try {
			applied = ClassService.applyClass(player, id);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Class " + id + " could not be applied for " + player.getName(), e);
			applied = false;
		}
		if (!applied) {
			character.setMMOClass(oldClassId);
			LOG.warning("[RPCharacters] Could not switch " + character.getName() + " (" + character.getId() + ") of "
					+ player.getName() + " into " + id + "; assign it again or set it by hand.");
			return false;
		}
		PlayerData pd = PlayerManager.get(player);
		CharacterLifecycle.notifyClassChange(player, pd.getUniqueId(), character, oldClassId, character.getMMOClass());
		RPCharacters.getPlayerManager().savePlayer(player);
		LOG.info("[RPCharacters] Assigned class " + id + " to " + character.getName() + " (" + character.getId()
				+ ") of " + player.getName() + ".");
		return true;
	}

	/** True when the character plays a class assigned to it, so it can't change class itself. */
	public static boolean isLockedIn(RPCharacter character) {
		return character != null && character.hasMMOClass()
				&& character.getUnlockedClasses().contains(normalise(character.getMMOClass()));
	}

	/**
	 * Releases the class and saves: the character keeps playing it, but may change class in the picker
	 * again (and can't pick this one back). False without an active character or when the save failed
	 * (it stays assigned then); true if it was not assigned.
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
