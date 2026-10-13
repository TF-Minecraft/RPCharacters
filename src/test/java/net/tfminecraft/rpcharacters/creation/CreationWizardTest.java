package net.tfminecraft.rpcharacters.creation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import io.papermc.paper.registry.data.dialog.input.*;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.calendar.AgeCalculator;
import net.tfminecraft.rpcharacters.calendar.FantasyCalendar;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.kit.KitService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreAttributeHelper;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.SelectableItem;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.races.RaceData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.utils.PlaytimeGate;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

class CreationWizardTest {

	private final List<AutoCloseable> mocks = new ArrayList<>();
	private RuntimeTestState state;
	private CreationDialogCapture ui;
	private Player player;
	private PlayerData account;
	private PlayerManager players;
	private CharacterCreation creation;
	private Race human, elf;
	private PlayerClass mage;
	private MockedStatic<RPCharacters> pluginStatic;
	private MockedStatic<CharacterSlotService> slots;
	private CreationDialogCapture.CapturedDialog localDialog;
	private final Map<String, String> localValues = new HashMap<>();

	@BeforeEach
	void setup() {
		var server = MockBukkit.mock();
		state = new RuntimeTestState(RPCharacters.class, CreationManager.class, StageLoader.class, RaceLoader.class, TraitLoader.class);
		CreationManager.activeCreators.clear();
		StageLoader.oList = new ArrayList<>();
		RaceLoader.oList = new ArrayList<>();
		TraitLoader.oList = new ArrayList<>();
		Cache.attributes = new ArrayList<>(List.of("strength"));
		Cache.professions = new ArrayList<>();
		Cache.personaDisplayNameMinLength = 2;
		Cache.personaDisplayNameMaxLength = 32;
		Cache.characterDescriptionMinLength = 32;
		Cache.characterDescriptionMaxLength = 256;
		Cache.clueMinLength = 8;
		Cache.clueMaxLength = 100;
		Cache.defaultCluesRequired = 2;
		Cache.maxClues = 6;
		Cache.calendarAgeMinimum = 18;
		player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.isOnline()).thenReturn(true);
		var plugin = mock(RPCharacters.class);
		when(plugin.getServer()).thenReturn(server);
		when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
		RPCharacters.plugin = plugin;
		account = new PlayerData(player);
		players = mock(PlayerManager.class);
		statics(PlayerManager.class).when(() -> PlayerManager.get(player)).thenReturn(account);
		pluginStatic = statics(RPCharacters.class);
		pluginStatic.when(RPCharacters::getPlayerManager).thenReturn(players);
		mage = mock(PlayerClass.class);
		when(mage.getId()).thenReturn("MAGE");
		when(mage.getName()).thenReturn("Mage");
		when(mage.getDescription()).thenReturn(List.of("Uses magic."));
		var mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
		when(mmo.getProfess()).thenReturn(mage);
		statics(net.Indyuce.mmocore.api.player.PlayerData.class).when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
		statics(ClassPickService.class).when(ClassPickService::baseClasses).thenReturn(List.of(mage));
		statics(MmoCoreClassGuiHelper.class).when(() -> MmoCoreClassGuiHelper.buildClassOptions(anyInt(), anyMap()))
				.thenAnswer(call -> new MmoCoreClassGuiHelper.ClassGuiData(List.of(new SelectableItem(mage)), List.of(10)));
		statics(MmoCoreAttributeHelper.class).when(() -> MmoCoreAttributeHelper.exists(anyString())).thenReturn(true);
		statics(PlaytimeGate.class).when(() -> PlaytimeGate.canSelectTrait(eq(player), any())).thenReturn(true);
		statics(Database.class);
		statics(PaidChangeService.class);
		statics(ClassService.class);
		statics(CharacterLifecycle.class);
		statics(CharacterTransitions.class);
		statics(KitService.class);
		statics(RosterSyncService.class);
		statics(WardrobeService.class);
		slots = statics(CharacterSlotService.class);
		slots.when(() -> CharacterSlotService.hasFreeSlot(player, account)).thenReturn(true);
		human = race("human", "Human", 110);
		elf = race("elf", "Elf", 210);
		ui = new CreationDialogCapture();
	}

	private <T> MockedStatic<T> statics(Class<T> type) {
		var mock = mockStatic(type);
		mocks.add(mock);
		return mock;
	}

	@AfterEach
	void teardown() throws Exception {
		ui.close();
		for (var mock : mocks.reversed()) mock.close();
		state.close();
		MockBukkit.unmock();
	}

	private Race race(String id, String name, int maxAge) {
		Race race = mock(Race.class);
		RaceData data = mock(RaceData.class);
		when(race.getId()).thenReturn(id);
		when(race.getName()).thenReturn(name);
		when(race.getDesc()).thenReturn(List.of(name + " lore."));
		when(race.getRaceData()).thenReturn(data);
		when(race.getAgeMax()).thenReturn(maxAge);
		when(data.getKey()).thenReturn("race");
		when(data.getAttributeData()).thenReturn(new AttributeData());
		RaceLoader.oList.add(race);
		return race;
	}

	private Trait trait(String id, String key, int cost) {
		Trait trait = mock(Trait.class);
		TraitData data = mock(TraitData.class);
		when(trait.getId()).thenReturn(id);
		when(trait.getName()).thenReturn(id);
		when(trait.getTraitData()).thenReturn(data);
		when(trait.getDesc()).thenReturn(List.of("About " + id));
		when(data.getKey()).thenReturn(key);
		when(data.getCost()).thenReturn(cost);
		when(data.getAttributeData()).thenReturn(new AttributeData());
		TraitLoader.oList.add(trait);
		return trait;
	}

	private Stage stage(String id, String yaml) throws Exception {
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString(yaml);
		return Stage.create(id, config);
	}

	private void start(Stage... stages) {
		StageLoader.oList = new ArrayList<>(List.of(stages));
		creation = CharacterCreation.startWizard(player);
		CreationManager.activeCreators.put(player, creation);
	}

	private Map<String, String> form() {
		if (localDialog != ui.last()) {
			localDialog = ui.last();
			localValues.clear();
			for (DialogInput input : localDialog.base().inputs()) {
				if (input instanceof TextDialogInput text) localValues.put(input.key(), text.initial());
				else if (input instanceof SingleOptionDialogInput choice) localValues.put(input.key(), choice.entries().stream().filter(SingleOptionDialogInput.OptionEntry::initial).map(SingleOptionDialogInput.OptionEntry::id).findFirst().orElse(""));
				else if (input instanceof BooleanDialogInput bool) localValues.put(input.key(), Boolean.toString(bool.initial()));
				else if (input instanceof NumberRangeDialogInput range) localValues.put(input.key(), Float.toString(range.initial()));
			}
		}
		return localValues;
	}

	private void click(String label, Map<String, String> values) {
		Map<String, String> submitted = new HashMap<>(form());
		submitted.putAll(values);
		ui.click(ui.last().button(label), player, submitted);
	}

	private void choose(String id) {
		SelectionStage stage = (SelectionStage) creation.getActiveStage();
		if (stage.getMaxSelections() == 1) form().put("choice", id);
		else for (int i = 0; i < stage.getOptions().size(); i++) if (stage.getOptions().get(i).getId().equals(id)) form().put("choice_" + i, "true");
	}

	private void click(String label) { click(label, Map.of()); }
	private String optionDetails() {
		return ui.last().base().inputs().stream().flatMap(input -> input instanceof SingleOptionDialogInput choice
				? choice.entries().stream().map(SingleOptionDialogInput.OptionEntry::display)
				: java.util.stream.Stream.of(((BooleanDialogInput) input).label()))
				.map(label -> label.hoverEvent() == null ? "" : CreationDialogCapture.text((net.kyori.adventure.text.Component) label.hoverEvent().value()))
				.reduce("", (a, b) -> a + b);
	}

	private String body() { return ui.last().bodyText(); }
	private String title() { return CreationDialogCapture.text(ui.last().base().title()); }
	private SetterStage setter(String id, String target) throws Exception { return (SetterStage) stage(id, "type: setter\ntarget: " + target); }
	private SummaryStage summary() throws Exception { return (SummaryStage) stage("creation_summary_stage", "type: summary"); }

	@Test
	void createsOneCharacterThroughFieldsChoicesCluesAndReview() throws Exception {
		start(setter("name_stage", "name"), stage("class_stage", "type: selection\ntarget: class\nmin-select: 1\nmax-select: 1"),
				stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"),
				setter("age_stage", "age"), setter("description_stage", "description"), stage("clues_stage", "type: clue"), summary());
		assertEquals("Name", title());
		click("Next", Map.of("value", "aren ashford"));
		choose("MAGE");
		assertTrue(optionDetails().contains("Uses magic."));
		click("Next");
		choose("human");
		choose("elf");
		assertEquals(0, ((SelectionStage) creation.getActiveStage()).getSelections(), "Choices stay client-local until Next");
		assertTrue(optionDetails().contains("Elf lore."));
		click("Next");
		click("Next", Map.of("value", "30"));
		assertNotNull(ui.last().field("value").multiline());
		click("Next", Map.of("value", "A quiet traveller with a faded coat and ink-stained fingers."));
		click("Next", Map.of("clue_0", "A faded green coat", "clue_1", "Ink-stained fingers"));
		assertEquals("Review your character", title());
		assertTrue(body().contains("Aren Ashford"));
		assertTrue(body().contains("Race: Elf"));
		assertTrue(creation.getWizard().validateComplete());
		var create = ui.last().button("Create character");
		ui.click(create, player, Map.of());
		ui.click(create, player, Map.of());
		assertEquals(1, account.getCharacters().size());
		assertFalse(CreationManager.activeCreators.containsKey(player));
		assertEquals("MAGE", creation.getCharacter().getMMOClass());
		verify(players).savePlayer(player);
		verify(player).closeDialog();
		verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
		verify(player, never()).sendMessage(anyString());
	}

	@Test
	void backRetainsTypingAndInvalidInputReturnsToTheSameField() throws Exception {
		start(stage("welcome_stage", "type: info\nmessages: [title(Welcome)]"), setter("name_stage", "name"), setter("description_stage", "description"), summary());
		click("Next");
		click("Back", Map.of("value", "Aren"));
		click("Next");
		assertEquals("Aren", ui.last().field("value").initial());
		click("Next", Map.of("value", "123"));
		assertEquals("Name", title());
		assertEquals("123", ui.last().field("value").initial());
		assertTrue(body().contains("Letters"));
		click("Next", Map.of("value", "Aren"));
		click("Next", Map.of("value", "Short"));
		assertEquals("Short", ui.last().field("value").initial());
		assertTrue(body().contains("at least 32"));
		click("Back", Map.of("value", "Retained description"));
		click("Next", Map.of("value", "Aren"));
		assertEquals("Retained description", ui.last().field("value").initial());
	}

	@Test
	void reviewEditingReturnsToReviewAndBackShowsOnlySavedValues() throws Exception {
		start(setter("name_stage", "name"), stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"), summary());
		click("Next", Map.of("value", "Aren"));
		choose("human"); click("Next");
		click("Edit Race"); choose("elf"); click("Back to review");
		assertTrue(body().contains("Race: Human"));
		click("Edit Race"); click("Save and review");
		assertTrue(body().contains("Race: Elf"));
		click("Edit Name"); click("Save and review", Map.of("value", "Mira"));
		assertTrue(body().contains("Mira"));
		click("Edit Name"); click("Save and review", Map.of("value", "Lena"));
		assertTrue(body().contains("Lena"), "A setter can be submitted more than once during review");
		creation.jumpToStageForEdit("unknown");
		assertTrue(body().contains("not available"));
	}

	@Test
	void callbacksCannotApplyAfterAnotherScreenCancelDisconnectOrSessionReplacement() throws Exception {
		start(setter("name_stage", "name"), summary());
		var next = ui.last().button("Next");
		ui.click(next, mock(Player.class), Map.of("value", "Wrong"));
		assertNull(creation.getCharacter().getName());
		when(player.isOnline()).thenReturn(false);
		ui.click(next, player, Map.of("value", "Wrong"));
		assertNull(creation.getCharacter().getName());
		when(player.isOnline()).thenReturn(true);
		creation.getWizard().resume();
		ui.click(next, player, Map.of("value", "Wrong"));
		assertNull(creation.getCharacter().getName());
		var current = ui.last().button("Next");
		CreationManager.activeCreators.put(player, mock(CharacterCreation.class));
		ui.click(current, player, Map.of("value", "Wrong"));
		assertNull(creation.getCharacter().getName());
		CreationManager.activeCreators.put(player, creation);
		creation.cancel();
		ui.click(current, player, Map.of("value", "Wrong"));
		assertNull(creation.getCharacter().getName());
	}

	@Test
	void cancelConfirmationKeepsTypingOrDiscardsTheDraft() throws Exception {
		start(setter("name_stage", "name"));
		ui.click(((MultiActionType) ui.last().type()).exitAction(), player, Map.of("value", "Aren"));
		click("Keep creating");
		assertEquals("Aren", ui.last().field("value").initial());
		ui.click(((MultiActionType) ui.last().type()).exitAction(), player, Map.of());
		click("Discard draft");
		assertTrue(creation.isCancelled());
		assertFalse(CreationManager.activeCreators.containsKey(player));
	}

	@Test
	void firstScreenBackRemainsUsableAndInstructionsHaveNoTimedTitles() throws Exception {
		start(stage("welcome_stage", "type: info\nmessages: [title(Game welcome), subtitle('/rpcharacter next')]\nweb-messages: [title(Web welcome), subtitle(Read at your own pace)]"), summary());
		assertTrue(body().contains("Web welcome"));
		assertFalse(body().contains("Game welcome"));
		click("Back");
		assertTrue(body().contains("no previous stage"));
		click("Next");
		assertEquals("Review your character", title());
		verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
	}

	@Test
	void realAgeUsesExplicitChoicesAndSkipsPreviouslyAttestedStages() throws Exception {
		var age = setter("real_age_stage", "real_age"); age.setRepeat(false);
		start(age, summary());
		click("Next", Map.of("value", "Yes"));
		assertTrue(account.isEighteen());
		assertTrue(body().contains("18+"));
		click("Edit Real age"); click("Save and review", Map.of("value", "No"));
		assertFalse(account.isEighteen());
		assertTrue(body().contains("Under 18"));
		creation.cancel();
		start(age, summary());
		assertEquals("Review your character", title());
		assertTrue(ui.last().buttons().stream().noneMatch(b -> CreationDialogCapture.text(b.label()).contains("Real age")));
	}

	@Test
	void ageValidationHandlesMissingRaceBoundsAndWholeYears() throws Exception {
		start(setter("age_stage", "age"), summary());
		click("Next", Map.of("value", "abc")); assertTrue(body().contains("Whole number"));
		click("Next", Map.of("value", "0")); assertTrue(body().contains("greater than 0"));
		click("Next", Map.of("value", "30")); assertTrue(body().contains("Pick a race"));
		creation.getCharacter().setRace(human);
		click("Next", Map.of("value", "200")); assertTrue(body().contains("between 18 and 110"));
		click("Next", Map.of("value", "30")); assertTrue(body().contains("Age: 30"));
		click("Edit Age"); assertEquals("30", ui.last().field("value").initial());
	}

	@Test
	void traitBudgetsRetainInvalidChoicesWithoutMutatingTheSavedDraft() throws Exception {
		for (int i = 0; i < 10; i++) trait("gift" + i, "gift", i == 9 ? 3 : 1);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 1\nmax-select: 1\npoints: 2"), summary());
		click("Next"); assertTrue(body().contains("Need at least 1"));
		choose("gift9"); click("Next"); assertTrue(body().contains("Cannot afford"));
		assertEquals(0, ((SelectionStage) creation.getActiveStage()).getSelections());
		choose("gift8"); click("Next");
		assertTrue(body().contains("Gift: gift8"));
		assertEquals(List.of("gift8"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
	}

	@Test
	void pointBuyChecksTheWholeBudgetAndOnlySavesOnNext() throws Exception {
		trait("str1", "attributes", 0);
		start(stage("attributes_stage", "type: attributes\nkey: attributes\npoints: 1\nmax-rank: 2\nattributes: [strength]"), summary());
		click("Next"); assertTrue(body().contains("Spend exactly 1"));
		click("Next", Map.of("rank_strength", "2")); assertTrue(body().contains("3 selected"));
		click("Next", Map.of("rank_strength", "1"));
		assertTrue(body().contains("Strength 1"));
		click("Edit Attributes"); click("Back to review", Map.of("rank_strength", "0"));
		assertTrue(body().contains("Strength 1"));
		click("Edit Attributes"); click("Save and review", Map.of("rank_strength", "1"));
		assertTrue(body().contains("Strength 1"));
	}

	@Test
	void allCluesAreValidatedBeforeReplacingPreviouslySavedClues() throws Exception {
		start(stage("clues_stage", "type: clue"), summary());
		click("Next", Map.of("clue_0", "Same clue", "clue_1", "Same clue"));
		assertTrue(body().contains("Duplicate"));
		assertTrue(creation.getCharacter().getPlayerClues().isEmpty());
		click("Next", Map.of("clue_0", "A green coat", "clue_1", "")); assertTrue(body().contains("at least 2"));
		click("Next", Map.of("clue_0", "A green coat", "clue_1", "Ink-stained fingers"));
		click("Edit Character clues");
		assertEquals("A green coat", ui.last().field("clue_0").initial());
		click("Save and review", Map.of("clue_0", "A brown coat", "clue_1", "Ink-stained fingers"));
		assertTrue(body().contains("A brown coat"));
		assertEquals(2, creation.getCharacter().getPlayerClues().size());
	}

	@Test
	void accountAndRaceGatesCannotBeBypassedWithBackOrReview() throws Exception {
		Stage excluded = setter("web_only_stage", "name"); excluded.setPlatform("web");
		Stage gated = stage("evil_stage", "type: selection\ntarget: trait\nkey: evil\nmin-select: 0\nmax-select: 1\nrequire-account-age-hours-min: 999999");
		start(stage("welcome_stage", "type: info\nmessages: [title(Welcome)]"), excluded, gated, setter("name_stage", "name"), summary());
		click("Next");
		assertEquals("Name", title());
		click("Back"); assertEquals("Welcome", title());
		click("Next"); click("Next", Map.of("value", "Aren"));
		creation.jumpToStageForEdit("evil_stage"); assertTrue(body().contains("not available"));
	}

	@Test
	void changingRaceRemovesUnavailableTraitsAndTheirDraftSelections() throws Exception {
		var gift = trait("human_gift", "gift", 0);
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString("type: race\nmode: one-or-more\ndepends-on: [human]");
		var dependency = new Dependency(config);
		when(gift.getTraitData().hasDependency()).thenReturn(true);
		when(gift.getTraitData().getDependency()).thenReturn(dependency);
		var gifts = stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 1\nmax-select: 1");
		gifts.setDependency(dependency);
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"), gifts, summary());
		choose("human"); click("Next"); choose("human_gift"); click("Next");
		assertEquals(1, creation.getCharacter().getTraits().size());
		click("Edit Race"); choose("elf"); click("Save and review");
		assertTrue(creation.getCharacter().getTraits().isEmpty());
		assertFalse(body().contains("human_gift"));
		var savedGifts = (SelectionStage) creation.getStages().get(1);
		assertEquals(0, savedGifts.getSelections());
	}

	@Test
	void questionsValidateAnswersAndAdvanceWithoutTimers() throws Exception {
		start(stage("questions_stage", "type: questions\namount: 2\nquestions:\n  first:\n    question: First question\n    answers: ['yes']\n  second:\n    question: Second question\n    answers: ['yes']"), summary());
		assertEquals(2, ((QuestionStage) creation.getActiveStage()).getQuestions().size());
		click("Next", Map.of("answer", "wrong"));
		assertTrue(body().contains("Wrong answer"));
		assertEquals("wrong", ui.last().field("answer").initial());
		click("Next", Map.of("answer", "yes"));
		assertEquals("", ui.last().field("answer").initial());
		click("Next", Map.of("answer", "yes"));
		assertTrue(body().contains("Questions: Completed"));
	}

	@Test
	void reviewPagesExposeAllStepsAndRequireTheirConfirmation() throws Exception {
		List<Stage> stages = new ArrayList<>();
		for (int i = 0; i < 9; i++) stages.add(setter("name_" + i, "name"));
		stages.add(summary());
		start(stages.toArray(Stage[]::new));
		creation.openSummary();
		assertTrue(body().contains("Not confirmed"));
		click("Create character");
		assertTrue(body().contains("Review Name"));
		click("More entries"); assertTrue(body().contains("page 2"));
		click("Previous entries"); assertTrue(body().contains("page 1"));
		assertTrue(account.getCharacters().isEmpty());
	}

	@Test
	void resumedDraftsAndHelpReopenTheScreenAndChatCannotSubmitIt() throws Exception {
		start(setter("name_stage", "name"), summary());
		int initial = ui.opened.size();
		CreationManager.initiateCreation(player);
		CreationStageHelp.send(player, creation);
		assertEquals(initial + 2, ui.opened.size());
		assertFalse(CreationManager.isChatInputStage(player));
		var event = new org.bukkit.event.player.AsyncPlayerChatEvent(true, player, "Chat cannot become the name", new java.util.HashSet<>());
		new CreationManager().chatEvent(event);
		assertTrue(event.isCancelled());
		assertNull(creation.getCharacter().getName());
		verify(player, never()).sendMessage(anyString());
	}

	@Test
	void customSetterValuesAndFinalValidationRemainVisible() throws Exception {
		start(setter("custom_stage", "nickname"), summary());
		click("Next", Map.of("value", "Aren"));
		assertTrue(body().contains("Nickname: Aren"));
		assertFalse(creation.getWizard().validateComplete());
		assertTrue(body().contains("Name may only"));
	}

	@Test
	void optionDetailsShowReadableRequirementsConflictsAndAttributeChanges() throws Exception {
		var base = trait("base", "gift", 0);
		var dependent = trait("dependent", "gift", 0);
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString("type: trait\nmode: all\ndepends-on: [base]");
		var dependency = new Dependency(config);
		when(dependent.getTraitData().hasDependency()).thenReturn(true);
		when(dependent.getTraitData().getDependency()).thenReturn(dependency);
		when(base.getTraitData().hasExclusives()).thenReturn(true);
		when(base.getTraitData().getExclusive()).thenReturn(List.of("future_choice"));
		base.getTraitData().getAttributeData().getModifiers().getFirst().add(2);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 0\nmax-select: 2"), summary());
		choose("base");
		assertTrue(optionDetails().contains("Strength: +2"));
		assertTrue(optionDetails().contains("Incompatible with: Future choice"));
		choose("dependent");
		assertTrue(optionDetails().contains("Requires all: base"));
		click("Next");
		click("Edit Gift");
		click("Save and review", Map.of("choice_0", "false", "choice_1", "true"));
		assertTrue(body().contains("Lacking requirements"));
		assertFalse(body().contains("Dependency{"));
	}
	@Test
	void formChoicesStayLocalAndNavigationNeverSharesTheChoiceRow() throws Exception {
		for (int i = 0; i < 5; i++) race("race" + i, "Race " + i, 110);
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"), summary());
		assertFalse(ui.last().base().canCloseWithEscape());
		assertEquals(io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction.NONE, ui.last().base().afterAction());
		assertEquals(List.of("Back", "Next"), ui.last().buttons().stream().map(b -> CreationDialogCapture.text(b.label())).toList());
		int shown = ui.opened.size();
		choose("human"); choose("elf"); choose("human");
		assertEquals(shown, ui.opened.size());
		assertTrue(creation.getCharacter().getRace() == null);
		click("Next"); assertEquals(human, creation.getCharacter().getRace());
	}

	@Test
	void checkboxPagesRetainChoicesAndBoundaryClicksDoNotReplaceTheDialog() throws Exception {
		for (int i = 0; i < 10; i++) trait("gift" + i, "gift", 1);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 1\nmax-select: 2\npoints: 2"), summary());
		int shown = ui.opened.size();
		click("Previous choices"); click("Previous choices");
		assertEquals(shown, ui.opened.size());
		choose("gift0"); click("More choices"); choose("gift8");
		click("Previous choices");
		assertTrue(((BooleanDialogInput) ui.last().base().inputs().getFirst()).initial());
		click("Next");
		assertEquals(List.of("gift0", "gift8"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
	}

	@Test
	void invalidChoiceFormsCannotPartiallyChangeDraftAttributesOrSavedTraits() throws Exception {
		trait("cheap", "gift", 1).getTraitData().getAttributeData().getModifiers().getFirst().add(2);
		trait("expensive", "gift", 3);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 0\nmax-select: 2\npoints: 2"), summary());
		choose("cheap"); choose("expensive"); click("Next");
		assertTrue(body().contains("Cannot afford"));
		assertEquals(0, creation.getTempData().getModifiers().getFirst().getAmount());
		assertEquals(0, ((SelectionStage) creation.getActiveStage()).getSelections());
		click("Next", Map.of("choice_1", "false"));
		assertEquals(List.of("cheap"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
		click("Edit Gift"); click("Save and review", Map.of("choice_0", "false", "choice_1", "false"));
		assertTrue(creation.getCharacter().getTraits().isEmpty());
		assertEquals(0, creation.getTempData().getModifiers().getFirst().getAmount());
	}

	@Test
	void dependenciesInAFormCanPrecedeTheirPrerequisiteAndUnknownIdsAreRejected() throws Exception {
		var dependent = trait("dependent", "gift", 0);
		trait("base", "gift", 0);
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString("type: trait\nmode: all\ndepends-on: [base]");
		when(dependent.getTraitData().hasDependency()).thenReturn(true);
		when(dependent.getTraitData().getDependency()).thenReturn(new Dependency(config));
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 0\nmax-select: 2"), summary());
		choose("dependent"); choose("base"); click("Next");
		assertEquals(2, creation.getCharacter().getTraits().size());
		creation.cancel();
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"));
		click("Next", Map.of("choice", "missing")); assertTrue(body().contains("available option"));
	}

	@Test
	void malformedAttributeRanksStayInlineAndCannotHangOrChangeTheDraft() throws Exception {
		start(stage("attributes_stage", "type: attributes\npoints: 1\nmax-rank: 2\nattributes: [strength]"));
		for (String value : List.of("NaN", "Infinity", "-1", "1.5", "3")) {
			click("Next", Map.of("rank_strength", value));
			assertTrue(body().contains("whole rank"));
			assertEquals(0, ((AttributesStage) creation.getActiveStage()).getRank("strength"));
		}
		int shown = ui.opened.size();
		click("Next", Map.of("rank_strength", "3"));
		assertEquals(shown, ui.opened.size(), "An unchanged error does not replace the dialog");
	}

	@Test
	void paletteCustomGradientsAndPreviewRespectRankLimitsAndPersistOnSave() throws Exception {
		try (var perks = mockStatic(PermissionGroupService.class, CALLS_REAL_METHODS)) {
			perks.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(2);
			start(setter("name_stage", "name"), summary());
			assertEquals(2, ui.last().base().inputs().stream().filter(input -> input.key().startsWith("colour_")).count());
			click("Preview name", Map.of("value", "Aren", "colour_0", "#ffaa00", "colour_1", "#ffffff"));
			assertTrue(body().contains("Aren"));
			assertNull(creation.getCharacter().getNameColour());
			int shown = ui.opened.size();
			click("Preview name"); assertEquals(shown, ui.opened.size());
			click("Next", Map.of("colours", "#zzzzzz")); assertTrue(body().contains("hex colours"));
			click("Next", Map.of("colours", "#ff0000 #00ff00 #0000ff")); assertTrue(body().contains("up to 2"));
			click("Next", Map.of("colours", "#FF0000 00ff00"));
			assertEquals(List.of("#ff0000", "#00ff00"), creation.getCharacter().getNameColour().getHexCodes());
			assertTrue(body().contains("Colours: #ff0000, #00ff00"));
			click("Edit Name"); click("Save and review", Map.of("colours", "", "colour_0", "", "colour_1", ""));
			assertNull(creation.getCharacter().getNameColour());
		}
	}

	@Test
	void coloursCannotBypassDonorPermissionsAndGenderIsOptionalPlainText() throws Exception {
		start(setter("name_stage", "name"), setter("description_stage", "description"), summary());
		assertTrue(body().contains("donor rank"));
		click("Next", Map.of("value", "Aren", "colours", "#ffffff"));
		assertTrue(body().contains("permission"));
		click("Next", Map.of("value", "Aren", "colours", ""));
		String description = "A quiet traveller with a faded coat and ink-stained fingers.";
		click("Next", Map.of("value", description, "gender", "&cBad"));
		assertTrue(body().contains("Gender may contain"));
		click("Next", Map.of("value", description, "gender", "Non-binary"));
		assertEquals("Non-binary", creation.getCharacter().getGender());
		assertTrue(body().contains("Gender: Non-binary"));
		click("Edit Description"); click("Save and review", Map.of("gender", ""));
		assertTrue(body().contains("Gender: Unspecified"));
	}

	@Test
	void discardingACharacterWorldDraftReturnsToTheSelectorWithoutChatOrTitles() throws Exception {
		var bodies = mock(net.tfminecraft.rpcharacters.body.CharacterBodies.class);
		pluginStatic.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
		when(bodies.isSelecting(player)).thenReturn(true);
		start(setter("name_stage", "name"));
		ui.click(((MultiActionType) ui.last().type()).exitAction(), player, Map.of("value", "Aren"));
		assertFalse(ui.last().base().canCloseWithEscape());
		click("Discard draft");
		verify(bodies).requestSelection(player);
		verify(player, never()).sendMessage(anyString());
		verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
	}

}
