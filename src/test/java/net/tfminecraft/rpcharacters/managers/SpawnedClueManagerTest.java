package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.clues.discovery.*;
import net.tfminecraft.rpcharacters.database.SpawnedClueDatabase;
import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.utils.ClueHologram;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.world.*;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;

class SpawnedClueManagerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    ServerMock server; World world; RPCharacters plugin; RuntimeTestState state; SpawnedClueManager manager;
    final List<AutoCloseable> mocks=new ArrayList<>(); final List<Runnable> timers=new ArrayList<>(), queued=new ArrayList<>();
    MockedStatic<SpawnedClueDatabase> database; MockedStatic<Bukkit> bukkit; MockedStatic<PlayerManager> players;
    MockedStatic<ClueHologram> holograms; MockedStatic<CluePotencyService> potency; MockedStatic<ClueDiscoveryService> discovery; MockedStatic<InvestigationPointService> points;
    ClueDiscoveryVisualManager visuals; BukkitScheduler scheduler;
    @BeforeEach void setup() {
        server=MockBukkit.mock();world=server.addSimpleWorld("clues");state=new RuntimeTestState(RPCharacters.class,SpawnedClueManager.class);
        plugin=mock(RPCharacters.class);RPCharacters.plugin=plugin;when(plugin.getName()).thenReturn("RPCharacters");when(plugin.namespace()).thenReturn("rpcharacters");manager=new SpawnedClueManager();
        var settings=new ClueDiscoverySettings();settings.setPassiveRadius(10);settings.setPassiveIntervalSeconds(0);Cache.spawnedClueParticleInterval=0;
        boundary(ClueDiscoveryLoader.class).when(ClueDiscoveryLoader::getSettings).thenReturn(settings);
        visuals=mock(ClueDiscoveryVisualManager.class);boundary(ClueDiscoveryVisualManager.class).when(ClueDiscoveryVisualManager::get).thenReturn(visuals);
        database=boundary(SpawnedClueDatabase.class);database.when(SpawnedClueDatabase::loadAll).thenReturn(List.of());database.when(() -> SpawnedClueDatabase.trySaveAll(anyCollection())).thenReturn(true);holograms=boundary(ClueHologram.class);
        players=boundary(PlayerManager.class);potency=boundary(CluePotencyService.class);discovery=boundary(ClueDiscoveryService.class);points=boundary(InvestigationPointService.class);
        scheduler=mock(BukkitScheduler.class);when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenAnswer(c -> {timers.add(c.getArgument(1));return mock(BukkitTask.class);});
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(c -> {queued.add(c.getArgument(1));return mock(BukkitTask.class);});
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);mocks.add(bukkit);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    }
    @AfterEach void restore() throws Exception {for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();state.close();MockBukkit.unmock();}
    <T> MockedStatic<T> boundary(Class<T> type) {var m=mockStatic(type);mocks.add(m);return m;}
    SpawnedClue clue(double x, double z) {return new SpawnedClue(UUID.randomUUID(),world.getName(),x,65,z,"clue",Long.MAX_VALUE,UUID.randomUUID());}
    SpawnedClue linked(String worldName,int x,int y,int z) {return new SpawnedClue(UUID.randomUUID(),worldName,0,65,0,"linked",Long.MAX_VALUE,UUID.randomUUID(),x,y,z);}

    @Test void registryFindsNearbyLiveCluesWithoutDuplicatingRegistrations() {
        assertSame(SpawnedClueManager.get(),SpawnedClueManager.get());var near=clue(1,1);var far=clue(100,100);var depleted=clue(2,2);depleted.setPotency(0);
        manager.register(near);manager.register(near);manager.register(far);manager.register(depleted);assertSame(near,manager.get(near.getId()));assertEquals(3,manager.getAllClues().size());
        var center=new Location(world,0,65,0);assertEquals(List.of(near),manager.getCluesNear(center,10));assertTrue(manager.getCluesNear(null,10).isEmpty());assertTrue(manager.getCluesNear(new Location(null,0,0,0),10).isEmpty());assertTrue(manager.getCluesNear(center,0).isEmpty());
        assertTrue(manager.hasClueAt(new Location(world,1.9,65.9,1.9)));assertFalse(manager.hasClueAt(null));assertFalse(manager.hasClueAt(new Location(null,0,0,0)));assertFalse(manager.hasClueAt(new Location(world,2,65,2)));assertFalse(manager.hasClueAt(new Location(world,1,64,1)));assertFalse(manager.hasClueAt(new Location(world,1,65,2)));
        assertFalse(manager.hasClueAt(new Location(server.addSimpleWorld("other"),1,65,1)));manager.spawnVisuals(near);
    }

    @Test void replacingAnIdMovesItsChunkIndexAndCannotAffectOldChunkEvents() {
        var original=clue(0,0);manager.register(original);var moved=new SpawnedClue(original.getId(),world.getName(),160,65,160,"moved",Long.MAX_VALUE,UUID.randomUUID());manager.register(moved);
        manager.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(0,0),false));verify(visuals,never()).removeClue(moved.getId());
        assertEquals(List.of(moved),manager.getCluesNear(new Location(world,160,65,160),3));
    }

    @Test void blockLinkedQueriesMatchAllCoordinatesAndWorld() {
        var target=linked(world.getName(),1,2,3);var otherY=linked(world.getName(),1,4,3);var otherZ=linked(world.getName(),1,2,4);var otherX=linked(world.getName(),2,2,3);var otherWorld=linked("elsewhere",1,2,3);
        for(var clue:List.of(target,otherX,otherY,otherZ,otherWorld,clue(10,10)))manager.register(clue);
        var block=new Location(world,1.1,2.2,3.3);assertEquals(List.of(target),manager.getCluesLinkedToBlock(block));assertTrue(manager.getCluesLinkedToBlock(null).isEmpty());assertTrue(manager.getCluesLinkedToBlock(new Location(null,0,0,0)).isEmpty());
        assertEquals(1,manager.clearLinkedToBlock(block));assertNull(manager.get(target.getId()));assertEquals(0,manager.clearLinkedToBlock(block));
    }

    @Test void removalCleansBothVisualStoresAndEmptyOrSharedIndexes() {
        var one=clue(0,0);var two=clue(1,1);manager.register(one);manager.register(two);manager.remove(null);manager.removeVisuals(null);manager.remove(one);
        assertNull(manager.get(one.getId()));assertSame(two,manager.get(two.getId()));holograms.verify(() -> ClueHologram.remove(one));verify(visuals,atLeastOnce()).removeClue(one.getId());
        assertEquals(1,manager.clearInRadius(new Location(world,0,65,0),3));assertTrue(manager.getAllClues().isEmpty());manager.remove(two);
        assertEquals(0,manager.clearInRadius(null,3));assertEquals(0,manager.clearInRadius(new Location(null,0,0,0),3));assertEquals(0,manager.clearInRadius(new Location(world,0,0,0),0));
    }

    @Test void expiryWaitsForLoadedChunkAndLoadEventRefreshesOnlyLocalViewers() {
        var expired=spy(clue(0,0));doReturn(true).when(expired).shouldRemove();doReturn(false).when(expired).isChunkLoaded();manager.register(expired);manager.removeIfGone(null);manager.removeIfGone(clue(1,1));manager.removeIfGone(expired);assertSame(expired,manager.get(expired.getId()));
        manager.processCluesInChunk("absent",0,0);var local=server.addPlayer();local.teleport(new Location(world,1,65,1));var remote=server.addPlayer();remote.teleport(new Location(world,32,65,32));var differentZ=server.addPlayer();differentZ.teleport(new Location(world,1,65,32));
        doReturn(true).when(expired).isChunkLoaded();manager.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0,0),false));assertNull(manager.get(expired.getId()));verify(visuals).refreshViewer(local);verify(visuals,never()).refreshViewer(remote);verify(visuals,never()).refreshViewer(differentZ);
        manager.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(0,0),false));var live=clue(0,0);manager.register(live);manager.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(0,0),false));verify(visuals).removeClue(live.getId());
    }

    @Test void loadingStartsDirtyOnlyAutosaveAndShutdownClearsVisualState() {
        var clue=clue(0,0);clue.getDisplayEntityIds().add(UUID.randomUUID());clue.setVisualsSpawned(true);manager.register(clue(100,100));database.when(SpawnedClueDatabase::loadAll).thenReturn(List.of(clue));manager.loadAllFromDisk();assertEquals(List.of(clue),new ArrayList<>(manager.getAllClues()));verify(visuals).cleanupLegacyOrphans(anyCollection());
        assertEquals(1,timers.size());timers.getFirst().run();database.verify(() -> SpawnedClueDatabase.trySaveAll(anyCollection()),times(1));timers.getFirst().run();database.verify(() -> SpawnedClueDatabase.trySaveAll(anyCollection()),times(1));
        manager.markDisplayDirty();timers.getFirst().run();database.verify(() -> SpawnedClueDatabase.trySaveAll(anyCollection()),times(2));manager.shutdown();assertFalse(clue.isVisualsSpawned());assertTrue(clue.getDisplayEntityIds().isEmpty());verify(visuals).shutdown();database.verify(() -> SpawnedClueDatabase.trySaveAll(anyCollection()),times(3));
    }

    @Test void scheduledDiscoveryRegeneratesEveryoneAndDiscoversOnlyForActiveCharacters() {
        var absent=server.addPlayer();var inactive=server.addPlayer();var inconsistent=server.addPlayer();var active=server.addPlayer();var pd=mock(PlayerData.class);players.when(() -> PlayerManager.get(inactive)).thenReturn(pd);
        var empty=mock(PlayerData.class);when(empty.hasActiveCharacter()).thenReturn(true);players.when(() -> PlayerManager.get(inconsistent)).thenReturn(empty);
        var data=mock(PlayerData.class);var character=mock(RPCharacter.class);when(data.hasActiveCharacter()).thenReturn(true);when(data.getActiveCharacter()).thenReturn(character);players.when(() -> PlayerManager.get(active)).thenReturn(data);active.teleport(new Location(world,0,65,0));
        var clue=clue(1,1);manager.register(clue);manager.startTicks();assertEquals(3,timers.size());timers.get(0).run();timers.get(1).run();timers.get(2).run();
        for(Player player:List.of(absent,inactive,inconsistent,active)){points.verify(() -> InvestigationPointService.regen(player));verify(visuals).tickParticles(player);}
        discovery.verify(() -> ClueDiscoveryService.tryPassiveDiscovery(active,character,clue));verify(visuals).refreshViewer(active);potency.verify(() -> CluePotencyService.tickAgeDecay(anyCollection()));potency.verify(() -> CluePotencyService.tickFootTraffic(anyCollection(),anyList()));
    }

    @Test void asynchronousVisualCleanupIsDispatchedToMainThread() {
        var clue=clue(0,0);manager.register(clue);bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);manager.removeVisuals(clue);manager.removeAllVisuals();assertEquals(2,queued.size());holograms.verifyNoInteractions();queued.forEach(Runnable::run);holograms.verify(() -> ClueHologram.remove(clue),times(2));verify(visuals).shutdown();
    }

    @Test void failedAutosaveRetainsDirtyDataForTheNextTick() throws Exception {
        database.close();mocks.remove(database);when(plugin.getDataFolder()).thenReturn(directory.toFile());
        manager.loadAllFromDisk();manager.register(clue(0,0));
        java.nio.file.Files.writeString(directory.resolve("data"),"blocked");timers.getFirst().run();
        java.nio.file.Files.delete(directory.resolve("data"));timers.getFirst().run();
        assertTrue(java.nio.file.Files.exists(directory.resolve("data/spawned-clues.json")),"Failed autosave must retry without another gameplay mutation");
    }

    @Test void nonFiniteRadiusCannotMatchOrDeleteAnything() {
        manager.register(clue(0,0));var center=new Location(world,0,65,0);
        for(double radius:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)){assertTrue(manager.getCluesNear(center,radius).isEmpty());assertEquals(0,manager.clearInRadius(center,radius));}assertEquals(1,manager.getAllClues().size());
    }
}
