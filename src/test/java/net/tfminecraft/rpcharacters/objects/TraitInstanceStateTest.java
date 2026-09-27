package net.tfminecraft.rpcharacters.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraitInstanceStateTest {

	private static final long HOUR = 3_600_000L;
	private static final long NOW = 1_790_000_000_000L;

	@Test
	void durationCountsDownInRealTime() {
		TraitInstanceState state = new TraitInstanceState();
		state.setDurationRemainingMs(48 * HOUR, NOW);

		assertEquals(NOW + 48 * HOUR, state.getExpiresAtMs());
		assertEquals(48 * HOUR, state.getDurationRemainingMs(NOW));
		assertEquals(24 * HOUR, state.getDurationRemainingMs(NOW + 24 * HOUR));
	}

	@Test
	void remainingStopsAtZeroOnceExpired() {
		TraitInstanceState state = new TraitInstanceState();
		state.setDurationRemainingMs(HOUR, NOW);

		assertEquals(0L, state.getDurationRemainingMs(NOW + 5 * HOUR));
		assertTrue(state.hasDuration());
	}

	@Test
	void noDurationReportsMinusOne() {
		TraitInstanceState state = new TraitInstanceState();

		assertFalse(state.hasDuration());
		assertEquals(-1L, state.getDurationRemainingMs(NOW));
	}

	@Test
	void hugeDurationDoesNotOverflow() {
		TraitInstanceState state = new TraitInstanceState();
		state.setDurationRemainingMs(Long.MAX_VALUE, NOW);

		assertEquals(Long.MAX_VALUE, state.getExpiresAtMs());
	}
}
