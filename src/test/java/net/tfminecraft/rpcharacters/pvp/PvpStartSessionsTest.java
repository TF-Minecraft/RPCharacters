package net.tfminecraft.rpcharacters.pvp;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpStartSessionsTest {

	@BeforeEach
	void clearSessions() {
		PvpStartSessions.clear();
	}

	@Test
	void countdownFinishMarksPlayersUntilTheWindowEnds() {
		UUID player = UUID.randomUUID();
		PvpStartSessions.begin(List.of(player), 1_000L, 60_000L);
		assertTrue(PvpStartSessions.isActive(player, 1_000L));
		assertTrue(PvpStartSessions.isActive(player, 60_999L));
		assertFalse(PvpStartSessions.isActive(player, 61_000L));
	}

	@Test
	void deathOrLogoutEndsItEarly() {
		UUID player = UUID.randomUUID();
		PvpStartSessions.begin(List.of(player), 0L, 60_000L);
		PvpStartSessions.end(player);
		assertFalse(PvpStartSessions.isActive(player, 1L));
	}

	@Test
	void aLaterStartExtendsTheWindow() {
		UUID player = UUID.randomUUID();
		PvpStartSessions.begin(List.of(player), 0L, 10_000L);
		PvpStartSessions.begin(List.of(player), 5_000L, 10_000L);
		assertTrue(PvpStartSessions.isActive(player, 12_000L));
		assertFalse(PvpStartSessions.isActive(player, 15_000L));
	}

	@Test
	void zeroDurationDoesNotStart() {
		UUID player = UUID.randomUUID();
		PvpStartSessions.begin(List.of(player), 0L, 0L);
		assertFalse(PvpStartSessions.isActive(player, 0L));
	}
}
