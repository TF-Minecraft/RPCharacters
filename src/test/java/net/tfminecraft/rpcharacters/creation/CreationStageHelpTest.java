package net.tfminecraft.rpcharacters.creation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.enums.StageType;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreAttributeHelper;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.utils.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class CreationStageHelpTest {
    RuntimeTestState state;Player player;CharacterCreation creation;RPCharacter character;MockedStatic<RPTexts> texts;MockedStatic<MmoCoreAttributeHelper> attributes;final List<String> messages=new ArrayList<>();
    @BeforeEach void setup(){MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,RaceLoader.class,TraitLoader.class);Cache.attributes=new ArrayList<>(List.of("strength"));Cache.professions=new ArrayList<>();Cache.clueMinLength=5;Cache.clueMaxLength=80;Cache.evilMinAccountAgeHours=72;RaceLoader.oList=new ArrayList<>();TraitLoader.oList=new ArrayList<>();RPCharacters.plugin=mock(RPCharacters.class);when(RPCharacters.plugin.getLogger()).thenReturn(mock(Logger.class));player=mock(Player.class);creation=mock(CharacterCreation.class);character=mock(RPCharacter.class);when(creation.getCharacter()).thenReturn(character);when(character.getPlayerClues()).thenReturn(List.of("Existing"));when(character.getCluesNeeded()).thenReturn(3);doAnswer(c->{messages.add(clean(c.getArgument(0)));return null;}).when(player).sendMessage(anyString());texts=mockStatic(RPTexts.class);texts.when(() -> RPTexts.formatGui(anyString())).thenAnswer(c->clean(c.getArgument(0)));texts.when(RPTexts::separator).thenReturn("---");texts.when(() -> RPTexts.send(eq(player),anyString())).thenAnswer(c->{messages.add(clean(c.getArgument(1)));return null;});attributes=mockStatic(MmoCoreAttributeHelper.class);attributes.when(() -> MmoCoreAttributeHelper.exists(anyString())).thenReturn(true);}
    @AfterEach void cleanup(){verify(player,never()).closeInventory();verify(player,never()).openInventory(any(Inventory.class));verify(creation,never()).runStage();verify(creation,never()).returnToSummary();verify(creation,never()).setCanNext(anyBoolean());attributes.close();texts.close();state.close();MockBukkit.unmock();}
    static String clean(String value){return value == null ? "" : org.bukkit.ChatColor.stripColor(org.bukkit.ChatColor.translateAlternateColorCodes('&', value));}
    String output(){return String.join("\n",messages);}
    void help(Stage stage){when(creation.getActiveStage()).thenReturn(stage);messages.clear();CreationStageHelp.send(player,creation);}
    Stage base(String id){var s=new Stage();s.setId(id);return s;}
    YamlConfiguration config(Object...pairs){var c=new YamlConfiguration();for(int i=0;i<pairs.length;i+=2)c.set((String)pairs[i],pairs[i+1]);return c;}

    @Test void missingAndUnknownStagesReturnUsefulReadOnlyFallbacks(){
        help(null);assertEquals(List.of("There is nothing to recap right now."),messages);help(base(null));assertTrue(output().contains("Stage: Character creation"));assertTrue(output().contains("No extra information"));assertTrue(output().contains("/rpcharacter back"));assertTrue(output().contains("to start over"));help(base(" "));assertTrue(output().contains("Stage: Character creation"));help(base("CUSTOM_STEP"));assertTrue(output().contains("Stage: Custom Step"));
    }

    @Test void infoRecapUsesFirstVisibleTitleAndPreservesNestedCopy(){
        var info=mock(InfoStage.class);when(info.getId()).thenReturn("intro_stage");when(info.getMessages()).thenReturn(Arrays.asList(null," ","chat(Details)","title(§a)","TITLE(§bVisible title)","chat(Wait {hours} hours (three days))","Plain copy","chat(Unclosed"));help(info);assertTrue(output().contains("Stage: Visible title"));assertTrue(output().contains("Wait 72 hours (three days)"));assertTrue(output().contains("- Plain copy"));assertTrue(output().contains("- chat(Unclosed"));verify(info,never()).execute(any(),any());
        when(info.getMessages()).thenReturn(List.of("chat(Only body)"));help(info);assertTrue(output().contains("Stage: Intro"));
    }

    @Test void settersDescribeTheirTargetAndHandleBlankOrMalformedMessages(){
        var setter=mock(SetterStage.class);when(setter.getTarget()).thenReturn("real_age");when(setter.getMessage()).thenReturn("subtitle(Answer yes (18+) or no)");help(setter);assertTrue(output().contains("Stage: Real Age"));assertTrue(output().contains("Answer yes (18+) or no"));
        when(setter.getTarget()).thenReturn(null);when(setter.getId()).thenReturn("NAME_STAGE");when(setter.getMessage()).thenReturn(null);help(setter);assertTrue(output().contains("Stage: Name"));assertTrue(output().contains("No extra information"));when(setter.getTarget()).thenReturn(" ");when(setter.getMessage()).thenReturn("Plain prompt");help(setter);assertTrue(output().contains("Plain prompt"));
    }

    @Test void clueRecapUsesCurrentCountsOrSafeMissingCharacterFallback(){
        var stage=new ClueStage(base("clue_stage"),config("message","chat(Type clue {current}/{needed})"));help(stage);assertTrue(output().contains("Type clue 2/3"));assertTrue(output().contains("5 to 80 characters"));when(creation.getCharacter()).thenReturn(null);help(stage);assertTrue(output().contains("Type clue {current}/{needed}"));
    }

    @Test void quizRecapOnlyRevealsCurrentQuestionAndValidControls(){
        var stage=mock(QuestionStage.class);when(stage.getId()).thenReturn("questions_stage");when(stage.getQuestions()).thenReturn(List.of(new Question("Current question",List.of("secret answer")),new Question("Next question",List.of("hidden"))));when(stage.getCurrentQuestion()).thenReturn(0);when(creation.canNext()).thenReturn(true);help(stage);assertTrue(output().contains("Question 1 of 2"));assertTrue(output().contains("Current question"));assertFalse(output().contains("secret answer"));assertFalse(output().contains("Next question"));assertTrue(output().contains("/rpcharacter next"));when(stage.getCurrentQuestion()).thenReturn(2);help(stage);assertFalse(output().contains("Current question"));assertTrue(output().contains("Type your answer in chat"));when(stage.getCurrentQuestion()).thenReturn(-1);help(stage);assertFalse(output().contains("Question 0"));
    }

    @Test void selectionRecapExplainsCountsPointsAndAutomaticReopening(){
        var stage=new SelectionStage(base("physical_stage"),config("target","trait","key","physical","min-select",1,"max-select",3,"points",5));stage.spendPoints(2);help(stage);assertTrue(output().contains("1 to 3 of them"));assertTrue(output().contains("Points left: 3 of 5"));assertTrue(output().contains("menu reopens"));var one=new SelectionStage(base("race_stage"),config("target","race","key","race","min-select",1,"max-select",1));help(one);assertTrue(output().contains("You must pick 1"));assertFalse(output().contains("Points left"));var optional=new SelectionStage(base("optional_stage"),config("target","trait","key","physical"));help(optional);assertFalse(output().contains("You must pick"));
    }

    @Test void attributesSummaryWardrobeAndPreviewHintsExplainAvailableActions(){
        var stage=new AttributesStage(base("attributes_stage"),config("points",3,"max-rank",2));stage.tryIncrease("strength");help(stage);assertTrue(output().contains("Points left: 2 of 3"));assertTrue(output().contains("Highest rank per attribute: 2"));help(new SummaryStage(base("summary_stage"),config()));assertTrue(output().contains("confirm to finish"));assertTrue(output().contains("redo that stage"));help(new WardrobeStage(base("wardrobe_stage"),config()));assertTrue(output().contains("/rpcharacter wardrobe"));when(creation.isPreview()).thenReturn(true);help(stage);assertTrue(output().contains("leave the preview"));assertFalse(output().contains("/rpcharacter back"));assertFalse(output().contains("start over"));when(creation.isPreview()).thenReturn(false);when(creation.isEditing()).thenReturn(true);help(stage);assertTrue(output().contains("/rpcharacter back"));assertFalse(output().contains("start over"));
    }

    @Test void baseStageMetadataGatesAndPlatformRulesPreserveExplicitBoundaries(){
        var stage=new Stage();assertEquals("both",stage.getPlatform());assertTrue(stage.runsInGame());assertTrue(stage.runsOnWeb());stage.setPlatform(" WEB ");assertFalse(stage.runsInGame());assertTrue(stage.runsOnWeb());stage.setPlatform("game");assertTrue(stage.runsInGame());assertFalse(stage.runsOnWeb());for(String value:Arrays.asList(null," ","invalid")){stage.setPlatform(value);assertEquals("both",stage.getPlatform());}stage.setRevision(-4);assertEquals(0,stage.getRevision());stage.setRevision(7);assertEquals(7,stage.getRevision());stage.setId("custom");stage.setRepeat(false);stage.setAutoNext(true);stage.setLockTimeMs(5000);stage.setDependency(null);assertFalse(stage.hasDependency());stage.cancel();assertTrue(stage.isCancelled());stage.setCancelled(false);assertFalse(stage.isCancelled());assertEquals("custom",stage.getId());assertFalse(stage.shouldRepeat());assertTrue(stage.autoNext());assertEquals(5000,stage.getLockTimeMs());
        var pd=mock(PlayerData.class);assertTrue(stage.passesAccountAgeGate(null));stage.setRequireAccountAgeHoursMin(2);stage.setRequireAccountAgeHoursMax(4);assertEquals(2,stage.getRequireAccountAgeHoursMin());assertEquals(4,stage.getRequireAccountAgeHoursMax());assertFalse(stage.passesAccountAgeGate(null));when(pd.getAgeSeconds()).thenReturn(7199);assertFalse(stage.passesAccountAgeGate(pd));when(pd.getAgeSeconds()).thenReturn(7200);assertTrue(stage.passesAccountAgeGate(pd));when(pd.getAgeSeconds()).thenReturn(14400);assertFalse(stage.passesAccountAgeGate(pd));stage.update(pd);assertEquals("custom",stage.getId());assertNull(Stage.another(stage));
    }

    @Test void stageFactoryAndCopiesPreserveEverySupportedRuntimeType(){
        Map<StageType,Class<? extends Stage>> expected=Map.of(StageType.INFO,InfoStage.class,StageType.SETTER,SetterStage.class,StageType.SELECTION,SelectionStage.class,StageType.QUESTIONS,QuestionStage.class,StageType.CLUE,ClueStage.class,StageType.SUMMARY,SummaryStage.class,StageType.ATTRIBUTES,AttributesStage.class,StageType.WARDROBE,WardrobeStage.class);
        for(StageType type:StageType.values()){
            assertSame(type,StageType.valueOf(type.name()));var cfg=config("type",type.name(),"target","trait","key","physical","questions.one.question","Question","questions.one.answers",List.of("answer"),"messages",List.of("chat(Hello)"));Stage stage=Stage.create(type.name(),cfg);assertEquals(expected.get(type),stage.getClass());assertTrue(stage.shouldRepeat());assertTrue(stage.autoNext());assertFalse(stage.isCancelled());Stage copy=Stage.another(stage);assertEquals(stage.getClass(),copy.getClass());assertEquals(type.name(),copy.getId());
        }
        var cfg=config("type","INFO","repeat",false,"auto-next",false,"lock-time","2h","revision",3,"require-account-age-hours-min",2,"require-account-age-hours-max",5,"platform","web","dependency.type","trait","dependency.mode","all","dependency.depends-on",List.of("strong"));Stage configured=Stage.create("configured",cfg);assertFalse(configured.shouldRepeat());assertFalse(configured.autoNext());assertEquals(7200000,configured.getLockTimeMs());assertEquals(3,configured.getRevision());assertEquals(2,configured.getRequireAccountAgeHoursMin());assertEquals(5,configured.getRequireAccountAgeHoursMax());assertEquals("web",configured.getPlatform());assertTrue(configured.hasDependency());assertEquals(List.of("strong"),configured.getDependency().getDependencies());assertSame(configured.getDependency(),Stage.another(configured).getDependency());
    }

    @Test void stageFactoryIdentifiersUseLocaleIndependentNormalization(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertInstanceOf(InfoStage.class,assertDoesNotThrow(()->Stage.create("intro",config("type","info"))));}
}
