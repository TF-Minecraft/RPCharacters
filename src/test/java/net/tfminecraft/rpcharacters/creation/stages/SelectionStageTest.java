package net.tfminecraft.rpcharacters.creation.stages;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.managers.*;
import net.tfminecraft.rpcharacters.mmocore.*;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.*;
import net.tfminecraft.rpcharacters.objects.races.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.*;

class SelectionStageTest extends StageFixture {
    @Test void raceAndTraitConfigurationFilterOptionsAndCopyBaseMetadata() {
        Race human=race("human","race"),elf=race("elf","other");trait("lasting","injury",2,false);trait("temporary","injury",1,true);trait("other","physical",1,false);
        Stage base=base();base.setRepeat(true);base.setRevision(3);base.setLockTimeMs(1000);base.setPlatform("game");SelectionStage races=new SelectionStage(base,config("target","race","key","race","min-select",1,"max-select",1));
        assertEquals(List.of("human"),races.getOptions().stream().map(SelectableItem::getId).toList());assertEquals("race",races.getTarget());assertEquals("race",races.getKey());assertEquals(27,races.getSize());assertEquals(1,races.getMinSelections());assertEquals(1,races.getMaxSelections());assertFalse(races.hasPoints());assertFalse(races.isActive());assertEquals(0,races.getSelections());assertTrue(races.shouldRepeat());assertEquals(3,races.getRevision());assertEquals(1000,races.getLockTimeMs());assertEquals("game",races.getPlatform());
        var filtered=new SelectionStage(base,config("target","trait","key","injury","filter","PERMANENT-ONLY","points",5,"gui-size",54,"slots",List.of(10,11)));
        assertEquals(List.of("lasting"),filtered.getOptions().stream().map(SelectableItem::getId).toList());assertEquals("PERMANENT-ONLY",filtered.getFilter());assertEquals(54,filtered.getSize());assertEquals(List.of(10,11),filtered.getSlots());assertTrue(filtered.hasPoints());assertEquals(5,filtered.getPoints());assertEquals(5,filtered.getInitialPoints());
        var all=new SelectionStage(base,config("target","trait","key","injury","filter","all"));assertEquals(2,all.getOptions().size());var copy=new SelectionStage(filtered);assertEquals(5,copy.getPoints());assertNotSame(filtered.getOptions(),copy.getOptions());assertNotSame(filtered.getOptions().getFirst(),copy.getOptions().getFirst());assertEquals(filtered.getId(),copy.getId());assertFalse(copy.isActive());
        var unknown=new SelectionStage(base,config("target","unsupported"));assertTrue(unknown.getOptions().isEmpty());
    }

