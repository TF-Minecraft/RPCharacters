package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.simplefactions.events.*;

class PermadeathZoneRuntimeTest extends ZoneBridgeFixture {
    PermadeathZoneListener listener=new PermadeathZoneListener();MockedStatic<PermadeathAreaLookup> lookup;MockedStatic<TutorialService> tutorial;PermadeathZoneDefinition current;
    @BeforeEach void setupZone(){lookup=boundary(PermadeathAreaLookup.class);lookup.when(()->PermadeathAreaLookup.getPermadeathZoneAt(eq(player),any(Location.class))).thenAnswer(call->current);tutorial=boundary(TutorialService.class);}
    String title(){return ChatColor.stripColor(player.nextTitle());}
    @Test void stationaryMovementDoesNotQueryZonesButEachCoordinateChangeDoes(){var from=player.getLocation();listener.onPlayerMove(new PlayerMoveEvent(player,from,from.clone().add(.1,.1,.1)));lookup.verifyNoInteractions();for(var delta:List.of(new org.bukkit.util.Vector(1,0,0),new org.bukkit.util.Vector(0,1,0),new org.bukkit.util.Vector(0,0,1))){current=zone("REGION_I");PermadeathZoneListener.clearZoneTracking(player);listener.onPlayerMove(new PlayerMoveEvent(player,from,from.clone().add(delta)));assertEquals("Now entering Highlands",title());}tutorial.verify(()->TutorialService.show(player,TutorialService.PERMADEATH_ZONE),times(3));}
    @Test void nullMovementDestinationIsIgnoredSafely(){assertDoesNotThrow(()->listener.onPlayerMove(new PlayerMoveEvent(player,player.getLocation(),null)));lookup.verifyNoInteractions();}
    @Test void enteringSwitchingAndLeavingZonesEmitOneTitlePerTransition(){PermadeathZoneListener.syncFromLocation(null,player.getLocation());PermadeathZoneListener.syncFromLocation(player,null);lookup.verifyNoInteractions();current=zone("REGION_I");PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now entering Highlands",title());PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertNull(title());current=zone("lowlands");PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now leaving Highlands",title());assertEquals("Now entering Lowlands",title());current=null;PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now leaving Lowlands",title());PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertNull(title());}
    @Test void zoneTransitionsAreIndependentOfTurkishDefaultLocale(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));current=zone("REGION_I");PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now entering Highlands",title());current=null;PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now leaving Highlands",title());}
    @Test void silentSyncAlsoUsesLocaleIndependentTracking(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));current=zone("REGION_I");PermadeathZoneListener.silentZoneSync(player,player.getLocation());assertNull(title());current=null;PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now leaving Highlands",title());}
    @Test void silentSyncCanSetAndClearTrackingWithoutTitles(){current=zone("REGION_I");PermadeathZoneListener.silentZoneSync(player,player.getLocation());PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertNull(title());PermadeathZoneListener.silentZoneSync(player,null);PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now entering Highlands",title());current=null;PermadeathZoneListener.silentZoneSync(player,player.getLocation());PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertNull(title());}
    @Test void reloadingAwayThePreviousZoneDoesNotInventALeaveTitle() throws Exception {current=zone("REGION_I");PermadeathZoneListener.silentZoneSync(player,player.getLocation());loadZones("");current=zone("lowlands");PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertNull(title());}
    @Test void joinAndTeleportUseTheirActualDestinationAndQuitClearsTracking(){current=zone("REGION_I");listener.onPlayerJoin(new PlayerJoinEvent(player,"joined"));assertEquals("Now entering Highlands",title());var destination=new Location(world,8,70,9);current=null;listener.onPlayerTeleport(new PlayerTeleportEvent(player,player.getLocation(),destination));assertEquals("Now leaving Highlands",title());lookup.verify(()->PermadeathAreaLookup.getPermadeathZoneAt(player,destination));current=zone("REGION_I");PermadeathZoneListener.silentZoneSync(player,player.getLocation());try(var service=mockStatic(PermadeathService.class)){listener.onPlayerQuit(new PlayerQuitEvent(player,"bye"));service.verify(()->PermadeathService.clearPendingPermadeathRespawn(player));}PermadeathZoneListener.syncFromLocation(player,player.getLocation());assertEquals("Now entering Highlands",title());}
    @Test void deathsAndRespawnsDelegateTheActualPlayerAndLocation(){var death=mock(PlayerDeathEvent.class);when(death.getEntity()).thenReturn(player);var respawn=new PlayerRespawnEvent(player,new Location(world,5,70,5),false,false);var location=player.getLocation();try(var service=mockStatic(PermadeathService.class)){listener.onPlayerDeath(death);listener.onPlayerRespawn(respawn);service.verify(()->PermadeathService.handleDeath(player,location));service.verify(()->PermadeathService.handlePlayerRespawn(player,respawn));}}
    @Test void simpleFactionsEnterAndLeaveEventsResynchronizeFromThePlayerLocation(){var sf=new SimpleFactionsPermadeathListener();current=zone("REGION_I");sf.onEnterRegion(new PlayerEnterRegionEvent(player,"REGION_I","Highlands",null));assertEquals("Now entering Highlands",title());current=null;sf.onLeaveRegion(new PlayerLeaveRegionEvent(player,"REGION_I",null));assertEquals("Now leaving Highlands",title());}
    @Test void dependencyListenerIgnoresAbsentAndUnrelatedPlugins(){var dependency=new PermadeathDependencyListener();dependency.registerSimpleFactionsIfPresent();var other=mock(Plugin.class);when(other.getName()).thenReturn("OtherPlugin");dependency.onPluginEnable(new PluginEnableEvent(other));verify(pluginManager,never()).registerEvents(any(),any());}
    @Test void dependencyListenerRegistersOnceAndRetriesAfterRegistrationFailure(){when(pluginManager.isPluginEnabled("SimpleFactions")).thenReturn(true);var sf=mock(Plugin.class);when(sf.getName()).thenReturn("SimpleFactions");var dependency=new PermadeathDependencyListener();try(var bridge=mockStatic(SimpleFactionsRegionBridge.class)){doThrow(new IllegalStateException("registration failed")).doNothing().when(pluginManager).registerEvents(any(),eq(plugin));dependency.onPluginEnable(new PluginEnableEvent(sf));verify(logger).warning(contains("Failed to enable SimpleFactions"));dependency.registerSimpleFactionsIfPresent();dependency.registerSimpleFactionsIfPresent();verify(pluginManager,times(2)).registerEvents(isA(SimpleFactionsPermadeathListener.class),eq(plugin));bridge.verify(SimpleFactionsRegionBridge::init,times(3));}}
    @Test void incompatibleSimpleFactionsEventApiDoesNotBreakDependencyEnable(){when(pluginManager.isPluginEnabled("SimpleFactions")).thenReturn(true);doThrow(new NoClassDefFoundError("missing optional event class")).when(pluginManager).registerEvents(any(),any());try(var bridge=mockStatic(SimpleFactionsRegionBridge.class)){assertDoesNotThrow(()->new PermadeathDependencyListener().registerSimpleFactionsIfPresent());}}
}

