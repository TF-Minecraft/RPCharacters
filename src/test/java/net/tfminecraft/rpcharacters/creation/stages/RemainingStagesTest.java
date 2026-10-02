package net.tfminecraft.rpcharacters.creation.stages;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.calendar.*;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.enums.ClueAddResult;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.objects.Question;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class RemainingStagesTest extends StageFixture {
    RuntimeTestState creators;BukkitScheduler scheduler;final List<BukkitTask> tasks=new ArrayList<>();final List<Runnable> timers=new ArrayList<>();final List<Long> delays=new ArrayList<>();final List<String> clues=new ArrayList<>();MockedStatic<RosterSyncService> roster;
    @BeforeEach void setupRemaining(){
        creators=new RuntimeTestState(CreationManager.class);CreationManager.activeCreators.clear();CreationManager.activeCreators.put(player,creation);Cache.calendarYearOffset=0;Cache.calendarAgeMinimum=18;Cache.personaDisplayNameMinLength=3;Cache.personaDisplayNameMaxLength=24;Cache.characterDescriptionMinLength=5;Cache.characterDescriptionMaxLength=100;
        texts.when(() -> RPTexts.formatGui(anyString())).thenAnswer(c->c.getArgument(0));texts.when(() -> RPTexts.formatDisplay(anyString())).thenAnswer(c->c.getArgument(0));roster=staticMock(RosterSyncService.class);when(character.getId()).thenReturn("test-character");when(character.getPlayerClues()).thenReturn(clues);when(character.getCluesNeeded()).thenReturn(2);when(character.hasEnoughClues()).thenAnswer(c->clues.size()>=2);
        scheduler=Bukkit.getScheduler();when(scheduler.runTaskLater(any(Plugin.class),any(Runnable.class),anyLong())).thenAnswer(c->{later.add(c.getArgument(1));delays.add(c.getArgument(2));return newTask();});when(scheduler.runTaskTimer(any(Plugin.class),any(Runnable.class),anyLong(),anyLong())).thenAnswer(c->{timers.add(c.getArgument(1));return newTask();});
    }
    @AfterEach void cleanupRemaining(){creators.close();}
    BukkitTask newTask(){var task=mock(BukkitTask.class);when(task.getTaskId()).thenReturn(tasks.size()+1);tasks.add(task);return task;}
    <T extends Stage>T bind(T stage){when(creation.getActiveStage()).thenReturn(stage);return stage;}
    SetterStage setter(String target){return bind(new SetterStage(base(),config("target",target,"message","chat(Prompt)")));}
    ClueStage clue(){return bind(new ClueStage(base(),config()));}
    QuestionStage questions(int amount){return bind(new QuestionStage(base(),config("amount",amount,"questions.one.question","First?","questions.one.answers",List.of("yes"),"questions.two.question","Second?","questions.two.answers",List.of("no"))));}

    @Test void setterMessagesCopiesAndAlphabeticNamesPreserveTheirContract(){
        var stage=setter("name");var copy=new SetterStage(stage);assertEquals("name",copy.getTarget());assertEquals("chat(Prompt)",copy.getMessage());assertEquals(stage.getId(),copy.getId());stage.runMessage(player,"title(Welcome)");stage.runMessage(player,"subtitle(Detail)");stage.runMessage(player,"chat(Hello)");stage.runMessage(player,"unknown(Ignored)");verify(player).sendTitle("Welcome"," ",5,50,5);verify(player).sendTitle(" ","Detail",5,50,5);verify(player).sendMessage("Hello");verify(player,never()).sendMessage("Ignored");assertFalse(stage.isAlphabetic(null));assertFalse(stage.isAlphabetic("Name2"));assertTrue(stage.isAlphabetic("A Name"));assertNull(stage.capitalizeWords(null));assertEquals("",stage.capitalizeWords(""));assertEquals("A Name",stage.capitalizeWords("  a   NAME "));
        when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);verify(player,never()).sendMessage("Prompt");when(creation.isCancelled()).thenReturn(false);stage.execute(player,creation);verify(player).sendMessage("Prompt");stage.finish("Name2",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Letters only")));stage.finish("Al",player,creation);verify(player).sendMessage(contains("at least 3"));stage.finish("aLICE smith",player,creation);verify(character).modify("name","Alice Smith");assertEquals(List.of(60L),delays);stage.finish("Other Person",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Already saved")));runLater();verify(creation).runStage();stage.execute(player,creation);verify(tasks.getFirst()).cancel();
    }

    @Test void setterNameCapitalizationDoesNotDependOnTurkishHostLocale(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("Arianna",setter("name").capitalizeWords("ARIANNA"));}

    @Test void setterRealAgePersistsOnlyOutsidePreviewAndRejectsUnknownAnswers(){
        var stage=setter("real_age");stage.finish("maybe",player,creation);assertTrue(later.isEmpty());stage.finish("yes",player,creation);verify(playerData).setEighteen(true);verify(manager).savePlayer(player);roster.verify(() -> RosterSyncService.pushRosterForPlayer(player));stage.execute(player,creation);stage.finish("no",player,creation);verify(playerData).setEighteen(false);verify(manager,times(2)).savePlayer(player);
        when(creation.isPreview()).thenReturn(true);stage.execute(player,creation);stage.finish("yes",player,creation);stage.execute(player,creation);stage.finish("no",player,creation);verify(manager,times(2)).savePlayer(player);assertEquals(4,later.size());
    }

    @Test void setterDescriptionAndRaceAgeValidateBeforeWriting(){
        var description=setter("description");description.finish(null,player,creation);verify(player).sendMessage(contains("cannot be empty"));assertTrue(later.isEmpty());description.finish("  §aA traveller  ",player,creation);verify(character).setPersonaDescription("A traveller");assertEquals(1,later.size());later.clear();
        var age=setter("age");for(String invalid:Arrays.asList(null," ","1.5","1,5","abc","999999999999999"))age.finish(invalid,player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Whole number only")),times(6));age.finish("0",player,creation);age.finish("-1",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("greater than 0")),times(2));age.finish("30",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Pick a race first")));var race=race("human","race");when(race.getAgeMax()).thenReturn(100);currentRace.set(race);age.finish("17",player,creation);age.finish("101",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Age must be between")),times(2));age.finish(" 30 ",player,creation);verify(character).setBirthday(AgeCalculator.birthdayFromAge(30,LocalDate.now(),"test-character"));assertEquals(1,later.size());
    }

    @Test void setterDelayedCompletionHonorsSummaryManualAndStaleSessionGuards(){
        var stage=setter("gender");stage.setAutoNext(false);stage.finish("woman",player,creation);runLater();verify(creation).setCanNext(true);stage.execute(player,creation);when(creation.isEditingFromSummary()).thenReturn(true);stage.finish("woman",player,creation);runLater();verify(creation).returnToSummary();
        stage.execute(player,creation);when(creation.isCancelled()).thenReturn(true);stage.finish("woman",player,creation);assertTrue(later.isEmpty());when(creation.isCancelled()).thenReturn(false);when(creation.getActiveStage()).thenReturn(base());stage.finish("woman",player,creation);assertTrue(later.isEmpty());bind(stage);
        stage.finish("woman",player,creation);CreationManager.activeCreators.remove(player);runLater();verify(creation,times(1)).returnToSummary();CreationManager.activeCreators.put(player,creation);stage.execute(player,creation);stage.finish("woman",player,creation);when(creation.getActiveStage()).thenReturn(base());runLater();verify(creation,times(1)).returnToSummary();bind(stage);stage.execute(player,creation);stage.finish("woman",player,creation);when(creation.isCancelled()).thenReturn(true);runLater();verify(creation,times(1)).returnToSummary();
    }

    @Test void cluePromptValidationAndCompletionTrackActualClueCounts(){
        var stage=clue();assertTrue(stage.getMessage().contains("{current}/{needed}"));assertEquals(stage.getMessage(),new ClueStage(stage).getMessage());stage.runMessage(player,"title(Clue)");stage.runMessage(player,"subtitle(Detail)");stage.runMessage(player,"chat(Other)");stage.runMessage(player,"unknown(Ignored)");verify(player).sendTitle("Clue"," ",5,50,5);verify(player).sendTitle(" ","Detail",5,50,5);stage.execute(player,creation);verify(player).sendMessage(contains("1/2"));
        when(character.addPlayerClue("short")).thenReturn(ClueAddResult.TOO_SHORT);when(character.getClueAddErrorMessage(ClueAddResult.TOO_SHORT)).thenReturn("Too short");stage.finish("short",player,creation);verify(player).sendMessage("Too short");when(character.addPlayerClue("first")).thenAnswer(c->{clues.add("first");return ClueAddResult.SUCCESS;});when(character.addPlayerClue("second")).thenAnswer(c->{clues.add("second");return ClueAddResult.SUCCESS;});stage.finish("first",player,creation);assertTrue(later.isEmpty());verify(player).sendMessage(contains("2/2"));stage.finish("second",player,creation);assertEquals(List.of(60L),delays);stage.finish("ignored",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("All clues saved")));verify(character,never()).addPlayerClue("ignored");runLater();verify(creation).runStage();stage.execute(player,creation);verify(tasks.getFirst()).cancel();
    }

    @Test void clueCallbacksIgnoreCancelledReplacedAndDetachedSessions(){
        var stage=clue();when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);stage.finish("ignored",player,creation);assertTrue(tasks.isEmpty());when(creation.isCancelled()).thenReturn(false);when(creation.getActiveStage()).thenReturn(base());stage.finish("ignored",player,creation);verify(character,never()).addPlayerClue(anyString());bind(stage);when(character.addPlayerClue(anyString())).thenReturn(ClueAddResult.SUCCESS);when(character.hasEnoughClues()).thenReturn(true);stage.setAutoNext(false);stage.finish("one",player,creation);runLater();verify(creation).setCanNext(true);
        stage.execute(player,creation);stage.finish("two",player,creation);when(creation.isCancelled()).thenReturn(true);runLater();when(creation.isCancelled()).thenReturn(false);stage.execute(player,creation);stage.finish("three",player,creation);when(creation.getActiveStage()).thenReturn(base());runLater();bind(stage);stage.execute(player,creation);stage.finish("four",player,creation);CreationManager.activeCreators.remove(player);runLater();verify(creation,times(1)).setCanNext(true);
    }

    @Test void questionPickingAnswersAndCompletionUseTheConfiguredPool(){
        var stage=questions(20);assertEquals(20,stage.getAmount());stage.pick();assertEquals(Set.of("First?","Second?"),new HashSet<>(stage.getQuestions().stream().map(Question::getQuestion).toList()));stage.setQuestions(new ArrayList<>(List.of(new Question("First?",List.of("yes")),new Question("Second?",List.of("no")))));stage.setCurrentQuestion(0);stage.execute(player,creation);texts.verify(() -> RPTexts.title(eq(player),contains("Question 1"),eq("First?"),eq(5),eq(60),eq(5)));stage.checkAnswer("wrong",player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Wrong answer")));stage.checkAnswer("YES",player,creation);assertEquals(1,stage.getCurrentQuestion());stage.checkAnswer("no",player,creation);assertEquals(1,stage.getCurrentQuestion());texts.verify(() -> RPTexts.send(eq(player),contains("Answer saved")));assertEquals(List.of(20L),delays);runLater();verify(tasks.getFirst()).cancel();stage.checkAnswer("no",player,creation);runLater();verify(creation).runStage();stage.setAutoNext(false);stage.execute(player,creation);verify(creation).setCanNext(true);stage.checkAnswer("yes",player,creation);assertTrue(later.isEmpty());
        stage.setStored(List.of(new Question("Replacement",List.of("x"))));stage.pick();assertEquals("Replacement",stage.getQuestions().getFirst().getQuestion());var empty=questions(0);empty.pick();empty.checkAnswer("ignored",player,creation);assertEquals(0,empty.getCurrentQuestion());assertTrue(later.isEmpty());
    }

    @Test void questionCallbacksAndInputsRespectCurrentSessionIdentity(){
        var stage=questions(1);stage.pick();stage.setQuestions(List.of(new Question("Question",List.of("yes"))));when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);stage.checkAnswer("yes",player,creation);assertEquals(0,stage.getCurrentQuestion());when(creation.isCancelled()).thenReturn(false);when(creation.getActiveStage()).thenReturn(base());stage.checkAnswer("yes",player,creation);assertEquals(0,stage.getCurrentQuestion());bind(stage);
        stage.checkAnswer("yes",player,creation);CreationManager.activeCreators.remove(player);runLater();verify(creation,never()).runStage();CreationManager.activeCreators.put(player,creation);stage.setCurrentQuestion(0);stage.execute(player,creation);stage.checkAnswer("yes",player,creation);when(creation.getActiveStage()).thenReturn(base());runLater();bind(stage);stage.setCurrentQuestion(0);stage.execute(player,creation);stage.checkAnswer("yes",player,creation);when(creation.isCancelled()).thenReturn(true);runLater();verify(creation,never()).runStage();
    }

    @Test void copiedQuestionStageRetainsConfiguredAmountAndUnpickedQuestionBank(){
        var template=questions(2);var copy=new QuestionStage(template);assertEquals(2,copy.getAmount(),"Copies must retain configured quiz length");copy.pick();assertEquals(2,copy.getQuestions().size(),"Template questions exist before the first pick");assertEquals(0,copy.getCurrentQuestion());assertTrue(template.getQuestions().isEmpty());
    }

    @Test void negativeQuestionAmountMeansNoQuestionsInsteadOfInvalidSublist(){var stage=questions(-1);assertDoesNotThrow(stage::pick);assertTrue(stage.getQuestions().isEmpty());}

    @Test void infoMessagesSubstitutePlaceholdersAndTimedDeliveryStopsCleanly(){
        Cache.evilMinAccountAgeHours=72;var stage=bind(new InfoStage(base(),config("interval",40,"messages",List.of("title(Welcome {hours})","chat(Next)"),"web-messages",List.of("Website"))));assertEquals(40,stage.getInterval());assertEquals(List.of("Website"),stage.getWebMessages());assertTrue(stage.hasWebMessages());var copy=new InfoStage(stage);copy.getWebMessages().clear();assertTrue(stage.hasWebMessages());assertEquals(stage.getMessages(),copy.getMessages());assertEquals("",InfoStage.substitutePlaceholders(null));assertEquals("72",InfoStage.substitutePlaceholders("{hours}"));stage.runMessage(player,"subtitle(Detail)");stage.runMessage(player,"unknown(Ignored)");verify(player).sendTitle(" ","Detail",5,30,5);
        stage.execute(player,creation);Runnable tick=timers.getFirst();tick.run();tick.run();tick.run();verify(player).sendTitle("Welcome 72"," ",5,30,5);verify(player).sendMessage("Next");verify(creation).runStage();verify(scheduler).cancelTask(tasks.getFirst().getTaskId());stage.stopMessages();stage.stopMessages();verify(tasks.getFirst()).cancel();
        stage.setAutoNext(false);stage.execute(player,creation);Runnable manual=timers.getLast();manual.run();manual.run();manual.run();verify(creation,times(3)).setCanNext(true);
    }

    @Test void infoTimerRejectsCancelledStageOrCreationAndReplacedActiveStage(){
        var stage=bind(new InfoStage(base(),config("interval",20,"messages",List.of("chat(Body)"))));assertFalse(stage.hasWebMessages());when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);assertTrue(timers.isEmpty());when(creation.isCancelled()).thenReturn(false);stage.execute(player,creation);stage.cancel();timers.getLast().run();stage.setCancelled(false);stage.execute(player,creation);when(creation.isCancelled()).thenReturn(true);timers.getLast().run();when(creation.isCancelled()).thenReturn(false);stage.execute(player,creation);when(creation.getActiveStage()).thenReturn(base());timers.getLast().run();verify(player,never()).sendMessage("Body");verify(creation,never()).runStage();verify(scheduler,times(3)).cancelTask(anyInt());
    }

    @Test void nestedParenthesesRemainVisibleInEveryMessageStage(){
        var setter=setter("name");var clue=clue();var info=new InfoStage(base(),config("interval",20));
        for(String malformed:Arrays.asList(null,"","Plain text","chat(Unclosed","chat(Text) trailing")){assertDoesNotThrow(()->setter.runMessage(player,malformed));assertDoesNotThrow(()->clue.runMessage(player,malformed));assertDoesNotThrow(()->info.runMessage(player,malformed));}
        verify(player,never()).sendMessage(anyString());setter.runMessage(player,"chat(Name (letters only))");clue.runMessage(player,"chat(Clue (visible detail))");info.runMessage(player,"chat(Info (read this))");verify(player).sendMessage("Name (letters only)");verify(player).sendMessage("Clue (visible detail)");verify(player).sendMessage("Info (read this)");verify(player,times(3)).sendMessage(anyString());
    }

    @Test void summaryAndWardrobeCopyMetadataAndOnlyOpenActiveSummaries(){
        var stage=new SummaryStage(base(),config("entries.name","Name","entries.race","Race"));assertEquals(Map.of("name","Name","race","Race"),stage.getEntries());var copy=new SummaryStage(stage);copy.getEntries().clear();assertEquals(2,stage.getEntries().size());assertTrue(new SummaryStage(base(),config()).getEntries().isEmpty());when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);assertTrue(inventories.constructed().isEmpty());when(creation.isCancelled()).thenReturn(false);stage.execute(player,creation);verify(inventories.constructed().getFirst()).creationSummaryView(player,creation,stage);
        var wardrobe=new WardrobeStage(base(),config("web-messages",List.of("Website"),"messages",List.of("Legacy")));assertTrue(wardrobe.hasWebMessages());assertEquals(List.of("Website"),wardrobe.getWebMessages());var wardrobeCopy=new WardrobeStage(wardrobe);wardrobeCopy.getWebMessages().clear();assertTrue(wardrobe.hasWebMessages());assertEquals(List.of("Legacy"),new WardrobeStage(base(),config("messages",List.of("Legacy"))).getWebMessages());assertFalse(new WardrobeStage(base(),config()).hasWebMessages());
    }
}
