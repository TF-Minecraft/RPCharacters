package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.kit.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class KitConfigurationTest {
    @TempDir Path directory;
    RuntimeTestState state;
    @BeforeEach void setup() {
        state = new RuntimeTestState(RPCharacters.class, KitLoader.class);
        RPCharacters.plugin = mock(RPCharacters.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("kit-config-test"));
    }
    @AfterEach void restore() { state.close(); }
    void write(String name, String content) throws Exception { Files.writeString(directory.resolve(name), content); }

    @Test void preferredCatalogIncludesEditableTemplatesAndValidatedAmounts() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        write("kit.yml", "items: [{path: vanilla.DIRT}]");
        write("kits.yml", """
            kits:
              ignored: scalar
              ' ': {items: []}
              STARTER:
                display-name: Starter Kit
                cooldown-hours: 3
                once-per-character: false
                items:
                  - {path: ' ', amount: 2}
                  - {amount: 2}
                  - {path: vanilla.STONE, amount: 4, display-name: ' Stone '}
                  - {path: vanilla.DIRT, amount: '2'}
                  - {path: vanilla.SAND, amount: invalid}
                  - {path: vanilla.COAL}
                  - {path: vanilla.WOOD, amount: -10}
                  - path: mmo.sword.iron
                    editable: {2d-template: blade, 3d-template: model, skin-png: image, skin-png-signed: cover, base-set: iron}
                  - path: vanilla.BOOK
                    editable: {2d-template: book}
              invalid: {items: [{path: vanilla.BOOK, editable: {skin-png: image}}]}
            """);
        new KitLoader().loadPreferred(directory.toFile());
        assertEquals(Set.of("starter"), KitLoader.kitIds());
        var starter = KitLoader.getKit(" STARTER "); assertEquals("starter", starter.getId());
        assertEquals("Starter Kit", starter.getDisplayName()); assertFalse(starter.isOncePerCharacter());
        assertEquals(List.of(4,2,1,1,1,1,1), starter.getItems().stream().map(KitItemDefinition::getAmount).toList());
        assertEquals("Stone", starter.getItems().getFirst().getDisplayName()); assertEquals(3, KitLoader.getCooldownHours());
        assertEquals(10800000, KitLoader.getCooldownMs()); assertEquals(10800000, KitLoader.getCooldownMs("starter"));
        assertEquals(starter.getItems(), KitLoader.getItems()); assertEquals(0, KitLoader.getCooldownMs("missing"));
        assertNull(KitLoader.getKit(null)); assertNull(KitLoader.getKit(" ")); assertNull(KitLoader.getKit("invalid"));
        var editable = starter.getItems().get(5).getEditable();
        assertEquals("blade", editable.get2dTemplate()); assertEquals("model", editable.get3dTemplate());
        assertEquals("image", editable.getSkinPng()); assertEquals("cover", editable.getSkinPngSigned()); assertEquals("iron", editable.getBaseSet());
        assertNull(starter.getItems().get(6).getEditable().get3dTemplate());
        assertThrows(UnsupportedOperationException.class, () -> KitLoader.getKits().clear());
        assertThrows(UnsupportedOperationException.class, () -> starter.getItems().clear());
    }

    @Test void legacyFallbackAndMissingFilesReplacePriorCatalog() throws Exception {
        write("kit.yml", "cooldown-hours: -10\nitems: [{path: vanilla.STONE}]");
        new KitLoader().loadPreferred(directory.toFile());
        assertEquals("Starter", KitLoader.getKit("starter").getDisplayName()); assertEquals(0, KitLoader.getCooldownMs());
        assertTrue(KitLoader.getKit("starter").isOncePerCharacter()); assertEquals(1, KitLoader.getItems().size());
        write("kit.yml", "items: [{path: vanilla.STONE, editable: {}}]");
        new KitLoader().loadPreferred(directory.toFile()); assertTrue(KitLoader.getKits().isEmpty());
        Files.delete(directory.resolve("kit.yml")); new KitLoader().loadPreferred(directory.toFile());
        assertTrue(KitLoader.getKits().isEmpty()); assertTrue(KitLoader.getItems().isEmpty()); assertEquals(48, KitLoader.getCooldownHours());
        write("kits.yml", "{}"); new KitLoader().loadPreferred(directory.toFile()); assertTrue(KitLoader.getKits().isEmpty());
    }

    @Test void unreadableOrMalformedCatalogDisablesKits() throws Exception {
        new KitLoader().load(directory.resolve("absent.yml").toFile()); assertTrue(KitLoader.getKits().isEmpty());
        write("kits.yml", "bad: [unterminated"); new KitLoader().loadPreferred(directory.toFile()); assertTrue(KitLoader.kitIds().isEmpty());
    }

    @Test void definitionCopiesInputAndExposesOnlyEditableItemKeys() {
        var spec = new KitEditableSpec(null, null, null, null);
        assertEquals("", spec.getSkinPng()); assertEquals("", spec.getSkinPngSigned()); assertEquals("", spec.getBaseSet());
        assertEquals("", spec.get2dTemplate()); assertNull(spec.get3dTemplate());
        var items = new ArrayList<KitItemDefinition>();
        items.add(null); items.add(new KitItemDefinition("vanilla.STONE", 0, null));
        items.add(new KitItemDefinition(null, 1, spec)); items.add(new KitItemDefinition("mmo.sword.iron", 1, spec, " "));
        var definition = new KitDefinition(" IRON ", " ", -1, true, items); items.clear();
        assertEquals("iron", definition.getId()); assertEquals("iron", definition.getDisplayName()); assertEquals(0, definition.getCooldownHours());
        assertEquals(4, definition.getItems().size()); assertEquals(List.of("iron"), definition.editableKitKeys());
        var empty = new KitDefinition(null, null, 1, false, null); assertEquals("", empty.getId()); assertTrue(empty.getItems().isEmpty());
    }
}
