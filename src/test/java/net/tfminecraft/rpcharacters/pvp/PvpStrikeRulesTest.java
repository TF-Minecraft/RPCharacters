package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PvpStrikeRulesTest {

	private final UUID victim = UUID.randomUUID();
	private final UUID killer = UUID.randomUUID();

	@Test
	void aPlayerKillInAPvpStartCounts() {
		assertTrue(PvpStrikeService.counts(true, false, victim, killer));
	}

	@Test
	void outsideAPvpStartNothingCounts() {
		assertFalse(PvpStrikeService.counts(false, false, victim, killer));
	}

	@Test
	void battlesNeverCount() {
		assertFalse(PvpStrikeService.counts(true, true, victim, killer));
	}

	@Test
	void noPlayerKillerIsAnAutomaticSpare() {
		assertFalse(PvpStrikeService.counts(true, false, victim, null));
		assertFalse(PvpStrikeService.counts(true, false, victim, victim));
	}

	@Test
	void consumingTheMarkEndsIt() {
		PvpStartSessions.clear();
		PvpStartSessions.begin(List.of(victim), 0L, 60_000L);
		assertTrue(PvpStartSessions.consume(victim, 1_000L));
		assertFalse(PvpStartSessions.isActive(victim, 1_000L));
		assertFalse(PvpStartSessions.consume(victim, 1_000L));
	}
}
