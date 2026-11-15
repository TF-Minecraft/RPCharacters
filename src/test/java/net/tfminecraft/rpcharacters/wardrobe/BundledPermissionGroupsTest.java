package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.PermissionGroupsLoader;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;

/** The shipped permission-groups.yml: donor wardrobe ladder and the hidden staff group. */
class BundledPermissionGroupsTest {

	private List<PermissionGroupDefinition> groups;
	private Map<String, Integer> defaults;
	private int maxSlots;

	@BeforeEach void loadBundledFile() throws URISyntaxException {
		groups = Cache.permissionGroups;
		defaults = Cache.permissionGroupDefaults;
		maxSlots = Cache.maxCharacterSlots;
		Cache.permissionGroups = new ArrayList<>();
		Cache.permissionGroupDefaults = new HashMap<>();
		Cache.maxCharacterSlots = 10;
		new PermissionGroupsLoader().load(new File(getClass().getResource("/permission-groups.yml").toURI()));
	}

	@AfterEach void restore() {
		Cache.permissionGroups = groups;
		Cache.permissionGroupDefaults = defaults;
		Cache.maxCharacterSlots = maxSlots;
	}

	private static Player holding(String... permissions) {
		Set<String> held = Set.of(permissions);
		Player player = mock(Player.class);
		for (String node : List.of("rpchar.group.noble", "rpchar.group.gilded", "rpchar.group.ascended",
				"rpchar.group.legacy", "rpchar.group.staff")) {
			when(player.hasPermission(node)).thenReturn(held.contains(node));
		}
		return player;
	}

	@Test void eachDonorRankGetsMoreWardrobeSkinsUpToTheCap() {
		assertEquals(1, PermissionGroupService.getWardrobeSkinSlots(holding()));
		assertEquals(2, PermissionGroupService.getWardrobeSkinSlots(holding("rpchar.group.noble")));
		assertEquals(3, PermissionGroupService.getWardrobeSkinSlots(holding("rpchar.group.gilded")));
		assertEquals(5, PermissionGroupService.getWardrobeSkinSlots(holding("rpchar.group.ascended")));
		assertEquals(5, PermissionGroupService.getWardrobeSkinSlots(holding("rpchar.group.legacy")));
		for (PermissionGroupDefinition group : Cache.permissionGroups) {
			assertTrue(group.getPerk(PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS, 1) <= WardrobeSlots.MAX_SWAPPABLE,
					group.getId());
		}
	}

	@Test void wardrobeLocksNameTheLowestVisibleRankAndNeverStaff() {
		Map<String, String> expected = Map.of("extra_1", "noble", "extra_2", "gilded", "extra_3", "ascended",
				"extra_4", "ascended");
		expected.forEach((slot, rank) -> assertEquals(rank,
				WardrobeGui.getMinimumVisibleUnlockGroup(WardrobeGui.minSlotsFor(slot)).orElseThrow().getId(), slot));
	}

	@Test void staffAndHelpersGetEightCharactersFiveSkinsAndNoSwitchCooldownOnTopOfTheirRank() {
		Player staff = holding("rpchar.group.noble", "rpchar.group.staff");
		assertEquals(8, CharacterSlotService.getMaxAliveCharacters(staff));
		assertEquals(5, PermissionGroupService.getWardrobeSkinSlots(staff));
		assertEquals(0, PermissionGroupService.getCharacterSwitchCooldownDays(staff));
		// Without the staff group the noble keeps its own numbers.
		Player noble = holding("rpchar.group.noble");
		assertEquals(3, CharacterSlotService.getMaxAliveCharacters(noble));
		assertTrue(PermissionGroupService.getCharacterSwitchCooldownDays(noble) > 0);
	}

	@Test void theStaffGroupIsHiddenFromLockedSlotLore() {
		PermissionGroupDefinition staff = Cache.permissionGroups.stream()
				.filter(group -> "staff".equals(group.getId())).findFirst().orElseThrow();
		assertFalse(staff.isVisible());
		for (int slot = 0; slot < 10; slot++) {
			CharacterSlotService.getMinimumVisibleUnlockGroup(slot)
					.ifPresent(group -> assertTrue(group.isVisible(), group.getId()));
		}
		assertTrue(CharacterSlotService.getMinimumVisibleUnlockGroup(5).isEmpty(),
				"No visible rank grants a sixth character");
	}
}
