package net.tfminecraft.rpcharacters.progress;

import java.util.List;
import java.util.UUID;

/**
 * The account's shared progress when per-character progress began, and the characters it existed for.
 * Written once; each of those characters starts from a copy, so claimed level rewards are never given again.
 */
record ProgressBaseline(UUID ownerId, List<String> characterIds, CharacterProgress template) {
	ProgressBaseline {
		characterIds = List.copyOf(characterIds);
	}

	boolean includes(String characterId) {
		return characterIds.stream().anyMatch(id -> id.equalsIgnoreCase(characterId));
	}

	CharacterProgress cloneFor(String characterId) {
		return template.copyFor(characterId, CharacterProgress.BASELINE);
	}
}
