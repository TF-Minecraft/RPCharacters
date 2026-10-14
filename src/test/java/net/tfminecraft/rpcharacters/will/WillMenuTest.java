package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.estate.EstateHooks;
import net.tfminecraft.rpcharacters.estate.EstateLink;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.will.WillScreens.Button;
import net.tfminecraft.rpcharacters.will.WillScreens.Screen;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

class WillMenuTest {
    private RuntimeTestState state;
    private final List<AutoCloseable> statics = new ArrayList<>();
    private final List<Screen> shown = new ArrayList<>();
    private final WillServiceTest.Characters characters = new WillServiceTest.Characters();
    private MockedStatic<Characters> identity;
    private MockedStatic<EstateLink> estates;
    private Database database;
    private Player player;
    private World world;
    private PlayerData data;
    private RPCharacter aria;
    private CharacterSession session;
    private CharacterInfo kira;

    private <T> MockedStatic<T> stub(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @BeforeEach
    void setUp() {
        state = new RuntimeTestState(WillMenu.class, WillService.class);
        WillMenu.screens = (viewer, screen) -> shown.add(screen);
        database = mock(Database.class);
        when(database.trySaveCharacterDurably(any(), any())).thenReturn(true);
        WillService.database = database;
        WillService.lookup = characters;
        world = mock(World.class);
        player = player(0);
        data = new PlayerData(player.getUniqueId());
        aria = new RPCharacter(WillQueueTest.owner());
        aria.setName("§aAria");
        data.addCharacter(aria);
        session = new CharacterSession(new CharacterRef(data.getUniqueId(), UUID.fromString(aria.getId())), 1);
        kira = characters.add("Kira", CharacterStatus.ALIVE);
        stub(PlayerManager.class).when(() -> PlayerManager.get(player)).thenReturn(data);
        stub(RosterSyncService.class);
        identity = stub(Characters.class);
        identity.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
        identity.when(() -> Characters.isCurrent(session)).thenReturn(true);
        identity.when(() -> Characters.findByName(anyString())).thenReturn(List.of());
        identity.when(() -> Characters.findByName("Kira")).thenReturn(List.of(kira));
        estates = stub(EstateLink.class);
        Race elf = mock(Race.class);
        when(elf.getName()).thenReturn("§2Elf");
        stub(RaceLoader.class).when(() -> RaceLoader.getByString("elf")).thenReturn(elf);
        when(world.getPlayers()).thenReturn(List.of(player));
    }

    private Player player(double x) {
        Player one = mock(Player.class);
        when(one.getUniqueId()).thenReturn(UUID.randomUUID());
        when(one.getWorld()).thenReturn(world);
        when(one.getLocation()).thenReturn(new Location(world, x, 64, 0));
        return one;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        state.close();
    }

    private Screen last() {
        return shown.getLast();
    }

    private void click(String label, Map<String, String> values) {
        Button button = last().buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow();
        button.action().accept(values::get);
    }

    private void click(String label) {
        click(label, Map.of());
    }

    @Test
    void namingAnHeirByNameConfirmsAndSaves() {
        EstateHooks hooks = mock(EstateHooks.class);
        when(hooks.holdings(UUID.fromString(aria.getId()))).thenReturn(List.of("340.25 in pouch and bank", "2 pets", "1 animal"));
        estates.when(EstateLink::current).thenReturn(hooks);
        WillMenu.open(player);
        assertEquals("Will of Aria", last().title());
        assertTrue(last().lines().contains("No heir is named."));
        assertTrue(last().lines().contains("• 340.25 in pouch and bank"));
        assertTrue(last().lines().contains("• 2 pets"));
        assertTrue(last().lines().contains("• 1 animal"));
        assertEquals(List.of("Name an heir", "Close"), last().buttons().stream().map(Button::label).toList());
        click("Name an heir");
        assertEquals("heir", last().input().key());
        click("Find", Map.of("heir", " Kira "));
        assertEquals(List.of("Name Kira (Elf, " + kira.ref().characterId().toString().substring(0, 8) + ") as your heir?"),
                last().lines());
        click("Confirm");
        verify(player).sendMessage("Your heir is named.");
        assertEquals(kira.ref().characterId(), aria.getWill().heir());
        verify(player).closeDialog();
    }

    @Test
    void revokingClearsTheWillAndAnUnfoundHeirIsSaidPlainly() {
        aria.setWill(new CharacterWill(UUID.randomUUID(), 1, 1L, null));
        WillMenu.open(player);
        assertTrue(last().lines().contains("Heir: a character who can't be found now"));
        assertTrue(last().lines().contains("Your estate holds nothing recorded today."));
        click("Revoke");
        assertEquals(List.of("Revoke your will? Nobody will be named."), last().lines());
        click("Back");
        assertEquals("Will of Aria", last().title());
        click("Revoke");
        click("Confirm");
        verify(player).sendMessage("Your will is revoked.");
        assertFalse(aria.getWill().named());
        WillMenu.open(player);
        click("Close");
        verify(player, times(2)).closeDialog();
    }

    @Test
    void noMatchSeveralMatchesAndNearbyPlayersAreOffered() {
        Player near = player(5);
        Player far = player(100);
        Player idle = player(3);
        when(world.getPlayers()).thenReturn(List.of(player, near, far, idle));
        CharacterInfo bran = characters.add("Bran", CharacterStatus.ALIVE);
        CharacterSession nearSession = new CharacterSession(bran.ref(), 2);
        identity.when(() -> Characters.currentSession(near)).thenReturn(Optional.of(nearSession));
        identity.when(() -> Characters.currentSession(idle)).thenReturn(Optional.empty());
        identity.when(() -> Characters.info(bran.ref().characterId())).thenReturn(Optional.of(bran));
        WillMenu.open(player);
        click("Name an heir");
        assertEquals(List.of("Find", "Name Bran", "Back"), last().buttons().stream().map(Button::label).toList());
        click("Find", new HashMap<>());
        assertEquals("No match", last().title());
        click("Back");
        CharacterInfo own = new CharacterInfo(new CharacterRef(data.getUniqueId(), UUID.randomUUID()), "Kira",
                CharacterStatus.ALIVE, null, Set.of(), 0, null);
        CharacterInfo self = new CharacterInfo(session.ref(), "Kira", CharacterStatus.ALIVE, "elf", Set.of(), 0, null);
        characters.infos.put(own.ref().characterId(), own);
        identity.when(() -> Characters.findByName("Kira")).thenReturn(List.of(kira, own, self));
        click("Find", Map.of("heir", "Kira"));
        assertEquals("Several characters", last().title());
        assertEquals(2, last().input().options().size());
        assertTrue(last().input().options().get(1)[1].endsWith(", on your account)"));
        int before = shown.size();
        click("Choose", Map.of("pick", "nobody"));
        assertEquals(before, shown.size());
        click("Choose", Map.of("pick", own.ref().characterId().toString()));
        assertTrue(last().lines().getFirst().startsWith("Name Kira (null, "));
        click("Back");
        click("Name an heir");
        click("Name Bran");
        assertTrue(last().lines().getFirst().startsWith("Name Bran (Elf"));
    }

    @Test
    void aChangedSessionAndFailedSavesAreExplained() {
        WillMenu.open(player);
        identity.when(() -> Characters.isCurrent(session)).thenReturn(false);
        click("Name an heir");
        verify(player).sendMessage("Your character changed. Open /will again.");
        identity.when(() -> Characters.isCurrent(session)).thenReturn(true);
        when(database.trySaveCharacterDurably(any(), any())).thenReturn(false);
        WillMenu.open(player);
        click("Name an heir");
        click("Find", Map.of("heir", "Kira"));
        click("Confirm");
        verify(player).sendMessage("Your will could not be saved. Try again or contact staff.");
        characters.unreadable.add(kira.ref().characterId());
        WillMenu.open(player);
        click("Name an heir");
        click("Find", Map.of("heir", "Kira"));
        click("Confirm");
        verify(player).sendMessage("That character can't be checked right now. Try again later.");
        characters.unreadable.clear();
        characters.infos.put(kira.ref().characterId(), new CharacterInfo(kira.ref(), "Kira", CharacterStatus.DEAD, "elf",
                Set.of(), 0, null));
        WillMenu.open(player);
        click("Name an heir");
        click("Find", Map.of("heir", "Kira"));
        click("Confirm");
        verify(player).sendMessage("That character can't be your heir.");
    }

    @Test
    void openingNeedsAPlayedCharacterAndTheCommandOpensTheMenu() {
        identity.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
        WillMenu.open(player);
        verify(player).sendMessage("Play a character to make its will.");
        try (var menu = mockStatic(WillMenu.class)) {
            assertTrue(new WillCommand().onCommand(player, null, "will", new String[0]));
            menu.verify(() -> WillMenu.open(player));
            var console = mock(org.bukkit.command.CommandSender.class);
            assertTrue(new WillCommand().onCommand(console, null, "will", new String[0]));
            verify(console).sendMessage("Only a player can make a will.");
        }
        Player stranger = player(0);
        identity.when(() -> Characters.currentSession(stranger)).thenReturn(Optional.of(session));
        WillMenu.open(stranger);
        verify(stranger).sendMessage("Play a character to make its will.");
    }
}
