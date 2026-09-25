package net.tfminecraft.rpcharacters.grave;

import java.util.List;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GraveLootRulesTest {

	@Test
	void graveLootTraitOrAdminCanTakeAGrave() {
		Player killer = mock(Player.class);
		when(killer.hasPermission("rpchar.grave.admin")).thenReturn(false);
		PlayerData data = mock(PlayerData.class);
		RPCharacter character = mock(RPCharacter.class);
		Trait trait = mock(Trait.class);
		TraitData traitData = mock(TraitData.class);
		when(data.hasActiveCharacter()).thenReturn(true);
		when(data.getActiveCharacter()).thenReturn(character);
		when(character.getTraits()).thenReturn(List.of(trait));
		when(trait.getTraitData()).thenReturn(traitData);
		when(traitData.canLootGraves()).thenReturn(false);
		try (var players = mockStatic(PlayerManager.class)) {
			players.when(() -> PlayerManager.get(killer)).thenReturn(data);
			assertFalse(GraveLootRules.canLootGrave(killer));
			when(traitData.canLootGraves()).thenReturn(true);
			assertTrue(GraveLootRules.canLootGrave(killer));
		}
		when(killer.hasPermission("rpchar.grave.admin")).thenReturn(true);
		assertTrue(GraveLootRules.canLootGrave(killer));
	}
}
