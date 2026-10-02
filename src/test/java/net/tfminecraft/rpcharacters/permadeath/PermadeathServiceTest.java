package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.clues.discovery.ClueAdminModeService;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;
import net.tfminecraft.rpcharacters.pvp.PvpStartSessions;
import net.tfminecraft.rpcharacters.pvp.PvpStrikeService;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

class PermadeathServiceTest extends PermadeathRuntimeFixture {
    @Test void riskCountsHealingAndPermanentInjuriesButNotOtherTraits() throws Exception {
        chance(12);character.addTrait(healing);character.addTrait(permanent);character.addTrait(trait("wise","background",false));character.addTrait(trait("unkeyed",null,false));
        var risk=PermadeathService.computeRisk(character);assertEquals(2,risk.getInjuryCount());assertEquals(24,risk.getChancePercent());assertEquals(12,risk.getChancePerInjury());
        String lore=ChatColor.stripColor(String.join("\n",risk.toLoreLines()));assertTrue(lore.contains("24%"));assertTrue(lore.contains("+12% per injury"));assertTrue(lore.contains("Injury count: 2"));
        chance(0);assertTrue(ChatColor.stripColor(String.join("\n",PermadeathService.computeRisk(character).toLoreLines())).contains("0%"));
    }

    @Test void configuredLargeRiskCannotOverflowAndDisablePermadeath() throws Exception {
        chance(Integer.MAX_VALUE);character.addTrait(healing);character.addTrait(permanent);
        assertTrue(PermadeathService.computeRisk(character).getChancePercent()>=100,"Two injuries must not wrap a positive configured death chance below zero");
        die();assertEquals(Status.DEAD,character.getStatus());
    }

    @Test void aFirstZoneDeathAddsOneHealingInjuryAndShowsItsTitle() throws Exception {
        pool(healing);die();assertEquals(List.of(healing),character.getTraits());assertTrue(text().contains("You gained"));verify(player).sendTitle(contains("wound"),eq(" "),eq(10),eq(400),eq(20));verify(manager).savePlayer(player);
    }

    @Test void emptyPoolWarnsWithoutChangingTheCharacter() {
        die();assertTrue(character.getTraits().isEmpty());verify(logger).warning(contains("Injury pool empty or misconfigured for Aria"));verify(manager,never()).savePlayer(any());
    }

    @Test void oneZoneDeathUpgradesOnlyTheFirstHealingInjury() throws Exception {
        Trait second=trait("burn","injury",true);Trait scar=trait("scar","injury",false);progression(healing,permanent,second,scar);character.addTrait(healing);character.addTrait(second);
        die();assertEquals(List.of(second,permanent),character.getTraits());assertTrue(text().contains("maimed"));assertEquals(Status.ALIVE,character.getStatus());
    }

    @Test void missingProgressionPreservesTheExistingHealingInjury() {
        character.addTrait(healing);long expiresAt=character.getTraitState(healing.getId()).getExpiresAtMs();die();
        assertEquals(List.of(healing),character.getTraits(),"A missing mapping must not silently heal the player");assertEquals(expiresAt,character.getTraitState(healing.getId()).getExpiresAtMs());verify(logger).warning(contains("No permanent progression target"));
    }

    @Test void removedProgressionTargetPreservesTheExistingHealingInjury() throws Exception {
        progression(healing,permanent);TraitLoader.oList.remove(permanent);character.addTrait(healing);die();
        assertEquals(List.of(healing),character.getTraits(),"A trait removed during reload must not destroy the existing injury");verify(logger).warning(contains("not found while converting"));
    }

    @Test void anAlreadyOwnedPermanentTargetIsNotDuplicated() throws Exception {
        progression(healing,permanent);character.addTrait(healing);character.addTrait(permanent);die();assertEquals(List.of(permanent),character.getTraits());
    }

    @Test void zeroRiskDoesNotPermakillAndMaximumRiskDoes() throws Exception {
        character.addTrait(permanent);pool(healing);die();assertEquals(Status.ALIVE,character.getStatus());assertTrue(character.getTraits().contains(healing));chance(100);die();
        assertEquals(Status.DEAD,character.getStatus());assertTrue(PermadeathService.isAwaitingPermakillRespawn(player));assertTrue(text().contains("permadeath zone"));zones.verify(()->PermadeathZoneListener.clearZoneTracking(player));verify(manager).releaseFreeze(player);
    }

