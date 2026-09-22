package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

class GraveKillerDisplayTest {

	@Test
	void formatDamageCausePrettifiesUnderscores() {
		assertEquals("Fall", GraveKillerDisplay.formatDamageCause(EntityDamageEvent.DamageCause.FALL));
		assertEquals("Void", GraveKillerDisplay.formatDamageCause(EntityDamageEvent.DamageCause.VOID));
		assertEquals("Fire", GraveKillerDisplay.formatDamageCause(EntityDamageEvent.DamageCause.FIRE));
	}

	@Test
	void formatEntityTypePrettifiesUnderscores() {
		assertEquals("Zombie", GraveKillerDisplay.formatEntityType("ZOMBIE"));
		assertEquals("Zombie villager", GraveKillerDisplay.formatEntityType("ZOMBIE_VILLAGER"));
	}

	@Test
	void formatDisplayAddsKilledByPrefix() {
		assertEquals("Killed by Fall", GraveKillerDisplay.formatDisplay("Fall"));
		assertEquals("Killed by Masked", GraveKillerDisplay.formatDisplay("Masked"));
	}

	@Test
	void formatDisplayStripsColorCodes() {
		assertEquals("Killed by Alice", GraveKillerDisplay.formatDisplay("\u00A7cAlice"));
	}

	@Test
	void formatDisplayReturnsNullForBlankLabel() {
		assertNull(GraveKillerDisplay.formatDisplay(null));
		assertNull(GraveKillerDisplay.formatDisplay(""));
		assertNull(GraveKillerDisplay.formatDisplay("   "));
	}

	@Test
	void formatDisplayPrefixAlwaysPresentWhenLabelValid() {
		String result = GraveKillerDisplay.formatDisplay("Zombie");
		assertTrue(result.startsWith("Killed by "));
	}
}
