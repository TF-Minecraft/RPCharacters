package net.tfminecraft.rpcharacters.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.calendar.AgeCalculator;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.kit.KitService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import net.tfminecraft.rpcharacters.utils.Integrator;

class CharacterIngestServiceTest extends IngestFixture {
    AtomicLong stamp;long savedStamp;BukkitTask savedTask;
    MockedStatic<RosterSyncService> roster;MockedStatic<KitCustomiseIngestService> kitCustomise;MockedStatic<CharacterLifecycle> lifecycle;MockedStatic<WardrobeService> wardrobe;MockedStatic<RPCharacters> root;MockedStatic<MmoCorePlayerReady> mmoReady;MockedStatic<AttributePointService> attributes;MockedStatic<ProfessionIntegrator> professions;
    PlayerManager manager; MockedConstruction<Integrator> integrators;
    @BeforeEach void setupCreation() throws Exception {
        var timestamp=CharacterIngestService.class.getDeclaredField("lastPullAtMs");timestamp.setAccessible(true);stamp=(AtomicLong)timestamp.get(null);savedStamp=stamp.getAndSet(0);
        var periodic=CharacterIngestService.class.getDeclaredField("periodicTask");periodic.setAccessible(true);savedTask=(BukkitTask)periodic.get(null);periodic.set(null,null);
        roster=mockStatic(RosterSyncService.class);kitCustomise=mockStatic(KitCustomiseIngestService.class);lifecycle=mockStatic(CharacterLifecycle.class);wardrobe=mockStatic(WardrobeService.class);root=mockStatic(RPCharacters.class,CALLS_REAL_METHODS);manager=mock(PlayerManager.class);root.when(RPCharacters::getPlayerManager).thenReturn(manager);mmoReady=mockStatic(MmoCorePlayerReady.class);attributes=mockStatic(AttributePointService.class);professions=mockStatic(ProfessionIntegrator.class);integrators=mockConstruction(Integrator.class,(mock,context)->when(mock.getRemoveList(any(),any())).thenReturn(List.of()));
    }
    @AfterEach void cleanupCreation() throws Exception {
        CharacterIngestService.stopPeriodicPull();var periodic=CharacterIngestService.class.getDeclaredField("periodicTask");periodic.setAccessible(true);periodic.set(null,savedTask);stamp.set(savedStamp);Thread.interrupted();
        integrators.close();professions.close();attributes.close();mmoReady.close();root.close();wardrobe.close();lifecycle.close();kitCustomise.close();roster.close();
    }

    @Test void nullEntrypointsAndCooldownPreventDuplicateFetchButStillRefreshJoiningRoster() {
        CharacterIngestService.tryPullAsync(null);CharacterIngestService.forcePullAsync(null);CharacterIngestService.tryPullForPlayerAsync(null,owner);CharacterIngestService.tryPullForPlayerAsync(plugin,null);CharacterIngestService.startPeriodicPull(null);CharacterIngestService.pullAsync(null);CharacterIngestService.pullForPlayerAsync(null,owner);CharacterIngestService.pullForPlayerAsync(plugin,null);assertTrue(workers.isEmpty());
        CharacterIngestService.tryPullAsync(plugin);assertEquals(1,workers.size());assertTrue(stamp.get()>0);CharacterIngestService.tryPullAsync(plugin);CharacterIngestService.tryPullForPlayerAsync(plugin,owner);assertEquals(1,workers.size());roster.verify(() -> RosterSyncService.pushRosterAsync(owner));
        CharacterIngestService.forcePullAsync(plugin);assertEquals(2,workers.size());runWorkers();api.verify(ProvinceSystemClient::fetchPendingCreates,times(2));kitCustomise.verify(() -> KitCustomiseIngestService.pullNow(plugin),times(2));
    }