    @Test void fractionalRiskChoosesOnlyADeathOrOneInjuryConsequence() throws Exception {
        chance(50);character.addTrait(permanent);pool(healing);die();
        assertTrue(character.getStatus()==Status.DEAD || character.getTraits().equals(List.of(permanent,healing)));
        assertFalse(character.getStatus()==Status.DEAD && character.getTraits().contains(healing));
    }

    @Test void adminDeathConsumesPvpStartButDoesNotApplyAnyConsequence() {
        PvpStartSessions.begin(List.of(player.getUniqueId()),System.currentTimeMillis(),60_000);admins.when(()->ClueAdminModeService.isEnabled(player)).thenReturn(true);die();assertFalse(PvpStartSessions.isActive(player.getUniqueId(),System.currentTimeMillis()));strikes.verifyNoInteractions();assertTrue(character.getTraits().isEmpty());
    }

    @Test void startedBattlesAndHandledPvpDeathsSkipZoneConsequences() {
        battles.when(()->PermadeathBattleExemption.isInStartedBattle(player)).thenReturn(true);die();strikes.verifyNoInteractions();battles.when(()->PermadeathBattleExemption.isInStartedBattle(player)).thenReturn(false);
        PvpStartSessions.begin(List.of(player.getUniqueId()),System.currentTimeMillis(),60_000);strikes.when(()->PvpStrikeService.handleDeath(player,true)).thenReturn(true);die();strikes.verify(()->PvpStrikeService.handleDeath(player,true));area.verifyNoInteractions();assertTrue(character.getTraits().isEmpty());
    }

    @Test void deathsOutsideZonesOrBeforePlayerDataLoadsAreIgnored() {
        area.when(()->PermadeathAreaLookup.getPermadeathZoneAt(eq(player),any(Location.class))).thenReturn(null);die();assertTrue(character.getTraits().isEmpty());inZone();loaded.clear();die();loaded.put(player,new PlayerData(player));die();verify(manager,never()).savePlayer(any());
    }

    @Test void randomHealingInjuriesExcludeAlreadyOwnedEntriesAcrossLocales() throws Exception {
        Trait uppercase=trait("INJURED","injury",true);pool(uppercase);Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertTrue(PermadeathService.applyRandomInjury(player,character));assertFalse(PermadeathService.applyRandomInjury(player,character));assertNull(PermadeathService.giveRandomInjury(player,character));assertEquals(List.of(uppercase),character.getTraits());
    }

    @Test void permanentInjuryPrefersUpgradingAHealingInjuryThenRunsOut() throws Exception {
        progression(healing,permanent);character.addTrait(healing);assertSame(permanent,PermadeathService.givePermanentInjury(player,character));assertEquals(List.of(permanent),character.getTraits());assertNull(PermadeathService.givePermanentInjury(player,character));assertFalse(PermadeathService.applyRandomPermanentInjury(player,character));
    }

    @Test void permanentInjurySkipsUnmappedAndAlreadyOwnedTargetsBeforeFallingBack() throws Exception {
        Trait second=trait("burn","injury",true);Trait scar=trait("scar","injury",false);progression(second,scar);character.addTrait(healing);assertSame(scar,PermadeathService.givePermanentInjury(player,character));assertEquals(List.of(healing,scar),character.getTraits());
        character.addTrait(second);assertNull(PermadeathService.givePermanentInjury(player,character));assertTrue(character.getTraits().contains(second));
    }

    @Test void missingReloadedPermanentTraitsAreSkippedForBothUpgradeAndRandomSelection() throws Exception {
        progression(healing,permanent);TraitLoader.oList.remove(permanent);character.addTrait(healing);assertNull(PermadeathService.givePermanentInjury(player,character));assertFalse(PermadeathService.applyRandomPermanentInjury(player,character));assertEquals(List.of(healing),character.getTraits());
    }

    @Test void randomPermanentSelectionDeduplicatesProgressionTargets() throws Exception {
        Trait second=trait("burn","injury",true);progression(healing,permanent,second,permanent);assertTrue(PermadeathService.applyRandomPermanentInjury(player,character));assertEquals(List.of(permanent),character.getTraits());assertFalse(PermadeathService.applyRandomPermanentInjury(player,character));
    }

    @Test void deadCharactersAndCancelledEventsHaveNoDeathSideEffects() {
        character.setStatus(Status.DEAD);assertFalse(PermadeathService.killCharacter(player,character));assertTrue(events.isEmpty());character.setStatus(Status.ALIVE);cancelEvents=true;assertFalse(PermadeathService.killCharacter(player,character,PermakillCause.COMMAND));assertEquals(Status.ALIVE,character.getStatus());assertTrue(character.isActive());verify(manager,never()).savePlayer(any());assertTrue(tasks.isEmpty());
    }

