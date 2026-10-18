package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec;
import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec.ProfessionLevel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SharedProfessionsTest extends ProgressFixture {
    @Test void everyProfessionTakesTheAccountsBestLevelAndPointsWithoutEditingSharedCopies() {
        var target = with(progress(UUID.randomUUID().toString()), Map.of("fishing", new ProfessionLevel(3, 9.0)));
        target.setProfessionPoints("fishing", 7);
        var miner = with(progress(UUID.randomUUID().toString()),
                Map.of("mining", new ProfessionLevel(10, 4.5), "fishing", new ProfessionLevel(2, 0.0)));
        miner.setProfessionPoints("mining", 9);
        var fisher = with(progress(UUID.randomUUID().toString()),
                Map.of("mining", new ProfessionLevel(10, 3.0), "fishing", new ProfessionLevel(6, 1.5)));
        fisher.setProfessionPoints("mining", 2); fisher.setProfessionPoints("fishing", 5);
        var smith = with(progress(UUID.randomUUID().toString()), Map.of("mining", new ProfessionLevel(10, 6.25)));
        YamlConfiguration own = target.mmo(); String before = own.saveToString(); String minerBefore = miner.mmo().saveToString();

        assertTrue(SharedProfessions.raise(target, Arrays.asList(target, null, miner, fisher, smith)));

        var levels = MmoProgressCodec.professions(target.mmo());
        assertEquals(new ProfessionLevel(10, 6.25), levels.get("mining"), "Same level, more experience wins");
        assertEquals(new ProfessionLevel(6, 1.5), levels.get("fishing"), "A higher level wins over more experience");
        assertEquals(9, target.getProfessionPoints("mining")); assertEquals(7, target.getProfessionPoints("fishing"),
                "Points never go down");
        assertNotSame(own, target.mmo()); assertEquals(before, own.saveToString()); assertEquals(minerBefore, miner.mmo().saveToString());
        assertFalse(SharedProfessions.raise(target, List.of(miner, fisher, smith)), "Nothing is left to raise");
    }

    @Test void pointsAloneRaiseWithoutReplacingTheMmoCopy() {
        var target = progress(UUID.randomUUID().toString()); var own = target.mmo();
        var other = progress(UUID.randomUUID().toString()); other.setProfessionPoints("mining", 3);
        assertTrue(SharedProfessions.raise(target, List.of(other)));
        assertSame(own, target.mmo()); assertEquals(3, target.getProfessionPoints("mining"));
    }

    @Test void activationGivesEachCharacterTheAccountsProfessionsOnSwitchDeathAndCreation() throws Exception {
        var a = character(); var b = character(); enable(); service.prepare(account);
        play(a); when(skills.getLevel(profession)).thenReturn(9); when(skills.getExperience(profession)).thenReturn(3d);
        a.getProgress().addProfessionPoints("mining", 4); service.save(account, a);
        clearInvocations(skills); play(b);
        verify(skills).setLevel(profession, 9, PlayerLevelChangeEvent.Reason.CHOOSE_PROFILE);
        verify(skills).setExperience(profession, 3d);
        assertEquals(12, b.getProgress().getProfessionPoints("mining"), "The points arrive unspent on the next character");
        assertEquals(8, account.getAccountProfessionPoints("mining"), "Legacy account pools are never written");
        service.save(account, b);
        assertEquals(new ProfessionLevel(9, 3d), MmoProgressCodec.professions(store.read(account.getUniqueId(), b.getId())
                .orElseThrow().mmo()).get("mining"), "The raise is saved with the next checkpoint");

        when(a.getStatus()).thenReturn(net.tfminecraft.rpcharacters.enums.Status.DEAD);
        var c = character(); service.ensureFresh(account, c); clearInvocations(skills); play(c);
        verify(skills).setLevel(profession, 9, PlayerLevelChangeEvent.Reason.CHOOSE_PROFILE);
        assertEquals(12, c.getProgress().getProfessionPoints("mining"));
    }

    private static CharacterProgress with(CharacterProgress progress, Map<String, ProfessionLevel> professions) {
        progress.setMmo(MmoProgressCodec.withProfessions(progress.mmo(), professions));
        return progress;
    }
}
