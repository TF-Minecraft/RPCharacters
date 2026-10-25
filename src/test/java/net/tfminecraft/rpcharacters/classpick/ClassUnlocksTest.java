package net.tfminecraft.rpcharacters.classpick;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class ClassUnlocksTest {
    ServerMock server; PlayerMock iris, staff; MMOCore previousMmo; PlayerData account; RPCharacter character; PlayerManager saves;
    MockedStatic<PlayerManager> players; final List<MockedStatic<?>> mocks = new ArrayList<>();
    final Map<String, PlayerClass> classMap = new HashMap<>();
    final List<LogRecord> logs = new ArrayList<>();
    final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { logs.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); iris = server.addPlayer("Iris"); staff = server.addPlayer("Staff");
        previousMmo = MMOCore.plugin; MMOCore.plugin = mock(MMOCore.class); var classes = mock(ClassManager.class);
        Field field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, classes);
        when(classes.get(anyString())).thenAnswer(call -> classMap.get(call.<String>getArgument(0)));
        var blood = mock(PlayerClass.class); when(blood.getId()).thenReturn("BLOOD_MAGE"); when(blood.getName()).thenReturn("&4Blood Mage");
        classMap.put("BLOOD_MAGE", blood);
        character = new RPCharacter(iris); character.setName("Hero");
        account = mock(PlayerData.class); when(account.getActiveCharacter()).thenReturn(character);
        players = mockStatic(PlayerManager.class); mocks.add(players); players.when(() -> PlayerManager.get(iris)).thenReturn(account);
        saves = mock(PlayerManager.class); when(saves.saveCharacterNow(any(), any())).thenReturn(true);
        var root = mockStatic(RPCharacters.class); mocks.add(root); root.when(RPCharacters::getPlayerManager).thenReturn(saves);
        Logger.getLogger("RPCharacters").addHandler(handler);
    }

    @AfterEach void cleanup() {
        Logger.getLogger("RPCharacters").removeHandler(handler);
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        MMOCore.plugin = previousMmo; MockBukkit.unmock();
    }

    boolean run(String... args) { return ClassUnlockCommand.handle(staff, args, 2); }

    String messages() {
        List<String> out = new ArrayList<>(); String message;
        while ((message = staff.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        return String.join("\n", out);
    }

    boolean logged(String text) { return logs.stream().anyMatch(record -> record.getMessage().contains(text)); }

    @Test void unlocksBelongToTheActiveCharacterAreSavedAndLockingKeepsTheCurrentClass() {
        assertTrue(ClassUnlocks.unlocked(iris).isEmpty());
        assertTrue(ClassUnlocks.unlock(iris, " blood-mage "));
        assertEquals(Set.of("BLOOD_MAGE"), ClassUnlocks.unlocked(iris)); assertEquals(Set.of("BLOOD_MAGE"), character.getUnlockedClasses());
        assertTrue(ClassUnlocks.isUnlocked(iris, "Blood Mage")); assertFalse(ClassUnlocks.isUnlocked(iris, null));
        verify(saves).saveCharacterNow(iris.getUniqueId(), character);
        assertTrue(logged("Unlocked class BLOOD_MAGE for Hero (" + character.getId() + ") of Iris."));
        assertTrue(ClassUnlocks.unlock(iris, "BLOOD_MAGE"), "Unlocking again is a success"); verify(saves, times(1)).saveCharacterNow(any(), any());
        assertThrows(UnsupportedOperationException.class, () -> ClassUnlocks.unlocked(iris).add("OTHER"));
        assertFalse(ClassUnlocks.unlock(iris, "missing")); assertFalse(ClassUnlocks.unlock(iris, " ")); assertFalse(ClassUnlocks.unlock(iris, null));
        character.setMMOClass("blood_mage");
        assertTrue(ClassUnlocks.lock(iris, "blood_mage")); assertTrue(ClassUnlocks.unlocked(iris).isEmpty());
        assertEquals("BLOOD_MAGE", character.getMMOClass()); assertTrue(logged("Locked class BLOOD_MAGE"));
        assertTrue(ClassUnlocks.lock(iris, "never-unlocked")); assertFalse(ClassUnlocks.lock(iris, null));
        verify(saves, times(2)).saveCharacterNow(any(), any());
    }

    @Test void withoutAnActiveLivingCharacterNothingIsUnlockedOrSaved() {
        assertFalse(ClassUnlocks.unlock(null, "BLOOD_MAGE"));
        character.addUnlockedClass("BLOOD_MAGE"); character.setStatus(Status.DEAD);
        assertFalse(ClassUnlocks.unlock(iris, "BLOOD_MAGE")); assertFalse(ClassUnlocks.lock(iris, "BLOOD_MAGE"));
        assertTrue(ClassUnlocks.unlocked(iris).isEmpty()); assertFalse(ClassUnlocks.isUnlocked(iris, "BLOOD_MAGE"));
        when(account.getActiveCharacter()).thenReturn(null); assertFalse(ClassUnlocks.unlock(iris, "BLOOD_MAGE"));
        players.when(() -> PlayerManager.get(iris)).thenReturn(null); assertFalse(ClassUnlocks.lock(iris, "BLOOD_MAGE"));
        verify(saves, never()).saveCharacterNow(any(), any());
    }

    @Test void aFailedSaveWritesTheOldStateBackAndReportsFailure() {
        List<Set<String>> written = new ArrayList<>();
        when(saves.saveCharacterNow(iris.getUniqueId(), character)).thenAnswer(call -> { written.add(Set.copyOf(character.getUnlockedClasses())); return false; });
        assertFalse(ClassUnlocks.unlock(iris, "blood_mage")); assertTrue(character.getUnlockedClasses().isEmpty());
        assertEquals(List.of(Set.of("BLOOD_MAGE"), Set.of()), written); assertTrue(logged("BLOOD_MAGE for Hero (" + character.getId() + ") of Iris stays locked."));
        character.addUnlockedClass("BLOOD_MAGE"); written.clear();
        assertFalse(ClassUnlocks.lock(iris, "blood_mage")); assertEquals(Set.of("BLOOD_MAGE"), character.getUnlockedClasses());
        assertEquals(List.of(Set.of(), Set.of("BLOOD_MAGE")), written); assertTrue(logged("stays unlocked."));
    }

    @Test void staffCommandsUnlockAndLockWithClearReplies() {
        assertTrue(ClassUnlockCommand.isSubcommand("UnlockClass")); assertTrue(ClassUnlockCommand.isSubcommand("lockclass")); assertFalse(ClassUnlockCommand.isSubcommand("prison"));
        assertTrue(run("admin", "unlockclass", "Iris")); assertEquals("Usage: /rpcharacter admin unlockclass <player> <classId>", messages());
        assertTrue(run("admin", "LOCKCLASS", "Iris", "a", "b")); assertEquals("Usage: /rpcharacter admin lockclass <player> <classId>", messages());
        assertTrue(run("admin", "unlockclass", "Nobody", "blood_mage")); assertEquals("No online player with an active living character named Nobody.", messages());
        assertTrue(run("admin", "unlockclass", "Iris", "nothing")); assertEquals("No class by the id NOTHING.", messages());
        assertTrue(run("admin", "unlockclass", "Iris", "blood-mage")); assertEquals("Unlocked Blood Mage for Hero (Iris). They can pick it in /class.", messages());
        assertEquals(Set.of("BLOOD_MAGE"), character.getUnlockedClasses());
        assertTrue(run("admin", "unlockclass", "Iris", "BLOOD_MAGE")); assertEquals("Blood Mage is already unlocked for Hero (Iris).", messages());
        assertTrue(run("admin", "lockclass", "Iris", "blood_mage")); assertEquals("Locked Blood Mage for Hero (Iris). Their current class did not change.", messages());
        assertTrue(run("admin", "lockclass", "Iris", "gone")); assertEquals("GONE is already locked for Hero (Iris).", messages());
        when(saves.saveCharacterNow(any(), any())).thenReturn(false);
        assertTrue(run("admin", "unlockclass", "Iris", "blood_mage")); assertEquals("Could not save Hero, so nothing changed. See the console.", messages());
        assertTrue(character.getUnlockedClasses().isEmpty()); assertNull(iris.nextMessage(), "Hidden unlocks are not announced to the player");
    }
}