abstract class ZoneBridgeFixture {
    @TempDir Path folder;ServerMock server;World world;PlayerMock player;RPCharacters plugin;Logger logger;PluginManager pluginManager;RuntimeTestState state;final List<AutoCloseable> mocks=new ArrayList<>();
    @BeforeEach void setupBridge() throws Exception {server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,PermadeathZoneLoader.class,PermadeathZoneListener.class,WorldGuardBridge.class,SimpleFactionsRegionBridge.class,PermadeathAreaLookup.class,PermadeathBattleExemption.class);world=server.addSimpleWorld("zones");player=server.addPlayer("Traveler");player.teleport(new Location(world,.5,70,.5));plugin=mock(RPCharacters.class);logger=mock(Logger.class);pluginManager=mock(PluginManager.class);var pluginServer=mock(Server.class);when(pluginServer.getPluginManager()).thenReturn(pluginManager);when(plugin.getServer()).thenReturn(pluginServer);when(plugin.getLogger()).thenReturn(logger);when(plugin.getName()).thenReturn("RPCharacters");when(plugin.namespace()).thenReturn("rpcharacters");when(plugin.isEnabled()).thenReturn(true);RPCharacters.plugin=plugin;var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);mocks.add(bukkit);bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);PermadeathBattleExemption.set(null);loadZones("permadeath-zones:\n  REGION_I:\n    name: Highlands\n  lowlands:\n    name: Lowlands\n");}
    void loadZones(String text) throws Exception {var file=folder.resolve("zones.yml");Files.writeString(file,text);new PermadeathZoneLoader().load(file.toFile());}
    PermadeathZoneDefinition zone(String id){return PermadeathZoneLoader.getZone(id);}
    <T> MockedStatic<T> boundary(Class<T> type){var mocked=mockStatic(type);mocks.add(mocked);return mocked;}
    @AfterEach void closeBridge() throws Exception {try{for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();if(state!=null)state.close();}finally{MockBukkit.unmock();}}
}
