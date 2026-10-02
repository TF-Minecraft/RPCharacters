package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import net.Indyuce.mmocore.api.event.PlayerChangeClassEvent;
import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.experience.Profession;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Dependency;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.enums.ConfirmType;
import net.tfminecraft.rpcharacters.enums.CreationGuiContext;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.holder.RPCHolder;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

class PlayerManagerEventsTest extends PlayerManagerFixture {
    @Test
    void confirmationKillsOrSwitchesAndBlocksEvilRoleplayUnlessAdmin() {
        manager.confirmClick(player, character, ConfirmType.KILL);
        deaths.verify(() -> PermadeathService.killCharacter(player, character, PermakillCause.CHARACTER_MENU));
        verify(lastInventory()).characterView(player, character);
        evil.when(() -> EvilRpService.blocksCharacterSwitch(player)).thenReturn(true);
        manager.confirmClick(player, character, ConfirmType.SWITCH);
        evil.verify(() -> EvilRpService.sendSwitchBlocked(player));
        verify(data, never()).setActiveCharacter(any());
        permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true);
        manager.confirmClick(player, character, ConfirmType.SWITCH);
        verify(data).setActiveCharacter(character);
        verify(clueVisuals).refreshViewer(player);
        wardrobe.verify(() -> WardrobeService.refreshActiveAsync(player));
        verify(lastInventory()).characterView(player, character);
    }

    @Test
    void revivalRequiresLoadedOwnerAndFreeSlotThenSavesAndActivates() {
        tracked.clear();
        manager.confirmClick(player, character, ConfirmType.REVIVE);
        message("Could not find that player's data.");
        tracked.add(data);
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(false);
        manager.confirmClick(player, character, ConfirmType.REVIVE);
        verify(character, never()).setStatus(any());
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(true);
        active.set(null);
        manager.confirmClick(player, character, ConfirmType.REVIVE);
        verify(character).setStatus(Status.ALIVE);
        assertSame(character, active.get());
        verify(database).savePlayer(data);
        verify(clueVisuals).refreshViewer(player);
        wardrobe.verify(() -> WardrobeService.refreshActiveAsync(player));
        verify(lastInventory()).characterView(player, character);
        manager.confirmClick(player, character, ConfirmType.REVIVE);
        verify(data, times(1)).setActiveCharacter(character);
    }

    @Test
    void traitEditorCopiesMatchingStageChecksDependencyAndUpdatesItsSelections() {
        SelectionStage wrong = mock(SelectionStage.class), matching = mock(SelectionStage.class);
        when(wrong.getKey()).thenReturn("other"); when(matching.getKey()).thenReturn("traits");
        Dependency dependency = mock(Dependency.class);
        when(matching.hasDependency()).thenReturn(true); when(matching.getDependency()).thenReturn(dependency);
        try (var loader = mockStatic(StageLoader.class);
             var copies = mockConstruction(SelectionStage.class, (copy, context) -> {
                 SelectionStage template = (SelectionStage) context.arguments().getFirst();
                 String key = template.getKey(); boolean hasDependency = template.hasDependency();
                 Dependency required = template.getDependency();
                 when(copy.getKey()).thenReturn(key); when(copy.hasDependency()).thenReturn(hasDependency);
                 when(copy.getDependency()).thenReturn(required);
             })) {
            loader.when(StageLoader::getNew).thenReturn(List.of(mock(InfoStage.class), wrong, matching));
            manager.traitEdit(player, "traits");
            message("You do not fulfill the prerequisites to view those traits (traits)");
            assertTrue(inventories.constructed().isEmpty());
            when(dependency.check(character)).thenReturn(true);
            manager.traitEdit(player, "traits");
            SelectionStage copy = copies.constructed().getLast();
            verify(copy).update(data);
            verify(lastInventory()).selectionView(player, copy, null);
            when(matching.hasDependency()).thenReturn(false);
            manager.traitEdit(player, "traits");
            verify(lastInventory()).selectionView(player, copies.constructed().getLast(), null);
        }
    }

    @Test
    void selectionGuardsIgnoreOtherHoldersOutsideClicksAndBottomInventory() {
        InventoryClickEvent unrelated = event(null, "Chest", 0, null);
        manager.selectionClick(unrelated);
        verify(unrelated, never()).setCancelled(anyBoolean());
        InventoryClickEvent outside = menu("Character Menu", 10, null, player);
        when(outside.getClickedInventory()).thenReturn(null);
        manager.selectionClick(outside);
        verify(outside, never()).setCancelled(anyBoolean());
        Inventory bottom = player.getInventory();
        when(outside.getClickedInventory()).thenReturn(bottom);
        manager.selectionClick(outside);
        verify(outside, never()).setCancelled(anyBoolean());
        manager.selectionClick(menu("Unrelated Menu", 0, null, player));
        assertTrue(inventories.constructed().isEmpty());
    }

    @Test
    void characterMenuOpensDeadAndLivingCharactersAndEnforcesCreationSlots() {
        manager.selectionClick(menu("Character Menu", Cache.deadSlot, new ItemStack(Material.PAPER), player));
        assertTrue(inventories.constructed().isEmpty());
        when(data.getCharacters(Status.DEAD)).thenReturn(List.of(character));
        manager.selectionClick(menu("Character Menu", Cache.deadSlot, new ItemStack(Material.PAPER), player));
        verify(lastInventory()).deadView(player, player);
        manager.selectionClick(menu("Character Menu", 10, null, player));
        manager.selectionClick(menu("Character Menu", 10, new ItemStack(Material.GRAY_STAINED_GLASS_PANE), player));
        manager.selectionClick(menu("Character Menu", 10, new ItemStack(Material.BARRIER), player));
        message("This character slot is locked.");
        Player other = server.addPlayer("Other");
        manager.selectionClick(menu("Character Menu", 10, new ItemStack(Material.YELLOW_CONCRETE), other));
        slots.when(() -> CharacterSlotService.isSlotUnlocked(player, 0)).thenReturn(false);
        manager.selectionClick(menu("Character Menu", 10, new ItemStack(Material.YELLOW_CONCRETE), player));
        slots.when(() -> CharacterSlotService.isSlotUnlocked(player, 0)).thenReturn(true);
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(false);
        manager.selectionClick(menu("Character Menu", 10, new ItemStack(Material.YELLOW_CONCRETE), player));
        message("You don't have a free character slot!");
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(true);
        try (var creation = mockStatic(CreationManager.class)) {
            manager.selectionClick(menu("Character Menu", 10, new ItemStack(Material.YELLOW_CONCRETE), player));
            creation.verify(() -> CreationManager.initiateCreation(player));
        }
        manager.selectionClick(menu("Character Menu", 10, item(Material.ENDER_PEARL, "character"), player));
        verify(lastInventory()).characterView(player, character);
        manager.selectionClick(menu("Character Menu", 10, item(Material.ENDER_PEARL, "missing"), player));
        message("Cant find character");
        manager.selectionClick(menu("Character Menu", 10, item(Material.ENDER_PEARL, "character"), null));
        message("Cant find player, maybe they are offline?");
        Cache.characterSlots.add(0);
        manager.selectionClick(menu("Character Menu", 0, item(Material.ENDER_PEARL, "character"), player));
    }

    @Test
    void characterInfoOpensProfileTraitsAndOwnedClues() {
        manager.selectionClick(menu("Character Info", 26, new ItemStack(Material.ARROW), player));
        verify(lastInventory()).profileView(player, player);
        manager.selectionClick(menu("Character Info", 26, new ItemStack(Material.ARROW), null));
        message("Cant find player, maybe they are offline?");
        manager.selectionClick(info(14, Material.PAPER, "character", player));
        verify(lastInventory()).traitsView(player, character);
        manager.selectionClick(info(14, Material.PAPER, "missing", player));
        message("Cant find character");
        manager.selectionClick(info(14, Material.PAPER, "character", null));
        manager.selectionClick(menu("Character Info", 14, new ItemStack(Material.PAPER), player));
        manager.selectionClick(menu("Character Info", 16, item(Material.BOOK, "character"), player));
        verify(lastInventory()).cluesView(player, character);
        manager.selectionClick(menu("Character Info", 16, item(Material.BOOK, "missing"), player));
        manager.selectionClick(menu("Character Info", 16, item(Material.BOOK, "character"), server.addPlayer("Other")));
        manager.selectionClick(menu("Character Info", 16, new ItemStack(Material.BOOK), player));
        manager.selectionClick(menu("Character Info", 16, new ItemStack(Material.PAPER), player));
        manager.selectionClick(menu("Character Info", 16, null, player));
        InventoryClickEvent noMeta = menu("Character Info", 16, null, player);
        when(noMeta.getCurrentItem()).thenReturn(mock(ItemStack.class));
        manager.selectionClick(noMeta);
    }

    @Test
    void killAndSwitchMenusRecordAndConsumeConfirmationsOrReturnOnCancel() {
        for (int slot : List.of(8, 6)) {
            Material action = slot == 8 ? Material.IRON_AXE : Material.EMERALD;
            manager.selectionClick(info(slot, Material.PAPER, "character", player));
            manager.selectionClick(info(slot, action, "character", null));
            manager.selectionClick(info(slot, action, "missing", player));
            manager.selectionClick(info(slot, action, "character", player));
            verify(lastInventory()).confirmView(player);
            manager.selectionClick(menu("Confirm Action", 0, null, player));
            manager.selectionClick(menu("Confirm Action", 15, null, player));
            verify(lastInventory()).characterView(player, character);
            assertTrue(confirmations().isEmpty());
            manager.selectionClick(info(slot, action, "character", player));
            manager.selectionClick(menu("Confirm Action", 11, null, player));
            assertTrue(confirmations().isEmpty());
            assertTrue(lastCharacters().isEmpty());
        }
        deaths.verify(() -> PermadeathService.killCharacter(player, character, PermakillCause.CHARACTER_MENU));
        verify(data).setActiveCharacter(character);
        manager.selectionClick(menu("Confirm Action", 11, null, player));
        assertTrue(confirmations().isEmpty());
    }

    @Test
    void reviveMenuRequiresAdminDeadCharacterAndCapacity() {
        manager.selectionClick(info(4, Material.TOTEM_OF_UNDYING, "character", player));
        assertTrue(confirmations().isEmpty());
        permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true);
        manager.selectionClick(menu("Character Info", 4, new ItemStack(Material.TOTEM_OF_UNDYING), player));
        manager.selectionClick(info(4, Material.PAPER, "character", player));
        manager.selectionClick(info(4, Material.TOTEM_OF_UNDYING, "character", null));
        manager.selectionClick(info(4, Material.TOTEM_OF_UNDYING, "missing", player));
        manager.selectionClick(info(4, Material.TOTEM_OF_UNDYING, "character", player));
        assertTrue(confirmations().isEmpty());
        when(character.getStatus()).thenReturn(Status.DEAD);
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(false);
        manager.selectionClick(info(4, Material.TOTEM_OF_UNDYING, "character", player));
        assertTrue(confirmations().isEmpty());
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(true);
        manager.selectionClick(info(4, Material.TOTEM_OF_UNDYING, "character", player));
        assertEquals(ConfirmType.REVIVE, confirmations().get(player));
        verify(lastInventory()).confirmView(player);
        manager.selectionClick(menu("Confirm Action", 11, null, player));
        verify(character).setStatus(Status.ALIVE);
    }

    @Test
    void clueMenuBackRoutesToSummaryOrSavedCharacterAndStartsInput() {
        CharacterCreation creation = mock(CharacterCreation.class);
        for (CreationGuiContext context : List.of(CreationGuiContext.CREATION_SUMMARY, CreationGuiContext.EDIT_SUMMARY)) {
            InventoryClickEvent back = clues(26, item(Material.ARROW, "character"), player, creation, context);
            manager.selectionClick(back);
        }
        verify(creation, times(2)).returnToSummary();
        manager.selectionClick(clues(26, item(Material.ARROW, "character"), player, null, CreationGuiContext.NONE));
        verify(lastInventory()).characterView(player, character);
        manager.selectionClick(clues(26, item(Material.ARROW, "missing"), player, null, CreationGuiContext.NONE));
        manager.selectionClick(clues(26, new ItemStack(Material.ARROW), player, null, CreationGuiContext.NONE));
        try (var inputs = mockStatic(ClueInputManager.class)) {
            manager.selectionClick(clues(8, item(Material.LIME_DYE, "character"), player, creation, CreationGuiContext.CREATION_SUMMARY));
            inputs.verify(() -> ClueInputManager.beginInput(player, "character", true));
            manager.selectionClick(clues(8, new ItemStack(Material.LIME_DYE), player, null, CreationGuiContext.NONE));
            manager.selectionClick(clues(8, item(Material.LIME_DYE, "character"), server.addPlayer("Other"), null, CreationGuiContext.NONE));
            inputs.verifyNoMoreInteractions();
        }
    }

    @Test
    void clueRemovalPersistsSavedCharactersButKeepsDraftChangesInTheSession() {
        when(character.removePlayerClue(2)).thenReturn(true);
        ItemStack paper = clueItem("character", 2);
        manager.selectionClick(clues(0, paper, player, null, CreationGuiContext.NONE));
        verify(character).removePlayerClue(2);
        verify(database).savePlayer(data);
        verify(lastInventory()).cluesView(player, character);
        CharacterCreation creation = mock(CharacterCreation.class);
        when(creation.getCharacter()).thenReturn(character);
        CreationManager.activeCreators.put(player, creation);
        manager.selectionClick(clues(0, paper, player, creation, CreationGuiContext.CREATION_SUMMARY));
        verify(database, times(1)).savePlayer(data);
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.CREATION_SUMMARY, creation);
        when(creation.isEditing()).thenReturn(true);
        manager.selectionClick(clues(0, paper, player, creation, CreationGuiContext.EDIT_SUMMARY));
        verify(database, times(2)).savePlayer(data);
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.EDIT_SUMMARY, creation);
    }

    @Test
    void clueMenuIgnoresMissingOwnerDataItemsIdsIndicesAndUnknownCharacters() {
        manager.selectionClick(clues(0, clueItem("character", 2), null, null, CreationGuiContext.NONE));
        Player other = server.addPlayer("Other");
        manager.selectionClick(clues(0, clueItem("character", 2), other, null, CreationGuiContext.NONE));
        manager.selectionClick(clues(0, null, player, null, CreationGuiContext.NONE));
        InventoryClickEvent noMeta = clues(0, null, player, null, CreationGuiContext.NONE);
        when(noMeta.getCurrentItem()).thenReturn(mock(ItemStack.class));
        manager.selectionClick(noMeta);
        manager.selectionClick(clues(0, new ItemStack(Material.PAPER), player, null, CreationGuiContext.NONE));
        manager.selectionClick(clues(0, clueItem("missing", 2), player, null, CreationGuiContext.NONE));
        manager.selectionClick(clues(0, item(Material.PAPER, "character"), player, null, CreationGuiContext.NONE));
        manager.selectionClick(clues(0, clueItem("character", 2), player, null, CreationGuiContext.NONE));
        verify(database, never()).savePlayer(any());
        assertTrue(inventories.constructed().isEmpty());
    }

    @Test
    void traitAndDeadListsReturnToCharactersOrProfiles() {
        manager.selectionClick(menu("Trait List", 0, null, player));
        manager.selectionClick(menu("Trait List", 26, null, player));
        InventoryClickEvent noMeta = menu("Trait List", 26, null, player);
        when(noMeta.getCurrentItem()).thenReturn(mock(ItemStack.class));
        manager.selectionClick(noMeta);
        manager.selectionClick(menu("Trait List", 26, item(Material.ARROW, "character"), null));
        manager.selectionClick(menu("Trait List", 26, item(Material.ARROW, "missing"), player));
        manager.selectionClick(menu("Trait List", 26, item(Material.ARROW, "character"), player));
        verify(lastInventory()).characterView(player, character);
        manager.selectionClick(menu("Dead Characters", 0, item(Material.ENDER_PEARL, "character"), null));
        manager.selectionClick(menu("Dead Characters", 0, item(Material.ENDER_PEARL, "missing"), player));
        manager.selectionClick(menu("Dead Characters", 0, item(Material.ENDER_PEARL, "character"), player));
        verify(lastInventory()).characterView(player, character);
        manager.selectionClick(menu("Dead Characters", 26, new ItemStack(Material.ARROW), null));
        manager.selectionClick(menu("Dead Characters", 26, new ItemStack(Material.ARROW), player));
        verify(lastInventory()).profileView(player, player);
    }

    @Test
    void emptyDeadListAndInfoActionSlotsAreHarmless() {
        for (String title : List.of("Dead Characters", "Character Info")) {
            for (int slot : List.of(0, 4, 6, 8)) {
                permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true);
                InventoryClickEvent event = menu(title, slot, null, player);
                assertDoesNotThrow(() -> manager.selectionClick(event), title + " slot " + slot);
                if (title.equals("Character Info") && slot != 0) {
                    event.getInventory().setItem(10, item(Material.ENDER_PEARL, "character"));
                    assertDoesNotThrow(() -> manager.selectionClick(event), "Empty action with a valid character icon");
                    Inventory displayed = mock(Inventory.class);
                    when(displayed.getItem(10)).thenReturn(mock(ItemStack.class));
                    when(event.getInventory()).thenReturn(displayed);
                    assertDoesNotThrow(() -> manager.selectionClick(event), "Character icon without metadata");
                }
            }
        }
        assertTrue(confirmations().isEmpty());
    }

    @Test
    void characterMenusHandleOwnerDisconnectWithoutDereferencingMissingData() {
        tracked.clear();
        for (String title : List.of("Character Menu", "Character Info", "Dead Characters", "Trait List")) {
            int slot = title.equals("Character Info") ? 6 : title.equals("Trait List") ? 26 : 10;
            InventoryClickEvent event = title.equals("Character Info")
                ? info(slot, Material.EMERALD, "character", player)
                : menu(title, slot, item(Material.ENDER_PEARL, "character"), player);
            assertDoesNotThrow(() -> manager.selectionClick(event), title);
        }
        assertTrue(inventories.constructed().isEmpty());
    }

    @Test
    void experienceModifierUsesProfessionAndTracksClassProgression() {
        PlayerExperienceGainEvent event = experience("mining", 20);
        active.set(null); manager.xpGain(event);
        verify(event, never()).setExperience(anyDouble());
        active.set(character);
        when(event.getProfession()).thenReturn(null);
        manager.xpGain(event);
        classes.verify(() -> ClassService.trackFromPlayer(player));
        event = experience("mining", 20);
        manager.xpGain(event);
        verify(event, never()).setExperience(anyDouble());
        character.getAttributeData().getExperienceModifiers().add(new ExperienceModifier("woodcutting", "Wood", 100));
        character.getAttributeData().getExperienceModifiers().add(new ExperienceModifier("MINING", "Mining", 50));
        manager.xpGain(event);
        verify(event).setExperience(30.0);
        classes.verify(() -> ClassService.trackFromPlayer(player), times(2));
    }

    @Test
    void experienceBeforePlayerDataLoadsIsIgnored() {
        tracked.clear();
        PlayerExperienceGainEvent event = experience("mining", 20);
        assertDoesNotThrow(() -> manager.xpGain(event));
        verify(event, never()).setExperience(anyDouble());
    }

    @Test
    void onlyActualLevelUpsTrackProgression() {
        PlayerLevelChangeEvent event = mock(PlayerLevelChangeEvent.class);
        when(event.getPlayer()).thenReturn(player);
        for (PlayerLevelChangeEvent.Reason reason : PlayerLevelChangeEvent.Reason.values()) {
            when(event.getReason()).thenReturn(reason);
            manager.levelUp(event);
        }
        classes.verify(() -> ClassService.trackFromPlayer(player), times(1));
    }

    @Test
    void classChangesUpdateCreationAndEditingSessionsWithoutOverwritingSavedCharacters() {
        PlayerChangeClassEvent event = classChange("mage");
        tracked.clear(); manager.classChange(event);
        verify(character, never()).setMMOClass(anyString());
        tracked.add(data);
        CharacterCreation session = mock(CharacterCreation.class);
        when(session.getCharacter()).thenReturn(character);
        CreationManager.activeCreators.put(player, session);
        manager.classChange(event);
        assertEquals("mage", character.getMMOClass());
        classes.verify(() -> ClassService.restoreAccountProgression(player));
        when(session.isEditing()).thenReturn(true);
        when(character.isActive()).thenReturn(false);
        manager.classChange(classChange("ranger"));
        lifecycle.verify(() -> CharacterLifecycle.notifyClassChange(player, player.getUniqueId(), character, "mage", "ranger"));
        server.getScheduler().performOneTick();
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(any(), any()), never());
        when(character.isActive()).thenReturn(true);
        classes.when(() -> ClassService.isApplying(player.getUniqueId())).thenReturn(true);
        manager.classChange(classChange("warrior"));
        server.getScheduler().performOneTick();
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(player, character));
        classes.verify(() -> ClassService.restoreAccountProgression(player), times(2));
    }

    @Test
    void classChangesUpdateActiveCharacterAndReapplyAttributesOnTheNextTick() {
        CreationManager.activeCreators.put(player, null);
        manager.classChange(classChange("mage"));
        verify(character, never()).setMMOClass(anyString());
        CreationManager.activeCreators.clear();
        active.set(null); manager.classChange(classChange("mage"));
        verify(character, never()).setMMOClass(anyString());
        active.set(character);
        manager.classChange(classChange("mage"));
        assertEquals("mage", character.getMMOClass());
        lifecycle.verify(() -> CharacterLifecycle.notifyClassChange(player, player.getUniqueId(), character, "warrior", "mage"));
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(player, character), never());
        server.getScheduler().performOneTick();
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(player, character));
        classes.when(() -> ClassService.isApplying(player.getUniqueId())).thenReturn(true);
        manager.classChange(classChange("ranger"));
        player.disconnect(); server.getScheduler().performOneTick();
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(player, character), times(1));
    }

    @Test
    void delayedAttributeApplicationCannotApplyThePreviousCharacterAfterSwitching() {
        manager.classChange(classChange("mage"));
        active.set(mock(RPCharacter.class));
        server.getScheduler().performOneTick();
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(player, character), never());
        active.set(character);
        manager.classChange(classChange("ranger"));
        tracked.clear();
        server.getScheduler().performOneTick();
        attributes.verify(() -> AttributePointService.applyCharacterAttributes(player, character), never());
    }

    private Map<Player, ConfirmType> confirmations() { return field(manager, "confirm"); }
    private Map<Player, RPCharacter> lastCharacters() { return field(manager, "last"); }

    private PlayerExperienceGainEvent experience(String id, double amount) {
        PlayerExperienceGainEvent event = mock(PlayerExperienceGainEvent.class);
        Profession profession = mock(Profession.class); when(profession.getId()).thenReturn(id);
        when(event.getPlayer()).thenReturn(player); when(event.getProfession()).thenReturn(profession);
        when(event.getExperience()).thenReturn(amount);
        return event;
    }

    private PlayerChangeClassEvent classChange(String id) {
        PlayerChangeClassEvent event = mock(PlayerChangeClassEvent.class);
        PlayerClass profession = mock(PlayerClass.class); when(profession.getId()).thenReturn(id);
        when(mmo.getProfess()).thenReturn(profession);
        when(event.getPlayer()).thenReturn(player); when(event.getData()).thenReturn(mmo);
        return event;
    }

    private InventoryClickEvent menu(String title, int slot, ItemStack current, Player owner) {
        return event(new RPCHolder(owner), "§7" + title, slot, current);
    }

    private InventoryClickEvent info(int slot, Material action, String id, Player owner) {
        InventoryClickEvent event = menu("Character Info", slot, new ItemStack(action), owner);
        event.getInventory().setItem(10, item(Material.ENDER_PEARL, id));
        return event;
    }

    private InventoryClickEvent clues(int slot, ItemStack item, Player owner, CharacterCreation creation, CreationGuiContext context) {
        return event(new RPCHolder(owner, creation, context), "§7Clues (Aria)", slot, item);
    }

    private InventoryClickEvent event(RPCHolder holder, String title, int slot, ItemStack current) {
        Inventory inventory = Bukkit.createInventory(holder, 27);
        inventory.setItem(slot, current);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory); when(view.getTitle()).thenReturn(title);
        when(view.getPlayer()).thenReturn(player);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory); when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getSlot()).thenReturn(slot); when(event.getCurrentItem()).thenAnswer(call -> inventory.getItem(slot));
        return event;
    }

    private ItemStack item(Material material, String id) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        if (id != null) meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "character_id"), PersistentDataType.STRING, id);
        item.setItemMeta(meta); return item;
    }

    private ItemStack clueItem(String id, int index) {
        ItemStack item = item(Material.PAPER, id);
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "clue_index"), PersistentDataType.INTEGER, index);
        item.setItemMeta(meta); return item;
    }
}
