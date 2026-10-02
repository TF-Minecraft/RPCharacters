package net.tfminecraft.rpcharacters.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.kit.KitCustomiseData;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class KitCustomiseIngestServiceTest extends IngestFixture {
    PlayerData data;RPCharacter character;PlayerManager manager;MockedStatic<RPCharacters> root;
    @BeforeEach void setupCustomisation() {
        data=new PlayerData(player);character=new RPCharacter(player);character.setId("alpha");character.setName("Aria");character.setRace(race);data.addCharacter(character);manager=mock(PlayerManager.class);
        doAnswer(call->{new Database().savePlayer(data);return null;}).when(manager).savePlayer(player);
        root=mockStatic(RPCharacters.class,CALLS_REAL_METHODS);root.when(RPCharacters::getPlayerManager).thenReturn(manager);
        api.when(ProvinceSystemClient::fetchPendingLoreItems).thenReturn(ProvinceSystemClient.SimpleResult.success("[]"));api.when(()->ProvinceSystemClient.parsePendingLoreItems(any())).thenReturn(List.of());
        api.when(()->ProvinceSystemClient.ackLoreItems(anyString())).thenAnswer(call->{acknowledgments.add(parse(call.getArgument(0)));return ProvinceSystemClient.SimpleResult.success("{}");});
    }
    @AfterEach void cleanupCustomisation(){root.close();Thread.interrupted();}
    JSONObject customiseRow(){return obj("character_id","alpha","player_uuid",owner.toString(),"kit_key","paper","display_name","Custom paper","lore",array("Lore"),"skin_slug","book","path","v.PAPER","ia_namespace","staff","name_colours",array("red"),"name_styles",array("bold"));}
    void ready(JSONObject...rows){api.when(()->ProvinceSystemClient.parsePendingLoreItems(any())).thenReturn(Arrays.asList(rows));}
    JSONObject savedCustomise() throws Exception {return (JSONObject)((JSONObject)parse(Files.readString(characterFolder.resolve("alpha.json"))).get("kit-customisations")).get("paper");}
    void seedDisk() {assertTrue(new Database().trySavePlayer(data));}

    @Test void nullEmptyAndFailedPullsNeverScheduleWritesOrAcknowledgements() {
        KitCustomiseIngestService.pullAsync(null);KitCustomiseIngestService.pullNow(null);assertTrue(workers.isEmpty());KitCustomiseIngestService.pullAsync(plugin);assertEquals(1,workers.size());runWorkers();assertTrue(acknowledgments.isEmpty());
        api.when(ProvinceSystemClient::fetchPendingLoreItems).thenReturn(ProvinceSystemClient.SimpleResult.fail("offline"));KitCustomiseIngestService.pullNow(plugin);verify(logger).warning(contains("pending fetch failed: offline"));assertFalse(Files.exists(playerFile));
    }

    @Test void onlineRowsPersistCompleteCustomisationBeforeSuccessfulAck() throws Exception {
        online(data);var row=customiseRow();row.put("lore",array(null,"Line",42));row.put("name_colours",array(null," "," #12ABef "));row.put("name_styles",array(null," "," BOLD "));ready(row);
        api.when(()->ProvinceSystemClient.ackLoreItems(anyString())).thenAnswer(call->{assertEquals("Custom paper",savedCustomise().get("display-name"));acknowledgments.add(parse(call.getArgument(0)));return ProvinceSystemClient.SimpleResult.success("{}");});
        KitCustomiseIngestService.pullAsync(plugin);assertFalse(Files.exists(playerFile));runWorkers();assertEquals(true,result(0).get("ok"));assertEquals("paper",result(0).get("kit_key"));assertEquals(owner.toString(),result(0).get("player_uuid"));var custom=character.getKitCustomisations().get("paper");assertEquals(List.of("Line","42"),custom.getLore());assertEquals(List.of("#12ABef"),custom.getNameColours());assertEquals(List.of("bold"),custom.getNameStyles());assertEquals("staff",custom.getIaNamespace());assertEquals("book",custom.getSkinSlug());assertEquals(array("Line","42"),savedCustomise().get("lore"));verify(logger).info(contains("processed 1 ready item"));
    }

    @Test void offlineAndUnmanagedOnlineAccountsPersistUsingRealDatabase() throws Exception {
        seedDisk();ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);assertEquals(true,result(0).get("ok"));assertEquals("Custom paper",savedCustomise().get("display-name"));assertFalse(character.getKitCustomisations().containsKey("paper"),"Offline load must update its loaded object, not the stale test instance");
        bukkit.when(()->Bukkit.getPlayer(owner)).thenReturn(player);players.when(()->PlayerManager.exists(player)).thenReturn(false);var row=customiseRow();row.put("display_name","Updated online");row.put("skin_slug"," ");row.put("ia_namespace"," ");row.put("lore",null);row.put("name_colours",null);row.put("name_styles",null);ready(row);KitCustomiseIngestService.pullNow(plugin);assertEquals(true,result(0).get("ok"));assertEquals("Updated online",savedCustomise().get("display-name"));assertEquals("tfmc_submissions",savedCustomise().get("ia-namespace"));assertFalse(savedCustomise().containsKey("skin-slug"));
    }

    @Test void malformedRowsMissingPlayersAndUnknownCharactersReceiveFailureAck() {
        var missingFields=obj("kit_key","paper");var invalidUuid=customiseRow();invalidUuid.put("player_uuid","invalid");ready(missingFields,invalidUuid,customiseRow());KitCustomiseIngestService.pullNow(plugin);assertEquals(false,result(0).get("ok"));assertEquals("missing character_id, kit_key, or player_uuid",result(0).get("error"));assertEquals(false,result(1).get("ok"));assertTrue(result(1).get("error").toString().contains("Invalid UUID"));assertEquals("character not found",result(2).get("error"));
        online(new PlayerData(player));ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);assertEquals("character not found",result(0).get("error"));players.when(()->PlayerManager.get(player)).thenReturn(null);KitCustomiseIngestService.pullNow(plugin);assertEquals("player data not loaded",result(0).get("error"));
    }

    @Test void unmanagedMissingAndCorruptOnlineAccountsDoNotAcknowledgeUnknownCharacters() throws Exception {
        bukkit.when(()->Bukkit.getPlayer(owner)).thenReturn(player);ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);assertEquals(false,result(0).get("ok"));assertFalse(Files.exists(playerFile));
        Files.writeString(playerFile,"{malformed");KitCustomiseIngestService.pullNow(plugin);assertEquals(false,result(0).get("ok"));assertEquals("{malformed",Files.readString(playerFile));
    }

    @Test void characterSpecificApplyFiltersOwnershipAndCapturesFailuresPerRow() {
        assertTrue(KitCustomiseIngestService.applyReadyForCharacterOnMain(null,character,List.of()).isEmpty());assertTrue(KitCustomiseIngestService.applyReadyForCharacterOnMain(player,null,List.of()).isEmpty());character.setId(null);assertTrue(KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,List.of()).isEmpty());character.setId(" ");assertTrue(KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,List.of()).isEmpty());character.setId("alpha");assertTrue(KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,null).isEmpty());
        online(data);var other=customiseRow();other.put("player_uuid",UUID.randomUUID().toString());var otherCharacter=customiseRow();otherCharacter.put("character_id","beta");var mine=customiseRow();mine.put("character_id","ALPHA");mine.put("player_uuid",owner.toString().toUpperCase(Locale.ROOT));var broken=customiseRow();broken.remove("kit_key");broken.put("skin_slug",null);broken.put("lore",null);
        var results=KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,Arrays.asList(null,other,otherCharacter,mine,broken));assertEquals(2,results.size());assertEquals(true,results.getFirst().get("ok"));assertEquals(false,results.getLast().get("ok"));assertEquals("Custom paper",character.getKitCustomisations().get("paper").getDisplayName());verify(logger).info(contains("matched=2"));
    }

    @Test void characterLookupFailureUsesExceptionNameWhenItHasNoMessage() {
        var broken=mock(PlayerData.class);when(broken.getCharacterById("alpha")).thenThrow(new IllegalStateException());online(broken);ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);assertEquals(false,result(0).get("ok"));assertEquals("IllegalStateException",result(0).get("error"));
    }

    @Test void ackFailuresWarnAndAsyncAckSnapshotsTheCallerList() {
        online(data);ready(customiseRow());api.when(()->ProvinceSystemClient.ackLoreItems(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("network down"));KitCustomiseIngestService.pullNow(plugin);verify(logger).warning(contains("ack failed: network down"));
        KitCustomiseIngestService.ackAsync(null);KitCustomiseIngestService.ackAsync(List.of());RPCharacters.plugin=null;KitCustomiseIngestService.ackAsync(List.of(obj("ok",true)));RPCharacters.plugin=plugin;assertTrue(workers.isEmpty());
        var results=new ArrayList<>(List.of(obj("ok",true,"kit_key","paper")));KitCustomiseIngestService.ackAsync(results);results.clear();runWorkers();verify(logger).warning(contains("claim-pull ack failed: network down"));
        api.when(()->ProvinceSystemClient.ackLoreItems(anyString())).thenAnswer(call->{acknowledgments.add(parse(call.getArgument(0)));return ProvinceSystemClient.SimpleResult.success("{}");});KitCustomiseIngestService.ackAsync(List.of(obj("ok",true)));runWorkers();assertEquals(true,result(0).get("ok"));verify(logger).info(contains("claim-pull ack ok results=1"));
    }

    @Test void failedOfflineWriteMustProduceFailureAckAndPreserveSavedCustomisation() throws Exception {
        var previous=new KitCustomiseData("paper","Original",List.of(),null,"v.PAPER");character.putKitCustomise(previous);seedDisk();var before=Files.readString(characterFolder.resolve("alpha.json"));
        // Replacing an existing file by a directory prevents atomic publication.
        Files.delete(characterFolder.resolve("alpha.json"));Files.createDirectory(characterFolder.resolve("alpha.json"));Files.writeString(characterFolder.resolve("alpha.json/keep"),before);
        // Offline load still has a valid character through a second file with the same id.
        Files.writeString(characterFolder.resolve("backup.json"),before);ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);
        assertEquals(false,result(0).get("ok"),"A failed character save must never receive a success ACK");assertEquals(before,Files.readString(characterFolder.resolve("alpha.json/keep")));assertEquals(before,Files.readString(characterFolder.resolve("backup.json")));
    }

    @Test void failedOnlineWriteRollsBackCustomisationAndMustNotReportSuccess() throws Exception {
        online(data);var previous=new KitCustomiseData("paper","Original",List.of(),null,"v.PAPER");character.putKitCustomise(previous);Files.writeString(characterFolder,"not a directory");ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);
        assertEquals(false,result(0).get("ok"),"A failed local save must remain retryable on the server");assertSame(previous,character.getKitCustomisations().get("paper"),"Failed persistence must restore the previously visible customisation");assertEquals("not a directory",Files.readString(characterFolder));
    }

    @Test void failedNewOnlineCustomisationDoesNotRemainInMemoryAfterSaveFailure() throws Exception {
        online(data);Files.writeString(characterFolder,"not a directory");ready(customiseRow());KitCustomiseIngestService.pullNow(plugin);assertEquals(false,result(0).get("ok"));assertFalse(character.getKitCustomisations().containsKey("paper"));
    }

    @Test void interruptedPullMustCancelItsQueuedMutationAndKeepRowsRetryable() {
        online(data);ready(customiseRow());var queued=new ArrayList<Runnable>();when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{queued.add(call.getArgument(1));return mock(BukkitTask.class);});
        try {Thread.currentThread().interrupt();KitCustomiseIngestService.pullNow(plugin);assertTrue(Thread.currentThread().isInterrupted());} finally {Thread.interrupted();}
        assertEquals(1,queued.size());assertTrue(acknowledgments.isEmpty());queued.getFirst().run();assertFalse(character.getKitCustomisations().containsKey("paper"),"An interrupted pull must not mutate state later without an ACK");assertFalse(Files.exists(playerFile));
    }

    @Test void timedOutPullMustCancelItsQueuedMutation() throws Exception {
        online(data);ready(customiseRow());var queued=new ArrayList<Runnable>();when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{queued.add(call.getArgument(1));return mock(BukkitTask.class);});
        try(var latches=mockConstruction(CountDownLatch.class,(latch,context)->when(latch.await(30,TimeUnit.SECONDS)).thenReturn(false))) {KitCustomiseIngestService.pullNow(plugin);assertEquals(1,latches.constructed().size());}
        assertTrue(acknowledgments.isEmpty());queued.getFirst().run();assertFalse(character.getKitCustomisations().containsKey("paper"),"Work whose wait expired must not apply after the caller already abandoned it");
    }

    @Test void nameStyleIdentifiersUseRootLocale() {
        online(data);var row=customiseRow();row.put("name_styles",array("ITALIC"));ready(row);Locale.setDefault(Locale.forLanguageTag("tr-TR"));KitCustomiseIngestService.pullNow(plugin);assertEquals(List.of("italic"),character.getKitCustomisations().get("paper").getNameStyles());
    }
}