    @Test void classSlotsNormalizeIdsAndUseTheExternalClassCatalog() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));var option=classOption("warrior");classOptions(List.of(option),List.of(20));var cfg=config("target","class","key","class","gui-size",54,"class-slots. WARRIOR ",20,"class-slots. ",7);
        var stage=new SelectionStage(base(),cfg);classGui.verify(() -> MmoCoreClassGuiHelper.buildClassOptions(54,Map.of("warrior",20)));assertEquals(List.of(option),stage.getOptions());assertEquals(List.of(20),stage.getSlots());new SelectionStage(base(),config("target","class"));classGui.verify(() -> MmoCoreClassGuiHelper.buildClassOptions(27,Map.of()));
    }

    @Test void selectionAndHydrationMaintainBudgetAndMatchAllSupportedTargets() {
        Trait wanted=trait("strong","physical",2,false),other=trait("other","other",1,false);var stage=selection("trait","physical",5);var item=stage.getOptions().getFirst();stage.select(item);assertEquals(3,stage.getPoints());assertEquals(1,stage.getSelections());assertEquals(List.of(item),stage.getSelection());stage.unSelect(item);assertEquals(5,stage.getPoints());assertTrue(stage.getSelection().isEmpty());stage.increase();stage.decrease();stage.spendPoints(1);stage.addPoints(1);assertEquals(0,stage.getSelections());
        characterTraits.addAll(List.of(wanted,other));stage.hydrateFromCharacter(character);assertEquals(1,stage.getSelections());assertTrue(item.isSelected());assertEquals(3,stage.getPoints());stage.hydrateFromCharacter(character);assertEquals(3,stage.getPoints());stage.hydrateFromCharacter(null);assertFalse(item.isSelected());assertEquals(5,stage.getPoints());assertEquals(0,stage.getSelections());
        Race first=race("human","race"),second=race("elf","race");currentRace.set(second);var raceStage=selection("race","race",0);raceStage.hydrateFromCharacter(character);assertEquals(List.of("elf"),raceStage.getSelection().stream().map(SelectableItem::getId).toList());currentRace.set(null);raceStage.hydrateFromCharacter(character);assertTrue(raceStage.getSelection().isEmpty());
        var warrior=classOption("warrior");var mage=classOption("mage");classOptions(List.of(warrior,mage),List.of(20,21));currentClass.set("MAGE");var classes=selection("class","class",0);classes.hydrateFromCharacter(character);assertEquals(List.of(mage),classes.getSelection());currentClass.set(null);classes.hydrateFromCharacter(character);assertTrue(classes.getSelection().isEmpty());
    }

    @Test void rejectedConfirmationKeepsTheStageOpenAndCharacterUntouched() {
        trait("costly","physical",3,false);var stage=new SelectionStage(base(),config("target","trait","key","physical","min-select",1,"max-select",2,"points",2));stage.execute(player,creation);assertTrue(stage.isActive());stage.confirm(player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Need at least 1")));verify(player,never()).closeInventory();
        var item=stage.getOptions().getFirst();item.setSelected(true);stage.select(item);stage.confirm(player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Cannot afford")));assertTrue(characterTraits.isEmpty());assertTrue(later.isEmpty());assertTrue(stage.isActive());
    }

    @Test void traitConfirmationReplacesOnlyItsGroupAndRoutesEveryCompletionMode() {
        var previous=trait("previous","physical",0,false);var retained=trait("retained","social",0,false);var selected=trait("selected","physical",0,false);characterTraits.addAll(List.of(previous,retained));var stage=selection("trait","physical",0);select(stage,"selected");stage.execute(player,creation);stage.confirm(player,creation);assertEquals(List.of(retained,selected),characterTraits);assertFalse(stage.isActive());verify(player).closeInventory();assertEquals(1,later.size());runLater();verify(creation).runStage();
        stage.setAutoNext(false);stage.confirm(player,creation);runLater();verify(creation).setCanNext(true);when(creation.isEditingFromSummary()).thenReturn(true);stage.confirm(player,creation);runLater();verify(creation).returnToSummary();
    }

    @Test void raceAndClassEditsNotifyLifecycleWhileCreationOnlySetsValues() {
        var human=race("human","race");var elf=race("elf","race");currentRace.set(human);var races=selection("race","race",0);select(races,"elf");races.confirm(player,creation);assertSame(elf,currentRace.get());lifecycle.verify(() -> CharacterLifecycle.notifyRaceChange(any(),any(),any(),any(),any()),never());
        currentRace.set(human);when(creation.isEditing()).thenReturn(true);races.confirm(player,creation);lifecycle.verify(() -> CharacterLifecycle.notifyRaceChange(player,owner,character,"human","elf"));
        var warrior=classOption("warrior");classOptions(List.of(warrior),List.of(20));var classes=selection("class","class",0);select(classes,"warrior");currentClass.set("mage");classes.confirm(player,creation);assertEquals("warrior",currentClass.get());lifecycle.verify(() -> CharacterLifecycle.notifyClassChange(player,owner,character,"mage","warrior"));when(creation.isEditing()).thenReturn(false);classes.confirm(player,creation);assertEquals("warrior",currentClass.get());
    }

    @Test void injuryConfirmationStripsReplacedInjuriesInCreationAndStandaloneMode() {
        trait("arm","prosthetic",0,false);var stage=selection("trait","prosthetic",0);select(stage,"arm");stage.confirm(player,creation);prosthetics.verify(() -> ProstheticTraitRules.stripReplacedInjuries(character));
        stage.confirm(player,null);verify(manager,never()).savePlayer(player);when(playerData.hasActiveCharacter()).thenReturn(true);prosthetics.when(() -> ProstheticTraitRules.stripReplacedInjuries(character)).thenReturn(true);stage.confirm(player,null);verify(character).update();verify(manager).savePlayer(player);
        prosthetics.when(() -> ProstheticTraitRules.stripReplacedInjuries(character)).thenReturn(false);stage.confirm(player,null);verify(manager,times(1)).savePlayer(player);players.when(() -> PlayerManager.get(player)).thenReturn(null);stage.confirm(player,null);selection("race","race",0).confirm(player,null);
    }

    @Test void executeIgnoresCancelledCreationAndUpdateHydratesExistingTraits() {
        var strong=trait("strong","physical",2,false);trait("other","physical",1,false);characterTraits.add(strong);var stage=selection("trait","physical",5);when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);assertFalse(stage.isActive());assertTrue(inventories.constructed().isEmpty());when(creation.isCancelled()).thenReturn(false);stage.execute(player,creation);verify(inventories.constructed().getFirst()).selectionView(player,stage,creation);
        stage.update(playerData);assertEquals(0,stage.getSelections());when(playerData.hasActiveCharacter()).thenReturn(true);stage.update(playerData);assertEquals(1,stage.getSelections());assertEquals(3,stage.getPoints());
    }

    @Test void refreshingAnExistingSelectionIsIdempotent() {
        characterTraits.add(trait("strong","physical",2,false));when(playerData.hasActiveCharacter()).thenReturn(true);var stage=selection("trait","physical",5);stage.update(playerData);stage.update(playerData);assertEquals(1,stage.getSelections(),"Repeated refresh must not duplicate the same selected trait");assertEquals(3,stage.getPoints());assertEquals(1,stage.getSelection().size());
    }

    @Test void permanentOnlyInjuryEditingPreservesTemporaryInjuries() {
        Trait permanent=trait("old","injury",0,false),temporary=trait("healing","injury",0,true),replacement=trait("new","injury",0,false);characterTraits.addAll(List.of(permanent,temporary));var stage=new SelectionStage(base(),config("target","trait","key","injury","filter","permanent-only","max-select",99));select(stage,"new");stage.confirm(player,creation);assertEquals(List.of(temporary,replacement),characterTraits,"Options excluded by permanent-only must survive editing the visible permanent injury set");
    }

    @Test void copyingUsedSelectionStartsWithFreshBudgetAndUnselectedOptions() {
        trait("strong","physical",2,false);var original=selection("trait","physical",5);select(original,"strong");var copy=new SelectionStage(original);assertEquals(0,copy.getSelections());assertEquals(5,copy.getPoints(),"A fresh stage copy must start with its configured budget");assertFalse(copy.getOptions().getFirst().isSelected(),"A zero-selection copy cannot inherit selected option flags");assertEquals(3,original.getPoints());
    }

    @Test void removedRaceDefinitionCannotClearCharacterRaceOrCrashConfirmation() {
        var previous=race("human","race");race("elf","race");currentRace.set(previous);var stage=selection("race","race",0);select(stage,"elf");stage.execute(player,creation);RaceLoader.oList.removeIf(r->r.getId().equals("elf"));assertDoesNotThrow(()->stage.confirm(player,creation));assertSame(previous,currentRace.get());assertTrue(stage.isActive());assertTrue(later.isEmpty());
    }

    @Test void removedTraitDefinitionCannotDeleteThePreviousTraitSet() {
        var previous=trait("old","physical",0,false);trait("new","physical",0,false);characterTraits.add(previous);var stage=selection("trait","physical",0);select(stage,"new");stage.execute(player,creation);TraitLoader.oList.removeIf(t->t.getId().equals("new"));assertDoesNotThrow(()->stage.confirm(player,creation));assertEquals(List.of(previous),characterTraits);assertTrue(stage.isActive());assertTrue(later.isEmpty());
    }

    @Test void delayedSummaryCompletionCannotResumeACancelledCreation() {
        var stage=selection("trait","physical",0);when(creation.isEditingFromSummary()).thenReturn(true);stage.confirm(player,creation);when(creation.isCancelled()).thenReturn(true);runLater();verify(creation,never()).returnToSummary();verify(creation,never()).runStage();verify(creation,never()).setCanNext(anyBoolean());
    }

    private SelectionStage selection(String target,String key,int points){return new SelectionStage(base(),config("target",target,"key",key,"points",points,"max-select",99));}
    private void select(SelectionStage stage,String id){var item=stage.getOptions().stream().filter(i->i.getId().equals(id)).findFirst().orElseThrow();item.setSelected(true);stage.select(item);}
}

