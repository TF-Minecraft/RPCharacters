package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.Indyuce.mmocore.experience.EXPSource;
import net.Indyuce.mmocore.manager.social.BoosterManager;
import net.tfminecraft.rpcharacters.mmocore.CarriedExperience;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CarriedExperienceTest extends ProgressFixture {
    final AtomicInteger level = new AtomicInteger(4), cap = new AtomicInteger(6);
    final AtomicReference<Double> experience = new AtomicReference<>(0d);
    BoosterManager boosters;

    @BeforeEach void levels() throws Exception {
        boosters = mock(BoosterManager.class); when(boosters.getMultiplier(null)).thenReturn(1d); field("boosterManager", boosters);
    }

    /** A class needing 100 EXP a level; MMOCore stops levelling at the cap. Call after activation. */
    void curve() {
        when(mmo.getLevel()).thenAnswer(c -> level.get()); when(mmo.getExperience()).thenAnswer(c -> experience.get());
        when(mmo.getLevelUpExperience()).thenReturn(100L); when(mmo.hasReachedMaxLevel()).thenAnswer(c -> level.get() >= cap.get());
        doAnswer(c -> {
            double total = experience.get() + (double) c.getArgument(0) * multiplier();
            while (total >= 100 && level.get() < cap.get()) { total -= 100; level.incrementAndGet(); }
            experience.set(total); return null;
        }).when(mmo).giveExperience(anyDouble(), any(EXPSource.class));
    }

    double multiplier() { return (1 + mmo.getStats().getStat("ADDITIONAL_EXPERIENCE") / 100) * boosters.getMultiplier(null); }

    RPCharacter live() throws Exception {
        var a = character(); enable(); service.prepare(account); play(a); curve(); clearInvocations(player); return a;
    }

    void runLater() { var due = new ArrayList<>(later); later.clear(); due.forEach(Runnable::run); }

    List<String> said() {
        var lines = ArgumentCaptor.forClass(String.class); verify(player, atLeast(0)).sendMessage(lines.capture()); return lines.getAllValues();
    }

    @Test void theClassTakesWhatItCanHoldAndTheRestWaitsForAHigherCap() throws Exception {
        var a = live();
        assertTrue(CarriedExperience.carry(player, 350));
        assertEquals(6, level.get()); assertEquals(150d, a.getProgress().getCarriedExperience(), 1e-9);
        assertTrue(CarriedExperience.carry(player, 20.5)); assertEquals(170.5d, a.getProgress().getCarriedExperience(), 1e-9);
        assertTrue(said().isEmpty(), "The batch is reported when it is done");
        assertEquals(2, later.size(), "One coalesced checkpoint and one notice for the whole batch"); runLater();
        assertEquals(1, said().size()); assertTrue(said().getFirst().contains("170.5 EXP"), said().getFirst());
        assertEquals(170.5d, store.read(account.getUniqueId(), a.getId()).orElseThrow().getCarriedExperience(), 1e-9,
                "Carried EXP is saved with the character");
        clearInvocations(player); level.set(5); CarriedExperience.carry(player, 50); runLater();
        assertTrue(said().isEmpty(), "Nothing new waits when the class takes the whole batch");
        level.set(6); CarriedExperience.carry(player, 5); play(a); curve(); runLater();
        assertTrue(said().isEmpty(), "A batch from an earlier activation is not reported");

        clearInvocations(player); CarriedExperience.release(player); assertTrue(said().isEmpty(), "Nothing is added at the cap");
        cap.set(11); CarriedExperience.release(player);
        assertEquals(7, level.get()); assertEquals(25.5d, experience.get(), 1e-9); assertEquals(0d, a.getProgress().getCarriedExperience());
        assertTrue(said().getFirst().contains("125.5 carried EXP"), said().getFirst());
        clearInvocations(player, mmo); CarriedExperience.release(player); verify(mmo, never()).giveExperience(anyDouble(), any(EXPSource.class));
    }

    @Test void boostedEXPIsGivenRawSoEachStepEndsOnALevel() throws Exception {
        var a = live(); when(mmo.getStats().getStat("ADDITIONAL_EXPERIENCE")).thenReturn(100d); experience.set(50d);
        assertTrue(CarriedExperience.carry(player, 100));
        assertEquals(6, level.get()); assertEquals(25d, a.getProgress().getCarriedExperience(), 1e-9);
        verify(mmo).giveExperience(25d, EXPSource.COMMAND); verify(mmo).giveExperience(50d, EXPSource.COMMAND);
        when(boosters.getMultiplier(null)).thenReturn(0d); cap.set(11); CarriedExperience.release(player);
        assertEquals(25d, a.getProgress().getCarriedExperience(), 1e-9, "No EXP can be gained, so it keeps waiting");
    }

    @Test void aStuckLevelKeepsTheEXPWaitingInsteadOfLooping() throws Exception {
        var a = live(); experience.set(120d);
        assertTrue(CarriedExperience.carry(player, 30));
        verify(mmo, never()).giveExperience(anyDouble(), any(EXPSource.class)); assertEquals(30d, a.getProgress().getCarriedExperience());
    }

    @Test void nothingIsCarriedWithoutALiveCharacterOrWhileItsClassIsApplied() throws Exception {
        assertFalse(CarriedExperience.carry(null, 5)); assertFalse(CarriedExperience.carry(player, 0));
        assertFalse(CarriedExperience.carry(player, Double.NaN)); assertFalse(CarriedExperience.carry(player, Double.POSITIVE_INFINITY));
        assertTrue(CarriedExperience.carry(player, 40), "Shared progress takes plain MMOCore EXP");
        verify(mmo).giveExperience(40d, EXPSource.COMMAND);
        var a = character(); enable(); service.prepare(account);
        assertFalse(CarriedExperience.carry(player, 40), "The character room has nobody to carry to");
        CarriedExperience.release(null); CarriedExperience.release(player); CarriedExperience.scheduleRelease(null); CarriedExperience.scheduleRelease(player);
        assertTrue(later.isEmpty());
        play(a); curve(); cap.set(4);
        ClassService.runApplying(player, () -> assertFalse(CarriedExperience.carry(player, 40)));
        assertTrue(CarriedExperience.carry(player, 40)); cap.set(11);
        ClassService.runApplying(player, () -> CarriedExperience.release(player));
        assertEquals(40d, a.getProgress().getCarriedExperience());
    }

    @Test void aClassChangeReleasesNextTickOnlyForTheSameActivation() throws Exception {
        var a = live(); cap.set(4); CarriedExperience.carry(player, 60); cap.set(11); runLater();
        var plugin = net.tfminecraft.rpcharacters.RPCharacters.plugin; net.tfminecraft.rpcharacters.RPCharacters.plugin = null;
        CarriedExperience.scheduleRelease(player); assertTrue(later.isEmpty()); net.tfminecraft.rpcharacters.RPCharacters.plugin = plugin;
        CarriedExperience.scheduleRelease(player); play(a); curve(); later.removeFirst().run();
        assertEquals(60d, a.getProgress().getCarriedExperience(), "A stale release never reaches the next activation");
        CarriedExperience.scheduleRelease(player); later.removeFirst().run();
        assertEquals(0d, a.getProgress().getCarriedExperience()); assertEquals(60d, experience.get(), 1e-9);
    }

    @Test void adminCommandValidatesItsArguments() throws Exception {
        var sender = mock(CommandSender.class); var lines = ArgumentCaptor.forClass(String.class);
        bukkit.when(() -> Bukkit.getPlayerExact("Owner")).thenReturn(player);
        assertTrue(CarriedExperience.handle(sender, new String[] {"admin", "carryexp", "Owner"}, 2));
        assertTrue(CarriedExperience.handle(sender, new String[] {"admin", "carryexp", "Nobody", "5"}, 2));
        assertTrue(CarriedExperience.handle(sender, new String[] {"admin", "carryexp", "Owner", "lots"}, 2));
        assertTrue(CarriedExperience.handle(sender, new String[] {"admin", "carryexp", "Owner", "-5"}, 2));
        var a = character(); enable(); service.prepare(account);
        assertTrue(CarriedExperience.handle(sender, new String[] {"admin", "carryexp", "Owner", "5"}, 2));
        play(a); curve();
        assertTrue(CarriedExperience.handle(sender, new String[] {"admin", "carryexp", "Owner", "12.5"}, 2));
        assertEquals(12.5d, experience.get(), 1e-9);
        verify(sender, times(6)).sendMessage(lines.capture()); var said = lines.getAllValues();
        assertTrue(said.get(0).contains("Usage")); assertTrue(said.get(1).contains("Nobody is not online"));
        assertTrue(said.get(2).contains("positive")); assertTrue(said.get(3).contains("positive"));
        assertTrue(said.get(4).contains("no character in play, or its class is being applied")); assertTrue(said.get(5).contains("Carried 12.5 EXP to ProgressOwner"));
    }

    @Test void carriedEXPSurvivesTheFileAndOlderFilesHaveNone() throws Exception {
        var progress = progress(UUID.randomUUID().toString()); progress.setCarriedExperience(-3); assertEquals(0d, progress.getCarriedExperience());
        assertThrows(IllegalArgumentException.class, () -> progress.setCarriedExperience(Double.NaN));
        progress.setCarriedExperience(42.25); store.create(progress);
        assertEquals(42.25d, store.read(account.getUniqueId(), progress.getCharacterId()).orElseThrow().getCarriedExperience());
        var yaml = new org.bukkit.configuration.file.YamlConfiguration(); progress.writeTo(yaml); yaml.set("pools.carried-experience", null);
        assertEquals(0d, CharacterProgress.readFrom(yaml, account.getUniqueId(), progress.getCharacterId(), CharacterProgress.FRESH).getCarriedExperience());
        yaml.set("pools.carried-experience", -1);
        assertThrows(java.io.IOException.class, () -> CharacterProgress.readFrom(yaml, account.getUniqueId(), progress.getCharacterId(), CharacterProgress.FRESH));
        assertEquals(42.25d, progress.copyFor(UUID.randomUUID().toString(), CharacterProgress.BASELINE).getCarriedExperience());
    }
}
