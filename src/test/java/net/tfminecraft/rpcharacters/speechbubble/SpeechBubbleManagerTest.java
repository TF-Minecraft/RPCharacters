package net.tfminecraft.rpcharacters.speechbubble;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.chat.*;
import net.tfminecraft.rpcharacters.chat.smart.SmartMessageSettings;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.speechbubble.fake.*;
import org.bukkit.*;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.EntityTypesMock;
import org.mockbukkit.mockbukkit.entity.TextDisplayMock;
import org.mockito.MockedStatic;

public class SpeechBubbleManagerTest extends SpeechBubbleManagerTestFixture {
    @Test void inputGuardsDoNotCreateDisplaysAndDebugPreviewHandlesNullAndLongMessages() {
        settings.setDebugMessages(true);settings.setEnabled(false);chat(player,"rp","disabled");settings.setEnabled(true);
        chat(null,"rp",null);var offline=server.addPlayer();offline.disconnect();chat(offline,"rp","offline");chat(player,null,"missing channel");chat(player,"missing","unknown");chat(player,"silent","silent");chat(player,"rp",null);chat(player,"rp"," ");
        assertTrue(displays().isEmpty());chat(player,"rp","A".repeat(60));assertEquals(1,displays().size());verify(logger,times(2)).info(contains("AAAAAAAAAAAA..."));
    }

    @Test void realDisplaysCarryTextScaleOwnershipAndLineTagsAndFollowTheSpeaker() {
        settings.setMaxCharactersPerLine(6);chat(player,"rp","one two three");var displays=displays();assertEquals(3,displays.size());
        assertEquals(List.of("§aone","§atwo","§athree"),displays.stream().sorted(Comparator.comparingInt(d->d.getPersistentDataContainer().get(key("speech_bubble_line"),PersistentDataType.INTEGER))).map(TextDisplay::getText).toList());
        for(var display:displays){assertEquals(player.getUniqueId().toString(),display.getPersistentDataContainer().get(key("speech_bubble_owner"),PersistentDataType.STRING));assertNotNull(display.getPersistentDataContainer().get(key("speech_bubble_utterance"),PersistentDataType.STRING));assertEquals(.6f,display.getTransformation().getScale().x);assertFalse(display.isPersistent());}
        manager.startTicks();manager.startTicks();server.getScheduler().performTicks(1);player.teleport(new Location(world,8,64,0));server.getScheduler().performTicks(1);assertTrue(displays().stream().allMatch(display->display.getLocation().getX()==4.0));manager.shutdown();manager.shutdown();assertTrue(displays().isEmpty());server.getScheduler().performTicks(2);
    }

    @Test void cappedAndExpiredUtterancesRemoveOnlyTheirOwnDisplays() {
        settings.setMaxStackedUtterances(2);settings.setUtteranceTimeoutSeconds(-1);chat(player,"rp","old");settings.setUtteranceTimeoutSeconds(60);chat(player,"rp","keep");assertEquals(2,displays().size());manager.startTicks();server.getScheduler().performTicks(1);assertEquals(List.of("§akeep"),displays().stream().map(TextDisplay::getText).toList());
        settings.setMaxStackedUtterances(1);chat(player,"rp","new");assertEquals(List.of("§anew"),displays().stream().map(TextDisplay::getText).toList());manager.removePlayer(UUID.randomUUID());manager.removePlayer(player.getUniqueId());assertTrue(displays().isEmpty());
        settings.setUtteranceTimeoutSeconds(-1);chat(player,"rp","gone");server.getScheduler().performTicks(1);assertTrue(displays().isEmpty());
    }

    @Test void missingDisplaysAreRecreatedAndOfflineSpeakersAreCleared() {
        chat(player,"rp","hello");TextDisplay old=displays().getFirst();old.remove();manager.startTicks();server.getScheduler().performTicks(1);assertEquals(1,displays().size());assertNotEquals(old.getUniqueId(),displays().getFirst().getUniqueId());
        settings.setEnabled(false);server.getScheduler().performTicks(1);settings.setEnabled(true);player.disconnect();server.getScheduler().performTicks(1);assertTrue(displays().isEmpty());
    }

