package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.api.GatewayClient;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;

class WillQueueTest {
    private ServerMock server;
    private RuntimeTestState state;
    private final List<AutoCloseable> boundaries = new ArrayList<>();
    private final WillServiceTest.Characters characters = new WillServiceTest.Characters();
    private MockedStatic<ProvinceSystemClient> site;
    private MockedStatic<GatewayClient> gateway;
    private MockedStatic<PlayerManager> players;
    private MockedConstruction<Database> loads;
    private Database saves;
    private PlayerData data;
    private RPCharacter aria;
    private CharacterInfo kira;
    private long now = 1_000_000L;
    private java.util.function.BiConsumer<Plugin, Runnable> defaultAsync;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        state = new RuntimeTestState(WillService.class, WillQueue.class);
        saves = mock(Database.class);
        when(saves.trySaveCharacterDurably(any(), any())).thenReturn(true);
        WillService.database = saves;
        WillService.lookup = characters;
        WillQueue.clock = () -> now;
        defaultAsync = WillQueue.async;
        WillQueue.async = (plugin, task) -> task.run();
        WillQueue.stop();
        data = new PlayerData(UUID.randomUUID());
        aria = new RPCharacter(owner());
        data.addCharacter(aria);
        characters.infos.put(UUID.fromString(aria.getId()), new CharacterInfo(new CharacterRef(data.getUniqueId(),
                UUID.fromString(aria.getId())), "Aria", CharacterStatus.ALIVE, "elf", java.util.Set.of(), 0, null));
        kira = characters.add("Kira", CharacterStatus.ALIVE);
        site = track(mockStatic(ProvinceSystemClient.class));
        gateway = track(mockStatic(GatewayClient.class));
        players = track(mockStatic(PlayerManager.class));
        track(mockStatic(RosterSyncService.class));
        loads = track(mockConstruction(Database.class, (db, context) -> when(db.loadPlayerData(data.getUniqueId())).thenReturn(data)));
    }

    private <T extends AutoCloseable> T track(T boundary) {
        boundaries.add(boundary);
        return boundary;
    }

    static org.bukkit.entity.Player owner() {
        org.bukkit.entity.Player owner = mock(org.bukkit.entity.Player.class);
        when(owner.getName()).thenReturn("Owner");
        return owner;
    }

    @AfterEach
    void tearDown() throws Exception {
        WillQueue.stop();
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        state.close();
        MockBukkit.unmock();
    }

    @SuppressWarnings("unchecked")
    private JSONObject request(String id, Object heir, long base) {
        JSONObject request = new JSONObject();
        request.put("id", id);
        request.put("realm_id", "main");
        request.put("character_id", aria.getId());
        request.put("account_uuid", data.getUniqueId().toString());
        request.put("heir_character_id", heir);
        request.put("base_revision", base);
        return request;
    }

    @Test
    void aRequestAppliesOnceAndItsReplayIsAcknowledgedAppliedAgain() {
        JSONObject first = WillQueue.apply(request("r1", kira.ref().characterId().toString(), 0), "main");
        assertEquals("applied", first.get("result"));
        assertEquals(1L, first.get("will_revision"));
        assertEquals("Kira", first.get("heir_name"));
        assertEquals("r1", aria.getWill().lastRequest());
        JSONObject replay = WillQueue.apply(request("r1", kira.ref().characterId().toString(), 0), "main");
        assertEquals("applied", replay.get("result"));
        assertEquals(1L, replay.get("will_revision"));
        verify(saves, times(1)).trySaveCharacterDurably(data, aria);
        assertEquals("stale", WillQueue.apply(request("r2", null, 0), "main").get("result"));
        JSONObject revoke = WillQueue.apply(request("r3", null, 1), "main");
        assertEquals("applied", revoke.get("result"));
        assertNull(revoke.get("heir_name"));
        assertFalse(aria.getWill().named());
    }

    @Test
    void invalidRequestsAreAnsweredAndUnreadableOnesWait() {
        assertNull(WillQueue.apply(request("other-realm", null, 0), "dev"));
        JSONObject broken = request("bad", null, 0);
        broken.put("character_id", "nope");
        assertEquals("invalid", WillQueue.apply(broken, "main").get("result"));
        JSONObject unknown = request("unknown", null, 0);
        unknown.put("character_id", UUID.randomUUID().toString());
        assertEquals("invalid", WillQueue.apply(unknown, "main").get("result"));
        JSONObject stranger = request("stranger", null, 0);
        stranger.put("account_uuid", UUID.randomUUID().toString());
        assertEquals("invalid", WillQueue.apply(stranger, "main").get("result"));
        assertEquals("invalid heir", WillQueue.apply(request("self", aria.getId(), 0), "main").get("result"));
        JSONObject noBase = request("nobase", null, 0);
        noBase.remove("base_revision");
        assertEquals("stale", WillQueue.apply(noBase, "main").get("result"));
        characters.unreadable.add(kira.ref().characterId());
        assertNull(WillQueue.apply(request("heir-unreadable", kira.ref().characterId().toString(), 0), "main"));
        characters.unreadable.add(UUID.fromString(aria.getId()));
        assertNull(WillQueue.apply(request("unreadable", null, 0), "main"));
        characters.unreadable.clear();
        when(saves.trySaveCharacterDurably(any(), any())).thenReturn(false);
        assertNull(WillQueue.apply(request("unsaved", null, 0), "main"));
        aria.setStatus(Status.DEAD);
        assertEquals("character dead", WillQueue.apply(request("dead", null, 0), "main").get("result"));
    }

    @Test
    void loadedAccountsAreUsedAndJoiningOnesWait() {
        PlayerMock online = new PlayerMock(server, "Owner", data.getUniqueId());
        server.addPlayer(online);
        assertNull(WillQueue.apply(request("joining", null, 0), "main"));
        players.when(() -> PlayerManager.exists(online)).thenReturn(true);
        players.when(() -> PlayerManager.get(online)).thenReturn(data);
        assertEquals("applied", WillQueue.apply(request("loaded", null, 0), "main").get("result"));
        loads.close();
        boundaries.remove(loads);
        online.disconnect();
        server.setPlayers(0);
        try (var missing = mockConstruction(Database.class)) {
            assertNull(WillQueue.apply(request("no-file", null, 1), "main"));
        }
    }

    @Test
    void aTestatorDyingBetweenChecksIsReportedDead() {
        RPCharacter racing = spy(aria);
        when(racing.getStatus()).thenReturn(Status.ALIVE, Status.DEAD);
        data.getCharacters().set(0, racing);
        assertEquals("character dead", WillQueue.apply(request("race", null, 0), "main").get("result"));
    }

    @Test
    void pullsAreScopedToTheRealmAckedWithItAndBackOffOnFailure() throws Exception {
        Plugin plugin = MockBukkit.createMockPlugin("RPCharacters");
        Logger logger = plugin.getLogger();
        WillQueue.pull(plugin);
        site.verifyNoInteractions();
        gateway.when(GatewayClient::realmId).thenReturn("main");
        site.when(() -> ProvinceSystemClient.fetchPendingWills("main")).thenReturn(ProvinceSystemClient.SimpleResult.fail("404"));
        WillQueue.pull(plugin);
        now += 1_000;
        WillQueue.pull(plugin);
        site.verify(() -> ProvinceSystemClient.fetchPendingWills("main"), times(1));
        now += 15_000;
        site.when(() -> ProvinceSystemClient.fetchPendingWills("main")).thenReturn(ProvinceSystemClient.SimpleResult.success("{\"requests\":[]}"));
        WillQueue.pull(plugin);
        site.verify(() -> ProvinceSystemClient.fetchPendingWills("main"), times(2));
        String pending = "{\"requests\":[" + request("r1", null, 0).toJSONString() + ",\"junk\"]}";
        site.when(() -> ProvinceSystemClient.fetchPendingWills("main")).thenReturn(ProvinceSystemClient.SimpleResult.success(pending));
        List<String> acks = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean ackOk = new java.util.concurrent.atomic.AtomicBoolean();
        site.when(() -> ProvinceSystemClient.ackWills(anyString())).thenAnswer(call -> {
            acks.add(call.getArgument(0));
            return ackOk.get() ? ProvinceSystemClient.SimpleResult.success("{}") : ProvinceSystemClient.SimpleResult.fail("timeout");
        });
        WillQueue.pull(plugin);
        server.getScheduler().performOneTick();
        JSONObject ack = (JSONObject) new JSONParser().parse(acks.getFirst());
        assertEquals("main", ack.get("realm_id"));
        assertEquals("applied", ((JSONObject) ((List<?>) ack.get("results")).getFirst()).get("result"));
        WillQueue.pull(plugin);
        assertEquals(1, acks.size());
        now += 60_000;
        ackOk.set(true);
        WillQueue.pull(plugin);
        server.getScheduler().performOneTick();
        assertEquals(2, acks.size());
        WillQueue.ack(logger, "main", List.of());
        assertTrue(WillQueue.parse("not json").isEmpty());
        assertTrue(WillQueue.parse("[]").isEmpty());
        assertNotNull(logger);
    }

    @Test
    void aSiteRequestAppliedBeforeAnInGameChangeStillReplaysAsApplied() {
        assertEquals("applied", WillQueue.apply(request("site", kira.ref().characterId().toString(), 0), "main").get("result"));
        WillService.change(data, aria, data.getUniqueId(), null, null);
        JSONObject replay = WillQueue.apply(request("site", kira.ref().characterId().toString(), 0), "main");
        assertEquals("applied", replay.get("result"));
        assertEquals(2L, replay.get("will_revision"));
    }

    @Test
    void anyFailureClearsThePullSoTheQueueKeepsWorking() {
        Plugin plugin = MockBukkit.createMockPlugin("RPCharacters");
        gateway.when(GatewayClient::realmId).thenReturn("main");
        site.when(() -> ProvinceSystemClient.fetchPendingWills("main")).thenThrow(new IllegalStateException("gateway"));
        WillQueue.pull(plugin);
        now += 20_000;
        String pending = "{\"requests\":[" + request("poison", null, 0).toJSONString() + ","
                + request("good", null, 0).toJSONString() + "]}";
        site.when(() -> ProvinceSystemClient.fetchPendingWills("main")).thenReturn(ProvinceSystemClient.SimpleResult.success(pending));
        List<String> acks = new ArrayList<>();
        site.when(() -> ProvinceSystemClient.ackWills(anyString())).thenAnswer(call -> {
            acks.add(call.getArgument(0));
            throw new IllegalStateException("ack exploded");
        });
        players.when(() -> PlayerManager.exists(any())).thenReturn(false);
        RPCharacter poison = spy(aria);
        when(poison.getWill()).thenThrow(new IllegalStateException("poison")).thenCallRealMethod();
        data.getCharacters().set(0, poison);
        WillQueue.pull(plugin);
        server.getScheduler().performOneTick();
        assertEquals(1, acks.size());
        assertTrue(acks.getFirst().contains("\"good\""));
        assertFalse(acks.getFirst().contains("\"poison\""));
        now += 40_000;
        WillQueue.async = (owner, task) -> { throw new IllegalStateException("scheduler closed"); };
        WillQueue.pull(plugin);
        server.getScheduler().performOneTick();
        now += 80_000;
        WillQueue.async = (owner, task) -> task.run();
        WillQueue.pull(plugin);
        server.getScheduler().performOneTick();
        assertEquals(2, acks.size());
    }

    @Test
    void theTimerPullsEveryFifteenSecondsUntilStoppedAndAcksOffTheMainThread() {
        Plugin plugin = MockBukkit.createMockPlugin("RPCharacters");
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        var task = mock(org.bukkit.scheduler.BukkitTask.class);
        List<Runnable> timers = new ArrayList<>();
        when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), eq(WillQueue.PERIOD_TICKS),
                eq(WillQueue.PERIOD_TICKS))).thenAnswer(call -> {
                    timers.add(call.getArgument(1));
                    return task;
                });
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            WillQueue.start(plugin);
            WillQueue.start(plugin);
            verify(task).cancel();
            timers.getLast().run();
            gateway.verify(GatewayClient::realmId);
            Runnable ack = () -> {};
            defaultAsync.accept(plugin, ack);
            verify(scheduler).runTaskAsynchronously(plugin, ack);
            WillQueue.stop();
            verify(task, times(2)).cancel();
        }
    }
}
