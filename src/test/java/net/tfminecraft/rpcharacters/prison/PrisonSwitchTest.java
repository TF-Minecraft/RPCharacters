package net.tfminecraft.rpcharacters.prison;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class PrisonSwitchTest {
    final PlayerData account = new PlayerData(UUID.randomUUID());
    RPCharacter a, b, c;

    RPCharacter character(String id, String name) {
        RPCharacter character = mock(RPCharacter.class); when(character.getId()).thenReturn(id); when(character.getName()).thenReturn(name);
        when(character.getStatus()).thenReturn(Status.ALIVE);
        doAnswer(call -> { when(character.isPrisonSwitch()).thenReturn(call.getArgument(0)); return null; }).when(character).setPrisonSwitch(anyBoolean());
        return character;
    }

    @BeforeEach void roster() {
        a = character("a", "Aria"); b = character("b", "Bram"); c = character("c", "Cora");
        account.addCharacter(a); account.addCharacter(b); account.addCharacter(c);
    }

    @Test void unmarkedCharactersKeepTheCooldownAndResumingIsFree() {
        assertTrue(PrisonSwitch.cooldownApplies(account, "a", b)); assertFalse(PrisonSwitch.cooldownApplies(account, "a", a));
        assertFalse(PrisonSwitch.cooldownApplies(account, null, b));
        when(a.getStatus()).thenReturn(Status.DEAD); assertFalse(PrisonSwitch.cooldownApplies(account, "a", b));
        assertFalse(PrisonSwitch.cooldownApplies(account, "gone", b));
        PrisonSwitch.entered(account, null, a); assertNull(account.getLastCharacterSwitchAtMs()); assertEquals("a", account.getLastFreeCharacterId());
        PrisonSwitch.entered(account, "a", a); assertNull(account.getLastCharacterSwitchAtMs());
        PrisonSwitch.entered(account, "a", b); assertNotNull(account.getLastCharacterSwitchAtMs()); assertEquals("b", account.getLastFreeCharacterId());
    }

    @Test void idsMatchWhateverCaseTheyWereStoredIn() {
        RPCharacter upper = character("D1", "Dara"); account.addCharacter(upper);
        assertFalse(PrisonSwitch.cooldownApplies(account, "d1", upper)); assertFalse(PrisonSwitch.cooldownApplies(account, "a", a));
        account.setLastFreeCharacterId("d1"); assertFalse(PrisonSwitch.cooldownApplies(account, "b", upper));
        PrisonSwitch.entered(account, "d1", upper); PrisonSwitch.entered(account, "b", upper); assertNull(account.getLastCharacterSwitchAtMs());
    }

    @Test void markedCharacterIsEnteredAndLeftFreelyWithoutBridgingTwoOthers() {
        b.setPrisonSwitch(true);
        assertFalse(PrisonSwitch.cooldownApplies(account, "a", b));
        PrisonSwitch.entered(account, "a", b);
        assertNull(account.getLastCharacterSwitchAtMs()); assertEquals("a", account.getLastFreeCharacterId());
        assertFalse(PrisonSwitch.cooldownApplies(account, "b", a)); assertTrue(PrisonSwitch.cooldownApplies(account, "b", c));
        PrisonSwitch.entered(account, "b", a); assertNull(account.getLastCharacterSwitchAtMs());
        PrisonSwitch.entered(account, "a", b); PrisonSwitch.entered(account, "b", c);
        assertNotNull(account.getLastCharacterSwitchAtMs()); assertEquals("c", account.getLastFreeCharacterId());
    }

    @Test void releasedPrisonerResumesAndReturnsFreelyButStillWaitsForAThirdCharacter() {
        b.setPrisonSwitch(true); PrisonSwitch.entered(account, "a", b); b.setPrisonSwitch(false);
        assertFalse(PrisonSwitch.cooldownApplies(account, "b", b)); assertFalse(PrisonSwitch.cooldownApplies(account, "b", a));
        assertTrue(PrisonSwitch.cooldownApplies(account, "b", c));
        PrisonSwitch.entered(account, "b", b); assertEquals("a", account.getLastFreeCharacterId()); assertNull(account.getLastCharacterSwitchAtMs());
    }

    @Test void markingTheCurrentCharacterLetsThePlayerLeaveIt() {
        PrisonSwitch.entered(account, null, a); a.setPrisonSwitch(true);
        assertFalse(PrisonSwitch.cooldownApplies(account, "a", b));
        PrisonSwitch.entered(account, "a", b); assertNull(account.getLastCharacterSwitchAtMs()); assertEquals("b", account.getLastFreeCharacterId());
    }

    @Test void accountsFromBeforeTheMarkCountFromTheirLastCharacter() {
        b.setPrisonSwitch(true);
        assertTrue(PrisonSwitch.cooldownApplies(account, "a", c)); assertFalse(PrisonSwitch.cooldownApplies(account, "b", c));
        PrisonSwitch.entered(account, "a", b); assertEquals("a", account.getLastFreeCharacterId());
        PlayerData fresh = new PlayerData(UUID.randomUUID()); fresh.addCharacter(b);
        PrisonSwitch.entered(fresh, null, b); assertNull(fresh.getLastFreeCharacterId());
        PlayerData marked = new PlayerData(UUID.randomUUID()); marked.addCharacter(b); marked.addCharacter(c);
        PrisonSwitch.entered(marked, "b", c); assertNull(marked.getLastCharacterSwitchAtMs()); assertEquals("c", marked.getLastFreeCharacterId());
    }

    @Nested class Command {
        ServerMock server; PlayerMock staff, iris; PlayerManager players; MockedStatic<RPCharacters> root; final List<MockedStatic<?>> mocks = new ArrayList<>();

        @BeforeEach void setup() {
            server = MockBukkit.mock(); staff = server.addPlayer("Staff"); iris = server.addPlayer("Iris"); server.addPlayer("Loading");
            players = mock(PlayerManager.class); when(players.saveCharacterNow(any(), any())).thenReturn(true);
            root = mockStatic(RPCharacters.class); mocks.add(root); root.when(RPCharacters::getPlayerManager).thenReturn(players);
            var data = mockStatic(PlayerManager.class); mocks.add(data); data.when(() -> PlayerManager.get(iris)).thenReturn(account);
            when(a.getSlug()).thenReturn("aria");
        }
        @AfterEach void cleanup() { for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close(); MockBukkit.unmock(); }

        void grant(String permission) { staff.addAttachment(MockBukkit.createMockPlugin(), permission, true); }
        boolean run(String... args) { return PrisonSwitch.handle(staff, args, 2); }
        String messages(PlayerMock player) {
            List<String> out = new ArrayList<>(); String message;
            while ((message = player.nextMessage()) != null) out.add(ChatColor.stripColor(message));
            return String.join("\n", out);
        }

        @Test void staffNeedThePrisonOrAdminPermission() {
            assertFalse(PrisonSwitch.canUse(staff)); assertTrue(run("admin", "prison", "Iris")); assertTrue(messages(staff).contains("permission"));
            grant(PrisonSwitch.PERMISSION); assertTrue(PrisonSwitch.canUse(staff));
            PlayerMock admin = server.addPlayer("Admin"); admin.addAttachment(MockBukkit.createMockPlugin(), Permissions.Permission_Admin, true);
            assertTrue(PrisonSwitch.canUse(admin));
        }

        @Test void badTargetsExplainTheUsageAndChangeNothing() {
            grant(PrisonSwitch.PERMISSION);
            for (String[] args : List.of(new String[] {"admin", "prison"},
                    new String[] {"admin", "prison", "Iris", "aria", "extra"}, new String[] {"admin", "prison", "Iris", "aria", "x", "on"})) {
                assertTrue(run(args)); assertTrue(messages(staff).contains("Usage: " + PrisonSwitch.USAGE));
            }
            assertTrue(run("admin", "prison", "Nobody")); assertTrue(messages(staff).contains("No online player with loaded characters named Nobody"));
            assertTrue(run("admin", "prison", "Loading")); assertTrue(messages(staff).contains("named Loading"));
            assertTrue(run("admin", "prison", "Iris")); assertTrue(messages(staff).contains("No active character for Iris"));
            assertTrue(run("admin", "prison", "Iris", "zed")); assertTrue(messages(staff).contains("No living character matching zed for Iris"));
            when(c.getStatus()).thenReturn(Status.DEAD);
            assertTrue(run("admin", "prison", "Iris", "Cora", "on")); assertTrue(messages(staff).contains("No living character matching Cora"));
            verify(c, never()).setPrisonSwitch(anyBoolean()); verify(players, never()).saveCharacterNow(any(), any());
        }

        @Test void markFlipsOrIsSetExplicitlyAndBothSidesAreTold() {
            grant(PrisonSwitch.PERMISSION); when(a.isActive()).thenReturn(true);
            assertTrue(run("admin", "prison", "Iris"));
            assertTrue(a.isPrisonSwitch()); assertTrue(messages(staff).contains("Prison switching is on for Aria (Iris)"));
            assertTrue(messages(iris).contains("Aria is in prison"));
            assertTrue(run("admin", "prison", "Iris", "ON")); assertTrue(a.isPrisonSwitch()); messages(staff); messages(iris);
            assertTrue(run("admin", "prison", "Iris", "aria")); assertFalse(a.isPrisonSwitch());
            assertTrue(messages(staff).contains("Prison switching is off for Aria")); assertTrue(messages(iris).contains("Aria is out of prison"));
            assertTrue(run("admin", "prison", "Iris", "Bram", "on")); assertTrue(b.isPrisonSwitch()); messages(staff);
            assertTrue(run("admin", "prison", "Iris", "b", "off")); assertFalse(b.isPrisonSwitch());
            verify(players, times(3)).saveCharacterNow(iris.getUniqueId(), a); verify(players, times(2)).saveCharacterNow(iris.getUniqueId(), b);
        }

        @Test void aFailedSaveKeepsTheOldMarkAndSaysSo() {
            grant(PrisonSwitch.PERMISSION); when(players.saveCharacterNow(iris.getUniqueId(), a)).thenReturn(false);
            assertTrue(run("admin", "prison", "Iris", "aria", "on"));
            assertFalse(a.isPrisonSwitch()); assertTrue(messages(staff).contains("Could not save Aria, so prison switching was not changed"));
            assertNull(iris.nextMessage());
        }

        @Test void withCharacterBodiesThePlayerIsToldThroughSelectionFeedback() {
            grant(PrisonSwitch.PERMISSION); var bodies = mock(net.tfminecraft.rpcharacters.body.CharacterBodies.class);
            root.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
            assertTrue(run("admin", "prison", "Iris", "aria", "on"));
            verify(bodies).feedback(eq(iris), argThat(m -> ChatColor.stripColor(m).equals("Aria is in prison. You can switch to and from them at any time without the character switch cooldown.")));
            assertTrue(run("admin", "prison", "Iris", "aria", "off"));
            verify(bodies).feedback(eq(iris), argThat(m -> ChatColor.stripColor(m).startsWith("Aria is out of prison.")));
            assertNull(iris.nextMessage());
        }
    }
}
