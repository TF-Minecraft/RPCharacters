package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import org.bukkit.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;

class SpawnedClueDataTest {
    @TempDir Path directory;
    RuntimeTestState state; ServerMock server; World world; UUID owner = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock(); world=server.addSimpleWorld("clue-world");
        state=new RuntimeTestState(RPCharacters.class,ClueDiscoveryLoader.class);
        RPCharacters.plugin=mock(RPCharacters.class); when(RPCharacters.plugin.getDataFolder()).thenReturn(directory.toFile());
        when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("clue-data-test"));
        new ClueDiscoveryLoader().load(Files.writeString(directory.resolve("clues.yml"),"{}").toFile());
        Cache.spawnedClueTimerHours=24; Cache.spawnedClueVisualYOffset=2;
    }
    @AfterEach void restore() {state.close();MockBukkit.unmock();}
    Path file() {return directory.resolve("data/spawned-clues.json");}
    SpawnedClue clue(long expiry) {return new SpawnedClue(UUID.randomUUID(),world.getName(),-16.25,65.5,32.5,"A footprint — café",expiry,owner,2,64,4);}
    JSONArray stored() throws Exception {return (JSONArray)new JSONParser().parse(Files.readString(file()));}
    void write(JSONArray array) throws Exception {Files.createDirectories(file().getParent());Files.writeString(file(),array.toJSONString());}

    @Test void persistedCluesRoundTripPositionsPotencyDiscoveriesAndTraffic() throws Exception {
        var clue=clue(System.currentTimeMillis()+3600000);var discoverer=UUID.randomUUID();clue.markDiscovered(discoverer);
        clue.setPotency(.45);clue.setSpawnedAtMs(System.currentTimeMillis()-10000);clue.setFootTrafficEventsThisHour(2);clue.setFootTrafficWindowStartMs(12345);
        SpawnedClueDatabase.saveAll(List.of(clue,clue(0)));
        assertEquals(1,stored().size());var loaded=SpawnedClueDatabase.loadAll();assertEquals(1,loaded.size());var actual=loaded.getFirst();
        assertEquals(clue.getId(),actual.getId());assertEquals(owner,actual.getOwnerUuid());assertEquals(clue.getClueText(),actual.getClueText());
        assertEquals(clue.getAnchor(),actual.getAnchor());assertEquals(clue.getTargetCenter(),actual.getTargetCenter());assertEquals(.45,actual.getPotency());
        assertEquals(clue.getSpawnedAtMs(),actual.getSpawnedAtMs());assertEquals(clue.getDiscoveredByCharacter(),actual.getDiscoveredByCharacter());
        assertEquals(2,actual.getFootTrafficEventsThisHour());assertEquals(12345,actual.getFootTrafficWindowStartMs());
    }

    @Test void legacyRowsEstimatePotencyAndIgnoreInvalidOptionalIdentifiers() throws Exception {
        var clue=clue(System.currentTimeMillis()+3600000);SpawnedClueDatabase.saveAll(List.of(clue));var data=stored();var row=(JSONObject)data.getFirst();
        row.remove("potency");row.remove("spawnedAtMs");row.remove("targetBlockX");row.remove("targetBlockY");row.remove("targetBlockZ");
        var display=UUID.randomUUID();var ids=new JSONArray();ids.addAll(List.of(display.toString(),"bad-id"));row.put("displayEntityIds",ids);
        var discoverer=UUID.randomUUID();row.put("discoveredBy",new JSONObject(Map.of(discoverer.toString(),123L,"bad-id",456L)));
        write(data);var actual=SpawnedClueDatabase.loadAll().getFirst();
        assertFalse(actual.hasTargetBlock());assertEquals(List.of(display),actual.getDisplayEntityIds());assertEquals(Map.of(discoverer,123L),actual.getDiscoveredByCharacter());
        assertTrue(actual.getPotency()>=0&&actual.getPotency()<1);assertEquals(clue.getExpiresAtMs()-86400000,actual.getSpawnedAtMs());
        row.put("expiresAt",0L);write(data);assertTrue(SpawnedClueDatabase.loadAll().isEmpty());
    }

    @Test void missingAndMalformedFilesAreRecoverable() throws Exception {
        assertDoesNotThrow(SpawnedClueDatabase::new);assertTrue(SpawnedClueDatabase.loadAll().isEmpty());Files.createDirectories(file().getParent());Files.writeString(file(),"{broken");assertTrue(SpawnedClueDatabase.loadAll().isEmpty());
        Files.delete(file());Files.delete(file().getParent());Files.writeString(file().getParent(),"blocked");assertDoesNotThrow(() -> SpawnedClueDatabase.saveAll(List.of(clue(Long.MAX_VALUE))));
    }

    @Test void invalidRowCannotHideValidRowsFollowingIt() throws Exception {
        var clue=clue(Long.MAX_VALUE);SpawnedClueDatabase.saveAll(List.of(clue));var data=stored();data.addFirst("corrupt row");write(data);
        assertEquals(List.of(clue.getId()),SpawnedClueDatabase.loadAll().stream().map(SpawnedClue::getId).toList());
    }

    @Test void nonFinitePotencyCannotDestroyPreviousSave() throws Exception {
        var clue=clue(Long.MAX_VALUE);SpawnedClueDatabase.saveAll(List.of(clue));String prior=Files.readString(file());clue.setPotency(Double.NaN);
        SpawnedClueDatabase.saveAll(List.of(clue));assertEquals(prior,Files.readString(file()),"Rejected non-finite data must not truncate the last valid file");
    }

    @Test void failedStagingWriteOrMovePreservesPreviousSaveAndCleansTemporaryFiles() throws Exception {
        var clue=clue(Long.MAX_VALUE);assertTrue(SpawnedClueDatabase.trySaveAll(List.of(clue)));String prior=Files.readString(file());
        for(String operation:List.of("writeString","move")) {
            var stage=new java.util.concurrent.atomic.AtomicReference<Path>();
            try(var files=mockStatic(Files.class,call -> {
                if(call.getMethod().getName().equals(operation)) {
                    Path path=call.getArgument(0);stage.set(path);
                    if(operation.equals("writeString"))Files.write(path,"partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    throw new java.io.IOException("simulated "+operation+" failure");
                }
                return call.callRealMethod();
            })) {assertFalse(SpawnedClueDatabase.trySaveAll(List.of(clue)));}
            assertNotNull(stage.get());assertNotEquals(file(),stage.get());assertFalse(Files.exists(stage.get()));assertEquals(prior,Files.readString(file()));
        }
    }

    @Test void cleanupFailureKeepsTheOriginalIoFailureAndPreviousSave() throws Exception {
        var clue=clue(Long.MAX_VALUE);SpawnedClueDatabase.saveAll(List.of(clue));String prior=Files.readString(file());
        var failure=new java.io.IOException("disk full");var cleanup=new java.io.IOException("cannot delete");var stage=new java.util.concurrent.atomic.AtomicReference<Path>();
        try {
            try(var files=mockStatic(Files.class,call -> {
                if(call.getMethod().getName().equals("writeString")){stage.set(call.getArgument(0));throw failure;}
                if(call.getMethod().getName().equals("deleteIfExists")){throw cleanup;}
                return call.callRealMethod();
            })) {assertFalse(SpawnedClueDatabase.trySaveAll(List.of(clue)));}
            assertEquals(prior,Files.readString(file()));assertArrayEquals(new Throwable[]{cleanup},failure.getSuppressed());
        } finally {if(stage.get()!=null)Files.deleteIfExists(stage.get());}
    }

    @Test void runtimeClueTracksDiscoveryAndBoundsAcrossWorldsAndChunks() {
        var id=UUID.randomUUID();var displays=new ArrayList<>(List.of(UUID.randomUUID()));var discoveries=new HashMap<UUID,Long>();var discoverer=UUID.randomUUID();discoveries.put(discoverer,99L);
        var c=new SpawnedClue(id,world.getName(),-16.25,65.5,32.5,"text",Long.MAX_VALUE,owner,2,64,4,displays,.8,0,discoveries,1,12);
        displays.clear();discoveries.clear();assertEquals(1,c.getDisplayEntityIds().size());assertEquals(99L,c.getDiscoveredByCharacter().get(discoverer));assertTrue(c.getSpawnedAtMs()>0);
        assertEquals(-2,c.getChunkX());assertEquals(2,c.getChunkZ());world.loadChunk(-2,2);assertTrue(c.isChunkLoaded());
        assertSame(world,c.resolveWorld());assertEquals(new Location(world,2.5,64.5,4.5),c.getTargetCenter(world));assertNull(c.getTargetCenter(null));
        assertEquals(c.getAnchor().clone().add(0,2,0),c.getVisualBase());assertNull(c.getAnchor(null));assertNull(c.getVisualBase(null));
        assertEquals(0,c.distanceSquaredTo(c.getAnchor()));assertEquals(Double.MAX_VALUE,c.distanceSquaredTo(null));assertEquals(Double.MAX_VALUE,c.distanceSquaredTo(new Location(null,0,0,0)));
        assertEquals(Double.MAX_VALUE,c.distanceSquaredTo(new Location(server.addSimpleWorld("elsewhere"),0,0,0)));
        assertFalse(c.isDiscoveredBy((String)null));assertFalse(c.isDiscoveredBy((UUID)null));assertFalse(c.isDiscoveredBy("invalid"));assertFalse(c.isDiscoveredBy(UUID.randomUUID()));
        assertTrue(c.isDiscoveredBy(discoverer.toString().toUpperCase(Locale.ROOT)));assertTrue(c.isDiscoveredBy(discoverer));c.markDiscovered(null);c.markDiscovered(discoverer);assertEquals(99L,c.getDiscoveredByCharacter().get(discoverer));
        assertFalse(c.isVisualsSpawned());c.setVisualsSpawned(true);assertTrue(c.isVisualsSpawned());c.clearDisplayEntityIds();assertTrue(c.getDisplayEntityIds().isEmpty());
        c.setFootTrafficEventsThisHour(-1);assertEquals(0,c.getFootTrafficEventsThisHour());c.setPotency(2);assertEquals(1,c.getPotency());c.setPotency(-1);assertEquals(0,c.getPotency());assertTrue(c.shouldRemove());
        ClueDiscoveryLoader.getSettings().setPotencyExpireWhenZero(false);assertFalse(c.shouldRemove());assertFalse(c.isExpired());assertTrue(clue(0).shouldRemove());
        var plain=new SpawnedClue(UUID.randomUUID(),"missing",1,2,3,"plain",Long.MAX_VALUE,owner);assertNull(plain.getTargetCenter());assertNull(plain.getAnchor());assertNull(plain.getVisualBase());assertFalse(plain.isChunkLoaded());
        assertFalse(plain.hasTargetBlock());var legacy=new SpawnedClue(UUID.randomUUID(),world.getName(),0,0,0,"legacy",Long.MAX_VALUE,owner,null,null,null,List.of(id));assertEquals(List.of(id),legacy.getDisplayEntityIds());
    }
}
