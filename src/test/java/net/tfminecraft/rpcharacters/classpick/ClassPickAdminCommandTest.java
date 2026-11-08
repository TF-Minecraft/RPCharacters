package net.tfminecraft.rpcharacters.classpick;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeRule;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.paidchange.Payment;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;

class ClassPickAdminCommandTest {
    static final String STAGE = "class_selection_stage";
    @TempDir Path directory;
    ServerMock server; PlayerMock staff, target, classless; RuntimeTestState state; PlayerManager playerManager; RPCharacter character;
    MMOCore previousMmo;
    final List<MockedStatic<?>> mocks = new ArrayList<>();
    <T> MockedStatic<T> boundary(Class<T> type) { var result = mockStatic(type); mocks.add(result); return result; }

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, StageLoader.class, ClassPickReset.class, PaidChangeService.class);
        ClassPickService.configure(ClassPickService.Settings.DEFAULTS); previousMmo = MMOCore.plugin;
        MMOCore.plugin = mock(MMOCore.class); var classes = mock(ClassManager.class);
        var field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, classes);
        when(classes.getAll()).thenReturn(List.of());
        boundary(MmoCoreClassGuiHelper.class).when(() -> MmoCoreClassGuiHelper.buildClassOptions(anyInt(), anyMap()))
            .thenReturn(new MmoCoreClassGuiHelper.ClassGuiData(new ArrayList<>(), List.of()));
        RPCharacters.plugin = mock(RPCharacters.class); when(RPCharacters.plugin.getDataFolder()).thenReturn(directory.toFile());
        playerManager = mock(PlayerManager.class); boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(playerManager);
        boundary(DisplayIdentityService.class).when(() -> DisplayIdentityService.resolveCharacterName(any(RPCharacter.class))).thenReturn("Belisarius");
        new StageLoader().load(Files.writeString(directory.resolve("stages.yml"), """
            class_selection_stage: {type: SELECTION, target: class, lock-time: 5d, revision: 2}
            """).toFile());
        PaidChangeService.configure(List.of(new PaidChangeRule("class", STAGE, "class",
                List.of(new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("3000")))), List.of());
        staff = server.addPlayer("Staff"); target = server.addPlayer("Target"); classless = server.addPlayer("Classless");
        character = new RPCharacter(target); character.setMMOClass("paladin"); character.setCreatedAtEpochSeconds(1);
        character.setStageRevision(STAGE, 2, 5L); character.setSubclassPicked(true); character.setPaidChangeCount(STAGE, 2); character.setPaidClassPicks(2);
        var account = mock(PlayerData.class); when(account.getActiveCharacter()).thenReturn(character);
        var empty = mock(PlayerData.class);
        var players = boundary(PlayerManager.class);
        players.when(() -> PlayerManager.get(target)).thenReturn(account); players.when(() -> PlayerManager.get(classless)).thenReturn(empty);
    }

    @AfterEach void cleanup() {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        MMOCore.plugin = previousMmo; ClassPickService.configure(ClassPickService.Settings.DEFAULTS); state.close(); MockBukkit.unmock();
    }

    void grant() { staff.addAttachment(MockBukkit.createMockPlugin(), "rpchar.class.reset", true); }
    boolean run(String... args) {
        String[] full = new String[args.length + 2]; full[0] = "admin"; full[1] = "classpicks";
        System.arraycopy(args, 0, full, 2, args.length); return ClassPickAdminCommand.handle(staff, full, 2);
    }
    String messages(PlayerMock who) {
        List<String> out = new ArrayList<>(); String message;
        while ((message = who.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        return String.join("\n", out);
    }
    Stage stage() { return StageLoader.getById(STAGE); }

    @Test void needsTheResetPermissionAndAnOnlinePlayerWithACharacter() {
        assertTrue(run("Target", "reset")); assertTrue(messages(staff).contains("permission")); assertTrue(character.hasPickedSubclass());
        assertFalse(ClassPickAdminCommand.canUse(staff)); grant(); assertTrue(ClassPickAdminCommand.canUse(staff));
        assertTrue(run()); assertTrue(messages(staff).contains("Usage: /rpcharacter admin classpicks"));
        assertTrue(run("Target", "paid", "1", "extra")); assertTrue(messages(staff).contains("Usage"));
        assertTrue(run("Nobody")); assertTrue(messages(staff).contains("No online player called Nobody"));
        assertTrue(run("Classless")); assertTrue(messages(staff).contains("No active character for Classless"));
        assertTrue(run("Target", "wipe")); assertTrue(messages(staff).contains("Usage"));
        assertTrue(run("Target", "paid")); assertTrue(messages(staff).contains("Usage"));
        assertTrue(run("Target", "reset", "now")); assertTrue(messages(staff).contains("Usage"));
        verify(playerManager, never()).savePlayer(any());
    }

    @Test void withoutAnActionItShowsTheCharactersPickState() {
        grant(); assertTrue(run("Target")); String info = messages(staff);
        assertTrue(info.contains("Character Belisarius (Target): class PALADIN"), info);
        assertTrue(info.contains("Free class changes: no, window closed"), info);
        assertTrue(info.contains("Next subclass pick free: yes"), info);
        assertTrue(info.contains("Paid class changes: 2, next paid change costs 3,000 denars"), info);
        character.setMMOClass(null); character.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond());
        PaidChangeService.configure(List.of(), List.of());
        assertTrue(run("Target")); info = messages(staff);
        assertTrue(info.contains("class none"), info); assertTrue(info.contains("Free class changes: for "), info);
        assertTrue(info.contains("Class changes cost: 200 denars"), info);
        verify(playerManager, never()).savePlayer(any());
    }

    @Test void resetGivesOnlyThisCharacterAFreshWindowAndFreeSubclass() {
        grant(); long before = Instant.now().getEpochSecond(); assertFalse(StageEditLock.canEdit(stage(), character));
        assertTrue(run("Target", "RESET")); assertTrue(messages(staff).contains("can change class and subclass for free for 5d"));
        assertTrue(StageEditLock.canEdit(stage(), character)); assertTrue(character.getStageRevisionSince(STAGE) >= before);
        assertEquals(2, character.getStageRevision(STAGE)); assertEquals(0, character.getPaidChangeCount(STAGE)); assertFalse(character.hasPickedSubclass());
        assertEquals("PALADIN", character.getMMOClass(), "The class itself is kept"); assertEquals(2, character.getPaidClassPicks());
        verify(playerManager).savePlayer(target); assertTrue(messages(target).contains("Staff reset your class picks"));
        assertEquals(2, stage().getRevision(), "The stage revision for everyone else is untouched");
    }

    @Test void resetNeverLowersARevisionTheCharacterAlreadyReached() {
        grant(); character.setStageRevision(STAGE, 7, 5L);
        assertTrue(run("Target", "reset")); assertEquals(7, character.getStageRevision(STAGE));
        assertTrue(StageEditLock.canEdit(stage(), character));
        StageLoader.oList = new ArrayList<>(); assertTrue(run("Target", "reset")); assertTrue(messages(staff).contains("no class stage"));
    }

    @Test void freeSubclassOnlyClearsTheUsedSubclass() {
        grant(); assertTrue(run("Target", "freesubclass"));
        assertTrue(messages(staff).contains("gets its next subclass pick for free")); assertFalse(character.hasPickedSubclass());
        assertEquals(2, character.getPaidChangeCount(STAGE)); verify(playerManager).savePlayer(target);
        assertTrue(messages(target).contains("Staff made your next subclass pick free."));
    }

    @Test void paidSetsTheCountThatPicksTheNextPrice() {
        grant(); assertTrue(run("Target", "paid", "0"));
        assertTrue(messages(staff).contains("has 0 paid class changes. The next paid change costs 100 denars."));
        assertEquals(0, character.getPaidChangeCount(STAGE)); assertEquals("", messages(target), "A price change is not announced to the player");
        assertTrue(run("Target", "paid", "1")); assertEquals(1, character.getPaidChangeCount(STAGE)); assertTrue(messages(staff).contains("1,000 denars"));
        for (String bad : List.of("-1", "two", "1.5")) {
            assertTrue(run("Target", "paid", bad)); assertTrue(messages(staff).contains("whole number"));
        }
        assertEquals(1, character.getPaidChangeCount(STAGE)); verify(playerManager, times(2)).savePlayer(target);
        PaidChangeService.configure(List.of(), List.of()); assertTrue(run("Target", "paid", "0"));
        assertTrue(messages(staff).contains("no class paid-change rule")); assertEquals(1, character.getPaidChangeCount(STAGE));
    }

    @Test void aHeldPaymentBlocksChanges() {
        grant(); character.setPendingClassPick(new PendingClassPick("CRUSADER", Payment.legacy(UUID.randomUUID(), Account.BANK, 300_000L, "refund"), false));
        assertTrue(run("Target", "reset")); assertTrue(messages(staff).contains("still being settled"));
        assertTrue(character.hasPickedSubclass()); assertEquals(2, character.getPaidChangeCount(STAGE)); verify(playerManager, never()).savePlayer(any());
    }
}
