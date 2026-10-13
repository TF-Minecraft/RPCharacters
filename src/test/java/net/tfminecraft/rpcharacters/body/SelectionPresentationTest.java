package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionPresentationTest {
    Plugin plugin;
    Player player;
    SelectionPresentation.Transport transport;
    SelectionPresentation presentation;
    List<String> sent;

    @BeforeEach void setup() {
        plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        transport = mock(SelectionPresentation.Transport.class);
        sent = new ArrayList<>();
        doAnswer(call -> { sent.add(call.getArgument(1)); return null; }).when(transport).chat(eq(player), anyString());
        presentation = new SelectionPresentation(plugin, transport);
    }

    @Test void entryQueuesChatAndClearsTransientHudWithoutChangingTheServerPlayer() {
        assertFalse(presentation.chat(player, "before"));
        assertFalse(presentation.transientPacket(player));
        presentation.begin(player);
        assertTrue(presentation.holds(player));
        assertTrue(presentation.transientPacket(player));
        assertFalse(presentation.chat(player, null));
        presentation.mask(player);
        assertTrue(presentation.chat(player, "first"));
        assertTrue(presentation.chat(player, "second"));
        var order = inOrder(transport);
        order.verify(transport).mode(player, true);
        order.verify(transport).clear(player);
        presentation.release(player);
        order.verify(transport).mode(player, false);
        order.verify(transport).fence(eq(player), anyInt());
        order.verify(transport).chat(player, "first");
        order.verify(transport).chat(player, "second");
        assertEquals(List.of("first", "second"), sent);
        assertFalse(presentation.holds(player));
        assertFalse(presentation.chat(player, "after"));
        presentation.release(player);
        verifyNoMoreInteractions(transport);
        verify(player, never()).setGameMode(any());
        verify(player, never()).setAllowFlight(anyBoolean());
        verify(player, never()).setFlying(anyBoolean());
    }

    @Test void arrivalsDuringRestoreAndDrainCannotOvertakeRetainedMessages() {
        presentation.begin(player);
        presentation.chat(player, "old-1");
        presentation.chat(player, "old-2");
        doAnswer(call -> {
            assertFalse(presentation.transientPacket(player));
            assertTrue(presentation.chat(player, "arrived-on-restore"));
            return null;
        })
                .when(transport).mode(player, false);
        doAnswer(call -> {
            String json = call.getArgument(1);
            sent.add(json);
            if (json.equals("old-1")) assertTrue(presentation.chat(player, "arrived-on-drain"));
            return null;
        }).when(transport).chat(eq(player), anyString());
        presentation.release(player);
        assertEquals(List.of("old-1", "old-2", "arrived-on-restore", "arrived-on-drain"), sent);
    }

    @Test void aContinuousProducerYieldsBetweenBoundedBatchesAndCannotBlockTheServerThread() {
        List<Runnable> tasks = new ArrayList<>();
        var flooding = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                tasks.add(call.getArgument(1)); return null;
            });
            presentation.begin(player);
            presentation.chat(player, "first");
            doAnswer(call -> {
                sent.add(call.getArgument(1));
                if (flooding.get()) presentation.chat(player, "arrival");
                return null;
            }).when(transport).chat(eq(player), anyString());
            presentation.release(player);
            assertEquals(101, sent.size());
            assertEquals(1, tasks.size());
            assertTrue(presentation.holds(player));
            presentation.release(player);
            presentation.mask(player);
            presentation.refresh(player);
            flooding.set(false);
            tasks.removeFirst().run();
            assertEquals(102, sent.size());
            assertFalse(presentation.holds(player));
        }
    }

    @Test void shutdownHasAFiniteDrainAndReportsLateArrivalsItCannotRetain() {
        presentation.begin(player);
        presentation.chat(player, "first");
        doAnswer(call -> {
            sent.add(call.getArgument(1));
            presentation.chat(player, "arrival");
            return null;
        }).when(transport).chat(eq(player), anyString());
        presentation.close();
        assertEquals(102, sent.size());
        assertTrue(sent.getLast().contains("1 later messages omitted during shutdown"));
        assertFalse(presentation.available());
    }

    @Test void asyncProducerIsNotBlockedByTheSendAndItsArrivalIsSerializedBehindTheOldQueue() throws Exception {
        presentation.begin(player);
        presentation.chat(player, "old");
        CountDownLatch sending = new CountDownLatch(1), arrived = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var producer = executor.submit(() -> {
                try { assertTrue(sending.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                assertTrue(presentation.chat(player, "async"));
                arrived.countDown();
            });
            doAnswer(call -> {
                String json = call.getArgument(1);
                sent.add(json);
                if (json.equals("old")) {
                    sending.countDown();
                    assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS));
                }
                return null;
            }).when(transport).chat(eq(player), anyString());
            presentation.release(player);
            producer.get();
        }
        assertEquals(List.of("old", "async"), sent);
    }

    @Test void countAndPayloadLimitsReportOmittedMessagesAndKeepTheNewestInOrder() {
        presentation.begin(player);
        for (int i = 0; i < 150; i++) presentation.chat(player, "message-" + i);
        presentation.release(player);
        assertTrue(sent.getFirst().contains("50 earlier messages omitted"));
        assertEquals("message-50", sent.get(1));
        assertEquals("message-149", sent.getLast());
        assertEquals(101, sent.size());

        sent.clear();
        presentation.begin(player);
        String half = "x".repeat(SelectionPresentation.MAX_CHARS / 2);
        presentation.chat(player, half);
        presentation.chat(player, half);
        presentation.chat(player, "new");
        presentation.chat(player, "x".repeat(SelectionPresentation.MAX_CHARS + 1));
        presentation.release(player);
        assertTrue(sent.getFirst().contains("2 earlier messages omitted"));
        assertEquals(List.of(half, "new"), sent.subList(1, sent.size()));
    }

    @Test void interactiveLinkingAllowsItsFeedbackWhileTheOldQueueStillWaitsForPlay() {
        presentation.begin(player);
        presentation.refresh(player);
        verifyNoInteractions(transport);
        presentation.mask(player);
        presentation.chat(player, "old");
        presentation.interact(player);
        assertFalse(presentation.transientPacket(player));
        assertFalse(presentation.chat(player, "link code"));
        assertTrue(sent.isEmpty());
        presentation.refresh(player);
        verify(transport, times(1)).mode(player, true);
        presentation.mask(player);
        presentation.refresh(player);
        verify(transport, times(3)).mode(player, true);
        assertTrue(presentation.chat(player, "new"));
        presentation.release(player);
        assertEquals(List.of("old", "new"), sent);
    }

    @Test void restoredFlightWaitsForTheClientBarrierWithoutHoldingGameplayChat() {
        assertFalse(presentation.blockFlight(player));
        assertFalse(presentation.acknowledge(player, 1));
        presentation.begin(player);
        assertFalse(presentation.blockFlight(player));
        presentation.mask(player);
        assertTrue(presentation.blockFlight(player));
        assertFalse(presentation.acknowledge(player, 1));
        presentation.release(player);
        var fence = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(transport).fence(eq(player), fence.capture());
        assertFalse(presentation.holds(player));
        assertFalse(presentation.chat(player, "gameplay"));
        assertTrue(presentation.blockFlight(player));
        assertFalse(presentation.acknowledge(player, fence.getValue() + 1));
        assertTrue(presentation.acknowledge(player, fence.getValue()));
        assertFalse(presentation.blockFlight(player));
        assertFalse(presentation.acknowledge(player, fence.getValue()));
    }

    @Test void anEarlyAcknowledgementStillKeepsChatOrderedAndAnOldPongCannotUnmaskASelector() {
        presentation.begin(player); presentation.mask(player);
        doAnswer(call -> {
            assertTrue(presentation.acknowledge(player, call.getArgument(1)));
            return null;
        }).when(transport).fence(eq(player), anyInt());
        presentation.chat(player, "old");
        presentation.release(player);
        assertEquals(List.of("old"), sent);
        assertFalse(presentation.blockFlight(player));
        presentation.begin(player); presentation.mask(player);
        presentation.interact(player);
        assertFalse(presentation.blockFlight(player));
        presentation.mask(player);
        assertTrue(presentation.blockFlight(player));
        assertFalse(presentation.acknowledge(player, 1));
    }

    @Test void reconnectAndQuitNeverDeliverAnOldConnectionsQueueToTheReplacement() {
        presentation.begin(player);
        presentation.chat(player, "old connection");
        Player replacement = mock(Player.class);
        UUID owner = player.getUniqueId();
        when(replacement.getUniqueId()).thenReturn(owner);
        presentation.begin(replacement);
        assertFalse(presentation.holds(player));
        assertFalse(presentation.chat(player, "late old"));
        presentation.quit(player);
        assertTrue(presentation.holds(replacement));
        presentation.chat(replacement, "new connection");
        presentation.quit(replacement);
        assertFalse(presentation.holds(replacement));
        presentation.release(replacement);
        verifyNoInteractions(transport);
    }

    @Test void replacementOrQuitDuringDrainStopsFurtherSendsFromTheOldConnection() {
        presentation.begin(player);
        presentation.chat(player, "first");
        presentation.chat(player, "stale");
        doAnswer(call -> { sent.add(call.getArgument(1)); presentation.quit(player); return null; })
                .when(transport).chat(eq(player), anyString());
        presentation.release(player);
        assertEquals(List.of("first"), sent);
    }

    @Test void temporaryPlayersAndUnknownSessionsAreIgnored() {
        Player temporary = mock(Player.class);
        when(temporary.getUniqueId()).thenThrow(new UnsupportedOperationException());
        assertFalse(presentation.holds(temporary));
        assertFalse(presentation.chat(temporary, "early"));
        assertFalse(presentation.transientPacket(temporary));
        presentation.mask(temporary);
        presentation.refresh(temporary);
        presentation.interact(temporary);
        presentation.release(temporary);
        presentation.quit(temporary);
        verifyNoInteractions(transport);
    }

    @Test void shutdownRestoresLivePlayersAndReleasesTheirQueuesButDiscardsOfflineQueues() {
        presentation.begin(player);
        presentation.mask(player);
        presentation.chat(player, "retained");
        Player offline = mock(Player.class);
        when(offline.getUniqueId()).thenReturn(UUID.randomUUID());
        presentation.begin(offline);
        presentation.chat(offline, "offline");
        presentation.close();
        assertEquals(List.of("retained"), sent);
        verify(transport).mode(player, false);
        verify(transport, never()).mode(eq(offline), anyBoolean());
        verify(transport).close();
        assertFalse(presentation.available());
        presentation.close();
    }

    @Test void aPresentationFailureFailsOpenAndStillReleasesChatWhenRestoreIsBroken() {
        presentation.begin(player);
        presentation.chat(player, "retained");
        doThrow(new IllegalStateException("mode unavailable")).when(transport).mode(eq(player), anyBoolean());
        presentation.mask(player);
        assertEquals(List.of("retained"), sent);
        assertFalse(presentation.available());
        verify(transport).close();
    }

    @Test void clearFailureAndReleaseFailureAlsoCloseTheOptionalAdapter() {
        presentation.begin(player);
        doThrow(new IllegalStateException("clear failed")).when(transport).clear(player);
        presentation.mask(player);
        assertFalse(presentation.available());

        presentation = new SelectionPresentation(plugin, transport);
        presentation.begin(player);
        doThrow(new IllegalStateException("restore failed")).when(transport).mode(player, false);
        presentation.release(player);
        assertFalse(presentation.available());
    }

    @Test void linkingRestoreAndListenerRemovalFailuresCannotTrapThePlayerOrBreakShutdown() {
        presentation.begin(player);
        presentation.mask(player);
        presentation.chat(player, "retained");
        doThrow(new IllegalStateException("restore failed")).when(transport).mode(player, false);
        doThrow(new IllegalStateException("listener removal failed")).when(transport).close();
        assertDoesNotThrow(() -> presentation.interact(player));
        assertEquals(List.of("retained"), sent);
        assertFalse(presentation.available());
    }

    @Test void chatTransportFailureDuringShutdownIsContained() {
        presentation.begin(player);
        presentation.chat(player, "retained");
        doThrow(new IllegalStateException("socket unavailable")).when(transport).chat(eq(player), anyString());
        assertDoesNotThrow(presentation::close);
        assertFalse(presentation.available());
    }

    @Test void disabledOrMissingProtocolLibKeepsTheExistingFlow() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginManager manager = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertFalse(SelectionPresentation.create(plugin, false).available());
            verifyNoInteractions(manager);
            assertFalse(SelectionPresentation.create(plugin, true).available());
            Plugin protocol = mock(Plugin.class);
            when(manager.getPlugin("ProtocolLib")).thenReturn(protocol);
            assertFalse(SelectionPresentation.create(plugin, true).available());
        }
        SelectionPresentation absent = new SelectionPresentation(plugin, null);
        absent.begin(player);
        absent.mask(player);
        absent.release(player);
        absent.close();
        assertFalse(absent.holds(player));
    }
}
