package net.tfminecraft.rpcharacters.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.CommandTabCompleter;

class CharacterMailCommandTest {
    @Test void togglesOnlyOwnActiveCharacterAndSavesExplicitOnOff() {
        Player player = mock(Player.class);
        PlayerData data = mock(PlayerData.class);
        PlayerManager manager = mock(PlayerManager.class);
        RPCharacter character = new RPCharacter(null);
        when(data.hasActiveCharacter()).thenReturn(true);
        when(data.getActiveCharacter()).thenReturn(character);
        try (var players = mockStatic(PlayerManager.class); var rpc = mockStatic(RPCharacters.class)) {
            players.when(() -> PlayerManager.get(player)).thenReturn(data);
            rpc.when(RPCharacters::getPlayerManager).thenReturn(manager);
            assertTrue(CharCommand.isPersonaSubcommand("mail"));
            CharCommand.handle(player, "rpcharacter", new String[]{"mail"});
            assertFalse(character.isMailListed());
            CharCommand.handle(player, "rpcharacter", new String[]{"mail", "ON"});
            assertTrue(character.isMailListed());
            CharCommand.handle(player, "rpcharacter", new String[]{"mail", "off"});
            assertFalse(character.isMailListed());
            CharCommand.handle(player, "rpcharacter", new String[]{"mail", "off"});
            assertFalse(character.isMailListed());
            verify(manager, times(4)).savePlayer(player);
            CharCommand.handle(player, "rpcharacter", new String[]{"mail", "invalid"});
            CharCommand.handle(player, "rpcharacter", new String[]{"mail", "on", "someone-else"});
            assertFalse(character.isMailListed());
            when(data.hasActiveCharacter()).thenReturn(false);
            CharCommand.handle(player, "rpcharacter", new String[]{"mail", "on"});
            verifyNoMoreInteractions(manager);
        }
    }

    @Test void consoleIsRejectedAndCompletionsExposeOnOff() {
        CommandSender console = mock(CommandSender.class);
        CharCommand.handle(console, "rpcharacter", new String[]{"mail"});
        verify(console).sendMessage(contains("Only players"));
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("rpcharacter");
        Player player = mock(Player.class);
        CommandTabCompleter completer = new CommandTabCompleter();
        assertEquals(List.of("mail"), completer.onTabComplete(player, command, "rpcharacter", new String[]{"mai"}));
        assertEquals(List.of("on", "off"), completer.onTabComplete(player, command, "rpcharacter", new String[]{"mail", ""}));
    }
}
