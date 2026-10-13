package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.tlibs.character.CharacterRef;

class EstateCommandTest {
    private EstateStaff staff;
    private EstateCollections collections;
    private EstateCommand command;
    private Player player;
    private CommandSender console;
    private final UUID current = UUID.randomUUID();
    private Optional<UUID> session = Optional.of(current);
    private final EstateRecord record = new EstateRecord(UUID.randomUUID(), new CharacterRef(UUID.randomUUID(), UUID.randomUUID()));

    @BeforeEach
    void setUp() {
        staff = mock(EstateStaff.class);
        collections = mock(EstateCollections.class);
        command = new EstateCommand(staff, collections, p -> session);
        player = mock(Player.class);
        when(player.getName()).thenReturn("Mod");
        console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(console.hasPermission(EstateCommand.PERMISSION)).thenReturn(true);
        when(staff.find(anyString())).thenReturn(Optional.of(record));
    }

    private void run(CommandSender sender, String... args) {
        assertTrue(command.onCommand(sender, null, "estate", args));
    }

    @Test
    void playersCollectFromTheirCurrentCharacterAndCannotReachStaffTools() {
        when(collections.collect(player, current)).thenReturn("You collected 1 stack.");
        run(player, "collect");
        verify(player).sendMessage("You collected 1 stack.");
        session = Optional.empty();
        run(player, "collect");
        verify(player).sendMessage("Play the character that inherited, then collect.");
        run(console, "collect");
        verify(console).sendMessage("Only a player can collect an inheritance.");
        run(player, "list");
        run(player);
        verify(player, times(2)).sendMessage("Use /estate collect to collect an inheritance.");
        verifyNoInteractions(staff);
        assertEquals(List.of("collect"), command.onTabComplete(player, null, "estate", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(player, null, "estate", new String[] {"collect", ""}));
    }

    @Test
    void staffSubcommandsReachTheirTools() {
        when(staff.list("frozen")).thenReturn(List.of("a", "b"));
        run(console, "list", "frozen");
        verify(console).sendMessage("a");
        when(staff.list(null)).thenReturn(List.of("all"));
        run(console, "list");
        verify(console).sendMessage("all");
        run(console, "nonsense");
        verify(console).sendMessage(startsWith("Usage: /estate list"));
        run(console, "show");
        verify(console).sendMessage("Name the estate: a death ID, character ID or dead character's name.");
        when(staff.show(record)).thenReturn(List.of("shown"));
        run(console, "show", "Ilvaran", "Thorne");
        verify(staff).find("Ilvaran Thorne");
        verify(console).sendMessage("shown");
        when(staff.hold(record, "CONSOLE")).thenReturn("held");
        run(console, "hold", "Aria");
        when(staff.release(record, "CONSOLE")).thenReturn("released");
        run(console, "release", "Aria");
        when(staff.decideWithoutGuild(record, "CONSOLE")).thenReturn("decided");
        run(console, "decide", "d", "--no-guild");
        run(console, "decide", "d");
        when(staff.assign(record, "all", "Kira Vale", "CONSOLE")).thenReturn("assigned");
        run(console, "assign", "d", "all", "Kira", "Vale");
        when(staff.assignMoneyToGuild(record, "Iron Hand", "CONSOLE")).thenReturn("to guild");
        run(console, "assign", "d", "money", "guild", "Iron", "Hand");
        run(console, "assign", "d", "money");
        when(staff.retry(record, "thievery", "CONSOLE")).thenReturn("retried");
        run(console, "retry", "d", "thievery");
        run(console, "retry", "d");
        when(staff.waive(record, "thievery", "fixed by hand", "CONSOLE")).thenReturn("waived");
        run(console, "waive", "d", "thievery", "fixed", "by", "hand");
        run(console, "waive", "d", "thievery");
        when(staff.revert(record, "CONSOLE")).thenReturn("reverted");
        run(console, "revert", "d");
        run(console, "collect", "d");
        for (String reply : List.of("held", "released", "decided", "Usage: /estate decide <deathId> --no-guild", "assigned",
                "to guild", "Usage: /estate assign <deathId> <money|property|guildlocks|all> <character>", "retried",
                "Usage: /estate retry <deathId> <participant>", "waived", "Usage: /estate waive <deathId> <participant> <note>",
                "reverted", "Only a player can collect.")) {
            verify(console).sendMessage(reply);
        }
        when(player.hasPermission(EstateCommand.PERMISSION)).thenReturn(true);
        when(collections.collectAsStaff(player, record)).thenReturn("collected for staff");
        run(player, "collect", "d");
        verify(player).sendMessage("collected for staff");
        when(staff.find("missing")).thenReturn(Optional.empty());
        run(console, "show", "missing");
        verify(console).sendMessage("No estate matches missing.");
    }

    @Test
    void staffTabCompletionOffersSubcommandsFiltersAndParts() {
        assertEquals(List.of("hold"), command.onTabComplete(console, null, "estate", new String[] {"ho"}));
        assertEquals(7, command.onTabComplete(console, null, "estate", new String[] {"list", ""}).size());
        assertEquals(List.of("money", "property", "guildlocks", "all"),
                command.onTabComplete(console, null, "estate", new String[] {"assign", "d", ""}));
        assertEquals(List.of("--no-guild"), command.onTabComplete(console, null, "estate", new String[] {"decide", "d", ""}));
        assertEquals(List.of(), command.onTabComplete(console, null, "estate", new String[] {"show", "d"}));
    }
}
