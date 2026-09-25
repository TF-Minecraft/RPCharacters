package net.tfminecraft.rpcharacters.professions;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.lumine.mythic.lib.api.event.SynchronizedDataLoadEvent;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.experience.EXPSource;
import net.Indyuce.mmocore.experience.PlayerProfessions;
import net.Indyuce.mmocore.experience.Profession;
import net.Indyuce.mmocore.manager.profession.ProfessionManager;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;

class BreedingExperienceServiceTest {
    private final Player player = mock(Player.class);
    private final PlayerData data = mock(PlayerData.class);
    private final PlayerProfessions skills = mock(PlayerProfessions.class);
    private final ProfessionManager professions = mock(ProfessionManager.class);
    private final Profession forager = mock(Profession.class);
    private final Logger logger = mock(Logger.class);
    private MockedStatic<PlayerData> players;

    @BeforeEach
    void readyPlayer() {
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(data.getUniqueId()).thenReturn(id);
        when(data.isSynchronized()).thenReturn(true);
        when(data.getCollectionSkills()).thenReturn(skills);
        when(professions.get("forager")).thenReturn(forager);
        players = mockStatic(PlayerData.class);
        players.when(() -> PlayerData.has(player)).thenReturn(true);
        players.when(() -> PlayerData.get(player)).thenReturn(data);
    }

    @AfterEach
    void cleanup() {
        MmoCorePlayerReady.cancel(player.getUniqueId());
        players.close();
    }

    @Test
    void givesSpecificAndGenericXpWithTheSameSemanticsAsTheCommand() {
        var service = new BreedingExperienceService(List.of("cow.20", "generic.5"), professions, logger);
        try (var bukkit = mockStatic(Bukkit.class)) {
            service.award(player, "COW");
            service.award(player, "generic");
            verify(skills).giveExperience(forager, 20, EXPSource.COMMAND, null, true);
            verify(skills).giveExperience(forager, 5, EXPSource.COMMAND, null, true);
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void malformedEntriesCannotBreakValidAwards() {
        var service = new BreedingExperienceService(
                List.of("broken", "cow.nope", "cow.-1", "cow.1.5", "cow.2147483648", "typo.4", "generic.5"),
                professions, logger);
        verify(logger, times(6)).warning(startsWith("Invalid breeding_exp entry"));
        service.award(player, "generic");
        verify(skills).giveExperience(forager, 5, EXPSource.COMMAND, null, true);
    }

    @Test
    void firstDuplicateWinsAndMatchingIsCaseInsensitive() {
        var service = new BreedingExperienceService(List.of("COW.20", "cow.99"), professions, logger);
        service.award(player, "cow");
        verify(logger).warning(startsWith("Duplicate breeding_exp entry"));
        verify(skills).giveExperience(forager, 20, EXPSource.COMMAND, null, true);
        verifyNoMoreInteractions(skills);
    }

    @Test
    void missingSpecificRuleDoesNotFallBackAndZeroAwardsDoNothing() {
        var service = new BreedingExperienceService(List.of("cow.0", "generic.5"), professions, logger);
        service.award(player, "cow");
        service.award(player, "sheep");
        players.verifyNoInteractions();
        verifyNoInteractions(skills);
    }

    @Test
    void missingProfessionIsReportedAtLoadOnceAndRecoversAfterMmoCoreReload() {
        when(professions.get("forager")).thenReturn(null);
        var service = new BreedingExperienceService(List.of("generic.5"), professions, logger);
        verify(logger).warning(contains("profession 'forager' is missing"));
        service.award(player, "generic");
        service.award(player, "generic");
        verifyNoMoreInteractions(logger);
        players.verifyNoInteractions();
        when(professions.get("forager")).thenReturn(forager);
        service.award(player, "generic");
        verify(skills).giveExperience(forager, 5, EXPSource.COMMAND, null, true);
    }

    @Test
    void usesCurrentProfessionAfterMmoCoreReload() {
        var service = new BreedingExperienceService(List.of("generic.5"), professions, logger);
        Profession replacement = mock(Profession.class);
        when(professions.get("forager")).thenReturn(replacement);
        service.award(player, "generic");
        verify(skills).giveExperience(replacement, 5, EXPSource.COMMAND, null, true);
    }

    @Test
    void waitsForSynchronizedPlayerDataAndAwardsOnlyOnce() {
        var service = new BreedingExperienceService(List.of("generic.5"), professions, logger);
        when(data.isSynchronized()).thenReturn(false);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            service.award(player, "generic");
            verifyNoInteractions(skills);
            when(data.isSynchronized()).thenReturn(true);
            var event = mock(SynchronizedDataLoadEvent.class);
            when(event.getHolder()).thenReturn(data);
            var listener = new MmoCorePlayerReady();
            listener.onMmoLoaded(event);
            listener.onMmoLoaded(event);
            verify(skills).giveExperience(forager, 5, EXPSource.COMMAND, null, true);
            verifyNoMoreInteractions(skills);
        }
    }
}
