package net.tfminecraft.rpcharacters.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.trait.Trait;

class ExpiredDurationTraitsTest {

	private static final long NOW = 1_790_000_000_000L;

	@Test
	void removesOnlyInjuriesWhoseTimeRanOut() {
		Trait healed = trait("broken_arm", true);
		Trait healing = trait("broken_leg", true);
		Trait permanent = trait("one_legged", false);
		RPCharacter character = new RPCharacter(null);
		character.getTraits().addAll(List.of(healed, healing, permanent));
		character.setDurationExpiresAtMs("broken_arm", NOW - 1L);
		character.setDurationExpiresAtMs("broken_leg", NOW + 60_000L);

		assertTrue(character.removeExpiredDurationTraits(NOW));

		assertEquals(List.of(healing, permanent), character.getTraits());
		assertEquals(-1L, character.getDurationRemainingMs("broken_arm"));
		assertFalse(character.removeExpiredDurationTraits(NOW));
	}

	private static Trait trait(String id, boolean hasDuration) {
		Trait trait = mock(Trait.class);
		when(trait.getId()).thenReturn(id);
		when(trait.hasDuration()).thenReturn(hasDuration);
		return trait;
	}
}
