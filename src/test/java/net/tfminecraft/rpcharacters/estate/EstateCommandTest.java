package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Decision;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;
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
    private final EstateRecord record = new EstateRecord(UUID.fromString("d0000000-0000-0000-0000-000000000001"),
            new CharacterRef(UUID.randomUUID(), UUID.randomUUID()));

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
        record.name = "Ilvaran Thorne";
        when(staff.records()).thenReturn(List.of(record));
        when(staff.find(anyString())).thenReturn(Optional.empty());
        for (String key : List.of("d", "Aria", "Ilvaran Thorne")) when(staff.find(key)).thenReturn(Optional.of(record));
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
        when(staff.assign(record, "property", "Lio", "CONSOLE")).thenReturn("assigned by name");
        run(console, "assign", "Ilvaran", "Thorne", "property", "Lio");
        verify(console).sendMessage("assigned by name");
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
        when(staff.stranded(record)).thenReturn(true);
        when(collections.collectAsStaff(player, record, true)).thenReturn("collected for staff");
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
        record.state = EstateState.DECIDED;
        record.decision = new Decision(Instant.EPOCH, 3, null, null, null, Part.FROZEN, Part.WAITING, Part.FROZEN, Part.SENDER);
        assertEquals(List.of("money", "property", "guildlocks", "all"),
                command.onTabComplete(console, null, "estate", new String[] {"assign", record.deathId.toString(), ""}));
        record.state = EstateState.OPEN;
        assertEquals(List.of("--no-guild"), command.onTabComplete(console, null, "estate", new String[] {"decide", record.deathId.toString(), ""}));
        assertEquals(List.of(record.deathId.toString()),
                command.onTabComplete(console, null, "estate", new String[] {"show", "d"}));
        assertEquals(List.of(), command.onTabComplete(console, null, "estate", new String[] {"show", "g"}));
    }
    private List<String> tab(String... args) {
        return command.onTabComplete(console, null, "estate", args);
    }

    @Test void consoleCompletionExcludesCollectionWhileRetainingStaffTools() {
        assertEquals(List.of("list", "show", "hold", "release", "decide", "assign", "retry", "waive", "revert"), tab(""));
        assertEquals(List.of("show"), tab("Sh"));
        assertEquals(List.of(), tab("Co"));
        assertEquals(List.of(), tab("CoLlEcT", ""));
        assertEquals(List.of(), tab("collect", "d"));
        assertEquals(List.of(), tab("collect", "Ilvaran", ""));
        assertEquals(List.of(), tab("collect", record.deathId.toString(), ""));
        when(console.hasPermission(EstateCommand.PERMISSION)).thenReturn(false);
        assertEquals(List.of(), tab(""));
        assertEquals(List.of(), tab("Co"));
        assertEquals(List.of(), tab("Sh"));
        assertEquals(List.of(), tab("collect", ""));
        verifyNoInteractions(staff, collections);
    }

    @Test void staffPlayersCanCompleteCollectionEstateIdsAndNameSuffixes() {
        when(player.hasPermission(EstateCommand.PERMISSION)).thenReturn(true);
        assertTrue(command.onTabComplete(player, null, "estate", new String[]{""}).contains("collect"));
        assertEquals(List.of("collect"), command.onTabComplete(player, null, "estate", new String[]{"Co"}));
        assertEquals(List.of(record.deathId.toString(), "Ilvaran Thorne"),
                command.onTabComplete(player, null, "estate", new String[]{"CoLlEcT", ""}));
        assertEquals(List.of(record.deathId.toString()),
                command.onTabComplete(player, null, "estate", new String[]{"collect", "D000"}));
        assertEquals(List.of("Ilvaran Thorne"),
                command.onTabComplete(player, null, "estate", new String[]{"collect", "iLv"}));
        assertEquals(List.of("Ilvaran Thorne"),
                command.onTabComplete(player, null, "estate", new String[]{"collect", "Ilvaran"}));
        assertEquals(List.of("Thorne"),
                command.onTabComplete(player, null, "estate", new String[]{"collect", "Ilvaran", ""}));
        assertEquals(List.of("Thorne"),
                command.onTabComplete(player, null, "estate", new String[]{"collect", "iLvArAn", "tH"}));
        verify(staff, never()).find(anyString()); verifyNoInteractions(collections);
    }

    @Test void rootsFiltersAndEveryArgumentRespectCaseAndCurrentTokenPrefixes() {
        assertEquals(List.of(), tab()); assertEquals(List.of(), tab("unknown", ""));
        assertEquals(List.of(), command.onTabComplete(player, null, "estate", new String[]{"x"}));
        assertEquals(List.of("collect"), command.onTabComplete(player, null, "estate", new String[]{"Co"}));
        assertEquals(List.of("frozen"), tab("LiSt", "Fr"));
        assertEquals(List.of(), tab("list", "frozen", ""));
        assertEquals(List.of(), tab("decide", "Ilvaran", "Thorne", "x"));
        assertEquals(List.of("--no-guild"), tab("decide", "Ilvaran", "Thorne", "--n"));
        assertEquals(List.of(), tab("decide", "Ilvaran", "Thorne", "--no-guild", ""));
        assertEquals(List.of(), tab("show", "missing", ""));
    }

    @Test void estateIdsAndMultiwordNameSuffixesAreUsableAtTheirActualBoundary() {
        String id = record.deathId.toString();
        for (String verb : List.of("show", "hold", "release", "decide", "assign", "retry", "waive", "revert")) {
            assertTrue(tab(verb, "").contains(id), verb);
            assertEquals(List.of(id), tab(verb, id.substring(0, 8).toUpperCase(java.util.Locale.ROOT)), verb);
        }
        assertEquals(List.of("Ilvaran Thorne"), tab("assign", "ILv"));
        assertEquals(List.of("Ilvaran Thorne"), tab("assign", "Ilvaran"));
        assertEquals(List.of("Thorne"), tab("assign", "Ilvaran", ""));
        assertEquals(List.of("Thorne"), tab("assign", "iLvArAn", "tH"));
        assertEquals(List.of("Thorne"), tab("assign", "Ilvaran", "Thorne"));
        assertEquals(List.of("--no-guild"), tab("decide", "Ilvaran", "Thorne", ""));
        assertEquals(List.of("--no-guild"), tab("decide", record.testator.characterId().toString(), ""));
        assertEquals(List.of(), tab("assign", "Ilvaran", "Thorne", ""));
        verify(staff, never()).find(anyString()); verifyNoInteractions(collections);
    }

    @Test void longestEstateNameWinsBeforePartsEvenWhenItsFirstWordIsAnEstate() {
        var shortName = new EstateRecord(UUID.randomUUID(), new CharacterRef(UUID.randomUUID(), UUID.randomUUID()));
        shortName.name = "Ilvaran";
        when(staff.records()).thenReturn(List.of(shortName, record));
        record.state = EstateState.DECIDED;
        record.decision = new Decision(Instant.EPOCH, 3, null, null, null, Part.FROZEN, Part.WAITING, Part.SENDER, Part.SENDER);
        assertEquals(List.of("Thorne"), tab("assign", "Ilvaran", ""));
        assertEquals(List.of("property"), tab("assign", "Ilvaran", "Thorne", "Pr"));
        assertEquals(List.of("money", "property", "all"), tab("assign", "Ilvaran", "Thorne", ""));
        assertEquals(List.of("guild"), tab("assign", "Ilvaran", "Thorne", "MoNeY", "Gu"));
        assertEquals(List.of(), tab("assign", "Ilvaran", "Thorne", "property", ""));
        assertEquals(List.of(), tab("assign", "Ilvaran", "Thorne", "money", "guild", ""));
        assertEquals(List.of(), tab("decide", "Ilvaran", "Thorne", ""));
        assertEquals(List.of(), tab("show", "Ilvaran", "Thorne", ""));
        when(staff.assign(record, "property", "Kira", "CONSOLE")).thenReturn("assigned");
        run(console, "assign", "Ilvaran", "Thorne", "property", "Kira");
        verify(staff).assign(record, "property", "Kira", "CONSOLE");
    }

    @Test void onlyAssignablePartsAndApplicableBlockedParticipantsAreSuggested() {
        record.state = EstateState.DECIDED;
        record.decision = new Decision(Instant.EPOCH, 3, null, null, null, Part.SENDER, Part.WAITING, Part.FROZEN, Part.SENDER);
        record.participants.put("thievery", new Report("BLOCKED", 3, "blocked", Map.of(), List.of(), Instant.EPOCH));
        record.participants.put("old", new Report("BLOCKED", 2, "old", Map.of(), List.of(), Instant.EPOCH));
        record.participants.put("done", new Report("DONE", 3, null, Map.of(), List.of(), Instant.EPOCH));
        String id = record.deathId.toString();
        assertEquals(List.of("property", "guildlocks", "all"), tab("assign", id, ""));
        assertEquals(List.of(), tab("assign", id, "money", ""));
        assertEquals(List.of("old", "thievery"), tab("retry", id, ""));
        assertEquals(List.of("thievery"), tab("retry", "Ilvaran", "Thorne", "Th"));
        assertEquals(List.of("thievery"), tab("waive", id, ""));
        assertEquals(List.of(), tab("waive", id, "thievery", ""));
        record.decision = new Decision(Instant.EPOCH, 3, null, null, null, Part.SENDER, Part.SENDER, Part.SENDER, Part.SENDER);
        assertEquals(List.of(), tab("assign", id, ""));
        record.state = EstateState.REVERTED;
        assertEquals(List.of(), tab("retry", id, ""));
        assertEquals(List.of(), tab("waive", id, ""));
    }

    @Test void realStaffCompletionUsesOnlyLoadedRecordsWithoutWorldOrPersistenceCalls() throws Exception {
        EstateCoordinator coordinator = mock(EstateCoordinator.class); EstateBook book = mock(EstateBook.class);
        EstateWorld world = mock(EstateWorld.class);
        when(coordinator.book()).thenReturn(book); when(coordinator.world()).thenReturn(world);
        when(book.all()).thenReturn(List.of(record));
        EstateStaff realStaff = new EstateStaff(coordinator, java.util.logging.Logger.getAnonymousLogger());
        command = new EstateCommand(realStaff, collections, p -> session);
        assertTrue(tab("show", "").contains(record.deathId.toString()));
        assertEquals(List.of("Thorne"), tab("assign", "Ilvaran", "Th"));
        assertEquals(List.of("--no-guild"), tab("decide", "Ilvaran", "Thorne", ""));
        assertEquals(List.of(), tab("decide", record.testator.characterId().toString(), "x"));
        verifyNoInteractions(world, collections);
        verify(book, never()).save(any()); verify(book, never()).log(anyString(), anyString());
    }

}
