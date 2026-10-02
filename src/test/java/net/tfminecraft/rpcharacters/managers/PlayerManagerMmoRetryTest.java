package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.clues.discovery.ClueAdminModeService;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager;
import net.tfminecraft.rpcharacters.clues.discovery.InvestigationPointService;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.grave.GraveVisualManager;
import net.tfminecraft.rpcharacters.identity.TempAliasService;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.professions.ProfessionPointService;

/** Exercises the real readiness queue and join callback; only other plugin boundaries are mocked. */
class PlayerManagerMmoRetryTest {
    private static final List<String> PENDING = List.of("strength.2", "dexterity.3");
    private final List<AutoCloseable> boundaries = new ArrayList<>();
    private final AtomicBoolean synchronizedData = new AtomicBoolean();
    private final AtomicReference<PlayerData> loaded = new AtomicReference<>();
    private final Map<String, AttributeInstance> instances = new LinkedHashMap<>();
    private RuntimeTestState state;
    private ServerMock server;
    private PlayerMock player;
    private RPCharacters plugin;
    private Logger logger;
    private PlayerManager manager;
    private PlayerData data;
    private net.Indyuce.mmocore.api.player.PlayerData mmo;
    private MockedStatic<AttributePointService> attributes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class, PlayerManager.class, CreationManager.class,
                MmoCorePlayerReady.class);
        // Isolate the public registry while preserving the surrounding suite's state.
        Field registry = PlayerManager.class.getDeclaredField("data");
        registry.setAccessible(true);
        ((List<PlayerData>) registry.get(null)).clear();
        CreationManager.activeCreators.clear();
        Cache.noCharacterFreeze = false;
        Cache.lackingCluesFreeze = false;
        Cache.excessCharactersFreeze = false;
        plugin = mock(RPCharacters.class);
        logger = mock(Logger.class);
        when(plugin.getName()).thenReturn("RPCharacters");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(logger);
        RPCharacters.plugin = plugin;
        player = server.addPlayer("PendingAttributes");
        data = new PlayerData(player);
        data.setPendingMmoAttributeRemoves(PENDING);
        loaded.set(data);
        var databases = mockConstruction(Database.class, (database, context) ->
                when(database.loadPlayerData(player.getUniqueId())).thenAnswer(call -> loaded.get()));
        boundaries.add(databases);
        manager = new PlayerManager();
        boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);
        boundary(PermissionGroupService.class);
        boundary(InvestigationPointService.class);
        attributes = boundary(AttributePointService.class);
        boundary(ClassService.class);
        boundary(ProfessionPointService.class);
        boundary(TempAliasService.class);
        boundary(ClueAdminModeService.class);
        var clues = mock(ClueDiscoveryVisualManager.class);
        boundary(ClueDiscoveryVisualManager.class).when(ClueDiscoveryVisualManager::get).thenReturn(clues);
        var graves = mock(GraveVisualManager.class);
        boundary(GraveVisualManager.class).when(GraveVisualManager::get).thenReturn(graves);
        mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
        when(mmo.isSynchronized()).thenAnswer(call -> synchronizedData.get());
        PlayerAttributes externalAttributes = mock(PlayerAttributes.class);
        when(mmo.getAttributes()).thenReturn(externalAttributes);
        when(externalAttributes.getInstance(anyString())).thenAnswer(call -> instances.get(call.getArgument(0)));
        for (String id : List.of("strength", "dexterity")) {
            // Real MMOCore numeric state and non-negative base semantics, without plugin stat updates.
            AttributeInstance instance = spy(new PlayerAttributes(mmo).new AttributeInstance(id));
            instance.setBase(10);
            clearInvocations(instance);
            instances.put(id, instance);
        }
        var external = boundary(net.Indyuce.mmocore.api.player.PlayerData.class);
        external.when(() -> net.Indyuce.mmocore.api.player.PlayerData.has(player)).thenReturn(true);
        external.when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
    }

    @AfterEach
    void cleanup() throws Exception {
        MmoCorePlayerReady.cancel(player.getUniqueId());
        server.getScheduler().cancelTasks(plugin);
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        state.close();
        MockBukkit.unmock();
    }

    @Test
    void readyJoinConsumesPersistedRemovalsExactlyOnce() {
        synchronizedData.set(true);
        manager.initiatePlayer(player);
        assertBases(8, 7);
        assertTrue(data.takePendingMmoAttributeRemoves().isEmpty());
        manager.initiatePlayer(player);
        server.getScheduler().performTicks(30);
        assertBases(8, 7);
        attributes.verify(() -> AttributePointService.migrateAttributePointsIfNeeded(player, data), times(1));
        verify(mmo, times(1)).setAttributeReallocationPoints(0);
    }

    @Test
    void readinessLostEarlierInTheSameCallbackBatchRetainsQueueUntilRetrySucceeds() {
        startJoinAfterAnotherCallbackClosesTheMmoSession();
        assertPending(data, PENDING);
        assertBases(10, 10);
        attributes.verifyNoInteractions();
        verify(mmo, never()).setAttributeReallocationPoints(anyInt());

        synchronizedData.set(true);
        server.getScheduler().performTicks(6);
        assertBases(8, 7);
        assertTrue(data.takePendingMmoAttributeRemoves().isEmpty());
        attributes.verify(() -> AttributePointService.migrateAttributePointsIfNeeded(player, data), times(1));
        server.getScheduler().performTicks(30);
        assertBases(8, 7);
        verify(mmo, times(1)).setAttributeReallocationPoints(0);
    }

    @Test
    void exhaustedRetriesKeepTheUnappliedQueueAndDoNotFinishJoinSetup() {
        startJoinAfterAnotherCallbackClosesTheMmoSession();
        server.getScheduler().performTicks(30);
        assertPending(data, PENDING);
        assertBases(10, 10);
        attributes.verifyNoInteractions();
        verify(mmo, never()).setAttributeReallocationPoints(anyInt());
        verify(logger, times(1)).warning(anyString());

        clearInvocations(mmo);
        synchronizedData.set(true);
        server.getScheduler().performTicks(30);
        verifyNoInteractions(mmo);
        assertPending(data, PENDING);
    }

    @Test
    @SuppressWarnings("deprecation")
    void aRetryFromAnOldSessionCannotConsumeOrModifyReplacementPlayerData() {
        startJoinAfterAnotherCallbackClosesTheMmoSession();
        PlayerData old = data;
        manager.onLeave(new PlayerQuitEvent(player, "reloading"));
        PlayerData replacement = new PlayerData(player);
        replacement.setPendingMmoAttributeRemoves(List.of("strength.1"));
        loaded.set(replacement);
        synchronizedData.set(true);
        manager.initiatePlayer(player);
        assertSame(replacement, PlayerManager.get(player));
        server.getScheduler().performTicks(30);

        assertBases(9, 10);
        assertPending(old, PENDING);
        assertTrue(replacement.takePendingMmoAttributeRemoves().isEmpty());
        attributes.verify(() -> AttributePointService.migrateAttributePointsIfNeeded(player, old), never());
        attributes.verify(() -> AttributePointService.migrateAttributePointsIfNeeded(player, replacement), times(1));
        verify(mmo, times(1)).setAttributeReallocationPoints(0);
    }

    private void startJoinAfterAnotherCallbackClosesTheMmoSession() {
        // Both requests queue while data loads. Readiness is checked once by flush(), before this
        // first callback represents a real MMOCore profile/session close on the server thread.
        MmoCorePlayerReady.runWhenLoaded(player, () -> synchronizedData.set(false));
        manager.initiatePlayer(player);
        assertSame(data, PlayerManager.get(player));
        synchronizedData.set(true);
        server.getScheduler().performTicks(1);
        assertFalse(synchronizedData.get(), "The first queued callback must run before join setup");
    }

    private void assertPending(PlayerData owner, List<String> expected) {
        List<String> pending = owner.takePendingMmoAttributeRemoves();
        owner.setPendingMmoAttributeRemoves(pending);
        assertEquals(expected, pending);
    }

    private void assertBases(int strength, int dexterity) {
        assertEquals(strength, instances.get("strength").getBase());
        assertEquals(dexterity, instances.get("dexterity").getBase());
    }

    private <T> MockedStatic<T> boundary(Class<T> type) {
        MockedStatic<T> value = mockStatic(type);
        boundaries.add(value);
        return value;
    }
}
