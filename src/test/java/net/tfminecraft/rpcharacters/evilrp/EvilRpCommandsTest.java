package net.tfminecraft.rpcharacters.evilrp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.pvp.PvpStrikeService;
import net.tfminecraft.rpcharacters.pvp.StrikeChoice;
import org.junit.jupiter.api.Test;

class EvilRpCommandsTest extends EvilRpFixture {
    void command(String... args) { assertTrue(EvilRpCommands.handleAdmin(admin, args, 0)); }

    @Test void ownSummaryRequiresActiveDataAndReportsRunningOrIdleSession() {
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertTrue(EvilRpCommands.handleOwnStrikes(player)); assertTrue(message(player).contains("active character"));
        players.when(() -> PlayerManager.get(player)).thenReturn(data); doReturn(false).when(character).isActive();
        EvilRpCommands.handleOwnStrikes(player); assertTrue(message(player).contains("active character"));
        doReturn(true).when(character).isActive(); character.setEvilRpStrikes(2);
        EvilRpCommands.handleOwnStrikes(player); assertTrue(message(player).contains("2/3")); assertTrue(message(player).contains("No evil RP"));
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() + 60_000);
        EvilRpCommands.handleOwnStrikes(player); message(player); assertTrue(message(player).contains("left"));
    }

    @Test void decisionButtonsResolveUuidOnlineNameAndUnknownName() {
        assertTrue(EvilRpCommands.handleDecision(admin, new String[]{"spare"}, StrikeChoice.SPARE));
        assertTrue(message(admin).contains("Use the buttons")); pvp.verifyNoInteractions();
        var id = UUID.randomUUID(); EvilRpCommands.handleDecision(admin, new String[]{"strike", id.toString()}, StrikeChoice.STRIKE);
        pvp.verify(() -> PvpStrikeService.choose(admin, id, StrikeChoice.STRIKE));
        EvilRpCommands.handleDecision(admin, new String[]{"spare", "Subject"}, StrikeChoice.SPARE);
        pvp.verify(() -> PvpStrikeService.choose(admin, player.getUniqueId(), StrikeChoice.SPARE));
        EvilRpCommands.handleDecision(admin, new String[]{"kill", "missing"}, StrikeChoice.STRIKE);
        pvp.verify(() -> PvpStrikeService.choose(admin, null, StrikeChoice.STRIKE));
    }

    @Test void adminSyntaxOfflineAndUnloadedTargetsProduceFeedback() {
        command("view"); assertTrue(message(admin).contains(EvilRpCommands.ADMIN_USAGE));
        command("view", "missing"); assertTrue(message(admin).contains("No player"));
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        command("view", "Subject"); assertTrue(message(admin).contains("not loaded"));
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
        command("unknown", "Subject"); assertTrue(message(admin).contains(EvilRpCommands.ADMIN_USAGE));
        assertTrue(EvilRpCommands.handleAdmin(admin, new String[]{"admin", "strikes", "view", "Subject"}, 2));
        assertTrue(message(admin).contains("Aria (Subject)")); message(admin);
    }

    @Test void characterSelectorsResolveSlugIdAndExactNameWithoutCrossingAccounts() {
        var second = new RPCharacter(player); second.setName("Second Hero"); data.addCharacter(second);
        var unnamed = new RPCharacter(player); data.addCharacter(unnamed);
        for (String query : List.of(second.getSlug(), second.getId(), "SECOND HERO")) {
            command("view", "Subject", query); assertTrue(message(admin).contains("Second Hero")); message(admin);
        }
        command("view", "Subject", "missing"); assertTrue(message(admin).contains("No character found"));
        doReturn(false).when(character).isActive(); command("view", "Subject"); assertTrue(message(admin).contains("no active character"));
    }

    @Test void normalAddsReportEachEscalatingOutcome() {
        for (String expected : List.of("healing injury", "permanent injury", "character killed")) {
            command("add", "Subject"); assertTrue(message(admin).contains(expected));
        }
        assertEquals(3, character.getEvilRpStrikes()); verify(manager, times(3)).savePlayer(player);
    }

    @Test void quietAddsApplyDecayAndPersistWithoutInjuryOrDeath() {
        enableDecay(1000); character.setEvilRpStrikes(2); character.setLastStrikeAtMs(System.currentTimeMillis() - 10_000);
        command("add", "Subject", "quiet"); assertEquals(1, character.getEvilRpStrikes());
        assertTrue(message(admin).contains("without any injury or death")); assertNull(player.nextMessage());
        injuries.verifyNoInteractions(); verify(manager).savePlayer(player);
        character.setStatus(Status.DEAD); command("add", "Subject"); assertTrue(message(admin).contains("not alive"));
        command("add", "Subject", "missing"); assertTrue(message(admin).contains("No character found"));
    }

    @Test void setValidatesCountsAndResetsAgeOnlyForPositiveCounts() {
        command("set", "Subject"); assertTrue(message(admin).contains("Usage"));
        command("set", "Subject", "text"); assertTrue(message(admin).contains("must be a number"));
        for (String bad : List.of("-1", "3")) { command("set", "Subject", bad); assertTrue(message(admin).contains("between 0 and 2")); }
        command("set", "Subject", "2", "missing"); assertTrue(message(admin).contains("No character found"));
        character.setLastStrikeAtMs(1234); long before = System.currentTimeMillis();
        command("set", "Subject", "2"); assertEquals(2, character.getEvilRpStrikes());
        assertTrue(character.getLastStrikeAtMs() >= before); assertTrue(message(admin).contains("Injuries were not changed"));
        long last = character.getLastStrikeAtMs(); command("set", "Subject", "0");
        assertEquals(0, character.getEvilRpStrikes()); assertEquals(last, character.getLastStrikeAtMs()); message(admin);
    }

    @Test void removalsPreserveExistingDecayAgeAndInitializeLegacyAge() {
        command("remove", "Subject", "missing"); assertTrue(message(admin).contains("No character found"));
        command("remove", "Subject"); assertTrue(message(admin).contains("no strikes"));
        character.setEvilRpStrikes(2); character.setLastStrikeAtMs(1234);
        command("remove", "Subject"); assertEquals(1, character.getEvilRpStrikes()); assertEquals(1234, character.getLastStrikeAtMs()); message(admin);
        character.setEvilRpStrikes(2); character.setLastStrikeAtMs(0);
        command("remove", "Subject"); assertEquals(1, character.getEvilRpStrikes()); assertTrue(character.getLastStrikeAtMs() > 0); message(admin);
        command("remove", "Subject"); assertEquals(0, character.getEvilRpStrikes()); message(admin);
    }

    @Test void staffCanStartAndEndSessionsButCannotStartDeadCharacters() {
        character.setStatus(Status.DEAD); command("startsession", "Subject"); assertTrue(message(admin).contains("no living active character"));
        character.setStatus(Status.ALIVE); command("startsession", "Subject");
        assertTrue(message(admin).contains("Started")); assertTrue(EvilRpService.isInSession(character)); message(player);
        command("endsession", "Subject", "missing"); assertTrue(message(admin).contains("No character found"));
        command("endsession", "Subject"); assertEquals(0, character.getEvilRpSessionEndsAtMs()); assertTrue(message(admin).contains("Ended"));
        verify(manager, times(2)).savePlayer(player);
    }

    @Test void summariesApplyElapsedDecayBeforeShowingStrikeCount() {
        enableDecay(1000); character.setEvilRpStrikes(2); character.setLastStrikeAtMs(System.currentTimeMillis() - 10_000);
        command("view", "Subject"); assertEquals(0, character.getEvilRpStrikes()); assertTrue(message(admin).contains("0/3"));
    }

    @Test void uppercaseAdminActionsUseLocaleIndependentDispatch() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        command("VIEW", "Subject"); assertTrue(message(admin).contains("Aria (Subject)"));
    }

    @Test void quietAddCannotWrapAnExcessivePersistedStrikeCountToZero() {
        character.setEvilRpStrikes(Integer.MAX_VALUE);
        command("add", "Subject", "quiet");
        assertEquals(Integer.MAX_VALUE, character.getEvilRpStrikes()); injuries.verifyNoInteractions();
    }
}
