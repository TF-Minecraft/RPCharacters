package net.tfminecraft.rpcharacters.creation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.enums.CharacterSessionMode;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.kit.KitService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.InventoryManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

class CharacterCreationTest {
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private RuntimeTestState state;
    private Player player;
    private PlayerData playerData;
    private PlayerManager playerManager;
    private RPCharacter character;
    private PlayerClass oldClass;
    private List<Stage> stages;
    private MockedStatic<StageLoader> loader;
    private MockedStatic<Stage> stageFactory;
    private MockedStatic<SummaryEditSupport> summarySupport;
    private MockedStatic<StageEditLock> locks;
    private MockedStatic<PaidChangeService> payments;
    private MockedStatic<RPTexts> texts;
    private MockedStatic<CharacterSlotService> slots;
    private MockedStatic<ClassService> classes;
    private MockedConstruction<InventoryManager> inventories;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        state = new RuntimeTestState(CreationManager.class, StageLoader.class);
        CreationManager.activeCreators.clear();
        Cache.attributes = new ArrayList<>();
        Cache.professions = new ArrayList<>();
        player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        playerData = mock(PlayerData.class);
        when(playerData.getUniqueId()).thenReturn(playerId);
        playerManager = mock(PlayerManager.class);
        character = mock(RPCharacter.class);
        configureCharacter(character);
        oldClass = mock(PlayerClass.class);
        when(oldClass.getId()).thenReturn("WARRIOR");
        when(oldClass.getName()).thenReturn("Warrior");
        var mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
        when(mmo.getProfess()).thenReturn(oldClass);
        staticMock(net.Indyuce.mmocore.api.player.PlayerData.class)
            .when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        staticMock(PlayerManager.class).when(() -> PlayerManager.get(player)).thenReturn(playerData);
        staticMock(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(playerManager);
        loader = staticMock(StageLoader.class);
        stages = new ArrayList<>(List.of(stage(InfoStage.class)));
        loader.when(StageLoader::getNew).thenAnswer(invocation -> stages);
        stageFactory = staticMock(Stage.class);
        summarySupport = staticMock(SummaryEditSupport.class);
        locks = staticMock(StageEditLock.class);
        locks.when(() -> StageEditLock.canEdit(eq(player), any(Stage.class), any(RPCharacter.class))).thenReturn(true);
        payments = staticMock(PaidChangeService.class);
        texts = staticMock(RPTexts.class);
        slots = staticMock(CharacterSlotService.class);
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, playerData)).thenReturn(true);
        classes = staticMock(ClassService.class);
        staticMock(ProstheticTraitRules.class);
        staticMock(StageRevisions.class);
        staticMock(CharacterLifecycle.class);
        staticMock(KitService.class);
        staticMock(RosterSyncService.class);
        staticMock(WardrobeService.class);
        inventories = mockConstruction(InventoryManager.class);
        mocks.add(inventories);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        state.close();
        MockBukkit.unmock();
    }

    @Test
    void startsCreationRunsFirstStageAndExposesSessionState() {
        try (var characters = mockConstruction(RPCharacter.class, (created, context) -> configureCharacter(created))) {
            CharacterCreation creation = new CharacterCreation(player);
            assertSame(player, creation.getPlayer());
            assertSame(characters.constructed().getFirst(), creation.getCharacter());
            assertEquals(CharacterSessionMode.CREATING, creation.getSessionMode());
            assertFalse(creation.isEditing());
            assertFalse(creation.isPreview());
            assertFalse(creation.isCancelled());
            assertFalse(creation.canNext());
            creation.setCanNext(true);
            assertTrue(creation.canNext());
            assertSame(stages.getFirst(), creation.getCurrentStage());
            assertSame(stages.getFirst(), creation.getActiveStage());
            assertNull(creation.getEditStage());
            assertFalse(creation.isEditingFromSummary());
            assertEquals("creation_summary_stage", creation.getSummaryStageId());
            assertNotNull(creation.getTempData());
            verify((InfoStage) stages.getFirst()).execute(player, creation);
        }
    }

    @Test
    void editingSeedsAttributeTotalsAndReplacesOnlyItsOwnContribution() {
        AttributeData total = attributes(10);
        when(character.getAttributeData()).thenReturn(total);
        AttributesStage attributes = stage(AttributesStage.class);
        when(attributes.getKey()).thenReturn("TRAITS");
        AttributesStage blank = stage(AttributesStage.class);
        when(blank.getKey()).thenReturn(" ");
        AttributesStage missing = stage(AttributesStage.class);
        Trait matching = trait("traits"), unrelated = trait("race"), noData = mock(Trait.class), noKey = trait(null);
        when(character.getTraits()).thenReturn(List.of(matching, unrelated, noData, noKey));
        stages = new ArrayList<>(List.of(stages.getFirst(), attributes, blank, missing));
        try (var effects = mockStatic(TraitEffectResolver.class)) {
            effects.when(() -> TraitEffectResolver.resolveAttributeData(character, matching)).thenReturn(attributes(3));
            CharacterCreation creation = edit();
            assertEquals(CharacterSessionMode.EDITING, creation.getSessionMode());
            assertTrue(creation.isEditing());
            verify(character).update();
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            creation.setAttributeStageContribution("traits", attributes(5));
            assertEquals(12, amount(creation.getTempData()));
            creation.setAttributeStageContribution("TRAITS", attributes(2));
            assertEquals(9, amount(creation.getTempData()));
            creation.setAttributeStageContribution("new", attributes(4));
            assertEquals(13, amount(creation.getTempData()));
            assertEquals(10, amount(total), "The saved character totals must remain untouched");
        }
    }

    @Test
    void emptyEditSeedsHandleMissingCharacterRaceAndStageList() {
        stages = null;
        CharacterCreation empty = CharacterCreation.forEdit(player, null);
        assertNotNull(empty.getTempData());
        assertNull(empty.getPendingPaidChange());
        stages = List.of(stage(AttributesStage.class));
        when(((AttributesStage) stages.getFirst()).getKey()).thenReturn("traits");
        when(character.getRace()).thenReturn(null);
        when(character.getTraits()).thenReturn(null);
        CharacterCreation withoutRace = edit();
        verify(character, never()).update();
        assertNotNull(withoutRace.getTempData());
        PendingPaidChange held = mock(PendingPaidChange.class);
        when(character.getPendingPaidChange()).thenReturn(held);
        assertSame(held, withoutRace.getPendingPaidChange());
    }

    @Test
    void previewRejectsInvalidUnknownUncopyableAndUnsupportedStages() {
        assertNull(CharacterCreation.forStagePreview(null, "stage"));
        assertNull(CharacterCreation.forStagePreview(player, null));
        assertNull(CharacterCreation.forStagePreview(player, " "));
        assertNull(CharacterCreation.forStagePreview(player, "missing"));
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "Unknown stage id."));
        Stage template = mock(Stage.class);
        loader.when(() -> StageLoader.getById("broken")).thenReturn(template);
        assertNull(CharacterCreation.forStagePreview(player, "broken"));
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "Could not preview that stage."));
        stageFactory.when(() -> Stage.another(template)).thenReturn(mock(Stage.class));
        assertNull(CharacterCreation.forStagePreview(player, "broken"));
        assertFalse(CreationManager.activeCreators.containsKey(player));
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "That stage type cannot be previewed."));
    }

    @Test
    void everySupportedPreviewDispatchesAndExitsWithoutPersisting() {
        for (Class<? extends Stage> type : supportedTypes()) {
            Stage stage = stage(type);
            CharacterCreation preview = preview(stage);
            assertTrue(preview.isPreview());
            assertFalse(preview.isEditing());
            assertTrue(preview.isEditingFromSummary());
            assertSame(stage, preview.getEditStage());
            assertSame(stage, preview.getActiveStage());
            assertSame(preview, CreationManager.activeCreators.get(player));
            verifyExecuted(stage, preview);
            preview.endPreview();
            assertNull(preview.getEditStage());
            assertFalse(preview.isEditingFromSummary());
            assertFalse(CreationManager.activeCreators.containsKey(player));
        }
        verifyNoInteractions(playerManager);
    }

    @Test
    void previewExitRoutesDoNotCloseReplacementSessions() {
        List<Consumer<CharacterCreation>> exits = List.of(CharacterCreation::openSummary,
            CharacterCreation::returnToSummary, CharacterCreation::runStage, CharacterCreation::finish,
            CharacterCreation::goBack, CharacterCreation::cancel);
        for (Consumer<CharacterCreation> exit : exits) {
            CharacterCreation preview = preview(stage(InfoStage.class));
            exit.accept(preview);
            assertFalse(CreationManager.activeCreators.containsKey(player));
        }
        CharacterCreation stale = preview(stage(InfoStage.class));
        CharacterCreation replacement = mock(CharacterCreation.class);
        CreationManager.activeCreators.put(player, replacement);
        clearInvocations(player);
        stale.endPreview();
        assertSame(replacement, CreationManager.activeCreators.get(player));
        verify(player, never()).closeInventory();
        edit().endPreview();
        verify(player, never()).closeInventory();
    }

    @Test
    void summariesUseSessionThenTemplateThenFallbackAndCloseWhenMissing() {
        SummaryStage inSession = stage(SummaryStage.class);
        stages = List.of(stage(InfoStage.class), inSession);
        CharacterCreation creation = edit();
        creation.openSummary();
        verify(lastInventory()).creationSummaryView(player, creation, inSession);
        stages = List.of(stage(InfoStage.class));
        SummaryStage template = stage(SummaryStage.class);
        loader.when(() -> StageLoader.getById("creation_summary_stage")).thenReturn(template);
        creation = edit();
        creation.openSummary();
        verify(lastInventory()).creationSummaryView(player, creation, template);
        loader.when(() -> StageLoader.getById("creation_summary_stage")).thenReturn(mock(Stage.class));
        SummaryStage fallback = stage(SummaryStage.class);
        summarySupport.when(SummaryEditSupport::getSummaryStage).thenReturn(fallback);
        creation = edit();
        creation.openSummary();
        verify(lastInventory()).creationSummaryView(player, creation, fallback);
        summarySupport.when(SummaryEditSupport::getSummaryStage).thenReturn(null);
        creation.openSummary();
        verify(playerManager).savePlayer(player);
        assertFalse(CreationManager.activeCreators.containsKey(player));
        CharacterCreation draft = draft();
        when(draft.getCharacter().hasEnoughClues()).thenReturn(false);
        draft.openSummary();
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "Missing clues."));
    }

    @Test
    void jumpEditHandlesMissingStageHeldRefundLockedPaymentAndCloneFailure() {
        CharacterCreation creation = edit();
        creation.jumpToStageForEdit(null);
        creation.jumpToStageForEdit(" ");
        creation.jumpToStageForEdit("missing");
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "Could not open editor for that choice."));
        Stage template = stage(SelectionStage.class);
        loader.when(() -> StageLoader.getById("choice")).thenReturn(template);
        when(character.getPendingPaidChange()).thenReturn(mock(PendingPaidChange.class));
        creation.jumpToStageForEdit("choice");
        assertFalse(creation.isEditingFromSummary());
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "Your last refund hasn't gone through yet. Try again after you rejoin."));
        when(character.getPendingPaidChange()).thenReturn(null);
        locks.when(() -> StageEditLock.canEdit(player, template, character)).thenReturn(false);
        creation.jumpToStageForEdit("choice");
        payments.verify(() -> PaidChangeService.payToOpen(player, creation, template));
        assertNull(creation.getEditStage());
        payments.when(() -> PaidChangeService.payToOpen(player, creation, template)).thenReturn(true);
        creation.jumpToStageForEdit("choice");
        assertNull(creation.getEditStage());
        payments.verify(() -> PaidChangeService.settle(creation), times(4));
    }

    @Test
    void editableStagesHydrateAndReturnToSummaryButUnsupportedTypeRefunds() {
        SummaryStage summary = stage(SummaryStage.class);
        stages.add(summary);
        CharacterCreation creation = edit();
        for (Class<? extends Stage> type : List.of(SelectionStage.class, AttributesStage.class, SetterStage.class, InfoStage.class)) {
            Stage stage = stage(type);
            loader.when(() -> StageLoader.getById("choice")).thenReturn(stage);
            stageFactory.when(() -> Stage.another(stage)).thenReturn(stage);
            creation.jumpToStageForEdit("choice");
            assertTrue(creation.isEditingFromSummary());
            assertSame(stage, creation.getActiveStage());
            verifyExecuted(stage, creation);
            creation.returnToSummary();
            assertFalse(creation.isEditingFromSummary());
            assertNull(creation.getEditStage());
            verify(lastInventory()).creationSummaryView(player, creation, summary);
        }
        verify(playerManager, times(4)).savePlayer(player);
        Stage unsupported = stage(ClueStage.class);
        loader.when(() -> StageLoader.getById("choice")).thenReturn(unsupported);
        stageFactory.when(() -> Stage.another(unsupported)).thenReturn(unsupported);
        creation.jumpToStageForEdit("choice");
        assertFalse(creation.isEditingFromSummary());
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "That choice cannot be edited from the summary."));
        CharacterCreation draft = draft();
        draft.returnToSummary();
        verify(playerManager, times(4)).savePlayer(player);
    }

    @Test
    void stagesSkipCompletedFailedDependenciesAgeAndWebThenDispatchEveryType() {
        Stage completed = stage(InfoStage.class), unmet = stage(InfoStage.class), young = stage(InfoStage.class), web = stage(InfoStage.class);
        when(completed.shouldRepeat()).thenReturn(false);
        when(playerData.hasCompletedStage(completed)).thenReturn(true);
        Dependency dependency = mock(Dependency.class);
        when(unmet.hasDependency()).thenReturn(true);
        when(unmet.getDependency()).thenReturn(dependency);
        when(young.passesAccountAgeGate(playerData)).thenReturn(false);
        when(web.runsInGame()).thenReturn(false);
        stages = new ArrayList<>(List.of(completed, unmet, young, web));
        List<Stage> executable = supportedTypes().stream().<Stage>map(this::stage).toList();
        stages.addAll(executable);
        when(executable.getFirst().shouldRepeat()).thenReturn(false);
        CharacterCreation creation = draft();
        verifyExecuted(executable.getFirst(), creation);
        verify(playerData).addCompletedStage(executable.getFirst());
        for (int i = 1; i < executable.size(); i++) {
            creation.setCanNext(true);
            creation.runStage();
            assertFalse(creation.canNext());
            assertSame(executable.get(i), creation.getCurrentStage());
            verifyExecuted(executable.get(i), creation);
        }
        verify((InfoStage) completed, never()).execute(any(), any());
        verify((InfoStage) unmet, never()).execute(any(), any());
        verify((InfoStage) young, never()).execute(any(), any());
        verify((InfoStage) web, never()).execute(any(), any());
        creation.runStage();
        verify(lastInventory()).creationSummaryView(player, creation, (SummaryStage) executable.getLast());
    }

    @Test
    void questionAnswersUseTheActiveSummaryEditor() {
        CharacterCreation creation = edit();
        creation.answerQuestion("ignored");
        QuestionStage question = stage(QuestionStage.class);
        stages = List.of(question);
        creation = edit();
        creation.answerQuestion("answer");
        verify(question).checkAnswer("answer", player, creation);
    }

    @Test
    void finishRequiresEveryMandatoryFieldBeforeSaving() {
        CharacterCreation creation = draft();
        RPCharacter draft = creation.getCharacter();
        when(draft.hasEnoughClues()).thenReturn(false);
        creation.finish();
        when(draft.hasEnoughClues()).thenReturn(true);
        when(draft.getName()).thenReturn(null);
        creation.finish();
        when(draft.getName()).thenReturn(" ");
        creation.finish();
        when(draft.getName()).thenReturn("Ada");
        when(draft.hasMMOClass()).thenReturn(false);
        creation.finish();
        when(draft.hasMMOClass()).thenReturn(true);
        when(draft.getRace()).thenReturn(null);
        creation.finish();
        when(draft.getRace()).thenReturn(mock(Race.class));
        when(draft.getBirthday()).thenReturn(null);
        creation.finish();
        when(draft.getBirthday()).thenReturn(" ");
        creation.finish();
        when(draft.getBirthday()).thenReturn("1700-01-01");
        when(draft.getPersonaDescription()).thenReturn(null, " ");
        creation.finish(); creation.finish();
        for (String message : List.of("Missing clues.", "Name not set.", "Class not set.", "Race not set.", "Age not set.", "Description not set.")) {
            texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + message), atLeastOnce());
        }
        verify(playerManager, never()).savePlayer(any());
        verify(playerData, never()).addCharacter(any());
    }

    @Test
    void finishRejectsSlotTakenByWebAndRestoresOldClass() {
        CharacterCreation creation = draft();
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, playerData)).thenReturn(false);
        CreationManager.activeCreators.put(player, creation);
        creation.finish();
        assertTrue(creation.isCancelled());
        assertFalse(CreationManager.activeCreators.containsKey(player));
        classes.verify(() -> ClassService.applyClass(player, "WARRIOR"));
        verify(playerData, never()).addCharacter(any());
        verify(player).closeInventory();
    }

    @Test
    void finishingCreatesActivatesSavesAndRemovesTheSession() {
        CharacterCreation creation = draft();
        RPCharacter draft = creation.getCharacter();
        CreationManager.activeCreators.put(player, creation);
        creation.finish();
        verify(draft).setCreatedAtEpochSeconds(intThat(value -> value > 1_700_000_000));
        verify(draft).update();
        var order = inOrder(playerData, playerManager);
        order.verify(playerData).addCharacter(draft);
        order.verify(playerData).setActiveCharacter(draft);
        order.verify(playerManager).reevaluateFreeze(player);
        order.verify(playerManager).savePlayer(player);
        assertFalse(CreationManager.activeCreators.containsKey(player));
        verify(player).closeInventory();
        CharacterCreation restored = draft();
        RPCharacter restoredDraft = restored.getCharacter();
        when(restoredDraft.getCreatedAtEpochSeconds()).thenReturn(123);
        restored.finish();
        verify(restoredDraft, never()).setCreatedAtEpochSeconds(anyInt());
    }

    @Test
    void editPersistenceAppliesOnlyActiveCharacterClassAndCloses() {
        CharacterCreation creation = edit();
        creation.persistEdits();
        verify(character, never()).applyStoredClass();
        when(character.isActive()).thenReturn(true);
        CreationManager.activeCreators.put(player, creation);
        creation.finish();
        verify(character).applyStoredClass();
        verify(playerManager, times(2)).savePlayer(player);
        verify(playerManager, times(2)).reevaluateFreeze(player);
        assertFalse(CreationManager.activeCreators.containsKey(player));
        payments.verify(() -> PaidChangeService.settle(creation));
    }

    @Test
    void cancellingCreationStopsItsStageAndFutureProgressWhileEditOnlySettles() {
        CharacterCreation creation = draft();
        CreationManager.activeCreators.put(player, creation);
        creation.cancel();
        assertTrue(creation.isCancelled());
        verify(stages.getFirst()).cancel();
        creation.runStage();
        verify((InfoStage) stages.getFirst()).execute(player, creation);
        set(creation, "oldclass", null);
        creation.cancel();
        classes.verify(() -> ClassService.applyClass(player, "WARRIOR"), times(1));
        CharacterCreation editing = edit();
        CreationManager.activeCreators.put(player, editing);
        editing.cancel();
        assertFalse(editing.isCancelled());
        assertFalse(CreationManager.activeCreators.containsKey(player));
        payments.verify(() -> PaidChangeService.settle(editing));
        verify(playerManager, never()).savePlayer(any());
    }

    @Test
    void backSkipsSummaryAndWebStagesAndRehydratesEverySupportedPreviousStage() {
        for (Class<? extends Stage> type : supportedTypes().subList(0, 6)) {
            Stage previous = stage(type);
            Stage web = stage(InfoStage.class);
            when(web.runsInGame()).thenReturn(false);
            stages = new ArrayList<>(List.of(previous, stage(SummaryStage.class), web, stage(InfoStage.class)));
            CharacterCreation creation = draft();
            set(creation, "currentStage", 4);
            clearInvocations(previous);
            creation.setCanNext(true);
            creation.goBack();
            assertFalse(creation.canNext());
            assertSame(previous, creation.getCurrentStage());
            verifyExecuted(previous, creation);
        }
        CharacterCreation creation = draft();
        set(creation, "currentStage", 0);
        creation.goBack();
        texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + "There is no previous stage."));
        set(creation, "currentStage", 100);
        assertSame(stages.getLast(), creation.getCurrentStage());
        creation.goBack();
        assertSame(stages.getFirst(), creation.getCurrentStage());
    }

    @Test
    void backFromEditAndSummaryUsesTheSummaryView() {
        SummaryStage summary = stage(SummaryStage.class);
        stages.add(summary);
        CharacterCreation creation = edit();
        creation.goBack();
        verify(lastInventory()).creationSummaryView(player, creation, summary);
        InfoStage info = stage(InfoStage.class);
        loader.when(() -> StageLoader.getById("info")).thenReturn(info);
        stageFactory.when(() -> Stage.another(info)).thenReturn(info);
        creation.jumpToStageForEdit("info");
        creation.goBack();
        assertFalse(creation.isEditingFromSummary());
        verify(playerManager).savePlayer(player);
    }

    @Test
    void gatedNonRepeatingStageIsNotPermanentlyCompletedBeforeItCanRun() {
        for (String gate : List.of("age", "dependency", "platform")) {
            InfoStage gated = stage(InfoStage.class);
            when(gated.shouldRepeat()).thenReturn(false);
            if (gate.equals("age")) when(gated.passesAccountAgeGate(playerData)).thenReturn(false);
            if (gate.equals("dependency")) {
                when(gated.hasDependency()).thenReturn(true);
                Dependency dependency = mock(Dependency.class);
                when(gated.getDependency()).thenReturn(dependency);
            }
            if (gate.equals("platform")) when(gated.runsInGame()).thenReturn(false);
            stages = new ArrayList<>(List.of(gated, stage(InfoStage.class)));
            draft();
            verify(playerData, never()).addCompletedStage(gated);
            verify(gated, never()).execute(any(), any());
        }
    }

    private CharacterCreation edit() { return CharacterCreation.forEdit(player, character); }

    private CharacterCreation draft() {
        try (var created = mockConstruction(RPCharacter.class, (value, context) -> configureCharacter(value))) {
            return new CharacterCreation(player);
        }
    }

    private CharacterCreation preview(Stage stage) {
        loader.when(() -> StageLoader.getById("preview")).thenReturn(stage);
        stageFactory.when(() -> Stage.another(stage)).thenReturn(stage);
        return CharacterCreation.forStagePreview(player, " preview ");
    }

    private void configureCharacter(RPCharacter value) {
        when(value.getName()).thenReturn("Ada");
        when(value.hasEnoughClues()).thenReturn(true);
        when(value.hasMMOClass()).thenReturn(true);
        when(value.getRace()).thenReturn(mock(Race.class));
        when(value.getBirthday()).thenReturn("1700-01-01");
        when(value.getPersonaDescription()).thenReturn("A travelling scholar");
        when(value.getAttributeData()).thenReturn(attributes(0));
        when(value.getTraits()).thenReturn(List.of());
        when(value.isActive()).thenReturn(false);
    }

    private <T extends Stage> T stage(Class<T> type) {
        T stage = mock(type);
        when(stage.shouldRepeat()).thenReturn(true);
        when(stage.passesAccountAgeGate(any())).thenReturn(true);
        when(stage.runsInGame()).thenReturn(true);
        return stage;
    }

    private List<Class<? extends Stage>> supportedTypes() {
        return List.of(InfoStage.class, QuestionStage.class, SetterStage.class, SelectionStage.class,
            AttributesStage.class, ClueStage.class, SummaryStage.class);
    }

    private void verifyExecuted(Stage stage, CharacterCreation creation) {
        if (stage instanceof InfoStage value) verify(value).execute(player, creation);
        else if (stage instanceof QuestionStage value) verify(value).execute(player, creation);
        else if (stage instanceof SetterStage value) verify(value).execute(player, creation);
        else if (stage instanceof SelectionStage value) verify(value).execute(player, creation);
        else if (stage instanceof AttributesStage value) verify(value).execute(player, creation);
        else if (stage instanceof ClueStage value) verify(value).execute(player, creation);
        else if (stage instanceof SummaryStage value) verify(value).execute(player, creation);
    }

    private Trait trait(String key) {
        Trait trait = mock(Trait.class);
        TraitData data = mock(TraitData.class);
        when(trait.getTraitData()).thenReturn(data);
        when(data.getKey()).thenReturn(key);
        return trait;
    }

    private AttributeData attributes(int strength) {
        AttributeData data = new AttributeData();
        data.clearAll();
        data.addModifier(new AttributeModifier("strength", strength));
        return data;
    }

    private int amount(AttributeData data) { return data.getAmount(new AttributeModifier("strength", 0)); }
    private InventoryManager lastInventory() { return inventories.constructed().getLast(); }

    private <T> MockedStatic<T> staticMock(Class<T> owner) {
        MockedStatic<T> value = mockStatic(owner);
        mocks.add(value);
        return value;
    }

    private static void set(Object object, String name, Object value) {
        try {
            Field field = CharacterCreation.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
