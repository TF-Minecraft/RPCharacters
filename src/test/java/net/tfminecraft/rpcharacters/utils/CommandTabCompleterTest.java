package net.tfminecraft.rpcharacters.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.command.CharCommand;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.party.PartyCommand;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.tutorial.TutorialLoader;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeCommand;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class CommandTabCompleterTest {
    ServerMock server;
    PlayerMock player, iris;
    Plugin plugin;
    RuntimeTestState state;
    Command command;
    CommandTabCompleter tabs;
    PlayerData data;
    final List<MockedStatic<?>> mocks = new ArrayList<>();
    MockedStatic<PlayerManager> players;
    MockedStatic<PermissionGroupService> groups;
    MockedStatic<PartyCommand> party;
    MockedStatic<WardrobeCommand> wardrobe;
    <T> MockedStatic<T> boundary(Class<T> type) { var mocked = mockStatic(type); mocks.add(mocked); return mocked; }

    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(StageLoader.class, TraitLoader.class);
        plugin = MockBukkit.createMockPlugin(); player = server.addPlayer("Alex"); iris = server.addPlayer("Iris");
        command = mock(Command.class); when(command.getName()).thenReturn("rpcharacter"); tabs = new CommandTabCompleter();
        Cache.personaGenders = List.of("Female", "Male", "Other");
        for (String permission : List.of(Permissions.Permission_Admin, Cache.personaOverridePermission,
                Cache.personaTempaliasPermission, Cache.personaCharacterHiddenPermission)) grant(permission, false);
        data = mock(PlayerData.class); List<RPCharacter> characters = List.of(character("hero", "Hero"), character(null, null));
        when(data.getCharacters()).thenReturn(characters);
        players = boundary(PlayerManager.class); players.when(() -> PlayerManager.get(any(Player.class))).thenReturn(data);
        boundary(KitLoader.class).when(KitLoader::kitIds).thenReturn(new LinkedHashSet<>(List.of("starter", "tools")));
        boundary(SummaryEditSupport.class).when(SummaryEditSupport::getEditEntryKeys).thenReturn(List.of("name", "race"));
        boundary(TutorialLoader.class).when(TutorialLoader::getIds).thenReturn(List.of("permadeath-zone", "strikes"));
        groups = boundary(PermissionGroupService.class); groups.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(4);
        party = boundary(PartyCommand.class); wardrobe = boundary(WardrobeCommand.class);
        StageLoader.oList = new ArrayList<>(); TraitLoader.oList = new ArrayList<>();
    }
    @AfterEach void restore() {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        state.close(); MockBukkit.unmock();
    }
    void grant(String permission, boolean value) { player.addAttachment(plugin, permission, value); }
    void admin() { grant(Permissions.Permission_Admin, true); }
    List<String> complete(String... args) { return tabs.onTabComplete(player, command, "rpcharacter", args); }
    RPCharacter character(String slug, String name) {
        RPCharacter character = mock(RPCharacter.class); when(character.getSlug()).thenReturn(slug); when(character.getName()).thenReturn(name); return character;
    }

    @Test void unrelatedCommandsAndEmptyArgumentArraysHaveNoSuggestions() {
        when(command.getName()).thenReturn("other"); assertEquals(List.of(), complete(""));
        when(command.getName()).thenReturn("rpcharacter"); assertEquals(List.of(), assertDoesNotThrow(() -> complete()));
    }

    @Test void rootSuggestionsRespectPermissionsAndPrefixAndAreFreshCopies() {
        List<String> basic = complete(""); assertTrue(basic.containsAll(List.of("create", "wardrobe", "party", "mail", "strikes")));
        assertFalse(basic.contains("admin")); assertFalse(basic.contains("tempalias")); assertFalse(basic.contains("sethidden"));
        basic.clear(); assertFalse(complete((String) null).isEmpty());
        grant("rpchar.class.reset", true); assertTrue(complete("").contains("admin")); assertFalse(complete("").contains("reload"));
        admin(); grant(Cache.personaTempaliasPermission, true); grant(Cache.personaCharacterHiddenPermission, true);
        assertTrue(complete("").containsAll(List.of("admin", "reload", "catalog", "pending", "wipe", "reclaimkit", "resetkit", "stage", "setclass",
            "seteighteen", "skipcooldown", "addtrait", "removetrait", "clearclues", "placeclue", "adminmode", "discordgate", "setworldspawn", "tempalias", "sethidden")));
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals(List.of("injure"), complete("INJ"));
    }

    @Test void delegatedPartyAndWardrobeSuggestionsPreserveResults() {
        String[] partyArgs = {"party", "invite", "I"}; party.when(() -> PartyCommand.tabComplete(player, partyArgs)).thenReturn(List.of("Iris"));
        assertEquals(List.of("Iris"), complete(partyArgs));
        String[] wardrobeArgs = {"wardrobe", "s"}; wardrobe.when(() -> WardrobeCommand.tabComplete(player, wardrobeArgs)).thenReturn(List.of("skin1"));
        assertEquals(List.of("skin1"), complete(wardrobeArgs));
    }

    @Test void secondArgumentSuggestionsUseConfiguredEntriesAndOnlinePlayers() {
        admin(); assertEquals(List.of("starter"), complete("kit", "st"));
        for (String verb : List.of("catalog", "pending")) assertEquals(List.of("sync"), complete(verb, ""));
        assertEquals(List.of("website"), complete("wipe", "")); assertEquals(List.of("preview"), complete("stage", ""));
        for (String verb : List.of("menu", "clues", "setclass", "seteighteen", "resetkit", "reclaimkit", "skipcooldown", "addtrait", "removetrait", "injure", "discordgate"))
            assertEquals(List.of("Iris"), complete(verb, "IR"), verb);
        assertEquals(List.of("name", "race"), complete("edit", ""));
        assertEquals(List.of("injure", "permakill", "strikes", "tutorial"), complete("admin", ""));
        grant("rpchar.class.reset", true); assertEquals(List.of("injure", "permakill", "strikes", "tutorial", "resetclasses"), complete("admin", ""));
        assertEquals(List.of("confirm"), complete("admin", "RESETCLASSES", "c")); assertEquals(List.of(), complete("admin", "resetclasses", "confirm", ""));
        assertEquals(List.of("5", "10", "25", "50"), complete("clearclues", "")); assertEquals(List.of(), complete("placeclue", ""));
        assertEquals(List.of("on", "off"), complete("adminmode", ""));
        grant(Cache.personaTempaliasPermission, true); assertEquals(List.of("clear"), complete("tempalias", ""));
        grant(Cache.personaCharacterHiddenPermission, true); assertEquals(List.of("hero"), complete("sethidden", ""));
        assertEquals(List.of(), complete("unknown", ""));
    }

    @Test void adminSuggestionsDisappearWithoutPermission() {
        for (String verb : List.of("catalog", "pending", "wipe", "stage", "seteighteen", "resetkit", "reclaimkit", "admin", "clearclues", "placeclue", "adminmode", "discordgate"))
            assertEquals(List.of(), complete(verb, ""), verb);
        assertEquals(List.of(), complete("admin", "strikes", "")); assertEquals(List.of(), complete("admin", "resetclasses", ""));
        grant("rpchar.class.reset", true); assertEquals(List.of("resetclasses"), complete("admin", ""));
    }

    @Test void hiddenCharacterSuggestionsWaitForPlayerDataToLoad() {
        grant(Cache.personaCharacterHiddenPermission, true);
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertEquals(List.of(), assertDoesNotThrow(() -> complete("sethidden", "")));
    }

    @Test void thirdArgumentSuggestionsExposeConfiguredStageTraitsAndCharacterIds() {
        admin(); Stage first = new Stage(); first.setId("intro"); StageLoader.oList.addAll(Arrays.asList(null, new Stage(), first));
        assertEquals(List.of("intro"), complete("stage", "preview", ""));
        assertEquals(List.of("confirm"), complete("wipe", "website", "")); assertEquals(List.of("on", "off"), complete("discordgate", "Iris", ""));
        grant(Cache.personaCharacterHiddenPermission, true); assertEquals(List.of("clear"), complete("sethidden", "hero", ""));
        assertEquals(List.of("className"), complete("setclass", "Iris", "")); assertEquals(List.of("true", "false"), complete("seteighteen", "Iris", ""));
        List<RPCharacter> characters = Arrays.asList(null, character(null, null), character(" ", null), character("hero", "Hero"));
        when(data.getCharacters()).thenReturn(characters);
        for (String verb : List.of("resetkit", "reclaimkit")) assertEquals(List.of("hero"), complete(verb, "Iris", ""));
        assertEquals(List.of(), complete("resetkit", "offline", ""));
        players.when(() -> PlayerManager.get(iris)).thenReturn(null); assertEquals(List.of(), complete("reclaimkit", "Iris", ""));
        players.when(() -> PlayerManager.get(iris)).thenReturn(data);
        Trait trait = mock(Trait.class); when(trait.getId()).thenReturn("brave"); TraitLoader.oList.add(trait);
        for (String verb : List.of("addtrait", "removetrait")) assertEquals(List.of("brave"), complete(verb, "Iris", ""));
        for (String verb : List.of("injure", "permakill")) assertEquals(List.of("Iris"), complete("admin", verb, "I"));
        assertEquals(List.of(), complete("unknown", "x", ""));
    }

    @Test void injuryAndKitCompletionAtLaterPositionsUsesTargetCharacters() {
        admin();
        assertEquals(List.of("hero", "Hero", "permanent"), complete("injure", "Iris", ""));
        assertEquals(List.of("hero", "Hero", "permanent"), complete("admin", "injure", "Iris", ""));
        assertEquals(List.of("permanent"), complete("injure", "offline", ""));
        assertEquals(List.of("permanent"), complete("admin", "injure", "offline", ""));
        players.when(() -> PlayerManager.get(iris)).thenReturn(null); assertEquals(List.of("permanent"), complete("injure", "Iris", ""));
        assertEquals(List.of("permanent"), complete("admin", "injure", "Iris", ""));
        assertEquals(List.of("permanent"), complete("injure", "Iris", "hero", ""));
        assertEquals(List.of("permanent"), complete("admin", "injure", "Iris", "hero", ""));
        for (String verb : List.of("resetkit", "reclaimkit")) assertEquals(List.of("starter", "tools"), complete(verb, "Iris", "hero", ""));
        assertEquals(List.of(), complete("unknown", "a", "b", "c", "d", "e"));
    }

    @Test void adminStrikesAndTutorialCompletionsTrackActionArity() {
        admin(); assertEquals(List.of("reset"), complete("admin", "tutorial", ""));
        assertEquals(List.of("view", "add", "remove", "set", "startsession", "endsession"), complete("admin", "strikes", ""));
        assertEquals(List.of("Iris"), complete("admin", "strikes", "add", "I"));
        assertEquals(List.of("permadeath-zone", "strikes"), complete("admin", "tutorial", "reset", "Iris", ""));
        assertEquals(List.of(), complete("admin", "tutorial", "reset", "Iris", "x", ""));
        assertEquals(List.of("0", "1", "2"), complete("admin", "strikes", "set", "Iris", ""));
        assertEquals(List.of("hero"), complete("admin", "strikes", "set", "Iris", "1", ""));
        assertEquals(List.of("hero", "quiet"), complete("admin", "strikes", "add", "Iris", ""));
        assertEquals(List.of("quiet"), complete("admin", "strikes", "add", "Iris", "hero", ""));
        assertEquals(List.of(), complete("admin", "strikes", "startsession", "Iris", ""));
        assertEquals(List.of(), complete("admin", "strikes", "view", "offline", ""));
        players.when(() -> PlayerManager.get(iris)).thenReturn(null); assertEquals(List.of(), complete("admin", "strikes", "view", "Iris", ""));
    }

    @Test void personaCompletionsRespectFieldArityAndColourAllowance() {
        for (String verb : List.of("alias", "description", "birthday")) {
            assertEquals(List.of("clear"), complete(verb, "")); assertEquals(List.of(), complete(verb, "x", ""));
        }
        assertEquals(List.of("on", "off"), complete("mail", "")); assertEquals(List.of(), complete("mail", "on", ""));
        assertEquals(List.of("clear", "#ff5555"), complete("namecolour", ""));
        assertEquals(List.of("#0000ff"), complete("namecolour", "#ff0000", ""));
        assertEquals(List.of(), complete("namecolour", "clear", ""));
        groups.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(2);
        assertEquals(List.of("#0000ff"), complete("namecolour", "#ff0000", ""), "The final permitted colour still needs completion");
        assertEquals(List.of(), complete("namecolour", "#ff0000", "#00ff00", ""));
        assertEquals(List.of("Female"), complete("gender", "f")); assertEquals(List.of(), complete("gender", "female", ""));
        assertEquals(List.of("Iris"), complete("profile", "i")); assertEquals(List.of(), complete("profile", "Iris", ""));
        assertEquals(List.of(), tabs.onTabComplete(server.getConsoleSender(), command, "rpcharacter", new String[]{"alias", ""}));
    }

    @Test void personaExtensionWithoutASpecializedCompleterHasNoSuggestions() {
        try (var persona = mockStatic(CharCommand.class)) {
            persona.when(() -> CharCommand.isPersonaSubcommand("extension")).thenReturn(true);
            assertEquals(List.of(), complete("extension", ""));
        }
    }

    @Test void overrideCompletionRequiresPermissionAndUsesFieldsValuesAndPrefixes() {
        assertEquals(List.of(), complete("override", "")); grant(Cache.personaOverridePermission, true);
        assertEquals(List.of("Iris"), complete("override", "IR"));
        assertEquals(List.of("alias", "tempalias", "gender", "description", "namecolour", "birthday", "playtime"), complete("override", "Iris", ""));
        for (String field : List.of("alias", "tempalias", "description", "namecolour", "birthday", "playtime"))
            assertEquals(List.of("clear"), complete("override", "Iris", field, ""));
        assertEquals(List.of("Other"), complete("override", "Iris", "gender", "O"));
        assertEquals(List.of(), complete("override", "Iris", "unknown", ""));
        assertEquals(List.of("#0000ff"), complete("override", "Iris", "namecolour", "#ff0000", ""));
        assertEquals(List.of(), complete("override", "Iris", "namecolour", "clear", ""));
    }
}
