package net.tfminecraft.rpcharacters.objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.CharacterSkull;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.*;
import net.tfminecraft.rpcharacters.identity.NameColour;
import net.tfminecraft.rpcharacters.kit.*;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.mmocore.*;
import net.tfminecraft.rpcharacters.objects.attributes.*;
import net.tfminecraft.rpcharacters.objects.races.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;
import net.tfminecraft.rpcharacters.professions.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;

class RPCharacterRuntimeTest {
    RuntimeTestState state; ServerMock server; Player player; Race race; MMOCore previousMmo;
    MockedStatic<Database> database; final List<AutoCloseable> boundaries=new ArrayList<>();
    @BeforeEach void setup() {
        server=MockBukkit.mock(); state=new RuntimeTestState(RaceLoader.class); previousMmo=MMOCore.plugin;
        Cache.attributes=new ArrayList<>(); Cache.professions=new ArrayList<>(); Cache.backgroundTraitTypes=new ArrayList<>(List.of("background"));
        Cache.maxClues=8; Cache.defaultCluesRequired=1; Cache.evilCluesRequired=4; Cache.clueMinLength=3; Cache.clueMaxLength=30; Cache.traitClueOverrides=new HashMap<>(); Cache.conversationPairCooldownHours=2;
        player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn("Owner");
        race=race("human",2); RaceLoader.oList=new ArrayList<>(List.of(race)); database=boundary(Database.class);
    }
    @AfterEach void teardown() throws Exception {for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close(); MMOCore.plugin=previousMmo; MockBukkit.unmock(); state.close();}
    <T> MockedStatic<T> boundary(Class<T> type) {var mock=mockStatic(type); boundaries.add(mock); return mock;}
    Race race(String id,int strength) {var race=mock(Race.class); var data=mock(RaceData.class); when(race.getId()).thenReturn(id); when(race.getRaceData()).thenReturn(data); when(data.getAttributeData()).thenReturn(attributes(strength)); return race;}
    Trait trait(String id,String key,int strength) {var trait=mock(Trait.class); var data=mock(TraitData.class); when(trait.getId()).thenReturn(id); when(trait.getName()).thenReturn(id); when(trait.getTraitData()).thenReturn(data); when(data.getKey()).thenReturn(key); when(data.getAttributeData()).thenReturn(attributes(strength)); when(trait.getDesc()).thenReturn(List.of(id+" story")); return trait;}
    AttributeData attributes(int strength) {var data=new AttributeData(); data.addModifier(new AttributeModifier("strength",strength)); return data;}
    RPCharacter character() {var c=new RPCharacter(player); c.setName("Aria"); c.setRace(race); return c;}
    ClassManager installMmo() throws Exception {MMOCore.plugin=mock(MMOCore.class); var manager=mock(ClassManager.class); var field=MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin,manager); return manager;}

    @Test void constructorsAndRefreshMergeSheetDataWithoutChangingSharedTemplates() {
        var background=trait("history","background",3); when(background.getDesc()).thenReturn(Arrays.asList("One line",null," ","Two lines"));
        var empty=trait("empty","background",0); when(empty.getDesc()).thenReturn(Arrays.asList(null,"")); var ordinary=trait("strong","physical",4);
        var c=new RPCharacter(player,"id","Aria",false,Status.ALIVE,race,new ArrayList<>(List.of(background,empty,ordinary)),"warrior",List.of("&aBlue coat"));
        assertEquals(List.of("One line","Two lines"),c.getDescription()); assertEquals(9,c.getCreationBaseAmount("strength")); assertEquals(2,race.getRaceData().getAttributeData().getAmount(new AttributeModifier("strength",0))); assertTrue(c.getPlayerClues().getFirst().contains("Blue coat"));
        c.update(); assertEquals(9,c.getCreationBaseAmount("strength")); assertEquals(2,c.getDescription().size());
        var noClues=new RPCharacter(player,"empty","Empty",false,Status.MISSING,race,new ArrayList<>(),null); assertTrue(noClues.getPlayerClues().isEmpty());
        var nullClues=new RPCharacter(null,"null","Null",false,Status.ALIVE,race,new ArrayList<>(),null,null); assertTrue(nullClues.getPlayerClues().isEmpty()); assertNull(nullClues.getOwner());
    }

    @Test void storedLocationsResolveOnlyLoadedWorldsAndInvalidUpdatesClearThem() {
        var c=character(); assertNull(c.getLastLocation()); c.stampLastLocation(null); c.stampLastLocation(new Location(null,1,2,3)); assertFalse(c.hasLastLocation());
        var world=server.addSimpleWorld("character-world"); var location=new Location(world,12.5,64,-3); c.stampLastLocation(location);
        assertEquals(location,c.getLastLocation()); assertEquals("character-world",c.getLastLocationWorld()); assertEquals(12.5,c.getLastLocationX()); assertEquals(64D,c.getLastLocationY()); assertEquals(-3D,c.getLastLocationZ());
        c.setLastLocation("unloaded",1D,2D,3D); assertTrue(c.hasLastLocation()); assertNull(c.getLastLocation());
        c.setLastLocation("",1D,2D,3D); assertFalse(c.hasLastLocation()); c.setLastLocation("character-world",null,2D,3D); assertNull(c.getLastLocationX());
        c.setLastLocation("character-world",1D,2D,3D); c.clearLastLocation(); assertNull(c.getLastLocationWorld()); assertNull(c.getLastLocationY()); assertNull(c.getLastLocationZ());
    }

    @Test void lifecycleSynchronizesClassesOnlyWhenMmoDataIsReadyAndNotifiesChangedClass() throws Exception {
        var c=character(); assertFalse(c.hasMMOClass()); assertTrue(c.applyStoredClass()); c.setMMOClass("warrior"); assertTrue(c.hasMMOClass());
        var ready=boundary(MmoCorePlayerReady.class); var classes=boundary(ClassService.class); var points=boundary(AttributePointService.class); var professions=boundary(ProfessionIntegrator.class);
        assertFalse(c.applyStoredClass()); classes.verifyNoInteractions(); ready.when(() -> MmoCorePlayerReady.isReady(player)).thenReturn(true);
        var manager=installMmo(); assertTrue(c.applyStoredClass()); var klass=mock(PlayerClass.class); when(klass.getName()).thenReturn("Warrior"); when(manager.get("WARRIOR")).thenReturn(klass);
        classes.when(() -> ClassService.applyClass(player,"WARRIOR")).thenReturn(false); assertFalse(c.applyStoredClass());
        classes.when(() -> ClassService.applyClass(player,"WARRIOR")).thenReturn(true); assertTrue(c.applyStoredClass()); verify(player,never()).sendMessage(anyString());
        c.activate(); assertTrue(c.isActive()); verify(player).sendMessage(contains("Warrior")); points.verify(() -> AttributePointService.syncOnActivate(c)); professions.verify(() -> ProfessionIntegrator.apply(player,c));
        classes.when(() -> ClassService.isOnClass(player,"WARRIOR")).thenReturn(true); c.activate(); verify(player,times(1)).sendMessage(anyString());
        c.deactivate(); assertFalse(c.isActive()); points.verify(() -> AttributePointService.syncOnDeactivate(c)); professions.verify(() -> ProfessionIntegrator.remove(player,c));
        c.setOwner(null); assertTrue(c.applyStoredClass()); assertNull(c.getOwner()); c.setOwner(player); assertSame(player,c.getOwner());
    }

    @Test void deactivationCapturesAnUnassignedMmoClassAndTraitMutationsLogOnlyActiveChanges() {
        var points=boundary(AttributePointService.class); var professions=boundary(ProfessionIntegrator.class); var mmoPlayers=boundary(net.Indyuce.mmocore.api.player.PlayerData.class);
        var mmo=mock(net.Indyuce.mmocore.api.player.PlayerData.class); var klass=mock(PlayerClass.class); when(klass.getId()).thenReturn("WARRIOR"); when(mmo.getProfess()).thenReturn(klass); mmoPlayers.when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        var c=character(); c.deactivate(); assertEquals("WARRIOR",c.getMMOClass()); assertFalse(c.isActive());
        var first=trait("first","physical",0); var second=trait("second","physical",0); c.addTrait(first); c.addTrait(second); c.setFuel("second",3);
        c.removeTrait(second); assertEquals(List.of(first),c.getTraits()); assertNull(c.getTraitState("second")); c.removeTrait(second); assertEquals(1,c.getTraits().size());
        var active=new RPCharacter(player,"active","Active",true,Status.ALIVE,race,new ArrayList<>(),null); active.addTrait(first); active.removeTrait(first); assertTrue(active.getTraits().isEmpty());
        database.verify(() -> Database.log(player,"+first (Active)")); database.verify(() -> Database.log(player,"-first (Active)")); c.setStatus(Status.DEAD); assertEquals(Status.DEAD,c.getStatus());
    }

    @Test void durationAndFuelDefaultsPreserveExistingValuesAndDropOrphanState() {
        var c=character(); assertNull(c.getTraitState(null)); assertEquals(-1,c.getDurationRemainingMs("none")); assertEquals(-1D,c.getFuel("none")); c.setDurationRemainingMs(null,2); c.setDurationExpiresAtMs(null,2); c.setFuel(null,2); c.removeTraitState(null); c.initializeTraitState(null);
        c.setDurationExpiresAtMs("new-deadline",5000); assertEquals(5000,c.getTraitState("new-deadline").getExpiresAtMs()); c.removeTraitState("new-deadline");
        var timed=trait("HEALING","injury",0); when(timed.hasDuration()).thenReturn(true); when(timed.getDurationMs()).thenReturn(60_000L); when(timed.hasFuelTemplate()).thenReturn(true); when(timed.getFuelCapacity()).thenReturn(20D);
        var invalid=trait(null,"physical",0); var emptyFuel=trait("empty","physical",0); when(emptyFuel.hasFuelTemplate()).thenReturn(true); when(emptyFuel.getFuelCapacity()).thenReturn(0D);
        c.setTraits(new ArrayList<>(Arrays.asList(timed,null,invalid,emptyFuel))); c.ensureTraitStateDefaults(); assertTrue(c.getDurationRemainingMs(" healing ")>0); assertEquals(20D,c.getFuel("healing"));
        c.setFuel("healing",7); c.setFuel("orphan",4); c.ensureTraitStateDefaults(); assertEquals(7D,c.getFuel("healing")); assertNull(c.getTraitState("orphan"));
        assertThrows(UnsupportedOperationException.class,() -> c.getTraitStateMap().clear()); c.setDurationExpiresAtMs("healing",10_000); assertTrue(c.removeExpiredDurationTraits(10_000)); assertFalse(c.getTraits().contains(timed)); assertNull(c.getTraitState("healing")); assertFalse(c.removeExpiredDurationTraits(10_000));
        c.initializeTraitState(timed); assertEquals(20D,c.getFuel("healing")); c.setFuel("healing",-5); assertEquals(0D,c.getFuel("healing")); c.setDurationRemainingMs("healing",-5); assertEquals(0L,c.getDurationRemainingMs("healing")); c.removeTraitState("HEALING"); assertNull(c.getTraitState("healing"));
    }

    @Test void identityPersonaKitsAndModificationKeepIndependentState() {
        var c=character(); assertNotNull(c.getId()); c.setId("character-id"); assertEquals("character-id",c.getId()); c.setName(null); assertEquals("",c.getEffectiveDisplayPlain()); c.setName(" "); assertNull(c.getName()); c.setName(" §aAria "); assertEquals("Aria",c.getName());
        c.setAlias("§bAlias"); assertEquals("Alias",c.getAlias()); assertEquals("Alias",c.getEffectiveDisplayPlain()); c.setAlias(" "); assertNull(c.getAlias()); c.setAlias(null); c.setAlias("Other"); c.clearAlias(); assertEquals("Aria",c.getEffectiveDisplayPlain());
        c.setSlug("aria"); assertEquals("aria",c.getSlug()); c.setMailListed(false); assertFalse(c.isMailListed()); c.setHidden(true); assertTrue(c.isHidden()); c.setGender("Woman"); assertEquals("Woman",c.getGender()); c.setPersonaDescription("Story"); assertEquals("Story",c.getPersonaDescription());
        var colour=NameColour.of(List.of("#123456")); c.setNameColour(colour); assertSame(colour,c.getNameColour()); c.setNameColourStaffOverride(true); assertTrue(c.isNameColourStaffOverride()); c.setBirthday(" 0351-01-01 "); assertEquals("0351-01-01",c.getBirthday()); c.setBirthday(" "); assertNull(c.getBirthday()); c.setBirthday(null);
        c.modify("name","Ignored",false); assertEquals("Aria",c.getName()); c.modify("NAME","Renamed"); assertEquals("Renamed",c.getName()); var elf=race("elf",3); RaceLoader.oList.add(elf); c.modify("race","elf",false); assertSame(race,c.getRace()); c.modify("race","elf"); assertSame(elf,c.getRace()); c.modify("race","missing"); c.modify("other","value"); assertSame(elf,c.getRace());
        c.setKitStatus(null,KitStatus.GRANTED); c.setKitStatus(" ",KitStatus.GRANTED); assertNull(c.getKitStatus(null)); assertNull(c.getKitStatus(" ")); c.setKitStatus(" EXTRA ",KitStatus.ELIGIBLE); assertEquals(KitStatus.ELIGIBLE,c.getKitStatus("extra")); c.setKitStatus("extra",null); assertNull(c.getKitStatus("EXTRA")); c.setKitStatus(KitStatus.GRANTED); assertEquals(KitStatus.GRANTED,c.getKitStatus()); assertEquals(1,c.getKitStatuses().size());
        c.putKitCustomise(null); c.putKitCustomise(new KitCustomiseData(" ",null,null,null,null)); var kit=new KitCustomiseData("Sword","Sword",List.of(),null,""); c.putKitCustomise(kit); assertSame(kit,c.getKitCustomisations().get("sword")); c.removeKitCustomise(null); c.removeKitCustomise(" "); c.removeKitCustomise(" SWORD "); assertTrue(c.getKitCustomisations().isEmpty());
        var skull=new ItemStack(Material.PLAYER_HEAD); var skulls=boundary(CharacterSkull.class); skulls.when(() -> CharacterSkull.of(c)).thenReturn(skull); assertSame(skull,c.getSkull());
    }

    @Test void clueRequirementsUseMaximumOverridesAndClueMutationRejectsInvalidInput() {
        var c=character(); var evil=trait("evil","evil",0); var other=trait("strong","physical",0); Cache.traitClueOverrides.put("strong",6); c.setTraits(new ArrayList<>(List.of(evil,other))); assertEquals(6,c.getCluesNeeded()); Cache.maxClues=5; assertEquals(5,c.getCluesNeeded()); assertFalse(c.hasEnoughClues());
        assertEquals(ClueAddResult.TOO_SHORT,c.addPlayerClue("x")); assertEquals(ClueAddResult.TOO_LONG,c.addPlayerClue("x".repeat(31))); assertEquals(ClueAddResult.SUCCESS,c.addPlayerClue("&aBlue coat")); assertEquals(ClueAddResult.DUPLICATE,c.addPlayerClue("BLUE COAT")); assertEquals(ClueAddResult.SUCCESS,c.addPlayerClue("Worn boots")); assertTrue(c.removePlayerClue(1));
        Cache.maxClues=1; assertTrue(c.hasEnoughClues()); assertFalse(c.canAddClue()); assertEquals(ClueAddResult.AT_MAX,c.addPlayerClue("Other clue")); assertThrows(UnsupportedOperationException.class,() -> c.getPlayerClues().clear());
        assertFalse(c.removePlayerClue(-1)); assertFalse(c.removePlayerClue(1)); assertTrue(c.removePlayerClue(0)); assertTrue(c.getPlayerClues().isEmpty());
        for(ClueAddResult result:ClueAddResult.values()) {if(result==ClueAddResult.SUCCESS)assertNull(c.getClueAddErrorMessage(result)); else assertFalse(c.getClueAddErrorMessage(result).isBlank());}
        c.setTraits(List.of(other)); Cache.maxClues=8; assertEquals(6,c.getCluesNeeded()); Cache.traitClueOverrides.clear(); assertEquals(1,c.getCluesNeeded());
    }

    @Test void progressCountersClampAndExposeReadOnlyStageState() {
        var c=character(); c.setSubclassPicked(true); assertTrue(c.hasPickedSubclass()); c.setPaidClassPicks(-2); assertEquals(0,c.getPaidClassPicks()); c.setPaidClassPicks(3); assertEquals(3,c.getPaidClassPicks());
        c.setPvpLethal(false); assertFalse(c.isPvpLethal()); c.setPaidChangeCount(null,3); assertEquals(0,c.getPaidChangeCount(null)); c.setPaidChangeCount("class",2); assertEquals(2,c.getPaidChangeCounts().get("class")); c.setPaidChangeCount("class",0); assertEquals(0,c.getPaidChangeCount("class")); assertThrows(UnsupportedOperationException.class,() -> c.getPaidChangeCounts().put("bad",1));
        var pending=new PendingPaidChange("class","class",player.getUniqueId(),null,BigDecimal.ZERO,"{}"); c.setPendingPaidChange(pending); assertSame(pending,c.getPendingPaidChange()); c.setPendingPaidChange(null); assertNull(c.getPendingPaidChange());
        c.setStageRevision(null,3,9); assertEquals(0,c.getStageRevision(null)); assertEquals(0,c.getStageRevisionSince(null)); c.setStageRevision("class",2,9); assertEquals(2,c.getStageRevision("class")); assertEquals(9,c.getStageRevisionSince("class")); c.setStageRevision("class",3,0); assertEquals(0,c.getStageRevisionSince("class")); assertEquals(Map.of("class",3),c.getStageRevisions()); assertThrows(UnsupportedOperationException.class,() -> c.getStageRevisions().clear());
        c.setEvilRpStrikes(-1); assertEquals(0,c.getEvilRpStrikes()); c.setEvilRpStrikes(3); assertEquals(3,c.getEvilRpStrikes()); c.setEvilRpSessionEndsAtMs(-1); assertEquals(0,c.getEvilRpSessionEndsAtMs()); c.setEvilRpSessionEndsAtMs(50); assertEquals(50,c.getEvilRpSessionEndsAtMs()); c.setLastStrikeAtMs(-1); assertEquals(0,c.getLastStrikeAtMs()); c.setLastStrikeAtMs(20); assertEquals(20,c.getLastStrikeAtMs());
        c.setFoodValue(-1); assertEquals(0,c.getFoodValue()); c.setFoodValue(1000); assertEquals(200,c.getFoodValue()); c.setDietScore(-1); assertEquals(0,c.getDietScore()); c.setDietScore(100); assertEquals(40,c.getDietScore()); c.setRawDietScore(100); assertEquals(100,c.getRawDietScore()); c.setRawDietScore(-1); assertEquals(0,c.getRawDietScore()); c.setLastDietTierId("healthy"); assertEquals("healthy",c.getLastDietTierId());
    }

    @Test void accountIndependentAgePlaytimeAndConversationCooldownsRetainHistory() {
        var c=character(); c.setCreatedAtEpochSeconds(-1); assertEquals(0,c.getCreatedAtEpochSeconds()); assertEquals(0,c.getAgeSeconds()); c.setCreatedAtEpochSeconds((int)Instant.now().getEpochSecond()-100); assertTrue(c.getAgeSeconds()>=100&&c.getAgeSeconds()<=101); c.setCreatedAtEpochSeconds(Integer.MAX_VALUE); assertEquals(0,c.getAgeSeconds());
        c.setOnlinePlaytimeSeconds(-1); assertEquals(0,c.getOnlinePlaytimeSeconds()); c.addOnlinePlaytimeSeconds(0); c.addOnlinePlaytimeSeconds(-1); c.addOnlinePlaytimeSeconds(10); assertEquals(10,c.getOnlinePlaytimeSeconds()); c.addOnlinePlaytimeSeconds(Integer.MAX_VALUE); assertEquals(Integer.MAX_VALUE,c.getOnlinePlaytimeSeconds());
        var other=character(); other.setId("other"); assertFalse(c.canCountConversationWith(null,1)); c.recordConversationWith(null,1); assertEquals(0,c.getConversationCount(null)); assertTrue(c.canCountConversationWith(other,1)); c.recordConversationWith(other,1); assertEquals(1,c.getConversationCount("other")); assertFalse(c.canCountConversationWith(other,7_200_000)); assertTrue(c.canCountConversationWith(other,7_200_001));
        var counts=new HashMap<>(Map.of("other",5)); var times=new HashMap<>(Map.of("other",20L)); c.setConversationCounts(counts); c.setConversationLastAtMs(times); counts.clear(); times.clear(); assertEquals(Map.of("other",5),c.getConversationCounts()); assertEquals(Map.of("other",20L),c.getConversationLastAtMs()); c.setConversationCounts(null); c.setConversationLastAtMs(null); assertTrue(c.getConversationCounts().isEmpty()); assertTrue(c.getConversationLastAtMs().isEmpty());
    }

    @Test void professionUpgradesResolveCostsAndForfeitureWithoutRefundingPoints() {
        var c=character(); var smith=new ProfessionUpgradeDefinition("smith-1","smithing",null,3,"",null,null); var mine=new ProfessionUpgradeDefinition("mine-1","mining",null,5,"",null,null); var registry=boundary(ProfessionRegistry.class); registry.when(() -> ProfessionRegistry.getUpgrade("smith-1")).thenReturn(smith); registry.when(() -> ProfessionRegistry.getUpgrade("mine-1")).thenReturn(mine);
        c.setProfessionUpgrades(Arrays.asList("smith-1",null," ","smith-1","missing")); c.addProfessionUpgrade(null); c.addProfessionUpgrade(" "); c.addProfessionUpgrade("mine-1"); assertEquals(List.of("smith-1","missing","mine-1"),c.getProfessionUpgrades()); assertTrue(c.hasProfessionUpgrade("smith-1")); assertFalse(c.hasProfessionUpgrade(null)); assertEquals(List.of(smith,mine),c.resolveProfessionUpgrades()); assertEquals(8,c.getTotalSpentPoints()); assertEquals(3,c.getSpentPointsOnProfession("SMITHING")); assertEquals(0,c.getSpentPointsOnProfession(null)); assertThrows(UnsupportedOperationException.class,() -> c.getProfessionUpgrades().clear());
        c.addForfeitedProfessionPoints(null,2); c.addForfeitedProfessionPoints(" ",2); c.addForfeitedProfessionPoints("mining",0); c.forfeitProfessionUpgrade(null); c.forfeitProfessionUpgrade(smith); c.forfeitProfessionUpgrade(smith); assertFalse(c.hasProfessionUpgrade("smith-1")); assertEquals(3,c.getSpentPointsOnProfession("smithing")); c.addForfeitedProfessionPoints("SMITHING",2); assertEquals(5,c.getForfeitedProfessionPoints().get("smithing"));
        var forfeits=new HashMap<String,Integer>(); forfeits.put("mining",4); forfeits.put("bad",null); c.setForfeitedProfessionPoints(forfeits); assertEquals(Map.of("mining",4),c.getForfeitedProfessionPoints()); assertThrows(UnsupportedOperationException.class,() -> c.getForfeitedProfessionPoints().clear()); c.clearForfeitedProfessionPoints(); assertTrue(c.getForfeitedProfessionPoints().isEmpty()); c.setForfeitedProfessionPoints(null); c.removeProfessionUpgrade("missing"); c.clearProfessionUpgrades(); assertTrue(c.getProfessionUpgrades().isEmpty()); c.setProfessionUpgrades(null);
    }

    @Test void extraAttributeAllocationsIgnoreInvalidValuesAndTrackSpentPoints() {
        var c=character(); var allocation=new HashMap<String,Integer>(); allocation.put("strength",3); allocation.put("DEXTERITY",2); allocation.put("zero",0); allocation.put("negative",-1); allocation.put("missing",null); allocation.put(null,4); c.setExtraAttributeAllocation(allocation); allocation.clear(); assertEquals(Map.of("strength",3,"dexterity",2),c.getExtraAttributeAllocation()); assertEquals(5,c.getSpentExtraAttributePoints()); assertThrows(UnsupportedOperationException.class,() -> c.getExtraAttributeAllocation().clear()); c.setExtraAttributeAllocation(null); assertEquals(0,c.getSpentExtraAttributePoints());
        c.setAttributeData(attributes(6)); assertEquals(6,c.getCreationBaseAmount("strength")); assertEquals(0,c.getCreationBaseAmount(null)); c.setAttributeData(null); assertEquals(0,c.getCreationBaseAmount("strength")); assertNull(c.getAttributeData());
    }

    @Test void machineIdentifiersRemainStableUnderTurkishLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); var c=character();
        assertAll(
            () -> {c.setMMOClass("mining"); assertEquals("MINING",c.getMMOClass());},
            () -> {var kit=new KitCustomiseData("IRON",null,null,null,null); c.putKitCustomise(kit); assertSame(kit,c.getKitCustomisations().get("iron")); c.removeKitCustomise("IRON"); assertTrue(c.getKitCustomisations().isEmpty());},
            () -> {var injury=trait("INJURY","physical",0); c.setTraits(List.of(injury)); Cache.traitClueOverrides.put("injury",5); assertEquals(5,c.getCluesNeeded());},
            () -> {c.addForfeitedProfessionPoints("MINING",4); assertEquals(Map.of("mining",4),c.getForfeitedProfessionPoints());},
            () -> {var lookup=character(); lookup.addForfeitedProfessionPoints("mining",4); assertEquals(4,lookup.getSpentPointsOnProfession("MINING"));},
            () -> {c.setExtraAttributeAllocation(Map.of("INTELLIGENCE",3)); assertEquals(Map.of("intelligence",3),c.getExtraAttributeAllocation());});
    }

    @Test void assigningExistingReadOnlyAllocationViewsDoesNotEraseTheirContents() {
        var c=character(); assertAll(
            () -> {c.setExtraAttributeAllocation(Map.of("strength",3)); c.setExtraAttributeAllocation(c.getExtraAttributeAllocation()); assertEquals(Map.of("strength",3),c.getExtraAttributeAllocation());},
            () -> {c.setForfeitedProfessionPoints(Map.of("mining",4)); c.setForfeitedProfessionPoints(c.getForfeitedProfessionPoints()); assertEquals(Map.of("mining",4),c.getForfeitedProfessionPoints());});
    }
}
