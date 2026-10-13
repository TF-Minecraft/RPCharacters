package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions.ReviveResult;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions.ReviveStatus;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

class StaffReviveTest {
    private ServerMock server;
    private RuntimeTestState state;
    private final List<AutoCloseable> boundaries = new ArrayList<>();
    private PlayerMock staff;
    private PlayerMock owner;
    private PlayerManager manager;
    private PlayerData data;
    private RPCharacter character;
    private MockedStatic<CharacterTransitions> transitions;
    private MockedStatic<PlayerManager> players;
    private MockedStatic<RosterSyncService> roster;
    private MockedConstruction<InventoryManager> views;
    private ClueDiscoveryVisualManager clues;
    private final AtomicReference<ClickCallback<Audience>> force = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        state = new RuntimeTestState(StaffRevive.class);
        staff = server.addPlayer("Staff");
        owner = server.addPlayer("Owner");
        manager = mock(PlayerManager.class);
        data = mock(PlayerData.class);
        character = mock(RPCharacter.class);
        when(character.getOwner()).thenReturn(owner);
        when(character.getName()).thenReturn("Aria");
        transitions = track(mockStatic(CharacterTransitions.class));
        players = track(mockStatic(PlayerManager.class));
        players.when(() -> PlayerManager.get(owner)).thenReturn(data);
        roster = track(mockStatic(RosterSyncService.class));
        track(mockStatic(WardrobeService.class));
        var slots = track(mockStatic(CharacterSlotService.class));
        slots.when(() -> CharacterSlotService.getMaxAliveCharacters(owner)).thenReturn(2);
        clues = mock(ClueDiscoveryVisualManager.class);
        track(mockStatic(ClueDiscoveryVisualManager.class)).when(ClueDiscoveryVisualManager::get).thenReturn(clues);
        views = track(mockConstruction(InventoryManager.class));
        StaffRevive.clickEvents = (callback, options) -> {
            force.set(callback);
            return ClickEvent.runCommand("/noop");
        };
    }

    private <T extends AutoCloseable> T track(T boundary) {
        boundaries.add(boundary);
        return boundary;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        state.close();
        MockBukkit.unmock();
    }

    private void answer(boolean force, ReviveResult result) {
        transitions.when(() -> CharacterTransitions.revive(data, character, force, "Staff")).thenReturn(result);
    }

    private String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        for (String line; (line = player.nextMessage()) != null;) all.append(line).append('\n');
        return all.toString();
    }

    @Test
    void refusalsExplainThemselvesAndChangeNothing() {
        players.when(() -> PlayerManager.get(owner)).thenReturn(null);
        StaffRevive.run(manager, staff, character, false);
        assertTrue(said(staff).contains("Could not find that player's data"));
        players.when(() -> PlayerManager.get(owner)).thenReturn(data);
        answer(false, new ReviveResult(ReviveStatus.NOT_DEAD, List.of()));
        StaffRevive.run(manager, staff, character, false);
        answer(false, new ReviveResult(ReviveStatus.OWNER_OFFLINE, List.of()));
        StaffRevive.run(manager, staff, character, false);
        answer(false, new ReviveResult(ReviveStatus.NOT_SAVED, List.of()));
        StaffRevive.run(manager, staff, character, false);
        when(data.getCharacters(Status.ALIVE)).thenReturn(List.of(character, character));
        answer(false, new ReviveResult(ReviveStatus.NO_SLOT, List.of()));
        StaffRevive.run(manager, staff, character, false);
        String text = said(staff);
        assertTrue(text.contains("not dead"));
        assertTrue(text.contains("must be online"));
        assertTrue(text.contains("could not be saved"));
        assertTrue(text.contains("no free character slots"));
        verify(manager, never()).savePlayer(any());
        assertEquals(4, views.constructed().size());
    }

    @Test
    void blockedEstateOffersAOneUseForceThatRunsTheReviveAgain() {
        answer(false, new ReviveResult(ReviveStatus.ESTATE_BLOCKED, List.of("thievery moves its records at the next restart")));
        answer(true, new ReviveResult(ReviveStatus.REVIVED, List.of("thievery moves its records at the next restart")));
        when(data.hasActiveCharacter()).thenReturn(true);
        StaffRevive.run(manager, staff, character, false);
        String text = said(staff);
        assertTrue(text.contains("has not finished moving"));
        assertTrue(text.contains("thievery moves its records"));
        verify(manager, never()).savePlayer(any());
        force.get().accept(owner);
        verify(manager, never()).savePlayer(any());
        force.get().accept(staff);
        verify(manager).savePlayer(owner);
        assertTrue(said(staff).contains("Forced: parts not yet moved stay with the character"));
        verify(data, never()).setActiveCharacter(any());
    }

    @Test
    void revivedCharacterIsActivatedWhenNoneIsActiveAndThePushFollows() {
        answer(false, new ReviveResult(ReviveStatus.REVIVED, List.of()));
        StaffRevive.run(manager, staff, character, false);
        verify(data).setActiveCharacter(character);
        verify(clues).refreshViewer(owner);
        verify(manager).reevaluateFreeze(owner);
        roster.verify(() -> RosterSyncService.pushRosterForPlayer(owner));
        String text = said(staff);
        assertTrue(text.contains("Revived character"));
        assertFalse(text.contains("Forced"));
        assertTrue(said(owner).contains("was revived by staff"));
    }
}
