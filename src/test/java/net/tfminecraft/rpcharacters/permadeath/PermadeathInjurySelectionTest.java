package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.trait.Trait;

class PermadeathInjurySelectionTest {

	@Test
	void noHealingInjuryLeavesRoomForANewRoll() {
		assertNull(PermadeathService.selectHealingInjuryToUpgrade(List.of()));
		assertNull(PermadeathService.selectHealingInjuryToUpgrade(null));
	}

	@Test
	void singleHealingInjuryIsTheUpgrade() {
		Trait healing = mock(Trait.class);
		assertSame(healing, PermadeathService.selectHealingInjuryToUpgrade(List.of(healing)));
	}

	@Test
	void extraHealingInjuriesAreLeftForLaterDeaths() {
		Trait first = mock(Trait.class);
		Trait second = mock(Trait.class);
		Trait third = mock(Trait.class);
		assertSame(first, PermadeathService.selectHealingInjuryToUpgrade(List.of(first, second, third)));
	}
}
