package net.tfminecraft.rpcharacters.classpick;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageRevisions;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class ClassPickResetTest {
    @TempDir Path directory;
    ServerMock server; PlayerMock staff, stale, current, loading; RuntimeTestState state; PlayerManager playerManager;
    RPCharacter staleCharacter, currentCharacter;
    final List<MockedStatic<?>> mocks = new ArrayList<>();
    <T> MockedStatic<T> boundary(Class<T> type) { var result = mockStatic(type); mocks.add(result); return result; }

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, StageLoader.class, ClassPickReset.class);
        ClassPickService.configure(ClassPickService.Settings.DEFAULTS);
        boundary(MmoCoreClassGuiHelper.class).when(() -> MmoCoreClassGuiHelper.buildClassOptions(anyInt(), anyMap()))
            .thenReturn(new MmoCoreClassGuiHelper.ClassGuiData(new ArrayList<>(), List.of()));
        RPCharacters.plugin = mock(RPCharacters.class); when(RPCharacters.plugin.getDataFolder()).thenReturn(directory.toFile());
        playerManager = mock(PlayerManager.class); boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(playerManager);
        staff = server.addPlayer("Staff"); stale = server.addPlayer("Stale"); current = server.addPlayer("Current"); loading = server.addPlayer("Loading");
        loadStages(0);
        staleCharacter = character(stale, 0); currentCharacter = character(current, 2);
        staleCharacter.setSubclassPicked(true); staleCharacter.setPaidChangeCount("class_selection_stage", 2); staleCharacter.setPaidChangeCount("intro", 1);
        currentCharacter.setSubclassPicked(true);
        var staleAccount = account(staleCharacter); var currentAccount = account(currentCharacter); var staffAccount = account();
        var players = boundary(PlayerManager.class);
        players.when(() -> PlayerManager.get(stale)).thenReturn(staleAccount);
        players.when(() -> PlayerManager.get(current)).thenReturn(currentAccount);
        players.when(() -> PlayerManager.get(staff)).thenReturn(staffAccount);
        players.when(() -> PlayerManager.get(loading)).thenReturn(null);
    }

    @AfterEach void cleanup() {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        ClassPickService.configure(ClassPickService.Settings.DEFAULTS); state.close(); MockBukkit.unmock();
    }

    void loadStages(int resets) throws Exception {
        if (resets > 0) {
            Files.createDirectories(directory.resolve("data"));
            Files.writeString(directory.resolve(ClassPickReset.FILE), "resets: " + resets + "\n");
        }
        new StageLoader().load(Files.writeString(directory.resolve("stages.yml"), """
            intro: {type: INFO, interval: 20, revision: 3}
            class_selection_stage: {type: SELECTION, target: class, lock-time: 5d, revision: 2}
            """).toFile());
    }

    RPCharacter character(PlayerMock owner, int revision) {
        var character = new RPCharacter(owner); character.setCreatedAtEpochSeconds(1);
        character.setStageRevision("class_selection_stage", revision, 5L); character.setStageRevision("intro", 3, 5L);
        return character;
    }

    PlayerData account(RPCharacter... characters) {
        var account = mock(PlayerData.class); when(account.getCharacters()).thenReturn(List.of(characters)); return account;
    }

    void grant() { staff.addAttachment(MockBukkit.createMockPlugin(), "rpchar.class.reset", true); }
    boolean run(String... args) { return ClassPickReset.handle(staff, args, 2); }
    String messages() {
        List<String> out = new ArrayList<>(); String message;
        while ((message = staff.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        return String.join("\n", out);
    }

    @Test void savedResetsRaiseOnlyTheClassStageRevisionOnLoad() throws Exception {
        assertEquals(2, StageLoader.getById("class_selection_stage").getRevision()); assertEquals(3, StageLoader.getById("intro").getRevision());
        loadStages(4);
        assertEquals(6, StageLoader.getById("class_selection_stage").getRevision()); assertEquals(3, StageLoader.getById("intro").getRevision());
        Files.writeString(directory.resolve(ClassPickReset.FILE), "resets: -2\n"); loadStages(0);
        assertEquals(2, StageLoader.getById("class_selection_stage").getRevision());
    }

    @Test void resetNeedsTheConfiguredPermissionAndAClassStage() {
        assertTrue(run("admin", "resetclasses", "confirm")); assertTrue(messages().contains("permission"));
        assertFalse(ClassPickReset.canUse(staff)); grant(); assertTrue(ClassPickReset.canUse(staff));
        ClassPickService.configure(new ClassPickService.Settings(true, false, ClassPickService.Settings.DEFAULTS.changeCost(),
            ClassPickService.Settings.DEFAULTS.accounts(), ClassPickService.Settings.DEFAULTS.commands(), "other.permission"));
        assertFalse(ClassPickReset.canUse(staff));
        ClassPickService.configure(ClassPickService.Settings.DEFAULTS);
        StageLoader.oList = new ArrayList<>(List.of(StageLoader.getById("intro")));
        assertTrue(run("admin", "resetclasses", "confirm")); assertTrue(messages().contains("no class stage"));
        assertFalse(Files.exists(directory.resolve(ClassPickReset.FILE)));
    }

    @Test void resetAsksForConfirmationAndNamesTheWindow() {
        grant();
        assertTrue(run("admin", "resetclasses")); String prompt = messages();
        assertTrue(prompt.contains("free for 5d")); assertTrue(prompt.contains("/rpcharacter admin resetclasses confirm"));
        StageLoader.getById("class_selection_stage").setLockTimeMs(-1);
        assertTrue(run("admin", "resetclasses", "now")); assertTrue(messages().contains("free always"));
        assertFalse(Files.exists(directory.resolve(ClassPickReset.FILE))); assertEquals(2, staleCharacter.getPaidChangeCount("class_selection_stage"));
    }

    @Test void confirmedResetOpensAFreshClassWindowForOnlineCharactersAndPersists() throws Exception {
        grant(); long before = Instant.now().getEpochSecond();
        assertTrue(run("admin", "RESETCLASSES", "CONFIRM"));
        assertTrue(messages().contains("Class picks reset. 2 online characters"));
        assertEquals(1, YamlConfiguration.loadConfiguration(directory.resolve(ClassPickReset.FILE).toFile()).getInt("resets"));
        Stage stage = StageLoader.getById("class_selection_stage"); assertEquals(3, stage.getRevision());
        for (RPCharacter character : List.of(staleCharacter, currentCharacter)) {
            assertEquals(3, character.getStageRevision("class_selection_stage"));
            assertTrue(character.getStageRevisionSince("class_selection_stage") >= before);
            assertFalse(character.hasPickedSubclass()); assertEquals(0, character.getPaidChangeCount("class_selection_stage"));
        }
        assertEquals(1, staleCharacter.getPaidChangeCount("intro")); assertEquals(5L, staleCharacter.getStageRevisionSince("intro"));
        verify(playerManager).savePlayer(stale); verify(playerManager).savePlayer(current); verify(playerManager, never()).savePlayer(staff);
        assertTrue(StageRevisions.secondsIntoWindow(stage, staleCharacter) < 60);

        assertTrue(run("admin", "resetclasses", "confirm")); assertTrue(messages().contains("2 online characters"));
        loadStages(0); assertEquals(4, StageLoader.getById("class_selection_stage").getRevision());
        assertFalse(StageRevisions.refresh(staleCharacter, StageLoader.oList));
    }

    @Test void unchangedCharactersAreNotSavedAgain() {
        grant(); staleCharacter.setStageRevision("class_selection_stage", 9, 5L); currentCharacter.setStageRevision("class_selection_stage", 9, 5L);
        assertTrue(run("admin", "resetclasses", "confirm")); assertTrue(messages().contains("Class picks reset. 0 online characters"));
        verify(playerManager, never()).savePlayer(any());
        assertTrue(staleCharacter.hasPickedSubclass());
    }

    @Test void aResetThatCannotBeSavedChangesNothing() throws Exception {
        grant(); Files.writeString(directory.resolve("data"), "not a folder");
        assertTrue(run("admin", "resetclasses", "confirm")); assertTrue(messages().contains("Could not save data/class-resets.yml"));
        assertEquals(2, StageLoader.getById("class_selection_stage").getRevision());
        assertTrue(staleCharacter.hasPickedSubclass()); verify(playerManager, never()).savePlayer(any());
        assertTrue(new File(directory.toFile(), "data").isFile());
    }
}
