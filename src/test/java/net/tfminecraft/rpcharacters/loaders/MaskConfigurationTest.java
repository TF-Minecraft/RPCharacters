package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import io.lumine.mythic.lib.api.item.NBTItem;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class MaskConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    MockedStatic<TLibs> tlibs;
    MockedStatic<NBTItem> nbtApi;
    ItemAPI items;
    ItemStack helmet;
    long stamp = 10000;
    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(MaskLoader.class); items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        tlibs = mockStatic(TLibs.class); tlibs.when(TLibs::getItemAPI).thenReturn(items); nbtApi = mockStatic(NBTItem.class);
        helmet = new ItemStack(Material.LEATHER_HELMET);
    }
    @AfterEach void restore() { nbtApi.close(); tlibs.close(); state.close(); MockBukkit.unmock(); }
    void bundled(String content) throws Exception {
        new MaskLoader().load(Files.writeString(directory.resolve("masks.yml"), content).toFile());
    }
    void custom(String content) throws Exception {
        Path file = Files.writeString(directory.resolve("custom-masks.yml"), content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp++));
    }

    @Test void mappingsSupportScalarAndSectionDefinitionsAndIgnoreBlankRows() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        bundled("masked-label: Hidden\nmasks:\n  blank: ' '\n  EMPTY: {}\n  IRON: ' vanilla.IRON_HELMET '\n  hood: {item: vanilla.LEATHER_HELMET}\n");
        assertEquals("Hidden", Cache.maskedLabel);
        assertNull(MaskLoader.resolveMask(null)); assertNull(MaskLoader.resolveMask(new ItemStack(Material.AIR)));
        assertNull(MaskLoader.resolveMask(helmet));
        when(items.getChecker().checkItemWithPath(helmet, "vanilla.LEATHER_HELMET")).thenReturn(true);
        var mask = MaskLoader.resolveMask(helmet); assertEquals("hood", mask.getId()); assertEquals("vanilla.LEATHER_HELMET", mask.getItem());
        bundled("{}"); assertEquals("Masked", Cache.maskedLabel); assertNull(MaskLoader.resolveMask(helmet));
    }

    @Test void listDefinitionsTrimPathsAndNumberOnlyValidEntries() throws Exception {
        bundled("masks: ['', ' vanilla.LEATHER_HELMET ', vanilla.PAPER]\n");
        when(items.getChecker().checkItemWithPath(helmet, "vanilla.LEATHER_HELMET")).thenReturn(true);
        var mask = MaskLoader.resolveMask(helmet); assertEquals("mask_0", mask.getId()); assertEquals("vanilla.LEATHER_HELMET", mask.getItem());
    }

    @Test void customFileCreationChangesAndDeletionRefreshWithoutRestart() throws Exception {
        bundled("masks: {shared: {item: vanilla.PAPER}}\n"); assertNull(MaskLoader.resolveMask(helmet));
        custom("masks: {shared: {item: vanilla.LEATHER_HELMET}}\n");
        when(items.getChecker().checkItemWithPath(helmet, "vanilla.LEATHER_HELMET")).thenReturn(true);
        assertEquals("shared", MaskLoader.resolveMask(helmet).getId());
        // Bundled and custom entries with the same ID remain independently resolvable.
        ItemStack paper = new ItemStack(Material.PAPER); when(items.getChecker().checkItemWithPath(paper, "vanilla.PAPER")).thenReturn(true);
        assertEquals("vanilla.PAPER", MaskLoader.resolveMask(paper).getItem());
        custom("masks: [vanilla.STONE]\n"); assertNull(MaskLoader.resolveMask(helmet));
        Files.delete(directory.resolve("custom-masks.yml")); assertNull(MaskLoader.resolveMask(helmet)); assertNotNull(MaskLoader.resolveMask(paper));
    }

    @Test void customMasksPresentAtStartupAreLoadedAndMalformedFilesCanRecover() throws Exception {
        custom("masks: [vanilla.LEATHER_HELMET]"); bundled("bad: [unterminated");
        when(items.getChecker().checkItemWithPath(helmet, "vanilla.LEATHER_HELMET")).thenReturn(true);
        assertNotNull(MaskLoader.resolveMask(helmet));
        custom("bad: [unterminated"); assertNull(MaskLoader.resolveMask(helmet));
        custom("masks: [vanilla.LEATHER_HELMET]"); assertNotNull(MaskLoader.resolveMask(helmet));
        new MaskLoader().load(directory.resolve("missing.yml").toFile()); assertNotNull(MaskLoader.resolveMask(helmet));
    }

    @Test void mythicSkinTagRecognizesMasksEvenWhenExternalItemApisAreUnavailable() throws Exception {
        bundled("masks: [ia.costumes:hood]");
        when(items.getChecker().checkItemWithPath(helmet, "ia.costumes:hood")).thenThrow(new IllegalStateException("ItemsAdder reloading"));
        assertNull(MaskLoader.resolveMask(helmet));
        NBTItem nbt = mock(NBTItem.class); nbtApi.when(() -> NBTItem.get(helmet)).thenReturn(nbt);
        assertNull(MaskLoader.resolveMask(helmet)); when(nbt.hasTag("ia")).thenReturn(true);
        when(nbt.getString("ia")).thenReturn("costumes.other"); assertNull(MaskLoader.resolveMask(helmet));
        when(nbt.getString("ia")).thenReturn("COSTUMES.HOOD"); assertEquals("ia.costumes:hood", MaskLoader.resolveMask(helmet).getItem());
        nbtApi.when(() -> NBTItem.get(helmet)).thenThrow(new IllegalStateException("MythicLib unavailable")); assertNull(MaskLoader.resolveMask(helmet));
    }

    @Test void resolvingAnItemBeforeAnyConfigurationIsLoadedReturnsNoMask() throws Exception {
        for (String name : List.of("bundledFile", "customFile")) {
            var field = MaskLoader.class.getDeclaredField(name); field.setAccessible(true); field.set(null, null);
        }
        var stamp = MaskLoader.class.getDeclaredField("customStamp"); stamp.setAccessible(true); stamp.setLong(null, Long.MIN_VALUE);
        assertNull(MaskLoader.resolveMask(helmet));
    }

    @Test void bareRelativeConfigurationCanBeAbsentWithoutFailure() {
        new MaskLoader().load(new File("absent-mask-" + UUID.randomUUID() + ".yml"));
        assertNull(MaskLoader.resolveMask(helmet));
    }
}
