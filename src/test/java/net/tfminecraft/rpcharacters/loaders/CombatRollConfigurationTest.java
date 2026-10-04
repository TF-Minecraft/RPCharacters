package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import net.tfminecraft.rpcharacters.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class CombatRollConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    @BeforeEach void setup() { MockBukkit.mock(); state = new RuntimeTestState(PvpLoader.class, RollLoader.class); }
    @AfterEach void restore() { state.close(); MockBukkit.unmock(); }
    File yaml(String text) throws Exception { return Files.writeString(directory.resolve("settings.yml"), text).toFile(); }

    @Test void pvpDurationsPoliciesAndMessagesLoadTogether() throws Exception {
        Map<String,Supplier<String>> messages = new LinkedHashMap<>();
        messages.put("start-warning", PvpLoader::getStartWarning); messages.put("countdown", PvpLoader::getCountdown);
        messages.put("started-title", PvpLoader::getStartedTitle); messages.put("situation-duration", PvpLoader::getSituationDuration);
        messages.put("ended-title", PvpLoader::getEndedTitle); messages.put("ended-subtitle", PvpLoader::getEndedSubtitle);
        messages.put("start-cancelled", PvpLoader::getStartCancelled); messages.put("no-situation", PvpLoader::getNoSituation);
        messages.put("lethal", PvpLoader::getLethal); messages.put("nonlethal", PvpLoader::getNonlethal);
        messages.put("usage", PvpLoader::getUsage); messages.put("no-character", PvpLoader::getNoCharacter);
        messages.put("current", PvpLoader::getCurrent); messages.put("players-only", PvpLoader::getPlayersOnly);
        StringBuilder config = new StringBuilder("""
            start-radius: 24
            start-warn-seconds: 8
            start-countdown-from: 4
            start-active-minutes: 20
            knockout-seconds: 40
            blindness-amplifier: 3
            freeze-period-ticks: 2
            strikes: {decision-seconds: 45, same-target-cooldown-hours: 36, decay: {enabled: true, days: 21}}
            messages:
            """);
        messages.keySet().forEach(key -> config.append("  ").append(key).append(": '").append(key).append(" {player}'\n"));
        new PvpLoader().load(yaml(config.toString()));
        assertEquals(24, PvpLoader.getStartRadius()); assertEquals(8, PvpLoader.getStartWarnSeconds()); assertEquals(4, PvpLoader.getStartCountdownFrom());
        assertEquals(20, PvpLoader.getStartActiveMinutes()); assertEquals(1200000, PvpLoader.getStartActiveMs()); assertEquals(40, PvpLoader.getKnockoutSeconds());
        assertEquals(3, PvpLoader.getBlindnessAmplifier()); assertEquals(2, PvpLoader.getFreezePeriodTicks()); assertEquals(45, PvpLoader.getDecisionSeconds());
        assertEquals(45000, PvpLoader.getDecisionMs()); assertEquals(36, PvpLoader.getSameTargetCooldownHours()); assertEquals(36 * 3_600_000L, PvpLoader.getSameTargetCooldownMs()); assertTrue(PvpLoader.isStrikeDecayEnabled()); assertEquals(1814400000L, PvpLoader.getStrikeDecayMs());
        messages.forEach((key,read) -> assertEquals(key + " {player}", read.get(), key));
        new PvpLoader().load(yaml("{}")); assertEquals(16, PvpLoader.getStartRadius()); assertFalse(PvpLoader.isStrikeDecayEnabled()); assertEquals(14*86400000L, PvpLoader.getStrikeDecayMs()); assertEquals(24, PvpLoader.getSameTargetCooldownHours()); assertEquals(24 * 3_600_000L, PvpLoader.getSameTargetCooldownMs());
    }

    @Test void pvpBoundsCannotScheduleZeroLengthRepeatingTasks() throws Exception {
        new PvpLoader().load(yaml("""
            start-radius: -1
            start-warn-seconds: 0
            start-countdown-from: 0
            start-active-minutes: -1
            knockout-seconds: 0
            blindness-amplifier: -1
            freeze-period-ticks: 0
            strikes: {decision-seconds: 0, same-target-cooldown-hours: -3, decay: {days: 0}}
            """));
        assertEquals(0, PvpLoader.getStartRadius()); assertEquals(1, PvpLoader.getStartWarnSeconds()); assertEquals(1, PvpLoader.getStartCountdownFrom());
        assertEquals(0, PvpLoader.getStartActiveMs()); assertEquals(1, PvpLoader.getKnockoutSeconds()); assertEquals(0, PvpLoader.getBlindnessAmplifier());
        assertEquals(1, PvpLoader.getFreezePeriodTicks()); assertEquals(1000, PvpLoader.getDecisionMs()); assertEquals(0, PvpLoader.getSameTargetCooldownHours()); assertEquals(0L, PvpLoader.getSameTargetCooldownMs()); assertEquals(86400000L, PvpLoader.getStrikeDecayMs());
        new PvpLoader().load(yaml("start-active-minutes: 2147483647\nstrikes: {decision-seconds: 2147483647, decay: {days: 2147483647}}"));
        assertEquals(2147483647L*60000, PvpLoader.getStartActiveMs()); assertEquals(2147483647L*1000, PvpLoader.getDecisionMs());
        assertEquals(2147483647L*86400000, PvpLoader.getStrikeDecayMs());
    }

    @Test void rollConfigurationNormalizesIdsAndClampsOnlyLookupValues() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        new RollLoader().load(yaml("""
            use-perm: roll.base
            alt-perm: roll.alt
            default: {min: 2, max: 30}
            alternative: {min: 5, max: 80}
            d20: {min: 1, max: 20}
            broadcast: {text: '{player} rolled {roll}', range: 40}
            attribute-modifiers:
              scalar: ignored
              INTELLIGENCE: {'0': -5, '10': 0, '20': 6, invalid: 99}
              strength: {'5': 2}
            """));
        assertEquals("roll.base", Cache.rollPermission); assertEquals("roll.alt", Cache.rollAltPermission);
        assertEquals(2, Cache.rollDefaultMin); assertEquals(30, Cache.rollDefaultMax); assertEquals(5, Cache.rollAltMin); assertEquals(80, Cache.rollAltMax);
        assertEquals(1, Cache.rollD20Min); assertEquals(20, Cache.rollD20Max); assertEquals(40, Cache.rollBroadcastRange);
        assertEquals("{player} rolled {roll}", Cache.rollBroadcastText); assertEquals(Set.of("intelligence", "strength"), RollLoader.getAttributeIds());
        assertTrue(RollLoader.isKnownAttribute("INTELLIGENCE")); assertFalse(RollLoader.isKnownAttribute(null)); assertFalse(RollLoader.isKnownAttribute(" "));
        assertEquals(-5, RollLoader.getModifier("INTELLIGENCE", -100)); assertEquals(6, RollLoader.getModifier("INTELLIGENCE", 100));
        assertEquals(2, RollLoader.getModifier("STRENGTH", 5)); assertEquals(0, RollLoader.getModifier("strength", 10));
        assertEquals(0, RollLoader.getModifier(null, 10)); assertEquals(0, RollLoader.getModifier("missing", 10));
        assertThrows(UnsupportedOperationException.class, () -> RollLoader.getAttributeIds().clear());
        new RollLoader().load(yaml("{}")); assertTrue(RollLoader.getAttributeIds().isEmpty()); assertEquals(0, RollLoader.getModifier("strength", 5));
    }

    @Test void malformedFilesLeaveLoadersUsable() throws Exception {
        File malformed = yaml("bad: [unterminated"); new RollLoader().load(malformed); new PvpLoader().load(malformed);
        assertTrue(RollLoader.getAttributeIds().isEmpty()); assertEquals(16, PvpLoader.getStartRadius());
    }
}
