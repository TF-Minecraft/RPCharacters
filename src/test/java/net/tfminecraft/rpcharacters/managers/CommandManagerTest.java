package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient.CatalogPushResult;
import net.tfminecraft.rpcharacters.catalog.CreationCatalogSyncService;
import net.tfminecraft.rpcharacters.classpick.ClassPickGui;
import net.tfminecraft.rpcharacters.classpick.ClassPickReset;
import net.tfminecraft.rpcharacters.clues.discovery.*;
import net.tfminecraft.rpcharacters.command.CharCommand;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.evilrp.EvilRpCommands;
import net.tfminecraft.rpcharacters.identity.TempAliasService;
import net.tfminecraft.rpcharacters.ingest.CharacterIngestService;
import net.tfminecraft.rpcharacters.injuries.RpInjureService;
import net.tfminecraft.rpcharacters.kit.KitService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.party.PartyCommand;
import net.tfminecraft.rpcharacters.permadeath.PermadeathAdminCommands;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.pvp.StrikeChoice;
import net.tfminecraft.rpcharacters.tutorial.*;
import net.tfminecraft.rpcharacters.utils.*;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeCommand;
import net.tfminecraft.rpcharacters.wipe.WipeCommand;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class CommandManagerTest {
    ServerMock server;
    PlayerMock player, target;
    Plugin permissions;
    RPCharacters plugin;
    RuntimeTestState state;
    CommandManager commands;
    Command command;
    PlayerData data;
    RPCharacter character;
    PlayerManager playerManager;
    PlaceClueManager placeClues;
    MockedStatic<PlayerManager> players;
    MockedStatic<CreationManager> creators;
    final List<MockedStatic<?>> mocks = new ArrayList<>();
    MMOCore previousCore;
    <T> MockedStatic<T> boundary(Class<T> type) { var result = mockStatic(type); mocks.add(result); return result; }

    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, CreationManager.class);
        previousCore = MMOCore.plugin; permissions = MockBukkit.createMockPlugin();
        player = server.addPlayer("Alex"); target = server.addPlayer("Iris");
        player.teleport(server.addSimpleWorld("world").getSpawnLocation());
        plugin = mock(RPCharacters.class); RPCharacters.plugin = plugin;
        commands = new CommandManager(); command = mock(Command.class); when(command.getName()).thenReturn("rpcharacter");
        for (String permission : List.of(Permissions.Permission_Admin, Cache.personaTempaliasPermission,
                Cache.personaCharacterHiddenPermission, "rpchar.injure")) grant(permission, false);
        data = mock(PlayerData.class); character = mock(RPCharacter.class);
        when(data.getUniqueId()).thenReturn(target.getUniqueId()); when(data.getActiveCharacter()).thenReturn(character);
        when(character.getName()).thenReturn("Hero"); when(character.getSlug()).thenReturn("hero");
        when(character.getMMOClass()).thenReturn("OLD");
        players = boundary(PlayerManager.class); players.when(() -> PlayerManager.get(any(Player.class))).thenReturn(data);
        creators = boundary(CreationManager.class); CreationManager.activeCreators = new HashMap<>();
        playerManager = mock(PlayerManager.class); placeClues = mock(PlaceClueManager.class);
        var globals = boundary(RPCharacters.class); globals.when(RPCharacters::getPlayerManager).thenReturn(playerManager);
        globals.when(RPCharacters::getPlaceClueManager).thenReturn(placeClues);
    }
    @AfterEach void restore() {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        MMOCore.plugin = previousCore; state.close(); MockBukkit.unmock();
    }
    void grant(String permission, boolean value) { player.addAttachment(permissions, permission, value); }
    void admin() { grant(Permissions.Permission_Admin, true); }
    boolean run(String... args) { return commands.onCommand(player, command, "rpcharacter", args); }
    String messages(PlayerMock recipient) {
        List<String> out = new ArrayList<>(); String message;
        while ((message = recipient.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        return String.join("\n", out);
    }
    void message(String expected) { assertTrue(messages(player).contains(expected), expected); }
    CharacterCreation creator(Player p) {
        CharacterCreation creation = mock(CharacterCreation.class); when(creation.getCharacter()).thenReturn(character);
        CreationManager.activeCreators.put(p, creation); return creation;
    }
    ClassManager classManager() throws Exception {
        MMOCore.plugin = mock(MMOCore.class); ClassManager manager = mock(ClassManager.class);
        Field field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, manager);
        return manager;
    }
    PlayerClass knownClass(ClassManager manager, String id) {
        PlayerClass value = mock(PlayerClass.class); when(value.getName()).thenReturn("Warrior"); when(manager.get(id)).thenReturn(value); return value;
    }

    @Test void unknownCommandsEmptyInputConsoleAndBadArityAreHandled() {
        when(command.getName()).thenReturn("other"); assertTrue(run("reload")); assertEquals("", messages(player));
        when(command.getName()).thenReturn("rpcharacter"); assertTrue(run()); assertEquals("", messages(player));
        CommandSender console = mock(CommandSender.class); assertTrue(commands.onCommand(console, command, "rpcharacter", new String[0]));
        verify(console).sendMessage(contains("Only players")); assertTrue(run("unknown")); message("Error with command format");
        for (String[] args : List.of(new String[]{"kit"}, new String[]{"kit", null}, new String[]{"kit", " "})) { run(args); message("Usage:"); }
        var kits = boundary(KitService.class); run("kit", "starter"); kits.verify(() -> KitService.tryClaim(player, "starter"));
    }

    @Test void delegatedRootCommandsPreserveHandlerReturnValuesAndArguments() {
        var persona = boundary(CharCommand.class); persona.when(() -> CharCommand.isPersonaSubcommand("alias")).thenReturn(true);
        String[] alias = {"alias", "name"}; assertFalse(run(alias)); persona.verify(() -> CharCommand.handle(player, "rpcharacter", alias));
        var wardrobe = boundary(WardrobeCommand.class); String[] skins = {"wardrobe"}; assertFalse(run(skins)); wardrobe.verify(() -> WardrobeCommand.handle(player, "rpcharacter", skins));
        var party = boundary(PartyCommand.class); String[] invite = {"party", "invite", "Iris"}; assertFalse(run(invite)); party.verify(() -> PartyCommand.handle(player, invite));
        var wipe = boundary(WipeCommand.class); String[] reset = {"wipe", "website", "confirm"}; assertFalse(run(reset)); wipe.verify(() -> WipeCommand.handle(player, reset));
    }

    @Test void administrativeRoutesCheckPermissionsBeforeDelegation() {
        for (String verb : List.of("admin", "permakill", "reload", "catalog", "pending", "reclaimkit", "resetkit")) { run(verb); message("permission"); }
        admin(); run("admin"); message("Usage:"); run("admin", "unknown"); message("Usage:");
        var death = boundary(PermadeathAdminCommands.class); var evil = boundary(EvilRpCommands.class); var tutorials = boundary(TutorialCommands.class);
        String[] injure = {"admin", "injure", "Iris"}; assertFalse(run(injure)); death.verify(() -> PermadeathAdminCommands.handleInjure(eq(player), same(injure), eq(2), anyString()));
        String[] kill = {"admin", "permakill", "Iris"}; assertFalse(run(kill)); death.verify(() -> PermadeathAdminCommands.handlePermakill(player, kill, 2, "permakill"));
        String[] strikes = {"admin", "strikes", "view"}; assertFalse(run(strikes)); evil.verify(() -> EvilRpCommands.handleAdmin(player, strikes, 2));
        String[] tutorial = {"admin", "tutorial", "reset"}; assertFalse(run(tutorial)); tutorials.verify(() -> TutorialCommands.handleAdmin(player, tutorial, 2));
        var resets = boundary(ClassPickReset.class); String[] reset = {"admin", "ResetClasses", "confirm"}; assertFalse(run(reset));
        resets.verify(() -> ClassPickReset.handle(player, reset, 2));
        String[] directKill = {"permakill", "Iris"}; assertFalse(run(directKill)); death.verify(() -> PermadeathAdminCommands.handlePermakill(player, directKill, 1, "permakill"));
        String[] directInjure = {"injure", "Iris", "permanent"}; assertFalse(run(directInjure)); death.verify(() -> PermadeathAdminCommands.handleInjure(eq(player), same(directInjure), eq(1), anyString()));
        run("reload"); verify(plugin).reloadConfigs(player);
    }

    @Test void catalogPublicationBuildsThenUploadsThenRepliesThroughTheScheduler() {
        admin(); run("catalog"); message("Usage:"); run("catalog", "wrong"); message("Usage:");
        var catalog = boundary(CreationCatalogSyncService.class); catalog.when(CreationCatalogSyncService::buildPayloadJson).thenReturn("catalog-json");
        BukkitScheduler scheduler = mock(BukkitScheduler.class); List<Runnable> main = new ArrayList<>(), async = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { main.add(call.getArgument(1)); return null; });
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> { async.add(call.getArgument(1)); return null; });
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            for (CatalogPushResult result : List.of(CatalogPushResult.success(1, 2, 3, 4, "now"), CatalogPushResult.fail("offline"), CatalogPushResult.fail(null))) {
                catalog.when(() -> CreationCatalogSyncService.pushJson("catalog-json")).thenReturn(result);
                run("catalog", "sync"); message("Syncing creation catalog"); assertEquals(1, main.size()); assertTrue(async.isEmpty());
                main.removeFirst().run(); assertEquals(1, async.size()); async.removeFirst().run(); assertEquals(1, main.size()); main.removeFirst().run();
                message(result.ok ? "stages=1 races=2 traits=3 classes=4" : result.error == null ? "unknown" : "offline");
            }
        }
        var ingest = boundary(CharacterIngestService.class); run("pending"); message("Usage:"); run("pending", "wrong"); message("Usage:");
        run("pending", "sync"); message("Pending sync started"); ingest.verify(() -> CharacterIngestService.forcePullAsync(plugin));
    }

    @Test void kitResetAndReclaimValidateTargetsAndReportLocalAndRemoteResults() {
        admin(); var kits = boundary(KitService.class);
        for (String verb : List.of("resetkit", "reclaimkit")) {
            run(verb); message("Usage:"); run(verb, "offline", "hero", "starter"); message("Player must be online");
            kits.when(() -> KitService.reclaimKit(target, "hero", "starter")).thenReturn(KitService.ResetResult.fail("no kit"));
            kits.when(() -> KitService.resetKit(target, "hero", "starter")).thenReturn(KitService.ResetResult.fail("no kit"));
            run(verb, "Iris", "hero", "starter"); message("no kit");
            kits.when(() -> KitService.reclaimKit(target, "hero", "starter")).thenReturn(KitService.ResetResult.ok("done", true, null));
            kits.when(() -> KitService.resetKit(target, "hero", "starter")).thenReturn(KitService.ResetResult.ok("done", true, null));
            run(verb, "Iris", "hero", "starter"); message("done");
        }
        for (String error : Arrays.asList("offline", null)) {
            kits.when(() -> KitService.resetKit(target, "hero", "starter")).thenReturn(KitService.ResetResult.ok("done", false, error));
            run("resetkit", "Iris", "hero", "starter"); message("ProvinceSystem customise wipe failed: " + (error == null ? "unknown" : error));
        }
    }

    @Test void creationNavigationChecksActiveDraftAndStageBeforeDelegating() {
        var slots = boundary(CharacterSlotService.class); run("create"); message("free character slot");
        slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(true); run("create"); creators.verify(() -> CreationManager.initiateCreation(player));
        for (String verb : List.of("next", "back", "help", "cancel")) { run(verb); message("active creator"); }
        CharacterCreation creator = creator(player); run("create"); message("already creating"); run("next"); message("cannot use");
        when(creator.canNext()).thenReturn(true); run("next"); creators.verify(() -> CreationManager.next(player));
        run("back"); creators.verify(() -> CreationManager.back(player));
        var help = boundary(CreationStageHelp.class); run("help"); help.verify(() -> CreationStageHelp.send(player, creator));
        run("cancel"); verify(creator).cancel();
    }

    @Test void profileMenuRespectsOtherPlayerPermissionAndBusyState() {
        try (var inventories = mockConstruction(InventoryManager.class)) {
            run("menu"); verify(inventories.constructed().getLast()).profileView(player, player);
            run("menu", "offline"); verify(inventories.constructed().getLast()).profileView(player, player);
            run("menu", "Iris"); message("access"); assertEquals(2, inventories.constructed().size());
            admin(); run("menu", "Iris"); verify(inventories.constructed().getLast()).profileView(player, target);
            creator(player); run("menu"); message("busy creating"); assertEquals(3, inventories.constructed().size());
        }
    }

    @Test void editingAndStagePreviewUseCorrectEntryPointsAndGuardDrafts() {
        run("edit"); creators.verify(() -> CreationManager.initiateEdit(player));
        run("edit", "name"); creators.verify(() -> CreationManager.initiateEditEntry(player, "name"));
        CharacterCreation creator = creator(player);
        for (boolean preview : List.of(false, true)) {
            when(creator.isPreview()).thenReturn(preview);
            for (String[] args : List.of(new String[]{"edit"}, new String[]{"edit", "name"})) { run(args); message(preview ? "busy previewing" : "busy creating"); }
        }
        when(creator.isEditing()).thenReturn(true); run("edit"); run("edit", "race"); creators.verify(() -> CreationManager.initiateEditEntry(player, "race"));
        run("stage", "preview", "intro"); message("access"); admin(); run("stage", "preview"); message("Usage:");
        run("stage", "preview", "intro"); creators.verify(() -> CreationManager.initiateStagePreview(player, "intro"));
    }

    @Test void classChangesValidateClassesAndUpdateDraftOrLiveLifecycle() throws Exception {
        ClassManager classes = classManager(); knownClass(classes, "WARRIOR");
        run("setclass", "Iris", "warrior"); message("access"); admin();
        run("setclass", "Iris", "missing"); message("No class by the id MISSING");
        CharacterCreation draft = creator(target); run("setclass", "Iris", "warrior"); verify(character).setMMOClass("WARRIOR"); message("Draft class for Iris");
        assertTrue(messages(target).contains("Draft class set to Warrior"));
        CreationManager.activeCreators.clear(); creator(player); run("setclass", "Alex", "warrior"); message("Draft class set to Warrior");
        CreationManager.activeCreators.clear(); run("setclass", "Iris", "warrior"); message("has no character");
        when(data.hasActiveCharacter()).thenReturn(true); var lifecycle = boundary(CharacterLifecycle.class); var service = boundary(ClassService.class);
        when(character.getMMOClass()).thenReturn("OLD", "WARRIOR");
        run("setclass", "Iris", "warrior"); lifecycle.verify(() -> CharacterLifecycle.notifyClassChange(target, target.getUniqueId(), character, "OLD", "WARRIOR"));
        service.verify(() -> ClassService.applyClass(target, "WARRIOR")); assertTrue(messages(target).contains("Your class was changed"));
        service.when(() -> ClassService.isOnClass(target, "WARRIOR")).thenReturn(true); run("setclass", "Iris", "warrior"); assertEquals("", messages(target));
    }

    @Test void settingAClassForAnOfflineTargetFailsWithoutDereferencingNull() throws Exception {
        assertDoesNotThrow(() -> run("setclass", "offline", "warrior")); message("access");
        admin(); ClassManager classes = classManager(); knownClass(classes, "WARRIOR");
        assertDoesNotThrow(() -> run("setclass", "offline", "warrior"));
        assertTrue(messages(player).toLowerCase(Locale.ROOT).contains("player")); verifyNoInteractions(data);
        players.when(() -> PlayerManager.get(target)).thenReturn(null);
        assertDoesNotThrow(() -> run("setclass", "Iris", "warrior")); message("has no character");
    }

    @Test void classIdentifiersRemainStableUnderTurkishLocale() throws Exception {
        admin(); ClassManager classes = classManager(); knownClass(classes, "FIGHTER"); creator(target);
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); run("setclass", "Iris", "fighter"); verify(character).setMMOClass("FIGHTER");
    }

    @Test void ageAndCooldownAdministrationCheckTargetsAndApplyRequestedValues() {
        for (String[] args : List.of(new String[]{"seteighteen", "Iris", "true"}, new String[]{"skipcooldown", "Iris"})) { run(args); message("access"); }
        admin(); run("seteighteen", "offline", "true"); message("No player found"); run("skipcooldown", "offline"); message("No player found");
        for (String value : List.of("true", "false")) { run("seteighteen", "Iris", value); verify(data).setEighteen(Boolean.parseBoolean(value)); message("changed to " + value); }
        run("skipcooldown", "Iris"); verify(data).clearCharacterSwitchCooldown(); message("Removed cooldown"); assertTrue(messages(target).contains("has been skipped"));
    }

    @Test void malformedAgeBooleanCannotSilentlyRevokeAdultStatus() {
        admin(); run("seteighteen", "Iris", "treu"); verify(data, never()).setEighteen(anyBoolean()); message("true");
    }

    @Test void adultStatusChangesWaitUntilOnlinePlayerDataIsLoaded() {
        admin(); players.when(() -> PlayerManager.get(target)).thenReturn(null);
        assertDoesNotThrow(() -> run("seteighteen", "Iris", "true"));
        assertTrue(messages(player).toLowerCase(Locale.ROOT).contains("data")); verifyNoInteractions(data);
    }

    @Test void cooldownChangesWaitUntilOnlinePlayerDataIsLoaded() {
        admin(); players.when(() -> PlayerManager.get(target)).thenReturn(null);
        assertDoesNotThrow(() -> run("skipcooldown", "Iris"));
        assertTrue(messages(player).toLowerCase(Locale.ROOT).contains("data")); verifyNoInteractions(data);
    }

    @Test void adminModeAndDiscordGateSwitchesRejectInvalidInputAndNotifyServices() {
        run("adminmode", "on"); message("access"); run("discordgate", "Iris", "on"); message("access"); admin();
        run("adminmode", "maybe"); message("Usage:"); run("discordgate", "Iris", "maybe"); message("Usage:");
        var modes = boundary(ClueAdminModeService.class); ClueDiscoveryVisualManager visual = mock(ClueDiscoveryVisualManager.class);
        boundary(ClueDiscoveryVisualManager.class).when(ClueDiscoveryVisualManager::get).thenReturn(visual);
        run("adminmode", "on"); modes.verify(() -> ClueAdminModeService.setEnabled(player, true)); message("enabled");
        run("adminmode", "off"); modes.verify(() -> ClueAdminModeService.setEnabled(player, false)); verify(visual).clearViewer(player.getUniqueId());
        verify(visual, times(2)).refreshViewer(player); message("disabled");
        run("discordgate", "offline", "on"); message("No online player found");
        run("discordgate", "Iris", "on"); verify(playerManager).setDiscordGate(target, true); message("ON");
        run("discordgate", "Iris", "off"); verify(playerManager).setDiscordGate(target, false); message("OFF");
    }

    @Test void tutorialStrikeAndSpawnCommandsUseTheirDomainHandlers() {
        var tutorials = boundary(TutorialCommands.class); assertFalse(run("dismisspdwarning")); tutorials.verify(() -> TutorialCommands.dismiss(player, TutorialService.PERMADEATH_ZONE));
        String[] tutorial = {"tutorial", "dismiss"}; assertFalse(run(tutorial)); tutorials.verify(() -> TutorialCommands.handle(player, tutorial));
        var evil = boundary(EvilRpCommands.class); assertFalse(run("strikes")); evil.verify(() -> EvilRpCommands.handleOwnStrikes(player));
        var picker = boundary(ClassPickGui.class); assertTrue(run("class")); picker.verify(() -> ClassPickGui.open(player));
        assertTrue(run("subclass")); picker.verify(() -> ClassPickGui.openSubclasses(player));
        String[] spare = {"spare", "Iris"}; assertFalse(run(spare)); evil.verify(() -> EvilRpCommands.handleDecision(player, spare, StrikeChoice.SPARE));
        run("setworldspawn"); message("access"); admin(); var zones = boundary(PermadeathZoneLoader.class); run("setworldspawn");
        zones.verify(() -> PermadeathZoneLoader.saveWorldSpawn(player.getLocation())); message("world spawn set");
    }

    @Test void playerInjuryRequiresPermissionOneOnlineTargetAndDelegates() {
        var injury = boundary(RpInjureService.class); run("injure", "Iris"); message("cannot injure");
        grant("rpchar.injure", true); run("injure"); message("Usage:"); admin(); run("injure"); message("Staff force injure");
        run("injure", "offline"); message("No player found"); run("injure", "Iris"); injury.verify(() -> RpInjureService.begin(player, target));
    }

    @Test void traitAdministrationValidatesCharacterAndTraitThenSendsDomainMessages() {
        var traits = boundary(TraitLoader.class); var changes = boundary(TraitChangeService.class);
        Trait brave = mock(Trait.class), old = mock(Trait.class); when(brave.getId()).thenReturn("brave"); when(old.getId()).thenReturn("old");
        for (String verb : List.of("addtrait", "removetrait")) { run(verb, "Iris", "brave"); message("access"); }
        admin();
        for (String verb : List.of("addtrait", "removetrait")) {
            run(verb, "offline", "brave"); message("No player found"); run(verb, "Iris", "brave"); message("no active character");
            players.when(() -> PlayerManager.get(target)).thenReturn(null); run(verb, "Iris", "brave"); message("no active character");
            players.when(() -> PlayerManager.get(target)).thenReturn(data);
        }
        when(data.hasActiveCharacter()).thenReturn(true); run("addtrait", "Iris", "brave"); message("No trait found");
        traits.when(() -> TraitLoader.getByString("brave")).thenReturn(brave); when(character.getTraits()).thenReturn(List.of(old, brave));
        run("addtrait", "Iris", "brave"); message("already has the trait");
        when(character.getTraits()).thenReturn(List.of(old)); run("addtrait", "Iris", "brave"); changes.verify(() -> TraitChangeService.addTrait(target, character, brave));
        changes.verify(() -> TraitChangeService.sendGainedMessage(target, brave)); message("Added trait brave");
        run("removetrait", "Iris", "brave"); message("does not have the trait");
        when(character.getTraits()).thenReturn(List.of(old, brave)); run("removetrait", "Iris", "BRAVE");
        changes.verify(() -> TraitChangeService.removeTrait(target, character, brave)); changes.verify(() -> TraitChangeService.sendLostMessage(target, brave)); message("Removed trait brave");
    }

    @Test void clueViewsValidateCharactersAndPrintAllSavedAndAutomaticClues() {
        creator(player); run("clues"); message("busy creating"); CreationManager.activeCreators.clear();
        run("clues"); message("no active character"); when(data.hasActiveCharacter()).thenReturn(true);
        try (var inventories = mockConstruction(InventoryManager.class)) { run("clues"); verify(inventories.constructed().getFirst()).cluesView(player, character); }
        run("clues", "Iris"); message("access"); admin(); run("clues", "offline"); message("No player found");
        when(data.hasActiveCharacter()).thenReturn(false); run("clues", "Iris"); message("no active character");
        players.when(() -> PlayerManager.get(target)).thenReturn(null); run("clues", "Iris"); message("no active character"); players.when(() -> PlayerManager.get(target)).thenReturn(data);
        when(data.hasActiveCharacter()).thenReturn(true); when(character.getPlayerClues()).thenReturn(List.of("One", "Two"));
        boundary(ClueProgressFormatter.class).when(() -> ClueProgressFormatter.progressLine(character)).thenReturn("2/2");
        boundary(ClueGiver.class).when(() -> ClueGiver.getAutomaticClues(character)).thenReturn(List.of("Race"));
        run("clues", "Iris"); String text = messages(player); assertTrue(text.contains("Clues for Hero (Iris)"));
        assertTrue(text.contains("Progress: 2/2")); assertTrue(text.contains("1. One\n2. Two")); assertTrue(text.contains("automatic race clue"));
        run("clues", "Iris", "extra"); message("Error with command format");
    }

    @Test void ownClueViewWaitsUntilPlayerDataIsLoaded() {
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        try (var inventories = mockConstruction(InventoryManager.class)) {
            assertDoesNotThrow(() -> run("clues")); assertTrue(inventories.constructed().isEmpty());
            assertFalse(messages(player).isBlank(), "Unavailable data must receive a useful response");
        }
    }

    @Test void placingAndClearingCluesValidateArgumentsAndReportResults() {
        run("placeclue", "text"); message("access"); run("clearclues", "5"); message("access"); admin();
        run("placeclue"); message("Usage:"); run("placeclue", " "); message("cannot be empty");
        run("placeclue", " One", "two "); verify(placeClues).startAwaiting(player, "One two");
        run("clearclues"); message("Usage:"); run("clearclues", "many"); message("must be a number"); run("clearclues", "0"); message("greater than 0");
        SpawnedClueManager clues = mock(SpawnedClueManager.class); boundary(SpawnedClueManager.class).when(SpawnedClueManager::get).thenReturn(clues);
        when(clues.clearInRadius(player.getLocation(), 5)).thenReturn(3); run("clearclues", "5"); message("Removed 3 spawned clue(s) within 5.0 blocks");
    }

    @Test void nonFiniteClueRadiusCannotTriggerAnyDeletion() {
        admin(); SpawnedClueManager clues = mock(SpawnedClueManager.class); boundary(SpawnedClueManager.class).when(SpawnedClueManager::get).thenReturn(clues);
        for (String radius : List.of("NaN", "Infinity", "-Infinity")) { run("clearclues", radius); message("Radius"); }
        verifyNoInteractions(clues);
    }

    @Test void temporaryAliasesValidatePermissionAndInputAndReportDomainErrors() {
        var aliases = boundary(TempAliasService.class); run("tempalias", "name"); message("permission"); grant(Cache.personaTempaliasPermission, true);
        run("tempalias"); message("Usage:"); run("tempalias", "clear"); aliases.verify(() -> TempAliasService.clear(player)); message("Cleared your session");
        aliases.when(() -> TempAliasService.set(player, "bad name")).thenReturn("invalid alias"); run("tempalias", "bad", "name"); message("invalid alias");
        aliases.when(() -> TempAliasService.getPlain(player)).thenReturn("Good Name"); run("tempalias", "Good", "Name"); aliases.verify(() -> TempAliasService.set(player, "Good Name")); message("set to Good Name");
    }

    @Test void hiddenCharactersValidateOwnershipToggleAndPersist() {
        run("sethidden", "hero"); message("permission"); grant(Cache.personaCharacterHiddenPermission, true); run("sethidden"); message("Usage:");
        players.when(() -> PlayerManager.get(player)).thenReturn(null); run("sethidden", "hero"); message("not loaded"); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        run("sethidden", "missing"); message("No character with id missing"); when(data.getCharacterBySlug("hero")).thenReturn(character);
        final boolean[] hidden = {false}; when(character.isHidden()).thenAnswer(call -> hidden[0]); doAnswer(call -> { hidden[0] = call.getArgument(0); return null; }).when(character).setHidden(anyBoolean());
        run("sethidden", "hero"); assertTrue(hidden[0]); message("now hidden");
        run("sethidden", "hero"); assertFalse(hidden[0]); message("no longer hidden");
        hidden[0] = true; run("sethidden", "hero", "clear"); assertFalse(hidden[0]); message("no longer hidden"); verify(playerManager, times(3)).savePlayer(player);
    }

    PlayerCommandPreprocessEvent preprocess(String input) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, input); commands.onCommand(event); return event;
    }
    @Test void commandRestrictionAllowsAdminsCharactersOtherModesChatAndExactWhitelistedLabels() {
        Cache.requireCharacter = true; player.setGameMode(GameMode.SURVIVAL);
        admin(); assertFalse(preprocess("/other").isCancelled()); grant(Permissions.Permission_Admin, false);
        when(data.hasActiveCharacter()).thenReturn(true); assertFalse(preprocess("/other").isCancelled()); when(data.hasActiveCharacter()).thenReturn(false);
        Cache.requireCharacter = false; assertFalse(preprocess("/other").isCancelled()); Cache.requireCharacter = true;
        player.setGameMode(GameMode.CREATIVE); assertFalse(preprocess("/other").isCancelled()); player.setGameMode(GameMode.SURVIVAL);
        boundary(ChatLoader.class).when(ChatLoader::getChannelCommands).thenReturn(List.of("ooc"));
        for (String input : List.of("/ooc", " /ooc hello", "/rpcharacter clues", "/rpcharacter", "/rpcharacter menu", "/roll", "/roll 20"))
            assertFalse(preprocess(input).isCancelled(), input);
        assertTrue(preprocess("other").isCancelled()); message("cannot use other commands");
        assertTrue(preprocess("/other").isCancelled()); message("cannot use other commands");
    }

    @Test void lookalikeCommandNamesCannotBypassTheNoCharacterRestriction() {
        Cache.requireCharacter = true; player.setGameMode(GameMode.SURVIVAL);
        boundary(ChatLoader.class).when(ChatLoader::getChannelCommands).thenReturn(List.of());
        for (String input : List.of("/rpcharacterevil", "/rpcharacter-extra run", "/rollback"))
            assertTrue(preprocess(input).isCancelled(), input);
    }

    @Test void unloadedPlayerDataStillAppliesTheNoCharacterCommandRestriction() {
        Cache.requireCharacter = true; player.setGameMode(GameMode.SURVIVAL);
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        boundary(ChatLoader.class).when(ChatLoader::getChannelCommands).thenReturn(List.of());
        assertTrue(assertDoesNotThrow(() -> preprocess("/other")).isCancelled()); message("cannot use other commands");
        assertFalse(assertDoesNotThrow(() -> preprocess("/rpcharacter menu")).isCancelled());
        Cache.requireCharacter = false; assertFalse(assertDoesNotThrow(() -> preprocess("/other")).isCancelled());
    }
}
