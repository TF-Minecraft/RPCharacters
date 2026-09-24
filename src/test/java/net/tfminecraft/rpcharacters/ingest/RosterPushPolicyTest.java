package net.tfminecraft.rpcharacters.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RosterPushPolicyTest {

	@Test
	void pushIsAllowedWhenEverySavedFileLoaded() {
		assertFalse(RosterPushPolicy.wouldDropSavedCharacters(1, 1));
		assertFalse(RosterPushPolicy.wouldDropSavedCharacters(0, 0));
	}

	@Test
	void pushIsSkippedWhenASavedFileFailedToLoad() {
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(0, 1));
		assertTrue(RosterPushPolicy.wouldDropSavedCharacters(1, 2));
	}

	@Test
	void pushIsAllowedWhenMemoryHasCharactersAndDiskHasNone() {
		assertFalse(RosterPushPolicy.wouldDropSavedCharacters(1, 0));
	}
}