    @Test void killingAnInactiveCharacterPersistsItWithoutKillingTheEntity() {
        RPCharacter inactive=character("Other",false);data.addCharacter(inactive);assertTrue(PermadeathService.killCharacter(player,inactive));assertEquals(Status.DEAD,inactive.getStatus());assertTrue(character.isActive());assertFalse(PermadeathService.isAwaitingPermakillRespawn(player));assertTrue(tasks.isEmpty());verify(manager).savePlayer(player);verify(manager).reevaluateFreeze(player);roster.verify(()->RosterSyncService.pushRosterForPlayer(player));
    }

    @Test void killingTheActiveCharacterSelectsTheFirstLivingReplacement() {
        RPCharacter old=character("Already dead",false);old.setStatus(Status.DEAD);RPCharacter next=character("Bryn",false);data.addCharacter(old);data.addCharacter(next);assertTrue(PermadeathService.killCharacter(player,character));
        assertEquals(Status.DEAD,character.getStatus());assertFalse(character.isActive());assertSame(next,data.getActiveCharacter());wardrobe.verify(()->WardrobeService.refreshActiveAsync(player));verify(player).sendTitle(contains("Aria"),contains("Bryn"),eq(10),eq(400),eq(20));assertEquals(1,tasks.size());
        runTask();verify(player).setHealth(0);die();strikes.verifyNoInteractions();assertTrue(next.getTraits().isEmpty());
    }

    @Test void activeCharacterWithMissingPlayerDataStillDiesAndSaves() {
        loaded.clear();assertTrue(PermadeathService.killCharacter(player,character,PermakillCause.COMMAND));assertFalse(character.isActive());verify(player).sendTitle(anyString(),contains("no active character"),eq(10),eq(400),eq(20));verify(manager).savePlayer(player);
    }

    @Test void entityDeathTaskIsNotScheduledDuringShutdown() {
        when(plugin.isEnabled()).thenReturn(false);assertTrue(PermadeathService.killCharacter(player,character));assertTrue(tasks.isEmpty());assertTrue(PermadeathService.consumePendingPermakillSounds(player));
    }

    @Test void disconnectedPlayerClearsTheScheduledDeathSuppression() {
        assertTrue(PermadeathService.killCharacter(player,character));when(player.isOnline()).thenReturn(false);runTask();verify(player,never()).setHealth(anyDouble());die();strikes.verify(()->PvpStrikeService.handleDeath(player,false));
    }

    @Test void anAlreadyDeadPlayerClearsTheScheduledDeathSuppression() {
        assertTrue(PermadeathService.killCharacter(player,character));when(player.isDead()).thenReturn(true);runTask();verify(player,never()).setHealth(anyDouble());die();strikes.verify(()->PvpStrikeService.handleDeath(player,false));
    }

    @Test void aStrikeAppliedAfterRespawnPlaysSoundsWithoutKillingAgain() {
        Player killer=mock(Player.class);assertTrue(PermadeathService.killCharacter(player,character,PermakillCause.STRIKES,killer,false));assertTrue(tasks.isEmpty());assertFalse(PermadeathService.isAwaitingPermakillRespawn(player));assertTrue(text().contains("final strike"));assertSounds();
        var event=events.getFirst();assertSame(player,event.getPlayer());assertSame(character,event.getCharacter());assertSame(killer,event.getKiller());assertEquals(PermakillCause.STRIKES,event.getCause());assertFalse(event.isFromPermadeathZone());assertSame(CharacterPermakillEvent.getHandlerList(),event.getHandlers());
    }

    @Test void aStrikeDuringDeathMarksWorldSpawnAndDefersSounds() {
        when(player.isDead()).thenReturn(true);assertTrue(PermadeathService.killCharacter(player,character,PermakillCause.STRIKES,player));assertTrue(PermadeathService.isAwaitingPermakillRespawn(player));assertTrue(PermadeathService.consumePendingPermadeathRespawn(player));assertTrue(PermadeathService.consumePendingPermakillSounds(player));assertTrue(tasks.isEmpty());verify(manager,never()).reevaluateFreeze(any());
    }

    @Test void ordinaryDeathDoesNotForceWorldSpawnEvenWhenTheEntityIsDead() {
        when(player.isDead()).thenReturn(true);assertTrue(PermadeathService.killCharacter(player,character));assertFalse(PermadeathService.consumePendingPermadeathRespawn(player));assertTrue(PermadeathService.consumePendingPermakillSounds(player));assertTrue(tasks.isEmpty());
    }

