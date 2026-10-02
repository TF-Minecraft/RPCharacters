package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class GraveLoaderTest {
    @TempDir Path directory;
    RuntimeTestState state;
    MockedStatic<TLibs> tlibs;
    ItemAPI items;
    GraveLoader loader;

    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); state = new RuntimeTestState(GraveLoader.class); loader = new GraveLoader();
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS); tlibs = mockStatic(TLibs.class); tlibs.when(TLibs::getItemAPI).thenReturn(items);
        when(items.getChecker().checkItemWithPath(any(ItemStack.class), anyString())).thenAnswer(call -> {
            ItemStack item = call.getArgument(0); String path = call.getArgument(1);
            return ("v." + item.getType().name()).equals(path);
        });
        load("");
    }
    @AfterEach void restore() { tlibs.close(); state.close(); MockBukkit.unmock(); }
    void load(String yaml) throws Exception { loader.load(Files.writeString(directory.resolve("grave.yml"), yaml).toFile()); }

    @Test void defaultConfigurationProvidesWorkingGravesAndReadableMessages() {
        assertTrue(GraveLoader.isEnabled()); assertTrue(GraveLoader.isProtectByDefault()); assertTrue(GraveLoader.isHologramShowKiller());
        assertEquals(20, GraveLoader.getSnapshotIntervalTicks()); assertEquals(0, GraveLoader.getExpireSeconds());
        assertEquals(Material.CHEST, GraveLoader.getMaterial()); assertEquals(1.2, GraveLoader.getHologramOffsetY()); assertEquals(32, GraveLoader.getHologramRadius());
        assertEquals("&7Despawns in &e{time}", GraveLoader.getHologramTimerFormat()); assertEquals("&7Despawning...", GraveLoader.getHologramTimerExpiring());
        assertTrue(GraveLoader.getExcludedSlots().isEmpty()); assertTrue(GraveLoader.isInsuranceEnabled()); assertTrue(GraveLoader.isInsuranceConsume());
        assertEquals("&7Bound to your grave at &f{x}, {y}, {z}", GraveLoader.getInsuranceBoundLore());
        assertEquals("&cThis grave is locked.", GraveLoader.getMessageLocked());
        assertEquals(15, messages().size()); assertTrue(messages().values().stream().allMatch(value -> value != null && !value.isBlank()));
    }

    @Test void configuredSettingsAndEveryMessageAreExposedWithoutLosingPlaceholders() throws Exception {
        YamlConfiguration config = new YamlConfiguration(); config.loadFromString("""
            enabled: false
            protect-by-default: false
            hologram-show-killer: false
            snapshot-interval-ticks: 7
            expire-seconds: 90
            material: BARREL
            hologram-offset-y: 2.5
            hologram-radius: 12.0
            hologram-timer-format: '{time} remains'
            hologram-timer-expiring: Leaving
            insurance: {item: ' v.PAPER ', consume: false, bound-lore: '{world} {x}'}
            """);
        for (String key : messages().keySet()) config.set("messages." + key, "custom " + key + " {x}");
        load(config.saveToString());
        assertFalse(GraveLoader.isEnabled()); assertFalse(GraveLoader.isProtectByDefault()); assertFalse(GraveLoader.isHologramShowKiller());
        assertEquals(7, GraveLoader.getSnapshotIntervalTicks()); assertEquals(90, GraveLoader.getExpireSeconds()); assertEquals(Material.BARREL, GraveLoader.getMaterial());
        assertEquals(2.5, GraveLoader.getHologramOffsetY()); assertEquals(12, GraveLoader.getHologramRadius());
        assertEquals("{time} remains", GraveLoader.getHologramTimerFormat()); assertEquals("Leaving", GraveLoader.getHologramTimerExpiring());
        assertFalse(GraveLoader.isInsuranceConsume()); assertEquals("{world} {x}", GraveLoader.getInsuranceBoundLore());
        assertTrue(GraveLoader.isInsuranceItem(new ItemStack(Material.PAPER))); assertFalse(GraveLoader.isInsuranceItem(new ItemStack(Material.STONE)));
        messages().forEach((key, value) -> assertEquals("custom " + key + " {x}", value, key));
    }

    @Test void exclusionsRespectValidSlotsGlobalItemsAndPerSlotItemPaths() throws Exception {
        load("""
            excluded-slots: [-1, 0, 40, 41, 0]
            excluded-items: [' ', ' v.STONE ']
            excluded-slot-items:
              invalid: [v.STONE]
              '-1': [v.STONE]
              '41': [v.STONE]
              '1': [' ', v.DIRT]
              '2': []
              '3': [v.GRAVEL, v.SAND]
            insurance: {item: v.PAPER}
            """);
        assertEquals(Set.of(0, 40), GraveLoader.getExcludedSlots());
        assertThrows(UnsupportedOperationException.class, () -> GraveLoader.getExcludedSlots().add(2));
        assertTrue(GraveLoader.isExcludedSlot(0)); assertFalse(GraveLoader.isExcludedSlot(1));
        assertFalse(GraveLoader.keepOutOfGrave(0, null)); assertFalse(GraveLoader.keepOutOfGrave(0, new ItemStack(Material.AIR)));
        assertTrue(GraveLoader.keepOutOfGrave(0, new ItemStack(Material.DIRT)));
        assertFalse(GraveLoader.isExcludedItem(null)); assertFalse(GraveLoader.isExcludedItem(new ItemStack(Material.AIR)));
        assertTrue(GraveLoader.isExcludedItem(new ItemStack(Material.STONE))); assertFalse(GraveLoader.isExcludedItem(new ItemStack(Material.DIRT)));
        assertTrue(GraveLoader.keepOutOfGrave(4, new ItemStack(Material.STONE)));
        assertTrue(GraveLoader.keepOutOfGrave(4, new ItemStack(Material.PAPER)));
        assertTrue(GraveLoader.keepOutOfGrave(1, new ItemStack(Material.DIRT))); assertFalse(GraveLoader.keepOutOfGrave(1, new ItemStack(Material.SAND)));
        assertFalse(GraveLoader.keepOutOfGrave(2, new ItemStack(Material.DIRT))); assertFalse(GraveLoader.keepOutOfGrave(4, new ItemStack(Material.DIRT)));
        assertTrue(GraveLoader.keepOutOfGrave(3, new ItemStack(Material.SAND)));
    }

    @Test void invalidMaterialAndDurationsUseDefaultsAndInsuranceCanBeDisabled() throws Exception {
        load("material: NOT_A_MATERIAL\nsnapshot-interval-ticks: -1\nexpire-seconds: -1\ninsurance: {item: ' '}\n");
        assertEquals(Material.CHEST, GraveLoader.getMaterial()); assertEquals(1, GraveLoader.getSnapshotIntervalTicks()); assertEquals(0, GraveLoader.getExpireSeconds());
        assertFalse(GraveLoader.isInsuranceEnabled()); assertFalse(GraveLoader.isInsuranceItem(new ItemStack(Material.PAPER)));
        assertFalse(GraveLoader.isExcludedItem(new ItemStack(Material.STONE)));
        load("insurance: {item: v.PAPER}\n"); assertFalse(GraveLoader.isInsuranceItem(null)); assertFalse(GraveLoader.isInsuranceItem(new ItemStack(Material.AIR)));
    }

    @Test void unreadableAndMalformedFilesFailSoftWithUsableDefaults() throws Exception {
        assertDoesNotThrow(() -> loader.load(directory.resolve("missing.yml").toFile())); assertEquals(Material.CHEST, GraveLoader.getMaterial());
        load("malformed: ["); assertTrue(GraveLoader.isEnabled()); assertEquals(20, GraveLoader.getSnapshotIntervalTicks());
    }

    @Test void removingConfiguredStringsRestoresDocumentedDefaultsOnReload() throws Exception {
        Map<String, String> defaults = messages(); String timer = GraveLoader.getHologramTimerFormat(), expiring = GraveLoader.getHologramTimerExpiring();
        String lore = GraveLoader.getInsuranceBoundLore();
        YamlConfiguration config = new YamlConfiguration();
        for (String key : defaults.keySet()) config.set("messages." + key, "custom " + key);
        config.set("hologram-timer-format", "custom"); config.set("hologram-timer-expiring", "custom");
        config.set("insurance.item", ""); config.set("insurance.bound-lore", "custom"); load(config.saveToString()); load("");
        assertEquals(defaults, messages()); assertEquals(timer, GraveLoader.getHologramTimerFormat()); assertEquals(expiring, GraveLoader.getHologramTimerExpiring());
        assertEquals(lore, GraveLoader.getInsuranceBoundLore()); assertTrue(GraveLoader.isInsuranceEnabled());
    }

    static Map<String, String> messages() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("locked", GraveLoader.getMessageLocked()); messages.put("recovered", GraveLoader.getMessageRecovered());
        messages.put("looted", GraveLoader.getMessageLooted()); messages.put("inventory-full", GraveLoader.getMessageInventoryFull());
        messages.put("empty", GraveLoader.getMessageEmpty()); messages.put("placed", GraveLoader.getMessagePlaced());
        messages.put("unlock-hint", GraveLoader.getMessageUnlockHint()); messages.put("insurance-none", GraveLoader.getMessageInsuranceNone());
        messages.put("insurance-gone", GraveLoader.getMessageInsuranceGone()); messages.put("insurance-bound", GraveLoader.getMessageInsuranceBound());
        messages.put("unlocked-evil-rp", GraveLoader.getMessageUnlockedByStrike()); messages.put("unlock-none", GraveLoader.getMessageUnlockNone());
        messages.put("unlock-already", GraveLoader.getMessageUnlockAlready()); messages.put("unlock-success", GraveLoader.getMessageUnlockSuccess());
        messages.put("rob-hint", GraveLoader.getMessageRobHint()); return messages;
    }
}
