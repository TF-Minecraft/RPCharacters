package net.tfminecraft.rpcharacters.speechbubble.fake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleManagerTest;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

class FakeBubbleManagerTest extends SpeechBubbleManagerTest.BubbleFixture {
    PlayerMock viewer;ChatChannel channel;
    @BeforeEach void readyFakeBubbles(){viewer=server.addPlayer("Viewer");viewer.teleport(new Location(world,1,64,0));channel=channel("rp",true,true);protocol.when(ProtocolLibBridge::isReady).thenReturn(true);}
    void show(String text,long expiry){fakeManager.show(viewer,player,UUID.randomUUID(),text,channel,expiry);}
    void show(String text){show(text,Long.MAX_VALUE);}

    @Test void unavailableBridgeAndInvalidInputsNeverSpawnPacketsOrScheduleWork() {
        protocol.when(ProtocolLibBridge::isReady).thenReturn(false);fakeManager.startTicks();show("unavailable");protocol.when(ProtocolLibBridge::isReady).thenReturn(true);fakeManager.show(null,player,UUID.randomUUID(),"text",channel,10);fakeManager.show(viewer,null,UUID.randomUUID(),"text",channel,10);fakeManager.show(viewer,player,UUID.randomUUID(),"text",null,10);show(null);show(" ");settings.setEnabled(false);show("disabled");fakeManager.startTicks();server.getScheduler().performTicks(1);settings.setEnabled(true);protocol.when(ProtocolLibBridge::isReady).thenReturn(false);protocol.when(ProtocolLibBridge::getPackets).thenReturn(null);server.getScheduler().performTicks(1);verifyNoInteractions(packets);fakeManager.removeViewer(UUID.randomUUID());fakeManager.removeSpeaker(UUID.randomUUID());fakeManager.shutdown();fakeManager.shutdown();
    }

