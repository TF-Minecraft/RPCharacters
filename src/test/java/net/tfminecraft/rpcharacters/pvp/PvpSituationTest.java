package net.tfminecraft.rpcharacters.pvp;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpSituationTest {

	private final UUID issuer = UUID.randomUUID();
	private final UUID survivor = UUID.randomUUID();
	private final UUID dead = UUID.randomUUID();

	@BeforeEach
	void clearSituations() {
		PvpSituations.clear();
	}

	@Test
	void endingBeforeTheCountdownFinishesShowsNobody() {
		PvpSituation situation = situation();
		assertTrue(situation.close().isEmpty());
		assertFalse(situation.isOpen());
	}

	@Test
	void timeoutTitlesEveryoneWhoHasNotDied() {
		PvpSituation situation = situation();
		situation.markStarted();
		situation.markDeath(dead);
		assertEquals(Set.of(issuer, survivor), Set.copyOf(situation.close()));
	}

	@Test
	void aSecondCloseDoesNotTitleAgain() {
		PvpSituation situation = situation();
		situation.markStarted();
		situation.close();
		assertTrue(situation.close().isEmpty());
	}

	@Test
	void aDeathDuringTheCountdownHidesTheEndedTitle() {
		PvpSituation situation = situation();
		situation.markDeath(dead);
		situation.markStarted();
		assertTrue(situation.hasDied(dead));
		assertEquals(Set.of(issuer, survivor), Set.copyOf(situation.close()));
	}

	@Test
	void issuerEndClosesTheSameWayAsTheTimeout() {
		PvpSituation situation = situation();
		PvpSituations.track(situation);
		situation.markStarted();
		situation.markDeath(dead);
		assertEquals(Set.of(issuer, survivor), Set.copyOf(PvpSituations.openFor(issuer).close()));
		assertNull(PvpSituations.openFor(issuer));
	}

	@Test
	void anotherOpenFightKeepsThePlayerActive() {
		PvpSituation first = situation();
		PvpSituation second = new PvpSituation(UUID.randomUUID(), List.of(survivor));
		first.markStarted();
		second.markStarted();
		PvpSituations.track(first);
		PvpSituations.track(second);
		first.close();
		assertTrue(PvpSituations.remainsActiveElsewhere(survivor, first));
		assertFalse(PvpSituations.remainsActiveElsewhere(issuer, first));
	}

	private PvpSituation situation() {
		return new PvpSituation(issuer, List.of(issuer, survivor, dead));
	}
}
