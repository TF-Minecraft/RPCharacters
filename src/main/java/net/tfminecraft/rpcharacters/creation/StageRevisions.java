package net.tfminecraft.rpcharacters.creation;

import java.time.Instant;
import java.util.Collection;
import java.util.Locale;

import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Staff raise a stage's {@code revision} in stages.yml after a large change to it. Each character
 * then gets a fresh lock window on that stage, starting when it next loads, and its paid-change
 * prices start over. On the class stage the free first subclass returns too.
 * {@code /rpcharacter admin resetclasses} raises the class stage's revision the same way.
 */
public final class StageRevisions {

	private StageRevisions() {}

	/** Starts a fresh window on every stage whose revision passed the character's. True if any did. */
	public static boolean refresh(RPCharacter character, Collection<? extends Stage> stages, long nowEpochSeconds) {
		if (character == null || stages == null) {
			return false;
		}
		boolean changed = false;
		for (Stage stage : stages) {
			if (stage == null || stage.getId() == null) {
				continue;
			}
			String id = key(stage);
			if (stage.getRevision() > character.getStageRevision(id)) {
				character.setStageRevision(id, stage.getRevision(), nowEpochSeconds);
				character.setPaidChangeCount(id, 0);
				if (CreationManager.isClassStage(stage)) {
					character.setSubclassPicked(false);
				}
				changed = true;
			}
		}
		return changed;
	}

	public static boolean refresh(RPCharacter character, Collection<? extends Stage> stages) {
		return refresh(character, stages, Instant.now().getEpochSecond());
	}

	/** New characters start on the current revisions, with the window counted from creation. */
	public static void stampCurrent(RPCharacter character, Collection<? extends Stage> stages) {
		if (character == null || stages == null) {
			return;
		}
		for (Stage stage : stages) {
			if (stage != null && stage.getId() != null && stage.getRevision() > 0) {
				character.setStageRevision(key(stage), stage.getRevision(), 0L);
			}
		}
	}

	/** Seconds since this stage's lock window opened: creation, or the latest revision reset. */
	public static long secondsIntoWindow(Stage stage, RPCharacter character, long nowEpochSeconds) {
		int createdAt = character.getCreatedAtEpochSeconds();
		if (createdAt <= 0) {
			return 0L;
		}
		long start = Math.max(createdAt, character.getStageRevisionSince(key(stage)));
		return Math.max(0L, nowEpochSeconds - start);
	}

	/** Stage ids are stored lower case, the same key paid-change counts use. */
	public static String key(Stage stage) {
		return stage.getId() == null ? null : stage.getId().toLowerCase(Locale.ROOT);
	}

	public static long secondsIntoWindow(Stage stage, RPCharacter character) {
		return secondsIntoWindow(stage, character, Instant.now().getEpochSecond());
	}
}
