package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;

/** Leave titles wait {@code leave-title-delay-ms}; stepping back in before then shows nothing. */
class PermadeathZoneLeaveDelayTest extends ZoneBridgeFixture {
    MockedStatic<TutorialService> tutorial;PermadeathZoneDefinition current;
    @BeforeEach void setupDelay() throws Exception {
        var lookup=boundary(PermadeathAreaLookup.class);lookup.when(()->PermadeathAreaLookup.getPermadeathZoneAt(eq(player),any(Location.class))).thenAnswer(call->current);tutorial=boundary(TutorialService.class);
        loadZones("leave-title-delay-ms: 1000\npermadeath-zones:\n  REGION_I:\n    name: Highlands\n  lowlands:\n    name: Lowlands\n");
    }
    String title(){return ChatColor.stripColor(player.nextTitle());}
    void move(String zoneId){current=zoneId==null?null:zone(zoneId);PermadeathZoneListener.syncFromLocation(player,player.getLocation());}
    void ticks(int count){server.getScheduler().performTicks(count);}

    @Test void delaySettingDefaultsToFiveSecondsAndIsClamped() throws Exception {
        loadZones("");assertEquals(5000,PermadeathZoneLoader.getLeaveTitleDelayMs());
        loadZones("leave-title-delay-ms: -5\n");assertEquals(0,PermadeathZoneLoader.getLeaveTitleDelayMs());
        loadZones("leave-title-delay-ms: 999999\n");assertEquals(60000,PermadeathZoneLoader.getLeaveTitleDelayMs());
    }
    @Test void leaveTitleShowsOnlyAfterTheDelay(){
        move("REGION_I");assertEquals("Now entering Highlands",title());
        move(null);assertNull(title());
        ticks(19);assertNull(title());
        ticks(1);assertEquals("Now leaving Highlands",title());
        ticks(40);assertNull(title());
    }
    @Test void steppingBackInBeforeTheDelayShowsNoTitlesOrTutorial(){
        move("REGION_I");assertEquals("Now entering Highlands",title());
        for(int i=0;i<5;i++){move(null);ticks(10);move("REGION_I");}
        ticks(40);assertNull(title());
        tutorial.verify(()->TutorialService.show(player,TutorialService.PERMADEATH_ZONE),times(1));
        move(null);ticks(20);assertEquals("Now leaving Highlands",title());
        move("REGION_I");assertEquals("Now entering Highlands",title());
        tutorial.verify(()->TutorialService.show(player,TutorialService.PERMADEATH_ZONE),times(2));
    }
    @Test void enteringAnotherZoneWhileALeaveWaitsWarnsAtOnce(){
        move("REGION_I");title();
        move(null);ticks(5);move("lowlands");
        assertEquals("Now entering Lowlands",title());
        ticks(40);assertNull(title());
        tutorial.verify(()->TutorialService.show(player,TutorialService.PERMADEATH_ZONE),times(2));
    }
    @Test void directZoneSwitchStillLeavesAndEntersAtOnce(){
        move("REGION_I");title();
        move("lowlands");assertEquals("Now leaving Highlands",title());assertEquals("Now entering Lowlands",title());
        ticks(40);assertNull(title());
    }
    @Test void clearingTrackingDropsTheWaitingLeaveSoTheNextEntryWarns(){
        move("REGION_I");title();
        move(null);PermadeathZoneListener.clearZoneTracking(player);ticks(40);assertNull(title());
        move("REGION_I");assertEquals("Now entering Highlands",title());
        move(null);PermadeathZoneListener.silentZoneSync(player,null);ticks(40);assertNull(title());
        move("REGION_I");assertEquals("Now entering Highlands",title());
    }
    @Test void quitDropsTheWaitingLeave(){
        move("REGION_I");title();move(null);
        try(var service=mockStatic(PermadeathService.class)){new PermadeathZoneListener().onPlayerQuit(new org.bukkit.event.player.PlayerQuitEvent(player,"bye"));}
        ticks(40);assertNull(title());
    }
    @Test void playerWhoWentOfflineGetsNoLateTitle(){
        move("REGION_I");title();move(null);player.disconnect();ticks(40);assertNull(title());
    }
    @Test void zoneReloadedAwayWhileItsLeaveWaitsShowsNothing() throws Exception {
        move("REGION_I");title();move(null);loadZones("leave-title-delay-ms: 1000\n");ticks(40);assertNull(title());
    }
    @Test void subTickDelayStillWaitsOneTick() throws Exception {
        loadZones("leave-title-delay-ms: 1\npermadeath-zones:\n  REGION_I:\n    name: Highlands\n");
        move("REGION_I");title();move(null);assertNull(title());
        ticks(1);assertEquals("Now leaving Highlands",title());
    }
    @Test void deathRightAfterLeavingShowsTheWaitingLeaveTitleAtOnce(){
        move("REGION_I");title();move(null);
        var death=mock(org.bukkit.event.entity.PlayerDeathEvent.class);when(death.getEntity()).thenReturn(player);
        try(var service=mockStatic(PermadeathService.class)){new PermadeathZoneListener().onPlayerDeath(death);}
        assertEquals("Now leaving Highlands",title());
        ticks(40);assertNull(title());
    }
    void teleport(String zoneId){current=zoneId==null?null:zone(zoneId);var to=player.getLocation().clone().add(100,0,0);new PermadeathZoneListener().onPlayerTeleport(new org.bukkit.event.player.PlayerTeleportEvent(player,player.getLocation(),to));}
    @Test void teleportOutOfAZoneShowsTheLeaveTitleAtOnce(){
        move("REGION_I");title();
        teleport(null);assertEquals("Now leaving Highlands",title());
        ticks(40);assertNull(title());
    }
    @Test void teleportWhileALeaveWaitsSettlesItAndWarnsOnArrival(){
        move("REGION_I");title();move(null);
        teleport("REGION_I");assertEquals("Now leaving Highlands",title());assertEquals("Now entering Highlands",title());
        ticks(40);assertNull(title());
        tutorial.verify(()->TutorialService.show(player,TutorialService.PERMADEATH_ZONE),times(2));
    }
    @Test void disabledPluginShowsTheLeaveTitleAtOnce(){
        move("REGION_I");title();when(plugin.isEnabled()).thenReturn(false);
        move(null);assertEquals("Now leaving Highlands",title());
    }
}
