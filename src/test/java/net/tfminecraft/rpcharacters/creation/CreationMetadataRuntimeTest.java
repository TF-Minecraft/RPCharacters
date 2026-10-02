package net.tfminecraft.rpcharacters.creation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.CreationGuiContext;
import net.tfminecraft.rpcharacters.holder.RPCHolder;
import net.tfminecraft.rpcharacters.mmocore.IgnoredAttributes;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CreationMetadataRuntimeTest {
    RuntimeTestState state; PlayerMock player; RPCharacter character;

    @BeforeEach void setup() {
        var server = MockBukkit.mock(); state = new RuntimeTestState();
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); Cache.backgroundTraitTypes = new ArrayList<>();
        player = server.addPlayer("Creator"); character = new RPCharacter(player);
        var race = new YamlConfiguration(); race.set("name", "Human"); character.setRace(new Race("human", race));
        character.getTraits().add(trait("brave")); character.getTraits().add(trait("scholar"));
    }

    @AfterEach void cleanup() { state.close(); MockBukkit.unmock(); }

    Trait trait(String id) { var config = new YamlConfiguration(); config.set("name", id); return new Trait(id, config); }
    Dependency dependency(String type, String mode, String... ids) {
        var config = new YamlConfiguration(); if (type != null) config.set("type", type);
        if (mode != null) config.set("mode", mode); config.set("depends-on", List.of(ids)); return new Dependency(config);
    }
    Stage stage(String id, int revision) { var stage = new Stage(); stage.setId(id); stage.setRevision(revision); return stage; }

    @Test void traitModesMatchDraftAndCharacterStateAndExcludeTheRemovedTrait() {
        var all = dependency("trait", "all", "brave", "scholar");
        assertEquals("trait", all.getType()); assertEquals("all", all.getMode()); assertEquals(List.of("brave", "scholar"), all.getDependencies());
        assertTrue(all.check(character)); assertTrue(all.satisfiedBy(Set.of("brave", "scholar")));
        assertFalse(all.checkExclude(character, "BRAVE")); assertFalse(all.satisfiedByExcluding(Set.of("brave", "scholar"), "brave"));
        var either = dependency("trait", "one-or-more", "brave", "absent");
        assertTrue(either.check(character)); assertTrue(either.satisfiedByExcluding(new HashSet<>(Arrays.asList("brave", null)), "absent"));
        assertFalse(either.satisfiedBy(Set.of("other"))); assertFalse(either.satisfiedByExcluding(Set.of("brave"), "BRAVE"));
        assertFalse(dependency("trait", "unknown", "brave").check(character));
        assertTrue(all.toString().contains("dependencies=[brave, scholar]"));
    }

    @Test void raceRequirementsUseTheSelectedRaceAndDoNotMatchDraftTraitIds() {
        var race = dependency("race", "one-or-more", "elf", "HUMAN");
        assertTrue(race.check(character)); assertTrue(race.checkExclude(character, "human"));
        assertFalse(dependency("race", "all", "elf").check(character));
        assertFalse(race.satisfiedBy(Set.of("human"))); assertFalse(race.satisfiedByExcluding(Set.of("human"), "elf"));
        assertFalse(dependency("unknown", "all", "human").check(character));
        assertFalse(dependency("trait", "all", "brave").satisfiedBy(null));
        assertFalse(dependency("trait", "all", "brave").satisfiedByExcluding(null, "brave"));
    }

    @Test void incompleteDependencyConfigurationFailsClosedAcrossPublicChecks() {
        var noType = dependency(null, "all", "brave"); var noMode = dependency("trait", null, "brave");
        assertAll(
            () -> assertFalse(assertDoesNotThrow(() -> noType.check(character))),
            () -> assertFalse(noType.satisfiedBy(Set.of("brave"))),
            () -> assertFalse(noType.satisfiedByExcluding(Set.of("brave"), "other")),
            () -> assertFalse(assertDoesNotThrow(() -> noMode.check(character))),
            () -> assertFalse(assertDoesNotThrow(() -> noMode.satisfiedBy(Set.of("brave")))),
            () -> assertFalse(assertDoesNotThrow(() -> noMode.satisfiedByExcluding(Set.of("brave"), "other")))
        );
    }

    @Test void charactersWithoutASelectedRaceCannotSatisfyRaceDependencies() {
        var incomplete = new RPCharacter(player); var dependency = dependency("race", "one-or-more", "human");
        assertFalse(assertDoesNotThrow(() -> dependency.check(incomplete)));
        assertFalse(assertDoesNotThrow(() -> dependency.check(null)));
    }

    @Test void traitIdsHaveTheSameCaseIndependentMeaningAsRaceIdsAndExclusions() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); character.getTraits().add(trait("intelligent"));
        var dependency = dependency("TRAIT", "ALL", "INTELLIGENT");
        assertAll(
            () -> assertTrue(dependency.check(character)),
            () -> assertTrue(dependency.satisfiedBy(Set.of("intelligent"))),
            () -> assertTrue(dependency.satisfiedByExcluding(Set.of("intelligent"), "other")),
            () -> assertFalse(dependency.checkExclude(character, "INTELLIGENT"))
        );
    }

    @Test void revisionRefreshIgnoresIncompleteEntriesAndOnlyResetsNewerStagePrices() {
        var current = stage("CLASS", 3); var previous = stage("race", 1);
        character.setStageRevision("class", 2, 10); character.setPaidChangeCount("class", 5);
        character.setStageRevision("race", 2, 20); character.setPaidChangeCount("race", 4);
        assertFalse(StageRevisions.refresh(null, List.of(current), 100)); assertFalse(StageRevisions.refresh(character, null, 100));
        assertTrue(StageRevisions.refresh(character, Arrays.asList(null, stage(null, 9), current, previous), 100));
        assertEquals(3, character.getStageRevision("class")); assertEquals(100, character.getStageRevisionSince("class"));
        assertEquals(0, character.getPaidChangeCount("class")); assertEquals(4, character.getPaidChangeCount("race"));
        assertEquals(20, character.getStageRevisionSince("race"));
        assertFalse(StageRevisions.refresh(character, List.of(current), 200));
        current.setRevision(4); long before = Instant.now().getEpochSecond(); assertTrue(StageRevisions.refresh(character, List.of(current)));
        assertTrue(character.getStageRevisionSince("class") >= before);
        assertTrue(character.getStageRevisionSince("class") <= Instant.now().getEpochSecond());
    }

    @Test void initialRevisionStampUsesCreationTimeAndWindowNeverBecomesNegative() {
        var stage = stage("INTELLIGENCE", 2); Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        StageRevisions.stampCurrent(null, List.of(stage)); StageRevisions.stampCurrent(character, null);
        StageRevisions.stampCurrent(character, Arrays.asList(null, stage(null, 9), stage("zero", 0), stage));
        assertEquals(Map.of("intelligence", 2), character.getStageRevisions()); assertEquals(0, character.getStageRevisionSince("intelligence"));
        assertNull(StageRevisions.key(stage(null, 1))); assertEquals(0, StageRevisions.secondsIntoWindow(stage, character, 100));
        character.setCreatedAtEpochSeconds(100); assertEquals(0, StageRevisions.secondsIntoWindow(stage, character, 90));
        assertEquals(50, StageRevisions.secondsIntoWindow(stage, character, 150));
        character.setStageRevision("intelligence", 3, 120); assertEquals(30, StageRevisions.secondsIntoWindow(stage, character, 150));
        long before = Instant.now().getEpochSecond(); long elapsed = StageRevisions.secondsIntoWindow(stage, character);
        assertTrue(elapsed >= before - 120); assertTrue(elapsed <= Instant.now().getEpochSecond() - 120);
    }

    @Test void inventoryHolderRetainsCreationMetadataWithoutInventingAnotherInventory() {
        var stage = stage("attributes", 1); var holder = new RPCHolder(player, stage);
        var inventory = Bukkit.createInventory(holder, 9, "Attributes");
        assertSame(holder, inventory.getHolder()); assertSame(player, holder.getOwner()); assertSame(stage, holder.getStage());
        assertNull(holder.getCreation()); assertEquals(CreationGuiContext.NONE, holder.getContext()); assertNull(holder.getInventory());
        assertFalse(holder.isOverridden()); holder.override(); assertTrue(holder.isOverridden());
    }

    @Test void ignoredAttributeMetadataHandlesMissingIdsAndUsesRootLocale() {
        Cache.ignoredAttributes = new HashSet<>(Set.of("intelligence")); Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertFalse(IgnoredAttributes.isIgnored(null)); assertFalse(IgnoredAttributes.isIgnored(" "));
        assertTrue(IgnoredAttributes.isIgnored(" INTELLIGENCE ")); assertFalse(IgnoredAttributes.isIgnored("strength"));
    }
}
