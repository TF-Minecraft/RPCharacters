package net.tfminecraft.rpcharacters.classpick;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class ClassSkillPointsTest {
    ServerMock server; PlayerMock iris; MMOCore previousMmo; RPCharacter character; PlayerManager saves; PlayerData account;
    MockedStatic<PlayerManager> players; MockedStatic<ClassService> classes; final List<MockedStatic<?>> mocks = new ArrayList<>();
    final List<LogRecord> logs = new ArrayList<>();
    final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { logs.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); iris = server.addPlayer("Iris");
        previousMmo = MMOCore.plugin; MMOCore.plugin = mock(MMOCore.class); var manager = mock(ClassManager.class);
        Field field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, manager);
        when(manager.get("BLOODSWORN")).thenReturn(mock(PlayerClass.class));
        character = new RPCharacter(iris); character.setName("Hero");
        account = mock(PlayerData.class); when(account.getActiveCharacter()).thenReturn(character);
        players = mockStatic(PlayerManager.class); mocks.add(players); players.when(() -> PlayerManager.get(iris)).thenReturn(account);
        saves = mock(PlayerManager.class); when(saves.saveCharacterNow(any(), any())).thenReturn(true);
        var root = mockStatic(RPCharacters.class); mocks.add(root); root.when(RPCharacters::getPlayerManager).thenReturn(saves);
        classes = mockStatic(ClassService.class); mocks.add(classes);
        Logger.getLogger("RPCharacters").addHandler(handler);
    }

    @AfterEach void cleanup() {
        Logger.getLogger("RPCharacters").removeHandler(handler);
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        MMOCore.plugin = previousMmo; MockBukkit.unmock();
    }

    boolean logged(String text) { return logs.stream().anyMatch(record -> record.getMessage().contains(text)); }

    @Test void poolsAreSetPerCharacterSavedAndRefreshOnlyWhilePlayed() {
        assertEquals(-1, ClassSkillPoints.get(iris, "bloodsworn"));
        assertTrue(ClassSkillPoints.set(iris, "bloodsworn", 0));
        assertEquals(0, ClassSkillPoints.get(iris, "Bloodsworn"));
        verify(saves).saveCharacterNow(iris.getUniqueId(), character);
        classes.verify(() -> ClassService.refreshSkillPoints(any()), never());
        assertTrue(ClassSkillPoints.set(iris, "BLOODSWORN", 0), "The same total again is a success");
        verify(saves, times(1)).saveCharacterNow(any(), any());
        classes.when(() -> ClassService.isOnClass(iris, "BLOODSWORN")).thenReturn(true);
        assertTrue(ClassSkillPoints.set(iris, "bloodsworn", 8));
        assertEquals(8, character.getClassSkillPoints("BLOODSWORN").getAsInt());
        classes.verify(() -> ClassService.refreshSkillPoints(iris));
        assertTrue(ClassSkillPoints.set(iris, "bloodsworn", -2));
        assertEquals(0, ClassSkillPoints.get(iris, "bloodsworn"));
        assertFalse(ClassSkillPoints.set(iris, "missing", 1));
        assertFalse(ClassSkillPoints.set(iris, " ", 1));
        assertFalse(ClassSkillPoints.set(null, "bloodsworn", 1));
        assertEquals(-1, ClassSkillPoints.get(iris, null));
        character.setStatus(Status.DEAD);
        assertFalse(ClassSkillPoints.set(iris, "bloodsworn", 4));
        assertEquals(-1, ClassSkillPoints.get(iris, "bloodsworn"));
    }

    @Test void aFailedSaveWritesTheOldPoolBack() {
        when(saves.saveCharacterNow(iris.getUniqueId(), character)).thenReturn(false);
        assertFalse(ClassSkillPoints.set(iris, "bloodsworn", 4));
        assertTrue(character.getClassSkillPoints("BLOODSWORN").isEmpty());
        assertTrue(logged("BLOODSWORN skill points of Hero (" + character.getId() + ") stay shared."));
        character.setClassSkillPoints("BLOODSWORN", 2);
        assertFalse(ClassSkillPoints.set(iris, "bloodsworn", 6));
        assertEquals(2, character.getClassSkillPoints("BLOODSWORN").getAsInt());
        assertTrue(logged("stay 2."));
        verify(saves, times(4)).saveCharacterNow(iris.getUniqueId(), character);
        classes.verify(() -> ClassService.refreshSkillPoints(any()), never());
    }
}
