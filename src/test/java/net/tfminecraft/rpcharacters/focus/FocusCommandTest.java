package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.RPCharacters;

class FocusCommandTest {
    RPCharacters plugin;
    CommandSender sender;
    Command command;
    FocusCommand handler;

    @BeforeEach void setup() {
        plugin = mock(RPCharacters.class);
        sender = mock(CommandSender.class);
        command = mock(Command.class);
        handler = new FocusCommand(plugin);
    }

    @Test
    void unauthorizedSenderCannotExecuteOrCompleteCommands() {
        handler.onCommand(sender, command, "focus", new String[] {"reload"});
        verify(plugin, never()).reloadFocusConfig();
        assertEquals(List.of(), handler.onTabComplete(sender, command, "focus", new String[] {"restore", ""}));
        verify(sender).sendMessage("You do not have permission to use this command.");
    }

    @Test
    void reloadReportsFailureAndSuccessAccurately() {
        when(sender.hasPermission(FocusCommand.PERMISSION)).thenReturn(true);
        when(plugin.reloadFocusConfig()).thenReturn(false, true);
        handler.onCommand(sender, command, "focus", new String[] {"reload"});
        verify(sender).sendMessage(contains("did not reload"));
        handler.onCommand(sender, command, "focus", new String[] {"reload"});
        verify(sender).sendMessage("Focus configuration reloaded.");
    }

    @Test
    void restoreUsesCurrentOwnerAndReportsTheResult() {
        when(sender.hasPermission(FocusCommand.PERMISSION)).thenReturn(true);
        var target = mock(Player.class);
        when(target.getName()).thenReturn("Alice");
        var service = mock(FocusService.class);
        when(service.restore(target)).thenReturn(true, false);
        when(service.getPoints(target)).thenReturn(150);
        when(service.getMax()).thenReturn(150);
        try (var bukkit = mockStatic(Bukkit.class); var rpc = mockStatic(RPCharacters.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(target);
            rpc.when(RPCharacters::getFocusService).thenReturn(service);
            handler.onCommand(sender, command, "focus", new String[] {"restore", "Alice"});
            verify(sender).sendMessage("Restored focus for Alice (150/150).");
            handler.onCommand(sender, command, "focus", new String[] {"restore", "Alice"});
            verify(sender).sendMessage(contains("Could not restore focus for Alice"));
            handler.onCommand(sender, command, "focus", new String[] {"restore", "Offline"});
            verify(sender).sendMessage("Player not found: Offline");
        }
    }

    @Test
    void unavailableOwnerDoesNotReportSuccessfulRestore() {
        when(sender.hasPermission(FocusCommand.PERMISSION)).thenReturn(true);
        var target = mock(Player.class);
        when(target.getName()).thenReturn("Alice");
        try (var bukkit = mockStatic(Bukkit.class); var rpc = mockStatic(RPCharacters.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(target);
            handler.onCommand(sender, command, "focus", new String[] {"restore", "Alice"});
            verify(sender).sendMessage(contains("Could not restore focus for Alice"));
        }
    }

    @Test
    void completionsRespectCommandPositionAndPrefix() {
        when(sender.hasPermission(FocusCommand.PERMISSION)).thenReturn(true);
        assertEquals(List.of("restore", "reload", "remove", "reset"), handler.onTabComplete(sender, command, "focus", new String[] {"re"}));
        assertEquals(List.of(), handler.onTabComplete(sender, command, "focus", new String[] {"reload", ""}));
        var alice = mock(Player.class);
        var bob = mock(Player.class);
        when(alice.getName()).thenReturn("Alice");
        when(bob.getName()).thenReturn("Bob");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
            assertEquals(List.of("Alice"), handler.onTabComplete(sender, command, "focus", new String[] {"restore", "a"}));
        }
    }

    @Test
    void valueCommandsValidateSaveAndReportActualBalances() {
        when(sender.hasPermission(FocusCommand.PERMISSION)).thenReturn(true);
        var target = mock(Player.class);
        when(target.getName()).thenReturn("Alice");
        var service = mock(FocusService.class);
        when(service.getLoadedPoints(target)).thenReturn(12);
        when(service.getMax()).thenReturn(150);
        when(service.editPoints(eq(target), anyInt(), anyBoolean())).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class); var rpc = mockStatic(RPCharacters.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(target);
            rpc.when(RPCharacters::getFocusService).thenReturn(service);
            for (String[] args : List.of(new String[]{"get", "Alice"}, new String[]{"set", "Alice", "10"},
                    new String[]{"add", "Alice", "-2"}, new String[]{"remove", "Alice", "3"}, new String[]{"reset", "Alice"})) {
                assertTrue(handler.onCommand(sender, command, "focus", args));
            }
            verify(service).editPoints(target, 10, false);
            verify(service).editPoints(target, -2, true);
            verify(service).editPoints(target, -3, true);
            verify(service).editPoints(target, 150, false);
            verify(sender, times(5)).sendMessage("Focus for Alice: 12/150.");
            for (String bad : List.of("NaN", "1.5", "2147483648", "bad"))
                handler.onCommand(sender, command, "focus", new String[]{"set", "Alice", bad});
            handler.onCommand(sender, command, "focus", new String[]{"remove", "Alice", "-1"});
            verify(sender, times(5)).sendMessage(contains("Use whole points"));
            handler.onCommand(sender, command, "focus", new String[]{"get", "Offline"});
            verify(sender).sendMessage("Player not found: Offline");
            when(service.editPoints(target, 10, false)).thenReturn(false);
            handler.onCommand(sender, command, "focus", new String[]{"set", "Alice", "10"});
            verify(sender).sendMessage("Could not save focus for Alice. Check console.");
            when(service.getLoadedPoints(target)).thenReturn(-1);
            handler.onCommand(sender, command, "focus", new String[]{"get", "Alice"});
            rpc.when(RPCharacters::getFocusService).thenReturn(null);
            handler.onCommand(sender, command, "focus", new String[]{"get", "Alice"});
            verify(sender, times(2)).sendMessage("Focus unavailable for Alice. Check their active character.");
            for (String[] args : List.of(new String[]{}, new String[]{"bogus", "Alice"}, new String[]{"get", "Alice", "extra"},
                    new String[]{"set", "Alice"}, new String[]{"get"}))
                handler.onCommand(sender, command, "focus", args);
            verify(sender, times(5)).sendMessage(startsWith("Usage:"));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(target));
            assertEquals(List.of("Alice"), handler.onTabComplete(sender, command, "focus", new String[]{"set", "a"}));
            assertEquals(List.of(), handler.onTabComplete(sender, command, "focus", new String[]{"set", "Alice", ""}));
        }
    }
}
