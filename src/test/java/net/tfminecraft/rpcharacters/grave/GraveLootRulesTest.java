package net.tfminecraft.rpcharacters.grave;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

	@Test
	void unlockedGraveCanBeOpenedByAnyone() {
		UUID owner = UUID.randomUUID();
		Player stranger = player(UUID.randomUUID());
		Grave unlocked = grave(owner, null, false);
		assertTrue(GraveLootRules.canRecover(stranger, unlocked));
		assertFalse(GraveLootRules.canSteal(stranger, unlocked));

		Grave locked = grave(owner, null, true);
		try (var players = mockStatic(PlayerManager.class)) {
			players.when(() -> PlayerManager.get(stranger)).thenReturn(null);
			assertFalse(GraveLootRules.canRecover(stranger, locked));
			assertFalse(GraveLootRules.canSteal(stranger, locked));
		}
	}

	@Test
	void lockedGraveCanStillBeRobbedByItsKiller() {
		UUID owner = UUID.randomUUID();
		UUID killerId = UUID.randomUUID();
		Player killer = player(killerId);
		Player bystander = player(UUID.randomUUID());
		Grave locked = grave(owner, killerId, true);
		PlayerData killerData = looter(true);
		PlayerData bystanderData = looter(false);
		try (var players = mockStatic(PlayerManager.class)) {
			players.when(() -> PlayerManager.get(killer)).thenReturn(killerData);
			players.when(() -> PlayerManager.get(bystander)).thenReturn(bystanderData);
			assertTrue(GraveLootRules.canSteal(killer, locked));
			assertFalse(GraveLootRules.canRecover(killer, locked));
			assertFalse(GraveLootRules.canSteal(bystander, locked));
		}
	}

	private static Player player(UUID id) {
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(id);
		when(player.hasPermission("rpchar.grave.admin")).thenReturn(false);
		return player;
	}

	private static Grave grave(UUID owner, UUID killer, boolean locked) {
		return new Grave(UUID.randomUUID(), owner, killer, null, true, locked, 0L, 0,
				new ItemStack[Grave.STORAGE_SLOTS],
				new ItemStack[Grave.ARMOR_SLOTS],
				null, List.of(), null, null);
	}

	private static PlayerData looter(boolean canLoot) {
		PlayerData data = mock(PlayerData.class);
		RPCharacter character = mock(RPCharacter.class);
		Trait trait = mock(Trait.class);
		TraitData traitData = mock(TraitData.class);
		when(data.hasActiveCharacter()).thenReturn(true);
		when(data.getActiveCharacter()).thenReturn(character);
		when(character.getTraits()).thenReturn(List.of(trait));
		when(trait.getTraitData()).thenReturn(traitData);
		when(traitData.canLootGraves()).thenReturn(canLoot);
		return data;
	}
}
