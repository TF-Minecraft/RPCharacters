package net.tfminecraft.rpcharacters.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RosterPushPolicyTest {

	@Test
	void pushIsAllowedWhenEverySavedFileLoaded() {
		assertFalse(RosterPushPolicy.wouldDropSavedCharacters(
				Set.of("nokk"), List.of("nokk")));
		assertFalse(RosterPushPolicy.wouldDropSavedCharacters(Set.of(), List.of()));
	}

	@Test
	void pushIsSkippedWhenASavedFileFailedToLoad() {
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(Set.of(), List.of("nokk")));
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(
				Set.of("other"), List.of("nokk", "other")));
	}

	@Test
	void pushIsSkippedWhenEqualCountsHideAMissingSavedId() {
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(
				Set.of("new-character"), List.of("nokk")));
	}

	@Test
	void pushIsSkippedWhenTheCharacterFolderCannotBeListed() {
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(Set.of(), null));
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(Set.of("nokk"), null));
	}

	@Test
	void pushIsAllowedWhenMemoryHasCharactersAndDiskHasNone() {
		assertFalse(RosterPushPolicy.wouldDropSavedCharacters(Set.of("nokk"), List.of()));
	}
}