abstract class StageFixture {
    RuntimeTestState state;RPCharacters plugin;Logger logger;Player player;UUID owner;PlayerData playerData;PlayerManager manager;RPCharacter character;CharacterCreation creation;
    final List<Trait> characterTraits=new ArrayList<>();final AtomicReference<Race> currentRace=new AtomicReference<>();final AtomicReference<String> currentClass=new AtomicReference<>();final List<Runnable> later=new ArrayList<>();final List<AutoCloseable> closeables=new ArrayList<>();
    MockedStatic<Bukkit> bukkit;MockedStatic<RPTexts> texts;MockedStatic<PlayerManager> players;MockedStatic<CharacterLifecycle> lifecycle;MockedStatic<ProstheticTraitRules> prosthetics;MockedStatic<MmoCoreClassGuiHelper> classGui;MockedStatic<MmoCoreAttributeHelper> attributes;MockedConstruction<InventoryManager> inventories;
    @BeforeEach void setupStages(){
        MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,RaceLoader.class,TraitLoader.class);Cache.attributes=new ArrayList<>(List.of("strength","dexterity"));Cache.professions=new ArrayList<>();Cache.backgroundTraitTypes=new ArrayList<>();RaceLoader.oList=new ArrayList<>();TraitLoader.oList=new ArrayList<>();
        plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);RPCharacters.plugin=plugin;owner=UUID.randomUUID();player=mock(Player.class);when(player.getUniqueId()).thenReturn(owner);when(player.isOnline()).thenReturn(true);playerData=mock(PlayerData.class);when(playerData.getUniqueId()).thenReturn(owner);manager=mock(PlayerManager.class);character=mock(RPCharacter.class);when(character.getTraits()).thenReturn(characterTraits);when(character.getRace()).thenAnswer(c->currentRace.get());doAnswer(c->{currentRace.set(c.getArgument(0));return null;}).when(character).setRace(any());when(character.getMMOClass()).thenAnswer(c->currentClass.get());when(character.hasMMOClass()).thenAnswer(c->currentClass.get()!=null);doAnswer(c->{currentClass.set(c.getArgument(0));return null;}).when(character).setMMOClass(any());doAnswer(c->{characterTraits.add(c.getArgument(0));return null;}).when(character).addTrait(any());doAnswer(c->{characterTraits.remove(c.getArgument(0));return null;}).when(character).removeTrait(any());when(character.getAttributeData()).thenReturn(new AttributeData());when(playerData.getActiveCharacter()).thenReturn(character);creation=mock(CharacterCreation.class);when(creation.getCharacter()).thenReturn(character);
        bukkit=staticMock(Bukkit.class,CALLS_REAL_METHODS);var scheduler=mock(BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);when(scheduler.runTaskLater(any(Plugin.class),any(Runnable.class),anyLong())).thenAnswer(c->{later.add(c.getArgument(1));return mock(BukkitTask.class);});
        texts=staticMock(RPTexts.class);players=staticMock(PlayerManager.class);players.when(() -> PlayerManager.get(player)).thenReturn(playerData);staticMock(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);lifecycle=staticMock(CharacterLifecycle.class);lifecycle.when(() -> CharacterLifecycle.raceId(any())).thenAnswer(c->c.<Race>getArgument(0).getId());prosthetics=staticMock(ProstheticTraitRules.class);classGui=staticMock(MmoCoreClassGuiHelper.class);attributes=staticMock(MmoCoreAttributeHelper.class);attributes.when(() -> MmoCoreAttributeHelper.exists(anyString())).thenReturn(true);attributes.when(() -> MmoCoreAttributeHelper.exists("missing")).thenReturn(false);inventories=mockConstruction(InventoryManager.class);closeables.add(inventories);
    }
    @AfterEach void cleanupStages() throws Exception{for(int i=closeables.size()-1;i>=0;i--)closeables.get(i).close();state.close();MockBukkit.unmock();}
    <T> MockedStatic<T> staticMock(Class<T> type){return staticMock(type,RETURNS_DEFAULTS);}
    <T> MockedStatic<T> staticMock(Class<T> type,org.mockito.stubbing.Answer<Object> answer){var value=mockStatic(type,answer);closeables.add(value);return value;}
    Stage base(){var s=new Stage();s.setId("stage");s.setAutoNext(true);return s;}
    YamlConfiguration config(Object...pairs){var config=new YamlConfiguration();for(int i=0;i<pairs.length;i+=2)config.set((String)pairs[i],pairs[i+1]);return config;}
    Trait trait(String id,String key,int cost,boolean duration){var trait=mock(Trait.class);var data=mock(TraitData.class);when(trait.getId()).thenReturn(id);when(trait.getName()).thenReturn(id);when(trait.getTraitData()).thenReturn(data);when(data.getKey()).thenReturn(key);when(data.getCost()).thenReturn(cost);when(data.hasDuration()).thenReturn(duration);when(trait.hasDuration()).thenReturn(duration);when(data.getAttributeData()).thenReturn(new AttributeData());TraitLoader.oList.add(trait);return trait;}
    Race race(String id,String key){var race=mock(Race.class);var data=mock(RaceData.class);when(race.getId()).thenReturn(id);when(race.getName()).thenReturn(id);when(race.getRaceData()).thenReturn(data);when(data.getKey()).thenReturn(key);when(data.getAttributeData()).thenReturn(new AttributeData());RaceLoader.oList.add(race);return race;}
    SelectableItem classOption(String id){var c=mock(PlayerClass.class);when(c.getId()).thenReturn(id);when(c.getName()).thenReturn(id);return new SelectableItem(c);}
    void classOptions(List<SelectableItem> options,List<Integer> slots){var data=mock(MmoCoreClassGuiHelper.ClassGuiData.class);when(data.getOptions()).thenReturn(options);when(data.getSlots()).thenReturn(slots);classGui.when(() -> MmoCoreClassGuiHelper.buildClassOptions(anyInt(),anyMap())).thenReturn(data);}
    void runLater(){while(!later.isEmpty())later.removeFirst().run();}
}
