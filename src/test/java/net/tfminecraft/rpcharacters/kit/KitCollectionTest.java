package net.tfminecraft.rpcharacters.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

class KitCollectionTest {

	private static final String PATH = "v.WRITABLE_BOOK";
	private static final KitEditableSpec EDITABLE =
			new KitEditableSpec("journal", "books", "book", null);
	private static final KitCustomiseData CUSTOMISE = new KitCustomiseData(
			"writable_book", "My journal", List.of("Custom lore"), "my_journal", PATH);

	@Test
	void collectedBooksAreCustomisedBeforeDeliveryAndKeepConfiguredAmount() {
		ItemStack template = mock(ItemStack.class);
		ItemStack first = mock(ItemStack.class);
		ItemStack second = mock(ItemStack.class);
		when(template.getType()).thenReturn(mock(Material.class));
		when(template.getMaxStackSize()).thenReturn(1);
		when(template.clone()).thenReturn(first, second);
		try (var customise = mockStatic(KitCustomiseApplyService.class)) {
			customise.when(() -> KitCustomiseApplyService.buildStack(CUSTOMISE)).thenReturn(template);
			List<ItemStack> stacks = KitService.buildStacks(
					new KitItemDefinition(PATH, 2, EDITABLE), Map.of("writable_book", CUSTOMISE));

			// These are the stacks handed to addItem, or dropped when inventory is full.
			assertEquals(List.of(first, second), stacks);
			assertNotSame(template, stacks.get(0));
			verify(first).setAmount(1);
			verify(second).setAmount(1);
			verify(template, never()).setAmount(1);
			customise.verify(() -> KitCustomiseApplyService.buildStack(CUSTOMISE));
		}
	}

	@Test
	void nonEditableBookLineIgnoresSavedJournalCustomisation() {
		assertUsesBaseItem(new KitItemDefinition(PATH, 1, null), Map.of("writable_book", CUSTOMISE));
	}

	@Test
	void editableBookWithoutSavedCustomisationUsesBaseItem() {
		assertUsesBaseItem(new KitItemDefinition(PATH, 1, EDITABLE), Map.of());
	}

	@Test
	void failedCustomItemBuildLeavesInventoryAndClaimUntouched() throws Exception {
		Player player = mock(Player.class);
		PlayerData pd = mock(PlayerData.class);
		RPCharacter character = mock(RPCharacter.class);
		when(character.getKitCustomisations()).thenReturn(Map.of("writable_book", CUSTOMISE));
		KitDefinition kit = new KitDefinition("starter", "Starter", 24, true, List.of(
				new KitItemDefinition("v.STONE", 1, null),
				new KitItemDefinition(PATH, 1, EDITABLE)));
		ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
		ItemStack stone = mock(ItemStack.class);
		when(api.getCreator().getItemFromPath("v.STONE")).thenReturn(stone);
		when(stone.getType()).thenReturn(mock(Material.class));
		when(stone.getMaxStackSize()).thenReturn(64);
		when(stone.clone()).thenReturn(stone);
		RPCharacters previous = RPCharacters.plugin;
		RPCharacters.plugin = mock(RPCharacters.class);
		when(RPCharacters.plugin.getLogger()).thenReturn(Logger.getLogger("KitCollectionTest"));
		try (var tlibs = mockStatic(TLibs.class);
				var customise = mockStatic(KitCustomiseApplyService.class);
				var texts = mockStatic(RPTexts.class)) {
			tlibs.when(TLibs::getItemAPI).thenReturn(api);
			customise.when(() -> KitCustomiseApplyService.requiredSkinsReady(
					character, kit.editableKitKeys())).thenReturn(true);
			customise.when(() -> KitCustomiseApplyService.buildStack(CUSTOMISE)).thenReturn(null);
			var grant = KitService.class.getDeclaredMethod("grantKitItems", Player.class,
					PlayerData.class, RPCharacter.class, KitDefinition.class, String.class);
			grant.setAccessible(true);
			grant.invoke(null, player, pd, character, kit, "starter");

			verify(player, never()).getInventory();
			verify(player, never()).getWorld();
			verify(character, never()).setKitStatus("starter", KitStatus.GRANTED);
			verifyNoInteractions(pd);
			texts.verify(() -> RPTexts.send(player, RPTexts.ERROR
					+ "That customised kit item could not be built. Your kit has not been claimed. Contact staff."));
		} finally {
			RPCharacters.plugin = previous;
		}
	}

	private void assertUsesBaseItem(KitItemDefinition definition, Map<String, KitCustomiseData> data) {
		ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
		ItemStack template = mock(ItemStack.class);
		ItemStack granted = mock(ItemStack.class);
		when(api.getCreator().getItemFromPath(PATH)).thenReturn(template);
		when(template.getType()).thenReturn(mock(Material.class));
		when(template.getMaxStackSize()).thenReturn(1);
		when(template.clone()).thenReturn(granted);
		try (var tlibs = mockStatic(TLibs.class);
				var customise = mockStatic(KitCustomiseApplyService.class)) {
			tlibs.when(TLibs::getItemAPI).thenReturn(api);
			assertEquals(List.of(granted), KitService.buildStacks(definition, data));
			verify(granted).setAmount(1);
			customise.verifyNoInteractions();
		}
	}
}
