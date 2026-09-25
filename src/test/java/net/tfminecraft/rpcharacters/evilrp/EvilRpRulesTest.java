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
}