    @Test void linesSpawnWithUniqueNegativeIdsAndMoveSmoothlyOnlyWhenNeeded() {
        settings.setMaxCharactersPerLine(6);show("one two three");var ids=ArgumentCaptor.forClass(Integer.class);var locations=ArgumentCaptor.forClass(Location.class);var text=ArgumentCaptor.forClass(String.class);verify(packets,times(3)).spawn(eq(viewer),ids.capture(),any(UUID.class),locations.capture(),text.capture(),eq(.6f),eq(50));assertEquals(List.of(-1,-2,-3),ids.getAllValues());assertEquals(List.of("§aone","§atwo","§athree"),text.getAllValues());assertTrue(locations.getAllValues().getFirst().getY()>locations.getAllValues().getLast().getY());
        fakeManager.startTicks();fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets,never()).teleport(any(),anyInt(),any());player.teleport(new Location(world,8,64,0));server.getScheduler().performTicks(1);var moved=ArgumentCaptor.forClass(Location.class);verify(packets,times(3)).teleport(eq(viewer),anyInt(),moved.capture());assertTrue(moved.getAllValues().stream().allMatch(location->location.getX()==4));fakeManager.shutdown();verify(packets).destroy(viewer,List.of(-1,-2,-3));server.getScheduler().performTicks(1);
    }

    @Test void stackCapAndExpiryDestroyOldLinesAndRebuildRemainingUtterances() {
        settings.setMaxStackedUtterances(2);show("expired",0);show("keep");fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets,atLeastOnce()).destroy(eq(viewer),argThat(ids->ids.contains(-2)));clearInvocations(packets);settings.setMaxStackedUtterances(1);show("new");verify(packets).spawn(eq(viewer),anyInt(),any(),any(),eq("§anew"),anyFloat(),anyInt());verify(packets,atLeastOnce()).destroy(eq(viewer),anyList());
        fakeManager.removeSpeaker(player.getUniqueId());clearInvocations(packets);show("gone",0);fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets,atLeastOnce()).destroy(eq(viewer),anyList());clearInvocations(packets);server.getScheduler().performTicks(1);verifyNoInteractions(packets);
    }

    @Test void removeViewerAndSpeakerAffectOnlyTheirOwnStacks() {
        var second=server.addPlayer("Second");show("first");fakeManager.show(viewer,second,UUID.randomUUID(),"second",channel,Long.MAX_VALUE);fakeManager.removeSpeaker(player.getUniqueId());verify(packets).destroy(viewer,List.of(-1));verify(packets,never()).destroy(viewer,List.of(-2));fakeManager.removeViewer(viewer.getUniqueId());verify(packets).destroy(viewer,List.of(-2));clearInvocations(packets);fakeManager.removeViewer(viewer.getUniqueId());fakeManager.removeSpeaker(second.getUniqueId());verifyNoInteractions(packets);
    }

    @Test void viewerDisconnectDiscardsItsStateWithoutTryingToSendPackets() {
        show("bye");viewer.disconnect();clearInvocations(packets);fakeManager.startTicks();server.getScheduler().performTicks(1);verifyNoInteractions(packets);fakeManager.removeViewer(viewer.getUniqueId());fakeManager.removeSpeaker(player.getUniqueId());verifyNoInteractions(packets);
    }

    @Test void speakerDisconnectDestroysItsBubblesForOnlineViewers() {
        show("bye");player.disconnect();clearInvocations(packets);fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets).destroy(viewer,List.of(-1));clearInvocations(packets);server.getScheduler().performTicks(1);verifyNoInteractions(packets);
    }

    @Test void removingOfflineViewersSpeakersAndChunkSpeakersDoesNotLeakOnlineBubbles() {
        show("offline");viewer.disconnect();clearInvocations(packets);fakeManager.removeSpeaker(player.getUniqueId());verifyNoInteractions(packets);fakeManager.removeViewer(viewer.getUniqueId());
        viewer=server.addPlayer("Online");show("target");var differentX=server.addPlayer();differentX.teleport(new Location(world,50,64,0));var differentZ=server.addPlayer();differentZ.teleport(new Location(world,0,64,50));var elsewhere=server.addPlayer();elsewhere.teleport(new Location(server.addSimpleWorld("other"),0,64,0));fakeManager.removeSpeakerInChunk(world.getName(),0,0);verify(packets).destroy(viewer,List.of(-1));clearInvocations(packets);fakeManager.removeSpeakerInChunk(world.getName(),0,0);verifyNoInteractions(packets);
    }

    @Test void partialSpawnFailureCleansAlreadySentEntitiesAndRetriesAllLines() {
        settings.setMaxCharactersPerLine(3);var calls=new AtomicInteger();doAnswer(call->{if(calls.incrementAndGet()==2)throw new IllegalStateException("packet failed");return null;}).when(packets).spawn(any(),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());show("one two");verify(packets).destroy(viewer,List.of(-1));verify(logger).warning(contains("Failed to refresh fake speech bubbles"));doNothing().when(packets).spawn(any(),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());clearInvocations(packets);fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets,times(2)).spawn(eq(viewer),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());
    }

    @Test void spawnFailureWithoutAPluginStillLeavesARecoverableStack() {
        RPCharacters.plugin=null;doThrow(new IllegalStateException("packet failed")).when(packets).spawn(any(),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());assertDoesNotThrow(()->show("retry"));verifyNoInteractions(logger);RPCharacters.plugin=plugin;doNothing().when(packets).spawn(any(),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets,times(2)).spawn(eq(viewer),anyInt(),any(),any(),eq("§aretry"),anyFloat(),anyInt());
    }

    @Test void speakerLeavingTheViewersWorldDestroysItsBubblesWithoutThrowing() {
        show("travel");fakeManager.startTicks();var other=server.addSimpleWorld("other");player.teleport(new Location(other,2,65,0));assertDoesNotThrow(()->server.getScheduler().performTicks(1));verify(packets).destroy(viewer,List.of(-1));verify(packets,never()).teleport(any(),anyInt(),any());clearInvocations(packets);server.getScheduler().performTicks(1);verifyNoInteractions(packets);
    }

    @Test void transferringTogetherRespawnsClientEntitiesInTheNewWorld() {
        show("travel");fakeManager.startTicks();var other=server.addSimpleWorld("other");player.teleport(new Location(other,2,65,0));viewer.teleport(new Location(other,3,65,0));assertDoesNotThrow(()->server.getScheduler().performTicks(1));verify(packets).destroy(viewer,List.of(-1));verify(packets).spawn(eq(viewer),eq(-2),any(),argThat(location->location.getWorld().equals(other)),eq("§atravel"),anyFloat(),anyInt());verify(packets,never()).teleport(any(),anyInt(),any());
    }

    @Test void bridgeLossDuringCleanupDoesNotThrowOrRetainStaleEntities() {
        show("cleanup");protocol.when(ProtocolLibBridge::isReady).thenReturn(false);protocol.when(ProtocolLibBridge::getPackets).thenReturn(null);assertDoesNotThrow(()->fakeManager.removeViewer(viewer.getUniqueId()));protocol.when(ProtocolLibBridge::getPackets).thenReturn(packets);protocol.when(ProtocolLibBridge::isReady).thenReturn(true);clearInvocations(packets);fakeManager.removeViewer(viewer.getUniqueId());verifyNoInteractions(packets);
    }

    @Test void packetDispatchThatDisablesTheBridgeLeavesARecoverableStack() {
        show("old");doAnswer(call->{protocol.when(ProtocolLibBridge::isReady).thenReturn(false);protocol.when(ProtocolLibBridge::getPackets).thenReturn(null);return null;}).when(packets).destroy(eq(viewer),anyList());show("new");verify(packets).destroy(viewer,List.of(-1));verify(packets,times(1)).spawn(any(),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());protocol.when(ProtocolLibBridge::isReady).thenReturn(true);protocol.when(ProtocolLibBridge::getPackets).thenReturn(packets);fakeManager.startTicks();server.getScheduler().performTicks(1);verify(packets).spawn(eq(viewer),eq(-2),any(),any(),eq("§aold"),anyFloat(),anyInt());verify(packets).spawn(eq(viewer),eq(-3),any(),any(),eq("§anew"),anyFloat(),anyInt());
    }

    @Test void viewersInADifferentWorldNeverReceiveInitialBubblePackets() {
        viewer.teleport(new Location(server.addSimpleWorld("other"),0,65,0));show("distant");verifyNoInteractions(packets);
    }

    @Test void negativeConfiguredStackLimitCannotThrowDuringShow() {
        settings.setMaxStackedUtterances(-1);assertDoesNotThrow(()->show("disabled by limit"));verify(packets,never()).spawn(any(),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());
    }

    @Test void realViewerStateAndUtteranceRecordsPreserveOrderAndExactExpiry() {
        UUID viewerId=UUID.randomUUID(),speakerId=UUID.randomUUID();var state=new FakeBubbleManager.ViewerBubbleState(viewerId);assertEquals(viewerId,state.getViewerId());var stack=state.getOrCreateStack(speakerId);assertSame(stack,state.getOrCreateStack(speakerId));assertEquals(-1,state.getEntityIdSource().getAndDecrement());assertSame(stack,state.getStacks().get(speakerId));var input=new ArrayList<>(List.of("one","two"));UUID utteranceId=UUID.randomUUID();var utterance=new FakeBubbleManager.ViewerUtterance(utteranceId,input,10);input.clear();assertEquals(List.of("one","two"),utterance.getLines());assertThrows(UnsupportedOperationException.class,()->utterance.getLines().clear());assertFalse(utterance.isExpired(9));assertTrue(utterance.isExpired(10));utterance.trackEntityId(2,-9);assertEquals(List.of(0,0,-9),utterance.getEntityIds());utterance.clearEntityIds();assertTrue(utterance.getEntityIds().isEmpty());stack.getUtterances().add(utterance);var lines=stack.buildLayoutLines();assertEquals(utteranceId,lines.getFirst().utteranceId());assertEquals("one",lines.getFirst().text());assertEquals(1,lines.get(1).lineIndex());assertSame(utterance,lines.getFirst().utterance());var line=new FakeBubbleManager.FakeBubbleLine(-1,UUID.randomUUID(),utteranceId,0,new Location(world,1,2,3));stack.getActiveLines().add(line);assertEquals(-1,line.getEntityId());line.setCurrentLocation(new Location(world,4,5,6));assertEquals(4,line.getCurrentLocation().getX());assertSame(stack,state.removeStack(speakerId));assertTrue(state.getStacks().isEmpty());
    }

    @Test void followStepSkipsMovesOfAThousandthOfABlockOrLess() {
        var current=new Location(world,0,64,0,10,0);
        assertNull(FakeBubbleManager.followStep(current,current.clone(),.35));
        assertNull(FakeBubbleManager.followStep(current,new Location(world,0,64.002,0,90,30),.35),"a 0.0007 block step plus a turn");
        var next=FakeBubbleManager.followStep(current,new Location(world,0,64.01,0,10,0),.5);
        assertEquals(64.005,next.getY(),1e-12);assertNotSame(current,next);assertEquals(64,current.getY());
    }

    @Test void standingOrTurningSpeakerWithoutBobbingSendsNoTeleports() {
        settings.setMaxCharactersPerLine(6);show("one two three");fakeManager.startTicks();
        server.getScheduler().performTicks(100);verify(packets,never()).teleport(any(),anyInt(),any());
        player.setRotation(170,40);server.getScheduler().performTicks(20);verify(packets,never()).teleport(any(),anyInt(),any());
    }

    @Test void bobbingOnlySendsStepsLargerThanTheThresholdFromTheLastSentPosition() {
        settings.setBobAmplitude(.12);settings.setBobPeriodTicks(60);settings.setFollowLerpFactor(.35);show("bob");fakeManager.startTicks();
        var spawn=ArgumentCaptor.forClass(Location.class);verify(packets).spawn(eq(viewer),eq(-1),any(),spawn.capture(),anyString(),anyFloat(),anyInt());
        server.getScheduler().performTicks(400);
        var moves=ArgumentCaptor.forClass(Location.class);verify(packets,atLeastOnce()).teleport(eq(viewer),eq(-1),moves.capture());
        assertTrue(moves.getAllValues().size()<400,"steps near the top and bottom of the bob are batched");
        var sentPositions=new ArrayList<Location>();sentPositions.add(spawn.getValue());sentPositions.addAll(moves.getAllValues());
        double middle=64+settings.getHeightAboveHead()+settings.getFirstLineOffset();
        for(int i=1;i<sentPositions.size();i++){var before=sentPositions.get(i-1);var after=sentPositions.get(i);
            assertTrue(before.distance(after)>.001);assertEquals(0,after.getX());assertEquals(0,after.getZ());assertEquals(middle,after.getY(),.12);}
    }

    @Test void movingSpeakerIsFollowedAndSendingStopsOnceTheBubbleCatchesUp() {
        show("walk");fakeManager.startTicks();server.getScheduler().performTicks(1);player.teleport(new Location(world,8,64,0));server.getScheduler().performTicks(40);
        var moves=ArgumentCaptor.forClass(Location.class);verify(packets,atLeast(10)).teleport(eq(viewer),eq(-1),moves.capture());
        var xs=moves.getAllValues().stream().mapToDouble(Location::getX).toArray();assertEquals(4,xs[0]);
        for(int i=1;i<xs.length;i++)assertTrue(xs[i]>xs[i-1]);assertEquals(8,xs[xs.length-1],.002);
        clearInvocations(packets);server.getScheduler().performTicks(40);verify(packets,never()).teleport(any(),anyInt(),any());
    }

    @Test void newUtteranceRespawnsEveryLineOnTheSpeakerRatherThanTheLaggingPosition() {
        show("first");fakeManager.startTicks();player.teleport(new Location(world,8,64,0));server.getScheduler().performTicks(1);clearInvocations(packets);show("second");
        var spawns=ArgumentCaptor.forClass(Location.class);verify(packets,times(2)).spawn(eq(viewer),anyInt(),any(),spawns.capture(),anyString(),anyFloat(),anyInt());
        assertTrue(spawns.getAllValues().stream().allMatch(location->location.getX()==8));
        clearInvocations(packets);server.getScheduler().performTicks(1);verify(packets,never()).teleport(any(),anyInt(),any());
    }

    @Test void viewerWhoStartsListeningLateGetsTheCurrentPositionAtOnce() {
        show("first");fakeManager.startTicks();player.teleport(new Location(world,8,64,0));server.getScheduler().performTicks(1);
        var late=server.addPlayer("Late");late.teleport(new Location(world,2,64,0));fakeManager.show(late,player,UUID.randomUUID(),"hello",channel,Long.MAX_VALUE);
        verify(packets).spawn(eq(late),eq(-1),any(),argThat(location->location.getX()==8),eq("§ahello"),anyFloat(),anyInt());
        clearInvocations(packets);server.getScheduler().performTicks(1);
        verify(packets,never()).teleport(eq(late),anyInt(),any());verify(packets).teleport(eq(viewer),eq(-1),argThat(location->location.getX()==6));
    }
}
