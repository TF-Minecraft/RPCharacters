package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.objects.trait.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class CharacterDefinitionLoadersTest {
    @TempDir Path directory;
    RuntimeTestState state;
    @BeforeEach void setup() { MockBukkit.mock(); state = new RuntimeTestState(StageLoader.class, RaceLoader.class, TraitLoader.class); StageLoader.oList.clear(); RaceLoader.oList.clear(); TraitLoader.oList.clear(); }
    @AfterEach void restore() { state.close(); MockBukkit.unmock(); }
    File yaml(String text) throws Exception { return Files.writeString(directory.resolve("settings.yml"), text).toFile(); }

    @Test void raceDefinitionsReplaceTheCatalogAndExposeConfiguredTraits() throws Exception {
        new RaceLoader().load(yaml("""
            human: {name: Human, description: [Versatile], shown: true, key: humanoid, age-max: 120, attribute-modifiers: ['strength.2']}
            elf: {name: Elf, description: [], shown: false}
            """));
        var race = RaceLoader.getByString("HUMAN"); assertEquals("human", race.getId()); assertEquals("Human", race.getName());
        assertEquals(List.of("Versatile"), race.getDesc()); assertTrue(race.isShown()); assertEquals(120, race.getAgeMax());
        assertEquals("humanoid", race.getRaceData().getKey()); assertEquals(120, race.getRaceData().getAgeMax());
        assertEquals(2, race.getRaceData().getAttributeData().getModifiers().getFirst().getAmount());
        assertEquals(2, RaceLoader.get().size()); assertEquals(100, RaceLoader.getByString("elf").getAgeMax());
        assertNull(RaceLoader.getByString("missing")); assertNull(RaceLoader.getByString(null));
        new RaceLoader().load(yaml("dwarf: {name: Dwarf}")); assertEquals(1, RaceLoader.get().size()); assertNull(RaceLoader.getByString("human"));
    }

    @Test void traitFilesAccumulateAndCarryDurationFuelVariantsAndMessages() throws Exception {
        new TraitLoader().load(yaml("""
            prosthetic:
              name: Mechanical Arm
              description: [Strong, ' ', Reliable]
              key: physical
              dependency: {type: trait, mode: all, depends-on: [plain]}
              cost: 3
              mutually-exclusive: [natural_arm]
              gained-message: Gained
              lost-message: Lost
              required-account-playtime: 1.5
              duration: 2h
              fuel-template: coal
              fuel-capacity: 10
              block-offhand: true
              can-loot-graves: true
              icon: IRON_INGOT
              potion-effects: ['SPEED(2)', 'not_real(1)']
              powered: {name: Powered, description: [Charged, ''], potion-effects: ['HASTE(1)', unknown]}
              depowered: {}
            """));
        var trait = TraitLoader.getByString("PROSTHETIC"); assertEquals("prosthetic", trait.getId()); assertEquals("Mechanical Arm", trait.getName());
        assertEquals(List.of("Strong", "Reliable"), trait.getDesc()); assertEquals("Gained", trait.getGainedMessage()); assertEquals("Lost", trait.getLostMessage());
        assertTrue(trait.hasDuration()); assertEquals(7200000, trait.getDurationMs()); assertTrue(trait.hasFuelTemplate()); assertEquals("coal", trait.getFuelTemplateId());
        assertEquals(10, trait.getFuelCapacity()); assertTrue(trait.hasPoweredVariant()); assertEquals("Powered", trait.getPoweredVariant().getName());
        assertEquals(List.of("Charged"), trait.getPoweredVariant().getDescription()); assertTrue(trait.getPoweredVariant().hasPotionEffects());
        assertNotNull(trait.getPoweredVariant().getAttributeData()); assertEquals(1, trait.getPoweredVariant().getPotionEffects().size());
        assertThrows(UnsupportedOperationException.class, () -> trait.getPoweredVariant().getDescription().clear());
        assertNull(trait.getDepoweredVariant().getName()); assertFalse(trait.getDepoweredVariant().hasPotionEffects());
        assertTrue(trait.hasIcon()); assertEquals(Material.IRON_INGOT, trait.getIcon());
        TraitData d = trait.getTraitData(); assertEquals("physical", d.getKey()); assertTrue(d.hasCost()); assertEquals(3, d.getCost());
        assertTrue(d.hasExclusives()); assertEquals(List.of("natural_arm"), d.getExclusive()); assertTrue(d.isExclusive("NATURAL_ARM")); assertFalse(d.isExclusive("other"));
        assertTrue(d.hasPotionEffects()); assertEquals(1, d.getPotionEffects().size()); assertEquals(5400, d.getRequiredAccountPlaytimeSeconds());
        assertTrue(d.hasDependency()); assertNotNull(d.getDependency()); assertFalse(d.isInjuryKey()); assertFalse(d.isProstheticKey());
        assertTrue(d.blocksOffhand()); assertTrue(d.canLootGraves()); assertNotNull(d.getAttributeData());
        new TraitLoader().load(yaml("plain: {name: Plain}")); assertEquals(2, TraitLoader.get().size()); assertSame(trait, TraitLoader.getByString("prosthetic"));
        assertNull(TraitLoader.getByString("unknown")); assertNull(TraitLoader.getByString(null));
        d = TraitLoader.getByString("plain").getTraitData(); assertFalse(d.hasCost()); assertFalse(d.hasExclusives()); assertFalse(d.hasPotionEffects());
        assertFalse(d.hasDuration()); assertFalse(d.hasFuelTemplate()); assertFalse(d.hasPoweredVariant()); assertFalse(d.hasIcon());
        assertFalse(d.hasDependency()); assertNull(d.getDependency()); d.setDependency(new net.tfminecraft.rpcharacters.creation.Dependency(new YamlConfiguration())); assertTrue(d.hasDependency());
    }

    @Test void optionalTraitValuesRejectInvalidDurationsAndAirIcons() throws Exception {
        for (String icon : List.of("", "AIR", "invalid")) {
            var c = new YamlConfiguration(); c.set("icon", icon); c.set("required-account-playtime", -1); c.set("duration", "invalid"); c.set("fuel-template", " ");
            var data = new TraitData(c); assertFalse(data.hasIcon()); assertFalse(data.hasDuration()); assertFalse(data.hasFuelTemplate()); assertEquals(0, data.getRequiredAccountPlaytimeSeconds());
        }
        for (String input : Arrays.asList(null, "unknown", "SPEED(bad)", "SPEED(-2)", "speed", "SPEED(3)")) {
            var potion = new PotionData(input); boolean valid = input != null && input.toLowerCase(Locale.ROOT).startsWith("speed");
            assertEquals(valid, potion.isValid());
            if (valid) { assertEquals("speed", potion.getId()); assertEquals(PotionEffectType.SPEED, potion.getType()); assertEquals("SPEED(3)".equals(input) ? 3 : 0, potion.getAmplifier()); assertEquals(60, potion.getEffect(60).getDuration()); }
        }
    }

    @Test void stageCatalogCreatesIndependentSessionsAndResetsOnReload() throws Exception {
        new StageLoader().load(yaml("""
            intro: {type: INFO, interval: 20, repeat: false, auto-next: false, messages: [chat(Hello)], web-messages: [Welcome], revision: 2}
            finish: {type: SUMMARY}
            """));
        assertEquals(2, StageLoader.oList.size()); var intro = (InfoStage) StageLoader.getById("intro"); assertEquals(20, intro.getInterval());
        assertFalse(intro.shouldRepeat()); assertFalse(intro.autoNext()); assertEquals(2, intro.getRevision()); assertTrue(intro.hasWebMessages());
        assertEquals(List.of("chat(Hello)"), intro.getMessages()); assertEquals(List.of("Welcome"), intro.getWebMessages());
        var copies = StageLoader.getNew(); assertEquals(2, copies.size()); assertNotSame(intro, copies.getFirst()); copies.getFirst().setCancelled(true); assertFalse(intro.isCancelled());
        assertNull(StageLoader.getById("missing")); new StageLoader().load(yaml("{}")); assertTrue(StageLoader.oList.isEmpty());
    }

    @Test void malformedYamlLeavesDefinitionLoadersUsable() throws Exception {
        File bad = yaml("bad: [unterminated"); new StageLoader().load(bad); new RaceLoader().load(bad); new TraitLoader().load(bad);
        assertTrue(StageLoader.oList.isEmpty()); assertTrue(RaceLoader.get().isEmpty()); assertTrue(TraitLoader.get().isEmpty());
    }

    @Test void identifiersAndEffectTypesDoNotDependOnHostLocale() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        var config = new YamlConfiguration(); config.set("icon", "iron_ingot");
        assertEquals(Material.IRON_INGOT, new TraitData(config).getIcon());
        assertTrue(new PotionData("invisibility(1)").isValid());
        config.set("type", "info"); config.set("interval", 20);
        assertInstanceOf(InfoStage.class, Stage.create("intro", config));
    }

    @Test void scalarRowsAreIgnoredWithoutDiscardingValidDefinitions() throws Exception {
        assertDoesNotThrow(() -> new RaceLoader().load(yaml("bad: ignored\ngood: {name: Good}\n"))); assertNotNull(RaceLoader.getByString("good"));
        assertDoesNotThrow(() -> new TraitLoader().load(yaml("bad: ignored\ngood: {name: Good}\n"))); assertNotNull(TraitLoader.getByString("good"));
        assertDoesNotThrow(() -> new StageLoader().load(yaml("bad: ignored\ngood: {type: INFO, interval: 20}\n"))); assertNotNull(StageLoader.getById("good"));
    }
}