    @Test void partialWorldSpawnFailureIsRepairedByTheNextScheduledTick() {
        settings.setMaxCharactersPerLine(3);textDisplaySpawnsBeforeFailure=1;assertThrows(IllegalStateException.class,()->chat(player,"rp","one two three"));assertEquals(1,displays().size());manager.startTicks();server.getScheduler().performTicks(1);assertEquals(Set.of("§aone","§atwo","§athree"),new HashSet<>(displays().stream().map(TextDisplay::getText).toList()));assertEquals(3,displays().size());
    }

    @Test void stackCleanupHandlesMissingAndRetaggedEntitiesWithoutLeakingExtraLines() {
        settings.setMaxCharactersPerLine(3);settings.setMaxStackedUtterances(1);chat(player,"rp","one two");var old=displays();old.getFirst().remove();chat(player,"rp","new");assertEquals(1,displays().size());
        chat(player,"rp","one two");for(var display:displays())display.getPersistentDataContainer().set(key("speech_bubble_utterance"),PersistentDataType.STRING,"external-change");chat(player,"rp","end");assertEquals(List.of("§aend"),displays().stream().map(TextDisplay::getText).toList());manager.removeAll();assertTrue(displays().isEmpty());
    }

    @Test void worldChangesDoNotThrowOrLeaveDisplaysInTheOldWorld() {
        chat(player,"rp","travel");manager.startTicks();var other=server.addSimpleWorld("other");player.teleport(new Location(other,0,65,0));assertDoesNotThrow(()->server.getScheduler().performTicks(1));assertTrue(world.getEntities().stream().noneMatch(TextDisplay.class::isInstance));assertEquals(1,other.getEntities().stream().filter(TextDisplay.class::isInstance).count());
    }

    @Test void negativeConfiguredStackLimitCannotThrowDuringChat() {
        settings.setMaxStackedUtterances(-1);assertDoesNotThrow(()->chat(player,"rp","disabled by limit"));assertTrue(displays().isEmpty());
    }

    @Test void stackAndUtteranceKeepOrderedImmutableLinesAndExactExpiry() {
        UUID owner=UUID.randomUUID();var stack=new SpeechBubbleStack(owner);List<String> input=new ArrayList<>(List.of("one","two"));var first=new SpeechBubbleUtterance(input,100);input.clear();var second=new SpeechBubbleUtterance(List.of("three"),200);stack.getUtterances().add(first);stack.getUtterances().add(second);assertEquals(owner,stack.getPlayerId());assertEquals(100,first.getExpiresAt());assertFalse(first.isExpired(99));assertTrue(first.isExpired(100));assertThrows(UnsupportedOperationException.class,()->first.getLines().clear());assertNull(stack.findUtterance(null));assertNull(stack.findUtterance(UUID.randomUUID()));assertSame(second,stack.findUtterance(second.getId()));var lines=stack.buildLayoutLines();assertEquals(List.of("one","two","three"),lines.stream().map(SpeechBubbleStack.LayoutLine::getText).toList());assertSame(first,lines.getFirst().getUtterance());assertEquals(first.getId(),lines.getFirst().getUtteranceId());assertEquals(1,lines.get(1).getLineIndex());stack.getDisplayEntityIds().add(UUID.randomUUID());stack.clear();assertTrue(stack.getUtterances().isEmpty());assertTrue(stack.getDisplayEntityIds().isEmpty());
    }

