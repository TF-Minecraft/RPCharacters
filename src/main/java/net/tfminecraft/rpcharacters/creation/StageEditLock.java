package net.tfminecraft.rpcharacters.creation;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class StageEditLock {

	public static final String BYPASS_PERMISSION = "rpchar.edit.bypass-lock";

	private StageEditLock() {}

	public static boolean canEdit(Player player, Stage stage, RPCharacter character) {
		if (player != null && player.hasPermission(BYPASS_PERMISSION)) {
			return true;
		}
		return canEdit(stage, character);
	}

	public static boolean canEdit(Stage stage, RPCharacter character) {
		if (stage == null || character == null) {
			return false;
		}
		long lockMs = stage.getLockTimeMs();
		if (lockMs < 0) {
			return true;
		}
		return character.getAgeSeconds() * 1000L < lockMs;
	}

	public static long lockRemainingMs(Stage stage, RPCharacter character) {
		if (stage == null || character == null) {
			return 0L;
		}
		long lockMs = stage.getLockTimeMs();
		if (lockMs < 0) {
			return 0L;
		}
		long remaining = lockMs - (character.getAgeSeconds() * 1000L);
		return Math.max(0L, remaining);
	}

	public static String lockLore(Stage stage, RPCharacter character) {
		if (stage == null || character == null || stage.getLockTimeMs() < 0) {
			return null;
		}
		long remaining = lockRemainingMs(stage, character);
		if (remaining <= 0) {
			return RPTexts.ERROR + "Locked";
		}
		return RPTexts.MUTED + "Will lock in: " + RPTexts.WARN + AgeFormatter.formatCountdown(remaining);
	}
}
