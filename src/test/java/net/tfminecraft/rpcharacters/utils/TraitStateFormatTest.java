package net.tfminecraft.rpcharacters.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraitStateFormatTest {

	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60 * MINUTE;

	@Test
	void hoursRemainingRoundsUpToWholeHours() {
		assertEquals("37h", TraitStateFormat.formatHoursRemaining(36 * HOUR + 1));
		assertEquals("48h", TraitStateFormat.formatHoursRemaining(48 * HOUR));
		assertEquals("1h", TraitStateFormat.formatHoursRemaining(HOUR));
	}

	@Test
	void hoursRemainingUsesMinutesUnderAnHour() {
		assertEquals("45m", TraitStateFormat.formatHoursRemaining(44 * MINUTE + 1));
		assertEquals("1m", TraitStateFormat.formatHoursRemaining(1));
	}

	@Test
	void hoursRemainingIsZeroWhenExpired() {
		assertEquals("0m", TraitStateFormat.formatHoursRemaining(0));
		assertEquals("0m", TraitStateFormat.formatHoursRemaining(-5));
	}
}