    @Test void listenerRoutesSmartMessagesAndCleansUpQuitAndChunkUnload() {
        var listener=new SpeechBubbleListener();var smart=new SmartMessageSettings();smart.setEnabled(true);
        try(var smartLoader=mockStatic(SmartMessageLoader.class);var fake=mockStatic(FakeBubbleManager.class)) {
            smartLoader.when(SmartMessageLoader::getSettings).thenReturn(smart);var fakeManager=mock(FakeBubbleManager.class);fake.when(FakeBubbleManager::get).thenReturn(fakeManager);protocol.when(ProtocolLibBridge::isReady).thenReturn(true);
            listener.onCharacterChat(event(player,"smart","handled elsewhere"));assertTrue(displays().isEmpty());protocol.when(ProtocolLibBridge::isReady).thenReturn(false);listener.onCharacterChat(event(player,"smart","fallback"));assertEquals(1,displays().size());listener.onCharacterChat(event(player,null,"ignored"));
            listener.onPlayerQuit(new PlayerQuitEvent(player,"quit"));assertTrue(displays().isEmpty());verify(fakeManager).removeViewer(player.getUniqueId());verify(fakeManager).removeSpeaker(player.getUniqueId());
            chat(player,"rp","unload");var differentChunk=server.addPlayer();differentChunk.teleport(new Location(world,50,64,0));var differentZ=server.addPlayer();differentZ.teleport(new Location(world,0,64,50));var other=server.addPlayer();other.teleport(new Location(server.addSimpleWorld("elsewhere"),0,64,0));listener.onChunkUnload(new ChunkUnloadEvent(world.getChunkAt(0,0)));assertTrue(displays().isEmpty());verify(fakeManager,times(2)).removeSpeaker(player.getUniqueId());verify(fakeManager).removeSpeakerInChunk(world.getName(),0,0);
        }
        var cancelled=event(null,"rp","blocked");listener.onCharacterChatDebug(cancelled);settings.setDebugMessages(true);listener.onCharacterChatDebug(cancelled);cancelled.setCancelled(true);listener.onCharacterChatDebug(cancelled);var withPlayer=event(player,"rp","blocked");withPlayer.setCancelled(true);listener.onCharacterChatDebug(withPlayer);verify(logger,times(2)).info(contains("event-cancelled"));
    }

    @Test void layoutOffsetsAndBobbingUseTheConfiguredSpacingWithoutMutatingPlayerLocation() {
        settings.setBobAmplitude(.2);settings.setBobPeriodTicks(0);var before=player.getLocation();Location layout=BubbleLayoutUtil.desiredLineLocation(player,2,settings,3);assertEquals(before.getY()+1+.18+2*.22+.2*Math.sin(19),layout.getY(),1e-10);assertEquals(before,player.getLocation());
    }

    public static class BubbleFixture extends SpeechBubbleManagerTestFixture {}
}

