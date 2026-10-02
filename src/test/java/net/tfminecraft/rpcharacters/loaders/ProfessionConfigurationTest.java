package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.Indyuce.mmocore.MMOCore;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.professions.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;
import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class ProfessionConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    MockedStatic<TLibs> tlibs;
    ItemAPI items;
    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, MMOCore.class, ProfessionRegistry.class);
        RPCharacters.plugin = mock(RPCharacters.class); when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("profession-test"));
        MMOCore.plugin = mock(MMOCore.class); items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        tlibs = mockStatic(TLibs.class); tlibs.when(TLibs::getItemAPI).thenReturn(items); ProfessionRegistry.clear();
    }
    @AfterEach void restore() { tlibs.close(); state.close(); MockBukkit.unmock(); }
    File yaml(String name, String text) throws Exception { return Files.writeString(directory.resolve(name), text).toFile(); }

    @Test void professionsReloadDefinitionsAndUpgradesWithoutLeavingStaleRows() throws Exception {
        File file = yaml("mining.yml", """
            name: Mining
            item: {material: IRON_PICKAXE, name: '&eMining'}
            upgrades:
              scalar: ignored
              iron: {cost: 3, type: permission, item: {material: IRON_INGOT}, requires: [basic], unlocks: [mine.iron]}
              IRON: {cost: 4}
            """);
        new ProfessionLoader().load(file); var profession = ProfessionRegistry.getProfession("MINING");
        assertEquals("mining", profession.getId()); assertEquals("Mining", profession.getName());
        assertEquals(Material.IRON_PICKAXE, profession.getMenuItem().getType()); assertEquals("§eMining", profession.getMenuItem().getItemMeta().getDisplayName());
        assertEquals(2, profession.getUpgrades().size()); var upgrade = ProfessionRegistry.getUpgrade("iron");
        assertEquals("iron", upgrade.getId()); assertEquals("mining", upgrade.getProfessionId()); assertEquals(3, upgrade.getCost());
        assertEquals("permission", upgrade.getType()); assertEquals(List.of("basic"), upgrade.getRequirements()); assertEquals(List.of("mine.iron"), upgrade.getUnlocks());
        assertEquals(Material.IRON_INGOT, upgrade.getMenuItem().getType());
        assertThrows(UnsupportedOperationException.class, () -> profession.getUpgrades().clear());
        assertNull(ProfessionRegistry.getProfession(null)); assertNull(ProfessionRegistry.getProfession("unknown"));
        assertNull(ProfessionRegistry.getUpgrade(null)); assertNull(ProfessionRegistry.getUpgrade("unknown"));
        new ProfessionLoader().load(yaml("fishing.yml", "name: Fishing"));
        new ProfessionLoader().load(yaml("mining.yml", "name: Miner\nupgrades: {stone: {cost: 1}}"));
        assertEquals(2, ProfessionRegistry.getProfessions().size()); assertEquals("Miner", ProfessionRegistry.getProfession("mining").getName());
        assertEquals(1, ProfessionRegistry.getUpgrades().size()); assertNull(ProfessionRegistry.getUpgrade("iron"));
        assertThrows(UnsupportedOperationException.class, () -> ProfessionRegistry.getProfessions().clear());
        assertThrows(UnsupportedOperationException.class, () -> ProfessionRegistry.getUpgrades().clear());
        assertTrue(new ProfessionDefinition("empty", "Empty", null, null).getUpgrades().isEmpty());
        var empty = new ProfessionUpgradeDefinition("none", "empty", null, 0, "permission", null, null);
        assertTrue(empty.getRequirements().isEmpty()); assertTrue(empty.getUnlocks().isEmpty()); assertEquals(Material.BARRIER, empty.getMenuItem().getType());
    }

    @Test void folderLoadingSkipsOtherFilesAndFailedReloadCanRecover() throws Exception {
        ProfessionLoader.loadAll(directory.resolve("missing").toFile());
        File plain = yaml("notes.txt", "text"); ProfessionLoader.loadAll(plain); Files.createDirectory(directory.resolve("nested.yml"));
        yaml("a.yml", "name: A"); yaml("broken.yml", "bad: [unterminated"); ProfessionLoader.reload(directory.toFile());
        assertEquals(1, ProfessionRegistry.getProfessions().size()); assertEquals("a", ProfessionRegistry.getProfessions().getFirst().getId());
        File unreadableListing = mock(File.class); when(unreadableListing.exists()).thenReturn(true); when(unreadableListing.isDirectory()).thenReturn(true);
        ProfessionLoader.loadAll(unreadableListing); assertEquals(1, ProfessionRegistry.getProfessions().size());
        ProfessionLoader.reload(directory.resolve("missing").toFile()); assertTrue(ProfessionRegistry.getProfessions().isEmpty()); assertTrue(ProfessionRegistry.getUpgrades().isEmpty());
    }

    @Test void globalProfessionSettingsWireTypesAndBreedingPolicy() throws Exception {
        new ProfessionsGlobalLoader().load(yaml("global.yml", """
            max_spending_points: 80
            perm_context: event
            admin-debug-messages: true
            lock_breeding: [COW]
            types:
              tool: {mmoitem_types: [TOOL, PICKAXE]}
              empty: {}
            """));
        assertEquals(80, Cache.professionMaxSpendingPoints); assertEquals("event", Cache.professionPermContext); assertTrue(Cache.professionAdminDebugMessages);
        assertEquals(List.of("COW"), Cache.professionLockedBreeding); assertNotNull(Cache.professionBreedingExperience);
        assertEquals(2, Cache.professionItemTypes.size()); assertEquals(Cache.professionItemTypes, ProfessionRegistry.getItemTypes());
        assertThrows(UnsupportedOperationException.class, () -> ProfessionRegistry.getItemTypes().clear());
        new ProfessionsGlobalLoader().load(yaml("global.yml", "{}")); assertEquals(40, Cache.professionMaxSpendingPoints);
        assertTrue(Cache.professionItemTypes.isEmpty()); assertFalse(Cache.professionAdminDebugMessages);
        new ProfessionsGlobalLoader().load(yaml("global.yml", "bad: [unterminated")); assertEquals(40, Cache.professionMaxSpendingPoints);
    }

    @Test void menuItemsSnapshotAndCloneInputWhileApplyingMetadata() throws Exception {
        ItemStack source = new ItemStack(Material.BOOK); when(items.getCreator().getItemFromPath("v.book")).thenReturn(source);
        var config = new YamlConfiguration(); config.loadFromString("""
            item: vanilla.BOOK
            material: PAPER
            name: '&aUpgrade'
            lore: ['&7Details']
            enchants: [unbreaking.2, nonsense, unknown_enchant.1]
            hide_enchants: true
            """);
        var spec = ProfessionItemFactory.snapshot(config); config.set("name", "Changed");
        ItemStack built = ProfessionItemFactory.build(spec); assertNotSame(source, built); assertEquals(Material.BOOK, built.getType());
        var meta = built.getItemMeta(); assertEquals("§aUpgrade", meta.getDisplayName()); assertEquals(List.of("§7Details"), meta.getLore());
        assertEquals(2, meta.getEnchantLevel(Enchantment.UNBREAKING));
        assertTrue(meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)); assertFalse(source.getItemMeta().hasDisplayName());
        assertEquals(Material.BARRIER, ProfessionItemFactory.build(null).getType());
        assertEquals(Material.BARRIER, ProfessionItemFactory.fromNode(null).getType());
        assertEquals(Material.BARRIER, ProfessionItemFactory.fromNode(42).getType());
        assertEquals(Material.BOOK, ProfessionItemFactory.fromNode("vanilla.BOOK").getType());
        config = new YamlConfiguration(); config.set("path", "missing.item"); config.set("material", "PAPER");
        when(items.getCreator().getItemFromPath("missing.item")).thenReturn(null);
        assertEquals(Material.PAPER, ProfessionItemFactory.fromNode(config).getType());
        config.set("material", null); assertEquals(Material.BARRIER, ProfessionItemFactory.fromNode(config).getType());
        when(items.getCreator().getItemFromPath("missing.item")).thenReturn(new ItemStack(Material.AIR)); assertEquals(Material.BARRIER, ProfessionItemFactory.fromNode(config).getType());
        when(items.getCreator().getItemFromPath("missing.item")).thenThrow(new IllegalStateException("unavailable")); assertEquals(Material.BARRIER, ProfessionItemFactory.fromNode(config).getType());
        config.set("path", " "); config.set("material", "invalid"); assertEquals(Material.BARRIER, ProfessionItemFactory.fromNode(config).getType());
        config.set("material", "AIR"); assertEquals(Material.AIR, ProfessionItemFactory.fromNode(config).getType());
        config.set("material", "BOOK"); config.set("name", ""); config.set("lore", List.of(""));
        assertEquals(List.of(""), ProfessionItemFactory.fromNode(config).getItemMeta().getLore());
        assertFalse(ProfessionItemSpec.empty().hasLore());
        assertTrue(new ProfessionItemSpec(null, null, null, null, null, false, List.of("Lore")).hasLore());
    }

    @Test void modelDataUsesTheModernComponentAndClearsLegacyExtraFields() throws Exception {
        var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        var component = mock(org.bukkit.inventory.meta.components.CustomModelDataComponent.class);
        when(meta.getCustomModelDataComponent()).thenReturn(component);
        ItemStack source = mock(ItemStack.class), built = mock(ItemStack.class);
        when(source.getType()).thenReturn(Material.BOOK); when(source.clone()).thenReturn(built); when(built.getItemMeta()).thenReturn(meta);
        when(items.getCreator().getItemFromPath("custom.book")).thenReturn(source);
        var config = new YamlConfiguration(); config.loadFromString("item: custom.book\nmodel_data: 25");
        assertSame(built, ProfessionItemFactory.fromNode(config));
        verify(component).setFloats(List.of(25f)); verify(component).setFlags(List.of()); verify(component).setStrings(List.of()); verify(component).setColors(List.of());
        verify(meta).setCustomModelDataComponent(component); verify(built).setItemMeta(meta);
        when(component.getFloats()).thenReturn(List.of(25f)); assertTrue(net.tfminecraft.rpcharacters.util.LegacyModelData.has(meta));
        assertEquals(25, net.tfminecraft.rpcharacters.util.LegacyModelData.get(meta));
        when(component.getFloats()).thenReturn(List.of()); assertFalse(net.tfminecraft.rpcharacters.util.LegacyModelData.has(meta));
        assertThrows(IllegalStateException.class, () -> net.tfminecraft.rpcharacters.util.LegacyModelData.get(meta));
        net.tfminecraft.rpcharacters.util.LegacyModelData.set(meta, null); verify(meta).setCustomModelDataComponent(null);
    }

    @Test void materialAndVanillaPathsAreIndependentOfHostLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); var config = new YamlConfiguration(); config.set("material", "iron_ingot");
        assertEquals(Material.IRON_INGOT, ProfessionItemFactory.fromNode(config).getType());
        ItemStack item = new ItemStack(Material.IRON_INGOT); when(items.getCreator().getItemFromPath("v.iron_ingot")).thenReturn(item);
        assertEquals(Material.IRON_INGOT, ProfessionItemFactory.fromNode("VANILLA.IRON_INGOT").getType());
    }

    @Test void malformedEnchantmentLevelsDoNotBreakTheEntireProfessionMenu() throws Exception {
        var config = new YamlConfiguration(); config.loadFromString("material: BOOK\nenchants: [unbreaking.notANumber, efficiency.2]\n");
        ItemStack built = assertDoesNotThrow(() -> ProfessionItemFactory.fromNode(config));
        assertEquals(2, built.getItemMeta().getEnchantLevel(Enchantment.EFFICIENCY)); assertEquals(0, built.getItemMeta().getEnchantLevel(Enchantment.UNBREAKING));
    }
}
