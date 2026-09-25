package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StrikeChoiceTest {

	@Test
	void woundAndMaimOnlyWhenTheStrikeWouldKill() {
		assertFalse(StrikeChoice.WOUND.isOffered(false));
		assertFalse(StrikeChoice.MAIM.isOffered(false));
		assertTrue(StrikeChoice.WOUND.isOffered(true));
		assertTrue(StrikeChoice.MAIM.isOffered(true));
	}

	@Test
	void spareAndStrikeAreAlwaysOffered() {
		for (boolean kills : new boolean[] { false, true }) {
			assertTrue(StrikeChoice.SPARE.isOffered(kills));
			assertTrue(StrikeChoice.STRIKE.isOffered(kills));
		}
	}

	@Test
	void killIsTheStrikeButton() {
		assertEquals(StrikeChoice.STRIKE, StrikeChoice.fromCommand("kill"));
		assertEquals(StrikeChoice.STRIKE, StrikeChoice.fromCommand("Strike"));
		assertEquals(StrikeChoice.WOUND, StrikeChoice.fromCommand("wound"));
		assertEquals(StrikeChoice.MAIM, StrikeChoice.fromCommand("maim"));
		assertEquals(StrikeChoice.SPARE, StrikeChoice.fromCommand("spare"));
		assertNull(StrikeChoice.fromCommand("strikes"));
	}
}
