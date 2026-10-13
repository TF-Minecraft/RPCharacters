package net.tfminecraft.rpcharacters.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.body.CharacterBodies;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;

class CharacterIdentityServiceTest {

    @TempDir Path root;
    private ServerMock server;
    private RuntimeTestState state;
    private PlayerMock player;
    private PlayerData data;
    private RPCharacter alice;
    private Database database;
    private CharacterDirectory directory;
    private CharacterTransitions runtime;
    private CharacterIdentityService service;
    private MockedStatic<RPCharacters> plugin;
    private MockedStatic<MaskService> masks;
    private final Map<Player, PlayerData> loaded = new LinkedHashMap<>();
    private final List<AutoCloseable> boundaries = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class);
        Cache.attributes = new ArrayList<>();
        Cache.professions = new ArrayList<>();
        Cache.backgroundTraitTypes = new ArrayList<>();
        boundary(Database.class);
        database = mock(Database.class);
        when(database.trySavePlayer(any())).thenReturn(true);
        install();
        plugin = boundary(RPCharacters.class);
        plugin.when(RPCharacters::getCharacterIdentity).thenAnswer(call -> service);
        plugin.when(RPCharacters::getCharacterTransitions).thenAnswer(call -> runtime);
        var players = boundary(PlayerManager.class);
        players.when(() -> PlayerManager.get(any(Player.class))).thenAnswer(call -> loaded.get(call.getArgument(0)));
        players.when(PlayerManager::getOnlineData).thenAnswer(call -> List.copyOf(loaded.values()));
        masks = boundary(MaskService.class);
        player = server.addPlayer("AccountName");
        data = new PlayerData(player);
        loaded.put(player, data);
        alice = character("Alice", true);
        data.addCharacter(alice);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            for (AutoCloseable boundary : boundaries.reversed()) boundary.close();
            state.close();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void allLookupMethodsSeeImmutableLiveEditsIncludingHiddenInactiveAndDeadCharacters() {
        RPCharacter deceased = character("Deceased", false);
        deceased.setStatus(Status.DEAD);
        UUID death = UUID.randomUUID();
        deceased.setDeathRecord(death, Instant.ofEpochMilli(3000));
        deceased.setLastPlayedAt(Instant.ofEpochMilli(4000));
        data.addCharacter(deceased);
        RPCharacter missing = character("Missing", false);
        missing.setStatus(Status.MISSING);
        data.addCharacter(missing);
        alice.setAlias("Public alias");
        alice.setHidden(true);
        alice.setLastPlayedAt(Instant.ofEpochMilli(1000));
        List<CharacterInfo> first = service.characters(player.getUniqueId()).orElseThrow();
        CharacterInfo snapshot = service.info(UUID.fromString(alice.getId())).orElseThrow();
        assertEquals(3, first.size());
        assertEquals(Optional.of(death), service.deathId(UUID.fromString(deceased.getId())));
        assertEquals("Alice", snapshot.name());
        assertEquals(CharacterStatus.DEAD, service.lastPlayed(player.getUniqueId()).orElseThrow().status());
        assertTrue(first.stream().anyMatch(character -> character.status() == CharacterStatus.MISSING));
        Trait trait = mock(Trait.class);
        when(trait.getId()).thenReturn("STRONG");
        Race race = mock(Race.class);
        when(race.getId()).thenReturn("elf");
        alice.setName("Changed real name");
        alice.setRace(race);
        alice.setTraits(new ArrayList<>(List.of(trait)));
        alice.setOnlinePlaytimeSeconds(150);
        alice.setLastPlayedAt(Instant.ofEpochMilli(5000));
        CharacterInfo changed = service.info(UUID.fromString(alice.getId())).orElseThrow();
        assertEquals("Changed real name", changed.name());
        assertEquals("elf", changed.raceId());
        assertEquals(Set.of("strong"), changed.traits());
        assertEquals(150, changed.playtimeSeconds());
        assertEquals(changed, service.lastPlayed(player.getUniqueId()).orElseThrow());
        assertEquals("Alice", snapshot.name());
        assertEquals(0, snapshot.playtimeSeconds());
        assertTrue(snapshot.traits().isEmpty());
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, () -> changed.traits().add("new"));
    }

    @Test
    void estateLookupsSeeLiveDeathsAndReadableAccounts() {
        RPCharacter deceased = character("Deceased", false);
        data.addCharacter(deceased);
        deceased.setStatus(Status.DEAD);
        UUID death = UUID.randomUUID();
        deceased.setDeathRecord(death, Instant.ofEpochMilli(3000));
        assertEquals(List.of(death), service.deaths().stream().map(CharacterDirectory.Death::deathId).toList());
        assertFalse(service.unreadable(UUID.fromString(deceased.getId())));
    }

    @Test
    void currentSessionAndIsCurrentDelegateOnlyForReadableOwners() {
        assertTrue(service.currentSession(player).isEmpty());
        CharacterSession session = join();
        assertEquals(session, service.currentSession(player).orElseThrow());
        assertTrue(service.isCurrent(session));
        RPCharacter malformed = mock(RPCharacter.class);
        when(malformed.getId()).thenReturn("invalid");
        directory.upsert(player.getUniqueId(), malformed);
        assertTrue(service.currentSession(player).isEmpty());
        assertFalse(service.isCurrent(session));
        assertTrue(service.characters(player.getUniqueId()).isEmpty());
        assertTrue(service.info(session.ref().characterId()).isEmpty());
        assertTrue(service.lastPlayed(player.getUniqueId()).isEmpty());
    }

    @Test
    void liveOverlaysCannotHealCorruptAccountsOrClaimAnotherOwnersCharacter() throws Exception {
        Path accounts = Files.createDirectories(root.resolve("playerdata"));
        Files.writeString(accounts.resolve(player.getUniqueId() + ".json"), "{broken");
        install();
        assertTrue(service.characters(player.getUniqueId()).isEmpty());
        assertTrue(service.info(UUID.fromString(alice.getId())).isEmpty());
        Files.delete(accounts.resolve(player.getUniqueId() + ".json"));
        install();
        CharacterSession session = join();
        PlayerMock other = server.addPlayer("Other");
        PlayerData otherData = new PlayerData(other);
        loaded.put(other, otherData);
        RPCharacter duplicate = mock(RPCharacter.class);
        when(duplicate.getId()).thenReturn(alice.getId());
        when(duplicate.getName()).thenReturn("Different owner");
        when(duplicate.getStatus()).thenReturn(Status.ALIVE);
        when(duplicate.getTraits()).thenReturn(List.of());
        otherData.addCharacter(duplicate);
        CharacterIdentityService.saved(other.getUniqueId(), duplicate);
        assertFalse(service.isCurrent(session));
        assertTrue(service.currentSession(player).isEmpty());
        assertTrue(service.characters(player.getUniqueId()).isEmpty());
        assertTrue(service.characters(other.getUniqueId()).isEmpty());
        assertTrue(service.info(UUID.fromString(alice.getId())).isEmpty());
    }

    @Test
    void savedAndRemovedHooksUpdateOfflineRecordsAndIgnoreUnavailableProvider() {
        UUID offline = UUID.randomUUID();
        RPCharacter character = character("Offline", false);
        UUID id = UUID.fromString(character.getId());
        assertEquals(List.of(), service.characters(offline).orElseThrow());
        CharacterIdentityService.saved(offline, character);
        assertEquals("Offline", service.info(id).orElseThrow().name());
        assertEquals(offline, service.characters(offline).orElseThrow().getFirst().ref().ownerId());
        CharacterIdentityService.removed(offline, character.getId());
        assertTrue(service.info(id).isEmpty());
        assertEquals(List.of(), service.characters(offline).orElseThrow());
        plugin.when(RPCharacters::getCharacterIdentity).thenReturn(null);
        CharacterIdentityService.saved(offline, character);
        CharacterIdentityService.removed(offline, character.getId());
        assertTrue(service.info(id).isEmpty());
    }

    @Test
    void legacyHeirsComeFromLoadedBodiesOnly() {
        UUID owner = player.getUniqueId();
        UUID heir = UUID.randomUUID();
        assertFalse(service.legacyHeirsReady());
        assertTrue(service.legacyHeir(owner).isEmpty());

        CharacterBodies bodies = mock(CharacterBodies.class);
        when(bodies.migrationHeir(owner)).thenReturn(heir);
        plugin.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        assertTrue(service.legacyHeirsReady());
        assertEquals(Optional.of(new CharacterRef(owner, heir)), service.legacyHeir(owner));
        assertTrue(service.legacyHeir(UUID.randomUUID()).isEmpty());
    }

    @Test
    void displayNamesFollowAliasesAndMasksWhileCanonicalLookupKeepsRealName() {
        alice.setAlias("Public alias");
        assertEquals("Public alias", ClueFormatter.stripColor(service.displayName(player)));
        data.setTempAlias("Temporary alias");
        assertEquals("Temporary alias", ClueFormatter.stripColor(service.displayName(player)));
        masks.when(() -> MaskService.isMasked(player)).thenReturn(true);
        masks.when(MaskService::getMaskedLabel).thenReturn("Masked stranger");
        assertEquals("Masked stranger", service.displayName(player));
        assertEquals("Alice", service.info(UUID.fromString(alice.getId())).orElseThrow().name());
        loaded.clear();
        assertEquals(player.getName(), service.displayName(player));
    }

    @Test
    void findByNameMatchesOnlyTheRealNamesOfLivingCharactersOnlineAndOffline() {
        alice.setAlias("Public alias");
        data.setTempAlias("Temporary alias");
        masks.when(() -> MaskService.isMasked(player)).thenReturn(true);
        masks.when(MaskService::getMaskedLabel).thenReturn("Masked stranger");
        assertEquals("Masked stranger", service.displayName(player));
        RPCharacter deceased = character("Alice", false);
        deceased.setStatus(Status.DEAD);
        data.addCharacter(deceased);
        UUID offline = UUID.randomUUID();
        RPCharacter namesake = character("ALICE", false);
        CharacterIdentityService.saved(offline, namesake);

        List<CharacterInfo> found = service.findByName("alice");
        assertEquals(Set.of(UUID.fromString(alice.getId()), UUID.fromString(namesake.getId())),
                Set.copyOf(found.stream().map(info -> info.ref().characterId()).toList()));
        assertEquals(Set.of(player.getUniqueId(), offline),
                Set.copyOf(found.stream().map(info -> info.ref().ownerId()).toList()));
        assertEquals(List.of(), service.findByName("Public alias"));
        assertEquals(List.of(), service.findByName("Temporary alias"));
        assertEquals(List.of(), service.findByName("Masked stranger"));

        // An unsaved rename on a loaded account counts at once, as it does for info.
        alice.setName("Kira");
        assertEquals(List.of(service.info(UUID.fromString(alice.getId())).orElseThrow()), service.findByName("kira"));
        assertEquals(List.of(service.info(UUID.fromString(namesake.getId())).orElseThrow()), service.findByName("Alice"));
        alice.setStatus(Status.DEAD);
        assertEquals(List.of(), service.findByName("Kira"));
    }

    @Test
    void allProviderMethodsRequireMainThread() {
        CharacterSession session = join();
        UUID owner = player.getUniqueId();
        UUID id = UUID.fromString(alice.getId());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> service.currentSession(player));
            assertThrows(IllegalStateException.class, () -> service.isCurrent(session));
            assertThrows(IllegalStateException.class, () -> service.info(id));
            assertThrows(IllegalStateException.class, () -> service.deathId(id));
            assertThrows(IllegalStateException.class, () -> service.characters(owner));
            assertThrows(IllegalStateException.class, () -> service.lastPlayed(owner));
            assertThrows(IllegalStateException.class, () -> service.findByName("Alice"));
            assertThrows(IllegalStateException.class, () -> service.displayName(player));
        }
        assertTrue(service.isCurrent(session));
    }

    @Test
    void hooksOffTheMainThreadQueueTheirUpdateSoACompletedSaveStillSucceeds() {
        RPCharacters.plugin = mock(RPCharacters.class);
        Logger logger = mock(Logger.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(logger);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Runnable> queued = new ArrayList<>();
        when(scheduler.runTask(eq(RPCharacters.plugin), any(Runnable.class))).thenAnswer(call -> {
            queued.add(call.getArgument(1));
            return null;
        });
        UUID offline = UUID.randomUUID();
        RPCharacter character = character("Saved off thread", false);
        UUID capturedDeath = UUID.randomUUID();
        character.setDeathRecord(capturedDeath, null);
        UUID id = UUID.fromString(character.getId());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            CharacterIdentityService.saved(offline, character);
            CharacterIdentityService.removed(offline, character.getId());
            doThrow(new IllegalPluginAccessException("Plugin attempted to register task while disabled"))
                    .when(scheduler).runTask(eq(RPCharacters.plugin), any(Runnable.class));
            CharacterIdentityService.saved(offline, character);
        }
        verify(logger).warning(contains("while disabled"));
        assertEquals(2, queued.size());
        assertTrue(service.info(id).isEmpty());
        // The queued update keeps what was saved, not later edits to the live character.
        character.setName("Renamed after the save");
        character.setDeathRecord(UUID.randomUUID(), null);
        queued.getFirst().run();
        assertEquals("Saved off thread", service.info(id).orElseThrow().name());
        assertEquals(capturedDeath, service.deathId(id).orElseThrow());
        queued.getLast().run();
        assertTrue(service.info(id).isEmpty());
    }

    @Test
    void anOffThreadSaveThatCannotBeSnapshottedQueuesTheLiveUpdate() {
        RPCharacters.plugin = mock(RPCharacters.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Runnable> queued = new ArrayList<>();
        when(scheduler.runTask(eq(RPCharacters.plugin), any(Runnable.class))).thenAnswer(call -> {
            queued.add(call.getArgument(1));
            return null;
        });
        UUID offline = UUID.randomUUID();
        RPCharacter invalid = mock(RPCharacter.class);
        when(invalid.getId()).thenReturn("not-a-uuid");
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            CharacterIdentityService.saved(offline, invalid);
        }
        assertEquals(1, queued.size());
        queued.getFirst().run();
        assertTrue(service.characters(offline).isEmpty());
    }

    @Test
    void ownerLookupsCatchALiveDuplicateOnAnotherOnlineAccount() {
        join();
        PlayerMock other = server.addPlayer("Other");
        PlayerData otherData = new PlayerData(other);
        loaded.put(other, otherData);
        RPCharacter duplicate = mock(RPCharacter.class);
        when(duplicate.getId()).thenReturn(alice.getId().toUpperCase(java.util.Locale.ROOT));
        when(duplicate.getName()).thenReturn("Different owner");
        when(duplicate.getStatus()).thenReturn(Status.ALIVE);
        when(duplicate.getTraits()).thenReturn(List.of());
        otherData.addCharacter(duplicate);
        assertTrue(service.characters(player.getUniqueId()).isEmpty());
        assertTrue(service.lastPlayed(player.getUniqueId()).isEmpty());
    }

    @Test
    void lookupsOnlyRebuildTheAccountsTheyNeed() {
        PlayerMock other = server.addPlayer("Bystander");
        PlayerData otherData = new PlayerData(other);
        loaded.put(other, otherData);
        RPCharacter bystander = mock(RPCharacter.class);
        UUID bystanderId = UUID.randomUUID();
        when(bystander.getId()).thenReturn(bystanderId.toString());
        when(bystander.getName()).thenReturn("Bystander");
        when(bystander.getStatus()).thenReturn(Status.ALIVE);
        when(bystander.getTraits()).thenReturn(List.of());
        when(bystander.isActive()).thenReturn(true);
        otherData.addCharacter(bystander);
        clearInvocations(bystander);
        alice.setLastPlayedAt(Instant.ofEpochMilli(1000));
        assertEquals("Alice", service.info(UUID.fromString(alice.getId())).orElseThrow().name());
        assertEquals(1, service.characters(player.getUniqueId()).orElseThrow().size());
        assertEquals("Alice", service.lastPlayed(player.getUniqueId()).orElseThrow().name());
        verify(bystander, never()).getName();
        assertTrue(service.info(UUID.randomUUID()).isEmpty());
        verify(bystander, never()).getName();
        assertEquals("Bystander", service.lastPlayed(other.getUniqueId()).orElseThrow().name());
        when(bystander.getName()).thenReturn("Renamed");
        assertEquals("Renamed", service.info(bystanderId).orElseThrow().name());
        assertEquals("Renamed", service.characters(other.getUniqueId()).orElseThrow().getFirst().name());
    }

    private void install() throws Exception {
        directory = CharacterDirectory.load(root, ignored -> {});
        runtime = new CharacterTransitions(database, directory);
        service = new CharacterIdentityService(directory, runtime);
    }

    private RPCharacter character(String name, boolean active) {
        RPCharacter character = new RPCharacter(player);
        character.setName(name);
        character.setActiveState(active);
        return character;
    }

    private CharacterSession join() {
        runtime.beginJoin(data);
        runtime.ready(data);
        return runtime.currentSession(player).orElseThrow();
    }

    private <T> MockedStatic<T> boundary(Class<T> type) {
        MockedStatic<T> boundary = mockStatic(type);
        boundaries.add(boundary);
        return boundary;
    }
}
