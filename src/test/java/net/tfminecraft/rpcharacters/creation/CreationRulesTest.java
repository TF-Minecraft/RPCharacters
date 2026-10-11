package net.tfminecraft.rpcharacters.creation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.calendar.AgeCalculator;
import net.tfminecraft.rpcharacters.calendar.FantasyCalendar;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.races.RaceData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class CreationRulesTest {
    RuntimeTestState state;
    Race race;
    RPCharacter draft;
    PlayerData account;
    final List<Trait> traits = new ArrayList<>();

    @BeforeEach void setup() {
        MockBukkit.mock();
        state = new RuntimeTestState();
        Cache.calendarAgeMinimum = 18; Cache.calendarYearOffset = 0;
        Cache.maxClues = 3; Cache.clueMinLength = 5; Cache.clueMaxLength = 20;
        race = mock(Race.class); RaceData raceData = mock(RaceData.class);
        when(race.getAgeMax()).thenReturn(90); when(race.getName()).thenReturn("Orc");
        when(race.getRaceData()).thenReturn(raceData); when(raceData.getKey()).thenReturn("race");
        draft = mock(RPCharacter.class); when(draft.getTraits()).thenReturn(traits); when(draft.getRace()).thenReturn(race);
        account = mock(PlayerData.class); when(account.getAgeSeconds()).thenReturn(10 * 3600);
    }

    @AfterEach void cleanup() { state.close(); MockBukkit.unmock(); }

    @Test void namesAllowLettersInAnyAlphabetAndCommonNamePunctuation() {
        for (String name : List.of("Dr. Dai", "Saul Go'Dar", "Sweyn Wolf-Fang", "Nøkk")) {
            assertNull(CreationRules.nameError(name), name);
        }
        assertFalse(CreationRules.isNameText(null));
        assertFalse(CreationRules.isNameText(" Aria"));
        assertFalse(CreationRules.isNameText("-Aria"));
        assertEquals("Name may only use letters, spaces, apostrophes, hyphens and full stops.", CreationRules.nameError("Ar1a"));
        assertEquals("Name must be at least 3 characters.", CreationRules.nameError("Al"));
    }

    @Test void ageBirthdayDescriptionAndClassFollowTheInGameLimits() {
        assertNull(CreationRules.ageError(18, race));
        assertNull(CreationRules.ageError(90, race));
        assertEquals("Age must be between 18 and 90 for your race.", CreationRules.ageError(17, race));
        assertEquals("Age must be between 18 and 90 for your race.", CreationRules.ageError(91, race));
        assertNull(CreationRules.birthdayError(draft, AgeCalculator.birthdayFromAge(30, FantasyCalendar.getCurrentDate(), "salt")));
        assertEquals("Age must be between 18 and 90 for your race.",
            CreationRules.birthdayError(draft, AgeCalculator.birthdayFromAge(120, FantasyCalendar.getCurrentDate(), "salt")));
        assertNull(CreationRules.descriptionError("A traveller from the northern coast."));
        assertEquals("Description must be at least 32 characters.", CreationRules.descriptionError("Short"));
        try (MockedStatic<ClassPickService> classes = mockStatic(ClassPickService.class)) {
            PlayerClass warrior = mock(PlayerClass.class); when(warrior.getId()).thenReturn("WARRIOR");
            classes.when(ClassPickService::baseClasses).thenReturn(List.of(warrior));
            assertNull(CreationRules.classError("warrior"));
            assertEquals("Unknown class: pirate", CreationRules.classError("pirate"));
        }
    }

    @Test void cluesNeedTheCountLengthAndNoRepeats() {
        assertNull(CreationRules.cluesError(List.of("Blue coat", "Red hair"), 2));
        assertEquals("You cannot have more than 3 clues.", CreationRules.cluesError(List.of("aaaaa", "bbbbb", "ccccc", "ddddd"), 0));
        assertEquals("Each clue must be 5 to 20 characters.", CreationRules.cluesError(List.of("tiny"), 0));
        assertEquals("Each clue must be 5 to 20 characters.", CreationRules.cluesError(List.of("far far too long for a clue"), 0));
        assertEquals("Duplicate clue: blue coat", CreationRules.cluesError(List.of("Blue coat", "blue coat"), 0));
        assertEquals("Needs at least 2 clues.", CreationRules.cluesError(List.of("Blue coat"), 2));
    }

    @Test void raceMustBeOfferedByAWebRaceStage() {
        assertNull(CreationRules.raceError(race, List.of()));
        SelectionStage gameOnly = selection("race", "race", false);
        SelectionStage traitStage = selection("trait", "physical", true);
        assertNull(CreationRules.raceError(race, List.of(gameOnly, traitStage, mock(InfoStage.class))));
        assertNull(CreationRules.raceError(race, List.of(selection("race", "race", true))));
        assertEquals("Orc cannot be picked at creation.", CreationRules.raceError(race, List.of(selection("race", "npc", true))));
    }

    @Test void selectionStagesCheckCountsPointsAndFilters() {
        SelectionStage physical = selection("trait", "physical", true);
        when(physical.getMinSelections()).thenReturn(1); when(physical.getMaxSelections()).thenReturn(2);
        when(physical.hasPoints()).thenReturn(true); when(physical.getInitialPoints()).thenReturn(1);
        List<Stage> stages = List.of(physical, selection("trait", "hidden", false), mock(InfoStage.class));
        assertEquals("Pick 1 to 2 physical traits.", CreationRules.traitsError(draft, account, stages));
        Trait strong = trait("strong", "physical", 1);
        assertNull(CreationRules.traitsError(draft, account, stages));
        trait("agile", "physical", 1);
        assertEquals("Your physical traits cost more than 1 points.", CreationRules.traitsError(draft, account, stages));
        traits.clear(); traits.add(strong); trait("weak", "physical", -1); trait("clumsy", "physical", -1);
        assertEquals("Pick 1 to 2 physical traits.", CreationRules.traitsError(draft, account, stages));
        traits.clear(); trait("secret", "hidden", 0);
        assertEquals("Pick 1 to 2 physical traits.", CreationRules.traitsError(draft, account, stages));
        traits.add(strong);
        assertEquals("secret cannot be picked at creation.", CreationRules.traitsError(draft, account, stages));

        SelectionStage injuries = selection("trait", "injury", true);
        when(injuries.getFilter()).thenReturn("permanent-only"); when(injuries.getMaxSelections()).thenReturn(99);
        traits.clear(); trait("blind", "injury", 0);
        assertNull(CreationRules.traitsError(draft, account, List.of(injuries)));
        when(trait("broken_arm", "injury", 0).getTraitData().hasDuration()).thenReturn(true);
        assertEquals("broken_arm cannot be picked at creation.", CreationRules.traitsError(draft, account, List.of(injuries)));
    }

    @Test void gatedStagesMustBeEmptyWhenTheyWouldNotOpen() {
        SelectionStage evil = selection("trait", "evil", true);
        when(evil.getMaxSelections()).thenReturn(2); when(evil.passesAccountAgeGate(any())).thenReturn(false);
        Dependency needsCallous = mock(Dependency.class);
        when(evil.hasDependency()).thenReturn(true); when(evil.getDependency()).thenReturn(needsCallous);
        assertNull(CreationRules.traitsError(draft, account, List.of(evil)), "A closed stage with no picks is fine");
        trait("bandit", "evil", 0);
        assertEquals("bandit is not available to this character.", CreationRules.traitsError(draft, account, List.of(evil)));
        when(needsCallous.check(draft)).thenReturn(true);
        assertEquals("bandit is not available to this character.", CreationRules.traitsError(draft, account, List.of(evil)),
            "The account-age gate still closes the stage");
        when(evil.passesAccountAgeGate(account)).thenReturn(true);
        assertNull(CreationRules.traitsError(draft, account, List.of(evil)));
    }

    @Test void attributeRanksMustRunInOrderAndSpendThePool() {
        AttributesStage sheet = mock(AttributesStage.class);
        when(sheet.runsOnWeb()).thenReturn(true); when(sheet.getKey()).thenReturn("attributes");
        when(sheet.getAttributes()).thenReturn(List.of("strength", "dexterity"));
        when(sheet.getMaxRank()).thenReturn(3); when(sheet.getPool()).thenReturn(8);
        List<Stage> stages = List.of(sheet);
        assertNull(CreationRules.traitsError(draft, account, stages), "A closed sheet with no ranks is fine");
        trait("str1", "attributes", 1);
        assertEquals("str1 is not available to this character.", CreationRules.traitsError(draft, account, stages));
        when(sheet.passesAccountAgeGate(account)).thenReturn(true);
        assertEquals("Spend exactly 8 attribute points (spent 1).", CreationRules.traitsError(draft, account, stages));
        trait("str2", "attributes", 2); trait("str3", "attributes", 4); trait("dex1", "attributes", 1);
        assertNull(CreationRules.traitsError(draft, account, stages));
        trait("dex3", "attributes", 4);
        assertEquals("Attribute ranks must be bought in order: dex3", CreationRules.traitsError(draft, account, stages));
        traits.removeLast(); trait("str4", "attributes", 8);
        assertEquals("Attribute ranks must be bought in order: str4", CreationRules.traitsError(draft, account, stages));
    }

    @Test void traitsCannotConflictMissRequirementsOrOutpaceTheAccount() {
        SelectionStage personality = selection("trait", "personality", true);
        when(personality.getMaxSelections()).thenReturn(99);
        List<Stage> stages = List.of(personality);
        Trait honest = trait("honest", "personality", 0);
        Trait deceitful = trait("deceitful", "personality", 0);
        assertNull(CreationRules.traitsError(draft, account, stages));
        when(honest.getTraitData().isExclusive("deceitful")).thenReturn(true);
        assertEquals("honest cannot be combined with deceitful.", CreationRules.traitsError(draft, account, stages));
        when(honest.getTraitData().isExclusive("deceitful")).thenReturn(false);
        when(honest.getTraitData().isExclusive("honest")).thenReturn(true);
        when(deceitful.getTraitData().isExclusive("honest")).thenReturn(true);
        assertEquals("honest cannot be combined with deceitful.", CreationRules.traitsError(draft, account, stages),
            "Conflicts count from either side, and a trait never conflicts with itself");
        traits.remove(deceitful);

        Dependency anyOf = mock(Dependency.class);
        when(anyOf.getMode()).thenReturn("one-or-more"); when(anyOf.getDependencies()).thenReturn(List.of("greedy", "callous"));
        when(honest.getTraitData().hasDependency()).thenReturn(true); when(honest.getTraitData().getDependency()).thenReturn(anyOf);
        assertEquals("honest needs greedy or callous.", CreationRules.traitsError(draft, account, stages));
        when(anyOf.getMode()).thenReturn("all");
        assertEquals("honest needs greedy and callous.", CreationRules.traitsError(draft, account, stages));
        when(anyOf.checkExclude(draft, "honest")).thenReturn(true);
        assertNull(CreationRules.traitsError(draft, account, stages));

        when(honest.getTraitData().getRequiredAccountPlaytimeSeconds()).thenReturn(24 * 3600);
        assertEquals("honest needs an account at least 24 hours old.", CreationRules.traitsError(draft, account, stages));
        assertEquals("honest needs an account at least 24 hours old.", CreationRules.traitsError(draft, null, stages));
        when(account.getAgeSeconds()).thenReturn(24 * 3600);
        assertNull(CreationRules.traitsError(draft, account, stages));
    }

    SelectionStage selection(String target, String key, boolean web) {
        SelectionStage stage = mock(SelectionStage.class);
        when(stage.getTarget()).thenReturn(target); when(stage.getKey()).thenReturn(key); when(stage.runsOnWeb()).thenReturn(web);
        when(stage.passesAccountAgeGate(any())).thenReturn(true);
        return stage;
    }

    Trait trait(String id, String key, int cost) {
        Trait trait = mock(Trait.class); TraitData data = mock(TraitData.class);
        when(trait.getId()).thenReturn(id); when(trait.getTraitData()).thenReturn(data);
        when(data.getKey()).thenReturn(key); when(data.getCost()).thenReturn(cost);
        traits.add(trait);
        return trait;
    }
}
