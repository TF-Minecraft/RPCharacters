package net.tfminecraft.rpcharacters.clues.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.managers.*;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ClueDiscoveryRuntimeTest {
    ServerMock server; World world; PlayerMock player; PlayerData data; RPCharacter character;
    RuntimeTestState state; ClueDiscoverySettings settings; SpawnedClueManager manager; ClueDiscoveryVisualManager visuals;
    List<AutoCloseable> mocks = new ArrayList<>(); MockedStatic<PlayerManager> players; MockedStatic<RPTexts> texts;
    @BeforeEach void setup() {
        server=MockBukkit.mock();world=server.addSimpleWorld("clues");player=server.addPlayer();player.teleport(new Location(world,.5,65.5,.5));state=new RuntimeTestState();
        settings=new ClueDiscoverySettings();boundary(ClueDiscoveryLoader.class).when(ClueDiscoveryLoader::getSettings).thenReturn(settings);
        manager=mock(SpawnedClueManager.class);boundary(SpawnedClueManager.class).when(SpawnedClueManager::get).thenReturn(manager);
        visuals=mock(ClueDiscoveryVisualManager.class);boundary(ClueDiscoveryVisualManager.class).when(ClueDiscoveryVisualManager::get).thenReturn(visuals);
        players=boundary(PlayerManager.class);texts=boundary(RPTexts.class);data=new PlayerData(player);var raceConfig=new org.bukkit.configuration.file.YamlConfiguration();raceConfig.set("name","Human");character=new RPCharacter(player,UUID.randomUUID().toString(),"Scout",true,net.tfminecraft.rpcharacters.enums.Status.ALIVE,new net.tfminecraft.rpcharacters.objects.races.Race("human",raceConfig),new ArrayList<>(),null);data.getCharacters().add(character);players.when(() -> PlayerManager.get(player)).thenReturn(data);
    }
    <T> MockedStatic<T> boundary(Class<T> type) {var mock=mockStatic(type);mocks.add(mock);return mock;}
    @AfterEach void cleanup() throws Exception {for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();state.close();MockBukkit.unmock();}
    SpawnedClue clue() {return new SpawnedClue(UUID.randomUUID(),world.getName(),.5,65.5,.5,"trace",Long.MAX_VALUE,player.getUniqueId(),0,65,0);}

    @Test void bootstrapInitialisesOnceClampsAndHandlesMissingAccounts() {
        players.when(() -> PlayerManager.get(player)).thenReturn(null);InvestigationPointService.bootstrap(player);InvestigationPointService.regen(player);assertFalse(InvestigationPointService.hasPoints(player,1));assertFalse(InvestigationPointService.spend(player,1));assertTrue(InvestigationPointService.spend(player,0));
        players.when(() -> PlayerManager.get(player)).thenReturn(data);InvestigationPointService.bootstrap(player);assertEquals(20,data.getInvestigationPoints());assertNotNull(data.getLastInvestigationRegenMs());assertTrue(InvestigationPointService.spend(player,3));assertEquals(17,data.getInvestigationPoints());assertFalse(InvestigationPointService.spend(player,18));assertTrue(InvestigationPointService.hasPoints(player,17));assertFalse(InvestigationPointService.hasPoints(player,18));
        settings.setInvestigationPointsMax(4);InvestigationPointService.bootstrap(player);assertEquals(4,data.getInvestigationPoints());settings.setMessageNoInvestigationPoints("Rest");InvestigationPointService.sendNoPointsMessage(player);texts.verify(() -> RPTexts.send(player,"Rest"));settings.setMessageNoInvestigationPoints(" ");InvestigationPointService.sendNoPointsMessage(player);texts.verifyNoMoreInteractions();
    }
    @Test void regenerationKeepsRemainderAndAppliesCharacterAttributes() {
        data.setInvestigationPoints(0);data.setLastInvestigationRegenMs(null);InvestigationPointService.regen(player);assertNotNull(data.getLastInvestigationRegenMs());assertEquals(0,data.getInvestigationPoints());
        long recent=System.currentTimeMillis();data.setLastInvestigationRegenMs(recent);InvestigationPointService.regen(player);assertEquals(recent,data.getLastInvestigationRegenMs());
        settings.setInvestigationRegenCycleMs(0);InvestigationPointService.regen(player);assertEquals(0,data.getInvestigationPoints());settings.setInvestigationRegenCycleMs(10000);settings.setWisdomWeight(.5);settings.setIntelligenceWeight(.5);character.getAttributeData().addModifier(new AttributeModifier("wisdom",1));character.getAttributeData().addModifier(new AttributeModifier("intelligence",1));
        long last=System.currentTimeMillis()-12500;data.setLastInvestigationRegenMs(last);InvestigationPointService.regen(player);assertEquals(2,data.getInvestigationPoints());assertEquals(last+10000,data.getLastInvestigationRegenMs());
        data.getCharacters().clear();data.setLastInvestigationRegenMs(System.currentTimeMillis()-10500);InvestigationPointService.regen(player);assertEquals(3,data.getInvestigationPoints());
    }
    @Test void regenerationCannotOverflowAndEraseAnExistingBalance() {
        settings.setInvestigationPointsMax(Integer.MAX_VALUE);settings.setInvestigationRegenCycleMs(1000);data.setInvestigationPoints(Integer.MAX_VALUE-1);data.setLastInvestigationRegenMs(System.currentTimeMillis()-5500);InvestigationPointService.regen(player);assertEquals(Integer.MAX_VALUE,data.getInvestigationPoints());
    }
    @Test void ageDecayNeverRestoresPreviouslyLostPotency() {
        var clue=clue();settings.setPotencyDecayPerHour(0);CluePotencyService.tickAgeDecay(List.of(clue));assertEquals(1,clue.getPotency());settings.setPotencyDecayPerHour(.5);clue.setSpawnedAtMs(System.currentTimeMillis()-3600000);CluePotencyService.tickAgeDecay(List.of(clue));assertEquals(.5,clue.getPotency(),.001);clue.setPotency(.1);CluePotencyService.tickAgeDecay(List.of(clue));assertEquals(.1,clue.getPotency());
        clue.setSpawnedAtMs(System.currentTimeMillis()-10800000);CluePotencyService.tickAgeDecay(List.of(clue));assertEquals(0,clue.getPotency());verify(manager).removeIfGone(clue);CluePotencyService.tickAgeDecay(List.of(clue));verify(manager,times(1)).removeIfGone(clue);
    }
    @Test void interactionRespectsDisabledAndNoLossPoliciesAndBoundsRandomLoss() {
        var clue=clue();settings.setTargetInteractEnabled(false);CluePotencyService.applyTargetInteractDisturbance(clue);assertEquals(1,clue.getPotency());settings.setTargetInteractEnabled(true);CluePotencyService.applyTargetInteractDisturbance(null);settings.setTargetInteractZeroLossChance(1);CluePotencyService.applyTargetInteractDisturbance(clue);assertEquals(1,clue.getPotency());
        settings.setTargetInteractZeroLossChance(0);settings.setTargetInteractLossMin(.2);settings.setTargetInteractLossMax(.4);CluePotencyService.applyTargetInteractDisturbance(clue);assertTrue(clue.getPotency()>=.6&&clue.getPotency()<=.8);settings.setTargetInteractLossMin(2);settings.setTargetInteractLossMax(2);CluePotencyService.applyTargetInteractDisturbance(clue);assertEquals(0,clue.getPotency());verify(manager).removeIfGone(clue);
        settings.setPotencyExpireWhenZero(false);CluePotencyService.applyTargetInteractDisturbance(clue);verify(manager,times(1)).removeIfGone(clue);
    }
    @Test void footTrafficLimitsLossPerWindowAndSkipsKnownClues() {
        var clue=clue();settings.setFootTrafficEnabled(false);CluePotencyService.tickFootTraffic(List.of(clue),List.of(player));assertEquals(0,clue.getFootTrafficEventsThisHour());settings.setFootTrafficEnabled(true);settings.setFootTrafficChancePerCheck(1);settings.setFootTrafficLossMin(.2);settings.setFootTrafficLossMax(.2);settings.setFootTrafficMaxEventsPerHour(1);
        CluePotencyService.tickFootTraffic(List.of(clue),List.of(player));assertEquals(.8,clue.getPotency());assertEquals(1,clue.getFootTrafficEventsThisHour());assertTrue(clue.getFootTrafficWindowStartMs()>0);CluePotencyService.tickFootTraffic(List.of(clue),List.of(player));assertEquals(.8,clue.getPotency());
        clue.setFootTrafficWindowStartMs(System.currentTimeMillis()-3600001);clue.markDiscovered(UUID.fromString(character.getId()));CluePotencyService.tickFootTraffic(List.of(clue),List.of(player));assertEquals(0,clue.getFootTrafficEventsThisHour());settings.setFootTrafficOnlyUndiscovered(false);CluePotencyService.tickFootTraffic(List.of(clue),List.of(player));assertEquals(.6,clue.getPotency(),.0001);
    }
    @Test void footTrafficRequiresOnlineNearbyActiveCharactersAndNonzeroChance() {
        var clue=clue();settings.setFootTrafficChancePerCheck(0);CluePotencyService.tickFootTraffic(List.of(clue),List.of(player));assertEquals(1,clue.getPotency());settings.setFootTrafficChancePerCheck(1);
        var offline=server.addPlayer();offline.disconnect();var elsewhere=server.addPlayer();elsewhere.teleport(new Location(server.addSimpleWorld("other"),0,65,0));var absent=server.addPlayer();var inactive=server.addPlayer();players.when(() -> PlayerManager.get(inactive)).thenReturn(new PlayerData(inactive));
        player.teleport(new Location(world,100,65,0));CluePotencyService.tickFootTraffic(List.of(clue),Arrays.asList(null,offline,elsewhere,absent,inactive,player));assertEquals(1,clue.getPotency());
        var unlinked=new SpawnedClue(UUID.randomUUID(),world.getName(),0,65,0,"trace",Long.MAX_VALUE,player.getUniqueId());var gone=clue();gone.setPotency(0);var faint=clue();faint.setPotency(.05);var unknown=new SpawnedClue(UUID.randomUUID(),"absent",0,65,0,"trace",Long.MAX_VALUE,player.getUniqueId(),0,65,0);CluePotencyService.tickFootTraffic(List.of(unlinked,gone,faint,unknown),List.of(player));assertEquals(0,clue.getFootTrafficEventsThisHour());
    }
    @Test void discoveryChanceIsBoundedAndUsesStatsToolAndPotency() {
        character.getAttributeData().addModifier(new AttributeModifier("wisdom",2));character.getAttributeData().addModifier(new AttributeModifier("intelligence",3));settings.setWisdomWeight(.1);settings.setIntelligenceWeight(.2);assertEquals(.55,ClueDiscoveryService.computeChance(character,.2,.1,.5),.0001);assertEquals(0,ClueDiscoveryService.computeChance(character,-10,0,1));assertEquals(1,ClueDiscoveryService.computeChance(character,10,0,1));
    }
    @Test void discoveryHonoursGatesAndRefreshesViewerOnlyOnFirstSuccess() {
        var clue=clue();settings.setPassiveDiscoveryEnabled(false);assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,character,clue));settings.setActiveDiscoveryEnabled(false);assertFalse(ClueDiscoveryService.tryActiveDiscovery(player,character,clue,null));settings.setPassiveDiscoveryEnabled(true);settings.setActiveDiscoveryEnabled(true);settings.setPassiveBaseChance(0);assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,character,clue));
        assertFalse(ClueDiscoveryService.tryPassiveDiscovery(null,character,clue));assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,null,clue));assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,character,null));clue.setPotency(0);assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,character,clue));clue.setPotency(.01);assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,character,clue));clue.setPotency(1);
        settings.setActiveBaseChance(1);settings.setMessageDiscovered("Found");assertTrue(ClueDiscoveryService.tryActiveDiscovery(player,character,clue,null));assertTrue(clue.isDiscoveredBy(UUID.fromString(character.getId())));assertFalse(ClueDiscoveryService.tryActiveDiscovery(player,character,clue,null));verify(manager).markDirty();verify(visuals).refreshViewer(player);texts.verify(() -> RPTexts.send(player,"Found"));
        settings.setPassiveBaseChance(1);settings.setMessageDiscovered("");assertTrue(ClueDiscoveryService.tryPassiveDiscovery(player,character,clue()));texts.verifyNoMoreInteractions();
    }
    @Test void legacyInvalidCharacterIdsCannotDiscoverButStillDisturbNearbyClues() {
        var cfg=new org.bukkit.configuration.file.YamlConfiguration();cfg.set("name","Human");var race=new net.tfminecraft.rpcharacters.objects.races.Race("human",cfg);
        for(String id:Arrays.asList(null,"old-character")) {
            var legacy=new RPCharacter(player,id,"Legacy",true,net.tfminecraft.rpcharacters.enums.Status.ALIVE,race,new ArrayList<>(),null);
            assertFalse(ClueDiscoveryService.tryPassiveDiscovery(player,legacy,clue()));
            if(id!=null){data.getCharacters().clear();data.getCharacters().add(legacy);settings.setFootTrafficChancePerCheck(1);settings.setFootTrafficLossMin(.2);settings.setFootTrafficLossMax(.2);var trace=clue();CluePotencyService.tickFootTraffic(List.of(trace),List.of(player));assertEquals(.8,trace.getPotency());}
        }
        var lensConfig=new org.bukkit.configuration.file.YamlConfiguration();lensConfig.set("discovery-bonus",1);settings.setActiveBaseChance(0);assertTrue(ClueDiscoveryService.tryActiveDiscovery(player,character,clue(),new MagnifyingGlassDefinition("lens",lensConfig)));
    }

}
