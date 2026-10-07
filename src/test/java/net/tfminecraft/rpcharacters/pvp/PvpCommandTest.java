package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.tlibs.armour.ArmorEquipEvent;
import net.tfminecraft.tlibs.armour.ArmorType;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;

class PvpCommandTest extends PvpRuntimeFixture {
    PvpCommand handler;Command command;
    @BeforeEach void setupCommand(){handler=new PvpCommand();command=mock(Command.class);when(command.getName()).thenReturn("pvp");config.when(PvpLoader::getStartWarnSeconds).thenReturn(3);config.when(PvpLoader::getStartCountdownFrom).thenReturn(2);config.when(PvpLoader::getStartActiveMs).thenReturn(60_000L);config.when(PvpLoader::getStartActiveMinutes).thenReturn(1);}
    @AfterEach void cleanupCommand(){handler.shutdown();}
    void command(String...args){assertTrue(handler.onCommand(victim,command,"pvp",args));}
    void start(){command("start");}
    void countdown(){runNext();runNext();runNext();}
    ArmorEquipEvent armor(org.bukkit.entity.Player p,boolean equip){return new ArmorEquipEvent(p,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.HELMET,null,equip?new ItemStack(Material.IRON_HELMET):null);}

    @Test void commandRoutingUsageAndLethalityPersistTheActiveCharacter() {
        when(command.getName()).thenReturn("other");assertFalse(handler.onCommand(victim,command,"pvp",new String[0]));when(command.getName()).thenReturn("PVP");var console=mock(CommandSender.class);assertTrue(handler.onCommand(console,command,"pvp",new String[0]));verify(console).sendMessage(contains("Players only"));command();assertTrue(text(victim).contains("nonlethal"));command("lethal");assertTrue(character.isPvpLethal());command("unknown");assertTrue(text(victim).contains("lethal"));command("nonlethal");assertFalse(character.isPvpLethal());verify(manager,times(2)).savePlayer(victim);
        loaded.remove(victim);command();command("lethal");assertTrue(text(victim).contains("active character"));loaded.put(victim,data);doReturn(false).when(data).hasActiveCharacter();command("nonlethal");command();verify(manager,times(2)).savePlayer(victim);
    }

    @Test void completionIsLocaleIndependentAndOnlyCompletesTheFirstArgument() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(List.of("start","end","lethal","nonlethal"),handler.onTabComplete(victim,command,"pvp",new String[]{""}));assertEquals(List.of("lethal"),handler.onTabComplete(victim,command,"pvp",new String[]{"L"}));assertTrue(handler.onTabComplete(victim,command,"pvp",new String[0]).isEmpty());assertTrue(handler.onTabComplete(victim,command,"pvp",new String[]{"start","x"}).isEmpty());assertTrue(handler.onTabComplete(victim,command,"pvp",new String[]{"missing"}).isEmpty());
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

    @Test void armourIsBlockedDuringWarningButRemovalAndUnrelatedPlayersRemainAllowed() {
        var malformed=mock(ArmorEquipEvent.class);handler.onArmorEquip(malformed);var allowed=armor(victim,true);handler.onArmorEquip(allowed);assertFalse(allowed.isCancelled());start();var adding=armor(victim,true);handler.onArmorEquip(adding);assertTrue(adding.isCancelled());var removing=armor(victim,false);handler.onArmorEquip(removing);assertFalse(removing.isCancelled());var distant=armor(other,true);handler.onArmorEquip(distant);assertFalse(distant.isCancelled());
    }

    @Test void armourStaysLockedAfterTheCountdownUntilDeathOrTheEnd() {
        start();countdown();var afterCountdown=armor(victim,true);handler.onArmorEquip(afterCountdown);assertTrue(afterCountdown.isCancelled(),"The lock must last for the whole fight, not only the warning");assertTrue(text(victim).contains("can't put armour on"));
        var death=mock(PlayerDeathEvent.class);when(death.getEntity()).thenReturn(killer);handler.onDeath(death);var respawned=armor(killer,true);handler.onArmorEquip(respawned);assertFalse(respawned.isCancelled(),"Someone who died in the fight can gear up again");
        command("end");var released=armor(victim,true);handler.onArmorEquip(released);assertFalse(released.isCancelled());
    }

