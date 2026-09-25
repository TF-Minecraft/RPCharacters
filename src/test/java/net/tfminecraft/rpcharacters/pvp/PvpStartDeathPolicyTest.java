package net.tfminecraft.rpcharacters.pvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpStartDeathPolicyTest {

	@Test
	void unlootableKillerDuringPvpStartKeepsInventory() {
		assertTrue(PvpStartDeathPolicy.keepInventory(true, true, false, false));
	}

	@Test
	void aKillerWhoCanLootStillMakesAGrave() {
		assertFalse(PvpStartDeathPolicy.keepInventory(true, true, true, false));
	}

	@Test
	void outsidePvpStartAGraveIsMade() {
		assertFalse(PvpStartDeathPolicy.keepInventory(false, true, false, false));
	}

	@Test
	void mobsAndSelfKillsStillMakeAGrave() {
		assertFalse(PvpStartDeathPolicy.keepInventory(true, false, false, false));
	}

	@Test
	void evilRpVictimsLeaveAnUnlockedGraveInstead() {
		assertFalse(PvpStartDeathPolicy.keepInventory(true, true, false, true));
	}
}
