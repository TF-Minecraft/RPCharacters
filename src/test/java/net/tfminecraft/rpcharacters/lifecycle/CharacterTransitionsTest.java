package net.tfminecraft.rpcharacters.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
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
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;
import net.tfminecraft.tlibs.character.CharacterDeathEvent;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

class CharacterTransitionsTest {

    @TempDir Path root;
    private ServerMock server;
    private RuntimeTestState state;
    private Plugin eventPlugin;
    private PlayerMock player;
    private PlayerData data;
    private RPCharacter alice;
    private RPCharacter bob;
    private Database database;
    private Logger logger;
    private CharacterDirectory directory;
    private CharacterTransitions runtime;
    private CharacterIdentityService service;
    private MockedStatic<RPCharacters> plugin;
    private MockedStatic<ProstheticTraitRules> prosthetics;
    private MockedStatic<WardrobeService> wardrobe;
    private final Map<Player, PlayerData> loaded = new LinkedHashMap<>();
    private final List<AutoCloseable> boundaries = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final List<Throwable> listenerFailures = new ArrayList<>();
    private final List<String> order = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class);
        Cache.attributes = new ArrayList<>();
        Cache.professions = new ArrayList<>();
        Cache.backgroundTraitTypes = new ArrayList<>();
        eventPlugin = MockBukkit.createMockPlugin("CharacterTransitionsTest");
        RPCharacters.plugin = mock(RPCharacters.class);
        logger = mock(Logger.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(logger);
        boundary(Database.class);
        boundary(MmoCorePlayerReady.class);
        boundary(AttributePointService.class);
        boundary(ProfessionIntegrator.class);
        boundary(MailRecipientDirectory.class);
        prosthetics = boundary(ProstheticTraitRules.class);
        wardrobe = boundary(WardrobeService.class);
        database = mock(Database.class);
        directory = CharacterDirectory.load(root, ignored -> {});
        runtime = new CharacterTransitions(database, directory);
        service = new CharacterIdentityService(directory, runtime);
        plugin = boundary(RPCharacters.class);
        plugin.when(RPCharacters::getCharacterTransitions).thenAnswer(call -> runtime);
        plugin.when(RPCharacters::getCharacterIdentity).thenAnswer(call -> service);
        var players = boundary(PlayerManager.class);
        players.when(() -> PlayerManager.get(any(Player.class))).thenAnswer(call -> loaded.get(call.getArgument(0)));
        players.when(PlayerManager::getOnlineData).thenAnswer(call -> List.copyOf(loaded.values()));
        when(database.trySavePlayer(any())).thenAnswer(call -> {
            order.add("save-account");
            PlayerData account = call.getArgument(0);
            for (RPCharacter character : account.getCharacters()) directory.upsert(account.getUniqueId(), character);
            return true;
        });
        when(database.trySaveCharacter(any(), any())).thenAnswer(call -> {
            order.add("save-death");
            PlayerData account = call.getArgument(0);
            directory.upsert(account.getUniqueId(), call.getArgument(1));
            return true;
        });
        player = server.addPlayer("Owner");
        data = account(player);
        alice = character(player, "Alice", true);
        bob = character(player, "Bob", false);
        data.addCharacter(alice);
        data.addCharacter(bob);
        on(CharacterSessionStartEvent.class, event -> { events.add(event); order.add("start"); });
        on(CharacterSessionEndEvent.class, event -> { events.add(event); order.add("end"); });
        on(CharacterDeathEvent.class, event -> { events.add(event); order.add("death"); });
        on(CharacterActivatedEvent.class, events::add);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            HandlerList.unregisterAll(eventPlugin);
            for (AutoCloseable boundary : boundaries.reversed()) boundary.close();
            state.close();
        } finally {
            MockBukkit.unmock();
        }
        if (!listenerFailures.isEmpty()) throw new AssertionError("Event listener assertion failed", listenerFailures.getFirst());
    }

    @Test
    void selectionPublishesOnlyAfterBothBodyAndProgressAreReady() {
        var bodies = mock(net.tfminecraft.rpcharacters.body.CharacterBodies.class);
        plugin.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        runtime.beginJoin(data);
        runtime.selected(data);
        assertTrue(runtime.currentSession(player).isEmpty());
        assertTrue(events.isEmpty());
        when(bodies.hasLiveBody(player)).thenReturn(true);
        runtime.selected(data);
        assertEquals(UUID.fromString(alice.getId()), runtime.currentSession(player).orElseThrow().ref().characterId());
        runtime.selected(data);
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
    }

    @Test
    void switchingWithBodiesDefersToReconnectWithoutMutatingEitherCharacter() {
        var bodies = mock(net.tfminecraft.rpcharacters.body.CharacterBodies.class);
        plugin.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        CharacterTransitions.switchCharacter(data, bob, null);
        verify(bodies).requestSwitch(data, bob);
        assertSame(alice, data.getActiveCharacter());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        assertTrue(events.isEmpty());
    }

    @Test
    void failedProgressCaptureDoesNotCommitPermanentDeath() {
        join(data);
        var bodies = mock(net.tfminecraft.rpcharacters.body.CharacterBodies.class);
        plugin.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        assertFalse(CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null).killed());
        assertEquals(Status.ALIVE, alice.getStatus());
        assertTrue(alice.isActive());
        verify(database, never()).trySaveCharacter(any(), any());
        verify(bodies, never()).markDead(any(), any());
        assertTrue(events(CharacterDeathEvent.class).isEmpty());
    }

    @Test
    void bodyDeathKeepsReplacementInactiveAndClearsTheDeadCharactersTemporaryAlias() {
        join(data);
        data.setTempAlias("Stranger");
        var bodies = mock(net.tfminecraft.rpcharacters.body.CharacterBodies.class);
        plugin.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        when(bodies.prepareDeath(data, alice)).thenReturn(true);
        var result = CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null);
        assertTrue(result.killed());
        assertTrue(result.wasActive());
        assertNull(result.replacementName());
        assertEquals(Status.DEAD, alice.getStatus());
        assertFalse(alice.isActive());
        assertFalse(bob.isActive());
        assertNull(data.getTempAlias());
        verify(bob, never()).tryActivate();
        verify(alice, never()).deactivate();
        verify(bodies).markDead(data, alice);
        var order = inOrder(bodies, database);
        order.verify(bodies).prepareDeath(data, alice);
        order.verify(database).trySaveCharacter(data, alice);
        order.verify(bodies).markDead(data, alice);
        assertTrue(runtime.currentSession(player).isEmpty());
        assertEquals(1, events(CharacterDeathEvent.class).size());
    }

    @Test
    void joiningWaitsForReadyAndAnnouncesExactlyOneActivation() {
        runtime.ready(data);
        assertTrue(runtime.currentSession(player).isEmpty());
        runtime.beginJoin(data);
        data.setActiveCharacter(bob);
        assertSame(bob, data.getActiveCharacter());
        assertFalse(alice.isActive());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        verify(database, never()).trySavePlayer(any());
        assertTrue(events.isEmpty());
        assertTrue(runtime.currentSession(player).isEmpty());
        runtime.ready(data);
        CharacterSession first = runtime.currentSession(player).orElseThrow();
        assertEquals(UUID.fromString(bob.getId()), first.ref().characterId());
        assertNotNull(bob.getLastPlayedAt());
        assertEquals(bob.getLastPlayedAt(), directory.info(first.ref().characterId()).orElseThrow().lastPlayedAt());
        runtime.ready(data);
        runtime.beginJoin(data);
        runtime.ready(data);
        assertEquals(first, runtime.currentSession(player).orElseThrow());
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
        assertEquals(1, events(CharacterActivatedEvent.class).size());
        verify(database, times(1)).trySavePlayer(data);
    }

    @Test
    void reconnectAndAliceBobAliceHaveDistinctSessions() {
        CharacterSession first = join(data);
        data.setActiveCharacter(bob);
        CharacterSession middle = runtime.currentSession(player).orElseThrow();
        assertFalse(runtime.isCurrent(first));
        assertTrue(runtime.isCurrent(middle));
        data.setActiveCharacter(alice);
        CharacterSession returned = runtime.currentSession(player).orElseThrow();
        assertEquals(first.ref(), returned.ref());
        assertNotEquals(first.sessionId(), returned.sessionId());
        assertFalse(runtime.isCurrent(middle));
        assertTrue(runtime.isCurrent(returned));
        runtime.leave(data, Reason.QUIT);
        assertFalse(runtime.isCurrent(returned));
        CharacterSession reconnected = join(data);
        assertEquals(returned.ref(), reconnected.ref());
        assertNotEquals(returned.sessionId(), reconnected.sessionId());
        assertEquals(List.of(Reason.SWITCH, Reason.SWITCH, Reason.QUIT),
                events(CharacterSessionEndEvent.class).stream().map(CharacterSessionEndEvent::getReason).toList());
        assertEquals(4, events(CharacterSessionStartEvent.class).size());
    }

    @Test
    void endingSessionIsAlreadyNotCurrentWhileOutgoingStateAndBodyRemainIntact() {
        CharacterSession session = join(data);
        data.setTempAlias("Temporary");
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));
        order.clear();
        on(CharacterSessionEndEvent.class, event -> {
            assertEquals(session, event.getSession());
            assertFalse(runtime.isCurrent(session));
            assertTrue(service.currentSession(player).isEmpty());
            assertSame(alice, data.getActiveCharacter());
            assertTrue(alice.isActive());
            assertEquals("Temporary", data.getTempAlias());
            assertEquals(Material.DIAMOND, player.getInventory().getItemInMainHand().getType());
            assertEquals(List.of("end"), order);
        });
        data.setActiveCharacter(bob);
        assertEquals(List.of("end", "save-account", "save-account", "start"), order);
        assertNull(data.getTempAlias());
        assertSame(bob, data.getActiveCharacter());
        assertSame(alice, events(CharacterActivatedEvent.class).getLast().getPrevious());
    }

    @Test
    void synchronousQuitDuringSwitchEndStopsBeforeSavingOrTouchingUnloadedClassState() {
        CharacterSession session = join(data);
        clearInvocations(database);
        evictOnQuit();
        on(CharacterSessionEndEvent.class, event -> player.disconnect());
        data.setActiveCharacter(bob);
        assertFalse(player.isOnline());
        assertFalse(loaded.containsKey(player));
        assertFalse(runtime.isCurrent(session));
        assertTrue(alice.isActive());
        assertFalse(bob.isActive());
        verify(database, never()).trySavePlayer(any());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
        assertEquals(1, events(CharacterSessionEndEvent.class).size());
    }

    @Test
    void nestedTransitionsCannotEndOrReplaceTheSessionBeingDispatched() {
        on(CharacterSessionStartEvent.class, event -> {
            assertTrue(runtime.isCurrent(event.getSession()));
            assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
            assertThrows(IllegalStateException.class, () -> runtime.leave(data, Reason.SWITCH));
        });
        CharacterSession first = join(data);
        on(CharacterSessionEndEvent.class, event -> {
            assertThrows(IllegalStateException.class, () -> runtime.ready(data));
            assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
        });
        runtime.leave(data, Reason.QUIT);
        assertFalse(runtime.isCurrent(first));
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
        assertEquals(1, events(CharacterSessionEndEvent.class).size());
    }

    @Test
    void noSessionStartsForOfflineUnboundMissingOrDeadActiveCharacters() {
        data.bindPlayer(null);
        runtime.beginJoin(data);
        runtime.ready(data);
        data.bindPlayer(player);
        alice.setActiveState(false);
        runtime.beginJoin(data);
        runtime.ready(data);
        alice.setActiveState(true);
        alice.setStatus(Status.DEAD);
        runtime.beginJoin(data);
        runtime.ready(data);
        alice.setStatus(Status.ALIVE);
        player.disconnect();
        runtime.beginJoin(data);
        runtime.ready(data);
        assertTrue(runtime.currentSession(player).isEmpty());
        assertTrue(events.isEmpty());
        verify(database, never()).trySavePlayer(any());
    }

    @Test
    void selectingSameCharacterKeepsSessionAndOnlyRefreshesChangedTraits() {
        CharacterSession session = join(data);
        data.setActiveCharacter(null);
        data.setActiveCharacter(alice);
        verify(alice, never()).update();
        prosthetics.when(() -> ProstheticTraitRules.stripReplacedInjuries(alice)).thenReturn(true);
        data.setActiveCharacter(alice);
        verify(alice).update();
        assertEquals(session, runtime.currentSession(player).orElseThrow());
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
    }

    @Test
    void rejectsUnownedAndDeadSelectionsWithoutEndingTheCurrentSession() {
        CharacterSession session = join(data);
        RPCharacter foreign = character(player, "Foreign", false);
        assertThrows(IllegalArgumentException.class, () -> data.setActiveCharacter(foreign));
        bob.setStatus(Status.DEAD);
        assertThrows(IllegalArgumentException.class, () -> data.setActiveCharacter(bob));
        assertTrue(runtime.isCurrent(session));
        assertTrue(events(CharacterSessionEndEvent.class).isEmpty());
    }

    @Test
    void outgoingSaveFailureLeavesBodyLoadedAndOldSessionEndedWithoutActivatingReplacement() {
        CharacterSession session = join(data);
        when(database.trySavePlayer(data)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
        assertSame(alice, data.getActiveCharacter());
        assertFalse(runtime.isCurrent(session));
        assertTrue(runtime.currentSession(player).isEmpty());
        assertFalse(player.isOnline());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        when(database.trySavePlayer(data)).thenReturn(true);
        // MockBukkit kick removes the player but leaves its reconnect flag set.
        player.disconnect();
        assertTrue(player.reconnect());
        CharacterSession recovered = join(data);
        assertNotEquals(session, recovered);
        data.setActiveCharacter(bob);
        assertEquals(UUID.fromString(bob.getId()), runtime.currentSession(player).orElseThrow().ref().characterId());
        assertEquals(2, events(CharacterSessionEndEvent.class).size());
    }

    @Test
    void activationSaveFailureAndIncompleteDirectoryNeverPublishASession() {
        when(database.trySavePlayer(data)).thenReturn(false);
        runtime.beginJoin(data);
        assertThrows(IllegalStateException.class, () -> runtime.ready(data));
        assertTrue(runtime.currentSession(player).isEmpty());
        assertFalse(player.isOnline());
        assertTrue(events.isEmpty());
        when(database.trySavePlayer(data)).thenReturn(true);
        // MockBukkit kick removes the player but leaves its reconnect flag set.
        player.disconnect();
        assertTrue(player.reconnect());
        RPCharacter malformed = mock(RPCharacter.class);
        when(malformed.getId()).thenReturn("invalid");
        directory.upsert(data.getUniqueId(), malformed);
        runtime.beginJoin(data);
        assertThrows(IllegalStateException.class, () -> runtime.ready(data));
        assertTrue(runtime.currentSession(player).isEmpty());
        assertFalse(player.isOnline());
        assertTrue(events.isEmpty());
    }

    @Test
    void classApplicationFailureDisconnectsWithoutStartingOrSavingTheReplacement() {
        CharacterSession session = join(data);
        doReturn(false).when(bob).tryActivate();
        assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
        assertFalse(player.isOnline());
        assertFalse(runtime.isCurrent(session));
        assertTrue(runtime.currentSession(player).isEmpty());
        assertNull(data.getActiveCharacter());
        assertNull(bob.getLastPlayedAt());
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
        assertEquals(1, events(CharacterActivatedEvent.class).size());
        verify(database, times(2)).trySavePlayer(data);
        verify(logger).severe(contains("Character class was not ready"));
    }

    @Test
    void startListenerQuitEndsTheSessionAndSuppressesTheLegacyActivationEvent() {
        on(CharacterSessionStartEvent.class, event -> runtime.leave(data, Reason.QUIT));
        runtime.beginJoin(data);
        runtime.ready(data);
        assertTrue(runtime.currentSession(player).isEmpty());
        CharacterSession started = events(CharacterSessionStartEvent.class).getFirst().getSession();
        assertFalse(runtime.isCurrent(started));
        assertEquals(started, events(CharacterSessionEndEvent.class).getFirst().getSession());
        assertEquals(Reason.QUIT, events(CharacterSessionEndEvent.class).getFirst().getReason());
        assertTrue(events(CharacterActivatedEvent.class).isEmpty());
    }

    @Test
    void failedSaveAllowsTerminalQuitCleanupInsideTheGuardWithoutDoubleEnding() {
        CharacterSession session = join(data);
        on(PlayerKickEvent.class, event -> {
            runtime.leave(data, Reason.QUIT);
            order.add("quit-cleanup");
        });
        when(database.trySavePlayer(data)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
        assertTrue(order.contains("quit-cleanup"));
        assertFalse(runtime.isCurrent(session));
        assertFalse(player.isOnline());
        assertEquals(1, events(CharacterSessionEndEvent.class).size());
    }

    @Test
    void activeDeathSavesBeforePublishingThenStartsReplacement() {
        CharacterSession session = join(data);
        order.clear();
        on(CharacterSessionEndEvent.class, event -> {
            assertEquals(Reason.DEATH, event.getReason());
            assertFalse(runtime.isCurrent(session));
            assertSame(alice, data.getActiveCharacter());
            assertEquals(Status.ALIVE, alice.getStatus());
        });
        on(CharacterDeathEvent.class, event -> {
            assertEquals(List.of("end", "save-death", "death"), order);
            assertEquals(alice.getDeathId(), event.getDeathId());
            assertEquals(alice.getDiedAt(), event.getAt());
            assertEquals(session.ref(), event.getCharacter());
            assertEquals("COMMAND", event.getCause());
            assertNull(event.getKillerId());
            assertNull(event.getKillerCharacter());
            assertEquals(Status.DEAD, alice.getStatus());
            assertFalse(alice.isActive());
        });
        var result = CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null);
        assertTrue(result.killed());
        assertTrue(result.wasActive());
        assertEquals("Bob", result.replacementName());
        assertSame(bob, data.getActiveCharacter());
        assertEquals(List.of("end", "save-death", "death", "save-account", "start"), order);
        assertFalse(runtime.isCurrent(session));
        assertEquals(UUID.fromString(bob.getId()), runtime.currentSession(player).orElseThrow().ref().characterId());
        assertSame(alice, events(CharacterActivatedEvent.class).getLast().getPrevious());
        wardrobe.verify(() -> WardrobeService.refreshActiveAsync(player));
    }

    @Test
    void synchronousQuitDuringDeathEndAbandonsDeathBeforePersistenceOrMmoCleanup() {
        CharacterSession session = join(data);
        evictOnQuit();
        on(CharacterSessionEndEvent.class, event -> player.disconnect());
        var result = CharacterTransitions.kill(player, alice, PermakillCause.OTHER, null);
        assertFalse(result.killed());
        assertFalse(player.isOnline());
        assertFalse(loaded.containsKey(player));
        assertFalse(runtime.isCurrent(session));
        assertEquals(Status.ALIVE, alice.getStatus());
        assertNull(alice.getDeathId());
        verify(database, never()).trySaveCharacter(any(), any());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        assertTrue(events(CharacterDeathEvent.class).isEmpty());
    }

    @Test
    void synchronousQuitAfterCommittedDeathPreservesDeathAndSkipsUnloadedClassAndReplacement() {
        CharacterSession session = join(data);
        clearInvocations(database);
        evictOnQuit();
        on(CharacterDeathEvent.class, event -> player.disconnect());
        var result = CharacterTransitions.kill(player, alice, PermakillCause.OTHER, null);
        assertTrue(result.killed());
        assertTrue(result.wasActive());
        assertNull(result.replacementName());
        assertFalse(player.isOnline());
        assertFalse(loaded.containsKey(player));
        assertFalse(runtime.isCurrent(session));
        assertEquals(Status.DEAD, alice.getStatus());
        assertNotNull(alice.getDeathId());
        assertFalse(alice.isActive());
        verify(database).trySaveCharacter(data, alice);
        verify(database, never()).trySavePlayer(any());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        assertEquals(1, events(CharacterDeathEvent.class).size());
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
        wardrobe.verifyNoInteractions();
    }

    @Test
    void committedDeathStillReportsKilledWhenTheReplacementCannotActivate() {
        CharacterSession session = join(data);
        doReturn(false).when(bob).tryActivate();
        var result = CharacterTransitions.kill(player, alice, PermakillCause.SACRIFICE, player);
        assertTrue(result.killed());
        assertTrue(result.wasActive());
        assertNull(result.replacementName());
        assertEquals(Status.DEAD, alice.getStatus());
        assertEquals(1, events(CharacterDeathEvent.class).size());
        assertFalse(player.isOnline());
        assertFalse(runtime.isCurrent(session));
        assertTrue(runtime.currentSession(player).isEmpty());
        verify(logger).severe(contains("Character class was not ready"));
        String aliceId = alice.getId();
        verify(logger).log(eq(Level.SEVERE), contains("Could not activate a replacement after the death of " + aliceId),
                any(IllegalStateException.class));
        wardrobe.verifyNoInteractions();
    }

    @Test
    void committedDeathSurvivesAFailedReplacementSaveOrAnUnexpectedActivationError() {
        join(data);
        when(database.trySavePlayer(data)).thenReturn(false);
        var unsaved = CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null);
        assertTrue(unsaved.killed());
        assertNull(unsaved.replacementName());
        assertFalse(player.isOnline());
        verify(logger).severe(contains("Could not save character activation"));

        when(database.trySavePlayer(data)).thenReturn(true);
        player.disconnect();
        assertTrue(player.reconnect());
        RPCharacter carol = character(player, "Carol", false);
        data.addCharacter(carol);
        join(data);
        doThrow(new IllegalArgumentException("MMOCore class missing")).when(carol).tryActivate();
        var broken = CharacterTransitions.kill(player, bob, PermakillCause.COMMAND, null);
        assertTrue(broken.killed());
        assertNull(broken.replacementName());
        assertFalse(player.isOnline(), "An unexpected activation error still disconnects the player");
        assertEquals(2, events(CharacterDeathEvent.class).size());
    }

    @Test
    void switchAfterCommitDisconnectsInsteadOfThrowingAndOtherwiseSwitches() {
        CharacterSession session = join(data);
        CharacterTransitions.switchAfterCommit(data, alice);
        assertTrue(runtime.isCurrent(session));
        doReturn(false).when(bob).tryActivate();
        assertDoesNotThrow(() -> CharacterTransitions.switchAfterCommit(data, bob));
        assertFalse(player.isOnline());
        assertTrue(runtime.currentSession(player).isEmpty());
        String bobId = bob.getId();
        verify(logger).log(eq(Level.SEVERE), contains("Could not activate character " + bobId), any(IllegalStateException.class));
        data.bindPlayer(null);
        RPCharacter foreign = character(player, "Foreign", false);
        assertDoesNotThrow(() -> CharacterTransitions.switchAfterCommit(data, foreign));
    }

    @Test
    void cancelledOrAlreadyDeadDeathsDoNotSaveOrEndSessions() {
        CharacterSession session = join(data);
        on(CharacterPermakillEvent.class, event -> event.setCancelled(true));
        assertFalse(CharacterTransitions.kill(player, alice, PermakillCause.OTHER, null).killed());
        assertEquals(Status.ALIVE, alice.getStatus());
        assertNull(alice.getDeathId());
        assertTrue(runtime.isCurrent(session));
        bob.setStatus(Status.DEAD);
        assertFalse(CharacterTransitions.kill(player, bob, PermakillCause.OTHER, null).killed());
        verify(database, never()).trySaveCharacter(any(), any());
        assertTrue(events(CharacterSessionEndEvent.class).isEmpty());
        assertTrue(events(CharacterDeathEvent.class).isEmpty());
    }

    @Test
    void deathDuringPendingJoinSelectsReplacementWithoutTouchingTheUnloadedClass() {
        runtime.beginJoin(data);
        var result = CharacterTransitions.kill(player, alice, PermakillCause.OTHER, null);
        assertTrue(result.killed());
        assertTrue(result.wasActive());
        assertEquals("Bob", result.replacementName());
        assertEquals(Status.DEAD, alice.getStatus());
        assertSame(bob, data.getActiveCharacter());
        verify(alice, never()).deactivate();
        verify(bob, never()).tryActivate();
        assertTrue(runtime.currentSession(player).isEmpty());
        assertTrue(events(CharacterSessionStartEvent.class).isEmpty());
        assertTrue(events(CharacterSessionEndEvent.class).isEmpty());
        assertEquals(1, events(CharacterDeathEvent.class).size());
        runtime.ready(data);
        assertEquals(UUID.fromString(bob.getId()), runtime.currentSession(player).orElseThrow().ref().characterId());
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
    }

    @Test
    void selfInflictedDeathRetainsTheOutgoingKillerIdentityAfterItsSessionEnds() {
        CharacterSession session = join(data);
        assertTrue(CharacterTransitions.kill(player, alice, PermakillCause.SACRIFICE, player).killed());
        CharacterDeathEvent death = events(CharacterDeathEvent.class).getFirst();
        assertFalse(runtime.isCurrent(session));
        assertEquals(session.ref(), death.getCharacter());
        assertEquals(player.getUniqueId(), death.getKillerId());
        assertEquals(session.ref(), death.getKillerCharacter());
    }

    @Test
    void inactiveDeathRetainsActiveSessionAndIncludesTheKillersCurrentCharacter() {
        CharacterSession session = join(data);
        PlayerMock killer = server.addPlayer("Killer");
        PlayerData killerData = account(killer);
        killerData.addCharacter(character(killer, "Executioner", true));
        CharacterSession killerSession = join(killerData);
        var result = CharacterTransitions.kill(player, bob, PermakillCause.SACRIFICE, killer);
        assertTrue(result.killed());
        assertFalse(result.wasActive());
        assertNull(result.replacementName());
        assertTrue(runtime.isCurrent(session));
        CharacterDeathEvent event = events(CharacterDeathEvent.class).getFirst();
        assertEquals(killer.getUniqueId(), event.getKillerId());
        assertEquals(killerSession.ref(), event.getKillerCharacter());
        assertEquals("SACRIFICE", event.getCause());
        assertTrue(events(CharacterSessionEndEvent.class).isEmpty());
        verify(bob, never()).deactivate();
    }

    @Test
    void deathWithNoReplacementEndsSessionAndPreservesKillerAccountWithoutSession() {
        CharacterSession session = join(data);
        bob.setStatus(Status.MISSING);
        PlayerMock killer = server.addPlayer("UnboundKiller");
        var result = CharacterTransitions.kill(player, alice, PermakillCause.STRIKES, killer);
        assertTrue(result.killed());
        assertTrue(result.wasActive());
        assertNull(result.replacementName());
        assertNull(data.getActiveCharacter());
        assertFalse(runtime.isCurrent(session));
        assertTrue(runtime.currentSession(player).isEmpty());
        var event = events(CharacterDeathEvent.class).getFirst();
        assertEquals(killer.getUniqueId(), event.getKillerId());
        assertNull(event.getKillerCharacter());
        assertEquals(1, events(CharacterSessionStartEvent.class).size());
        wardrobe.verifyNoInteractions();
    }

    @Test
    void saveFailureRestoresStatusAndPriorDeathRecordButCannotReviveTheOldSession() {
        CharacterSession session = join(data);
        UUID previousDeath = UUID.randomUUID();
        Instant previousAt = Instant.ofEpochMilli(1000);
        alice.setDeathRecord(previousDeath, previousAt);
        when(database.trySaveCharacter(data, alice)).thenAnswer(call -> {
            assertEquals(Status.DEAD, alice.getStatus());
            assertFalse(alice.isActive());
            assertNotEquals(previousDeath, alice.getDeathId());
            assertNotEquals(previousAt, alice.getDiedAt());
            return false;
        });
        assertFalse(CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null).killed());
        assertEquals(Status.ALIVE, alice.getStatus());
        assertTrue(alice.isActive());
        assertEquals(previousDeath, alice.getDeathId());
        assertEquals(previousAt, alice.getDiedAt());
        assertTrue(events(CharacterDeathEvent.class).isEmpty());
        assertFalse(runtime.isCurrent(session));
        assertTrue(runtime.currentSession(player).isEmpty());
        assertFalse(player.isOnline());
        verify(logger).severe(contains("Permanent death was not saved"));
        doReturn(true).when(database).trySaveCharacter(data, alice);
        // MockBukkit kick removes the player but leaves its reconnect flag set.
        player.disconnect();
        assertTrue(player.reconnect());
        CharacterSession recovered = join(data);
        assertNotEquals(session, recovered);
        assertTrue(CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null).killed());
        assertEquals(1, events(CharacterDeathEvent.class).size());
        assertFalse(runtime.isCurrent(session));
    }

    @Test
    void revivalThenAnotherDeathHasADistinctPersistedDeathId() {
        join(data);
        assertTrue(CharacterTransitions.kill(player, bob, PermakillCause.OTHER, null).killed());
        UUID first = bob.getDeathId();
        bob.setStatus(Status.ALIVE);
        assertTrue(CharacterTransitions.kill(player, bob, PermakillCause.OTHER, null).killed());
        assertNotEquals(first, bob.getDeathId());
        assertEquals(List.of(first, bob.getDeathId()), events(CharacterDeathEvent.class).stream()
                .map(CharacterDeathEvent::getDeathId).toList());
        verify(database, times(2)).trySaveCharacter(data, bob);
        assertTrue(events(CharacterSessionEndEvent.class).isEmpty());
    }

    @Test
    void unownedOrUnloadedCharacterCannotBeKilledThroughTheInstalledRuntime() {
        RPCharacter foreign = character(player, "Foreign", false);
        assertFalse(CharacterTransitions.kill(player, foreign, PermakillCause.COMMAND, null).killed());
        loaded.clear();
        assertFalse(CharacterTransitions.kill(player, alice, PermakillCause.COMMAND, null).killed());
        assertEquals(Status.ALIVE, alice.getStatus());
        assertEquals(Status.ALIVE, foreign.getStatus());
        verify(database, never()).trySaveCharacter(any(), any());
    }

    @Test
    void shutdownEndsEverySessionOnceAndBlocksSessionsStartedInsideEndCallbacks() {
        CharacterSession first = join(data);
        PlayerMock other = server.addPlayer("Other");
        PlayerData otherData = account(other);
        otherData.addCharacter(character(other, "Other character", true));
        CharacterSession second = join(otherData);
        on(CharacterSessionEndEvent.class, event -> {
            assertEquals(Reason.SHUTDOWN, event.getReason());
            assertFalse(runtime.isCurrent(event.getSession()));
            assertNotNull(loaded.get(event.getPlayer()).getActiveCharacter());
            runtime.beginJoin(data);
            assertThrows(IllegalStateException.class, () -> runtime.ready(data));
        });
        runtime.shutdown();
        runtime.shutdown();
        assertFalse(runtime.isCurrent(first));
        assertFalse(runtime.isCurrent(second));
        assertTrue(runtime.currentSession(player).isEmpty());
        assertTrue(runtime.currentSession(other).isEmpty());
        assertEquals(2, events(CharacterSessionEndEvent.class).size());
        assertEquals(2, events(CharacterSessionStartEvent.class).size());
        assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
    }

    @Test
    void leaveBeforeReadyCancelsPendingActivationAndAnOfflinePlayerHasNoCurrentSession() {
        runtime.beginJoin(data);
        runtime.leave(data, Reason.QUIT);
        runtime.ready(data);
        assertTrue(events.isEmpty());
        CharacterSession session = join(data);
        player.disconnect();
        assertTrue(runtime.currentSession(player).isEmpty());
        assertFalse(runtime.isCurrent(session));
    }

    @Test
    void legacyCallsWithoutRuntimeStillActivateAndKillWithoutPublishingAuthoritativeEvents() {
        plugin.when(RPCharacters::getCharacterTransitions).thenReturn(null);
        data.bindPlayer(null);
        data.setActiveCharacter(bob);
        assertSame(bob, data.getActiveCharacter());
        assertNotNull(alice.getLastLocation());
        data.bindPlayer(player);
        assertTrue(CharacterTransitions.kill(player, bob, PermakillCause.OTHER, null).killed());
        assertSame(alice, data.getActiveCharacter());
        assertTrue(events(CharacterDeathEvent.class).isEmpty());
        assertTrue(events(CharacterSessionStartEvent.class).isEmpty());
        assertEquals(2, events(CharacterActivatedEvent.class).size());
    }

    @Test
    void allRuntimeEntryPointsRejectCallsOffTheMainThread() {
        CharacterSession session = join(data);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, CharacterTransitions::requireMainThread);
            assertThrows(IllegalStateException.class, () -> runtime.currentSession(player));
            assertThrows(IllegalStateException.class, () -> runtime.isCurrent(session));
            assertThrows(IllegalStateException.class, () -> runtime.beginJoin(data));
            assertThrows(IllegalStateException.class, () -> runtime.ready(data));
            assertThrows(IllegalStateException.class, () -> runtime.leave(data, Reason.QUIT));
            assertThrows(IllegalStateException.class, runtime::shutdown);
            assertThrows(IllegalStateException.class, () -> data.setActiveCharacter(bob));
            assertThrows(IllegalStateException.class, () -> CharacterTransitions.kill(player, alice, PermakillCause.OTHER, null));
        }
        assertTrue(runtime.isCurrent(session));
    }

    private PlayerData account(Player player) {
        PlayerData account = new PlayerData(player);
        loaded.put(player, account);
        return account;
    }

    private void evictOnQuit() {
        on(PlayerQuitEvent.class, event -> {
            runtime.leave(data, Reason.QUIT);
            loaded.remove(event.getPlayer());
            alice.setMMOClass(null);
        });
    }

    private RPCharacter character(Player owner, String name, boolean active) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("name", "Human");
        RPCharacter character = spy(new RPCharacter(owner));
        character.setName(name);
        character.setRace(new Race("human", config));
        character.setMMOClass("HUMAN");
        character.setActiveState(active);
        doAnswer(call -> { character.setActiveState(true); return true; }).when(character).tryActivate();
        return character;
    }

    private CharacterSession join(PlayerData account) {
        runtime.beginJoin(account);
        runtime.ready(account);
        return runtime.currentSession(account.getPlayer()).orElseThrow();
    }

    private <T> MockedStatic<T> boundary(Class<T> type) {
        MockedStatic<T> boundary = mockStatic(type);
        boundaries.add(boundary);
        return boundary;
    }

    private <T extends Event> void on(Class<T> type, Consumer<T> listener) {
        server.getPluginManager().registerEvent(type, new Listener() {}, EventPriority.NORMAL, (ignored, event) -> {
            try { listener.accept(type.cast(event)); }
            catch (Throwable failure) { listenerFailures.add(failure); }
        }, eventPlugin);
    }

    private <T extends Event> List<T> events(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
