package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class StrikeCooldownTest {

	private final UUID killer = UUID.randomUUID();
	private final UUID other = UUID.randomUUID();
	private final long day = 86_400_000L;

	@Test
	void theSameKillerMustWaitOutTheWindow() {
		long struckAt = 1_000L;
		Map<UUID, Long> strikes = Map.of(killer, struckAt);

		assertTrue(StrikeCooldown.blocks(strikes, killer, struckAt + day - 1, day));
		assertFalse(StrikeCooldown.blocks(strikes, killer, struckAt + day, day));
		assertFalse(StrikeCooldown.blocks(strikes, other, struckAt + 1, day));
	}

	@Test
	void aDisabledCooldownNeverBlocks() {
		assertFalse(StrikeCooldown.blocks(Map.of(killer, 1_000L), killer, 1_001L, 0L));
		assertFalse(StrikeCooldown.blocks(null, 5_000L, day));
		assertFalse(StrikeCooldown.blocks(0L, 5_000L, day));
	}

	@Test
	void recordingKeepsOtherRecentKillersAndDropsOldOnes() {
		long now = 1_000L + day;
		UUID expired = UUID.randomUUID();
		Map<UUID, Long> existing = new LinkedHashMap<>();
		existing.put(other, now - 1L);
		existing.put(expired, now - day);

		Map<UUID, Long> recorded = StrikeCooldown.record(existing, killer, now, day);

		assertEquals(now, recorded.get(killer));
		assertEquals(now - 1L, recorded.get(other));
		assertFalse(recorded.containsKey(expired));
	}

	@Test
	void aDisabledCooldownDoesNotWipeOrRecord() {
		Map<UUID, Long> existing = Map.of(killer, 1_000L);
		assertEquals(existing, StrikeCooldown.record(existing, other, 2_000L, 0L));
	}

	@Test
	void remainingTimeRoundsUpToAMinute() {
		assertEquals(day, StrikeCooldown.remainingMs(1_000L, 1_000L, day));
		assertEquals(0L, StrikeCooldown.remainingMs(1_000L, 1_000L + day, day));
		assertEquals(0L, StrikeCooldown.remainingMs(1_000L, 1_000L, 0L));
		assertEquals(day - 1, StrikeCooldown.remainingMs(Map.of(killer, 1_000L), killer, 1_001L, day));
		assertEquals(0L, StrikeCooldown.remainingMs(Map.of(), killer, 1_001L, day));
		assertEquals(0L, StrikeCooldown.remainingMs((Map<UUID, Long>) null, killer, 1_001L, day));
		assertEquals("24h", StrikeCooldown.formatRemaining(day));
		assertEquals("23h 1m", StrikeCooldown.formatRemaining(23 * 3_600_000L + 1L));
		assertEquals("1m", StrikeCooldown.formatRemaining(1L));
		assertEquals("1m", StrikeCooldown.formatRemaining(0L));
	}

	@Test
	void blankEntriesAreDroppedAndAMissingHistoryCanStillBeRecorded() {
		assertFalse(StrikeCooldown.blocks((Map<UUID, Long>) null, killer, 1L, day));
		assertFalse(StrikeCooldown.blocks(Map.of(), null, 1L, day));
		assertEquals(Map.of(killer, 5L), StrikeCooldown.record(null, killer, 5L, day));

		Map<UUID, Long> messy = new LinkedHashMap<>();
		messy.put(killer, 0L);
		messy.put(other, null);
		assertEquals(Map.of(killer, 9L), StrikeCooldown.record(messy, killer, 9L, day));
	}
}
