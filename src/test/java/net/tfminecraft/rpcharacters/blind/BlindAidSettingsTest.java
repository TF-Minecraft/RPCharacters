package net.tfminecraft.rpcharacters.blind;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BlindAidSettingsTest {

    @Test void defaultsMatchTheShippedFile() throws Exception {
        BlindAidSettings d = BlindAidSettings.defaults();
        assertTrue(d.enabled());
        assertEquals(List.of("blind"), d.traits());
        assertEquals(List.of("m.props.walking_cane", "m.props.forearm_crutch", "m.props.forearm_crutch_slim"), d.caneItems());
        assertEquals(5, d.checkTicks());
        assertTrue(d.strideEnabled()); assertEquals(0.25, d.strideBonus()); assertEquals(7, d.strideMinFood());
        assertEquals(0.1f, d.strideExhaustionPerBlock(), 1e-6);
        assertTrue(d.senseEnabled()); assertEquals(10, d.senseRadius()); assertTrue(d.senseLineOfSight()); assertEquals(0.2, d.senseMinMove());
        assertTrue(d.tapEnabled()); assertEquals(8, d.tapRadius()); assertEquals(60, d.tapTicks());
        assertEquals(5000, d.tapCooldownMillis()); assertEquals(60, d.tapMaxMarkers()); assertEquals("minecraft:block.wood.hit", d.tapSound());
        assertTrue(d.warnEnabled()); assertEquals(3, d.warnLookAhead()); assertEquals(3, d.warnDropDepth()); assertEquals(3000, d.warnCooldownMillis());
        assertEquals("§cThe ground falls away ahead.", d.warnDropMessage());
        assertEquals("§6Something dangerous is just ahead.", d.warnHazardMessage());
        assertEquals("minecraft:block.note_block.bass", d.warnSound());

        var shipped = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/blind.yml")) {
            shipped.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(d, BlindAidSettings.from(shipped));
    }

    @Test void readsValuesTrimsNamesAndClampsNonsense() throws Exception {
        var c = new YamlConfiguration();
        c.loadFromString("""
            enabled: false
            traits: [" Blind ", "", "Deaf"]
            cane-items: ["M.PROPS.STAFF"]
            check-ticks: 0
            stride: {enabled: false, speed-bonus: -1, min-food: 3, exhaustion-per-block: -2}
            sound-sense: {enabled: false, radius: -4, line-of-sight: false, min-move: -1}
            cane-tap: {enabled: false, radius: 0, seconds: 0, cooldown-seconds: -1, max-markers: 0, sound: "x"}
            warnings: {enabled: false, look-ahead: 0, drop-depth: 0, cooldown-seconds: -3, drop-message: "&aD", hazard-message: "H", sound: "y"}
            """);
        BlindAidSettings s = BlindAidSettings.from(c);
        assertFalse(s.enabled()); assertEquals(List.of("blind", "deaf"), s.traits()); assertEquals(List.of("m.props.staff"), s.caneItems());
        assertEquals(1, s.checkTicks());
        assertFalse(s.strideEnabled()); assertEquals(0, s.strideBonus()); assertEquals(3, s.strideMinFood()); assertEquals(0f, s.strideExhaustionPerBlock());
        assertFalse(s.senseEnabled()); assertEquals(0, s.senseRadius()); assertFalse(s.senseLineOfSight()); assertEquals(0, s.senseMinMove());
        assertFalse(s.tapEnabled()); assertEquals(1, s.tapRadius()); assertEquals(1, s.tapTicks()); assertEquals(0, s.tapCooldownMillis());
        assertEquals(1, s.tapMaxMarkers()); assertEquals("x", s.tapSound());
        assertFalse(s.warnEnabled()); assertEquals(1, s.warnLookAhead()); assertEquals(1, s.warnDropDepth()); assertEquals(0, s.warnCooldownMillis());
        assertEquals("§aD", s.warnDropMessage()); assertEquals("H", s.warnHazardMessage()); assertEquals("y", s.warnSound());
    }

    @Test void capsRadiiAndFallsBackFromMalformedSoundKeys() throws Exception {
        var c = new YamlConfiguration();
        c.loadFromString("""
            sound-sense: {radius: 500}
            cane-tap: {radius: 80, sound: "BLOCK WOOD HIT"}
            warnings: {sound: "Bad:Key"}
            """);
        BlindAidSettings s = BlindAidSettings.from(c);
        assertEquals(32, s.senseRadius()); assertEquals(16, s.tapRadius());
        assertEquals("minecraft:block.wood.hit", s.tapSound()); assertEquals("minecraft:block.note_block.bass", s.warnSound());
        assertEquals("minecraft:x", BlindAidSettings.sound("minecraft:x", "d")); assertEquals("d", BlindAidSettings.sound(null, "d"));
    }
}
