package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.classpick.ClassPickGui;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Dependency;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.SummaryEditSupport;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.enums.CreationGuiContext;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.holder.RPCHolder;
import net.tfminecraft.rpcharacters.ingest.CharacterIngestService;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SelectableItem;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.utils.PlaytimeGate;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;

class CreationManagerTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private RuntimeTestState state;
    private ServerMock server;
    private Player player;
    private RPCharacters plugin;
    private PlayerData data;
    private RPCharacter character;
    private CharacterCreation creation;
    private PlayerManager playerManager;
    private CreationManager manager;
    private MockedStatic<CharacterCreation> sessions;
    private MockedStatic<PlayerManager> players;
    private MockedStatic<CharacterSlotService> slots;
    private MockedStatic<PermissionGroupService> cooldowns;
    private MockedStatic<Permissions> permissions;
    private MockedStatic<EvilRpService> evil;
    private MockedStatic<SummaryEditSupport> entries;
    private MockedStatic<StageLoader> stages;
    private MockedStatic<StageEditLock> locks;
    private MockedStatic<PaidChangeService> paid;
    private MockedStatic<ClueInputManager> clues;
    private MockedStatic<TraitLoader> traits;
    private MockedStatic<PlaytimeGate> playtime;
    private MockedStatic<RPTexts> texts;
    private MockedConstruction<InventoryManager> inventories;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class, CreationManager.class);
        CreationManager.activeCreators.clear();
        Cache.attributes = new ArrayList<>();
        Cache.professions = new ArrayList<>();
        plugin = mock(RPCharacters.class);
        when(plugin.getName()).thenReturn("RPCharacters");
        when(plugin.namespace()).thenReturn("rpcharacters");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        RPCharacters.plugin = plugin;
        player = spy(server.addPlayer("Ada"));
        doNothing().when(player).resetTitle();
        data = mock(PlayerData.class);
        character = mock(RPCharacter.class);
        when(character.getId()).thenReturn("draft");
        when(character.getTraits()).thenReturn(List.of());
        when(character.getAttributeData()).thenReturn(new AttributeData());
        when(data.hasActiveCharacter()).thenReturn(true);
        when(data.getActiveCharacter()).thenReturn(character);
        when(data.getCharacters(Status.ALIVE)).thenReturn(List.of(character));
        creation = mock(CharacterCreation.class);
        when(creation.getCharacter()).thenReturn(character);
        when(creation.getTempData()).thenReturn(new AttributeData());
        playerManager = mock(PlayerManager.class);
        staticMock(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(playerManager);
        players = staticMock(PlayerManager.class);
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
        sessions = staticMock(CharacterCreation.class);
        sessions.when(() -> CharacterCreation.forEdit(player, character)).thenReturn(creation);
        slots = staticMock(CharacterSlotService.class);
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(true);
        cooldowns = staticMock(PermissionGroupService.class);
        permissions = staticMock(Permissions.class);
        evil = staticMock(EvilRpService.class);
        entries = staticMock(SummaryEditSupport.class);
        stages = staticMock(StageLoader.class);
        locks = staticMock(StageEditLock.class);
        locks.when(() -> StageEditLock.canEdit(eq(player), any(Stage.class), eq(character))).thenReturn(true);
        paid = staticMock(PaidChangeService.class);
        clues = staticMock(ClueInputManager.class);
        traits = staticMock(TraitLoader.class);
        playtime = staticMock(PlaytimeGate.class);
        playtime.when(() -> PlaytimeGate.canSelectTrait(eq(player), any(Trait.class))).thenReturn(true);
        texts = staticMock(RPTexts.class);
        staticMock(CharacterIngestService.class);
        staticMock(ProstheticTraitRules.class);
        inventories = mockConstruction(InventoryManager.class);
        mocks.add(inventories);
        manager = new CreationManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        state.close();
        MockBukkit.unmock();
    }

    @Test
    void creationAdmissionChecksSlotsCooldownAndEvilRoleplayBeforeOpening() {
        try (var created = mockConstruction(CharacterCreation.class)) {
            slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(false);
            CreationManager.initiateCreation(player);
            assertTrue(created.constructed().isEmpty());
            message("You don't have a free character slot!");
            slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(true);
            cooldowns.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(true);
            CreationManager.initiateCreation(player);
            assertTrue(created.constructed().isEmpty());
            message("You are on cooldown from switching characters");
            cooldowns.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(false);
            evil.when(() -> EvilRpService.blocksCharacterSwitch(player)).thenReturn(true);
            CreationManager.initiateCreation(player);
            evil.verify(() -> EvilRpService.sendSwitchBlocked(player));
            assertTrue(created.constructed().isEmpty());
            permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true);
            CreationManager.initiateCreation(player);
            CharacterCreation opened = created.constructed().getFirst();
            assertSame(opened, CreationManager.activeCreators.get(player));
            verify(opened).setCanNext(false);
        }
    }

    @Test
    void playersWithoutLivingCharactersCanCreateDuringSwitchCooldown() {
        cooldowns.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(true);
        when(data.getCharacters(Status.ALIVE)).thenReturn(List.of());
        try (var created = mockConstruction(CharacterCreation.class)) {
            CreationManager.initiateCreation(player);
            assertSame(created.constructed().getFirst(), CreationManager.activeCreators.get(player));
        }
    }

    @Test
    void startingCreationDoesNotReplaceAnExistingSession() {
        active();
        try (var created = mockConstruction(CharacterCreation.class)) {
            CreationManager.initiateCreation(player);
            assertSame(creation, CreationManager.activeCreators.get(player));
            assertTrue(created.constructed().isEmpty());
        }
    }

    @Test
    void previewAndEditAdmissionPreserveExistingSessionsAndRejectMissingCharacters() {
        CreationManager.initiateStagePreview(null, "info");
        CreationManager.initiateStagePreview(player, "info");
        sessions.verify(() -> CharacterCreation.forStagePreview(player, "info"));
        active();
        CreationManager.initiateStagePreview(player, "info");
        message("You already have an active character session.");
        when(creation.isPreview()).thenReturn(true);
        CreationManager.initiateEdit(player);
        message("You are busy previewing a stage.");
        when(creation.isPreview()).thenReturn(false);
        CreationManager.initiateEdit(player);
        CreationManager.activeCreators.put(player, null);
        CreationManager.initiateEdit(player);
        CreationManager.activeCreators.clear();
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        CreationManager.initiateEdit(player);
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
        when(data.hasActiveCharacter()).thenReturn(false);
        CreationManager.initiateEdit(player);
        message("You have no active character to edit.");
        when(data.hasActiveCharacter()).thenReturn(true);
        CreationManager.initiateEdit(player);
        assertSame(creation, CreationManager.activeCreators.get(player));
        verify(creation).openSummary();
    }

    @Test
    void editEntryRoutesDefaultCluesUnknownLockedAndPaidStages() {
        CreationManager.initiateEditEntry(player, null);
        verify(creation).openSummary();
        CreationManager.activeCreators.clear();
        CreationManager.initiateEditEntry(player, " ");
        verify(creation, times(2)).openSummary();
        CreationManager.activeCreators.clear();
        entries.when(() -> SummaryEditSupport.resolveStageId("clues")).thenReturn("clues");
        when(creation.isEditing()).thenReturn(true);
        CreationManager.initiateEditEntry(player, "clues");
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.EDIT_SUMMARY, creation);
        when(creation.isEditing()).thenReturn(false);
        CreationManager.initiateEditEntry(player, "clues");
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.CREATION_SUMMARY, creation);
        CreationManager.activeCreators.clear();
        when(data.hasActiveCharacter()).thenReturn(false);
        CreationManager.initiateEditEntry(player, "clues");
        assertFalse(CreationManager.activeCreators.containsKey(player));
        CreationManager.initiateEditEntry(player, "unknown");
        message("Unknown edit option.");
        entries.when(() -> SummaryEditSupport.resolveStageId("race")).thenReturn("race_stage");
        CreationManager.initiateEditEntry(player, "race");
        message("You have no active character to edit.");
        when(data.hasActiveCharacter()).thenReturn(true);
        Stage stage = mock(Stage.class);
        stages.when(() -> StageLoader.getById("race_stage")).thenReturn(stage);
        locks.when(() -> StageEditLock.canEdit(player, stage, character)).thenReturn(false);
        CreationManager.initiateEditEntry(player, "race");
        message("That choice is locked and can no longer be edited.");
        paid.when(() -> PaidChangeService.canPayToOpen(stage)).thenReturn(true);
        CreationManager.initiateEditEntry(player, "race");
        verify(creation).jumpToStageForEdit("race_stage");
        assertFalse(CreationManager.activeCreators.containsKey(player), "Failed payment/open must release the empty session");
        when(creation.isEditingFromSummary()).thenReturn(true);
        CreationManager.initiateEditEntry(player, "race");
        assertSame(creation, CreationManager.activeCreators.get(player));
        when(creation.isPreview()).thenReturn(true);
        CreationManager.initiateEditEntry(player, "race");
        message("You are busy previewing a stage.");
        when(creation.isPreview()).thenReturn(false);
        CreationManager.initiateEditEntry(player, "race");
        message("You are busy creating a character.");
        when(creation.isEditing()).thenReturn(true);
        CreationManager.initiateEditEntry(player, "race");
        verify(creation, times(3)).jumpToStageForEdit("race_stage");
    }

    @Test
    void resolvesDraftOrSavedCharactersAndIdentifiesInputStages() {
        assertNull(CreationManager.resolveCharacter(player, "missing"));
        assertFalse(CreationManager.isDraftCharacter(player, "draft"));
        assertFalse(CreationManager.isChatInputStage(player));
        RPCharacter saved = mock(RPCharacter.class);
        when(data.getCharacterById("saved")).thenReturn(saved);
        active();
        assertSame(character, CreationManager.resolveCharacter(player, "draft"));
        assertSame(saved, CreationManager.resolveCharacter(player, "saved"));
        assertTrue(CreationManager.isDraftCharacter(player, "draft"));
        assertFalse(CreationManager.isDraftCharacter(player, "saved"));
        when(creation.isEditing()).thenReturn(true);
        assertFalse(CreationManager.isDraftCharacter(player, "draft"));
        when(creation.isEditing()).thenReturn(false);
        when(creation.isPreview()).thenReturn(true);
        assertFalse(CreationManager.isDraftCharacter(player, "draft"));
        for (Stage stage : List.of(mock(QuestionStage.class), mock(SetterStage.class), mock(ClueStage.class))) {
            when(creation.getActiveStage()).thenReturn(stage);
            assertTrue(CreationManager.isChatInputStage(player));
        }
        when(creation.getActiveStage()).thenReturn(mock(InfoStage.class));
        assertFalse(CreationManager.isChatInputStage(player));
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertNull(CreationManager.resolveCharacter(player, "missing"));
    }

    @Test
    void chatSubmissionsAreQueuedAndDispatchedOnlyForTheCapturedLiveStage() {
        AsyncPlayerChatEvent ignored = chat("normal");
        manager.chatEvent(ignored);
        assertFalse(ignored.isCancelled());
        active();
        clues.when(() -> ClueInputManager.isPending(player)).thenReturn(true);
        manager.chatEvent(ignored);
        assertFalse(ignored.isCancelled());
        clues.when(() -> ClueInputManager.isPending(player)).thenReturn(false);
        QuestionStage question = mock(QuestionStage.class);
        when(creation.getActiveStage()).thenReturn(question);
        AsyncPlayerChatEvent answer = chat("answer");
        manager.chatEvent(answer);
        assertTrue(answer.isCancelled());
        verify(creation, never()).answerQuestion(anyString());
        server.getScheduler().performOneTick();
        verify(creation).answerQuestion("answer");
        SetterStage setter = mock(SetterStage.class);
        when(creation.getActiveStage()).thenReturn(setter);
        manager.chatEvent(chat("Ada"));
        server.getScheduler().performOneTick();
        verify(setter).finish("Ada", player, creation);
        ClueStage clue = mock(ClueStage.class);
        when(creation.getActiveStage()).thenReturn(clue);
        manager.chatEvent(chat("Blue cloak"));
        server.getScheduler().performOneTick();
        verify(clue).finish("Blue cloak", player, creation);
        when(creation.getActiveStage()).thenReturn(mock(InfoStage.class));
        manager.chatEvent(chat("cannot chat"));
        server.getScheduler().performOneTick();
        message("Chat is blocked during character creation.");
        when(creation.getActiveStage()).thenReturn(question);
        manager.chatEvent(chat("old stage"));
        when(creation.getActiveStage()).thenReturn(setter);
        server.getScheduler().performOneTick();
        manager.chatEvent(chat("cancelled"));
        when(creation.isCancelled()).thenReturn(true);
        server.getScheduler().performOneTick();
        when(creation.isCancelled()).thenReturn(false);
        manager.chatEvent(chat("replaced"));
        CreationManager.activeCreators.put(player, mock(CharacterCreation.class));
        server.getScheduler().performOneTick();
        verify(creation, times(1)).answerQuestion(anyString());
        verify(setter, times(1)).finish(anyString(), any(), any());
    }

    @Test
    void nextStopsInfoAndReturnsEditorsToSummaryOtherwiseAdvances() {
        CreationManager.next(player);
        active();
        InfoStage info = mock(InfoStage.class);
        when(creation.getActiveStage()).thenReturn(info);
        CreationManager.next(player);
        verify(info).stopMessages();
        verify(creation).runStage();
        when(creation.isEditingFromSummary()).thenReturn(true);
        CreationManager.next(player);
        verify(creation).returnToSummary();
        verify(creation, times(1)).runStage();
    }

    @Test
    void backCancelsPendingCluesAndReturnsToTheAppropriateSessionView() {
        CreationManager.back(player);
        message("You dont have an active creator");
        active();
        CreationManager.back(player);
        verify(creation).goBack();
        clues.when(() -> ClueInputManager.isPending(player)).thenReturn(true);
        clues.when(() -> ClueInputManager.getPendingCharacterId(player)).thenReturn("draft");
        CreationManager.back(player);
        clues.verify(() -> ClueInputManager.cancel(player));
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.CREATION_SUMMARY, creation);
        when(creation.isEditing()).thenReturn(true);
        CreationManager.back(player);
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.EDIT_SUMMARY, creation);
        clues.when(() -> ClueInputManager.getPendingCharacterId(player)).thenReturn("missing");
        CreationManager.back(player);
        verify(creation).openSummary();
        clues.when(() -> ClueInputManager.getPendingCharacterId(player)).thenReturn(null);
        CreationManager.back(player);
        verify(creation, times(2)).openSummary();
        CreationManager.activeCreators.put(player, null);
        CreationManager.back(player);
        verify(creation, times(2)).openSummary();
    }

    @Test
    void selectionClicksSpendAndRefundPointsWhileUpdatingTheDraftOnly() {
        SelectionStage stage = selection("trait", 3);
        SelectableItem item = option("athletic", 3);
        stage.getOptions().add(item);
        InventoryClickEvent event = click(stage, 0);
        manager.click(player, stage, creation, event);
        assertTrue(item.isSelected());
        assertEquals(List.of(item), stage.getSelection());
        assertEquals(7, stage.getPoints());
        verify(lastInventory()).selectionUpdate(event.getView().getTopInventory(), player, stage, creation);
        manager.click(player, stage, creation, event);
        assertFalse(item.isSelected());
        assertEquals(0, stage.getSelections());
        assertEquals(10, stage.getPoints());
        verify(playerManager, never()).savePlayer(any());
    }

    @Test
    void immediateSelectionsSaveAndReevaluateTheActiveCharacter() {
        SelectionStage stage = selection("trait", 3);
        SelectableItem item = option("athletic", 3);
        stage.getOptions().add(item);
        manager.click(player, stage, null, click(stage, 0));
        assertTrue(item.isSelected());
        verify(character).addTrait(any(Trait.class));
        verify(character).update();
        verify(playerManager).savePlayer(player);
        verify(playerManager).reevaluateFreeze(player);
    }

    @Test
    void selectionRejectsExclusiveDraftAndSavedTraitsWithoutSpendingPoints() {
        SelectionStage stage = selection("trait", 3);
        SelectableItem chosen = option("chosen", 1), candidate = option("candidate", 2);
        chosen.getExclusives().add("candidate");
        stage.select(chosen);
        chosen.setSelected(true);
        stage.getOptions().add(candidate);
        manager.click(player, stage, creation, click(stage, 0));
        assertFalse(candidate.isSelected());
        assertEquals(9, stage.getPoints());
        stage.unSelect(chosen);
        Trait existing = trait("saved", "other", 0);
        when(character.getTraits()).thenReturn(List.of(existing));
        candidate.getExclusives().add("saved");
        manager.click(player, stage, creation, click(stage, 0));
        assertFalse(candidate.isSelected());
        candidate.getExclusives().clear();
        when(existing.getTraitData().isExclusive("candidate")).thenReturn(true);
        manager.click(player, stage, creation, click(stage, 0));
        assertFalse(candidate.isSelected());
        message("You have one or more incompatible traits");
    }

    @Test
    void draftTraitConflictsIgnoreStaleTraitsOfTheSameKeyButRetainOtherKeys() {
        SelectionStage stage = selection("trait", 3);
        SelectableItem item = option("replacement", 1);
        item.getExclusives().add("old");
        stage.getOptions().add(item);
        Trait stale = trait("old", "TRAITS", 0);
        Trait noData = mock(Trait.class);
        when(noData.getId()).thenReturn("no-data");
        Trait noKey = trait("no-key", null, 0);
        Trait noId = trait(null, "other", 0);
        when(character.getTraits()).thenReturn(List.of(stale, noData, noKey, noId));
        SelectableItem unnamed = option(null, 0);
        stage.select(unnamed);
        manager.click(player, stage, creation, click(stage, 0));
        assertTrue(item.isSelected());
        assertEquals(9, stage.getPoints());
    }

    @Test
    void selectionRejectsMaximumCostRequirementsAndPlaytime() {
        SelectionStage stage = selection("trait", 0);
        SelectableItem item = option("expensive", 11);
        stage.getOptions().add(item);
        manager.click(player, stage, creation, click(stage, 0));
        message("Cannot make any more selections");
        stage = selection("trait", 3);
        stage.getOptions().add(item);
        manager.click(player, stage, creation, click(stage, 0));
        message("Cannot afford this trait");
        item = option("dependent", 2);
        item.setDependency(dependency("trait", "all", "prerequisite"));
        stage.getOptions().set(0, item);
        manager.click(player, stage, creation, click(stage, 0));
        message("Lacking requirements");
        assertFalse(item.isSelected());
        SelectableItem prerequisite = option("prerequisite", 0);
        stage.select(prerequisite);
        Trait dependent = TraitLoader.getByString("dependent");
        playtime.when(() -> PlaytimeGate.canSelectTrait(player, dependent)).thenReturn(false);
        playtime.when(() -> PlaytimeGate.denialMessage(player, dependent)).thenReturn("Need two hours");
        manager.click(player, stage, creation, click(stage, 0));
        texts.verify(() -> RPTexts.send(player, "Need two hours"));
        assertFalse(item.isSelected());
        playtime.when(() -> PlaytimeGate.canSelectTrait(player, dependent)).thenReturn(true);
        manager.click(player, stage, creation, click(stage, 0));
        assertTrue(item.isSelected());
    }

    @Test
    void dependencyRemovalUsesDraftAlternativesAndPreservesRequiredTrait() {
        SelectionStage stage = selection("trait", 4);
        SelectableItem base = option("base", 2), dependent = option("dependent", 1), alternative = option("alternative", 0);
        Dependency needsBase = dependency("trait", "one-or-more", "base", "alternative");
        Trait dependentTrait = TraitLoader.getByString("dependent");
        when(dependentTrait.getTraitData().hasDependency()).thenReturn(true);
        when(dependentTrait.getTraitData().getDependency()).thenReturn(needsBase);
        stage.getOptions().add(base);
        stage.select(base); base.setSelected(true);
        stage.select(dependent); dependent.setSelected(true);
        manager.click(player, stage, creation, click(stage, 0));
        assertTrue(base.isSelected());
        assertEquals(7, stage.getPoints());
        texts.verify(() -> RPTexts.send(player, needsBase.toString()));
        stage.select(alternative);
        manager.click(player, stage, creation, click(stage, 0));
        assertFalse(base.isSelected());
        assertEquals(9, stage.getPoints());
    }

    @Test
    void nonDraftDependenciesCheckTheSavedCharacterAndIgnoreMissingTraitMetadata() {
        SelectionStage stage = selection("race", 4);
        SelectableItem base = option("base", 2);
        Dependency requirement = mock(Dependency.class);
        base.setDependency(requirement);
        when(requirement.check(character)).thenReturn(true);
        stage.getOptions().add(base);
        manager.click(player, stage, creation, click(stage, 0));
        verify(requirement).check(character);
        assertTrue(base.isSelected());
        Trait missingData = mock(Trait.class);
        when(missingData.getId()).thenReturn("missing-data");
        traits.when(() -> TraitLoader.getByString("missing-data")).thenReturn(missingData);
        Trait noRequirement = trait("plain", "other", 0);
        Trait nullRequirement = trait("null-requirement", "other", 0);
        when(nullRequirement.getTraitData().hasDependency()).thenReturn(true);
        Trait unrelated = trait("unrelated", "other", 0);
        when(unrelated.getTraitData().hasDependency()).thenReturn(true);
        when(unrelated.getTraitData().getDependency()).thenReturn(dependency("trait", "all", "other"));
        Trait dependent = trait("dependent", "other", 0);
        Dependency dependency = mock(Dependency.class);
        when(dependent.getTraitData().hasDependency()).thenReturn(true);
        when(dependent.getTraitData().getDependency()).thenReturn(dependency);
        when(dependency.getDependencies()).thenReturn(List.of("base"));
        when(dependency.checkExclude(character, "base")).thenReturn(true);
        Trait unresolved = mock(Trait.class);
        when(unresolved.getId()).thenReturn("unresolved");
        when(character.getTraits()).thenReturn(List.of(missingData, noRequirement, nullRequirement, unrelated, dependent, unresolved));
        manager.click(player, stage, creation, click(stage, 0));
        verify(dependency).checkExclude(character, "base");
        assertFalse(base.isSelected());
    }

    @Test
    void classSelectionSwitchesTheSinglePick() {
        SelectionStage stage = selection("unknown", 1);
        SelectableItem old = classOption("warrior"), replacement = classOption("mage");
        stage.select(old); old.setSelected(true);
        stage.getOptions().add(replacement);
        manager.click(player, stage, creation, click(stage, 0));
        assertFalse(old.isSelected());
        assertTrue(replacement.isSelected());
        assertEquals(List.of(replacement), stage.getSelection());
        assertEquals(1, stage.getSelections());
    }

    @Test
    void rejectedClassReplacementKeepsTheExistingSelection() {
        SelectionStage stage = selection("unknown", 1);
        SelectableItem old = classOption("warrior"), replacement = classOption("mage");
        replacement.setDependency(dependency("trait", "all", "required"));
        stage.select(old); old.setSelected(true);
        stage.getOptions().add(replacement);
        manager.click(player, stage, creation, click(stage, 0));
        assertTrue(old.isSelected());
        assertEquals(List.of(old), stage.getSelection());
        assertFalse(replacement.isSelected());
    }

    @Test
    void selectionCloseAndConfirmButtonsRespectEditModes() {
        SelectionStage stage = spy(selection("trait", 3));
        doNothing().when(stage).confirm(player, creation);
        InventoryClickEvent cancel = click(stage, 18);
        manager.click(player, stage, creation, cancel);
        verify(creation).cancel();
        assertTrue(((RPCHolder) cancel.getInventory().getHolder()).isOverridden());
        when(creation.isEditingFromSummary()).thenReturn(true);
        manager.click(player, stage, creation, click(stage, 18));
        verify(creation).returnToSummary();
        when(creation.isEditingFromSummary()).thenReturn(false);
        when(creation.isEditing()).thenReturn(true);
        manager.click(player, stage, creation, click(stage, 18));
        verify(creation, times(2)).returnToSummary();
        manager.click(player, stage, null, click(stage, 18));
        InventoryClickEvent confirm = click(stage, 26);
        manager.click(player, stage, creation, confirm);
        verify(stage).confirm(player, creation);
        assertTrue(((RPCHolder) confirm.getInventory().getHolder()).isOverridden());
    }

    @Test
    void clickGuardsIgnoreAbsentStagesCharactersInventoriesAndUnrelatedHolders() {
        SelectionStage stage = selection("trait", 3);
        InventoryClickEvent event = click(stage, 5);
        manager.click(player, null, creation, event);
        when(creation.getCharacter()).thenReturn(null);
        manager.click(player, stage, creation, event);
        when(creation.getCharacter()).thenReturn(character);
        when(event.getClickedInventory()).thenReturn(null);
        manager.click(player, stage, creation, event);
        Inventory ordinary = Bukkit.createInventory(null, 27);
        when(event.getClickedInventory()).thenReturn(ordinary);
        manager.click(player, stage, creation, event);
        assertTrue(inventories.constructed().isEmpty());
    }

    @Test
    void summaryButtonsConfirmAllModesCancelOpenCluesAndValidateEditorLock() {
        active();
        for (String mode : List.of("create", "edit", "preview")) {
            when(creation.isEditing()).thenReturn(mode.equals("edit"));
            when(creation.isPreview()).thenReturn(mode.equals("preview"));
            manager.selectionClick(summary("confirm"));
        }
        verify(creation).finish(); verify(creation).closeEditSession(); verify(creation).endPreview();
        manager.selectionClick(summary("cancel"));
        verify(creation).cancel();
        manager.selectionClick(summary("clues"));
        manager.selectionClick(summary("edit:race"));
        message("Preview is one stage only. Confirm or cancel to exit.");
        when(creation.isPreview()).thenReturn(false);
        manager.selectionClick(summary("clues"));
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.CREATION_SUMMARY, creation);
        when(creation.isEditing()).thenReturn(true);
        manager.selectionClick(summary("clues"));
        verify(lastInventory()).cluesView(player, character, CreationGuiContext.EDIT_SUMMARY, creation);
        Stage stage = mock(Stage.class);
        stages.when(() -> StageLoader.getById("race")).thenReturn(stage);
        locks.when(() -> StageEditLock.canEdit(player, stage, character)).thenReturn(false);
        manager.selectionClick(summary("edit:race"));
        message("That choice is locked and can no longer be edited.");
        verify(creation, never()).jumpToStageForEdit(anyString());
        paid.when(() -> PaidChangeService.canPayToOpen(stage)).thenReturn(true);
        manager.selectionClick(summary("edit:race"));
        verify(creation).jumpToStageForEdit("race");
        manager.selectionClick(summary("unknown"));
    }

    @Test
    void editingTheClassEntryOpensTheClassPickerInsteadOfThePaidStage() {
        active();
        when(creation.isEditing()).thenReturn(true);
        SelectionStage stage = mock(SelectionStage.class);
        when(stage.getTarget()).thenReturn("class");
        stages.when(() -> StageLoader.getById("class_stage")).thenReturn(stage);
        locks.when(() -> StageEditLock.canEdit(player, stage, character)).thenReturn(true);
        try (var gui = mockStatic(ClassPickGui.class)) {
            manager.selectionClick(summary("edit:class_stage"));
            verify(creation).closeEditSession();
            gui.verify(() -> ClassPickGui.open(player));
            ClassPickService.configure(new ClassPickService.Settings(false, false, java.math.BigDecimal.ZERO, List.of(), java.util.Set.of(), "rpchar.class.reset"));
            manager.selectionClick(summary("edit:class_stage"));
            verify(creation).jumpToStageForEdit("class_stage");
        } finally {
            ClassPickService.configure(ClassPickService.Settings.DEFAULTS);
        }
        assertTrue(CreationManager.isClassStage(stage));
        when(stage.getTarget()).thenReturn("race");
        assertFalse(CreationManager.isClassStage(stage));
        assertFalse(CreationManager.isClassStage(mock(Stage.class)));
        assertFalse(CreationManager.isClassStage(null));
    }

    @Test
    void summaryAndSelectionRoutingIgnoreEmptyBottomAndOrdinaryInventories() {
        InventoryClickEvent summary = summary(null);
        manager.selectionClick(summary);
        verify(summary).setCancelled(true);
        active();
        manager.selectionClick(summary);
        when(summary.getCurrentItem()).thenReturn(new ItemStack(Material.PAPER));
        manager.selectionClick(summary);
        ItemStack noMeta = mock(ItemStack.class);
        when(summary.getCurrentItem()).thenReturn(noMeta);
        manager.selectionClick(summary);
        when(summary.getClickedInventory()).thenReturn(null);
        manager.selectionClick(summary);
        InventoryClickEvent ordinary = click(null, 0);
        Inventory bottom = player.getInventory();
        when(ordinary.getClickedInventory()).thenReturn(bottom);
        manager.selectionClick(ordinary);
        manager.nonCreationClick(player, ordinary);
        Inventory plain = Bukkit.createInventory(null, 27);
        ordinary = event(plain, "Chest", 0);
        manager.nonCreationClick(player, ordinary);
        verify(ordinary, never()).setCancelled(anyBoolean());
        when(creation.getActiveStage()).thenReturn(mock(InfoStage.class));
        manager.selectionClick(ordinary);
        verify(ordinary).setCancelled(true);
        CreationManager.activeCreators.clear();
        SelectionStage stage = selection("trait", 3);
        stage.getOptions().add(option("athletic", 1));
        InventoryClickEvent nonCreation = click(stage, 0);
        manager.selectionClick(nonCreation);
        verify(nonCreation).setCancelled(true);
        assertTrue(stage.getOptions().getFirst().isSelected());
    }

    @Test
    void selectionRoutingInvokesActiveSelectionStage() {
        active();
        SelectionStage stage = selection("trait", 3);
        stage.getOptions().add(option("athletic", 1));
        when(creation.getActiveStage()).thenReturn(stage);
        manager.selectionClick(click(stage, 0));
        assertTrue(stage.getOptions().getFirst().isSelected());
    }

    @Test
    void attributeButtonsUpdateOnlySuccessfulChangesAndRouteNavigation() {
        active();
        AttributesStage stage = mock(AttributesStage.class);
        when(stage.getSize()).thenReturn(27);
        when(creation.getActiveStage()).thenReturn(stage);
        manager.selectionClick(click(stage, 18));
        verify(creation).cancel();
        when(creation.isEditingFromSummary()).thenReturn(true);
        manager.selectionClick(click(stage, 18));
        verify(creation).returnToSummary();
        when(creation.isEditingFromSummary()).thenReturn(false);
        when(creation.isEditing()).thenReturn(true);
        manager.selectionClick(click(stage, 18));
        verify(creation, times(2)).returnToSummary();
        manager.selectionClick(click(stage, 26));
        verify(stage).confirm(player, creation);
        InventoryClickEvent increase = attribute(stage, "strength", "plus");
        manager.selectionClick(increase);
        message("Cannot increase strength.");
        assertTrue(inventories.constructed().isEmpty());
        when(stage.tryIncrease("strength")).thenReturn(true);
        manager.selectionClick(increase);
        verify(lastInventory()).attributesUpdate(increase.getView().getTopInventory(), player, stage, creation);
        InventoryClickEvent decrease = attribute(stage, "strength", "minus");
        manager.selectionClick(decrease);
        message("Cannot decrease strength.");
        when(stage.tryDecrease("strength")).thenReturn(true);
        manager.selectionClick(decrease);
        verify(lastInventory()).attributesUpdate(decrease.getView().getTopInventory(), player, stage, creation);
        int updates = inventories.constructed().size();
        manager.selectionClick(attribute(stage, "strength", "unknown"));
        manager.selectionClick(attribute(stage, null, "plus"));
        manager.selectionClick(attribute(stage, "strength", null));
        manager.selectionClick(click(stage, 0));
        InventoryClickEvent noMeta = click(stage, 0);
        when(noMeta.getCurrentItem()).thenReturn(mock(ItemStack.class));
        manager.selectionClick(noMeta);
        manager.selectionClick(event(Bukkit.createInventory(null, 27), "Chest", 0));
        assertEquals(updates, inventories.constructed().size());
    }

    @Test
    void closedPersistentMenusReopenAfterThreeTicks() {
        SelectionStage selection = mock(SelectionStage.class);
        AttributesStage attributes = mock(AttributesStage.class);
        manager.stopClose(close(new RPCHolder(player, selection)));
        manager.stopClose(close(new RPCHolder(player, attributes)));
        assertTrue(inventories.constructed().isEmpty());
        server.getScheduler().performTicks(3);
        verify(inventories.constructed().get(0)).selectionView(player, selection, null);
        verify(inventories.constructed().get(1)).attributesView(player, attributes, null);
        active();
        when(selection.isActive()).thenReturn(true);
        when(creation.getActiveStage()).thenReturn(selection);
        manager.stopClose(close(new RPCHolder(player, selection)));
        server.getScheduler().performTicks(3);
        verify(lastInventory()).selectionView(player, selection, creation);
        when(attributes.isActive()).thenReturn(true);
        when(creation.getActiveStage()).thenReturn(attributes);
        manager.stopClose(close(new RPCHolder(player, attributes)));
        server.getScheduler().performTicks(3);
        verify(lastInventory()).attributesView(player, attributes, creation);
    }

    @Test
    void closeRespectsOverridesInactiveStagesPreviewAndSummaryLifetime() {
        manager.stopClose(close(null));
        manager.stopClose(close(new RPCHolder(player)));
        manager.stopClose(close(new RPCHolder(player, mock(InfoStage.class))));
        RPCHolder overridden = new RPCHolder(player, mock(SelectionStage.class));
        overridden.override();
        manager.stopClose(close(overridden));
        active();
        when(creation.isPreview()).thenReturn(true);
        manager.stopClose(close(overridden));
        verify(creation, never()).endPreview();
        manager.stopClose(close(new RPCHolder(player, mock(SelectionStage.class))));
        verify(creation).endPreview();
        when(creation.isPreview()).thenReturn(false);
        RPCHolder summary = new RPCHolder(player, mock(SummaryStage.class));
        manager.stopClose(close(summary));
        server.getScheduler().performTicks(3);
        verify(creation).openSummary();
        summary.override();
        manager.stopClose(close(summary));
        manager.stopClose(close(new RPCHolder(player, mock(SummaryStage.class))));
        CreationManager.activeCreators.clear();
        server.getScheduler().performTicks(3);
        verify(creation, times(1)).openSummary();
        active();
        for (Stage stage : List.of(mock(SelectionStage.class), mock(AttributesStage.class), mock(InfoStage.class))) {
            when(creation.getActiveStage()).thenReturn(stage);
            manager.stopClose(close(new RPCHolder(player, stage)));
            if (stage instanceof SelectionStage selection) when(selection.isActive()).thenReturn(true);
            if (stage instanceof AttributesStage attributes) when(attributes.isActive()).thenReturn(true);
            RPCHolder holder = new RPCHolder(player, stage); holder.override();
            manager.stopClose(close(holder));
        }
        server.getScheduler().performTicks(3);
        assertTrue(inventories.constructed().isEmpty());
    }

    @Test
    void delayedReopenDoesNotResurrectACancelledOrReplacedSession() {
        for (boolean attributes : List.of(false, true)) {
            for (String reason : List.of("removed", "replaced", "changed stage", "offline", "cancelled")) {
                active();
                when(player.isOnline()).thenReturn(true);
                when(creation.isCancelled()).thenReturn(false);
                Stage stage;
                if (attributes) {
                    AttributesStage value = mock(AttributesStage.class);
                    when(value.isActive()).thenReturn(true);
                    stage = value;
                } else {
                    SelectionStage value = mock(SelectionStage.class);
                    when(value.isActive()).thenReturn(true);
                    stage = value;
                }
                when(creation.getActiveStage()).thenReturn(stage);
                manager.stopClose(close(new RPCHolder(player, stage)));
                switch (reason) {
                    case "removed" -> CreationManager.activeCreators.remove(player);
                    case "replaced" -> CreationManager.activeCreators.put(player, mock(CharacterCreation.class));
                    case "changed stage" -> when(creation.getActiveStage()).thenReturn(mock(InfoStage.class));
                    case "offline" -> when(player.isOnline()).thenReturn(false);
                    case "cancelled" -> when(creation.isCancelled()).thenReturn(true);
                    default -> throw new AssertionError(reason);
                }
                server.getScheduler().performTicks(3);
                assertTrue(inventories.constructed().isEmpty(), "Old menu must stay closed after " + reason);
            }
        }
    }

    @Test
    void delayedSummaryAndStandaloneMenusRespectSessionChangesAndDisconnects() {
        CharacterCreation replacement = mock(CharacterCreation.class);
        active();
        manager.stopClose(close(new RPCHolder(player, mock(SummaryStage.class))));
        CreationManager.activeCreators.put(player, replacement);
        server.getScheduler().performTicks(3);
        verify(replacement, never()).openSummary();
        active();
        manager.stopClose(close(new RPCHolder(player, mock(SummaryStage.class))));
        when(player.isOnline()).thenReturn(false);
        server.getScheduler().performTicks(3);
        verify(creation, never()).openSummary();
        for (Stage stage : List.of(mock(SelectionStage.class), mock(AttributesStage.class))) {
            when(player.isOnline()).thenReturn(true);
            CreationManager.activeCreators.clear();
            manager.stopClose(close(new RPCHolder(player, stage)));
            active();
            server.getScheduler().performTicks(3);
            assertTrue(inventories.constructed().isEmpty());
            CreationManager.activeCreators.clear();
            manager.stopClose(close(new RPCHolder(player, stage)));
            when(player.isOnline()).thenReturn(false);
            server.getScheduler().performTicks(3);
            assertTrue(inventories.constructed().isEmpty());
        }
    }

    private void active() { CreationManager.activeCreators.put(player, creation); }
    private void message(String message) { texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + message), atLeastOnce()); }
    private InventoryManager lastInventory() { return inventories.constructed().getLast(); }

    private SelectionStage selection(String target, int max) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("target", target); config.set("key", "traits"); config.set("max-select", max);
        config.set("points", 10); config.set("slots", List.of(0, 1, 2, 3));
        return new SelectionStage(new Stage(), config);
    }

    private SelectableItem option(String id, int cost) { return new SelectableItem(trait(id, "traits", cost)); }

    private SelectableItem classOption(String id) {
        var profession = mock(net.Indyuce.mmocore.api.player.profess.PlayerClass.class);
        when(profession.getId()).thenReturn(id); when(profession.getName()).thenReturn(id);
        return new SelectableItem(profession);
    }

    private Trait trait(String id, String key, int cost) {
        Trait trait = mock(Trait.class);
        TraitData data = mock(TraitData.class);
        when(trait.getId()).thenReturn(id); when(trait.getName()).thenReturn(id);
        when(trait.getTraitData()).thenReturn(data);
        when(data.getKey()).thenReturn(key); when(data.getCost()).thenReturn(cost);
        when(data.getAttributeData()).thenReturn(new AttributeData());
        if (id != null) traits.when(() -> TraitLoader.getByString(id)).thenReturn(trait);
        return trait;
    }

    private Dependency dependency(String type, String mode, String... ids) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("type", type); config.set("mode", mode); config.set("depends-on", List.of(ids));
        return new Dependency(config);
    }

    private InventoryClickEvent click(Stage stage, int slot) {
        return event(Bukkit.createInventory(new RPCHolder(player, stage), 27), "Choices", slot);
    }

    private InventoryClickEvent event(Inventory inventory, String title, int slot) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory); when(view.getTitle()).thenReturn(title);
        when(view.getPlayer()).thenReturn(player);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getClickedInventory()).thenReturn(inventory); when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player);
        when(event.getSlot()).thenReturn(slot);
        when(event.getCurrentItem()).thenAnswer(call -> inventory.getItem(slot));
        return event;
    }

    private InventoryClickEvent summary(String action) {
        InventoryClickEvent event = event(Bukkit.createInventory(new RPCHolder(player, mock(SummaryStage.class)), 27), "§7Creation Summary", 0);
        if (action != null) event.getInventory().setItem(0, tagged(Map.of("summary_action", action)));
        return event;
    }

    private InventoryClickEvent attribute(AttributesStage stage, String attribute, String action) {
        InventoryClickEvent event = click(stage, 0);
        java.util.HashMap<String, String> tags = new java.util.HashMap<>();
        if (attribute != null) tags.put("attr_id", attribute);
        if (action != null) tags.put("attr_action", action);
        event.getInventory().setItem(0, tagged(tags));
        return event;
    }

    private ItemStack tagged(Map<String, String> tags) {
        ItemStack item = new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        tags.forEach((key, value) -> meta.getPersistentDataContainer().set(new NamespacedKey(plugin, key), PersistentDataType.STRING, value));
        item.setItemMeta(meta);
        return item;
    }

    private InventoryCloseEvent close(RPCHolder holder) {
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(Bukkit.createInventory(holder, 27));
        return event;
    }

    @SuppressWarnings("deprecation")
    private AsyncPlayerChatEvent chat(String message) { return new AsyncPlayerChatEvent(true, player, message, new HashSet<>()); }

    private <T> MockedStatic<T> staticMock(Class<T> owner) {
        MockedStatic<T> value = mockStatic(owner);
        mocks.add(value);
        return value;
    }
}
