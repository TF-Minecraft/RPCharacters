package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.*;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.injuries.InjuryLimits;
import net.tfminecraft.rpcharacters.loaders.InjuryProgressionLoader;
import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;

/** Body-part limits: eyes hold one injury, arms two (Broken Arm, One-Handed and an arm prosthetic). */
class InjuryLimitsTest extends PermadeathRuntimeFixture {
    RuntimeTestState prosthetics;
    Trait brokenArm, oneHanded, clawArm, halfBlind, blind;

    @BeforeEach void setupBody() throws Exception {
        prosthetics = new RuntimeTestState(ProstheticLoader.class);
        brokenArm = trait("broken_arm", "injury", true);
        oneHanded = trait("one_handed", "injury", false);
        clawArm = trait("claw_arm", "prosthetic", false);
        halfBlind = trait("half_blind", "injury", true);
        blind = trait("blind", "injury", false);
        progression(brokenArm, oneHanded, halfBlind, blind);
        var file = folder.resolve("prosthetics.yml");
        Files.writeString(file, "replacements:\n  one_handed:\n    claw_arm: m.utils.claw_arm\n");
        new ProstheticLoader().load(file.toFile());
    }

    @AfterEach void cleanupBody() throws Exception {
        if (prosthetics != null) prosthetics.close();
    }

    List<String> ids() {
        return character.getTraits().stream().map(Trait::getId).toList();
    }

    @Test void slotsDefaultToTwoForProstheticLimbsAndOneOtherwise() {
        assertEquals("one_handed", InjuryLimits.bodyPartOf("BROKEN_ARM"));
        assertEquals("one_handed", InjuryLimits.bodyPartOf("one_handed"));
        assertEquals("one_handed", InjuryLimits.bodyPartOf("claw_arm"));
        assertEquals("blind", InjuryLimits.bodyPartOf("half_blind"));
        assertNull(InjuryLimits.bodyPartOf("wise"));
        assertNull(InjuryLimits.bodyPartOf(" "));
        assertNull(InjuryLimits.bodyPartOf(null));
        assertEquals(2, InjuryLimits.slotsFor("one_handed"));
        assertEquals(1, InjuryLimits.slotsFor("blind"));
        assertTrue(InjuryLimits.canAdd(List.of("blind"), "wise"), "Traits outside a body part are only limited to one each");
        assertFalse(InjuryLimits.canAdd(List.of("wise"), "WISE"));
        assertFalse(InjuryLimits.canAdd(List.of(), null));
        assertNull(InjuryLimits.healingToReplace(List.of("half_blind"), null));
    }

    @Test void aBlindCharacterNeverRollsHalfBlindFromAZoneDeath() throws Exception {
        pool(halfBlind);
        character.addTrait(blind);
        die();
        assertEquals(List.of("blind"), ids());
        verify(logger).warning(contains("Injury pool empty"));
        assertNull(PermadeathService.giveRandomInjury(player, character));
    }

    @Test void aRandomPermanentInjuryTurnsHalfBlindIntoBlindInsteadOfStacking() {
        character.addTrait(clawArm);
        character.addTrait(oneHanded);
        character.addTrait(halfBlind);
        assertTrue(PermadeathService.applyRandomPermanentInjury(player, character), "Arms are full, so Blind is the only candidate");
        assertEquals(List.of("claw_arm", "one_handed", "blind"), ids(), "Blind takes the eye slot Half Blind held");
        assertFalse(PermadeathService.applyRandomPermanentInjury(player, character));
    }

    @Test void armsHoldAtMostTwoInjuries() throws Exception {
        pool(brokenArm);
        character.addTrait(clawArm);
        character.addTrait(oneHanded);
        die();
        assertEquals(List.of("claw_arm", "one_handed"), ids(), "A third arm injury must not be rolled");
        assertNull(PermadeathService.giveRandomInjury(player, character));
        assertSame(blind, PermadeathService.givePermanentInjury(player, character), "Only the eyes still have room");
    }

    @Test void aOneHandedCharacterCanStillBreakTheOtherArm() throws Exception {
        pool(brokenArm);
        character.addTrait(oneHanded);
        assertSame(brokenArm, PermadeathService.giveRandomInjury(player, character));
        assertEquals(List.of("one_handed", "broken_arm"), ids());
    }

    @Test void losingTheOtherArmBesideAProstheticSurvivesTheLoginCleanup() throws Exception {
        character.addTrait(clawArm);
        character.addTrait(brokenArm);
        die();
        assertEquals(List.of("claw_arm", "one_handed"), ids());
        assertTrue(character.isSecondLimb("one_handed"));
        verify(manager, atLeast(2)).savePlayer(player);
        assertFalse(ProstheticTraitRules.stripReplacedInjuries(character));
        assertEquals(List.of("claw_arm", "one_handed"), ids());
    }

