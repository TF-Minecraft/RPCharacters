package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.EvilRpLoader;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class PlotCharactersTest {
    @TempDir Path directory;
    final List<MockedStatic<?>> statics = new ArrayList<>();
    MockedStatic<Bukkit> bukkit;
    MockedStatic<PlayerManager> players;
    final Player player = mock(Player.class);
    final UUID id = UUID.randomUUID();
    final PlayerData data = mock(PlayerData.class);
    final RPCharacter character = mock(RPCharacter.class);
    RPCharacters previous;

    <T> MockedStatic<T> boundary(MockedStatic<T> mocked) {
        statics.add(mocked);
        return mocked;
    }

    @BeforeEach
    void setup() {
        previous = RPCharacters.plugin;
        RPCharacters.plugin = mock(RPCharacters.class);
        when(RPCharacters.plugin.getDataFolder()).thenReturn(directory.toFile());
        bukkit = boundary(mockStatic(Bukkit.class));
        players = boundary(mockStatic(PlayerManager.class));
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Player");
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
        players.when(() -> PlayerManager.get(id)).thenReturn(data);
        when(data.getActiveCharacter()).thenReturn(character);
        when(data.getCharacters()).thenReturn(List.of(character));
        when(character.getId()).thenReturn("c1");
        when(character.getName()).thenReturn("Ana");
        when(character.getStatus()).thenReturn(Status.ALIVE);
    }

    @AfterEach
    void close() {
        for (int index = statics.size() - 1; index >= 0; index--) statics.get(index).close();
        RPCharacters.plugin = previous;
    }

    @Test
    void theActiveLivingCharacterIsThePlotCharacter() {
        PlotCharacter active = PlotCharacters.active(player);
        assertEquals("c1", active.characterId);
        assertEquals("Ana", active.name);
        assertNull(PlotCharacters.active((Player) null));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        assertSame(player, PlotCharacters.online(active));
        assertNull(PlotCharacters.online(new PlotCharacter("c2", id, "Player", "B")));
        assertNull(PlotCharacters.online(null));
        assertNull(PlotCharacters.online(new PlotCharacter("c1", null, "Player", "Ana")));
        when(character.getStatus()).thenReturn(Status.DEAD);
        assertNull(PlotCharacters.active(player));
        assertTrue(PlotCharacters.dead(active));
        assertFalse(PlotCharacters.dead(new PlotCharacter("c9", id, "Player", "X")));
        assertFalse(PlotCharacters.dead(new PlotCharacter("c1", null, "Player", "Ana")));
        assertFalse(PlotCharacters.dead(null));
        players.when(() -> PlayerManager.get(id)).thenReturn(null);
        assertFalse(PlotCharacters.dead(active));
        when(data.getActiveCharacter()).thenReturn(null);
        assertNull(PlotCharacters.active(player));
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertNull(PlotCharacters.active(player));
    }

    @Test
    void anAccountUnseenForTheInactivityPeriodIsInactive() {
        assertFalse(PlotCharacters.active(null, 0));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        assertTrue(PlotCharacters.active(id, 0));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getLastSeen()).thenReturn(1000L);
        bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(offline);
        assertTrue(PlotCharacters.active(id, 2000));
        assertFalse(PlotCharacters.active(id, 1000 + PlotConfig.inactivity()));
    }

    @Test
    void masksAndAliasesHideTheCharacterName() {
        PlotCharacter ana = new PlotCharacter("c1", id, "Player", "Ana");
        var identity = boundary(mockStatic(DisplayIdentityService.class));
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn("§7Masked figure");
        assertEquals("Masked figure", PlotCharacters.seenAs(player, ana));
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn(" ");
        assertEquals("Ana", PlotCharacters.seenAs(player, ana));
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn(null);
        assertEquals("Ana", PlotCharacters.seenAs(player, ana));
    }

    @Test
    void attributesComeFromTheCharacterWithMmoCoreBaseValuesOnTop() {
        AttributeData attributes = mock(AttributeData.class);
        when(character.getAttributeData()).thenReturn(attributes);
        when(attributes.getAmount(any(net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier.class))).thenReturn(5);
        var mmo = boundary(mockStatic(net.Indyuce.mmocore.api.player.PlayerData.class));
        var mmoData = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
        var mmoAttributes = mock(net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.class);
        var wisdom = mock(net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance.class);
        mmo.when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmoData);
        when(mmoData.getAttributes()).thenReturn(mmoAttributes);
        when(mmoAttributes.getInstance("wisdom")).thenReturn(wisdom);
        when(wisdom.getBase()).thenReturn(12);
        Map<String, Integer> values = PlotCharacters.attributes(player);
        assertEquals(12, values.get("wisdom"));
        assertEquals(5, values.get("intelligence"));
        assertEquals(6, values.size());
        when(data.getActiveCharacter()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> PlotCharacters.attributes(player));
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> PlotCharacters.attributes(player));
    }

    @Test
    void onlyTheStillActiveCharacterIsKilled() {
        var deaths = boundary(mockStatic(PermadeathService.class));
        Player killer = mock(Player.class);
        deaths.when(() -> PermadeathService.killCharacter(player, character, PermakillCause.OTHER, killer)).thenReturn(true);
        assertTrue(PlotCharacters.permakill(player, "c1", killer));
        assertFalse(PlotCharacters.permakill(player, "c2", killer));
        when(data.getActiveCharacter()).thenReturn(null);
        assertFalse(PlotCharacters.permakill(player, "c1", killer));
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertFalse(PlotCharacters.permakill(player, "c1", killer));
    }

    @Test
    void huntingKeepsTheEvilRpSessionRunning() {
        var evil = boundary(mockStatic(EvilRpService.class));
        var loader = boundary(mockStatic(EvilRpLoader.class));
        loader.when(EvilRpLoader::getSessionMs).thenReturn(60_000L);
        PlotCharacters.keepEvilSession(player, true);
        evil.verify(() -> EvilRpService.recordPlay(player));
        when(character.getEvilRpSessionEndsAtMs()).thenReturn(0L);
        PlotCharacters.keepEvilSession(player, false);
        org.mockito.Mockito.verify(character).setEvilRpSessionEndsAtMs(org.mockito.ArgumentMatchers.anyLong());
        when(character.getEvilRpSessionEndsAtMs()).thenReturn(Long.MAX_VALUE);
        PlotCharacters.keepEvilSession(player, false);
        org.mockito.Mockito.verify(character, org.mockito.Mockito.times(1)).setEvilRpSessionEndsAtMs(org.mockito.ArgumentMatchers.anyLong());
        when(data.getActiveCharacter()).thenReturn(null);
        PlotCharacters.keepEvilSession(player, false);
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        PlotCharacters.keepEvilSession(player, false);
    }

    @Test
    void cluesAreLeftUntilTheCharacterHasNoMore() {
        var clues = boundary(mockStatic(ClueGiver.class));
        Location scene = new Location(null, 1, 2, 3);
        SpawnedClue first = mock(SpawnedClue.class);
        when(first.getClueText()).thenReturn("A grey cloak");
        clues.when(() -> ClueGiver.spawnClue(org.mockito.ArgumentMatchers.eq(scene), org.mockito.ArgumentMatchers.eq(player),
                org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(first).thenReturn(null);
        assertEquals(1, PlotCharacters.leaveClues(scene, player, 3));
        assertEquals(0, PlotCharacters.leaveClues(scene, player, 0));
    }

    @Test
    void offlineAccountsAreReadFromTheirSavedActiveCharacter() throws Exception {
        OfflinePlayer account = mock(OfflinePlayer.class);
        when(account.getUniqueId()).thenReturn(id);
        when(account.getName()).thenReturn("Player");
        assertNull(PlotCharacters.offline(null));
        assertNull(PlotCharacters.offline(account));
        Path folder = directory.resolve("data/characterdata").resolve(id.toString());
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("a.json"), "{broken");
        Files.writeString(folder.resolve("b.json"), "{\"id\":\"old\",\"name\":\"Old\",\"active\":\"false\",\"status\":\"ALIVE\"}");
        Files.writeString(folder.resolve("c.json"), "{\"id\":\"dead\",\"name\":\"Dead\",\"active\":\"true\",\"status\":\"DEAD\"}");
        Files.writeString(folder.resolve("d.json"), "{\"id\":\" \",\"name\":{},\"active\":\"true\"}");
        Files.writeString(folder.resolve("e.txt"), "ignored");
        assertNull(PlotCharacters.offline(account));
        Files.writeString(folder.resolve("f.json"), "{\"id\":\"live\",\"name\":\"Live\",\"active\":\"true\",\"status\":\"ALIVE\"}");
        PlotCharacter live = PlotCharacters.offline(account);
        assertEquals("live", live.characterId);
        assertEquals("Player", live.playerName);
        var files = boundary(mockStatic(Files.class, CALLS_REAL_METHODS));
        files.when(() -> Files.list(any())).thenThrow(new java.io.IOException("locked"));
        assertNull(PlotCharacters.offline(account));
        files.verify(() -> Files.readString(any()), never());
    }
}
