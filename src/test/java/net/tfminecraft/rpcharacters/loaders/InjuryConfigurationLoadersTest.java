package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class InjuryConfigurationLoadersTest {
    @TempDir Path directory;
    RuntimeTestState state;
    final Map<String,Trait> traits = new HashMap<>();
    MockedStatic<TraitLoader> traitLoader;
    MockedStatic<TLibs> tlibs;
    ItemAPI items;
    Logger logger;
    World world;
    @BeforeEach void setup() {
        world = MockBukkit.mock().addSimpleWorld("world");
        state = new RuntimeTestState(RPCharacters.class, InjuryPoolLoader.class, InjuryProgressionLoader.class,
            ProstheticLoader.class, FuelTemplateLoader.class, PermadeathZoneLoader.class);
        RPCharacters.plugin = mock(RPCharacters.class); logger = mock(Logger.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(logger);
        traitLoader = mockStatic(TraitLoader.class);
        traitLoader.when(() -> TraitLoader.getByString(nullable(String.class)))
            .thenAnswer(inv -> inv.getArgument(0) == null ? null : traits.get(((String)inv.getArgument(0)).toLowerCase(Locale.ROOT)));
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS); tlibs = mockStatic(TLibs.class); tlibs.when(TLibs::getItemAPI).thenReturn(items);
    }
    @AfterEach void restore() { tlibs.close(); traitLoader.close(); state.close(); MockBukkit.unmock(); }
    File yaml(String text) throws Exception { return Files.writeString(directory.resolve("settings.yml"), text).toFile(); }
    Trait trait(String id, String kind, boolean healing) {
        Trait trait = mock(Trait.class); TraitData data = mock(TraitData.class);
        when(trait.getId()).thenReturn(id); when(trait.getTraitData()).thenReturn(data);
        when(data.hasDuration()).thenReturn(healing); when(data.isInjuryKey()).thenReturn(kind.equals("injury"));
        when(data.isProstheticKey()).thenReturn(kind.equals("prosthetic")); traits.put(id.toLowerCase(Locale.ROOT), trait); return trait;
    }

    @Test void injuryPoolValidatesWeightsAndSkipsOwnedInjuriesCaseInsensitively() throws Exception {
        Trait bruise = trait("bruise", "injury", true), wound = trait("wound", "injury", true);
        trait("permanent", "injury", false);
        new InjuryPoolLoader().load(yaml("""
            healing-tick-interval: 250ms
            injuries:
              scalar: ignored
              zero: {weight: 0}
              missing: {weight: 1}
              permanent: {weight: 1}
              BRUISE: {weight: 1}
              wound: {weight: 1}
            """));
        assertEquals(250, InjuryPoolLoader.getHealingTickIntervalMs()); assertEquals(20, InjuryPoolLoader.getHealingTickIntervalTicks());
        assertEquals(Set.of("bruise", "wound"), InjuryPoolLoader.getPoolTraitIds());
        assertThrows(UnsupportedOperationException.class, () -> InjuryPoolLoader.getPoolTraitIds().clear());
        assertEquals(1, InjuryPoolLoader.countRemainingInjuries(Set.of("BRUISE")));
        assertEquals(2, InjuryPoolLoader.countRemainingInjuries(Set.of("other")));
        assertSame(wound, InjuryPoolLoader.pickRandom(Set.of("BRUISE")));
        assertNull(InjuryPoolLoader.pickRandom(Set.of("bruise", "WOUND")));
        try (var random = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom generator = mock(ThreadLocalRandom.class);
            random.when(ThreadLocalRandom::current).thenReturn(generator);
            when(generator.nextInt(2)).thenReturn(0,1); when(generator.nextLong(2L)).thenReturn(0L,1L);
            assertEquals(Set.of(bruise,wound), Set.of(InjuryPoolLoader.pickRandom(Set.of()), InjuryPoolLoader.pickRandom(Set.of())));
        }
        new InjuryPoolLoader().load(yaml("healing-tick-interval: invalid\n"));
        assertEquals(60_000, InjuryPoolLoader.getHealingTickIntervalMs()); assertEquals(1_200, InjuryPoolLoader.getHealingTickIntervalTicks());
        assertNull(InjuryPoolLoader.pickRandom(Set.of()));
        new InjuryPoolLoader().load(yaml("{}")); assertEquals(60_000, InjuryPoolLoader.getHealingTickIntervalMs());
    }

    @Test void largeValidWeightsMustNotMakeAllInjuriesDisappear() throws Exception {
        trait("bruise", "injury", true); trait("wound", "injury", true);
        new InjuryPoolLoader().load(yaml("injuries:\n  bruise: {weight: 2147483647}\n  wound: {weight: 2147483647}\n"));
        assertNotNull(InjuryPoolLoader.pickRandom(Set.of()), "Positive weights remain selectable when their sum exceeds an int");
    }

    @Test void injuryProgressionRejectsInvalidLinksAndPublishesOnlyPermanentInjuries() throws Exception {
        trait("HEAL", "injury", true); trait("permanent", "injury", false); trait("temporary", "injury", true);
        trait("nonduration", "injury", false); trait("unknown-target", "injury", true);
        trait("temporary-target", "injury", true); trait("wrong-target", "injury", true); trait("other", "physical", false);
        new InjuryProgressionLoader().load(yaml("""
            progression:
              blank: ''
              unknown-source: permanent
              nonduration: permanent
              unknown-target: missing
              temporary-target: temporary
              wrong-target: other
              HEAL: PERMANENT
            """));
        assertEquals(Map.of("heal", "permanent"), InjuryProgressionLoader.getProgressionMap());
        assertEquals("permanent", InjuryProgressionLoader.getPermanentId("HEAL"));
        for (String absent : Arrays.asList(null," ","missing")) assertNull(InjuryProgressionLoader.getPermanentId(absent));
        assertTrue(InjuryProgressionLoader.isHealingTrait("heal")); assertFalse(InjuryProgressionLoader.isHealingTrait("missing"));
        assertFalse(InjuryProgressionLoader.isHealingTrait("permanent")); assertTrue(InjuryProgressionLoader.isPermanentInjury("permanent"));
        assertFalse(InjuryProgressionLoader.isPermanentInjury("temporary")); assertFalse(InjuryProgressionLoader.isPermanentInjury("other"));
        assertFalse(InjuryProgressionLoader.isPermanentInjury("missing"));
        assertThrows(UnsupportedOperationException.class, () -> InjuryProgressionLoader.getProgressionMap().clear());
        new InjuryProgressionLoader().load(yaml("{}")); assertTrue(InjuryProgressionLoader.getProgressionMap().isEmpty());
    }

    @Test void prostheticValidationRejectsBadRowsAndPreservesValidMappings() throws Exception {
        trait("arm", "injury", false); trait("leg", "injury", false); trait("temporary", "injury", true); trait("other", "physical", false);
        Trait prosthetic = trait("wood", "prosthetic", false), powered = trait("powered", "prosthetic", false);
        for (String invalid : List.of("missing: {wood: vanilla.STICK}", "other: {wood: vanilla.STICK}", "temporary: {wood: vanilla.STICK}",
                "arm: {wood: ''}", "arm: {missing: vanilla.STICK}", "arm: {other: vanilla.STICK}", "arm: {' ': vanilla.STICK}", "arm: {}", "scalar: ignored")) {
            new ProstheticLoader().load(yaml("replacements:\n  " + invalid + "\n")); assertNull(ProstheticLoader.getReplacement("arm"));
        }
        when(powered.hasFuelTemplate()).thenReturn(true); when(powered.getFuelTemplateId()).thenReturn("fuel");
        String poweredYaml = "replacements:\n  arm: {powered: vanilla.STICK}\n";
        new ProstheticLoader().load(yaml(poweredYaml)); assertNull(ProstheticLoader.getReplacement("arm"));
        new FuelTemplateLoader().load(yaml("fuel: {item: vanilla.COAL, amount-per-item: 1, burn-rate: 1, burn-interval: 1m}\n"));
        new ProstheticLoader().load(yaml(poweredYaml)); assertNull(ProstheticLoader.getReplacement("arm"));
        when(powered.getFuelCapacity()).thenReturn(10D);
        new ProstheticLoader().load(yaml(poweredYaml)); assertNull(ProstheticLoader.getReplacement("arm"));
        when(powered.hasPoweredVariant()).thenReturn(true);
        new ProstheticLoader().load(yaml(poweredYaml)); assertNull(ProstheticLoader.getReplacement("arm"));
        when(powered.getDepoweredVariant()).thenReturn(mock(TraitVariant.class));
        new ProstheticLoader().load(yaml("replacements:\n  ARM: {wood: vanilla.STICK, powered: vanilla.IRON_INGOT}\n  leg: {wood: vanilla.STICK}\n"));
        var replacement = ProstheticLoader.getReplacement("arm"); assertNotNull(replacement); assertNull(ProstheticLoader.getReplacement("leg"));
        assertEquals("arm", replacement.getPermanentInjuryId()); assertEquals(Map.of("wood","vanilla.STICK","powered","vanilla.IRON_INGOT"), replacement.getItemByTraitId());
        assertSame(replacement, ProstheticLoader.getReplacementForProsthetic("WOOD")); assertEquals("vanilla.STICK", replacement.getItemPath("WOOD"));
        assertTrue(replacement.containsTrait("wood")); assertFalse(replacement.containsTrait("none"));
        for (String missing : Arrays.asList(null," ","missing")) {
            assertNull(ProstheticLoader.getReplacement(missing)); assertNull(ProstheticLoader.getReplacementForProsthetic(missing)); assertNull(replacement.getItemPath(missing));
        }
        assertNull(replacement.ownedProstheticId(null)); assertNull(replacement.ownedProstheticId(Arrays.asList(null, mock(Trait.class), trait("unrelated","physical",false))));
        assertEquals("wood", replacement.ownedProstheticId(List.of(prosthetic)));
        ItemStack stick = new ItemStack(Material.STICK); when(items.getChecker().checkItemWithPath(stick,"vanilla.STICK")).thenReturn(true);
        var match = ProstheticLoader.resolveForItem(stick); assertSame(replacement, match.getReplacement()); assertEquals("wood", match.getTraitId()); assertEquals("vanilla.STICK", match.getItemPath());
        for (ItemStack missing : Arrays.asList(null,new ItemStack(Material.AIR),new ItemStack(Material.STONE))) assertNull(ProstheticLoader.resolveForItem(missing));
        new ProstheticLoader().load(yaml("{}")); assertNull(ProstheticLoader.resolveForItem(stick));
    }

    @SuppressWarnings("deprecation")
    @Test void zonesReadLegacyNamesClampSettingsAndPersistSpawnCoordinates() throws Exception {
        // Before the first load, saving a spawn has no configured destination.
        var field = PermadeathZoneLoader.class.getDeclaredField("configFile"); field.setAccessible(true); field.set(null,null);
        PermadeathZoneLoader.saveWorldSpawn(new Location(world,1,2,3));
        File file = yaml("""
            permadeath-chance-per-injury: -3
            zone-check-cooldown-ms: -1
            world-spawn: {world: world, x: 1.5, y: 65, z: -4.5, yaw: 20, pitch: 5}
            permadeath-zones:
              scalar: ignored
              CAMP: {name: Camp}
              legacy: {display-name: 'Now entering Old City'}
              leaving: {display-name: 'Now leaving Hills'}
              plain: {display-name: Meadow}
              blank: {display-name: ''}
              prefix: {display-name: 'Now entering '}
            """);
        new PermadeathZoneLoader().load(file);
        assertEquals(0,PermadeathZoneLoader.getChancePerInjury()); assertEquals(0,PermadeathZoneLoader.getZoneCheckCooldownMs());
        assertEquals("CAMP",PermadeathZoneLoader.getZone("camp").getRegionId()); assertEquals("Camp",PermadeathZoneLoader.getZone("CAMP").getName());
        assertEquals("Old City",PermadeathZoneLoader.getZone("legacy").getDisplayName()); assertEquals("Hills",PermadeathZoneLoader.getZone("leaving").getName());
        assertEquals("Meadow",PermadeathZoneLoader.getZone("plain").getName()); assertEquals("",PermadeathZoneLoader.getZone("blank").getName());
        assertEquals("Now entering ",PermadeathZoneLoader.getZone("prefix").getName()); assertNull(PermadeathZoneLoader.getZone(null));
        assertEquals(6,PermadeathZoneLoader.getZones().size()); assertThrows(UnsupportedOperationException.class,()->PermadeathZoneLoader.getZones().clear());
        assertEquals(new Location(world,1.5,65,-4.5,20,5),PermadeathZoneLoader.getWorldSpawn());
        Location next = new Location(world,4,80,6,90,10); PermadeathZoneLoader.saveWorldSpawn(next);
        new PermadeathZoneLoader().load(file); assertEquals(next,PermadeathZoneLoader.getWorldSpawn());
        PermadeathZoneLoader.saveWorldSpawn(null); assertNull(PermadeathZoneLoader.getWorldSpawn()); assertFalse(YamlConfiguration.loadConfiguration(file).contains("world-spawn"));
        for (String spawn : List.of("{}","world-spawn: {}","world-spawn: {world: ' '}","world-spawn: {world: absent}")) {
            new PermadeathZoneLoader().load(yaml(spawn)); assertNull(PermadeathZoneLoader.getWorldSpawn());
        }
    }

    @Test void failedSpawnWriteDoesNotChangeLiveSpawnAndWorldlessLocationsClearIt() throws Exception {
        File file = yaml("world-spawn: {world: world, x: 1, y: 2, z: 3}\n");
        new PermadeathZoneLoader().load(file); Location before = PermadeathZoneLoader.getWorldSpawn().clone();
        Files.delete(file.toPath()); Files.createDirectory(file.toPath());
        PermadeathZoneLoader.saveWorldSpawn(new Location(world,8,9,10));
        assertEquals(before,PermadeathZoneLoader.getWorldSpawn());
        Files.delete(file.toPath()); Files.writeString(file.toPath(),"{}");
        PermadeathZoneLoader.saveWorldSpawn(new Location(null,1,2,3)); assertNull(PermadeathZoneLoader.getWorldSpawn());
    }

    @Test void invalidYamlClearsRegistriesAndLeavesLoadersReusable() throws Exception {
        File malformed = yaml("bad: [unterminated");
        new InjuryPoolLoader().load(malformed); new InjuryProgressionLoader().load(malformed);
        new ProstheticLoader().load(malformed); new PermadeathZoneLoader().load(malformed);
        assertTrue(InjuryPoolLoader.getPoolTraitIds().isEmpty()); assertTrue(InjuryProgressionLoader.getProgressionMap().isEmpty());
        assertTrue(PermadeathZoneLoader.getZones().isEmpty()); assertNull(ProstheticLoader.getReplacement("arm"));
    }
}