    @Test void losingAnArmWithoutAProstheticIsNotMarkedAsTheSecondLimb() throws Exception {
        character.addTrait(brokenArm);
        die();
        assertEquals(List.of("one_handed"), ids());
        assertFalse(character.isSecondLimb("one_handed"));
    }

    @Test void configuredLimitsOverrideTheDefaultsAndBadEntriesAreSkipped() throws Exception {
        var file = folder.resolve("limits.yml");
        Files.writeString(file, "progression:\n  half_blind: blind\n  broken_arm: one_handed\n"
                + "limits:\n  blind: 2\n  one_handed: 0\n  broken_arm: 3\n  missing: 2\n");
        new InjuryProgressionLoader().load(file.toFile());
        verify(logger).warning(contains("Injury limit 'one_handed' must be at least 1"));
        verify(logger).warning(contains("Injury limit 'broken_arm' is not a permanent injury"));
        verify(logger).warning(contains("Injury limit 'missing' is not a permanent injury"));
        assertEquals(2, InjuryLimits.slotsFor("blind"));
        assertEquals(2, InjuryLimits.slotsFor("one_handed"));
        assertEquals(0, InjuryProgressionLoader.getLimit(null));
        assertEquals(0, InjuryProgressionLoader.getLimit(" "));
        pool(halfBlind);
        character.addTrait(blind);
        assertSame(halfBlind, PermadeathService.giveRandomInjury(player, character));
    }

    void groups(String yaml) throws Exception {
        var file = folder.resolve("groups.yml");
        Files.writeString(file, "progression:\n  half_blind: blind\n  broken_arm: one_handed\n" + yaml);
        new InjuryProgressionLoader().load(file.toFile());
    }

    @Test void injuriesInAGroupShareOneSlot() throws Exception {
        Trait manaless = trait("manaless", "injury", false);
        Trait hollow = trait("hollow", "injury", true);
        groups("groups:\n  wild_magic:\n    - Manaless\n    - hollow\n");
        assertEquals("wild_magic", InjuryLimits.bodyPartOf("MANALESS"));
        assertEquals("wild_magic", InjuryLimits.bodyPartOf("hollow"));
        assertEquals(1, InjuryLimits.slotsFor("wild_magic"));
        assertTrue(InjuryLimits.canAdd(List.of("blind", "one_handed"), "manaless"), "Other body parts don't fill the group");
        character.addTrait(manaless);
        assertFalse(InjuryLimits.canReceive(InjuryLimits.ownedIds(character), "hollow"));
        pool(hollow);
        assertNull(PermadeathService.giveRandomInjury(player, character), "The only pool injury is in the full group");
        assertFalse(InjuryLimits.give(player, character, hollow));
        assertEquals(List.of("manaless"), ids());
    }

    @Test void aGroupLimitAllowsMoreThanOne() throws Exception {
        Trait manaless = trait("manaless", "injury", false);
        Trait hollow = trait("hollow", "injury", true);
        groups("groups:\n  wild_magic: [manaless, hollow]\nlimits:\n  wild_magic: 2\n");
        assertEquals(2, InjuryLimits.slotsFor("wild_magic"));
        character.addTrait(manaless);
        assertTrue(InjuryLimits.give(player, character, hollow));
        assertEquals(List.of("manaless", "hollow"), ids());
    }

    @Test void badGroupEntriesAreSkipped() throws Exception {
        trait("manaless", "injury", false);
        trait("wise", "background", false);
        groups("groups:\n  blind:\n    - manaless\n  wild_magic:\n    - manaless\n    - wise\n    - missing\n    - half_blind\n"
                + "  other:\n    - manaless\n  empty: []\n  single: manaless\nlimits:\n  empty: 2\n");
        verify(logger).warning(contains("Injury group 'single' is not a list of injuries"));
        verify(logger).warning(contains("Injury group 'blind' has the name of a trait"));
        verify(logger).warning(contains("entry 'wise' is not an injury"));
        verify(logger).warning(contains("entry 'missing' is not an injury"));
        verify(logger).warning(contains("entry 'half_blind' already has a body part from progression"));
        verify(logger).warning(contains("entry 'manaless' is already in group 'wild_magic'"));
        verify(logger).warning(contains("Injury limit 'empty' is not a permanent injury or an injury group"));
        assertEquals("wild_magic", InjuryLimits.bodyPartOf("manaless"));
        assertEquals("blind", InjuryLimits.bodyPartOf("half_blind"));
        assertNull(InjuryLimits.bodyPartOf("wise"));
        assertNull(InjuryProgressionLoader.getGroup(null));
        assertNull(InjuryProgressionLoader.getGroup(" "));
    }
}