    @Test void respawnUsesConfiguredSpawnOnceAndReleasesFreezeBeforeRechecking() {
        var configured=new Location(world,12,80,9,45,20);PermadeathZoneLoader.saveWorldSpawn(configured);PermadeathService.markPendingPermadeathRespawn(player);PermadeathService.markPendingPermakillSounds(player);var event=respawn();
        PermadeathService.handlePlayerRespawn(player,event);assertEquals(configured,event.getRespawnLocation());assertNotSame(configured,event.getRespawnLocation());zones.verify(()->PermadeathZoneListener.silentZoneSync(player,configured));assertSounds();var order=inOrder(manager);order.verify(manager).releaseFreeze(player);order.verify(manager).reevaluateFreeze(player);assertFalse(PermadeathService.isAwaitingPermakillRespawn(player));
        PermadeathService.handlePlayerRespawn(player,event);verify(manager,times(1)).releaseFreeze(player);
    }

    @Test void respawnFallsBackToTheFirstWorldSpawnOrRetainsTheEventLocation() {
        assertEquals(world.getSpawnLocation(),WorldSpawnService.getSpawn());PermadeathService.markPendingPermadeathRespawn(player);bukkit.when(Bukkit::getWorlds).thenReturn(List.of());assertNull(WorldSpawnService.getSpawn());var event=respawn();var original=event.getRespawnLocation().clone();PermadeathService.handlePlayerRespawn(player,event);assertEquals(original,event.getRespawnLocation());zones.verify(()->PermadeathZoneListener.silentZoneSync(player,original));
    }

    @Test void soundsAloneAreConsumedWithoutAForcedRespawnOrFreezeChange() {
        PermadeathService.markPendingPermakillSounds(player);assertTrue(PermadeathService.isAwaitingPermakillRespawn(player));var event=respawn();var original=event.getRespawnLocation().clone();PermadeathService.handlePlayerRespawn(player,event);assertEquals(original,event.getRespawnLocation());assertSounds();verifyNoInteractions(manager);assertFalse(PermadeathService.consumePendingPermakillSounds(player));
    }

    @Test void clearingPendingStateAlsoClearsSuppressionAndAllOneShotFlags() {
        assertTrue(PermadeathService.killCharacter(player,character));PermadeathService.markPendingPermadeathRespawn(player);assertTrue(PermadeathService.isAwaitingPermakillRespawn(player));PermadeathService.clearPendingPermadeathRespawn(player);assertFalse(PermadeathService.isAwaitingPermakillRespawn(player));assertFalse(PermadeathService.consumePendingPermadeathRespawn(player));assertFalse(PermadeathService.consumePendingPermakillSounds(player));die();strikes.verify(()->PvpStrikeService.handleDeath(player,false));
    }

    @Test void publicEventConstructorSupportsCancellationAndZoneIdentification() {
        var event=new CharacterPermakillEvent(player,character,PermakillCause.PERMADEATH_ZONE);assertNull(event.getKiller());assertTrue(event.isFromPermadeathZone());assertFalse(event.isCancelled());event.setCancelled(true);assertTrue(event.isCancelled());event.setCancelled(false);assertFalse(event.isCancelled());
    }
}

