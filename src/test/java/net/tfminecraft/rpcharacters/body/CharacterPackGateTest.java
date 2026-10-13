package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.papermc.paper.registry.data.dialog.DialogBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class CharacterPackGateTest {
    final Plugin plugin = mock(Plugin.class), core = mock(Plugin.class);
    final Player player = mock(Player.class);
    final PluginManager plugins = mock(PluginManager.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    final MetadataValue metadata = mock(MetadataValue.class);
    final AtomicLong clock = new AtomicLong();
    final AtomicBoolean current = new AtomicBoolean(true);
    final Runnable resume = mock(Runnable.class);
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    final ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
    MockedStatic<Bukkit> bukkit;
    CharacterSelectViewTest.DialogCapture ui;
    CharacterPackGate gate;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
        when(plugins.getPlugin("TFMCCore")).thenReturn(core);
        when(core.isEnabled()).thenReturn(true);
        when(metadata.getOwningPlugin()).thenReturn(core);
        when(metadata.value()).thenReturn("loading");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("PackTest");
        when(player.isOnline()).thenReturn(true);
        when(player.getMetadata(CharacterPackGate.KEY)).thenReturn(List.of(metadata));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(10L))).thenAnswer(call -> {
            tasks.add(call.getArgument(1)); return null;
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(20L))).thenAnswer(call -> {
            refreshes.add(call.getArgument(1)); return null;
        });
        gate = new CharacterPackGate(plugin, true, clock::get);
        ui = new CharacterSelectViewTest.DialogCapture();
    }

    @AfterEach void cleanup() { gate.close(); ui.close(); bukkit.close(); }

    void await() { gate.await(player, current::get, resume); }

    @Test void blockedRoomFeedbackIsVisibleWithoutDependingOnChatOrThePack() {
        gate.roomBlocked(player, current::get);
        assertEquals("Entering character selection", title());
        assertTrue(ui.last().bodyText().contains("Retrying automatically"));
        var leave = ui.last().buttons().getFirst();
        ui.click(leave, mock(Player.class));
        verify(player, never()).kickPlayer(anyString());
        when(player.isOnline()).thenReturn(false);
        ui.click(leave, player);
        verify(player, never()).kickPlayer(anyString());
        when(player.isOnline()).thenReturn(true);
        current.set(false);
        ui.click(leave, player);
        verify(player, never()).kickPlayer(anyString());
        current.set(true);
        ui.click(leave, player);
        verify(player).kickPlayer("See you next time.");
    }
    String title() { return PlainTextComponentSerializer.plainText().serialize(ui.last().base().title()); }
    List<String> buttons() { return ui.last().buttons().stream().map(b ->
            PlainTextComponentSerializer.plainText().serialize(b.label())).toList(); }

    @Test void onlyCurrentEnabledCoreCanReleaseSelection() {
        assertTrue(new CharacterPackGate(plugin, false).ready(player));
        assertFalse(gate.ready(player));
        when(metadata.value()).thenReturn("ready"); assertTrue(gate.ready(player));
        when(metadata.getOwningPlugin()).thenReturn(plugin); assertFalse(gate.ready(player));
        when(metadata.getOwningPlugin()).thenReturn(core);
        when(metadata.value()).thenReturn(Boolean.TRUE); assertFalse(gate.ready(player));
        when(metadata.value()).thenReturn("READY"); assertFalse(gate.ready(player));
        when(player.getMetadata(CharacterPackGate.KEY)).thenReturn(List.of()); assertFalse(gate.ready(player));
        when(core.isEnabled()).thenReturn(false); assertFalse(gate.ready(player));
        when(plugins.getPlugin("TFMCCore")).thenReturn(null); assertFalse(gate.ready(player));
    }

    @Test void waitingDialogHasNoPlayAndOnlyContinuesOnceAfterReady() {
        await(); await();
        assertEquals("Loading TFMC", title()); assertEquals(List.of("Leave"), buttons());
        assertFalse(ui.last().base().pause()); assertFalse(ui.last().base().canCloseWithEscape());
        assertEquals(DialogBase.DialogAfterAction.NONE, ui.last().base().afterAction());
        assertEquals(1, ui.opened.size()); assertEquals(1, tasks.size());
        tasks.remove().run(); verifyNoInteractions(resume);
        var oldLeave = ui.last().buttons().getFirst();
        when(metadata.value()).thenReturn("ready"); tasks.remove().run();
        verify(resume).run(); verify(player).resetTitle(); assertTrue(tasks.isEmpty());
        ui.click(oldLeave, player); verify(player, never()).kickPlayer(anyString());
        verify(player, never()).closeDialog();
    }

    @Test void alreadyReadyResumesWithoutScheduling() {
        when(metadata.value()).thenReturn("ready"); await();
        verify(resume).run(); assertTrue(tasks.isEmpty());
    }

    @Test void failedRequestCanRetryOnlyThroughCoreAndOldButtonsAreInvalid() {
        when(metadata.value()).thenReturn("failed"); await();
        assertEquals(List.of("Retry", "Leave"), buttons());
        var retry = ui.last().buttons().getFirst(); var leave = ui.last().buttons().getLast();
        bukkit.when(() -> Bukkit.dispatchCommand(console, "tfmccore:tcore packretry PackTest")).thenAnswer(call -> {
            assertEquals("Loading TFMC", title(), "Loading must arrive before the pack prompt");
            when(metadata.value()).thenReturn("loading"); return true;
        });
        ui.click(retry, mock(Player.class));
        assertEquals("Could not load TFMC", title());
        ui.click(retry, player); assertEquals("Loading TFMC", title());
        ui.click(leave, player); verify(player, never()).kickPlayer(anyString());
        ui.click(retry, player);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "tfmccore:tcore packretry PackTest"), times(1));
        when(metadata.value()).thenReturn("ready"); tasks.remove().run(); verify(resume).run();
    }

    @Test void unavailableOrTimedOutLoadingDoesNotOfferAnIneffectiveRetry() {
        when(plugins.getPlugin("TFMCCore")).thenReturn(null); await();
        assertEquals("Could not load TFMC", title()); assertEquals(List.of("Leave"), buttons());
        assertTrue(tasks.isEmpty()); verifyNoInteractions(resume);
        gate.quit(player); when(plugins.getPlugin("TFMCCore")).thenReturn(core); await();
        clock.set(CharacterPackGate.TIMEOUT); tasks.remove().run();
        assertEquals("Could not load TFMC", title()); assertEquals(List.of("Leave"), buttons());
        assertTrue(tasks.isEmpty()); verifyNoInteractions(resume);
        ui.click(ui.last().buttons().getFirst(), player); verify(player).kickPlayer("See you next time.");
    }

    @Test void retryFollowsAnExternalAttemptAndPreservesRetryAfterSynchronousFailure() {
        when(metadata.value()).thenReturn("failed"); await(); var retry = ui.last().buttons().getFirst();
        int opened = ui.opened.size();
        when(metadata.value()).thenReturn("loading"); ui.click(retry, player);
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
        assertEquals(opened, ui.opened.size(), "An external pack prompt must not be covered");
        when(metadata.value()).thenReturn("failed"); tasks.remove().run();
        ui.click(ui.last().buttons().getFirst(), player);
        assertEquals(List.of("Retry", "Leave"), buttons()); verifyNoInteractions(resume);
        bukkit.when(() -> Bukkit.dispatchCommand(console, "tfmccore:tcore packretry PackTest")).thenReturn(true);
        ui.click(ui.last().buttons().getFirst(), player);
        assertEquals(List.of("Retry", "Leave"), buttons()); assertTrue(tasks.isEmpty());
        when(metadata.value()).thenReturn("unavailable"); ui.click(ui.last().buttons().getFirst(), player);
        assertEquals(List.of("Leave"), buttons());
    }

    @Test void quitOfflineAndInvalidOwnerCancelScheduledContinuations() {
        await(); var leave = ui.last().buttons().getFirst(); gate.quit(player);
        when(metadata.value()).thenReturn("ready"); tasks.remove().run();
        ui.click(leave, player); verifyNoInteractions(resume); verify(player, never()).kickPlayer(anyString());
        when(metadata.value()).thenReturn("loading"); await(); when(player.isOnline()).thenReturn(false);
        tasks.remove().run(); verifyNoInteractions(resume);
        when(player.isOnline()).thenReturn(true); await(); current.set(false); tasks.remove().run();
        verifyNoInteractions(resume); gate.quit(player);
    }

    @Test void sameUuidReconnectAndLateQuitCannotCancelNewConnection() {
        await(); Runnable oldPoll = tasks.remove();
        Player next = mock(Player.class);
        UUID owner = player.getUniqueId();
        when(next.getUniqueId()).thenReturn(owner); when(next.isOnline()).thenReturn(true);
        when(next.getMetadata(CharacterPackGate.KEY)).thenReturn(List.of(metadata));
        Runnable nextResume = mock(Runnable.class);
        gate.await(next, () -> true, nextResume); gate.quit(player); oldPoll.run();
        when(metadata.value()).thenReturn("ready"); tasks.remove().run();
        verify(nextResume).run(); verifyNoInteractions(resume);
        when(metadata.value()).thenReturn("loading"); gate.await(next, () -> true, nextResume);
        gate.close(); tasks.remove().run(); verify(next).closeDialog();
    }

    @Test void idleFailureRenewsButtonsButNeverReopensAfterRetryOrQuit() {
        when(metadata.value()).thenReturn("failed"); await(); var oldLeave = ui.last().buttons().getLast();
        refreshes.remove().run(); assertEquals(oldLeave, ui.last().buttons().getLast());
        clock.set(java.time.Duration.ofMinutes(5).toNanos());
        refreshes.remove().run(); assertEquals(List.of("Retry", "Leave"), buttons());
        ui.click(oldLeave, player); verify(player, never()).kickPlayer(anyString());
        bukkit.when(() -> Bukkit.dispatchCommand(console, "tfmccore:tcore packretry PackTest")).thenAnswer(call -> {
            when(metadata.value()).thenReturn("loading"); return true;
        });
        ui.click(ui.last().buttons().getFirst(), player); int opened = ui.opened.size();
        refreshes.remove().run(); assertEquals(opened, ui.opened.size());
        when(metadata.value()).thenReturn("failed"); tasks.remove().run(); gate.quit(player);
        opened = ui.opened.size(); refreshes.remove().run(); assertEquals(opened, ui.opened.size());
    }

    @Test void staffRetryAndAlreadyCompletedAttemptAutomaticallyLeaveTheFailureScreen() {
        when(metadata.value()).thenReturn("failed"); await();
        int opened = ui.opened.size();
        when(metadata.value()).thenReturn("loading"); refreshes.remove().run();
        assertEquals(opened, ui.opened.size(), "Staff retry must not repaint over the pack prompt");
        when(metadata.value()).thenReturn("ready"); tasks.remove().run();
        verify(resume).run();
        when(metadata.value()).thenReturn("failed"); await();
        when(metadata.value()).thenReturn("ready"); refreshes.remove().run(); verify(resume,times(2)).run();
        when(metadata.value()).thenReturn("unavailable"); await();
        when(metadata.value()).thenReturn("failed"); refreshes.remove().run();
        assertEquals(List.of("Retry", "Leave"), buttons());
    }

    @Test void staffRetryKeepsVisibleLeaveUsableButInvalidatesRetryWithoutCoveringPrompt() {
        when(metadata.value()).thenReturn("failed"); await();
        var retry = ui.last().buttons().getFirst(); var leave = ui.last().buttons().getLast();
        int opened = ui.opened.size();
        when(metadata.value()).thenReturn("loading"); refreshes.remove().run();
        ui.click(retry, player);
        assertEquals(1, tasks.size()); assertEquals(opened, ui.opened.size());
        ui.click(leave, player); verify(player).kickPlayer("See you next time.");
        bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
    }
}
