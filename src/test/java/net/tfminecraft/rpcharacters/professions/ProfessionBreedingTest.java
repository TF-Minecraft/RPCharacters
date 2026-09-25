package net.tfminecraft.rpcharacters.professions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityBreedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class ProfessionBreedingTest {
    private final BreedingExperienceService previousService = Cache.professionBreedingExperience;
    private final List<String> previousLocks = Cache.professionLockedBreeding;
    private final BreedingExperienceService experience = mock(BreedingExperienceService.class);
    private final ProfessionEffectService listener = new ProfessionEffectService();
    private final Player player = mock(Player.class);

    private EntityBreedEvent event() {
        Cache.professionBreedingExperience = experience;
        Cache.professionLockedBreeding = List.of("cow");
        var event = mock(EntityBreedEvent.class);
        when(event.getBreeder()).thenReturn(player);
        when(event.getEntityType()).thenReturn(EntityType.COW);
        return event;
    }

    @AfterEach
    void restoreCache() {
        Cache.professionBreedingExperience = previousService;
        Cache.professionLockedBreeding = previousLocks;
    }

    @Test
    void unlockedBreedingAwardsSpecificXpOnlyAtMonitor() throws Exception {
        var event = event();
        PlayerData data = mock(PlayerData.class);
        RPCharacter character = mock(RPCharacter.class);
        when(data.getActiveCharacter()).thenReturn(character);
        when(character.resolveProfessionUpgrades()).thenReturn(List.of(
                new ProfessionUpgradeDefinition("breeder", "forager", null, 1, "breeding", List.of(), List.of("COW"))));
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(player)).thenReturn(data);
            listener.breedEvent(event);
            verify(event, never()).setCancelled(true);
            verifyNoInteractions(experience);
            listener.awardBreedingExperience(event);
            verify(experience).award(player, "cow");
            verifyNoMoreInteractions(experience);
        }
        var handler = ProfessionEffectService.class.getMethod("awardBreedingExperience", EntityBreedEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    @Test
    void unrestrictedBreedingWithoutUpgradeAwardsGenericXp() {
        var event = event();
        Cache.professionLockedBreeding = List.of();
        try (var players = mockStatic(PlayerManager.class)) {
            listener.breedEvent(event);
            listener.awardBreedingExperience(event);
            verify(event, never()).setCancelled(true);
            verify(experience).award(player, "generic");
        }
    }

    @Test
    void lockedBreedingClearsLoveModeAndAwardsNothing() {
        var event = event();
        Animals mother = mock(Animals.class), father = mock(Animals.class);
        when(event.getMother()).thenReturn(mother);
        when(event.getFather()).thenReturn(father);
        doAnswer(call -> { when(event.isCancelled()).thenReturn(true); return null; }).when(event).setCancelled(true);
        try (var players = mockStatic(PlayerManager.class)) {
            listener.breedEvent(event);
            verify(event).setCancelled(true);
            verify(mother).setLoveModeTicks(0);
            verify(father).setLoveModeTicks(0);
            listener.awardBreedingExperience(event);
            verifyNoInteractions(experience);
        }
    }

    @Test
    void lockedBreedingUncancelledByAnotherPluginStillAwardsNothing() {
        var event = event();
        try (var players = mockStatic(PlayerManager.class)) {
            listener.breedEvent(event);
            verify(event).setCancelled(true);
            when(event.isCancelled()).thenReturn(false);
            listener.awardBreedingExperience(event);
            verifyNoInteractions(experience);
        }
    }

    @Test
    void cancellationByAnotherPluginPreventsXp() {
        var event = event();
        when(event.isCancelled()).thenReturn(true);
        listener.awardBreedingExperience(event);
        verifyNoInteractions(experience);
    }

    @Test
    void nonPlayerBreedingDoesNotAwardXp() {
        var event = event();
        when(event.getBreeder()).thenReturn(mock(Animals.class));
        listener.breedEvent(event);
        listener.awardBreedingExperience(event);
        verifyNoInteractions(experience);
        verify(event, never()).setCancelled(true);
    }
}
