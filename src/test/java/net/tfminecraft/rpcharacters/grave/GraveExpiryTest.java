package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraveExpiryTest {

	private static final int TWENTY_MINUTES = 1200;

	@Test
	void zeroExpireSecondsNeverExpires() {
		long created = 1_000_000L;
		long now = created + TWENTY_MINUTES * 1000L;
		assertFalse(GraveExpiryLogic.isExpired(created, now, 0));
	}

	@Test
	void notExpiredBeforeDeadline() {
		long created = 1_000_000L;
		long deadline = created + TWENTY_MINUTES * 1000L;
		assertFalse(GraveExpiryLogic.isExpired(created, deadline - 1L, TWENTY_MINUTES));
	}

	@Test
	void expiredAtDeadline() {
		long created = 1_000_000L;
		long deadline = created + TWENTY_MINUTES * 1000L;
		assertTrue(GraveExpiryLogic.isExpired(created, deadline, TWENTY_MINUTES));
	}

	@Test
	void expiredAfterDeadline() {
		long created = 1_000_000L;
		long deadline = created + TWENTY_MINUTES * 1000L;
		assertTrue(GraveExpiryLogic.isExpired(created, deadline + 1L, TWENTY_MINUTES));
	}
}
