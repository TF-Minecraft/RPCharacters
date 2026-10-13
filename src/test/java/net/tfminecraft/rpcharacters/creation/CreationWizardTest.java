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
	private MockedStatic<PlaytimeGate> playtime;
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
		playtime = statics(PlaytimeGate.class);
		playtime.when(() -> PlaytimeGate.canSelectTrait(eq(player), any())).thenReturn(true);
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
		var button = ui.last().buttons().stream().filter(b -> CreationDialogCapture.text(b.label()).equals(label)).findFirst();
		if (button.isPresent()) ui.click(button.get(), player, submitted);
		else if (((MultiActionType) ui.last().type()).exitAction() != null && CreationDialogCapture.text(((MultiActionType) ui.last().type()).exitAction().label()).equals(label))
			ui.click(((MultiActionType) ui.last().type()).exitAction(), player, submitted);
		else ui.click(ui.last().link(label), player, submitted);
	}

	private void choose(String id) {
		ui.click(optionRow(id), player, Map.of());
	}
	private net.kyori.adventure.text.Component optionRow(String id) {
		SelectionStage stage = (SelectionStage) creation.getActiveStage();
		String name = net.tfminecraft.rpcharacters.utils.ClueFormatter.stripColor(stage.getOptions().stream().filter(o -> o.getId().equals(id)).findFirst().orElseThrow().getName());
		return ui.last().base().body().stream().map(b -> ((io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody) b).contents())
				.filter(c -> CreationDialogCapture.text(c).matches("[☑☐☒] " + java.util.regex.Pattern.quote(name) + "(?: · .*|)"))
				.findFirst().orElseThrow();
	}

	private void click(String label) { click(label, Map.of()); }
	private String optionDetails() {
		return ui.last().base().body().stream().flatMap(b -> CreationDialogCapture.components(((io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody) b).contents()))
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
		click("Edit Age"); assertEquals(30f, ((NumberRangeDialogInput) ui.last().base().inputs().getFirst()).initial());
	}

	@Test
	void traitBudgetsRetainInvalidChoicesWithoutMutatingTheSavedDraft() throws Exception {
		for (int i = 0; i < 10; i++) trait("gift" + i, "gift", i == 9 ? 3 : 1);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 1\nmax-select: 1\npoints: 2"), summary());
		click("Next"); assertTrue(body().contains("Need at least 1"));
		click("More choices");
		assertNull(optionRow("gift9").clickEvent());
		assertTrue(optionDetails().contains("costs 3 points"));
		assertEquals(0, ((SelectionStage) creation.getActiveStage()).getSelections());
		choose("gift8"); click("Next");
		assertTrue(body().contains("Gift: gift8"));
		assertEquals(List.of("gift8"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
	}

	@Test
	void pointBuyChecksTheWholeBudgetAndOnlySavesOnNext() throws Exception {
		trait("str1", "attributes", 0);
		start(stage("attributes_stage", "type: attributes\nkey: attributes\npoints: 1\nmax-rank: 2\nattributes: [strength]"), summary());
		click("Next"); assertTrue(body().contains("1 points remaining"));
		click("Next", Map.of("rank_strength", "2")); assertTrue(body().contains("2 points over budget"));
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
		assertTrue(optionDetails().contains("Strength: 0 → 2 (+2)"));
		choose("base");
		assertTrue(optionDetails().contains("Strength: 2 → 0 (-2)"));
		assertTrue(optionDetails().contains("Incompatible with: Future choice"));
		choose("dependent");
		assertTrue(optionDetails().contains("Requires all: base"));
		click("Next");
		click("Edit Gift");
		choose("base"); click("Save and review");
		assertTrue(body().contains("Lacking requirements"));
		assertFalse(body().contains("Dependency{"));
	}
	@Test
	void directChoicesKeepTheLayoutAndNavigationStableAndIgnoreAnUnchangedPick() throws Exception {
		for (int i = 0; i < 5; i++) race("race" + i, "Race " + i, 110);
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"), summary());
		assertFalse(ui.last().base().canCloseWithEscape());
		assertEquals(io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction.NONE, ui.last().base().afterAction());
		assertEquals(List.of("Back", "Next"), ui.last().buttons().stream().map(b -> CreationDialogCapture.text(b.label())).toList());
		assertTrue(ui.last().base().inputs().isEmpty());
		int shown = ui.opened.size();
		choose("human"); choose("elf"); choose("human");
		assertEquals(shown + 3, ui.opened.size());
		choose("human"); assertEquals(shown + 3, ui.opened.size());
		assertNull(creation.getCharacter().getRace());
		assertTrue(CreationDialogCapture.text(optionRow("human")).startsWith("☑ "));
		click("Next"); assertEquals(human, creation.getCharacter().getRace());
		verify(player, never()).closeInventory();
		verify(player, never()).closeDialog();
	}

	@Test
	void checkboxPagesRetainChoicesAndOnlyOfferAvailablePages() throws Exception {
		for (int i = 0; i < 10; i++) trait("gift" + i, "gift", 1);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 1\nmax-select: 4\npoints: 2"), summary());
		assertThrows(AssertionError.class, () -> ui.last().link("Previous choices"));
		choose("gift0"); click("More choices"); choose("gift8");
		assertThrows(AssertionError.class, () -> ui.last().link("More choices"));
		click("Previous choices");
		assertTrue(CreationDialogCapture.text(optionRow("gift0")).startsWith("☑ "));
		click("Next");
		assertEquals(List.of("gift0", "gift8"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
	}

	@Test
	void unavailableChoicesAreUntickableAndFinalRevalidationCannotPartiallyChangeTheDraft() throws Exception {
		trait("cheap", "gift", 1).getTraitData().getAttributeData().getModifiers().getFirst().add(2);
		trait("expensive", "gift", 3);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 0\nmax-select: 2\npoints: 2"), summary());
		assertNull(optionRow("expensive").clickEvent());
		assertTrue(optionDetails().contains("costs 3 points; 2 remain"));
		choose("cheap");
		var blocker = trait("blocker", "previous", 0);
		when(blocker.getTraitData().hasExclusives()).thenReturn(true);
		when(blocker.getTraitData().getExclusive()).thenReturn(List.of("cheap"));
		when(blocker.getTraitData().isExclusive("cheap")).thenReturn(true);
		creation.getCharacter().addTrait(blocker);
		click("Next"); assertTrue(body().contains("incompatible"));
		assertEquals(0, creation.getTempData().getModifiers().getFirst().getAmount());
		assertEquals(0, ((SelectionStage) creation.getActiveStage()).getSelections());
		creation.getCharacter().removeTrait(blocker);
		click("Next");
		assertEquals(List.of("cheap"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
		click("Edit Gift"); choose("cheap"); click("Save and review");
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
		assertNull(optionRow("dependent").clickEvent());
		assertTrue(optionDetails().contains("requires base"));
		choose("base"); choose("dependent"); click("Next");
		assertEquals(2, creation.getCharacter().getTraits().size());
		creation.cancel();
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"));
		click("Next", Map.of("choice", "missing", "option_human", "true"));
		assertNull(creation.getCharacter().getRace());
		assertTrue(body().contains("Need at least"));
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
	void compactNameAndSeparateColourEditorRespectRankLimitsAndPersistOnlyOnNext() throws Exception {
		try (var perks = mockStatic(PermissionGroupService.class, CALLS_REAL_METHODS)) {
			perks.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(2);
			start(setter("name_stage", "name"), summary());
			assertEquals(1, ui.last().base().inputs().size());
			assertTrue(ui.last().buttons().stream().noneMatch(b -> CreationDialogCapture.text(b.label()).isBlank()));
			click("Edit colours", Map.of("value", "Aren"));
			assertTrue(ui.last().base().inputs().isEmpty());
			click("Gradient"); assertEquals(2, ui.last().base().inputs().size());
			click("Preview", Map.of("colour_0", "#ffaa00", "colour_1", "#ffffff"));
			assertTrue(body().contains("Aren"));
			assertNull(creation.getCharacter().getNameColour());
			int shown = ui.opened.size(); click("Preview"); assertEquals(shown, ui.opened.size());
			click("Use colours");
			assertEquals("Aren", ui.last().field("value").initial());
			click("Edit colours"); click("Custom hex");
			click("Use colours", Map.of("colours", "#zzzzzz")); assertTrue(body().contains("hex colours"));
			click("Preview", Map.of("colours", "#ff0000 #00ff00 #0000ff")); assertTrue(body().contains("up to 2"));
			click("Use colours", Map.of("colours", "#FF0000 00ff00")); click("Next");
			assertEquals(List.of("#ff0000", "#00ff00"), creation.getCharacter().getNameColour().getHexCodes());
			assertFalse(body().contains("Colours:"));
			click("Edit Name"); click("Edit colours"); click("Default colour"); click("Save and review");
			assertNull(creation.getCharacter().getNameColour());
		}
	}

	@Test
	void coloursCannotBypassDonorPermissionsAndGenderIsOptionalPlainText() throws Exception {
		start(setter("name_stage", "name"), setter("description_stage", "description"), summary());
		assertTrue(ui.last().buttons().stream().noneMatch(b -> CreationDialogCapture.text(b.label()).equals("Edit colours")));
		creation.getCharacter().setNameColour(net.tfminecraft.rpcharacters.identity.NameColour.of(List.of("#ffffff")));
		click("Next", Map.of("value", "Aren"));
		assertTrue(body().contains("permission"));
		creation.getCharacter().setNameColour(null);
		click("Next", Map.of("value", "Aren", "colours", "#ffffff"));
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
	void eightColourRanksStillHaveOneNameFieldAndBackRestoresTheOriginalGradient() throws Exception {
		try (var perks = mockStatic(PermissionGroupService.class, CALLS_REAL_METHODS)) {
			perks.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(8);
			start(setter("name_stage", "name"), summary());
			creation.getCharacter().setNameColour(net.tfminecraft.rpcharacters.identity.NameColour.of(List.of("#123456", "#abcdef", "#ffffff")));
			creation.getWizard().resume();
			assertEquals(1, ui.last().base().inputs().size());
			click("Edit colours", Map.of("value", "Aren")); click("Solid colour");
			assertEquals(1, ui.last().base().inputs().size());
			assertTrue(((SingleOptionDialogInput) ui.last().base().inputs().getFirst()).entries().stream().anyMatch(e -> e.id().equals("#123456") && e.initial()));
			click("Preview", Map.of("colour_0", "#ff5555")); click("Back to name");
			assertEquals("Aren", ui.last().field("value").initial());
			click("Edit colours"); click("Custom hex");
			assertEquals("#123456 #abcdef #ffffff", ui.last().field("colours").initial());
			assertTrue(body().contains("Up to 8"));
			click("Change style"); click("Gradient");
			click("Use colours"); click("Next");
			assertEquals(List.of("#123456", "#abcdef"), creation.getCharacter().getNameColour().getHexCodes());
		}
	}

	@Test
	void singleColourRanksHaveNoGradientControlAndExpiredPermissionsCannotBeUsed() throws Exception {
		try (var perks = mockStatic(PermissionGroupService.class, CALLS_REAL_METHODS)) {
			perks.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(1);
			start(setter("name_stage", "name"), summary());
			click("Edit colours", Map.of("value", "Aren"));
			assertThrows(AssertionError.class, () -> ui.last().button("Gradient"));
			click("Solid colour"); click("Use colours");
			click("Edit colours"); click("Custom hex");
			click("Preview", Map.of("colours", "#bad"));
			click("Change style"); click("Solid colour");
			click("Use colours", Map.of("colour_0", ""));
			perks.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(2);
			click("Edit colours"); click("Gradient");
			click("Preview", Map.of("colour_0", "", "colour_1", ""));
			perks.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(0);
			click("Use colours"); assertTrue(body().contains("permission"));
			click("Change style");
			assertEquals(List.of("Default colour"), ui.last().buttons().stream().map(b -> CreationDialogCapture.text(b.label())).toList());
			click("Back to name"); click("Next");
			assertNull(creation.getCharacter().getNameColour());
		}
	}

	@Test
	void reviewPagesHaveOnlyRelevantLinksAndNoEmptyButtonOrHexColourRow() throws Exception {
		List<Stage> stages = new ArrayList<>();
		for (int i = 0; i < 17; i++) stages.add(setter("name_" + i, "name"));
		stages.add(summary()); start(stages.toArray(Stage[]::new)); creation.openSummary();
		assertThrows(AssertionError.class, () -> ui.last().link("Previous entries"));
		click("More entries"); assertTrue(body().contains("page 2 of 3"));
		click("More entries"); assertTrue(body().contains("page 3 of 3"));
		assertThrows(AssertionError.class, () -> ui.last().link("More entries"));
		assertEquals(List.of("Back", "Create character"), ui.last().buttons().stream().map(b -> CreationDialogCapture.text(b.label())).toList());
		click("Previous entries"); assertTrue(body().contains("page 2 of 3"));
		assertFalse(body().contains("Colours:"));
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

	@Test
	void twoPickStepsDisableExtraPicksAndReenableThemWhenOneIsUnticked() throws Exception {
		for (int i = 0; i < 6; i++) trait("gift" + i, "gift", 0);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 2\nmax-select: 2"), summary());
		assertTrue(ui.last().base().inputs().isEmpty());
		var third = optionRow("gift2");
		choose("gift0"); choose("gift1");
		assertNull(optionRow("gift2").clickEvent());
		assertTrue(optionDetails().contains("all 2 picks are filled"));
		ui.click(third, player, Map.of());
		assertTrue(body().contains("2 selected"));
		choose("gift0"); assertNotNull(optionRow("gift2").clickEvent());
		choose("gift2"); click("Next", Map.of("choice_2", "gift3", "option_gift3", "true"));
		assertEquals(List.of("gift1", "gift2"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
	}

	@Test
	void optionalOnePickTraitsCanBeUntickedInTheDraftAndWhenEditingReview() throws Exception {
		trait("arm", "prosthetic", 1);
		start(stage("prosthetic_stage", "type: selection\ntarget: trait\nkey: prosthetic\nmin-select: 0\nmax-select: 1\npoints: 1"), summary());
		choose("arm"); choose("arm"); click("Next");
		assertTrue(creation.getCharacter().getTraits().isEmpty());
		assertTrue(body().contains("Prosthetic: None"));
		click("Edit Prosthetic"); choose("arm"); click("Save and review");
		assertEquals(List.of("arm"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
		click("Edit Prosthetic"); choose("arm"); click("Save and review");
		assertTrue(creation.getCharacter().getTraits().isEmpty());
		assertTrue(body().contains("Prosthetic: None"));
	}

	private void modifier(Trait trait, String attribute, int amount) {
		trait.getTraitData().getAttributeData().addModifier(new net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier(attribute, amount));
	}
	private String hover(String id) {
		return CreationDialogCapture.text((net.kyori.adventure.text.Component) optionRow(id).hoverEvent().value());
	}

	@Test
	void traitHoverShowsRaceAndPendingChoicesWithCompactPositiveAndNegativeResults() throws Exception {
		Cache.attributes = new ArrayList<>(List.of("dexterity", "strength"));
		var boost = trait("boost", "gift", 0); modifier(boost, "DEXTERITY", 1);
		var loss = trait("loss", "gift", 0); modifier(loss, "dexterity", -2);
		trait("zero", "gift", 0);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 0\nmax-select: 2"), summary());
		AttributeData race = new AttributeData(); race.addModifier(new net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier("dexterity", 2));
		when(human.getRaceData().getAttributeData()).thenReturn(race); creation.getCharacter().setRace(human); creation.getWizard().resume();
		assertTrue(hover("boost").contains("Dexterity: 2 → 3 (+1)"));
		assertEquals(1, hover("boost").lines().filter(l -> l.startsWith("Dexterity:")).count());
		assertFalse(hover("boost").contains("Strength:"));
		assertFalse(hover("zero").contains("Dexterity:"));
		choose("boost");
		assertTrue(hover("loss").contains("Dexterity: 3 → 1 (-2)"));
		assertTrue(hover("boost").startsWith("Remove boost"));
		assertTrue(hover("boost").contains("Dexterity: 3 → 2 (-1)"));
		choose("loss"); assertTrue(hover("loss").contains("Dexterity: 1 → 3 (+2)"));
		assertTrue(creation.getCharacter().getTraits().isEmpty());
		assertEquals(0, creation.getTempData().getAmount(new net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier("dexterity", 0)));
	}

	@Test
	void hoverPreviewReplacesSavedSingleChoicesWithoutDoubleCountingOrChangingDraftData() throws Exception {
		Cache.attributes = new ArrayList<>(List.of("dexterity", "strength"));
		var old = trait("old", "gift", 0); modifier(old, "strength", 2);
		var next = trait("next", "gift", 0); modifier(next, "dexterity", 1);
		var background = trait("background", "previous", 0); modifier(background, "strength", 3);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 1\nmax-select: 1"), summary());
		creation.getCharacter().addTrait(old); creation.getCharacter().addTrait(background);
		((SelectionStage) creation.getActiveStage()).hydrateFromCharacter(creation.getCharacter());
		creation.getTempData().addModifier(new net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier("strength", 9));
		creation.getWizard().resume();
		assertTrue(hover("old").contains("Strength: 5")); assertFalse(hover("old").contains("→"));
		assertTrue(hover("next").contains("Strength: 5 → 3 (-2)"));
		assertTrue(hover("next").contains("Dexterity: 0 → 1 (+1)"));
		choose("next"); assertTrue(hover("old").contains("Strength: 3 → 5 (+2)"));
		assertTrue(hover("old").contains("Dexterity: 1 → 0 (-1)"));
		assertEquals(List.of(old, background), creation.getCharacter().getTraits());
		assertEquals(9, creation.getTempData().getAmount(new net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier("strength", 0)));
		assertEquals(0, creation.getCharacter().getCreationBaseAmount("strength"));
	}

	@Test
	void hoverUsesStartingFuelForNewTraitsAndPreservesExistingFuelAndHealingState() throws Exception {
		var arm = trait("arm", "prosthetic", 0);
		when(arm.hasFuelTemplate()).thenReturn(true); when(arm.getFuelCapacity()).thenReturn(10D);
		var powered = mock(net.tfminecraft.rpcharacters.objects.trait.TraitVariant.class);
		var depowered = mock(net.tfminecraft.rpcharacters.objects.trait.TraitVariant.class);
		AttributeData gain = new AttributeData(), loss = new AttributeData();
		gain.getModifiers().getFirst().add(2); loss.getModifiers().getFirst().add(-1);
		when(powered.getAttributeData()).thenReturn(gain); when(depowered.getAttributeData()).thenReturn(loss);
		when(powered.getDescription()).thenReturn(List.of("Powered arm")); when(depowered.getDescription()).thenReturn(List.of("Empty arm"));
		when(arm.getPoweredVariant()).thenReturn(powered); when(arm.getDepoweredVariant()).thenReturn(depowered);
		var healing = trait("healing", "previous", 0); modifier(healing, "strength", 2);
		when(healing.hasDuration()).thenReturn(true); when(healing.getDurationMs()).thenReturn(1_000_000L);
		start(stage("prosthetic_stage", "type: selection\ntarget: trait\nkey: prosthetic\nmin-select: 0\nmax-select: 1"), summary());
		creation.getCharacter().addTrait(healing);
		long expiry = System.currentTimeMillis() + 500_000L;
		creation.getCharacter().setDurationExpiresAtMs("healing", expiry); creation.getWizard().resume();
		assertTrue(hover("arm").contains("Strength: 1 → 3 (+2)")); assertTrue(hover("arm").contains("Powered arm"));
		assertNull(creation.getCharacter().getTraitState("arm"));
		choose("arm"); assertTrue(hover("arm").contains("Strength: 3 → 1 (-2)"));
		assertNull(creation.getCharacter().getTraitState("arm"));
		click("Next"); creation.getCharacter().setFuel("arm", 0D);
		click("Edit Prosthetic");
		assertTrue(hover("arm").contains("Strength: 0 → 1 (+1)")); assertTrue(hover("arm").contains("Empty arm"));
		assertEquals(0D, creation.getCharacter().getFuel("arm"));
		assertEquals(expiry, creation.getCharacter().getTraitState("healing").getExpiresAtMs());
		assertEquals(List.of(healing, arm), creation.getCharacter().getTraits());
	}

	@Test
	void raceHoverKeepsItsExistingAttributeModifierDescription() throws Exception {
		AttributeData race = new AttributeData(); race.getModifiers().getFirst().add(2);
		when(human.getRaceData().getAttributeData()).thenReturn(race);
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"));
		assertTrue(hover("human").contains("Strength: +2"));
	}

	@Test
	void disabledOptionsStayVisibleWithExactConflictAndPlaytimeReasonsAndCannotBeForged() throws Exception {
		var existing = trait("existing", "previous", 0);
		trait("allowed", "virtue", 0);
		var blocked = trait("blocked", "virtue", 0);
		when(blocked.getTraitData().hasExclusives()).thenReturn(true);
		when(blocked.getTraitData().getExclusive()).thenReturn(List.of("existing"));
		var gated = trait("gated", "virtue", 0);
		playtime.when(() -> PlaytimeGate.canSelectTrait(player, gated)).thenReturn(false);
		playtime.when(() -> PlaytimeGate.denialMessage(player, gated)).thenReturn("Need 2h playtime. 1h left.");
		start(stage("virtue_stage", "type: selection\ntarget: trait\nkey: virtue\nmin-select: 1\nmax-select: 1"), summary());
		creation.getCharacter().addTrait(existing); creation.getWizard().resume();
		assertNull(optionRow("blocked").clickEvent());
		assertNull(optionRow("gated").clickEvent());
		assertTrue(optionDetails().contains("Disabled because it conflicts with existing."));
		assertTrue(optionDetails().contains("Need 2h playtime. 1h left."));
		click("Next", Map.of("option_blocked", "true"));
		assertEquals(List.of("existing"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
		choose("allowed"); click("Next");
		assertEquals(List.of("existing", "allowed"), creation.getCharacter().getTraits().stream().map(Trait::getId).toList());
	}

	@Test
	void nextAndBackNeverCloseTheInventoryWhileTheDialogWizardIsOpen() throws Exception {
		trait("str1", "attributes", 0);
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"),
				stage("attributes_stage", "type: attributes\nkey: attributes\npoints: 1\nmax-rank: 2\nattributes: [strength]"), summary());
		choose("human"); click("Next");
		click("Next", Map.of("rank_strength", "1"));
		click("Back"); click("Back", Map.of("rank_strength", "1"));
		verify(player, never()).closeInventory();
		verify(player, never()).closeDialog();
	}

	@Test
	void liveChoiceCallbacksRecheckTheReasonAndMissingTraitsStayDisabled() throws Exception {
		var gift = trait("gift", "gift", 0);
		start(stage("gift_stage", "type: selection\ntarget: trait\nkey: gift\nmin-select: 0\nmax-select: 2"), summary());
		var earlier = optionRow("gift");
		playtime.when(() -> PlaytimeGate.canSelectTrait(player, gift)).thenReturn(false);
		playtime.when(() -> PlaytimeGate.denialMessage(player, gift)).thenReturn("Need 2h playtime. 1h left.");
		ui.click(earlier, player, Map.of());
		assertTrue(body().contains("Disabled because Need 2h playtime. 1h left."));
		assertNull(optionRow("gift").clickEvent());
		TraitLoader.oList.clear(); creation.getWizard().resume();
		assertNull(optionRow("gift").clickEvent());
		assertTrue(optionDetails().contains("Disabled because this trait is unavailable."));
		assertEquals(0, ((SelectionStage) creation.getActiveStage()).getSelections());
	}

	@Test
	void ageUsesABoundedWholeYearSliderAndCluesHaveNoOverlappingCounter() throws Exception {
		start(stage("race_stage", "type: selection\ntarget: race\nkey: race\nmin-select: 1\nmax-select: 1"), setter("age_stage", "age"), stage("clues_stage", "type: clue"), summary());
		choose("human"); click("Next");
		assertInstanceOf(NumberRangeDialogInput.class, ui.last().base().inputs().getFirst());
		click("Next", Map.of("value", "30.5")); assertTrue(body().contains("Whole number"));
		click("Next", Map.of("value", "30"));
		assertEquals("30", AgeCalculator.formatAge(creation.getCharacter().getBirthday()));
		assertTrue(ui.last().base().inputs().stream().allMatch(input -> ((TextDialogInput)input).multiline() == null));
	}

	@Test
	void attributeControlsShowCostsHideUnaffordableRanksAndBalanceWithoutCommitting() throws Exception {
		Cache.attributes = new ArrayList<>(List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"));
		for (String attr : Cache.attributes) for (int rank = 1; rank <= 2; rank++) trait(AttributesStage.traitId(attr, rank), "attributes", 0);
		start(stage("attributes_stage", "type: attributes\nkey: attributes\npoints: 12\nmax-rank: 4\nattributes: [strength, dexterity, constitution, intelligence, wisdom, charisma]"), summary());
		var input = (SingleOptionDialogInput) ui.last().base().inputs().getFirst();
		assertEquals(List.of("0", "1", "2", "3"), input.entries().stream().map(SingleOptionDialogInput.OptionEntry::id).toList());
		assertTrue(CreationDialogCapture.text(input.entries().get(2).display()).contains("3 points"));
		click("Check points"); assertTrue(body().contains("12 points remaining"));
		click("Balanced");
		assertEquals(0, ((AttributesStage)creation.getActiveStage()).spentPoints());
		click("Check points"); assertTrue(body().contains("All points assigned"));
		assertTrue(creation.getCharacter().getTraits().isEmpty());
		click("Next");
		assertEquals(9, creation.getCharacter().getTraits().size());
		verify(player, never()).closeInventory();
	}

	@Test
	void impossibleBalancedBudgetsAndUnknownRankValuesStayInline() throws Exception {
		start(stage("attributes_stage", "type: attributes\npoints: 2\nmax-rank: 1\nattributes: [strength]"));
		click("Balanced"); assertTrue(body().contains("cannot fit"));
		click("Next", Map.of("rank_strength", "unknown")); assertTrue(body().contains("whole rank"));
	}

}
