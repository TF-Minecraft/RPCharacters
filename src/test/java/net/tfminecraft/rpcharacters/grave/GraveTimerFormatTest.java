package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraveTimerFormatTest {

	@Test
	void remainingMillisAtFullTtl() {
		long created = 1_000_000L;
		int expireSeconds = 1200;
		assertEquals(1_200_000L, GraveExpiryLogic.remainingMillis(created, created, expireSeconds));
	}

	@Test
	void remainingMillisMidCountdown() {
		long created = 1_000_000L;
		int expireSeconds = 1200;
		long now = created + 500_000L;
		assertEquals(700_000L, GraveExpiryLogic.remainingMillis(created, now, expireSeconds));
	}

	@Test
	void remainingMillisZeroWhenExpired() {
		long created = 1_000_000L;
		int expireSeconds = 1200;
		long now = created + 1_200_000L;
		assertEquals(0L, GraveExpiryLogic.remainingMillis(created, now, expireSeconds));
	}

	@Test
	void remainingMillisZeroWhenExpireDisabled() {
		assertEquals(0L, GraveExpiryLogic.remainingMillis(1_000_000L, 2_000_000L, 0));
	}

	@Test
	void formatRemainingTimeMinutesAndSeconds() {
		assertEquals("1:05", GraveExpiryLogic.formatRemainingTime(65_000L));
		assertEquals("0:05", GraveExpiryLogic.formatRemainingTime(5_000L));
		assertEquals("18:42", GraveExpiryLogic.formatRemainingTime((18 * 60L + 42) * 1000L));
	}

	@Test
	void applyTimePlaceholderSubstitutesTime() {
		String result = GraveTimerFormat.applyTimePlaceholder("&7Despawns in &e{time}", 1_122_000L);
		assertTrue(result.contains("18:42"));
	}
}