abstract class PermadeathRuntimeFixture {
    @TempDir Path folder;
    RuntimeTestState state;RPCharacters plugin;Logger logger;Player player;PlayerData data;RPCharacter character;Race race;Trait healing,permanent;PlayerManager manager;World world;PluginManager pluginManager;BukkitScheduler scheduler;
    final List<AutoCloseable> boundaries=new ArrayList<>();final Map<Player,PlayerData> loaded=new HashMap<>();final List<String> messages=new ArrayList<>();final Deque<Runnable> tasks=new ArrayDeque<>();final List<CharacterPermakillEvent> events=new ArrayList<>();boolean cancelEvents;
    MockedStatic<Bukkit> bukkit;MockedStatic<ClueAdminModeService> admins;MockedStatic<PermadeathBattleExemption> battles;MockedStatic<PvpStrikeService> strikes;MockedStatic<PermadeathAreaLookup> area;MockedStatic<PermadeathZoneListener> zones;MockedStatic<WardrobeService> wardrobe;MockedStatic<RosterSyncService> roster;
    @BeforeEach void setupPermadeath() throws Exception {
        var server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,TraitLoader.class,InjuryPoolLoader.class,InjuryProgressionLoader.class,PermadeathZoneLoader.class,PermadeathService.class,PvpStartSessions.class);Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();Cache.backgroundTraitTypes=new ArrayList<>();TraitLoader.oList=new ArrayList<>();
        plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.isEnabled()).thenReturn(true);RPCharacters.plugin=plugin;
        world=server.addSimpleWorld("permadeath");player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn("Aria");when(player.isOnline()).thenReturn(true);when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(new Location(world,1,64,2));doAnswer(call->{messages.add(call.getArgument(0));return null;}).when(player).sendMessage(anyString());
        manager=mock(PlayerManager.class);var root=boundary(RPCharacters.class);root.when(RPCharacters::getPlayerManager).thenReturn(manager);var players=boundary(PlayerManager.class);players.when(()->PlayerManager.get(any(Player.class))).thenAnswer(call->loaded.get(call.getArgument(0)));
        boundary(Database.class);boundary(AttributePointService.class);boundary(ProfessionIntegrator.class);boundary(MmoCorePlayerReady.class);boundary(CharacterLifecycle.class);boundaries.add(mockConstruction(Integrator.class));roster=boundary(RosterSyncService.class);wardrobe=boundary(WardrobeService.class);admins=boundary(ClueAdminModeService.class);battles=boundary(PermadeathBattleExemption.class);strikes=boundary(PvpStrikeService.class);area=boundary(PermadeathAreaLookup.class);zones=boundary(PermadeathZoneListener.class);
        pluginManager=mock(PluginManager.class);doAnswer(call->{if(call.getArgument(0) instanceof CharacterPermakillEvent event){events.add(event);event.setCancelled(cancelEvents);}return null;}).when(pluginManager).callEvent(any());scheduler=mock(BukkitScheduler.class);when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{tasks.add(call.getArgument(1));return mock(BukkitTask.class);});
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);boundaries.add(bukkit);bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);bukkit.when(()->Bukkit.getPlayerExact(anyString())).thenAnswer(call->player.getName().equals(call.getArgument(0))?player:null);
        var raceConfig=new YamlConfiguration();raceConfig.set("name","Human");race=new Race("human",raceConfig);character=character("Aria",true);data=new PlayerData(player);data.addCharacter(character);loaded.put(player,data);healing=trait("wound","injury",true);permanent=trait("maimed","INJURY",false);pool();progression();chance(0);inZone();
    }
    <T> MockedStatic<T> boundary(Class<T> type){var handle=mockStatic(type);boundaries.add(handle);return handle;}
    RPCharacter character(String name,boolean active){return new RPCharacter(player,UUID.randomUUID().toString(),name,active,Status.ALIVE,race,new ArrayList<>(),"HUMAN");}
    Trait trait(String id,String key,boolean temporary){var yaml=new YamlConfiguration();yaml.set("name",id);yaml.set("key",key);if(temporary)yaml.set("duration","1h");var trait=new Trait(id,yaml);TraitLoader.oList.add(trait);return trait;}
    void pool(Trait... traits) throws Exception {var yaml=new YamlConfiguration();for(Trait trait:traits)yaml.set("injuries."+trait.getId()+".weight",1);var path=folder.resolve("injuries.yml");yaml.save(path.toFile());new InjuryPoolLoader().load(path.toFile());}
    void progression(Trait... pairs) throws Exception {var yaml=new YamlConfiguration();for(int i=0;i<pairs.length;i+=2)yaml.set("progression."+pairs[i].getId(),pairs[i+1].getId());var path=folder.resolve("progression.yml");yaml.save(path.toFile());new InjuryProgressionLoader().load(path.toFile());}
    void chance(int chance) throws Exception {var path=folder.resolve("zones.yml");Files.writeString(path,"permadeath-chance-per-injury: "+chance+"\n");new PermadeathZoneLoader().load(path.toFile());}
    void inZone(){var zone=new PermadeathZoneDefinition("danger","Danger");area.when(()->PermadeathAreaLookup.getPermadeathZoneAt(eq(player),any(Location.class))).thenReturn(zone);}
    void die(){PermadeathService.handleDeath(player,player.getLocation());}
    void runTask(){assertFalse(tasks.isEmpty());tasks.removeFirst().run();}
    String text(){return ChatColor.stripColor(String.join("\n",messages));}
    PlayerRespawnEvent respawn(){return new PlayerRespawnEvent(player,new Location(world,9,65,9),false);}
    void assertSounds(){verify(player).playSound(player,"ambient.soul_sand_valley.mood",SoundCategory.MASTER,10f,1f);verify(player).playSound(player,"block.end_portal.spawn",SoundCategory.MASTER,10f,1f);}
    @AfterEach void cleanupPermadeath() throws Exception {try{for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close();if(state!=null)state.close();}finally{MockBukkit.unmock();}}
}