    @Test void helmetsStayFreeForThoseWhoCameInBodyArmour() {
        var inventory=victim.getInventory();when(inventory.getChestplate()).thenReturn(new ItemStack(Material.IRON_CHESTPLATE));when(inventory.getLeggings()).thenReturn(new ItemStack(Material.IRON_LEGGINGS));when(inventory.getBoots()).thenReturn(new ItemStack(Material.IRON_BOOTS));
        try(var masks=mockStatic(net.tfminecraft.rpcharacters.identity.MaskService.class)){start();}countdown();
        var helmet=armor(victim,true);handler.onArmorEquip(helmet);assertFalse(helmet.isCancelled(),"Armoured fighters keep their helmet free");
        var chestplate=new ArmorEquipEvent(victim,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.CHESTPLATE,null,new ItemStack(Material.IRON_CHESTPLATE));handler.onArmorEquip(chestplate);assertTrue(chestplate.isCancelled());
        var unarmoured=armor(killer,true);handler.onArmorEquip(unarmoured);assertTrue(unarmoured.isCancelled(),"Without body armour a helmet stays off");var removing=armor(killer,false);handler.onArmorEquip(removing);assertFalse(removing.isCancelled());
    }

    @Test void startTakesOffRecentArmourAndTellsTheOthersAtOnce() {
        try(var donning=mockStatic(ArmourDonning.class)){donning.when(()->ArmourDonning.takeOffRecent(eq(victim),anyLong())).thenReturn(true);start();
            donning.verify(()->ArmourDonning.takeOffRecent(eq(killer),anyLong()));donning.verify(()->ArmourDonning.takeOffRecent(eq(other),anyLong()),never());assertTrue(text(killer).contains("Victim hadn't finished fastening their armour"));assertFalse(text(victim).contains("hadn't finished fastening their"));assertTrue(allTasks.stream().allMatch(task->task.delay()>0),"Armour comes off when the command runs, before the countdown");}
    }

    @Test void cancellingTheOnlyCountdownImmediatelyReleasesItsArmourRestriction() {
        start();command("end");var equip=armor(victim,true);handler.onArmorEquip(equip);assertFalse(equip.isCancelled(),"Cancelling the fight must release its warning-only armour restriction");
    }

    @Test void cancellingOneCountdownPreservesAnotherCountdownsArmourRestriction() {
        start();assertTrue(handler.onCommand(killer,command,"pvp",new String[]{"start"}));command("end");var stillBlocked=armor(victim,true);handler.onArmorEquip(stillBlocked);assertTrue(stillBlocked.isCancelled());assertTrue(handler.onCommand(killer,command,"pvp",new String[]{"end"}));var released=armor(victim,true);handler.onArmorEquip(released);assertFalse(released.isCancelled());
    }

    @Test void shutdownClearsTheSessionsOwnedByItsClosedSituations() {
        start();countdown();handler.shutdown();assertNull(PvpSituations.openFor(victim.getUniqueId()));assertFalse(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()),"Shutdown must not leave combat marks from situations it just closed");var equip=armor(victim,true);handler.onArmorEquip(equip);assertFalse(equip.isCancelled());
    }

    @Test void shutdownPreservesCombatMarksOwnedByAnotherOpenSituation() {
        start();countdown();var otherSituation=new PvpSituation(killer.getUniqueId(),List.of(victim.getUniqueId()));otherSituation.markStarted();PvpSituations.track(otherSituation);handler.shutdown();
        assertTrue(PvpStartSessions.isActive(victim.getUniqueId(),System.currentTimeMillis()));assertFalse(PvpStartSessions.isActive(killer.getUniqueId(),System.currentTimeMillis()));assertSame(otherSituation,PvpSituations.openFor(killer.getUniqueId()));verify(victim,never()).sendTitle(contains("ENDED"),anyString(),anyInt(),anyInt(),anyInt());
    }

    @Test void sessionAndSituationPublicGuardsLeaveExistingStateIntact() {
        PvpStartSessions.begin(null,0,10);PvpStartSessions.begin(Arrays.asList(null,victim.getUniqueId()),0,10);assertTrue(PvpStartSessions.isActive(victim.getUniqueId(),0));assertFalse(PvpStartSessions.isActive(null,0));PvpStartSessions.end(null);PvpSituations.track(null);PvpSituations.untrack(null);assertNull(PvpSituations.openFor(null));var situation=new PvpSituation(victim.getUniqueId(),Arrays.asList(null,victim.getUniqueId()));PvpSituations.track(situation);assertFalse(situation.includes(null));assertFalse(situation.hasDied(null));assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null),"An unfinished countdown is not another active fight");situation.markStarted();assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),situation));assertTrue(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null));PvpSituations.markDeath(victim.getUniqueId());assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null));situation.close();assertFalse(PvpSituations.remainsActiveElsewhere(victim.getUniqueId(),null));situation.markStarted();assertTrue(situation.close().isEmpty());PvpSituations.clear();
    }
}
