package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.chat.smart.*;
import net.tfminecraft.rpcharacters.speechbubble.*;
import org.bukkit.Material;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class MessageDisplayConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    Logger logger;
    final Map<Object, Map<Field, Object>> savedSettings = new IdentityHashMap<>();
    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class);
        for (Object settings : List.of(SmartMessageLoader.getSettings(), SpeechBubbleLoader.getSettings())) {
            Map<Field,Object> snapshot = new HashMap<>();
            for (Field field : settings.getClass().getDeclaredFields()) { field.setAccessible(true); snapshot.put(field, field.get(settings)); }
            savedSettings.put(settings, snapshot);
        }
        RPCharacters.plugin = mock(RPCharacters.class); logger = mock(Logger.class); when(RPCharacters.plugin.getLogger()).thenReturn(logger);
    }
    @AfterEach void restore() throws Exception {
        for (var entry : savedSettings.entrySet()) for (var field : entry.getValue().entrySet()) field.getKey().set(entry.getKey(), field.getValue());
        state.close(); MockBukkit.unmock();
    }
    File yaml(String text) throws Exception { return Files.writeString(directory.resolve("settings.yml"), text).toFile(); }

    @Test void smartHearingConfigurationLoadsRulesMaterialsAndAllPolicies() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        new SmartMessageLoader().load(yaml("""
            enabled: false
            debug-messages: true
            generic-muffle: false
            sender-hears-self: false
            fade-start-percent: 0.4
            min-audible: 0.06
            full-clarity: 0.9
            low-intelligibility-threshold: 0.12
            low-intelligibility-placeholder: Unclear
            ray-step: 0.25
            occlusion-max-weight: 4
            occlusion-curve: 3
            listener-anchor-search: {enabled: false, diagonals: false, early-exit-clear-los: false}
            sound-occlusion: {collision-fill-threshold: 0.7}
            block-attenuation: {default: 0.2, iron_block: 0.8, not_a_material: 1}
            muffle-rules:
              - {replace: hello, to: hmm, min-intelligibility: 0.1, max-intelligibility: '0.7', percentage: 55}
              - {replace: test, min-intelligibility: wrong, percentage: 200}
              - {to: ignored}
            charisma-hearing: {enabled: false, attribute: wisdom, max-boost: 0.3, scale: 50, placeholder-boost-multiplier: 0.4}
            anonymous-muffled-voice: {enabled: false, max-intelligibility: 0.6, anonymous-display: Unknown}
            placeholder-suppression: {enabled: false}
            """));
        var s = SmartMessageLoader.getSettings();
        assertFalse(s.isEnabled()); assertTrue(s.isDebugMessages()); assertFalse(s.isGenericMuffle()); assertFalse(s.isSenderHearsSelf());
        assertEquals(.4, s.getFadeStartPercent()); assertEquals(.06, s.getMinAudible()); assertEquals(.9, s.getFullClarity());
        assertEquals(.12, s.getLowIntelligibilityThreshold()); assertEquals("Unclear", s.getLowIntelligibilityPlaceholder());
        assertEquals(.25, s.getRayStep()); assertEquals(4, s.getOcclusionMaxWeight()); assertEquals(3, s.getOcclusionCurve());
        assertFalse(s.isListenerAnchorSearch()); assertFalse(s.isListenerAnchorDiagonals()); assertFalse(s.isListenerAnchorEarlyExitClearLos());
        assertEquals(.7, s.getCollisionFillThreshold()); assertEquals(.2, s.getDefaultBlockAttenuation()); assertEquals(.2, s.getBlockAttenuation(null));
        assertEquals(.8, s.getBlockAttenuation(Material.IRON_BLOCK)); assertEquals(.8, s.getBlockAttenuationOverride(Material.IRON_BLOCK));
        assertNull(s.getBlockAttenuationOverride(null)); assertNull(s.getBlockAttenuationOverride(Material.AIR));
        assertTrue(Double.isFinite(s.getBlockAttenuation(Material.STONE)));
        assertFalse(s.isCharismaHearingEnabled()); assertEquals("wisdom", s.getCharismaAttribute()); assertEquals(.3, s.getCharismaMaxBoost());
        assertEquals(50, s.getCharismaScale()); assertEquals(.4, s.getCharismaPlaceholderMultiplier());
        assertFalse(s.isAnonymousMuffledVoiceEnabled()); assertEquals(.6, s.getAnonymousMuffledMaxIntelligibility());
        assertEquals("Unknown", s.getAnonymousMuffledDisplay()); assertFalse(s.isPlaceholderSuppressionEnabled());
        assertEquals(2, s.getMuffleRules().size()); var rule = s.getMuffleRules().getFirst();
        assertEquals("hello", rule.getReplace()); assertEquals("hmm", rule.getTo()); assertEquals(.1, rule.getMinIntelligibility());
        assertEquals(.7, rule.getMaxIntelligibility()); assertEquals(55, rule.getPercentage());
        assertTrue(rule.appliesTo(.1)); assertTrue(rule.appliesTo(.7)); assertFalse(rule.appliesTo(0)); assertFalse(rule.appliesTo(1));
        var fallback = s.getMuffleRules().get(1); assertEquals("", fallback.getTo()); assertEquals(0, fallback.getMinIntelligibility());
        assertEquals(1, fallback.getMaxIntelligibility()); assertEquals(100, fallback.getPercentage());
        assertThrows(UnsupportedOperationException.class, () -> s.getMuffleRules().clear());
        var blank = new MuffleRule(null, null, 0, 1, -1); assertEquals("", blank.getReplace()); assertEquals("", blank.getTo()); assertEquals(0, blank.getPercentage());
        verify(logger).info("[SmartMessages] config-load: debug-messages enabled");
    }

    @Test void smartDefaultsResetRemovedSectionsAndClampUnsafeGeometry() throws Exception {
        new SmartMessageLoader().load(yaml("""
            ray-step: -1
            occlusion-max-weight: 0
            occlusion-curve: -2
            sound-occlusion: {collision-fill-threshold: 5}
            charisma-hearing: {scale: 0, placeholder-boost-multiplier: -1}
            anonymous-muffled-voice: {max-intelligibility: 5}
            """));
        var s = SmartMessageLoader.getSettings(); assertEquals(.1, s.getRayStep()); assertEquals(.1, s.getOcclusionMaxWeight()); assertEquals(.1, s.getOcclusionCurve());
        assertEquals(1, s.getCollisionFillThreshold()); assertEquals(1, s.getCharismaScale()); assertEquals(0, s.getCharismaPlaceholderMultiplier());
        assertEquals(1, s.getAnonymousMuffledMaxIntelligibility());
        new SmartMessageLoader().load(yaml("{}")); assertTrue(s.isEnabled()); assertFalse(s.isDebugMessages());
        assertTrue(s.isGenericMuffle()); assertTrue(s.isSenderHearsSelf()); assertTrue(s.isListenerAnchorSearch());
        assertTrue(s.isListenerAnchorDiagonals()); assertTrue(s.isListenerAnchorEarlyExitClearLos()); assertEquals(.3, s.getCollisionFillThreshold());
        assertTrue(s.isCharismaHearingEnabled()); assertEquals("charisma", s.getCharismaAttribute()); assertEquals(.2, s.getCharismaMaxBoost());
        assertEquals(40, s.getCharismaScale()); assertEquals(.5, s.getCharismaPlaceholderMultiplier());
        assertTrue(s.isAnonymousMuffledVoiceEnabled()); assertEquals(.65, s.getAnonymousMuffledMaxIntelligibility());
        assertEquals("???", s.getAnonymousMuffledDisplay()); assertTrue(s.isPlaceholderSuppressionEnabled()); assertTrue(s.getMuffleRules().isEmpty());
        Map<Material,Double> source = new HashMap<>(Map.of(Material.STONE, .9)); s.setBlockAttenuation(source); source.clear();
        assertEquals(.9, s.getBlockAttenuation(Material.STONE)); s.setBlockAttenuation(null); assertNull(s.getBlockAttenuationOverride(Material.STONE));
        s.setMuffleRules(null); assertTrue(s.getMuffleRules().isEmpty());
    }

    @Test void speechBubbleDisplayLoadsAllValuesAndBoundsFollowing() throws Exception {
        new SpeechBubbleLoader().load(yaml("""
            enabled: false
            debug-messages: true
            display:
              scale: 0.8
              max-characters-per-line: 40
              line-spacing: 0.3
              first-line-offset: 0.2
              height-above-head: 1.5
              max-stacked-utterances: 3
              utterance-timeout-seconds: 8
              bob-amplitude: 0.1
              bob-period-ticks: 60
              follow-lerp-factor: 2
            """));
        var s = SpeechBubbleLoader.getSettings(); assertFalse(s.isEnabled()); assertTrue(s.isDebugMessages());
        assertEquals(.8f, s.getScale()); assertEquals(40, s.getMaxCharactersPerLine()); assertEquals(.3, s.getLineSpacing());
        assertEquals(.2, s.getFirstLineOffset()); assertEquals(1.5, s.getHeightAboveHead()); assertEquals(3, s.getMaxStackedUtterances());
        assertEquals(8, s.getUtteranceTimeoutSeconds()); assertEquals(.1, s.getBobAmplitude()); assertEquals(60, s.getBobPeriodTicks()); assertEquals(1, s.getFollowLerpFactor());
        verify(logger).info("[SpeechBubbles] config-load: enabled=false, scale=0.8, heightAboveHead=1.5, maxLineLen=40");
        new SpeechBubbleLoader().load(yaml("display: {follow-lerp-factor: -1}")); assertEquals(0, s.getFollowLerpFactor());
        new SpeechBubbleLoader().load(yaml("{}")); assertTrue(s.isEnabled()); assertFalse(s.isDebugMessages()); assertEquals(.8f, s.getScale());
    }

    @Test void debugLoggingHonoursDisabledAndMissingPluginStates() {
        SmartMessageLoader.getSettings().setDebugMessages(false); SpeechBubbleLoader.getSettings().setDebugMessages(false);
        assertFalse(SmartMessageDebug.isEnabled()); assertFalse(SpeechBubbleDebug.isEnabled());
        SmartMessageDebug.log("test", "hidden"); SpeechBubbleDebug.log("test", "hidden"); verifyNoInteractions(logger);
        SmartMessageLoader.getSettings().setDebugMessages(true); SpeechBubbleLoader.getSettings().setDebugMessages(true);
        SmartMessageDebug.logSkip("test", "blocked"); SpeechBubbleDebug.logSkip("test", "blocked");
        verify(logger).info("[SmartMessages] test: SKIP — blocked"); verify(logger).info("[SpeechBubbles] test: SKIP — blocked");
        RPCharacters.plugin = null; SmartMessageDebug.log("test", "ignored"); SpeechBubbleDebug.log("test", "ignored");
        verifyNoMoreInteractions(logger);
    }

    @Test void brokenFilesLeaveSettingsLoadersUsable() throws Exception {
        File bad = yaml("bad: [unterminated"); new SmartMessageLoader().load(bad); new SpeechBubbleLoader().load(bad);
        assertTrue(SmartMessageLoader.getSettings().isEnabled()); assertTrue(SpeechBubbleLoader.getSettings().isEnabled());
        RPCharacters.plugin = null; new SmartMessageLoader().load(yaml("debug-messages: true")); new SpeechBubbleLoader().load(yaml("debug-messages: true"));
        assertTrue(SmartMessageDebug.isEnabled()); assertTrue(SpeechBubbleDebug.isEnabled());
    }
}
