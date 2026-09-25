package net.tfminecraft.rpcharacters.pvp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpStartDeathPolicyTest {

	@Test
	void unlootableKillerDuringPvpStartKeepsInventory() {
		assertTrue(PvpStartDeathPolicy.keepInventory(true, true, false));
	}

	@Test
	void aKillerWhoCanLootStillMakesAGrave() {
		assertFalse(PvpStartDeathPolicy.keepInventory(true, true, true));
	}

	@Test
	void outsidePvpStartAGraveIsMade() {
		assertFalse(PvpStartDeathPolicy.keepInventory(false, true, false));
	}

	@Test
	void mobsAndSelfKillsStillMakeAGrave() {
		assertFalse(PvpStartDeathPolicy.keepInventory(true, false, false));
	}
}
