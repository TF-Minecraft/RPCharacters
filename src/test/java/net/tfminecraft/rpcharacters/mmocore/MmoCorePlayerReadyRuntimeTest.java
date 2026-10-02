package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.tfminecraft.rpcharacters.*;

class MmoCorePlayerReadyRuntimeTest {
    ServerMock server; PlayerMock player; PlayerData data; RuntimeTestState state;
    MockedStatic<PlayerData> external;
    AtomicInteger calls = new AtomicInteger();
    java.util.List<org.bukkit.scheduler.BukkitTask> scheduled = new java.util.ArrayList<>();
    MockedStatic<org.bukkit.Bukkit> bukkit;
    long queued() { return scheduled.stream().filter(task -> server.getScheduler().isQueued(task.getTaskId())).count(); }
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, MmoCorePlayerReady.class);
        RPCharacters.plugin = mock(RPCharacters.class); when(RPCharacters.plugin.isEnabled()).thenReturn(true);
        when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("ready-test"));
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class, org.mockito.AdditionalAnswers.delegatesTo(server.getScheduler()));
        doAnswer(call -> { var task = server.getScheduler().runTaskLater(call.getArgument(0), (Runnable) call.getArgument(1), call.getArgument(2)); scheduled.add(task); return task; })
                .when(scheduler).runTaskLater(eq(RPCharacters.plugin), any(Runnable.class), anyLong());
        bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
        player = server.addPlayer(); data = mock(PlayerData.class);
        when(data.getUniqueId()).thenReturn(player.getUniqueId());
        external = mockStatic(PlayerData.class);
        external.when(() -> PlayerData.has(player)).thenReturn(true);
        external.when(() -> PlayerData.get(player)).thenReturn(data);
    }
    @AfterEach void cleanup() { server.getScheduler().cancelTasks(RPCharacters.plugin); bukkit.close(); external.close(); state.close(); MockBukkit.unmock(); }
    void loadedEvent() { var event = mock(io.lumine.mythic.lib.api.event.SynchronizedDataLoadEvent.class); when(event.getHolder()).thenReturn(data); new MmoCorePlayerReady().onMmoLoaded(event); }

    @Test void missingUnsynchronizedAndFailingDataAreNeverReady() {
        assertFalse(MmoCorePlayerReady.isReady(null));
        external.when(() -> PlayerData.has(player)).thenReturn(false); assertFalse(MmoCorePlayerReady.isReady(player));
        external.when(() -> PlayerData.has(player)).thenReturn(true); assertFalse(MmoCorePlayerReady.isReady(player));
        external.when(() -> PlayerData.get(player)).thenThrow(new IllegalStateException("loading")); assertFalse(MmoCorePlayerReady.isReady(player));
        MmoCorePlayerReady.runWhenLoaded(null, calls::incrementAndGet); MmoCorePlayerReady.runWhenLoaded(player, null); MmoCorePlayerReady.cancel(null);
        assertEquals(0, calls.get()); assertTrue(queued() == 0);
    }

    @Test void alreadyReadyDataRunsImmediatelyWithoutScheduling() {
        when(data.isSynchronized()).thenReturn(true);
        assertTrue(MmoCorePlayerReady.isReady(player));
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
        assertEquals(1, calls.get()); assertTrue(queued() == 0);
    }

    @Test void queuedCallbacksShareAPollAndOneFailureDoesNotHideTheNext() {
        MmoCorePlayerReady.runWhenLoaded(player, () -> { throw new IllegalStateException("callback failed"); });
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
        assertEquals(1, queued());
        server.getScheduler().performTicks(2); assertEquals(0, calls.get());
        when(data.isSynchronized()).thenReturn(true); server.getScheduler().performTicks(1);
        assertEquals(1, calls.get()); loadedEvent(); assertEquals(1, calls.get());
    }

    @Test void disconnectedPlayersAndExpiredWaitsDropCallbacks() {
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
        player.disconnect(); server.getScheduler().performTicks(2);
        when(data.isSynchronized()).thenReturn(true); loadedEvent(); assertEquals(0, calls.get());
    }

    @Test void timeoutDropsOldWorkButAllowsLaterReadyRequests() {
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
        server.getScheduler().performTicks(105);
        assertTrue(queued() == 0);
        when(data.isSynchronized()).thenReturn(true); loadedEvent(); assertEquals(0, calls.get());
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet); assertEquals(1, calls.get());
    }

    @Test void cancellationStopsPollingWithoutWaitingForTheTimeout() {
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
        MmoCorePlayerReady.cancel(player.getUniqueId()); server.getScheduler().performTicks(2);
        assertTrue(queued() == 0, "Cancelled loading work must not keep scheduling polls");
        when(data.isSynchronized()).thenReturn(true); loadedEvent(); assertEquals(0, calls.get());
    }

    @Test void cancelledPollCannotExpireANewerLoadRequest() {
        AtomicInteger oldCalls = new AtomicInteger();
        MmoCorePlayerReady.runWhenLoaded(player, oldCalls::incrementAndGet);
        server.getScheduler().performTicks(90);
        MmoCorePlayerReady.cancel(player.getUniqueId());
        MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
        server.getScheduler().performTicks(15);
        when(data.isSynchronized()).thenReturn(true); server.getScheduler().performTicks(1);
        assertEquals(0, oldCalls.get()); assertEquals(1, calls.get()); assertEquals(0, queued());
    }
}
