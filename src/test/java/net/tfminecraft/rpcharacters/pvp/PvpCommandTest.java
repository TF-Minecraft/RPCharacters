package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import org.bukkit.command.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;

class PvpCommandTest extends PvpRuntimeFixture {
    PvpCommand handler;Command command;
    @BeforeEach void setupCommand(){handler=new PvpCommand();command=mock(Command.class);when(command.getName()).thenReturn("pvp");config.when(PvpLoader::getStartWarnSeconds).thenReturn(3);config.when(PvpLoader::getStartCountdownFrom).thenReturn(2);config.when(PvpLoader::getStartActiveMs).thenReturn(60_000L);config.when(PvpLoader::getStartActiveMinutes).thenReturn(1);}
    @AfterEach void cleanupCommand(){handler.shutdown();}
    void command(String...args){assertTrue(handler.onCommand(victim,command,"pvp",args));}
    void start(){command("start");}
    void countdown(){runNext();runNext();runNext();}

    @Test void commandRoutingUsageAndLethalModeNeedsAConfirmedWarningAndIsNeverSaved() {
        when(command.getName()).thenReturn("other");assertFalse(handler.onCommand(victim,command,"pvp",new String[0]));when(command.getName()).thenReturn("PVP");var console=mock(CommandSender.class);assertTrue(handler.onCommand(console,command,"pvp",new String[0]));verify(console).sendMessage(contains("Players only"));command();assertTrue(text(victim).contains("nonlethal"));
        command("lethal");assertFalse(character.isPvpLethal(),"Lethal needs a confirmation");assertTrue(text(victim).contains("Lethal PvP"));assertTrue(text(victim).contains("can't spare, wound or maim"));
        var button=components.get(victim).getLast();assertEquals(Set.of("/pvp lethal confirm"),PvpStrikeServiceTest.commands(button));assertTrue(PlainTextComponentSerializer.plainText().serialize(button).contains("within 30 seconds"));
        command("lethal","CONFIRM");assertTrue(character.isPvpLethal());assertTrue(text(victim).contains("now in lethal PvP"));command("lethal");assertTrue(text(victim).contains("already in lethal"));
        command("unknown");assertTrue(text(victim).contains("lethal"));command("nonlethal");assertFalse(character.isPvpLethal());
        command("lethal","confirm");assertFalse(character.isPvpLethal());assertTrue(text(victim).contains("first, then confirm within 30 seconds"));
        command("lethal");command("nonlethal");command("lethal","confirm");assertFalse(character.isPvpLethal(),"Going nonlethal drops an unconfirmed request");verify(manager,never()).savePlayer(victim);
        loaded.remove(victim);messages.get(victim).clear();command();command("lethal");assertTrue(text(victim).contains("active character"));messages.get(victim).clear();command("lethal","confirm");assertTrue(text(victim).contains("active character"));messages.get(victim).clear();command("nonlethal");assertTrue(text(victim).contains("active character"));
        loaded.put(victim,data);doReturn(false).when(data).hasActiveCharacter();command("nonlethal");command();
    }

    @Test void aConfirmationExpiresAndOnlyCoversTheCharacterThatWasWarned() {
        config.when(PvpLoader::getLethalConfirmMs).thenReturn(-1L);command("lethal");command("lethal","confirm");assertFalse(character.isPvpLethal());
        config.when(PvpLoader::getLethalConfirmMs).thenReturn(60_000L);command("lethal");var switched=new RPCharacter(victim);data.addCharacter(switched);doReturn(switched).when(data).getActiveCharacter();command("lethal","confirm");assertFalse(switched.isPvpLethal());assertFalse(character.isPvpLethal());
        config.when(PvpLoader::getLethalWarning).thenReturn("one\\ntwo\nthree");messages.get(victim).clear();command("lethal");assertEquals(List.of("one","two","three"),messages.get(victim));
    }

