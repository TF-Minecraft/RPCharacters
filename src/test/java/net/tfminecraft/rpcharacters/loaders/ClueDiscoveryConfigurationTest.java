package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoverySettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class ClueDiscoveryConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    @BeforeEach void setup() { MockBukkit.mock(); state = new RuntimeTestState(ClueDiscoveryLoader.class); }
    @AfterEach void restore() { state.close(); MockBukkit.unmock(); }
    ClueDiscoverySettings load(String text) throws Exception {
        File file = Files.writeString(directory.resolve("clues.yml"), text).toFile();
        new ClueDiscoveryLoader().load(file); return ClueDiscoveryLoader.getSettings();
    }

    @Test void allDiscoveryPoliciesAndMessagesLoadTogether() throws Exception {
        var s = load("""
            investigation-points: {max: 12, regen-cycle: 2m}
            passive-discovery: {enabled: false, interval-seconds: 25, radius: 6, base-chance: 0.21, min-potency: 0.22}
            active-discovery: {enabled: false, cooldown-seconds: 8, investigation-cost: 3, radius: 7, base-chance: 0.31, min-potency: 0.32}
            attributes: {wisdom-weight: 0.04, intelligence-weight: 0.05}
            potency: {initial: 0.9, decay-per-hour: 0.06, min-for-discovery: 0.4, min-for-readable: 0.5, expire-when-zero: false}
            disturbance:
              target-interact: {enabled: false, potency-loss-min: 0.11, potency-loss-max: 0.23, zero-loss-chance: 0.24}
              foot-traffic: {enabled: false, radius: 8, chance-per-check: 0.09, max-events-per-clue-per-hour: 9, only-undiscovered-players: false, potency-loss-min: 0.03, potency-loss-max: 0.07}
            readability: {full-clarity: 0.8, min-audible: 0.2, too-faint-placeholder: Faint}
            messages: {discovered: Found, no-investigation-points: Rest, attribute-too-low: Train, no-clue-nearby: Nothing}
            """);
        assertEquals(12, s.getInvestigationPointsMax()); assertEquals(120000, s.getInvestigationRegenCycleMs());
        assertFalse(s.isPassiveDiscoveryEnabled()); assertEquals(25, s.getPassiveIntervalSeconds());
        assertEquals(6, s.getPassiveRadius()); assertEquals(.21, s.getPassiveBaseChance()); assertEquals(.22, s.getPassiveMinPotency());
        assertFalse(s.isActiveDiscoveryEnabled()); assertEquals(8, s.getActiveCooldownSeconds()); assertEquals(3, s.getActiveInvestigationCost());
        assertEquals(7, s.getActiveRadius()); assertEquals(.31, s.getActiveBaseChance()); assertEquals(.32, s.getActiveMinPotency());
        assertEquals(.04, s.getWisdomWeight()); assertEquals(.05, s.getIntelligenceWeight());
        assertEquals(.9, s.getPotencyInitial()); assertEquals(.06, s.getPotencyDecayPerHour());
        assertEquals(.4, s.getPotencyMinForDiscovery()); assertEquals(.5, s.getPotencyMinForReadable()); assertFalse(s.isPotencyExpireWhenZero());
        assertFalse(s.isTargetInteractEnabled()); assertEquals(.11, s.getTargetInteractLossMin());
        assertEquals(.23, s.getTargetInteractLossMax()); assertEquals(.24, s.getTargetInteractZeroLossChance());
        assertFalse(s.isFootTrafficEnabled()); assertEquals(8, s.getFootTrafficRadius()); assertEquals(.09, s.getFootTrafficChancePerCheck());
        assertEquals(9, s.getFootTrafficMaxEventsPerHour()); assertFalse(s.isFootTrafficOnlyUndiscovered());
        assertEquals(.03, s.getFootTrafficLossMin()); assertEquals(.07, s.getFootTrafficLossMax());
        assertEquals(.8, s.getReadabilityFullClarity()); assertEquals(.2, s.getReadabilityMinAudible());
        assertEquals("Faint", s.getReadabilityTooFaintPlaceholder()); assertEquals("Found", s.getMessageDiscovered());
        assertEquals("Rest", s.getMessageNoInvestigationPoints()); assertEquals("Train", s.getMessageAttributeTooLow()); assertEquals("Nothing", s.getMessageNoClueNearby());
        var defaults = load("{}"); assertNotSame(s, defaults);
        assertEquals(20, defaults.getInvestigationPointsMax()); assertEquals(30000, defaults.getInvestigationRegenCycleMs());
        assertTrue(defaults.isPassiveDiscoveryEnabled()); assertTrue(defaults.isActiveDiscoveryEnabled());
        assertTrue(defaults.isTargetInteractEnabled()); assertTrue(defaults.isFootTrafficEnabled());
        assertTrue(defaults.isFootTrafficOnlyUndiscovered()); assertTrue(defaults.isPotencyExpireWhenZero());
        assertEquals("", defaults.getMessageNoClueNearby());
    }

    @Test void legacyRegenerationOnlyAppliesWhenNoExplicitCycleIsGiven() throws Exception {
        assertEquals(15000, load("investigation-points: {regen-per-minute: 4}").getInvestigationRegenCycleMs());
        assertEquals(30000, load("investigation-points: {regen-per-minute: 0}").getInvestigationRegenCycleMs());
        assertEquals(30000, load("investigation-points: {regen-cycle: bad, regen-per-minute: 4}").getInvestigationRegenCycleMs());
        assertEquals(60000, load("investigation-points: {regen-cycle: 1m, regen-per-minute: 4}").getInvestigationRegenCycleMs());
        assertEquals(30000, load("investigation-points: {}\ndisturbance: {}\n").getInvestigationRegenCycleMs());
    }

    @Test void boundedValuesRejectNegativeCapacityRadiusAndTiming() throws Exception {
        var s = load("""
            investigation-points: {max: -2}
            passive-discovery: {interval-seconds: 0, radius: -5}
            active-discovery: {cooldown-seconds: -1, investigation-cost: -3, radius: 0}
            potency: {initial: -1, decay-per-hour: -2}
            disturbance:
              target-interact: {zero-loss-chance: -2}
              foot-traffic: {radius: 0, max-events-per-clue-per-hour: -1}
            """);
        assertEquals(1, s.getInvestigationPointsMax()); assertEquals(1, s.getPassiveIntervalSeconds());
        assertEquals(.5, s.getPassiveRadius()); assertEquals(.5, s.getActiveRadius()); assertEquals(.5, s.getFootTrafficRadius());
        assertEquals(0, s.getActiveCooldownSeconds()); assertEquals(0, s.getActiveInvestigationCost());
        assertEquals(0, s.getPotencyInitial()); assertEquals(0, s.getPotencyDecayPerHour());
        assertEquals(0, s.getFootTrafficMaxEventsPerHour()); assertEquals(0, s.getTargetInteractZeroLossChance());
        s.setTargetInteractZeroLossChance(2); assertEquals(1, s.getTargetInteractZeroLossChance());
        s.setInvestigationRegenCycleMs(-1); assertEquals(0, s.getInvestigationRegenCycleMs());
        s.setReadabilityTooFaintPlaceholder(null); s.setMessageDiscovered(null); s.setMessageNoInvestigationPoints(null);
        s.setMessageAttributeTooLow(null); s.setMessageNoClueNearby(null);
        assertEquals("", s.getReadabilityTooFaintPlaceholder()); assertEquals("", s.getMessageDiscovered());
        assertEquals("", s.getMessageNoInvestigationPoints()); assertEquals("", s.getMessageAttributeTooLow()); assertEquals("", s.getMessageNoClueNearby());
    }

    @Test void malformedAndMissingConfigurationResetToUsableDefaults() throws Exception {
        load("investigation-points: {max: 45}"); assertEquals(20, load("bad: [unterminated").getInvestigationPointsMax());
        new ClueDiscoveryLoader().load(directory.resolve("absent.yml").toFile());
        assertEquals(20, ClueDiscoveryLoader.getSettings().getInvestigationPointsMax());
    }
}