    @Test void concurrentAdmissionSchedulesOnlyOneFetchPerCooldownWindow() throws Exception {
        // A shared server mock applies to worker threads as well as this test thread.
        // Scheduled network work is captured, never executed by these contenders.
        var serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object original=serverField.get(null);
        var sharedServer=mock(org.bukkit.Server.class);when(sharedServer.getScheduler()).thenReturn(scheduler);
        int contenders=16,rounds=400;var barrier=new CyclicBarrier(contenders+1);var executor=Executors.newFixedThreadPool(contenders);List<Future<?>> futures=new ArrayList<>();
        try {
            serverField.set(null,sharedServer);
            for(int n=0;n<contenders;n++)futures.add(executor.submit(()->{for(int i=0;i<rounds;i++){barrier.await(10,TimeUnit.SECONDS);if(i%2==0)CharacterIngestService.tryPullAsync(plugin);else CharacterIngestService.tryPullForPlayerAsync(plugin,owner);barrier.await(10,TimeUnit.SECONDS);}return null;}));
            for(int round=0;round<rounds;round++){
                workers.clear();stamp.set(0);barrier.await(10,TimeUnit.SECONDS);barrier.await(10,TimeUnit.SECONDS);
                long fetches=workers.stream().filter(task->task.getClass().getName().startsWith(CharacterIngestService.class.getName()+"$$Lambda")).count();
                assertEquals(1,fetches,"Concurrent requests must share one admitted fetch");assertEquals(round%2==0?1:contenders,workers.size(),"Each skipped player pull still schedules a roster refresh");
            }
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();try {assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));} finally {serverField.set(null,original);workers.clear();}}
    }

    @Test void failedOnlineWriteRollsBackNewCharacterAndAgeWithoutLifecycleEffects() throws Exception {
        PlayerData pd=mockData();online(pd);when(pd.isEighteen()).thenReturn(false);Files.writeString(characterFolder,"blocked");JSONObject payload=payload();payload.put("eighteen",true);pending(row("alpha",payload));CharacterIngestService.pullAsync(plugin);runWorkers();
        assertEquals(false,result(0).get("ok"));assertTrue(pd.getCharacters().isEmpty(),"Unpersisted character must not survive in player memory");verify(pd).setEighteen(false);verify(pd,never()).setActiveCharacter(any());lifecycle.verifyNoInteractions();wardrobe.verifyNoInteractions();verify(manager,never()).savePlayer(any());
    }

    @Test void timerReplacementCancelsPreviousAndTicksShareTheCooldown() {
        List<Runnable> timers=new ArrayList<>();BukkitTask first=mock(BukkitTask.class),second=mock(BukkitTask.class);when(scheduler.runTaskTimerAsynchronously(eq(plugin),any(Runnable.class),eq(300L),eq(300L))).thenAnswer(call->{timers.add(call.getArgument(1));return timers.size()==1?first:second;});
        CharacterIngestService.startPeriodicPull(plugin);CharacterIngestService.startPeriodicPull(plugin);verify(first).cancel();timers.getLast().run();timers.getLast().run();assertEquals(1,workers.size());CharacterIngestService.stopPeriodicPull();CharacterIngestService.stopPeriodicPull();verify(second,times(1)).cancel();
    }

    @Test void fetchFailuresWarnAndPerPlayerFailureStillPushesRoster() {
        api.when(ProvinceSystemClient::fetchPendingCreates).thenReturn(ProvinceSystemClient.SimpleResult.fail("offline"));CharacterIngestService.pullAsync(plugin);runWorkers();verify(logger).warning(contains("pending fetch failed: offline"));kitCustomise.verify(() -> KitCustomiseIngestService.pullNow(plugin));
        CharacterIngestService.pullForPlayerAsync(plugin,owner);runWorkers();roster.verify(() -> RosterSyncService.pushRosterNow(owner));assertTrue(acknowledgments.isEmpty());
    }

    @Test void pendingRowsApplyOnMainAndPersistBeforeAcknowledgement() throws Exception {
        trait("strong","physical");JSONObject payload=payload();payload.putAll(obj("all_traits",array("strong","missing"),"clues",array("Blue coat"),"name_colours",array(null," "," #112233 ","#abcdef"),"birthday","2000-01-02","eighteen",true));pending(row("alpha",payload));
        api.when(() -> ProvinceSystemClient.ackCreates(anyString())).thenAnswer(call->{assertTrue(Files.exists(characterFolder.resolve("alpha.json")),"Character must exist before a success acknowledgment");acknowledgments.add(parse(call.getArgument(0)));return ProvinceSystemClient.SimpleResult.success("{}");});
        CharacterIngestService.pullAsync(plugin);assertFalse(Files.exists(playerFile));assertTrue(acknowledgments.isEmpty());runWorkers();
        assertEquals(obj("id","alpha","ok",true,"character_id","alpha"),result(0));JSONObject saved=parse(Files.readString(characterFolder.resolve("alpha.json")));assertEquals("Aria",saved.get("name"));assertEquals("woman",saved.get("gender"));assertEquals("Traveller",saved.get("description"));assertEquals(array("strong"),saved.get("traits"));assertEquals("2000-01-02",saved.get("birthday"));assertTrue(((Number)saved.get("created-at")).longValue()>0);assertEquals(obj("colours",array("#112233","#abcdef")),saved.get("name-colour"));assertEquals("true",parse(Files.readString(playerFile)).get("eighteen"));
        lifecycle.verify(() -> CharacterLifecycle.fireCreated(isNull(),eq(owner),argThat(c -> c.getId().equals("alpha"))));kitService.verify(() -> KitService.onCharacterCreated(isNull(),any(PlayerData.class),argThat(c -> c.getId().equals("alpha"))));roster.verify(() -> RosterSyncService.pushRosterAsync(owner));verify(logger).info(contains("processed 1"));verify(scheduler).runTask(eq(plugin),any(Runnable.class));
    }

    @Test void fallbackBirthdayAndLegacyTraitsPersistAcrossOfflineCreates() throws Exception {
        trait("strong","physical");JSONObject one=payload();one.putAll(obj("traits",array("strong"),"birthday","invalid","client_request_id","seed"));JSONObject two=payload();two.put("name_colours",array(null," "));two.put("eighteen","not boolean");pending(row("alpha",one),row("beta",two));CharacterIngestService.pullAsync(plugin);runWorkers();
        assertEquals(true,result(0).get("ok"));assertEquals(true,result(1).get("ok"));var saved=parse(Files.readString(characterFolder.resolve("alpha.json")));assertEquals(AgeCalculator.birthdayFromAge(24,LocalDate.now(),"seed"),saved.get("birthday"));assertEquals(array("strong"),saved.get("traits"));assertEquals(AgeCalculator.birthdayFromAge(24,LocalDate.now(),"beta"),parse(Files.readString(characterFolder.resolve("beta.json"))).get("birthday"));
    }

    @Test void inMemoryPlayerIsUpdatedSavedAndActivatedOnlyWhenNeeded() {
        PlayerData pd=mockData();online(pd);when(pd.hasActiveCharacter()).thenReturn(false);pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(true,result(0).get("ok"));verify(pd).addCharacter(argThat(c->c.getName().equals("Aria")));verify(pd).setActiveCharacter(any(RPCharacter.class));wardrobe.verify(() -> WardrobeService.refreshActiveAsync(player));verify(manager).savePlayer(player);verify(manager).reevaluateFreeze(player);
        when(pd.hasActiveCharacter()).thenReturn(true);pending(row("beta",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();verify(pd,times(1)).setActiveCharacter(any(RPCharacter.class));assertEquals(true,result(0).get("ok"));
    }

    @Test void unregisteredOnlinePlayerLoadsOrCreatesAndPersistsTheirAccount() throws Exception {
        bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(true,result(0).get("ok"));assertTrue(Files.exists(playerFile));assertTrue(Files.exists(characterFolder.resolve("alpha.json")));verify(manager,never()).savePlayer(any());wardrobe.verify(() -> WardrobeService.refreshActiveAsync(player));
        pending(row("beta",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(true,result(0).get("ok"));assertTrue(Files.exists(characterFolder.resolve("beta.json")));
    }

    @Test void perPlayerPullFiltersOwnersAndAlwaysRefreshesRosterAfterApply() {
        var mine=row("alpha",obj());mine.put("player_uuid",owner.toString().toUpperCase(Locale.ROOT));var other=row("other",obj());other.put("player_uuid",UUID.randomUUID().toString());pending(other,mine,obj("id","missing-owner"));CharacterIngestService.tryPullForPlayerAsync(plugin,owner);runWorkers();assertEquals(1,((JSONArray)acknowledgments.getFirst().get("results")).size());assertEquals("alpha",result(0).get("id"));assertEquals("invalid age",result(0).get("error"));roster.verify(() -> RosterSyncService.pushRosterNow(owner));kitCustomise.verify(() -> KitCustomiseIngestService.pullNow(plugin));
        pending(other);CharacterIngestService.pullForPlayerAsync(plugin,owner);runWorkers();assertEquals(1,acknowledgments.size());roster.verify(() -> RosterSyncService.pushRosterNow(owner),times(2));
    }

    @Test void invalidPayloadsAndExceptionsProducePerRowErrorsAndDoNotWriteData() {
        var unknown=payload();unknown.put("race_id","missing");pending(obj("id","bad-uuid","player_uuid","bad","payload",obj()),row("missing",null),row("age",obj("age","24")),row("race",unknown));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(false,result(0).get("ok"));assertEquals("missing payload",result(1).get("error"));assertEquals("invalid age",result(2).get("error"));assertEquals("unknown race: missing",result(3).get("error"));assertFalse(Files.exists(playerFile));
        try(var races=mockStatic(RaceLoader.class)){races.when(() -> RaceLoader.getByString("human")).thenThrow(new IllegalStateException());pending(row("throws",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals("apply failed",result(0).get("error"));}
    }

    @Test void failedAccountLoadAndFullSlotsRejectNewCreates() throws Exception {
        Files.writeString(playerFile,"{corrupt");pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals("could not load player data",result(0).get("error"));assertEquals("{corrupt",Files.readString(playerFile));Files.delete(playerFile);
        slots.when(CharacterSlotService::getHardSlotCap).thenReturn(0);CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals("no free character slot",result(0).get("error"));
        var pd=mockData();online(pd);slots.when(() -> CharacterSlotService.hasFreeSlot(player,pd)).thenReturn(false);CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals("no free character slot",result(0).get("error"));verify(pd,never()).addCharacter(any());
        players.when(() -> PlayerManager.get(player)).thenReturn(null);CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals("could not load player data",result(0).get("error"));
    }

    @Test void duplicateRequestIsAcknowledgedEvenAfterCreationFillsTheFinalSlot() {
        var pd=mockData();online(pd);doReturn(List.of(character(null),character("ALPHA"))).when(pd).getCharacters();slots.when(() -> CharacterSlotService.hasFreeSlot(player,pd)).thenReturn(false);pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(obj("id","alpha","ok",true,"character_id","alpha"),result(0),"Retry must succeed idempotently even when the first create consumed the final slot");verify(pd,never()).addCharacter(any());verify(manager,never()).savePlayer(any());
    }

    @Test void duplicateOfflineRequestAtCapacityPreservesSavedCharacterBytes() throws Exception {
        slots.when(CharacterSlotService::getHardSlotCap).thenReturn(1);pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();String original=Files.readString(characterFolder.resolve("alpha.json"));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(true,result(0).get("ok"),"An acknowledged retry must not fail because its own character occupies the slot");assertEquals(original,Files.readString(characterFolder.resolve("alpha.json")));
    }

    @Test void corruptOnlineAccountMustNotBeReplacedBySuccessfulWebCreation() throws Exception {
        bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);Files.writeString(playerFile,"{valuable corrupt account");pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals("{valuable corrupt account",Files.readString(playerFile),"Failed existing-account load must not be treated as an empty account");assertEquals(false,result(0).get("ok"));assertFalse(Files.exists(characterFolder.resolve("alpha.json")));
    }


    @Test void failedCharacterWriteMustNotAcknowledgeSuccessfulCreation() throws Exception {
        Files.writeString(characterFolder,"blocking file");pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();
        assertEquals(false,result(0).get("ok"),"Remote create must remain failed when no character file was saved");assertEquals("blocking file",Files.readString(characterFolder));
    }

    @Test void existingDuplicateWithoutCapacityPressureAndNullIdAreSafe() {
        var pd=mockData();online(pd);doReturn(Arrays.asList(character(null),character("alpha"))).when(pd).getCharacters();pending(row("ALPHA",payload()));CharacterIngestService.pullAsync(plugin);runWorkers();assertEquals(true,result(0).get("ok"));verify(pd,never()).addCharacter(any());
    }

    @Test void acknowledgementFailureIsLoggedAndCustomisePullStillRuns() {
        pending(row("invalid",null));api.when(() -> ProvinceSystemClient.ackCreates(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("write unavailable"));CharacterIngestService.pullAsync(plugin);runWorkers();verify(logger).warning(contains("ack failed: write unavailable"));kitCustomise.verify(() -> KitCustomiseIngestService.pullNow(plugin));
    }

    @Test void interruptionRestoresWorkerFlagWithoutAcknowledgingUnappliedRows() throws Exception {
        List<Runnable> queuedMain=new ArrayList<>();when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{queuedMain.add(call.getArgument(1));return mock(BukkitTask.class);});pending(row("alpha",payload()));CharacterIngestService.pullAsync(plugin);
        Thread.currentThread().interrupt();try {runWorkers();assertTrue(Thread.currentThread().isInterrupted());assertTrue(acknowledgments.isEmpty());assertEquals(1,queuedMain.size());} finally {Thread.interrupted();}
        queuedMain.getFirst().run();assertFalse(Files.exists(playerFile));assertTrue(acknowledgments.isEmpty());
    }
    @Test void timedOutApplyCannotCreateACharacterAfterItsWorkerHasReturned() throws Exception {
        List<Runnable> queuedMain=new ArrayList<>();when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{queuedMain.add(call.getArgument(1));return mock(BukkitTask.class);});pending(row("alpha",payload()));
        try(var latches=mockConstruction(CountDownLatch.class,(latch,context)->when(latch.await(30,TimeUnit.SECONDS)).thenReturn(false))) {
            CharacterIngestService.pullAsync(plugin);runWorkers();
        }
        assertTrue(acknowledgments.isEmpty());assertEquals(1,queuedMain.size());queuedMain.getFirst().run();
        assertFalse(Files.exists(playerFile));assertTrue(acknowledgments.isEmpty());
    }

}
