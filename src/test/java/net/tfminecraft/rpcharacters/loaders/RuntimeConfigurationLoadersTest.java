package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.profile.ProfileFormatter;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class RuntimeConfigurationLoadersTest {
    @TempDir Path directory;
    RuntimeTestState state;
    MockedStatic<TLibs> tlibs;
    ItemAPI items;
    Logger logger;

    @BeforeEach void setup() {
        MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class, AttributePointTomeLoader.class,
            SkillPointTomeLoader.class, FuelTemplateLoader.class, MagnifyingGlassLoader.class, PaidChangeService.class);
        RPCharacters.plugin = mock(RPCharacters.class);
        logger = mock(Logger.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(logger);
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        tlibs = mockStatic(TLibs.class);
        tlibs.when(TLibs::getItemAPI).thenReturn(items);
    }
    @AfterEach void restore() { tlibs.close(); state.close(); MockBukkit.unmock(); }
    File yaml(String text) throws Exception { return Files.writeString(directory.resolve("settings.yml"), text).toFile(); }

    @Test void tomesValidateRowsResolveMatchingItemsAndClearOnReload() throws Exception {
        ItemStack book = new ItemStack(Material.BOOK), stone = new ItemStack(Material.STONE);
        when(items.getChecker().checkItemWithPath(book, "vanilla.BOOK")).thenReturn(true);
        String rows = """
            attribute-point-tomes:
              scalar: ignored
              noitem: {attribute-points: 2}
              zero: {item: vanilla.BOOK, attribute-points: 0}
              INSIGHT: {item: vanilla.BOOK, attribute-points: 4}
            skill-point-tomes:
              scalar: ignored
              noitem: {skill-points: 2}
              zero: {item: vanilla.BOOK, skill-points: 0}
              INSIGHT: {item: vanilla.BOOK, skill-points: 3}
            """;
        new AttributePointTomeLoader().load(yaml(rows)); new SkillPointTomeLoader().load(yaml(rows));
        AttributePointTomeDefinition attribute = AttributePointTomeLoader.resolve(book);
        SkillPointTomeDefinition skill = SkillPointTomeLoader.resolve(book);
        assertEquals("INSIGHT", attribute.getId()); assertEquals(4, attribute.getAttributePoints());
        assertEquals("vanilla.BOOK", attribute.getItem());
        assertEquals("INSIGHT", skill.getId()); assertEquals(3, skill.getSkillPoints()); assertEquals("vanilla.BOOK", skill.getItem());
        for (ItemStack absent : Arrays.asList(null, new ItemStack(Material.AIR), stone)) {
            assertNull(AttributePointTomeLoader.resolve(absent)); assertNull(SkillPointTomeLoader.resolve(absent));
        }
        verify(logger, times(4)).warning(anyString());
        new AttributePointTomeLoader().load(yaml("{}")); new SkillPointTomeLoader().load(yaml("{}"));
        assertNull(AttributePointTomeLoader.resolve(book)); assertNull(SkillPointTomeLoader.resolve(book));
    }

    @Test void fuelDefinitionsValidateDurationsAndLookupWithoutExposingMutableMap() throws Exception {
        new FuelTemplateLoader().load(yaml("""
            scalar: ignored
            invalid: {item: vanilla.COAL, amount-per-item: 0}
            COAL: {item: vanilla.COAL, amount-per-item: 12.5, burn-rate: 2, burn-interval: 30s}
            """));
        FuelTemplate fuel = FuelTemplateLoader.getByString("coal");
        assertEquals("COAL", fuel.getId()); assertEquals("vanilla.COAL", fuel.getItem());
        assertEquals(12.5, fuel.getAmountPerItem()); assertEquals(2, fuel.getBurnRate());
        assertEquals(30_000, fuel.getBurnIntervalMs()); assertTrue(fuel.isValid());
        assertSame(fuel, FuelTemplateLoader.getByItem("VANILLA.coal"));
        assertEquals(Map.of("coal", fuel), FuelTemplateLoader.getAll());
        assertThrows(UnsupportedOperationException.class, () -> FuelTemplateLoader.getAll().clear());
        for (String missing : Arrays.asList(null, " ", "missing")) {
            assertNull(FuelTemplateLoader.getByString(missing)); assertNull(FuelTemplateLoader.getByItem(missing));
        }
        ItemStack coal = new ItemStack(Material.COAL);
        when(items.getChecker().checkItemWithPath(coal, "vanilla.COAL")).thenReturn(true);
        assertSame(fuel, FuelTemplateLoader.resolveForItem(coal));
        for (ItemStack missing : Arrays.asList(null, new ItemStack(Material.AIR), new ItemStack(Material.STONE)))
            assertNull(FuelTemplateLoader.resolveForItem(missing));
        var config = new YamlConfiguration(); config.set("item", "vanilla.COAL");
        config.set("amount-per-item", 1); config.set("burn-rate", 1);
        for (String interval : Arrays.asList(null, " ", "garbage", "2d")) {
            config.set("burn-interval", interval);
            FuelTemplate definition = new FuelTemplate("test", config);
            assertEquals("2d".equals(interval), definition.isValid());
            assertEquals("2d".equals(interval) ? 172_800_000L : -1L, definition.getBurnIntervalMs());
        }
        config.set("item", " "); assertFalse(new FuelTemplate("blank", config).isValid());
        config.set("item", "vanilla.COAL"); config.set("burn-rate", 0); assertFalse(new FuelTemplate("zero", config).isValid());
        new FuelTemplateLoader().load(yaml("{}")); assertTrue(FuelTemplateLoader.getAll().isEmpty());
    }

    @Test void magnifyingGlassLoadsRequirementsAndRejectsMissingItems() throws Exception {
        new MagnifyingGlassLoader().load(yaml("""
            magnifying-glasses:
              scalar: ignored
              missing: {search-radius: 3}
              detective:
                item: vanilla.SPYGLASS
                discovery-bonus: 0.25
                investigation-cost: -5
                search-radius: 6
                requires: {strength: 2}
            """));
        ItemStack spyglass = new ItemStack(Material.SPYGLASS);
        when(items.getChecker().checkItemWithPath(spyglass, "vanilla.SPYGLASS")).thenReturn(true);
        MagnifyingGlassDefinition glass = MagnifyingGlassLoader.resolve(spyglass);
        assertEquals("detective", glass.getId()); assertEquals("vanilla.SPYGLASS", glass.getItem());
        assertEquals(.25, glass.getDiscoveryBonus()); assertEquals(0, glass.getInvestigationCost());
        assertEquals(6, glass.getSearchRadius()); assertEquals(Map.of("strength", 2), glass.getRequires());
        assertTrue(new MagnifyingGlassDefinition("empty", new YamlConfiguration()).getRequires().isEmpty());
        for (ItemStack absent : Arrays.asList(null, new ItemStack(Material.AIR), new ItemStack(Material.STONE)))
            assertNull(MagnifyingGlassLoader.resolve(absent));
        new MagnifyingGlassLoader().load(yaml("{}")); assertNull(MagnifyingGlassLoader.resolve(spyglass));
    }

    @Test void clueAndLensAttributeIdsAreIndependentOfHostLocale() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        new ConfigLoader().load(yaml("trait-clue-overrides: {INSIGHT: 2}\n"));
        assertEquals(Map.of("insight", 2), Cache.traitClueOverrides);
        YamlConfiguration lens = new YamlConfiguration(); lens.set("requires.INTELLIGENCE", 3);
        assertEquals(Map.of("intelligence", 3), new MagnifyingGlassDefinition("test", lens).getRequires());
    }

    @Test void permissionGroupsSortExplicitAndAutomaticTiersAndSnapshotPerks() throws Exception {
        new PermissionGroupsLoader().load(yaml("""
            defaults: {max-alive-characters: 3, name-colour-stops: 1}
            groups:
              scalar: ignored
              gold: {permission: group.gold, display-name: Gold, tier: 8, visible: false, max-alive-characters: 7}
              member: {permission: group.member, name-colour-stops: 2}
              friend: {wardrobe-skin-slots: 4}
            """));
        assertEquals(Map.of("max-alive-characters", 3, "name-colour-stops", 1), Cache.permissionGroupDefaults);
        assertEquals(List.of("member", "friend", "gold"), Cache.permissionGroups.stream().map(PermissionGroupDefinition::getId).toList());
        var gold = Cache.permissionGroups.get(2);
        assertEquals("group.gold", gold.getPermission()); assertEquals("Gold", gold.getDisplayName());
        assertEquals(8, gold.getTier()); assertFalse(gold.isVisible()); assertEquals(7, gold.getPerk("max-alive-characters", 3));
        assertEquals(9, gold.getPerk("missing", 9)); assertEquals(Map.of("max-alive-characters", 7), gold.getPerks());
        assertThrows(UnsupportedOperationException.class, () -> gold.getPerks().clear());
        assertTrue(new PermissionGroupDefinition("empty", "", "", 0, true, null).getPerks().isEmpty());
        new PermissionGroupsLoader().load(yaml("{}")); assertTrue(Cache.permissionGroups.isEmpty()); assertTrue(Cache.permissionGroupDefaults.isEmpty());
    }

    @Test void calendarRealmAndPlayerListConfigurationPreservesMeaningAndOrdering() throws Exception {
        new CalendarLoader().load(yaml("year-offset: 25\nera-suffix: AE\nage: {minimum: -2, unset-label: Unknown}\n"));
        assertEquals(25, Cache.calendarYearOffset); assertEquals("AE", Cache.calendarEraSuffix);
        assertEquals(0, Cache.calendarAgeMinimum); assertEquals("Unknown", Cache.calendarAgeUnsetLabel);
        new CalendarLoader().load(yaml("{}")); assertEquals(25, Cache.calendarYearOffset);
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        new WebCreatorLoader().load(yaml("by-realm:\n  scalar: ignored\n  ' MAIN ': {min-tier: -2, min-group-id: ' gold '}\n"));
        assertEquals(Set.of("main"), Cache.webCreatorAccessByRealm.keySet());
        var access = Cache.webCreatorAccessByRealm.get("main"); assertEquals(0, access.getMinTier()); assertEquals("gold", access.getMinGroupId());
        assertEquals("", new WebCreatorRealmAccess(-1, null).getMinGroupId());
        new WebCreatorLoader().load(yaml("{}")); assertTrue(Cache.webCreatorAccessByRealm.isEmpty());
        new PlayerListLoader().load(yaml("""
            permission: list.custom
            portrait: false
            title: Who
            header: Welcome
            quick-action: {enabled: false, label: Players}
            ranks: {VIP: Gold, member: Member}
            """));
        assertEquals("list.custom", Cache.playerList.permission()); assertFalse(Cache.playerList.portrait()); assertFalse(Cache.playerList.quickAction());
        assertEquals("Who", Cache.playerList.title()); assertEquals("Welcome", Cache.playerList.header()); assertEquals("Players", Cache.playerList.quickActionLabel());
        assertEquals(List.of("vip", "member"), Cache.playerList.rankOrder());
        assertEquals("vip", Cache.playerList.rank(group -> true)); assertNull(Cache.playerList.rank(group -> false));
        assertEquals(0, Cache.playerList.rankIndex("vip")); assertEquals(2, Cache.playerList.rankIndex(null)); assertEquals(2, Cache.playerList.rankIndex("none"));
        assertEquals("Gold", Cache.playerList.tag("vip")); assertEquals("", Cache.playerList.tag(null)); assertEquals("", Cache.playerList.tag("none"));
        assertEquals(Map.of("vip", "Gold", "member", "Member"), Cache.playerList.rankTags());
        new PlayerListLoader().load(yaml("{}")); assertEquals("rpchar.playerlist", Cache.playerList.permission());
    }

    @Test void profileViewSupportsLegacyDefaultSheetAndExplicitFormat() throws Exception {
        new ProfileViewLoader().load(yaml("permission: custom.profile\nview: {require-sneak: false, require-empty-hand: false, cooldown-seconds: -1}\nformat: ['Hello {name}']\n"));
        assertEquals("custom.profile", Cache.profilePermission); assertFalse(Cache.profileRequireSneak); assertFalse(Cache.profileRequireEmptyHand);
        assertEquals(0, Cache.profileViewCooldownSeconds); assertEquals(List.of("Hello {name}"), Cache.profileFormatLines);
        assertEquals(ProfileFormatter.DEFAULT_SHEET_FORMAT, Cache.profileSheetFormatLines);
        new ProfileViewLoader().load(yaml("sheet-format: ['Custom sheet']\n"));
        assertTrue(Cache.profileFormatLines.isEmpty()); assertEquals(List.of("Custom sheet"), Cache.profileSheetFormatLines);
    }

    @Test void profileSlotsDeduplicateKeepDeadSlotFreeAndRespectHardCap() throws Exception {
        ProfileLoader loader = new ProfileLoader(); loader.load(yaml("{}"));
        assertEquals(10, Cache.maxCharacterSlots); assertEquals(5, Cache.baseCharacterSlotCount);
        assertEquals(List.of(10,11,12,13,14,19,20,21,22,23), Cache.characterSlots);
        loader.load(yaml("max-character-slots: 2\ndead-slot: 16\ncharacter-slots: [16, 10, 10, 11, 12]\n"));
        assertEquals(List.of(10,11), Cache.characterSlots); assertEquals(2, Cache.baseCharacterSlotCount);
        loader.load(yaml("max-character-slots: 6\ncharacter-slots: [10]\nextended-character-slots: [16, 10, 20, 21, 22, 23, 24, 25]\n"));
        assertEquals(List.of(10,20,21,22,23,24), Cache.characterSlots);
        loader.load(yaml("max-character-slots: 6\ncharacter-slots: [10]\nextended-character-slots: [20]\n"));
        assertEquals(List.of(10,20), Cache.characterSlots);
        loader.load(yaml("max-character-slots: -1\n")); assertEquals(1, Cache.maxCharacterSlots);
    }

    @Test void invalidYamlAndMissingFilesLeaveLoadersUsableForAValidReload() throws Exception {
        List<LoaderInterface> loaders = List.of(new AttributePointTomeLoader(), new SkillPointTomeLoader(),
            new FuelTemplateLoader(), new MagnifyingGlassLoader(), new PermissionGroupsLoader(), new CalendarLoader(),
            new WebCreatorLoader(), new PlayerListLoader(), new ProfileViewLoader(), new ProfileLoader(), new PersonaLoader(), new ConfigLoader());
        File malformed = yaml("broken: [unterminated");
        for (LoaderInterface loader : loaders) assertDoesNotThrow(() -> loader.load(malformed), loader.getClass().getSimpleName());
        assertTrue(FuelTemplateLoader.getAll().isEmpty()); assertTrue(Cache.permissionGroups.isEmpty());
        assertTrue(Cache.webCreatorAccessByRealm.isEmpty());
        File missing = directory.resolve("absent.yml").toFile();
        for (LoaderInterface loader : loaders) assertDoesNotThrow(() -> loader.load(missing));
        new CalendarLoader().load(yaml("year-offset: 123\n")); assertEquals(123, Cache.calendarYearOffset);
    }

    @Test void coreConfigLoadsClueBoundsConversationAndRuntimePolicies() throws Exception {
        new ConfigLoader().load(yaml("""
            attributes: [strength, intelligence]
            ignored-attributes: [' INTELLIGENCE ', '']
            professions: [mining]
            editable-trait-types: [physical]
            background-trait-types: [history]
            continent: Vardera
            require-character: true
            no-character-freeze: false
            lacking-clues-freeze: false
            excess-characters-freeze: false
            skill-points-admin-debug-messages: true
            attribute-points-admin-debug-messages: true
            base-profession-factor: -4
            default-clues-required: 8
            evil-clues-required: 9
            evil-min-account-age-hours: -1
            character-description: {length-minimum: 3, length-maximum: 400}
            clue-min-length: 5
            clue-max-length: 80
            max-clues: 4
            race-clue: 'A {race}'
            trait-clue-overrides: {history: 10, ordinary: 1}
            spawned-clue-timer: 12
            spawned-clue-visual-y-offset: 0.3
            spawned-clue-line-spacing: 0.4
            spawned-clue-first-line-offset: 0.5
            spawned-clue-scale: 0.8
            spawned-clue-particle-interval: 20
            clue-spawn-radius: 6
            spawned-clue-line-length: 40
            conversation: {reply-timeout-seconds: 0, pair-cooldown-hours: -1}
            rp-injure: {range: 0, timeout-seconds: 0}
            """));
        assertEquals(List.of("strength", "intelligence"), Cache.attributes); assertEquals(Set.of("intelligence"), Cache.ignoredAttributes);
        assertEquals(List.of("mining"), Cache.professions); assertEquals(List.of("physical"), Cache.editableTraits);
        assertEquals(List.of("history"), Cache.backgroundTraitTypes); assertEquals("Vardera", Cache.continent); assertTrue(Cache.requireCharacter);
        assertFalse(Cache.noCharacterFreeze); assertFalse(Cache.lackingCluesFreeze); assertFalse(Cache.excessCharactersFreeze);
        assertTrue(Cache.skillPointsAdminDebugMessages); assertTrue(Cache.attributePointsAdminDebugMessages); assertEquals(-4, Cache.startingProfessionFactor);
        assertEquals(8, Cache.defaultCluesRequired); assertEquals(9, Cache.evilCluesRequired); assertEquals(0, Cache.evilMinAccountAgeHours);
        assertEquals(3, Cache.characterDescriptionMinLength); assertEquals(400, Cache.characterDescriptionMaxLength);
        assertEquals(5, Cache.clueMinLength); assertEquals(80, Cache.clueMaxLength); assertEquals(4, Cache.maxClues);
        assertEquals("A {race}", Cache.raceClueTemplate); assertEquals(Map.of("history", 10, "ordinary", 1), Cache.traitClueOverrides);
        assertEquals(12, Cache.spawnedClueTimerHours); assertEquals(.3, Cache.spawnedClueVisualYOffset); assertEquals(.4, Cache.spawnedClueLineSpacing);
        assertEquals(.5, Cache.spawnedClueFirstLineOffset); assertEquals(.8f, Cache.spawnedClueScale); assertEquals(20, Cache.spawnedClueParticleInterval);
        assertEquals(6, Cache.clueSpawnRadius); assertEquals(40, Cache.spawnedClueLineLength);
        assertEquals(1, Cache.conversationReplyTimeoutSeconds); assertEquals(0, Cache.conversationPairCooldownHours);
        assertEquals(.1, Cache.rpInjureRange); assertEquals(1, Cache.rpInjureTimeoutSeconds);
        new ConfigLoader().load(yaml("{}")); assertEquals(30, Cache.conversationReplyTimeoutSeconds); assertEquals(2, Cache.conversationPairCooldownHours);
        assertEquals(10, Cache.rpInjureRange); assertEquals(30, Cache.rpInjureTimeoutSeconds); assertTrue(Cache.traitClueOverrides.isEmpty());
    }

    @Test void personaCustomFieldsAndDefaultsFollowDisplayNameLimits() throws Exception {
        new PersonaLoader().load(yaml("""
            permissions: {set: p.set, namecolour: p.color, description-colors: p.desc, override: p.override, bypass-cooldown: p.bypass, tempalias: p.temp, character-hidden: p.hidden}
            no-character-fallback: '&7Unknown'
            display-name: {length-minimum: 3, length-maximum: 20}
            alias: {length: {minimum: 4, maximum: 15}, allowed-chars: '[a-z]+', cooldown-seconds: 12}
            gender: {values: [A, B], default: A, cooldown-seconds: 4}
            description: {length-minimum: 8, cooldown-seconds: 9, default-template: Hello}
            """));
        assertEquals("p.set", Cache.personaSetPermission); assertEquals("p.color", Cache.personaNamecolourPermission);
        assertEquals("p.desc", Cache.personaDescriptionColorsPermission); assertEquals("p.override", Cache.personaOverridePermission);
        assertEquals("p.bypass", Cache.personaBypassCooldownPermission); assertEquals("p.temp", Cache.personaTempaliasPermission); assertEquals("p.hidden", Cache.personaCharacterHiddenPermission);
        assertEquals("§7Unknown", Cache.personaNoCharacterFallback); assertEquals(3, Cache.personaDisplayNameMinLength); assertEquals(20, Cache.personaDisplayNameMaxLength);
        assertEquals(4, Cache.personaAliasMinLength); assertEquals(15, Cache.personaAliasMaxLength); assertEquals("[a-z]+", Cache.personaAliasAllowedChars);
        assertEquals(12, Cache.personaAliasCooldownSeconds); assertEquals(List.of("A","B"), Cache.personaGenders); assertEquals("A", Cache.personaGenderDefault);
        assertEquals(4, Cache.personaGenderCooldownSeconds); assertEquals(8, Cache.personaDescriptionMinLength); assertEquals(9, Cache.personaDescriptionCooldownSeconds);
        assertEquals("Hello", Cache.personaDescriptionDefaultTemplate);
        new PersonaLoader().load(yaml("{}")); assertEquals(3, Cache.personaAliasMinLength); assertEquals(20, Cache.personaAliasMaxLength);
    }
}
