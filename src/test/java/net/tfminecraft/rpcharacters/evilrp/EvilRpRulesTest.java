package net.tfminecraft.rpcharacters.evilrp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvilRpRulesTest {

    @Test
    void strikesEscalateToDeathOnTheThird() {
        assertEquals(StrikeOutcome.HEALING_INJURY, StrikeOutcome.forStrike(1));
        assertEquals(StrikeOutcome.PERMANENT_INJURY, StrikeOutcome.forStrike(2));
        assertEquals(StrikeOutcome.DEATH, StrikeOutcome.forStrike(3));
        // Staff can leave a character above three; any further strike still kills.
        assertEquals(StrikeOutcome.DEATH, StrikeOutcome.forStrike(5));
    }

    @Test
    void sessionRunsUntilItsEndTime() {
        assertTrue(EvilRpService.isInSession(10_000L, 9_999L));
        assertFalse(EvilRpService.isInSession(10_000L, 10_000L));
        assertFalse(EvilRpService.isInSession(0L, 5L));
    }

    @Test
    void remainingTimeRoundsUpToWholeSeconds() {
        assertEquals("30:00", EvilRpService.formatRemaining(30 * 60_000L));
        assertEquals("0:01", EvilRpService.formatRemaining(1L));
        assertEquals("12:05", EvilRpService.formatRemaining(12 * 60_000L + 4_500L));
    }

    @Test
    void anyStrikeKillsDuringAnEvilSession() {
        assertTrue(StrikeOutcome.nextStrikeKills(0, true));
        assertFalse(StrikeOutcome.nextStrikeKills(0, false));
        assertFalse(StrikeOutcome.nextStrikeKills(1, false));
        assertTrue(StrikeOutcome.nextStrikeKills(2, false));
    }

    @Test
    void oneStrikeWearsOffPerPeriod() {
        long day = 86_400_000L;
        long period = 14 * day;
        StrikeDecay.Result early = StrikeDecay.apply(2, 1_000L, 1_000L + period - 1, period);
        assertEquals(2, early.strikes());
        assertEquals(1_000L, early.lastStrikeAtMs());

        StrikeDecay.Result one = StrikeDecay.apply(2, 1_000L, 1_000L + period + day, period);
        assertEquals(1, one.strikes());
        // The next strike wears off a full period after the first one did, not after the check.
        assertEquals(1_000L + period, one.lastStrikeAtMs());

        assertEquals(0, StrikeDecay.apply(2, 1_000L, 1_000L + 5 * period, period).strikes());
    }

    @Test
    void untrackedStrikesStartTheirClockNow() {
        StrikeDecay.Result result = StrikeDecay.apply(1, 0L, 50_000L, 10L);
        assertEquals(1, result.strikes());
        assertEquals(50_000L, result.lastStrikeAtMs());
    }
}