    @Test void everyCharacterSessionStartsNonlethalAndQuittingDropsTheRequest() {
        command("lethal");command("lethal","confirm");assertTrue(character.isPvpLethal());
        var session=mock(CharacterSessionStartEvent.class);when(session.getPlayer()).thenReturn(victim);handler.onSessionStart(session);assertFalse(character.isPvpLethal());
        command("lethal");handler.onSessionStart(session);command("lethal","confirm");assertFalse(character.isPvpLethal(),"A request from before the session started does not carry over");
        loaded.remove(victim);handler.onSessionStart(session);loaded.put(victim,data);
        command("lethal");var quit=mock(PlayerQuitEvent.class);when(quit.getPlayer()).thenReturn(victim);handler.onQuit(quit);command("lethal","confirm");assertFalse(character.isPvpLethal());
    }

    @Test void completionIsLocaleIndependentAndOnlyCompletesTheFirstArgument() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(List.of("start","end","lethal","nonlethal"),handler.onTabComplete(victim,command,"pvp",new String[]{""}));assertEquals(List.of("lethal"),handler.onTabComplete(victim,command,"pvp",new String[]{"L"}));assertTrue(handler.onTabComplete(victim,command,"pvp",new String[0]).isEmpty());assertTrue(handler.onTabComplete(victim,command,"pvp",new String[]{"start","x"}).isEmpty());assertTrue(handler.onTabComplete(victim,command,"pvp",new String[]{"missing"}).isEmpty());assertEquals(List.of("confirm"),handler.onTabComplete(victim,command,"pvp",new String[]{"LETHAL","C"}));assertTrue(handler.onTabComplete(victim,command,"pvp",new String[]{"lethal","x"}).isEmpty());assertTrue(handler.onTabComplete(victim,command,"pvp",new String[]{"end","c"}).isEmpty());
    }

    @Test void countdownIncludesOnlyNearbyPlayersAndStartsTimedSessions() {
        start();assertEquals(List.of(20L,40L,60L),allTasks.stream().map(PendingTask::delay).toList());assertTrue(text(victim).contains("3 seconds"));assertTrue(text(killer).contains("1 minutes"));assertTrue(text(other).isEmpty());tutorials.verify(()->net.tfminecraft.rpcharacters.tutorial.TutorialService.show(eq(victim),anyString(),anyMap()));assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));countdown();assertTrue(text(victim).contains("§c2"));assertTrue(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));assertTrue(PvpStartSessions.isActive(killer.getUniqueId(),System.currentTimeMillis()));assertFalse(PvpStartSessions.isActive(other.getUniqueId(),System.currentTimeMillis()));assertEquals(1,delayed.size());assertEquals(1200,delayed.getFirst().delay());verify(victim).sendTitle(contains("PVP STARTED"),eq(" "),eq(10),eq(60),eq(20));runNext();assertNull(PvpSituations.openFor(victim.getUniqueId()));assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));verify(victim).sendTitle(contains("PVP SITUATION ENDED"),anyString(),eq(10),eq(400),eq(20));
    }

    @Test void closedCountdownCallbacksCannotRestartOrBroadcastForACancelledFight() {
        start();var queued=new ArrayList<>(delayed);command("end");assertNull(PvpSituations.openFor(victim.getUniqueId()));assertTrue(text(victim).contains("cancelled"));for(var task:queued){verify(task.task()).cancel();task.run().run();}assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));verify(victim,never()).sendTitle(contains("STARTED"),anyString(),anyInt(),anyInt(),anyInt());command("end");assertTrue(text(victim).contains("no PvP situation"));
    }

    @Test void reissuingStartCancelsPreviousTasksAndKeepsOnlyNewSituation() {
        start();var first=PvpSituations.openFor(victim.getUniqueId());var old=new ArrayList<>(delayed);start();assertFalse(first.isOpen());assertNotSame(first,PvpSituations.openFor(victim.getUniqueId()));for(var task:old)verify(task.task()).cancel();handler.shutdown();assertNull(PvpSituations.openFor(victim.getUniqueId()));for(var task:allTasks)verify(task.task(),atLeastOnce()).cancel();
    }

    @Test void endingOneOverlappingFightKeepsOtherParticipantsTagged() {
        start();countdown();assertTrue(handler.onCommand(killer,command,"pvp",new String[]{"start"}));var secondCountdown=new ArrayList<>(delayed).subList(1,4);for(var task:secondCountdown)task.run().run();assertTrue(PvpSituations.openFor(killer.getUniqueId()).hasStarted());command("end");assertTrue(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));assertTrue(handler.onCommand(killer,command,"pvp",new String[]{"end"}));assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));
    }

    @Test void deadAndLoggedOutPlayersDoNotReceiveEndTitlesAndQuittingEndsSession() {
        start();countdown();var death=mock(PlayerDeathEvent.class);when(death.getEntity()).thenReturn(victim);handler.onDeath(death);when(killer.isOnline()).thenReturn(false);command("end");verify(victim,never()).sendTitle(contains("ENDED"),anyString(),anyInt(),anyInt(),anyInt());verify(killer,never()).sendTitle(contains("ENDED"),anyString(),anyInt(),anyInt(),anyInt());
        var quit=mock(PlayerQuitEvent.class);when(quit.getPlayer()).thenReturn(victim);handler.onQuit(quit);assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));
    }

    @Test void offlineTargetsAreSkippedAndZeroLengthSituationsHaveNoExpiryTask() {
        config.when(PvpLoader::getStartActiveMs).thenReturn(0L);config.when(PvpLoader::getStartActiveMinutes).thenReturn(0);start();online.remove(killer.getUniqueId());countdown();assertTrue(delayed.isEmpty());assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));assertTrue(PvpSituations.openFor(victim.getUniqueId()).hasStarted());command("end");
    }

    @Test void shutdownClearsTheSessionsOwnedByItsClosedSituations() {
        start();countdown();handler.shutdown();assertNull(PvpSituations.openFor(victim.getUniqueId()));assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()),"Shutdown must not leave combat marks from situations it just closed");
    }

    @Test void shutdownPreservesCombatMarksOwnedByAnotherOpenSituation() {
        start();countdown();var otherSituation=new PvpSituation(killer.getUniqueId(),List.of(victim.getUniqueId()));otherSituation.markStarted();PvpSituations.track(otherSituation);handler.shutdown();
        assertTrue(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));assertFalse(PvpStartSessions.isActive(killer.getUniqueId(),System.currentTimeMillis()));assertSame(otherSituation,PvpSituations.openFor(killer.getUniqueId()));verify(victim,never()).sendTitle(contains("ENDED"),anyString(),anyInt(),anyInt(),anyInt());
    }

    @Test void sessionAndSituationPublicGuardsLeaveExistingStateIntact() {
        PvpStartSessions.begin(null,0,10);PvpStartSessions.begin(Arrays.asList(null,victim.getUniqueId()),0,10);assertTrue(PvpStartSessions.isActive(victim.getUniqueId(),0));assertFalse(PvpStartSessions.isActive(null,0));PvpStartSessions.end(null);PvpSituations.track(null);PvpSituations.untrack(null);assertNull(PvpSituations.openFor(null));var situation=new PvpSituation(victim.getUniqueId(),Arrays.asList(null,victim.getUniqueId()));PvpSituations.track(situation);assertFalse(situation.includes(null));assertFalse(situation.hasDied(null));assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null),"An unfinished countdown is not another active fight");situation.markStarted();assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),situation));assertTrue(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null));PvpSituations.markDeath(victim.getUniqueId());assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null));situation.close();assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null));situation.markStarted();assertTrue(situation.close().isEmpty());PvpSituations.clear();
    }
}
