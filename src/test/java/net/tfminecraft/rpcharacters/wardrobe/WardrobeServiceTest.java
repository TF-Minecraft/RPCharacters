package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.*;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class WardrobeServiceTest extends WardrobeFixture {
    @Test void refreshGuardsAndSoftRefreshTimersRespectOnlineActiveCharacters() {
        WardrobeService.refreshActiveAsync(null);WardrobeService.refreshAsync(null,"alpha");WardrobeService.refreshAsync(player,null);WardrobeService.refreshAsync(player," ");var offline=server.addPlayer();offline.disconnect();WardrobeService.refreshActiveAsync(offline);WardrobeService.refreshAsync(offline,"alpha");players.when(()->PlayerManager.get(player)).thenReturn(null);WardrobeService.refreshActiveAsync(player);players.when(()->PlayerManager.get(player)).thenReturn(account);active.set(null);WardrobeService.refreshActiveAsync(player);assertTrue(async.isEmpty());
        WardrobeService.startSoftRefresh(plugin);var first=timerTasks.getFirst();WardrobeService.startSoftRefresh(plugin);verify(first).cancel();timers.getLast().run();assertTrue(async.isEmpty());active.set(character);timers.getLast().run();assertEquals(1,async.size());WardrobeService.stopSoftRefresh();WardrobeService.stopSoftRefresh();verify(timerTasks.getLast()).cancel();
    }

    @Test void successfulPullCachesAppliesAndAcknowledgesOnlyReadyPendingSlots() {
        client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ok(json("alpha","base",true)));client.when(()->ProvinceSystemClient.ackWardrobe(anyString(),anyString(),anyList())).thenReturn(ok("{}"));var callbacks=new ArrayList<String>();WardrobeService.refreshActiveAsync(player,()->callbacks.add("done"));assertNull(WardrobeCache.get(player));async.removeFirst().run();assertNull(WardrobeCache.get(player));main.removeFirst().run();assertEquals(List.of("done"),callbacks);assertEquals("base-value",SkinApplyHelper.readTextures(player).getValue());assertEquals("account-value",WardrobeCache.getAccountSkin(player).getValue());mail.verify(()->MailRecipientDirectory.cacheWardrobeSnapshot(eq(player.getUniqueId()),any(WardrobeSnapshot.class)));async.removeFirst().run();client.verify(()->ProvinceSystemClient.ackWardrobe(player.getUniqueId().toString(),"alpha",List.of("base")));assertTrue(async.isEmpty());
        client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ok(json("alpha","base",false)));WardrobeService.refreshAsync(player," alpha ");drain();assertTrue(async.isEmpty());
    }

    @Test void pullAndAckFailuresAreReportedWithoutPublishingInvalidSnapshots() {
        client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("down"));WardrobeService.refreshActiveAsync(player);drain();verify(logger).log(eq(Level.WARNING),contains("Wardrobe pull failed"));assertNull(WardrobeCache.get(player));client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ok("broken"));WardrobeService.refreshActiveAsync(player);drain();verify(logger).warning(contains("unreadable JSON"));assertNull(WardrobeCache.get(player));
        client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ok(json("alpha","base",true)));client.when(()->ProvinceSystemClient.ackWardrobe(anyString(),anyString(),anyList())).thenReturn(ProvinceSystemClient.SimpleResult.fail("retry"));WardrobeService.refreshActiveAsync(player);drain();verify(logger).log(eq(Level.WARNING),contains("Wardrobe ack failed"));assertNotNull(WardrobeCache.get(player));
    }

    @Test void delayedPullDoesNotPublishAfterLogoutOrCharacterChange() {
        client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ok(json("alpha","base",false)));WardrobeService.refreshActiveAsync(player);async.removeFirst().run();active.set(null);main.removeFirst().run();assertNull(WardrobeCache.get(player));active.set(character);WardrobeService.refreshActiveAsync(player);async.removeFirst().run();active.set(otherCharacter("beta"));main.removeFirst().run();assertNull(WardrobeCache.get(player));active.set(character);WardrobeService.refreshActiveAsync(player);async.removeFirst().run();players.when(()->PlayerManager.get(player)).thenReturn(null);main.removeFirst().run();assertNull(WardrobeCache.get(player));players.when(()->PlayerManager.get(player)).thenReturn(account);WardrobeService.refreshActiveAsync(player);async.removeFirst().run();player.disconnect();main.removeFirst().run();assertNull(WardrobeCache.get(player));
    }

    @Test void responseForAnotherCharacterMustNotReplaceTheCurrentWardrobe() {
        client.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ok(json("beta","base",true)));client.when(()->ProvinceSystemClient.ackWardrobe(anyString(),anyString(),anyList())).thenReturn(ok("{}"));var done=new ArrayList<String>();WardrobeService.refreshActiveAsync(player,()->done.add("done"));drain();assertNull(WardrobeCache.get(player));assertTrue(done.isEmpty());client.verify(()->ProvinceSystemClient.ackWardrobe(anyString(),anyString(),anyList()),never());assertEquals("account-value",SkinApplyHelper.readTextures(player).getValue());
    }

    @Test void applyPrefersMaskThenActiveThenOriginalAccountSkin() {
        WardrobeService.applyFor(null);WardrobeService.applyFor(player);assertEquals("account-value",WardrobeCache.getAccountSkin(player).getValue());var slots=new LinkedHashMap<String,WardrobeSlotData>();slots.put("base",slot("base",true,true,"Base"));slots.put("masked",slot("masked",true,true,"Masked"));var snapshot=new WardrobeSnapshot("alpha","base",3,slots);WardrobeCache.put(player.getUniqueId(),snapshot);masks.when(()->MaskService.isMasked(player)).thenReturn(true);WardrobeService.applyFor(player);assertEquals("masked-value",SkinApplyHelper.readTextures(player).getValue());slots.remove("masked");WardrobeService.applyFor(player);assertEquals("base-value",SkinApplyHelper.readTextures(player).getValue());masks.when(()->MaskService.isMasked(player)).thenReturn(false);snapshot.setActiveSlot("missing");WardrobeService.applyFor(player);assertEquals("account-value",SkinApplyHelper.readTextures(player).getValue());snapshot.setActiveSlot(null);WardrobeService.applyFor(player);player.disconnect();WardrobeService.applyFor(player);
    }

    @Test void activeSelectionRejectsInvalidUnavailableAndLockedSlots() {
        var errors=new ArrayList<String>();WardrobeService.setActiveAndApply(null,"base",errors::add);WardrobeService.setActiveAndApply(null,"base",null);WardrobeService.setActiveAndApply(player,"masked",errors::add);WardrobeService.setActiveAndApply(player,null,null);WardrobeService.setActiveAndApply(player,"base",errors::add);WardrobeService.setActiveAndApply(player,"base",null);cache(new WardrobeSnapshot("alpha",null,3,new HashMap<>()));WardrobeService.setActiveAndApply(player,"base",errors::add);WardrobeService.setActiveAndApply(player,"base",null);cache(snapshot());active.set(null);WardrobeService.setActiveAndApply(player,"base",errors::add);WardrobeService.setActiveAndApply(player,"base",null);assertEquals(5,errors.size());assertTrue(errors.getFirst().contains("offline"));assertTrue(errors.get(1).contains("Unknown"));assertTrue(errors.get(2).contains("loading"));assertTrue(errors.get(3).contains("empty or locked"));assertTrue(errors.get(4).contains("active character"));assertTrue(async.isEmpty());
    }

    @Test void selectingASignedSlotPersistsThenUpdatesCacheAndProfile() {
        cache(snapshot());client.when(()->ProvinceSystemClient.setWardrobeActive(anyString(),anyString(),anyString())).thenReturn(ok("{}"));var errors=new ArrayList<String>();WardrobeService.setActiveAndApply(player,"skin2",errors::add);assertEquals("base",WardrobeCache.get(player).getActiveSlot());drain();assertEquals(Collections.singletonList(null),errors);assertEquals("extra_1",WardrobeCache.get(player).getActiveSlot());assertEquals("extra_1-value",SkinApplyHelper.readTextures(player).getValue());client.verify(()->ProvinceSystemClient.setWardrobeActive(player.getUniqueId().toString(),"alpha","extra_1"));WardrobeService.setActiveAndApply(player,"base",null);drain();
    }

    @Test void selectionFailureAndLogoutAreDeliveredToTheCallbackWithoutApplying() {
        cache(snapshot());var errors=new ArrayList<String>();client.when(()->ProvinceSystemClient.setWardrobeActive(anyString(),anyString(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("denied"));WardrobeService.setActiveAndApply(player,"extra_1",errors::add);drain();assertEquals(List.of("denied"),errors);client.when(()->ProvinceSystemClient.setWardrobeActive(anyString(),anyString(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail(null));WardrobeService.setActiveAndApply(player,"extra_1",errors::add);drain();assertTrue(errors.getLast().contains("Could not set"));WardrobeService.setActiveAndApply(player,"extra_1",null);drain();WardrobeService.setActiveAndApply(player,"extra_1",errors::add);async.removeFirst().run();player.disconnect();main.removeFirst().run();assertEquals("Player went offline.",errors.getLast());assertEquals("base",WardrobeCache.get(player).getActiveSlot());
    }

    @Test void selectionCompletionCannotApplyAnOldCharactersSkinAfterSwitching() {
        cache(snapshot());client.when(()->ProvinceSystemClient.setWardrobeActive(anyString(),anyString(),anyString())).thenReturn(ok("{}"));var errors=new ArrayList<String>();WardrobeService.setActiveAndApply(player,"extra_1",errors::add);async.removeFirst().run();active.set(otherCharacter("beta"));var unrelated=server.createInventory(null,9,"New character menu");player.openInventory(unrelated);main.removeFirst().run();assertEquals("account-value",SkinApplyHelper.readTextures(player).getValue());assertSame(unrelated,player.getOpenInventory().getTopInventory());assertEquals("base",WardrobeCache.get(player).getActiveSlot());assertFalse(errors.isEmpty());assertNotNull(errors.getFirst());
    }

    @Test void labelsAndAliasesAreStableAcrossLocales() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertNull(WardrobeService.normalizeSwappable(null));assertNull(WardrobeService.normalizeSwappable("masked"));for(String alias:List.of("base","1","skin1","skin_1"))assertEquals("base",WardrobeService.normalizeSwappable(alias));for(String alias:List.of("extra_1","extra1","2","SKIN2","skin_2"))assertEquals("extra_1",WardrobeService.normalizeSwappable(alias));for(String alias:List.of("extra_2","extra2","3","SKIN3","skin_3"))assertEquals("extra_2",WardrobeService.normalizeSwappable(alias));assertEquals("Skin",WardrobeService.labelForSlot((String)null));assertEquals("Base",WardrobeService.labelForSlot("BASE"));assertEquals("Skin 2",WardrobeService.labelForSlot("extra_1"));assertEquals("Skin 3",WardrobeService.labelForSlot("extra_2"));assertEquals("Masked",WardrobeService.labelForSlot("masked"));assertEquals("custom",WardrobeService.labelForSlot("custom"));assertEquals("Base",WardrobeService.labelForSlot(null,"base"));assertEquals("Skin",WardrobeService.labelForSlot(snapshot(),null));assertEquals("Skin 2",WardrobeService.labelForSlot(snapshot(),"extra_1"));assertEquals("absent",WardrobeService.labelForSlot(snapshot(),"absent"));
    }

    @Test void snapshotsParseRealRowsAndIgnoreMalformedOptionalFields() {
        for(String bad:Arrays.asList(null," ","broken","[]","null"))assertNull(WardrobeSnapshot.parse(bad));var empty=WardrobeSnapshot.parse("{}");assertNull(empty.getActiveSlot());assertEquals(1,empty.getSwappableSlots());assertNull(empty.getSlot(null));assertNull(empty.getSlot("missing"));var parsed=WardrobeSnapshot.parse("{\"character_id\":\"alpha\",\"active_slot\":\" BASE \",\"swappable_slots\":3,\"slots\":[null,1,{}, {\"slot\":\"BASE\",\"unlocked\":\"1\",\"filled\":\"true\",\"has_signature\":true,\"model\":\"slim\",\"display_name\":\" Summer \",\"texture_value\":\"value\",\"texture_signature\":\"sig\",\"apply_pending\":true},{\"slot\":\"extra_1\",\"unlocked\":\"0\",\"filled\":\"false\",\"signed\":\"unknown\"}]}");assertEquals("alpha",parsed.getCharacterId());assertEquals(3,parsed.getSwappableSlots());var base=parsed.getSlot("BASE");assertTrue(base.isUnlocked());assertTrue(base.isFilled());assertTrue(base.isSigned());assertTrue(base.isApplyPending());assertEquals("slim",base.getModel());assertEquals("Summer",base.getDisplayName());assertTrue(base.canApply());assertFalse(parsed.getSlot("extra_1").canApply());assertNull(parsed.getSlot("extra_1").getModel());assertThrows(UnsupportedOperationException.class,()->parsed.getSlots().clear());
    }

    @Test void skinTexturesValidateCompareAndCacheOriginalAndLastAppliedValues() {
        var valid=new SkinTextures("value","signature");assertEquals("value",valid.getValue());assertEquals("signature",valid.getSignature());assertTrue(valid.isValid());assertTrue(valid.sameTextures(new SkinTextures("value","signature")));assertFalse(valid.sameTextures((SkinTextures)null));assertFalse(valid.sameTextures("other","signature"));assertFalse(valid.sameTextures("value","other"));for(String missing:Arrays.asList(null,"")){assertFalse(new SkinTextures(missing,"sig").isValid());assertFalse(new SkinTextures("value",missing).isValid());assertFalse(valid.sameTextures(missing,"sig"));assertFalse(valid.sameTextures("value",missing));}assertFalse(new SkinTextures(null,null).sameTextures("value","sig"));WardrobeCache.put(null,snapshot());WardrobeCache.put(player.getUniqueId(),null);assertNull(WardrobeCache.get((UUID)null));assertNull(WardrobeCache.get((Player)null));assertNull(WardrobeCache.getAccountSkin((UUID)null));assertNull(WardrobeCache.getAccountSkin((Player)null));assertNull(WardrobeCache.getLastApplied((UUID)null));assertNull(WardrobeCache.getLastApplied((Player)null));WardrobeCache.captureAccountSkinIfNeeded(null);WardrobeCache.setLastApplied(null,valid);WardrobeCache.setLastApplied(player,null);WardrobeCache.setLastApplied(player,new SkinTextures(null,null));WardrobeCache.captureAccountSkinIfNeeded(player);WardrobeCache.captureAccountSkinIfNeeded(player);WardrobeCache.setLastApplied(player,valid);assertSame(valid,WardrobeCache.getLastApplied(player));cache(snapshot());WardrobeCache.clear((UUID)null);WardrobeCache.clear((Player)null);WardrobeCache.clear(player);assertNull(WardrobeCache.get(player));assertNull(WardrobeCache.getAccountSkin(player));assertNull(WardrobeCache.getLastApplied(player));
    }

    @Test void profileApplicationReplacesOnlyTexturesAndSkipsUnchangedProfiles() {
        assertNull(SkinApplyHelper.readTextures(null));assertFalse(SkinApplyHelper.apply(null,"v","s"));assertFalse(SkinApplyHelper.apply(player,(SkinTextures)null));assertFalse(SkinApplyHelper.apply(player,new SkinTextures(null,null)));for(String absent:Arrays.asList(null,"")){assertFalse(SkinApplyHelper.apply(player,absent,"s"));assertFalse(SkinApplyHelper.apply(player,"v",absent));}var profile=player.getPlayerProfile();profile.setProperty(new ProfileProperty("other","retain"));player.setPlayerProfile(profile);assertTrue(SkinApplyHelper.apply(player,new SkinTextures("next","signed")));assertEquals("next",SkinApplyHelper.readTextures(player).getValue());assertTrue(player.getPlayerProfile().hasProperty("other"));assertTrue(SkinApplyHelper.apply(player,"next","signed"));assertEquals("next",WardrobeCache.getLastApplied(player).getValue());profile=player.getPlayerProfile();profile.removeProperty("textures");player.setPlayerProfile(profile);assertNull(SkinApplyHelper.readTextures(player));profile.setProperty(new ProfileProperty("textures","unsigned"));player.setPlayerProfile(profile);assertNull(SkinApplyHelper.readTextures(player));
        Player broken=mock(Player.class);when(broken.getName()).thenReturn("Broken");when(broken.getUniqueId()).thenReturn(UUID.randomUUID());when(broken.getPlayerProfile()).thenThrow(new IllegalStateException("profile unavailable"));assertNull(SkinApplyHelper.readTextures(broken));assertFalse(SkinApplyHelper.apply(broken,"value","signature"));verify(logger).log(eq(Level.WARNING),contains("Failed to apply wardrobe skin"),any(Throwable.class));
    }
    @Test void selectionRejectsCachedSlotsFromThePreviousCharacterBeforeAnyWebWrite() {
        cache(snapshot());active.set(otherCharacter("beta"));var errors=new ArrayList<String>();WardrobeService.setActiveAndApply(player,"extra_1",errors::add);assertTrue(async.isEmpty(),"A stale cache must never choose a slot for the new character");assertFalse(errors.isEmpty());assertNotNull(errors.getFirst());client.verifyNoInteractions();
    }
    @Test void directApplyCannotUseThePreviousCharactersCachedSkin() {
        cache(snapshot());active.set(otherCharacter("beta"));WardrobeService.applyFor(player);assertEquals("account-value",SkinApplyHelper.readTextures(player).getValue());
    }
}

abstract class WardrobeFixture {
    ServerMock server;PlayerMock player;RPCharacters plugin;Logger logger;PlayerData account;RPCharacter character;final AtomicReference<RPCharacter> active=new AtomicReference<>();RuntimeTestState state;BukkitScheduler scheduler;MockedStatic<PlayerManager> players;MockedStatic<ProvinceSystemClient> client;MockedStatic<MaskService> masks;MockedStatic<MailRecipientDirectory> mail;MockedStatic<Bukkit> bukkit;final Deque<Runnable> async=new ArrayDeque<>(),main=new ArrayDeque<>();final List<Runnable> timers=new ArrayList<>();final List<BukkitTask> timerTasks=new ArrayList<>();
    @BeforeEach void setupWardrobe(){server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,WardrobeCache.class,WardrobeService.class);player=new WardrobePlayer(server,"Wardrobe");server.addPlayer(player);plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getName()).thenReturn("RPCharacters");when(plugin.namespace()).thenReturn("rpcharacters");when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);when(plugin.getLogger()).thenReturn(logger);RPCharacters.plugin=plugin;var profile=player.getPlayerProfile();profile.setProperty(new ProfileProperty("textures","account-value","account-signature"));player.setPlayerProfile(profile);account=mock(PlayerData.class);character=otherCharacter("alpha");active.set(character);when(account.hasActiveCharacter()).thenAnswer(c->active.get()!=null);when(account.getActiveCharacter()).thenAnswer(c->active.get());players=mockStatic(PlayerManager.class);players.when(()->PlayerManager.get(player)).thenReturn(account);client=mockStatic(ProvinceSystemClient.class);masks=mockStatic(MaskService.class);mail=mockStatic(MailRecipientDirectory.class);scheduler=mock(BukkitScheduler.class);bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenAnswer(c->{async.add(c.getArgument(1));return mock(BukkitTask.class);});when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(c->{main.add(c.getArgument(1));return mock(BukkitTask.class);});when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),eq(900L),eq(900L))).thenAnswer(c->{timers.add(c.getArgument(1));var task=mock(BukkitTask.class);timerTasks.add(task);return task;});Cache.permissionGroups=new ArrayList<>();Cache.permissionGroupDefaults=new HashMap<>();}
    @AfterEach void restoreWardrobe(){try{WardrobeService.stopSoftRefresh();}finally{for(var boundary:new MockedStatic<?>[]{bukkit,mail,masks,client,players})if(boundary!=null)boundary.close();state.close();MockBukkit.unmock();}}
    RPCharacter otherCharacter(String id){var result=mock(RPCharacter.class);when(result.getId()).thenReturn(id);return result;}
    static ProvinceSystemClient.SimpleResult ok(String body){return ProvinceSystemClient.SimpleResult.success(body);}
    static WardrobeSlotData slot(String id,boolean unlocked,boolean filled,String label){return new WardrobeSlotData(id,unlocked,filled,true,false,"slim",label,id+"-value",id+"-signature");}
    WardrobeSnapshot snapshot(){return new WardrobeSnapshot("alpha","base",3,new LinkedHashMap<>(Map.of("base",slot("base",true,true,"Base"),"extra_1",slot("extra_1",true,true,null),"extra_2",slot("extra_2",false,false,null))));}
    void cache(WardrobeSnapshot snapshot){WardrobeCache.put(player.getUniqueId(),snapshot);}
    void drain(){int count=0;while(!async.isEmpty()||!main.isEmpty()){assertTrue(count++<20,"Unexpected task loop");while(!async.isEmpty())async.removeFirst().run();while(!main.isEmpty())main.removeFirst().run();}}
    public static final class WardrobePlayer extends PlayerMock {
        private org.bukkit.inventory.InventoryView defaultView;
        WardrobePlayer(ServerMock server,String name){super(server,name);}
        @Override public org.bukkit.inventory.InventoryView getOpenInventory(){
            var current=super.getOpenInventory();
            if(current.getTopInventory()!=null)return current;
            // MockBukkit leaves the normal closed-window crafting inventory null.
            if(defaultView==null)defaultView=new org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock(this,new org.mockbukkit.mockbukkit.inventory.InventoryMock(this,org.bukkit.event.inventory.InventoryType.CRAFTING));
            return defaultView;
        }
    }
    static String json(String characterId,String active,boolean pending){return "{\"character_id\":\""+characterId+"\",\"active_slot\":\""+active+"\",\"slots\":[{\"slot\":\"base\",\"unlocked\":true,\"filled\":true,\"texture_value\":\"base-value\",\"texture_signature\":\"base-signature\",\"apply_pending\":"+pending+"},{\"slot\":\"extra_1\",\"apply_pending\":true}]}";}
}
