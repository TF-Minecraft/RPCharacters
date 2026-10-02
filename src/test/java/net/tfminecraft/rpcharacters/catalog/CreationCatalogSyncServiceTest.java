package net.tfminecraft.rpcharacters.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.kit.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreAttributeHelper;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.*;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.races.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class CreationCatalogSyncServiceTest {
    @TempDir Path directory;
    RuntimeTestState state;
    RPCharacters plugin;
    MMOCore previousCore;
    ClassManager classes;
    Logger logger;
    Map<String, KitDefinition> kits;
    List<EditableKitPreviewBuilder.Row> previews;
    MockedStatic<KitLoader> kitLoader;
    MockedStatic<EditableKitPreviewBuilder> previewBuilder;
    MockedStatic<MmoCoreClassGuiHelper> classDisplay;
    MockedStatic<MmoCoreAttributeHelper> attributes;
    MockedStatic<ProstheticLoader> prosthetics;
    MockedStatic<ProvinceSystemClient> api;
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJ1sAAAAASUVORK5CYII=");

    @BeforeEach void setup() throws Exception {
        MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, StageLoader.class, RaceLoader.class, TraitLoader.class);
        plugin = mock(RPCharacters.class); logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        RPCharacters.plugin = plugin; previousCore = MMOCore.plugin;
        MMOCore.plugin = mock(MMOCore.class); classes = mock(ClassManager.class);
        Field field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, classes);
        when(classes.getAll()).thenReturn(List.of());
        classDisplay = mockStatic(MmoCoreClassGuiHelper.class);
        classDisplay.when(() -> MmoCoreClassGuiHelper.isClassDisplayed(any(PlayerClass.class))).thenReturn(true);
        attributes = mockStatic(MmoCoreAttributeHelper.class);
        attributes.when(() -> MmoCoreAttributeHelper.exists(anyString())).thenReturn(true);
        StageLoader.oList = new ArrayList<>(); RaceLoader.oList = new ArrayList<>(); TraitLoader.oList = new ArrayList<>();
        Cache.attributes = new ArrayList<>(List.of("strength", "dexterity")); Cache.professions = new ArrayList<>();
        Cache.permissionGroupDefaults = new HashMap<>(); Cache.permissionGroups = new ArrayList<>();
        Cache.webCreatorAccessByRealm = new LinkedHashMap<>(); Cache.maxCharacterSlots = 10;
        Cache.evilMinAccountAgeHours = 24; Cache.calendarEraSuffix = "AE";
        kits = new LinkedHashMap<>(); kitLoader = mockStatic(KitLoader.class); kitLoader.when(KitLoader::getKits).thenAnswer(call -> kits);
        previews = new ArrayList<>(); previewBuilder = mockStatic(EditableKitPreviewBuilder.class);
        previewBuilder.when(EditableKitPreviewBuilder::build).thenAnswer(call -> previews);
        prosthetics = mockStatic(ProstheticLoader.class);
        api = mockStatic(ProvinceSystemClient.class);
        api.when(() -> ProvinceSystemClient.pushCreationCatalog(anyString())).thenReturn(ProvinceSystemClient.CatalogPushResult.success(1, 2, 3, 4, "now"));
        api.when(() -> ProvinceSystemClient.putKitSkin(anyString(), any(byte[].class))).thenReturn(ProvinceSystemClient.SimpleResult.success("ok"));
        api.when(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(any(byte[].class))).thenReturn(ProvinceSystemClient.SimpleResult.success("ok"));
    }

    @AfterEach void restore() {
        if (api != null) api.close();
        if (prosthetics != null) prosthetics.close();
        if (previewBuilder != null) previewBuilder.close();
        if (kitLoader != null) kitLoader.close();
        if (attributes != null) attributes.close();
        if (classDisplay != null) classDisplay.close();
        MMOCore.plugin = previousCore; state.close(); MockBukkit.unmock();
    }

    JSONObject payload() throws Exception { return (JSONObject) new JSONParser().parse(CreationCatalogSyncService.buildPayloadJson()); }
    static JSONObject object(JSONObject parent, String key) { return (JSONObject) parent.get(key); }
    static List<JSONObject> rows(JSONObject parent, String key) { return ((JSONArray) parent.get(key)).stream().map(JSONObject.class::cast).toList(); }
    Stage stage(String id, String yaml) throws Exception {
        var config = new YamlConfiguration(); config.loadFromString(yaml); Stage stage = Stage.create(id, config);
        StageLoader.oList.add(stage); return stage;
    }
    static YamlConfiguration config(String yaml) throws Exception {
        var config = new YamlConfiguration(); config.loadFromString(yaml); return config;
    }
    Path png(String stem) throws Exception { return Files.write(Files.createDirectories(directory.resolve("assets")).resolve(stem + ".png"), PNG); }

    @Test void stagesPublishRealConfiguredTypesOrderGatesDependenciesAndPointBuy() throws Exception {
        StageLoader.oList.add(null); StageLoader.oList.add(new Stage());
        stage("intro", """
            type: info
            repeat: false
            auto-next: true
            platform: web
            lock-time: 2h
            require-account-age-hours-min: 4
            require-account-age-hours-max: 40
            interval: 20
            messages: ['Wait {hours} hours', 'Second']
            web-messages: ['Web {hours} hours']
            dependency: {type: trait, mode: all, depends-on: [brave, strong]}
            """);
        stage("name", "type: setter\ntarget: name\nmessage: Enter\n");
        stage("traits", "type: selection\ntarget: trait\nkey: merits\npoints: 4\nmin-select: 1\nmax-select: 3\nfilter: permanent-only\n");
        stage("attributes", "type: attributes\nkey: attributes\npoints: 9\nmax-rank: 3\nattributes: [strength, dexterity]\n");
        stage("clues", "type: clue\nmessage: Clue\n");
        stage("questions", "type: questions\nquestions: {}\n");
        stage("summary", "type: summary\nentries: {name: '{name}', race: '{race}'}\n");
        stage("wardrobe", "type: wardrobe\nweb-messages: ['Wardrobe {hours}']\n");
        Stage unknown = new Stage(); unknown.setId("extension"); StageLoader.oList.add(unknown);
        JSONObject root = payload(); var stages = rows(root, "stages");
        assertEquals(List.of("info", "setter", "selection", "attributes", "clue", "questions", "summary", "wardrobe", "unknown"), stages.stream().map(o -> o.get("type")).toList());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), stages.stream().map(o -> o.get("order")).toList());
        JSONObject intro = stages.getFirst(); assertEquals(false, intro.get("repeat")); assertEquals(true, intro.get("auto_next"));
        assertEquals("web", intro.get("platform")); assertEquals(7200000L, intro.get("lock_time_ms"));
        assertEquals(4L, intro.get("require_account_age_hours_min")); assertEquals(40L, intro.get("require_account_age_hours_max"));
        assertEquals(20L, intro.get("interval")); assertEquals(List.of("Wait 24 hours", "Second"), intro.get("messages"));
        assertEquals(List.of("Web 24 hours"), intro.get("web_messages"));
        assertEquals(Map.of("type", "trait", "mode", "all", "depends_on", List.of("brave", "strong")), intro.get("dependency"));
        assertEquals("name", stages.get(1).get("target")); assertEquals("merits", stages.get(2).get("key"));
        assertEquals(1L, stages.get(2).get("min_select")); assertEquals(3L, stages.get(2).get("max_select"));
        assertEquals(4L, stages.get(2).get("points")); assertEquals("permanent-only", stages.get(2).get("filter"));
        assertEquals(Map.of("name", "{name}", "race", "{race}"), stages.get(6).get("entries"));
        assertEquals(List.of("Wardrobe 24"), stages.get(7).get("web_messages"));
        JSONObject buy = object(root, "attribute_point_buy"); assertEquals(9L, buy.get("pool")); assertEquals(3L, buy.get("max_rank"));
        assertEquals(List.of(1L, 2L, 4L), buy.get("cost_for_rank")); assertEquals(List.of("strength", "dexterity"), buy.get("attributes"));
        assertEquals(Map.of("strength", "str", "dexterity", "dex"), buy.get("abbreviations"));
        assertEquals("{abbr}{rank}", buy.get("trait_id_pattern"));
    }

    @Test void optionalStageFieldsNullListsAndJsonEscapesRemainValid() throws Exception {
        var info = mock(InfoStage.class); when(info.getId()).thenReturn("line\n\r\t\b\"\\"); when(info.getMessages()).thenReturn(null);
        var dep = mock(Dependency.class); when(dep.getDependencies()).thenReturn(null);
        when(info.hasDependency()).thenReturn(true); when(info.getDependency()).thenReturn(dep);
        StageLoader.oList.add(info);
        stage("wardrobe", "type: wardrobe\n"); stage("selection", "type: selection\ntarget: extension\nfilter: ' '\n");
        var summary = (SummaryStage) stage("summary", "type: summary\nentries: {}\n"); summary.getEntries().put(null, null);
        var attributes = mock(AttributesStage.class); when(attributes.getId()).thenReturn("attrs"); when(attributes.getPool()).thenReturn(6);
        when(attributes.getMaxRank()).thenReturn(2); when(attributes.getAttributes()).thenReturn(List.of()); StageLoader.oList.add(attributes);
        var root = payload(); var stages = rows(root, "stages"); assertEquals("line\n\r\t\b\"\\", stages.getFirst().get("id"));
        assertEquals(List.of(), stages.getFirst().get("messages")); assertEquals(List.of(), object(stages.getFirst(), "dependency").get("depends_on"));
        assertEquals("", stages.getFirst().get("platform")); assertFalse(stages.get(1).containsKey("web_messages"));
        assertFalse(stages.get(2).containsKey("key")); assertFalse(stages.get(2).containsKey("points")); assertFalse(stages.get(2).containsKey("filter"));
        assertEquals(Map.of("", ""), stages.get(3).get("entries"));
        assertEquals(List.of("strength", "dexterity"), object(root, "attribute_point_buy").get("attributes"));
    }

    @Test void racesTraitsAndModifiersPublishEffectsAndExcludeTemporaryInjuries() throws Exception {
        Race race = new Race("human", config("name: '&aHuman'\nkey: race\nshown: true\nage-max: 90\ndescription: ['&7People']\nattribute-modifiers: ['strength.2', 'dexterity.0']\n"));
        RaceLoader.oList.addAll(Arrays.asList(null, mock(Race.class), race));
        Race fallback = mock(Race.class); when(fallback.getId()).thenReturn("fallback"); when(fallback.getDesc()).thenReturn(null); RaceLoader.oList.add(fallback);
        AttributeData modifiers = race.getRaceData().getAttributeData(); modifiers.getModifiers().add(null); modifiers.getModifiers().add(new AttributeModifier("wisdom", -1));
        modifiers.getExperienceModifiers().addAll(Arrays.asList(null, new ExperienceModifier("mining", "Miner", 0),
            new ExperienceModifier("fishing", "§aFisher", 2), new ExperienceModifier("farming", "Farmer", -1)));
        TraitLoader.oList.addAll(Arrays.asList(null, mock(Trait.class)));
        Trait noData = mock(Trait.class); when(noData.getId()).thenReturn("no-data"); TraitLoader.oList.add(noData);
        Trait arm = trait("arm", "prosthetic", "required-account-playtime: 2\nicon: IRON_INGOT\nfuel-template: coal\nblock-offhand: true\ndependency: {type: race, mode: one-or-more, depends-on: [human]}\n");
        arm.getTraitData().getExclusive().addAll(Arrays.asList(null, "one", "two"));
        prosthetics.when(() -> ProstheticLoader.getReplacementForProsthetic("arm")).thenReturn(new ProstheticReplacement("lost_arm", Map.of("arm", "v.IRON_INGOT")));
        trait("leg", "prosthetic", "required-account-playtime: 1.5\n");
        trait("permanent", "injury", ""); trait("temporary", "injury", "duration: 1h\n");
        Trait nullKey = mock(Trait.class); TraitData data = mock(TraitData.class); when(nullKey.getId()).thenReturn("untyped");
        when(nullKey.getTraitData()).thenReturn(data); when(nullKey.getDesc()).thenReturn(List.of()); TraitLoader.oList.add(nullKey);
        JSONObject root = payload(); var races = rows(root, "races"); assertEquals(2, races.size());
        assertEquals("Human", races.getFirst().get("name")); assertEquals("race", races.getFirst().get("key")); assertEquals(90L, races.getFirst().get("age_max"));
        assertEquals(List.of("People"), races.getFirst().get("description"));
        assertEquals(List.of(Map.of("type", "strength", "amount", 2L), Map.of("type", "wisdom", "amount", -1L)), races.getFirst().get("attribute_modifiers"));
        assertEquals(List.of(Map.of("profession", "fishing", "alias", "Fisher", "amount", 2L), Map.of("profession", "farming", "alias", "Farmer", "amount", -1L)), races.getFirst().get("experience_modifiers"));
        assertEquals("race", races.get(1).get("key")); assertEquals("", races.get(1).get("name")); assertEquals(List.of(), races.get(1).get("description"));
        var traits = rows(root, "traits"); assertEquals(List.of("arm", "leg", "permanent", "untyped"), traits.stream().map(o -> o.get("id")).toList());
        JSONObject first = traits.getFirst(); assertEquals("lost_arm", first.get("replaces_injury")); assertEquals("IRON_INGOT", first.get("icon"));
        assertEquals(true, first.get("fuel_disclaimer")); assertEquals(false, first.get("has_duration")); assertEquals(2L, first.get("required_account_playtime_hours"));
        assertEquals(List.of("one", "two"), first.get("mutually_exclusive")); assertEquals("race", object(first, "dependency").get("type"));
        assertEquals(List.of("Detail", "Cannot use the offhand or two-handed items."), first.get("description"));
        assertEquals(1.5, traits.get(1).get("required_account_playtime_hours")); assertFalse(traits.get(1).containsKey("replaces_injury"));
        assertFalse(traits.get(3).containsKey("key")); assertFalse(traits.get(3).containsKey("attribute_modifiers"));
    }

    Trait trait(String id, String key, String extra) throws Exception {
        Trait trait = new Trait(id, config("name: '&a" + id + "'\nkey: " + key + "\ncost: 2\ndescription: ['&7Detail']\n" + extra));
        TraitLoader.oList.add(trait); return trait;
    }

    @Test void classesAreFilteredSortedAndUnavailableIntegrationsDoNotPreventCatalogs() throws Exception {
        PlayerClass high = mock(PlayerClass.class), low = mock(PlayerClass.class), hidden = mock(PlayerClass.class);
        when(high.getId()).thenReturn("high"); when(high.getName()).thenReturn("§aHigh"); when(high.getDisplayOrder()).thenReturn(2);
        when(high.getDescription()).thenReturn(Arrays.asList(null, "§7Lore")); when(high.getAttributeDescription()).thenReturn(List.of("§bStrong"));
        when(low.getId()).thenReturn("low"); when(low.getDisplayOrder()).thenReturn(1);
        when(classes.getAll()).thenReturn(List.of(high, hidden, low));
        classDisplay.when(() -> MmoCoreClassGuiHelper.isClassDisplayed(hidden)).thenReturn(false);
        var rows = rows(payload(), "classes"); assertEquals(List.of("low", "high"), rows.stream().map(o -> o.get("id")).toList());
        assertEquals("High", rows.get(1).get("name")); assertEquals(List.of("", "Lore"), rows.get(1).get("description"));
        assertEquals(List.of("Strong"), rows.get(1).get("attribute_description"));
        when(classes.getAll()).thenThrow(new IllegalStateException("offline")); assertEquals(List.of(), rows(payload(), "classes"));
        verify(logger).warning(contains("could not read MMOCore classes: offline"));
    }

    @Test void aClassFailureMidSerializationDoesNotCorruptCatalogJson() {
        PlayerClass broken = mock(PlayerClass.class); when(broken.getId()).thenReturn("broken");
        when(broken.getName()).thenThrow(new IllegalStateException("class disappeared")); when(classes.getAll()).thenReturn(List.of(broken));
        JSONObject root = assertDoesNotThrow(this::payload);
        assertEquals(List.of(), rows(root, "classes"));
        PlayerClass good = mock(PlayerClass.class); when(good.getId()).thenReturn("good");
        when(classes.getAll()).thenReturn(List.of(good, broken));
        String json = CreationCatalogSyncService.buildPayloadJson();
        assertFalse(json.contains(",]"), "A rejected row must not leave a dangling comma");
        JSONObject preserved = assertDoesNotThrow(() -> (JSONObject) new JSONParser().parse(json));
        assertEquals(List.of("good"), rows(preserved, "classes").stream().map(row -> row.get("id")).toList());
    }

    @Test void validationSlotLimitsAndRealmAccessReflectRuntimeConfiguration() throws Exception {
        Cache.personaDisplayNameMinLength = 2; Cache.personaDisplayNameMaxLength = 18; Cache.calendarAgeMinimum = 16;
        Cache.calendarYearOffset = 100; Cache.calendarEraSuffix = "Era\n\"X\""; Cache.characterDescriptionMinLength = 20; Cache.characterDescriptionMaxLength = 200;
        Cache.defaultCluesRequired = 2; Cache.evilCluesRequired = 4; Cache.evilMinAccountAgeHours = 48;
        Cache.clueMinLength = 5; Cache.clueMaxLength = 40; Cache.maxClues = 6; Cache.maxCharacterSlots = 8;
        Cache.permissionGroupDefaults.putAll(Map.of("max-alive-characters", 2, "name-colour-stops", 1, "wardrobe-skin-slots", 3));
        Cache.permissionGroups.addAll(Arrays.asList(null,
            new PermissionGroupDefinition("gold", "rank.gold", "Gold", 2, true, Map.of("max-alive-characters", 5)),
            new PermissionGroupDefinition("hidden", "rank.hidden", "Hidden", 3, false, Map.of())));
        Cache.webCreatorAccessByRealm.put(null, new WebCreatorRealmAccess(0, "")); Cache.webCreatorAccessByRealm.put("missing", null);
        Cache.webCreatorAccessByRealm.put("main", new WebCreatorRealmAccess(0, "")); Cache.webCreatorAccessByRealm.put("dev", new WebCreatorRealmAccess(2, "gold"));
        JSONObject root = payload(); JSONObject validation = object(root, "validation");
        assertEquals(Map.of("min_length", 2L, "max_length", 18L), validation.get("name"));
        assertEquals(Map.of("minimum", 16L), validation.get("age"));
        assertEquals(Map.of("year_offset", 100L, "era_suffix", "Era\n\"X\""), validation.get("calendar"));
        assertEquals(Map.of("min_length", 20L, "max_length", 200L), validation.get("description"));
        assertEquals(Map.of("default_required", 2L, "evil_required", 4L, "evil_min_account_age_hours", 48L, "min_length", 5L, "max_length", 40L, "max_clues", 6L), validation.get("clues"));
        JSONObject limits = object(root, "slot_limits"); assertEquals(8L, limits.get("hard_cap"));
        assertEquals(Map.of("max_alive_characters", 2L, "name_colour_stops", 1L, "wardrobe_skin_slots", 3L), limits.get("defaults"));
        assertEquals(5L, rows(limits, "groups").getFirst().get("max_alive_characters"));
        assertEquals(false, rows(limits, "groups").get(1).get("visible"));
        assertEquals(Map.of("main", Map.of("min_tier", 0L), "dev", Map.of("min_tier", 2L, "min_group_id", "gold")), object(root, "web_creator_access").get("by_realm"));
        assertEquals(12L, object(root, "attribute_point_buy").get("pool")); assertEquals(4L, object(root, "attribute_point_buy").get("max_rank"));
    }

    @Test void kitAndPreviewRowsIncludeOptionalFieldsAndSkipInvalidDefinitions() throws Exception {
        KitItemDefinition editable = new KitItemDefinition("m.tool.pick", 2, new KitEditableSpec("pick", "signed", "tools", "2d", "3d"), "Pick");
        kits.put("null", null); kits.put("empty", new KitDefinition("", "", 0, false, List.of()));
        kits.put("starter", new KitDefinition("starter", "Starter", 24, true, Arrays.asList(null, new KitItemDefinition(null, 1, null), new KitItemDefinition(" ", 1, null), editable, new KitItemDefinition("v.STONE", 3, null))));
        kits.put("extra", new KitDefinition("extra", "Extra", 0, false, List.of()));
        previews.add(new EditableKitPreviewBuilder.Row("starter", "pick", "m.tool.pick", 2, "pick", "signed", "tools", "2d", "3d",
            new EditableKitPreviewBuilder.Preview("Pick", List.of("Lore", "More"), "IRON_PICKAXE", 4)));
        previews.add(new EditableKitPreviewBuilder.Row("", "plain", "v.STONE", 1, "plain", null, null, null, null,
            new EditableKitPreviewBuilder.Preview("Stone", List.of(), "STONE", null)));
        previews.add(new EditableKitPreviewBuilder.Row(null, "none", "v.DIRT", 1, null, null, null, null, null, null));
        JSONObject root = payload(); var nested = rows(root, "kits"); assertEquals(2, nested.size());
        assertEquals("Starter", nested.getFirst().get("display_name")); assertEquals(24L, nested.getFirst().get("cooldown_hours")); assertEquals(true, nested.getFirst().get("once_per_character"));
        assertEquals(List.of(Map.of("path", "m.tool.pick", "amount", 2L, "editable", true, "display_name", "Pick"), Map.of("path", "v.STONE", "amount", 3L)), nested.getFirst().get("items"));
        var rows = rows(root, "editable_kit"); assertEquals(3, rows.size()); JSONObject row = rows.getFirst();
        assertEquals("starter", row.get("kit_id")); assertEquals("signed", row.get("skin_png_signed")); assertEquals("3d", row.get("3d_template"));
        assertEquals(Map.of("display_name", "Pick", "lore", List.of("Lore", "More"), "material", "IRON_PICKAXE", "custom_model_data", 4L), row.get("preview"));
        assertFalse(rows.get(1).containsKey("kit_id")); assertFalse(rows.get(1).containsKey("skin_png_signed")); assertFalse(rows.get(1).containsKey("3d_template"));
        assertFalse(object(rows.get(1), "preview").containsKey("custom_model_data")); assertFalse(rows.get(2).containsKey("preview"));
    }

    @Test void editableSkinStemsAreDistinctNormalizedAndCannotEscapeAssets() {
        List<KitItemDefinition> items = new ArrayList<>(); items.add(null); items.add(new KitItemDefinition("v.STONE", 1, null));
        KitItemDefinition inconsistent = mock(KitItemDefinition.class); when(inconsistent.isEditable()).thenReturn(true); items.add(inconsistent);
        for (String raw : Arrays.asList(null, " ", ".PNG", "../bad", "a/b", "a\\b", " good.PNG ", "good")) {
            KitEditableSpec spec = mock(KitEditableSpec.class); when(spec.getSkinPng()).thenReturn(raw); when(spec.getSkinPngSigned()).thenReturn("signed.png");
            items.add(new KitItemDefinition("v.PAPER", 1, spec));
        }
        kits.put("null", null); kits.put("starter", new KitDefinition("starter", "Starter", 0, false, items));
        assertEquals(new LinkedHashSet<>(List.of("signed", "good")), CreationCatalogSyncService.collectEditableSkinPngStems());
    }

    @Test void realPngUploadsReportSuccessMissingRemoteFailureAndExceptions() throws Exception {
        png("good"); png("failed"); png("throws");
        api.when(() -> ProvinceSystemClient.putKitSkin(eq("failed"), any(byte[].class))).thenReturn(ProvinceSystemClient.SimpleResult.fail("offline"));
        api.when(() -> ProvinceSystemClient.putKitSkin(eq("throws"), any(byte[].class))).thenThrow(new IllegalStateException("lost connection"));
        CreationCatalogSyncService.syncKitSkins(null, logger, Set.of("good"));
        CreationCatalogSyncService.syncKitSkins(plugin, null, Set.of("good"));
        CreationCatalogSyncService.syncKitSkins(plugin, logger, null); CreationCatalogSyncService.syncKitSkins(plugin, logger, Set.of());
        api.verifyNoInteractions();
        CreationCatalogSyncService.syncKitSkins(plugin, logger, new LinkedHashSet<>(List.of("good", "missing", "failed", "throws")));
        api.verify(() -> ProvinceSystemClient.putKitSkin(eq("good"), aryEq(PNG)));
        verify(logger).warning(contains("kit skin missing: assets/missing.png")); verify(logger).warning(contains("kit skin upload failed for failed: offline"));
        verify(logger).warning(contains("kit skin read/upload failed for throws: lost connection")); verify(logger).info(contains("ok=1 missing=1 failed=2"));
    }

    @Test void maskedTemplateUploadsAreFailSoftForMissingFilesAndRemoteFailures() throws Exception {
        CreationCatalogSyncService.syncMaskedTemplate(null, logger); CreationCatalogSyncService.syncMaskedTemplate(plugin, null);
        api.verifyNoInteractions(); CreationCatalogSyncService.syncMaskedTemplate(plugin, logger);
        verify(logger).warning(contains("masked template missing")); png("masked");
        CreationCatalogSyncService.syncMaskedTemplate(plugin, logger);
        api.verify(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(aryEq(PNG))); verify(logger).info(contains("masked template synced"));
        api.when(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(any(byte[].class))).thenReturn(ProvinceSystemClient.SimpleResult.fail("offline"));
        CreationCatalogSyncService.syncMaskedTemplate(plugin, logger); verify(logger).warning(contains("masked template upload failed: offline"));
        api.when(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(any(byte[].class))).thenThrow(new IllegalStateException("connection"));
        CreationCatalogSyncService.syncMaskedTemplate(plugin, logger); verify(logger).warning(contains("masked template read/upload failed: connection"));
    }

    @Test void synchronousPushesSyncAssetsOnlyAfterSuccessfulCatalogPublication() throws Exception {
        png("skin"); png("masked"); kits.put("kit", new KitDefinition("kit", "Kit", 0, false,
            List.of(new KitItemDefinition("v.PAPER", 1, new KitEditableSpec("skin", "", "", null)))));
        assertTrue(CreationCatalogSyncService.pushNow().ok); assertTrue(CreationCatalogSyncService.pushJson("custom").ok);
        api.verify(() -> ProvinceSystemClient.pushCreationCatalog("custom"));
        api.verify(() -> ProvinceSystemClient.putKitSkin(eq("skin"), aryEq(PNG)), times(2));
        api.clearInvocations(); RPCharacters.plugin = null;
        assertTrue(CreationCatalogSyncService.pushNow().ok); assertTrue(CreationCatalogSyncService.pushJson("custom").ok);
        api.verify(() -> ProvinceSystemClient.putKitSkin(anyString(), any(byte[].class)), never());
        RPCharacters.plugin = plugin;
        api.when(() -> ProvinceSystemClient.pushCreationCatalog(anyString())).thenReturn(ProvinceSystemClient.CatalogPushResult.fail("offline"));
        assertFalse(CreationCatalogSyncService.pushNow().ok); assertFalse(CreationCatalogSyncService.pushJson("custom").ok);
        api.verify(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(any(byte[].class)), never());
    }

    @Test void asyncPushBuildsOnMainThreadAndUploadsAfterSuccess() throws Exception {
        BukkitScheduler scheduler = mock(BukkitScheduler.class); List<Runnable> main = new ArrayList<>(), background = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { main.add(call.getArgument(1)); return null; });
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> { background.add(call.getArgument(1)); return null; });
        png("masked");
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            CreationCatalogSyncService.pushAsync(null); RPCharacters.plugin = null; CreationCatalogSyncService.pushAsyncFromPlugin();
            verifyNoInteractions(scheduler); RPCharacters.plugin = plugin; CreationCatalogSyncService.pushAsyncFromPlugin();
            assertEquals(1, main.size()); assertTrue(background.isEmpty()); api.verifyNoInteractions();
            main.removeFirst().run(); assertEquals(1, background.size()); api.verifyNoInteractions();
            background.removeFirst().run(); api.verify(() -> ProvinceSystemClient.pushCreationCatalog(anyString()));
            verify(logger).info(contains("synced to ProvinceSystem: stages=1 races=2 traits=3 classes=4"));
            api.verify(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(aryEq(PNG)));
            api.clearInvocations(); bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            api.when(() -> ProvinceSystemClient.pushCreationCatalog(anyString())).thenReturn(ProvinceSystemClient.CatalogPushResult.fail("offline"));
            CreationCatalogSyncService.pushAsync(plugin); assertTrue(main.isEmpty()); assertEquals(1, background.size());
            background.removeFirst().run(); verify(logger).warning(contains("sync failed: offline"));
            api.verify(() -> ProvinceSystemClient.putWardrobeMaskedTemplate(any(byte[].class)), never());
        }
    }
}
