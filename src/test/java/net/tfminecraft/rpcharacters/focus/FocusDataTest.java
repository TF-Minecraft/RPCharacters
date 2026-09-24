package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FocusDataTest {
    @BeforeEach void reset() { FocusConfig.max = 150; }

    @Test
    void regeneratesWholeIntervalsAndPreservesRemainder() {
        FocusData data = FocusData.createNew("alice", "owner");
        data.setPoints(20);
        data.setLastRegenMs(1000);
        assertEquals(0, data.applyRegenForElapsed(10, 3_600_000, 3_600_999));
        assertEquals(20, data.applyRegenForElapsed(10, 3_600_000, 7_800_000));
        assertEquals(40, data.getPoints());
        assertEquals(7_201_000, data.getLastRegenMs());
    }

    @Test
    void spendingGrantsAndFullRegenerationKeepExistingLimits() {
        FocusData data = FocusData.createNew("alice", "owner");
        assertFalse(data.trySpend(151));
        assertTrue(data.trySpend(140));
        assertEquals(10, data.getPoints());
        data.grant(1000);
        assertEquals(150, data.getPoints());
        assertEquals(0, data.applyRegenForElapsed(10, 3_600_000, 99));
        assertEquals(99, data.getLastRegenMs());
    }
}