class SpeechBubbleManagerTestFixture {
    protected ServerMock server;protected World world;protected PlayerMock player;protected RPCharacters plugin;protected Logger logger;protected SpeechBubbleSettings settings;protected SpeechBubbleManager manager;protected FakeBubbleManager fakeManager;protected MockedStatic<ProtocolLibBridge> protocol;protected FakeTextDisplayPackets packets;protected int textDisplaySpawnsBeforeFailure=-1;
    private RuntimeTestState state;private MockedStatic<SpeechBubbleLoader> loader;private MockedStatic<ChatLoader> chatLoader;private MockedStatic<EntityTypesMock> entityTypes;private final List<SingletonState> singletonStates=new ArrayList<>();
    @BeforeEach public void setupBubbles() throws Exception {
        server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class);world=server.addSimpleWorld("bubbles");player=server.addPlayer("Speaker");player.teleport(new Location(world,0,64,0));plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getName()).thenReturn("RPCharacters");when(plugin.namespace()).thenReturn("rpcharacters");when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);when(plugin.getLogger()).thenReturn(logger);RPCharacters.plugin=plugin;
        // MockBukkit implements TextDisplayMock but omits its default entity-factory mapping.
        entityTypes=mockStatic(EntityTypesMock.class,invocation->{if(invocation.getMethod().getName().equals("createEntity")&&invocation.getArgument(0)==TextDisplay.class){if(textDisplaySpawnsBeforeFailure==0){textDisplaySpawnsBeforeFailure=-1;throw new IllegalStateException("Transient server spawn failure");}if(textDisplaySpawnsBeforeFailure>0)textDisplaySpawnsBeforeFailure--;return new SupportedTextDisplay(invocation.getArgument(1),invocation.getArguments().length==3?invocation.getArgument(2):UUID.randomUUID());}return invocation.callRealMethod();});
        settings=new SpeechBubbleSettings();settings.setBobAmplitude(0);settings.setFollowLerpFactor(.5);settings.setMaxCharactersPerLine(8);settings.setUtteranceTimeoutSeconds(60);loader=mockStatic(SpeechBubbleLoader.class);loader.when(SpeechBubbleLoader::getSettings).thenReturn(settings);chatLoader=mockStatic(ChatLoader.class);chatLoader.when(()->ChatLoader.getChannel("rp")).thenReturn(channel("rp",true,false));chatLoader.when(()->ChatLoader.getChannel("silent")).thenReturn(channel("silent",false,false));chatLoader.when(()->ChatLoader.getChannel("smart")).thenReturn(channel("smart",true,true));protocol=mockStatic(ProtocolLibBridge.class);packets=mock(FakeTextDisplayPackets.class);protocol.when(ProtocolLibBridge::getPackets).thenReturn(packets);
        manager=SpeechBubbleManager.get();fakeManager=FakeBubbleManager.get();singletonStates.add(new SingletonState(manager));singletonStates.add(new SingletonState(fakeManager));
    }
    @AfterEach public void restoreBubbles() throws Exception {
        try {protocol.when(ProtocolLibBridge::getPackets).thenReturn(packets);manager.shutdown();fakeManager.shutdown();}
        finally {for(int i=singletonStates.size()-1;i>=0;i--)singletonStates.get(i).close();if(protocol!=null)protocol.close();if(chatLoader!=null)chatLoader.close();if(loader!=null)loader.close();if(entityTypes!=null)entityTypes.close();if(state!=null)state.close();MockBukkit.unmock();}
    }
    protected ChatChannel channel(String id,boolean bubble,boolean smart){var config=new org.bukkit.configuration.file.YamlConfiguration();config.set("bubble",bubble);config.set("smart-messages",smart);config.set("format","&a{message}");return new ChatChannel(id,config);}
    protected CharacterChatEvent event(org.bukkit.entity.Player sender,String channel,String message){return new CharacterChatEvent(sender,null,channel,message,"Speaker",Set.of(),false,false);}
    protected void chat(org.bukkit.entity.Player sender,String channel,String message){manager.onChat(event(sender,channel,message));}
    protected List<TextDisplay> displays(){return world.getEntities().stream().filter(TextDisplay.class::isInstance).map(TextDisplay.class::cast).filter(d->!d.isDead()).toList();}
    protected NamespacedKey key(String name){return new NamespacedKey(plugin,name);}
    private static final class SupportedTextDisplay extends TextDisplayMock {
        private org.bukkit.entity.Display.Billboard billboard=org.bukkit.entity.Display.Billboard.FIXED;
        SupportedTextDisplay(ServerMock server,UUID id){super(server,id);}
        @Override public void setBillboard(org.bukkit.entity.Display.Billboard billboard){this.billboard=Objects.requireNonNull(billboard);}
        @Override public org.bukkit.entity.Display.Billboard getBillboard(){return billboard;}
    }
    private static final class SingletonState implements AutoCloseable {
        final Object owner;final Map<Field,Object> values=new LinkedHashMap<>();final Map<Field,Map<?,?>> maps=new HashMap<>();
        @SuppressWarnings("rawtypes") SingletonState(Object owner)throws Exception{this.owner=owner;for(Field field:owner.getClass().getDeclaredFields()){if(Modifier.isStatic(field.getModifiers()))continue;field.setAccessible(true);Object value=field.get(owner);values.put(field,value);if(value instanceof Map map){maps.put(field,new HashMap<>(map));map.clear();}else if(!Modifier.isFinal(field.getModifiers())){if(field.getType()==long.class)field.setLong(owner,0);else if(org.bukkit.scheduler.BukkitRunnable.class.isAssignableFrom(field.getType()))field.set(owner,null);}}}
        @SuppressWarnings({"rawtypes","unchecked"}) public void close()throws Exception{for(var entry:values.entrySet()){if(entry.getValue() instanceof Map map){map.clear();map.putAll(maps.get(entry.getKey()));}else if(!Modifier.isFinal(entry.getKey().getModifiers()))entry.getKey().set(owner,entry.getValue());}}
    }
}
