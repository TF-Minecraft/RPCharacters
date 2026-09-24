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
        assertEquals(List.of("restore", "reload"), handler.onTabComplete(sender, command, "focus", new String[] {"re"}));
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
}
