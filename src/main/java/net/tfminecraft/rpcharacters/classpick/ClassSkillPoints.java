package net.tfminecraft.rpcharacters.classpick;

import java.util.OptionalInt;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Per-character skill-point pools for classes that must not share the character's pool, for other
 * plugins (Magic's hidden blood classes earn their points from sacrifices only). While the character
 * plays a class with its own pool, its free points are that total minus what the class spent; the
 * character's shared points and tomes stay with its other classes. Saved in the character file under
 * "class-skill-points". Call from the main thread; changes write the character file at once.
 */
public final class ClassSkillPoints {

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private ClassSkillPoints() {}

	/**
	 * Gives the class its own pool on the player's active living character, with this lifetime total
	 * (never below 0), and saves. When the character plays the class now, its free points follow at
	 * once. False without an active character, for an unknown MMOCore class id, or when the save
	 * failed (nothing changes then). Idempotent.
	 */
	public static boolean set(Player player, String classId, int total) {
		RPCharacter character = ClassUnlocks.activeCharacter(player);
		String id = ClassUnlocks.normalise(classId);
		if (character == null || id == null || MMOCore.plugin.classManager.get(id) == null) {
			return false;
		}
		int wanted = Math.max(0, total);
		OptionalInt before = character.getClassSkillPoints(id);
		if (before.isPresent() && before.getAsInt() == wanted) {
			return true;
		}
		character.setClassSkillPoints(id, wanted);
		if (!RPCharacters.getPlayerManager().saveCharacterNow(player.getUniqueId(), character)) {
			if (before.isEmpty()) {
				character.removeClassSkillPoints(id);
			} else {
				character.setClassSkillPoints(id, before.getAsInt());
			}
			// A failed save may already have replaced the file, so write the old state back.
			RPCharacters.getPlayerManager().saveCharacterNow(player.getUniqueId(), character);
			LOG.warning("[RPCharacters] Could not save the character file, so the " + id + " skill points of "
					+ character.getName() + " (" + character.getId() + ") stay "
					+ (before.isEmpty() ? "shared" : String.valueOf(before.getAsInt())) + ".");
			return false;
		}
		if (ClassService.isOnClass(player, id)) {
			ClassService.refreshSkillPoints(player);
		}
		return true;
	}

	/** The class's own lifetime total on the active character; -1 when it has none (shared pool). */
	public static int get(Player player, String classId) {
		RPCharacter character = ClassUnlocks.activeCharacter(player);
		String id = ClassUnlocks.normalise(classId);
		return character == null || id == null ? -1 : character.getClassSkillPoints(id).orElse(-1);
	}
}
