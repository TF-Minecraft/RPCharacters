package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.progress.CharacterProgressService;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;
import net.tfminecraft.rpcharacters.pvp.ArmourDonning;
import net.tfminecraft.rpcharacters.pvp.Duels;
import net.tfminecraft.rpcharacters.pvp.PvpKnockoutManager;
import net.tfminecraft.rpcharacters.lifecycle.CharacterSelectionRequestEvent;
import net.tfminecraft.rpcharacters.wardrobe.SkinApplyHelper;
import net.tfminecraft.rpcharacters.wardrobe.SkinTextures;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeCache;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason;
import net.tfminecraft.tlibs.character.Characters;

/** Exercises the join/select/stow transaction with persistence and server boundaries independently tested. */
class CharacterBodiesTest {
    @TempDir Path temp;
    final UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
    List<AutoCloseable> mocks;
    List<Runnable> queued;
    RPCharacters plugin;
    Player player, other;
    PlayerData account;
    RPCharacter a, b;
    World world, selection;
    Location spawn, room;
    BodySnapshot snapshot;
    BodyStore store;
    BodyLeaseStore leases, diskLeases;
    CharacterBodyMigration migration;
    CharacterProgressService progress;
    Database database;
    CharacterBodies bodies;
    PlayerManager manager;
    CharacterTransitions transitions;
    MockedStatic<BodyTag> tags;
    MockedStatic<CharacterSelectView> view;
    MockedStatic<PlayerManager> players;
    MockedStatic<PermissionGroupService> groups;
    MockedStatic<Characters> sessions;
    MockedStatic<Bukkit> bukkit;
    MockedStatic<RPCharacters> rpc;
    MockedStatic<BodyRespawn> respawns;
    MockedStatic<SkinApplyHelper> skins;
    MockedStatic<WardrobeCache> wardrobes;
    MockedStatic<WardrobeService> wardrobeService;
    MockedStatic<net.tfminecraft.rpcharacters.evilrp.EvilRpService> evil;
    MockedStatic<Duels> duels;
    MockedStatic<ArmourDonning> donning;
    Runnable timer;
    org.bukkit.scheduler.BukkitTask timerTask;
    MockedConstruction<BodyStore> stores;
    MockedConstruction<BodyLeaseStore> leaseStores;
    MockedConstruction<CharacterBodyMigration> migrations;
    MockedConstruction<CharacterProgressService> progresses;
    BodyTag tag;
    CharacterSelectView.Actions actions;
    YamlConfiguration config;
    CharacterPackGate packs;
    java.util.function.BooleanSupplier packCurrent;
    Runnable packResume;

    @BeforeEach void setup() throws Exception {
        mocks = new ArrayList<>(); queued = new ArrayList<>();
        plugin=mock(RPCharacters.class); when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CharacterBodiesTest"));
        player=mock(Player.class); other=mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        world=mock(World.class); selection=mock(World.class);
        when(world.getName()).thenReturn("world"); when(selection.getName()).thenReturn("selection");
        spawn=new Location(world, 15,70,25); room=new Location(selection,.5,64,.5);
        when(world.getSpawnLocation()).thenReturn(spawn); when(player.getLocation()).thenReturn(spawn);
        when(player.teleport(any(Location.class))).thenReturn(true);
        when(world.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        var inventory=mock(PlayerInventory.class); when(inventory.isEmpty()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        var ender=mock(Inventory.class); when(ender.isEmpty()).thenReturn(true); when(player.getEnderChest()).thenReturn(ender);
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(player.getActivePotionEffects()).thenReturn(List.of());
        account=new PlayerData(player); a=character(first); b=character(second); account.addCharacter(a); account.addCharacter(b);
        snapshot=mock(BodySnapshot.class); when(snapshot.withLocation(any())).thenReturn(snapshot); when(snapshot.withFullHealth()).thenReturn(snapshot); when(snapshot.location()).thenReturn(spawn);
        var snapshots=mockStatic(BodySnapshot.class); mocks.add(snapshots);
        snapshots.when(()->BodySnapshot.capture(eq(player),anySet())).thenReturn(snapshot);
        bukkit=mockStatic(Bukkit.class); mocks.add(bukkit);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world)); bukkit.when(()->Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player,other));
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        var scheduler=mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        doAnswer(c->{queued.add(c.getArgument(1)); return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        doAnswer(c->{queued.add(c.getArgument(1)); return null;}).when(scheduler).runTaskLater(eq(plugin),any(Runnable.class),anyLong());
        timer=null; timerTask=mock(org.bukkit.scheduler.BukkitTask.class);
        doAnswer(c->{timer=c.getArgument(1); return timerTask;}).when(scheduler).runTaskTimer(eq(plugin),any(Runnable.class),eq(20L),eq(20L));
        var worlds=mockStatic(CharacterSelectionWorld.class); mocks.add(worlds);
        worlds.when(()->CharacterSelectionWorld.create(anyString())).thenReturn(room);
        var dbs=mockConstruction(Database.class,(db,c)-> {when(db.loadPlayerData(owner)).thenReturn(account);when(db.trySavePlayer(account)).thenReturn(true);});mocks.add(dbs);
        stores=mockConstruction(BodyStore.class);mocks.add(stores);
        diskLeases=new BodyLeaseStore(temp.resolve("leases"));
        leaseStores=mockConstruction(BodyLeaseStore.class);mocks.add(leaseStores);
        migrations=mockConstruction(CharacterBodyMigration.class);mocks.add(migrations);
        progresses=mockConstruction(CharacterProgressService.class);mocks.add(progresses);
        players=mockStatic(PlayerManager.class);mocks.add(players);players.when(()->PlayerManager.get(player)).thenReturn(account);players.when(PlayerManager::getOnlineData).thenReturn(List.of(account));
        rpc=mockStatic(RPCharacters.class);mocks.add(rpc);manager=mock(PlayerManager.class);transitions=mock(CharacterTransitions.class);
        rpc.when(RPCharacters::getPlayerManager).thenReturn(manager);rpc.when(RPCharacters::getCharacterTransitions).thenReturn(transitions);
        skins=mockStatic(SkinApplyHelper.class);mocks.add(skins);wardrobes=mockStatic(WardrobeCache.class);mocks.add(wardrobes);
        duels=mockStatic(Duels.class);mocks.add(duels);donning=mockStatic(ArmourDonning.class);mocks.add(donning);
        wardrobeService=mockStatic(WardrobeService.class);mocks.add(wardrobeService);
        for(Class<?> type:List.of(ProfessionIntegrator.class,MmoInventorySlots.class,net.tfminecraft.rpcharacters.joinsafety.JoinUnstuckListener.class))mocks.add(mockStatic(type));
        evil=mockStatic(net.tfminecraft.rpcharacters.evilrp.EvilRpService.class);mocks.add(evil);
        groups=mockStatic(PermissionGroupService.class);mocks.add(groups);
        sessions=mockStatic(Characters.class);mocks.add(sessions);
        tags=mockStatic(BodyTag.class);mocks.add(tags);tag=new BodyTag.Untagged();tags.when(()->BodyTag.read(player)).thenAnswer(c->tag);
        tags.when(()->BodyTag.room(eq(player),any())).thenAnswer(c->{tag=new BodyTag.Room(c.getArgument(1));return null;});
        tags.when(()->BodyTag.live(eq(player),any(),anyLong())).thenAnswer(c->{tag=new BodyTag.Live(c.getArgument(1),c.getArgument(2));return null;});
        view=mockStatic(CharacterSelectView.class);mocks.add(view);view.when(()->CharacterSelectView.open(eq(player),eq(account),any())).thenAnswer(c->{actions=c.getArgument(2);return null;});
        config=new YamlConfiguration(); config.set("persistent-keys",List.of("accessory:test"));
        var packGates=mockConstruction(CharacterPackGate.class,(gate,context)-> {
            when(gate.ready(any())).thenReturn(true);
            doAnswer(call->{packCurrent=call.getArgument(1);packResume=call.getArgument(2);return null;})
                    .when(gate).await(any(),any(),any());
        }); mocks.add(packGates);
        respawns=mockStatic(BodyRespawn.class); mocks.add(respawns);
        bodies=new CharacterBodies(plugin,config);rpc.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        packs=packGates.constructed().getFirst();
        database=dbs.constructed().getFirst();store=stores.constructed().getFirst();migration=migrations.constructed().getFirst();progress=progresses.constructed().getFirst();
        leases=leaseStores.constructed().getFirst();
        when(migration.initialize(account,snapshot)).thenReturn(first);when(migration.migrated(owner)).thenReturn(true);
        when(store.read(owner,first)).thenReturn(Optional.of(record(first,1)));
        when(store.read(owner,second)).thenReturn(Optional.of(record(second,1)));
    }
    RPCharacter character(UUID id) {
        RPCharacter c=mock(RPCharacter.class);when(c.getId()).thenReturn(id.toString());when(c.getStatus()).thenReturn(Status.ALIVE);
        doAnswer(call->{when(c.isActive()).thenReturn(call.getArgument(0));return null;}).when(c).setActiveState(anyBoolean());
        when(c.tryActivate()).thenReturn(true);return c;
    }
    BodyRecord record(UUID id,long rev){return new BodyRecord(owner,id,rev,1,Instant.now(),"test",snapshot);}
    void drain(){for(int i=0;!queued.isEmpty()&&i<20;i++)queued.removeFirst().run();}
    void ready(){bodies.join(player);bodies.takeAccount(player);bodies.prepare(account);drain();}
    void play(){ready();bodies.select(account,a);drain();assertTrue(bodies.hasLiveBody(player));}

    @Test void requiredPackWaitsInsideRoomAfterRecoveryWithoutOpeningSelectionOrTeleportingTwice() throws Exception {
        tag=new BodyTag.Live(first,1); when(packs.ready(player)).thenReturn(false);
        bodies.join(player); bodies.prepare(account); queued.removeFirst().run();
        verify(store).write(owner,first,1,"join-recovery",snapshot);
        verify(player,times(2)).saveData(); verify(leases).complete(owner,new BodyTag.Live(first,1));
        InOrder order=inOrder(player,packs);
        order.verify(player).saveData(); order.verify(player).teleport(room); order.verify(player).saveData();
        order.verify(packs).await(eq(player),any(),any());
        verify(progress,never()).prepare(account);
        assertTrue(bodies.isSelecting(player)); assertFalse(bodies.isReadyToChoose(player)); assertNull(actions);
        bodies.select(account,a); verify(leases,never()).begin(any(),any()); assertTrue(packCurrent.getAsBoolean());
        when(packs.ready(player)).thenReturn(true); packResume.run();
        verify(player).teleport(room); verify(progress).prepare(account); assertNotNull(actions);
        assertTrue(bodies.isReadyToChoose(player));
        var original=actions; when(packs.ready(player)).thenReturn(false);
        assertFalse(original.isCurrent(player,account));
        assertEquals("The TFMC resource pack is still loading.",bodies.blockReason(account,a));
        bodies.select(account,a); verify(leases,never()).begin(any(),any());
        bodies.show(player); assertSame(original,actions);
    }

    @Test void pendingPackContinuationCannotResumeAfterQuitOrNewRoomEntry() throws Exception {
        when(packs.ready(player)).thenReturn(false); bodies.join(player); bodies.prepare(account); drain();
        verify(player).teleport(room); clearInvocations(player);
        var oldCurrent=packCurrent; var oldResume=packResume; assertTrue(oldCurrent.getAsBoolean());
        bodies.quit(player); verify(packs).quit(player); assertFalse(oldCurrent.getAsBoolean());
        when(packs.ready(player)).thenReturn(true); oldResume.run();
        verify(player,never()).teleport(room); verify(progress,never()).prepare(account);
        bodies.join(player); assertFalse(oldCurrent.getAsBoolean());
    }

    @Test void cancelledEarlyRoomTeleportStillOpensLoadingBeforePackAndRetriesAfterItLoads() throws Exception {
        when(packs.ready(player)).thenReturn(false);
        when(player.teleport(room)).thenReturn(false,false,true);
        bodies.join(player); bodies.prepare(account); queued.removeFirst().run();
        verify(packs).await(eq(player),any(),any()); assertTrue(packCurrent.getAsBoolean());
        assertNull(actions); assertTrue(queued.isEmpty()); verify(progress,never()).prepare(account);
        when(packs.ready(player)).thenReturn(true); packResume.run();
        assertNull(actions); assertEquals(1,queued.size()); queued.removeFirst().run();
        verify(player,times(3)).teleport(room); verify(progress).prepare(account); assertNotNull(actions);
    }

    @Test void preparationAlsoWaitsIfPackProviderBecomesUnavailableAfterRoomEntry() throws Exception {
        bodies.join(player); drain(); verify(player).teleport(room);
        when(packs.ready(player)).thenReturn(false); bodies.prepare(account);
        verify(progress,never()).prepare(account); assertNull(actions); assertTrue(packCurrent.getAsBoolean());
        when(packs.ready(player)).thenReturn(true); packResume.run();
        verify(progress).prepare(account); assertNotNull(actions);
        bodies.shutdown(); verify(packs).close();
    }

    @Test void deadPlayerCannotRespawnOrSelectUntilRequiredPackFinishes() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true);
        when(packs.ready(player)).thenReturn(false);
        respawns.when(()->BodyRespawn.respawn(player)).thenAnswer(call->{
            when(player.isDead()).thenReturn(false); bodies.respawned(player); return null;
        });
        bodies.join(player); bodies.prepare(account); drain(); respawns.verifyNoInteractions(); assertNull(actions);
        when(packs.ready(player)).thenReturn(true); packResume.run(); drain();
        respawns.verify(()->BodyRespawn.respawn(player)); verify(progress).prepare(account); assertNotNull(actions);
    }
    void useRealLeases() throws Exception {
        doAnswer(c->{diskLeases.validate(c.getArgument(0),c.getArgument(1));return null;}).when(leases).validate(any(),any());
        doAnswer(c->{diskLeases.begin(c.getArgument(0),c.getArgument(1));return null;}).when(leases).begin(any(),any());
        doAnswer(c->{diskLeases.complete(c.getArgument(0),c.getArgument(1));return null;}).when(leases).complete(any(),any());
        doAnswer(c->{diskLeases.markRespawn(c.getArgument(0),c.getArgument(1));return null;}).when(leases).markRespawn(any(),any());
        doAnswer(c->diskLeases.pendingRespawn(c.getArgument(0),c.getArgument(1))).when(leases).pendingRespawn(any(),any());
        doAnswer(c->{diskLeases.completeRespawn(c.getArgument(0),c.getArgument(1));return null;}).when(leases).completeRespawn(any(),any());
        doAnswer(c->diskLeases.respawnCompleted(c.getArgument(0),c.getArgument(1))).when(leases).respawnCompleted(any(),any());
    }
    @AfterEach void cleanup() throws Exception {CreationManager.activeCreators.remove(player);for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();}

    @Test void selectionWaitsForVanillaMountLoadingAndLeavesTheVehicleTreeInItsWorld() throws Exception {
        bodies.join(player); bodies.prepare(account);
        assertTrue(bodies.isSelecting(player)); assertNull(actions); verify(progress,never()).prepare(account);
        verify(player,never()).teleport(room); verify(player,never()).saveData();
        // Paper restores the vehicle only after PlayerJoinEvent has finished.
        var mount=mock(org.bukkit.entity.Donkey.class);
        when(player.getVehicle()).thenReturn(mount);
        when(player.leaveVehicle()).thenAnswer(call->{when(player.getVehicle()).thenReturn(null);return true;});
        drain();
        InOrder order=inOrder(player,progress);
        order.verify(player).leaveVehicle(); order.verify(player).saveData(); order.verify(player).teleport(room);
        order.verify(player).saveData(); order.verify(progress).prepare(account);
        verifyNoInteractions(mount); assertNotNull(actions); assertEquals(new BodyTag.Room(first),tag);
        verify(transitions,never()).selected(any());
    }

    @Test void roomEntryBeforeMmoReadyWaitsForItsExplicitPrepareAndOldEntryCallbacksDoNothing() throws Exception {
        bodies.join(player); drain(); verify(player).teleport(room);
        assertNull(actions); verify(progress,never()).prepare(any());
        bodies.prepare(account); assertNotNull(actions);
        bodies.quit(player); bodies.join(player); bodies.prepare(account);
        clearInvocations(player,progress); bodies.quit(player); drain();
        verify(player,never()).teleport(any(Location.class)); verify(progress,never()).prepare(any());
    }

    @Test void refusedDismountNeverOpensSelectionOrMovesTheVehicle() throws Exception {
        var mount=mock(org.bukkit.entity.Donkey.class); when(player.getVehicle()).thenReturn(mount);
        ready(); verify(player).kickPlayer(contains("needs attention")); assertNull(actions);
        verifyNoInteractions(mount); verify(player,never()).teleport(room); verify(progress,never()).prepare(account);
        assertFalse(bodies.isApplying(player));
    }

    @Test void resourcePackCanDelayRoomEntryWithoutRepeatingDismountOrOpeningSelectionEarly() throws Exception {
        bodies.join(player); bodies.prepare(account);
        var mount=mock(org.bukkit.entity.Donkey.class); when(player.getVehicle()).thenReturn(mount);
        when(player.leaveVehicle()).thenAnswer(call->{when(player.getVehicle()).thenReturn(null);return true;});
        when(player.teleport(room)).thenReturn(false,false,true);
        queued.removeFirst().run(); queued.removeFirst().run();
        verify(player,times(1)).leaveVehicle(); verifyNoInteractions(mount);
        verify(player,times(1)).sendMessage(contains("Loading character selection"));
        verify(player,never()).kickPlayer(anyString()); assertNull(actions); verify(progress,never()).prepare(account);
        verify(leases,never()).begin(any(),any()); assertEquals(new BodyTag.Room(first),tag);
        assertTrue(bodies.isSelecting(player)); assertFalse(bodies.isApplying(player));
        queued.removeFirst().run(); verify(progress).prepare(account); assertNotNull(actions);
        verify(player,times(1)).leaveVehicle(); assertTrue(queued.isEmpty());
    }

    @Test void cancelledRoomTeleportCannotScheduleRetryAfterTheConnectionCloses() throws Exception {
        bodies.join(player); bodies.prepare(account); clearInvocations(player);
        when(player.teleport(room)).thenAnswer(call->{when(player.isOnline()).thenReturn(false);return false;});
        drain(); assertTrue(queued.isEmpty()); assertNull(actions); verify(player,times(1)).saveData();
        verify(player,never()).sendMessage(anyString()); verify(progress,never()).prepare(account);
    }

    @Test void disconnectDuringRoomEntryCannotSaveOrPrepareTheOldConnection() throws Exception {
        bodies.join(player); bodies.prepare(account); clearInvocations(player,progress);
        when(player.teleport(room)).thenAnswer(call->{when(player.isOnline()).thenReturn(false);return true;});
        drain(); verify(player,times(1)).saveData(); verify(progress,never()).prepare(account); assertNull(actions);
        assertFalse(bodies.isApplying(player));
    }

    @Test void offlineRoomEntryNeverDetachesOrTeleportsTheOldPlayer() throws Exception {
        bodies.join(player); when(player.isOnline()).thenReturn(false); drain();
        verify(player,never()).leaveVehicle(); verify(player,never()).teleport(room);
    }

    @Test void liveRecoveryRetainsTheDurableLeaseAndRawMountUntilThePostJoinPhase() throws Exception {
        useRealLeases(); diskLeases.begin(owner,new BodyTag.Live(first,1)); tag=new BodyTag.Live(first,1);
        bodies.join(player);
        verify(store).write(owner,first,1,"join-recovery",snapshot);
        verify(player,never()).saveData(); verify(leases,never()).complete(any(),any());
        diskLeases.validate(owner,new BodyTag.Live(first,1));
        var mount=mock(org.bukkit.entity.Donkey.class); when(player.getVehicle()).thenReturn(mount);
        when(player.leaveVehicle()).thenAnswer(call->{when(player.getVehicle()).thenReturn(null);return true;});
        drain();
        InOrder order=inOrder(player,leases);
        order.verify(player).leaveVehicle(); order.verify(player).saveData();
        order.verify(leases).complete(owner,new BodyTag.Live(first,1)); order.verify(player).teleport(room);
        diskLeases.validate(owner,new BodyTag.Room(first)); verifyNoInteractions(mount);
    }

    @Test void oldRoomRetryCannotMoveAReconnectedPlayerOrPrepareTheNewSessionEarly() throws Exception {
        bodies.join(player); bodies.prepare(account); when(player.teleport(room)).thenReturn(false);
        queued.removeFirst().run(); assertEquals(1,queued.size());
        bodies.quit(player); when(player.teleport(room)).thenReturn(true);
        bodies.join(player); bodies.prepare(account); clearInvocations(player,progress);
        queued.removeFirst().run(); verify(player,never()).teleport(room); verify(progress,never()).prepare(account);
        drain(); verify(player).teleport(room); verify(progress).prepare(account); assertNotNull(actions);
    }

    @Test void quitBeforePostJoinFinishesTheCommittedRecoveryWithoutReplayingItsBody() throws Exception {
        useRealLeases(); BodyTag.Live original = new BodyTag.Live(first,1);
        diskLeases.begin(owner,original); tag=original;
        bodies.join(player); bodies.prepare(account);
        assertEquals(new BodyTag.Room(first),tag); verify(player,never()).saveData();
        bodies.quit(player); drain();
        InOrder order=inOrder(store,player,leases);
        order.verify(store).write(owner,first,1,"join-recovery",snapshot);
        order.verify(player).saveData(); order.verify(leases).complete(owner,original);
        diskLeases.validate(owner,tag); assertNull(actions);
        verify(player,never()).teleport(room); verify(progress,never()).prepare(account);
        bodies.join(player); bodies.prepare(account); drain();
        assertNotNull(actions); verify(store,times(1)).write(owner,first,1,"join-recovery",snapshot);
        verify(snapshot,never()).apply(any(),anySet());
    }

    @Test void refusedDismountThenQuitCompletesTheCommittedRecoveryAndKeepsTheMountUntouched() throws Exception {
        useRealLeases(); BodyTag.Live original = new BodyTag.Live(first,1);
        diskLeases.begin(owner,original); tag=original;
        var mount=mock(org.bukkit.entity.Donkey.class); when(player.getVehicle()).thenReturn(mount);
        ready(); verify(player).kickPlayer(contains("needs attention"));
        verify(player,never()).saveData(); diskLeases.validate(owner,original);
        bodies.quit(player); diskLeases.validate(owner,new BodyTag.Room(first));
        verifyNoInteractions(mount); verify(player,never()).teleport(room);
        when(player.getVehicle()).thenReturn(null); ready(); assertNotNull(actions);
        verify(store,times(1)).write(owner,first,1,"join-recovery",snapshot);
    }

    @Test void failedDeferredQuitSaveKeepsTheLeaseAndPreventsAnotherBodyReplay() throws Exception {
        useRealLeases(); BodyTag.Live original = new BodyTag.Live(first,1);
        diskLeases.begin(owner,original); tag=original; bodies.join(player);
        doThrow(new IllegalStateException("disk unavailable")).when(player).saveData();
        assertDoesNotThrow(()->bodies.quit(player)); diskLeases.validate(owner,original);
        verify(leases,never()).complete(any(),any());
        assertThrows(IOException.class,()->diskLeases.validate(owner,tag));
        doNothing().when(player).saveData(); bodies.join(player); drain();
        verify(player).kickPlayer(contains("needs attention"));
        verify(store,times(1)).write(owner,first,1,"join-recovery",snapshot);
        verify(snapshot,never()).apply(any(),anySet());
    }

    @Test void deferredQuitLeaseFailureRemainsFailClosedAndDifferentPlayerCannotCompleteIt() throws Exception {
        useRealLeases(); BodyTag.Live original = new BodyTag.Live(first,1);
        diskLeases.begin(owner,original); tag=original; bodies.join(player);
        Player stale=mock(Player.class); when(stale.getUniqueId()).thenReturn(owner);
        bodies.quit(stale); verify(player,never()).saveData(); verify(leases,never()).complete(any(),any());
        view.verify(()->CharacterSelectView.close(stale)); view.verify(()->CharacterSelectView.close(player),never());
        diskLeases.validate(owner,original);
        // Restore the interrupted connection's pending recovery, then exercise an I/O failure.
        tag=original; when(store.read(owner,first)).thenReturn(Optional.of(record(first,2)));
        bodies.join(player); doThrow(new IOException("lease removal failed")).when(leases).complete(owner,original);
        assertDoesNotThrow(()->bodies.quit(player)); verify(player).saveData();
        diskLeases.validate(owner,original); assertThrows(IOException.class,()->diskLeases.validate(owner,tag));
        verify(store).quarantine(owner,"stale-live-copy",snapshot);
        verify(store,times(1)).write(owner,first,1,"join-recovery",snapshot);
    }

    @Test void joinRetainsOriginalLocationBeforeClearingAndNeverActivatesAutomatically() throws Exception {
        Location original=new Location(world,30,80,40);bodies.rememberSpawn(owner,original);bodies.join(player);
        verify(snapshot).withLocation(original);verify(migration).initialize(account,snapshot);
        verify(player).closeInventory();verify(player,never()).saveData();verify(player.getInventory()).clear();verify(player.getEnderChest()).clear();
        assertTrue(bodies.isSelecting(player));assertFalse(bodies.hasLiveBody(player));assertEquals(new BodyTag.Room(first),tag);
        assertSame(account,bodies.takeAccount(player));assertNull(bodies.takeAccount(player));
        verify(other).hidePlayer(plugin,player);verify(player).hidePlayer(plugin,other);verify(transitions,never()).selected(any());
        bodies.prepare(account);drain();verify(progress).prepare(account);verify(migration).ensureBody(account,a);assertNotNull(actions);
        assertTrue(actions.isCurrent(player,account));assertFalse(actions.isCurrent(other,account));assertNull(actions.blockReason(a));
        actions.leave();verify(player).kickPlayer("See you next time.");
    }
    @Test void selectionRestoresBeforePublishingTheSessionAndQuitStowsBeforeClearing() throws Exception {
        play();assertEquals(first,bodies.liveCharacter(player));assertFalse(bodies.isSelecting(player));assertFalse(bodies.isApplying(player));
        InOrder order=inOrder(progress,a,snapshot,player,transitions);order.verify(player).teleport(spawn);order.verify(progress).activate(account,a);order.verify(a).tryActivate();order.verify(snapshot).apply(eq(player),anySet());order.verify(player).saveData();order.verify(transitions).selected(account);
        clearInvocations(database);bodies.stow(account,"quit");verify(progress).save(account,a);verify(store).write(owner,first,1,"quit",snapshot);verify(database).trySavePlayer(account);
        assertEquals(new BodyTag.Room(first),tag);assertFalse(bodies.hasLiveBody(player));verify(a).stampLastLocation(spawn);
        bodies.quit(player);assertFalse(bodies.isSelecting(player));assertNull(bodies.liveCharacter(player));
    }
    @Test void liveCrashCopyIsSavedOnlyAtMatchingRevision() throws Exception {
        tag=new BodyTag.Live(first,1);bodies.join(player);verify(store).write(owner,first,1,"join-recovery",snapshot);
        verify(migration,never()).initialize(any(),any());assertEquals(new BodyTag.Room(first),tag);
    }
    @Test void staleLiveCopyIsQuarantinedInsteadOfReplacingCommittedBody() throws Exception {
        tag=new BodyTag.Live(first,1);when(store.read(owner,first)).thenReturn(Optional.of(record(first,2)));
        bodies.join(player);verify(store).quarantine(owner,"stale-live-copy",snapshot);verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
    }
    @Test void missingOrAheadLiveBodyBlocksTheAccountWithoutClearingItems() throws Exception {
        tag=new BodyTag.Live(first,2);bodies.join(player);verify(player).kickPlayer(contains("needs attention"));verify(player.getInventory(),never()).clear();verify(player).teleport(spawn);
        bodies.quit(player);when(store.read(owner,first)).thenReturn(Optional.empty());bodies.join(player);verify(player,times(2)).kickPlayer(contains("needs attention"));
    }
    @Test void roomWithLateItemsRetainsThemInQuarantine() throws Exception {
        tag=new BodyTag.Room(first);when(player.getEnderChest().isEmpty()).thenReturn(false);bodies.join(player);
        verify(store).quarantine(owner,"items-returned-after-stow",snapshot);verify(player.getEnderChest()).clear();
    }
    @Test void roomRequiresCompletedMigration() throws Exception {
        tag=new BodyTag.Room(first);when(migration.migrated(owner)).thenReturn(false);bodies.join(player);
        verify(player).kickPlayer(contains("needs attention"));verify(player.getInventory(),never()).clear();
    }
    @Test void accountLoadFailureAndProgressFailureCannotOpenSelection() throws Exception {
        when(database.loadPlayerData(owner)).thenReturn(null);bodies.join(player);assertNull(bodies.takeAccount(player));bodies.prepare(account);assertNull(actions);
        bodies.quit(player);when(database.loadPlayerData(owner)).thenReturn(account);bodies.join(player);doThrow(new IOException("offline")).when(progress).prepare(account);bodies.prepare(account);assertNull(actions);
    }
    @Test void sameCharacterResumeBypassesSwitchCooldownButOtherLivingCharacterDoesNot() throws Exception {
        ready();groups.when(()->PermissionGroupService.hasCharacterSwitchCooldown(player,account)).thenReturn(true);
        groups.when(()->PermissionGroupService.getRemainingCooldownMinutes(player,account)).thenReturn(1501);
        assertNull(bodies.blockReason(account,a));assertTrue(bodies.blockReason(account,b).contains("1d 1h 1m"));
        when(player.hasPermission("rpcharacters.no_cooldown")).thenReturn(true);assertNull(bodies.blockReason(account,b));
        when(player.hasPermission("rpcharacters.no_cooldown")).thenReturn(false);when(a.getStatus()).thenReturn(Status.DEAD);assertNull(bodies.blockReason(account,b));
    }
    @Test void selectionRequiresReadyLivingOwnedCharacterAndLinkUnlessBypassed() throws Exception {
        bodies.join(player);assertTrue(bodies.blockReason(account,a).contains("loading"));bodies.prepare(account);drain();
        assertTrue(bodies.blockReason(account,character(UUID.randomUUID())).contains("cannot be played"));
        when(a.getStatus()).thenReturn(Status.DEAD);assertTrue(bodies.blockReason(account,a).contains("cannot be played"));when(a.getStatus()).thenReturn(Status.ALIVE);
        when(manager.isDiscordGate(owner)).thenReturn(true);assertTrue(bodies.blockReason(account,a).contains("Discord"));
        when(player.hasPermission("tfmcweb.discord.bypass")).thenReturn(true);assertNull(bodies.blockReason(account,a));
        tags.when(()->BodyTag.read(player)).thenThrow(new IOException("bad tag"));assertTrue(bodies.blockReason(account,a).contains("recovery"));
        bodies.select(account,a);verify(player).sendMessage(contains("recovery"));
    }
    @Test void onlyTheNewestAsyncSelectionRequestMayActivate() throws Exception {
        ready();CompletableFuture<Chunk> loaded=new CompletableFuture<>();when(world.getChunkAtAsync(any(Location.class))).thenReturn(loaded);
        bodies.select(account,a);bodies.select(account,b);loaded.complete(mock(Chunk.class));drain();
        verify(progress,never()).activate(account,a);verify(progress).activate(account,b);assertEquals(second,bodies.liveCharacter(player));
    }
    @Test void disconnectOrChangedGateDropsPendingSelection() throws Exception {
        ready();bodies.select(account,a);when(player.isOnline()).thenReturn(false);drain();verify(progress,never()).activate(any(),any());
        when(player.isOnline()).thenReturn(true);bodies.select(account,a);when(manager.isDiscordGate(owner)).thenReturn(true);drain();verify(player).sendMessage(contains("Discord"));
    }
    @Test void failedChunkLoadDoesNotApplyBody() throws Exception {
        ready();when(world.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.failedFuture(new IOException("chunk")));
        bodies.select(account,a);drain();verify(snapshot,never()).apply(any(),anySet());verify(player).kickPlayer(contains("needs attention"));
    }
    @Test void roomDestinationAndChangedRevisionAreRefused() throws Exception {
        ready();when(snapshot.location()).thenReturn(room);bodies.select(account,a);verify(player).kickPlayer(contains("needs attention"));
        bodies.quit(player);tag=new BodyTag.Room(first);when(snapshot.location()).thenReturn(spawn);ready();bodies.select(account,a);
        when(store.read(owner,first)).thenReturn(Optional.of(record(first,2)));drain();verify(progress,never()).activate(any(),any());
    }
    @Test void refusedClassDoesNotPublishReadySession() throws Exception {
        ready();when(a.tryActivate()).thenReturn(false);bodies.select(account,a);drain();verify(transitions,never()).selected(any());
        assertFalse(bodies.hasLiveBody(player));
    }
    @Test void rosterChangesDuringInitialLoadWaitForTheProgressionBaseline() throws Exception {
        bodies.join(player);bodies.takeAccount(player);
        RPCharacter created=character(UUID.randomUUID());account.addCharacter(created);
        bodies.rosterChanged(account);
        verify(progress,never()).ensureFresh(any(),any());verify(migration,never()).ensureBody(any(),any());
        verify(progress,never()).prepare(any());assertNull(actions);
        bodies.prepare(account);drain();
        verify(progress).prepare(account);verify(migration).ensureBody(account,created);assertNotNull(actions);
        bodies.rosterChanged(account);verify(progress).ensureFresh(account,created);
    }
    @Test void cancelledTeleportKeepsRoomAndStoredBodiesUntouchedAndCanRetryWithoutReconnecting() throws Exception {
        useRealLeases(); ready(); account.setTempAlias("Room alias");
        CharacterSelectView.Actions originalActions=actions;
        clearInvocations(player,progress,store,leases,a,b,player.getInventory());
        when(player.teleport(spawn)).thenAnswer(call->{
            assertTrue(bodies.isSelecting(player)); assertTrue(bodies.isApplying(player));
            assertFalse(bodies.hasLiveBody(player)); assertEquals(new BodyTag.Room(first),tag);
            return false;
        });
        bodies.select(account,b); drain();
        assertEquals(new BodyTag.Room(first),tag); diskLeases.validate(owner,tag);
        assertTrue(bodies.isSelecting(player)); assertFalse(bodies.isApplying(player)); assertFalse(bodies.hasLiveBody(player));
        assertNull(account.getActiveCharacter()); assertEquals("Room alias",account.getTempAlias());
        assertNull(bodies.blockReason(account,b)); assertNotSame(originalActions,actions);
        verify(player).sendMessage(contains("try again")); verify(player,never()).kickPlayer(anyString());
        verify(player,never()).saveData(); verify(leases,never()).begin(any(),any());
        verify(progress,never()).activate(any(),any()); verify(progress,never()).save(any(),any());
        verify(a,never()).tryActivate(); verify(b,never()).tryActivate(); verify(b,never()).setActiveState(true);
        verify(snapshot,never()).apply(any(),anySet()); verify(player.getInventory(),never()).clear();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        verify(transitions,never()).selected(any());
        doReturn(true).when(player).teleport(spawn);
        actions.play(b); drain();
        assertEquals(new BodyTag.Live(second,1),tag); diskLeases.validate(owner,tag);
        assertTrue(bodies.hasLiveBody(player)); assertFalse(bodies.isSelecting(player));
        verify(leases).begin(owner,new BodyTag.Live(second,1)); verify(progress).activate(account,b);
        verify(snapshot).apply(eq(player),anySet()); verify(transitions).selected(account);
    }
    @Test void classPreflightRefusalLeavesNoLeaseOrBodyChangesAndAnotherCharacterCanBeChosen() throws Exception {
        useRealLeases(); ready(); clearInvocations(player,progress,store,leases,snapshot,a,b);
        CharacterSelectView.Actions originalActions=actions;
        doThrow(new IOException("Missing stored class")).when(progress).preflight(account,a);
        bodies.select(account,a); drain();
        assertEquals(new BodyTag.Room(first),tag); diskLeases.validate(owner,tag);
        assertNull(account.getActiveCharacter()); assertTrue(bodies.isSelecting(player)); assertFalse(bodies.isApplying(player));
        assertNotSame(originalActions,actions); verify(player).sendMessage(contains("Choose another"));
        verify(player,never()).kickPlayer(anyString()); verify(player,never()).teleport(any(Location.class));
        verify(leases,never()).begin(any(),any()); verify(progress,never()).activate(any(),any());
        verify(snapshot,never()).apply(any(),anySet()); verify(a,never()).tryActivate();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        actions.play(b); drain(); assertEquals(new BodyTag.Live(second,1),tag); assertTrue(bodies.hasLiveBody(player));
        verify(progress).preflight(account,b); verify(leases).begin(owner,new BodyTag.Live(second,1));
        verify(progress).activate(account,b); verify(transitions).selected(account);
    }
    @Test void disconnectInsideTeleportCannotBeginActivationOrPoisonNextJoin() throws Exception {
        useRealLeases(); ready(); clearInvocations(progress,store,leases,snapshot,transitions);
        when(player.teleport(spawn)).thenAnswer(call->{when(player.isOnline()).thenReturn(false); return true;});
        bodies.select(account,a); drain();
        assertEquals(new BodyTag.Room(first),tag); diskLeases.validate(owner,tag);
        verify(leases,never()).begin(any(),any()); verify(progress,never()).activate(any(),any());
        verify(snapshot,never()).apply(any(),anySet()); verify(transitions,never()).selected(any());
        bodies.quit(player); when(player.isOnline()).thenReturn(true); doReturn(true).when(player).teleport(spawn);
        ready(); bodies.select(account,a); drain(); assertTrue(bodies.hasLiveBody(player));
    }
    @Test void cancelledTeleportAfterDisconnectDoesNotReopenTheOldConnection() throws Exception {
        useRealLeases(); ready(); CharacterSelectView.Actions originalActions=actions;
        when(player.teleport(spawn)).thenAnswer(call->{when(player.isOnline()).thenReturn(false); return false;});
        bodies.select(account,a); drain();
        assertSame(originalActions,actions); verify(player,never()).sendMessage(contains("try again"));
        verify(leases,never()).begin(any(),any()); diskLeases.validate(owner,new BodyTag.Room(first));
    }
    @Test void replacementAccountDuringTeleportCannotActivateOnTheStaleConnection() throws Exception {
        useRealLeases(); ready();
        when(player.teleport(spawn)).thenAnswer(call->{players.when(()->PlayerManager.get(player)).thenReturn(new PlayerData(player)); return true;});
        bodies.select(account,a); drain();
        verify(leases,never()).begin(any(),any()); verify(progress,never()).activate(any(),any());
        verify(snapshot,never()).apply(any(),anySet()); diskLeases.validate(owner,new BodyTag.Room(first));
    }
    @Test void stowFailurePreservesRawLiveBodyForNextJoinRecovery() throws Exception {
        play();doThrow(new IOException("disk")).when(store).write(owner,first,1,"quit",snapshot);
        clearInvocations(player.getInventory());bodies.stow(account,"quit");verify(player.getInventory(),never()).clear();assertInstanceOf(BodyTag.Live.class,tag);
        bodies.stow(account,"retry");verify(store,never()).write(owner,first,1,"retry",snapshot);
    }
    @Test void missingCharacterOrAccountSaveFailureCannotClearRawBody() throws Exception {
        play();account.getCharacters().remove(a);bodies.stow(account,"quit");verify(player).kickPlayer(contains("needs attention"));
        bodies.quit(player);account.addCharacter(a);tag=new BodyTag.Room(first);play();when(database.trySavePlayer(account)).thenReturn(false);
        clearInvocations(player.getInventory());bodies.stow(account,"quit");verify(player.getInventory(),never()).clear();assertInstanceOf(BodyTag.Live.class,tag);
    }
    @Test void deathSavesProgressBeforeDeathAndStoresTheDeadBodyAtItsDeathLocation() throws Exception {
        play();clearInvocations(player);assertTrue(bodies.prepareDeath(account,a));verify(progress).save(account,a);
        bodies.markDead(account,a);when(a.getStatus()).thenReturn(Status.DEAD);a.setActiveState(false);assertTrue(bodies.awaitingDeadBody(player));
        bodies.checkpoint();verify(progress,times(1)).save(account,a);
        bodies.finishDeath(player);drain();verify(store).write(owner,first,1,"permadeath",snapshot);verify(progress,times(1)).save(account,a);
        assertTrue(bodies.isSelecting(player));assertFalse(bodies.awaitingDeadBody(player));assertNotNull(actions);verify(player).teleport(room);
    }
    @Test void permanentDeathCannotPublishSelectionBeforeItsRoomTeleportSucceeds() throws Exception {
        play(); bodies.markDead(account,a); when(a.getStatus()).thenReturn(Status.DEAD); a.setActiveState(false);
        actions=null; when(player.teleport(room)).thenReturn(false); clearInvocations(other);
        bodies.finishDeath(player); queued.removeFirst().run();
        assertNull(actions); assertTrue(bodies.isSelecting(player)); assertFalse(bodies.hasLiveBody(player));
        assertEquals(new BodyTag.Room(first),tag); assertTrue(bodies.blockReason(account,b).contains("loading"));
        verify(other).hidePlayer(plugin,player); verify(other,never()).showPlayer(any(),any());
        verify(store).write(owner,first,1,"permadeath",snapshot); verify(player,never()).kickPlayer(anyString());
        when(player.teleport(room)).thenReturn(true); drain(); assertNotNull(actions); assertNull(bodies.blockReason(account,b));
        verify(b,never()).tryActivate();
    }
    @SuppressWarnings("unchecked") void selectingPeer() throws Exception {
        var field=CharacterBodies.class.getDeclaredField("selecting"); field.setAccessible(true);
        ((Set<UUID>) field.get(bodies)).add(other.getUniqueId());
    }
    @Test void selectingPeersStayMutuallyHiddenFromJoinThroughPermanentDeathRoomEntry() throws Exception {
        selectingPeer(); play();
        verify(other).hidePlayer(plugin,player); verify(player).hidePlayer(plugin,other);
        verify(other,never()).showPlayer(plugin,player); verify(player,never()).showPlayer(plugin,other);
        bodies.markDead(account,a); when(a.getStatus()).thenReturn(Status.DEAD); a.setActiveState(false); clearInvocations(player,other);
        bodies.finishDeath(player);
        // The guard sets the room respawn and calls finishDeath before vanilla moves the entity there.
        verify(other).hidePlayer(plugin,player); verify(player).hidePlayer(plugin,other);
        assertEquals(1,queued.size()); verify(store,never()).write(any(),any(),anyLong(),eq("permadeath"),any());
        drain(); verify(store).write(owner,first,1,"permadeath",snapshot); verify(player).teleport(room);
        verify(other,never()).showPlayer(any(),any()); verify(player,never()).showPlayer(any(),any());
    }
    @Test void playablePeersAreRevealedButSimultaneousDeathsAreHiddenBeforeTheRoomRespawn() throws Exception {
        play(); verify(other).showPlayer(plugin,player); verify(player).showPlayer(plugin,other);
        bodies.markDead(account,a); when(a.getStatus()).thenReturn(Status.DEAD); a.setActiveState(false); clearInvocations(player,other);
        bodies.finishDeath(player); verify(other).hidePlayer(plugin,player); verify(player).hidePlayer(plugin,other); assertFalse(queued.isEmpty());
    }
    @Test void failedDeathProgressSaveRefusesPermanentDeath() throws Exception {
        play();doThrow(new IOException("progress")).when(progress).save(account,a);assertFalse(bodies.prepareDeath(account,a));verify(player).kickPlayer(contains("needs attention"));
    }
    @Test void staleDeathCallbackDoesNotActOnReconnectedAccount() throws Exception {
        play();bodies.markDead(account,a);clearInvocations(player,other);bodies.finishDeath(player);var reconnected=new PlayerData(player);players.when(()->PlayerManager.get(player)).thenReturn(reconnected);drain();verify(store,never()).write(any(),any(),anyLong(),eq("permadeath"),any());
        verify(other).hidePlayer(plugin,player);verify(other,never()).showPlayer(plugin,player);verify(player,never()).teleport(room);
    }
    @Test void rosterChangesPrepareNewRecordsOnlyWhileSelecting() throws Exception {
        bodies.rosterChanged(account);verify(progress,never()).ensureFresh(any(),any());ready();bodies.rosterChanged(account);drain();verify(progress).ensureFresh(account,a);verify(progress).ensureFresh(account,b);
        doThrow(new IOException("new record")).when(progress).ensureFresh(account,a);bodies.rosterChanged(account);verify(player).kickPlayer(contains("needs attention"));
    }
    @Test void switchRequestsStartTheReturnToSelectionAndShutdownStowsThenClosesProgress() throws Exception {
        play();bodies.requestSwitch(account,a);assertNull(timer);bodies.requestSwitch(account,b);assertNotNull(timer);verify(player,never()).kickPlayer(anyString());
        view.when(CharacterSelectView::refreshAll).thenAnswer(call->{verify(progress).save(account,a);return null;});
        bodies.checkpoint();view.verify(CharacterSelectView::refreshAll);verify(progress).save(account,a);bodies.shutdown();verify(store).write(owner,first,1,"shutdown",snapshot);view.verify(CharacterSelectView::cleanupAll);verify(progress).close();
    }
    void leaveRunsOutgoing() {
        doAnswer(c->{((Runnable)c.getArgument(2)).run();return null;}).when(transitions).leave(eq(account),eq(Reason.SWITCH),any());
    }
    void countdown(int seconds) {for(int i=0;i<seconds;i++)timer.run();}

    @Test void selectFromAPlayingCharacterStaysConnectedAndEntersTheRoomAsSteveAfterStandingStill() throws Exception {
        var accountSkin=new SkinTextures("account","signed");
        wardrobes.when(()->WardrobeCache.getAccountSkin(player)).thenReturn(accountSkin);
        play(); leaveRunsOutgoing(); clearInvocations(player,other,progress); skins.clearInvocations(); actions=null;
        bodies.requestSelection(player); verify(player).sendMessage(contains("5 seconds"));
        countdown(4); verify(transitions,never()).leave(any(),any(),any()); verify(player,times(4)).sendActionBar(any(net.kyori.adventure.text.Component.class));
        verify(player).sendActionBar(net.kyori.adventure.text.Component.text("Character selection in 1..."));
        timer.run(); verify(timerTask).cancel();
        InOrder order=inOrder(transitions,store,player);
        order.verify(transitions).leave(eq(account),eq(Reason.SWITCH),any());
        order.verify(store).write(owner,first,1,"selection",snapshot);
        order.verify(player).teleport(room);
        duels.verify(()->Duels.forget(player)); donning.verify(()->ArmourDonning.cancel(player));
        verify(other).hidePlayer(plugin,player); verify(player,never()).kickPlayer(anyString());
        assertFalse(bodies.hasLiveBody(player)); assertTrue(bodies.isSelecting(player)); assertEquals(new BodyTag.Room(first),tag);
        verify(progress).save(account,a); verify(progress).prepare(account); assertNotNull(actions);
        skins.verify(()->SkinApplyHelper.apply(player,SelectionSkin.STEVE));
        // Coming back to the same character restores the account skin straight away and is not a switch.
        actions.play(a); drain(); assertTrue(bodies.hasLiveBody(player));
        skins.verify(()->SkinApplyHelper.apply(player,accountSkin));
        wardrobes.when(()->WardrobeCache.get(player)).thenReturn(mock(net.tfminecraft.rpcharacters.wardrobe.WardrobeSnapshot.class));
        bodies.requestSelection(player);countdown(5);actions.play(a);drain();wardrobeService.verify(()->WardrobeService.applyFor(player));
        assertNull(account.getLastCharacterSwitchAtMs());
    }

    @Test void roomSkinNeedsAKnownAccountSkinToRestore() throws Exception {
        ready(); skins.verify(()->SkinApplyHelper.apply(any(Player.class),any(SkinTextures.class)),never());
        wardrobes.verify(()->WardrobeCache.captureAccountSkinIfNeeded(player));
    }

    @Test void movingOrBeingHurtKeepsTheCharacterAndOnlyOneCountdownRuns() throws Exception {
        play(); leaveRunsOutgoing();
        bodies.requestSelection(player); bodies.requestSelection(player); verify(player).sendMessage(contains("already returning"));
        when(player.getLocation()).thenReturn(new Location(world,15.3,70,25)); timer.run();
        verify(player).sendMessage(contains("You moved")); verify(timerTask).cancel();
        when(player.getLocation()).thenReturn(spawn); var oldTimer=timer; oldTimer.run(); verify(transitions,never()).leave(any(),any(),any());
        when(player.getLocation()).thenReturn(new Location(selection,15,70,25)); bodies.requestSelection(player);
        when(player.getLocation()).thenReturn(spawn); timer.run(); verify(player,times(2)).sendMessage(contains("You moved"));
        bodies.requestSelection(player); bodies.interruptReturn(player,"You were hurt."); bodies.interruptReturn(player,"twice");
        verify(player).sendMessage("You were hurt."); verify(player,never()).sendMessage("twice");
        countdown(5); verify(transitions,never()).leave(any(),any(),any()); assertTrue(bodies.hasLiveBody(player));
    }

    @Test void countdownEndsQuietlyAfterQuitOrAnotherConnection() throws Exception {
        play(); bodies.requestSelection(player); bodies.quit(player); verify(timerTask).cancel(); timer.run();
        var reconnected=new PlayerData(player); play(); bodies.requestSelection(player); players.when(()->PlayerManager.get(player)).thenReturn(reconnected);
        countdown(5); verify(transitions,never()).leave(any(),any(),any());
        players.when(()->PlayerManager.get(player)).thenReturn(account); bodies.requestSelection(player); when(player.isOnline()).thenReturn(false); countdown(5);
        verify(transitions,never()).leave(any(),any(),any()); verify(player,never()).sendMessage(contains("You moved"));
        players.when(()->PlayerManager.get(player)).thenReturn(null); bodies.requestSelection(player); verify(transitions,never()).leave(any(),any(),any());
    }

    @Test void selectWithoutALiveBodyReopensTheSelector() throws Exception {
        ready(); var opened=actions; actions=null; bodies.requestSelection(player); assertNotNull(actions); assertNotNull(opened); assertNull(timer);
    }

    @Test void returnIsRefusedWhileDeadMountedFallingEditingDuellingKnockedOutOrHeldByAnotherPlugin() throws Exception {
        play(); var events=mock(PluginManager.class); bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        when(player.isDead()).thenReturn(true); bodies.requestSelection(player); verify(player).sendMessage(contains("while dead"));
        when(player.isDead()).thenReturn(false); when(player.isInsideVehicle()).thenReturn(true); bodies.requestSelection(player); verify(player).sendMessage(contains("Dismount"));
        when(player.isInsideVehicle()).thenReturn(false); when(player.getFallDistance()).thenReturn(2f); bodies.requestSelection(player); verify(player).sendMessage(contains("Land"));
        when(player.getFallDistance()).thenReturn(0f); CreationManager.activeCreators.put(player,mock(net.tfminecraft.rpcharacters.creation.CharacterCreation.class));
        bodies.requestSelection(player); verify(player).sendMessage(contains("editing")); CreationManager.activeCreators.remove(player);
        duels.when(()->Duels.isDuelling(owner)).thenReturn(true); bodies.requestSelection(player); verify(player).sendMessage(contains("duel"));
        duels.when(()->Duels.isDuelling(owner)).thenReturn(false); var knockouts=mock(PvpKnockoutManager.class); rpc.when(RPCharacters::getPvpKnockoutManager).thenReturn(knockouts);
        when(knockouts.isDown(player)).thenReturn(true); bodies.requestSelection(player); verify(player).sendMessage(contains("knocked out"));
        when(knockouts.isDown(player)).thenReturn(false);
        evil.when(()->net.tfminecraft.rpcharacters.evilrp.EvilRpService.blocksCharacterSwitch(player)).thenReturn(true);
        bodies.requestSelection(player); verify(player).sendMessage(contains("roleplay"));
        evil.when(()->net.tfminecraft.rpcharacters.evilrp.EvilRpService.blocksCharacterSwitch(player)).thenReturn(false);
        doAnswer(c->{((CharacterSelectionRequestEvent)c.getArgument(0)).deny("Finish the battle first.");return null;}).when(events).callEvent(any(CharacterSelectionRequestEvent.class));
        bodies.requestSelection(player); verify(player).sendMessage("Finish the battle first."); assertNull(timer);
        // A plugin can also refuse later in the countdown.
        doNothing().when(events).callEvent(any()); bodies.requestSelection(player); assertNotNull(timer);
        doAnswer(c->{((CharacterSelectionRequestEvent)c.getArgument(0)).deny("A raid just started.");return null;}).when(events).callEvent(any(CharacterSelectionRequestEvent.class));
        timer.run(); verify(player).sendMessage("A raid just started."); verify(transitions,never()).leave(any(),any(),any());
    }

    @Test void returnRefusedWhileAlreadyRespawningOrApplying() throws Exception {
        play(); bodies.markDead(account,a); bodies.requestSelection(player); verify(player).sendMessage(contains("while dead"));
        bodies.quit(player); play();
        var field=CharacterBodies.class.getDeclaredField("applying"); field.setAccessible(true);
        @SuppressWarnings("unchecked") Set<UUID> applying=(Set<UUID>)field.get(bodies); applying.add(owner);
        bodies.requestSelection(player); verify(player).sendMessage(contains("still loading"));
    }

    @Test void busyTransitionLeavesThePlayerPlaying() throws Exception {
        play(); doThrow(new IllegalStateException("busy")).when(transitions).leave(eq(account),eq(Reason.SWITCH),any());
        bodies.requestSelection(player); countdown(5);
        verify(player).sendMessage(contains("busy")); assertTrue(bodies.hasLiveBody(player)); assertFalse(bodies.isSelecting(player));
    }

    @Test void failedOrMissingStowNeverEntersTheRoom() throws Exception {
        play(); leaveRunsOutgoing(); clearInvocations(player); when(database.trySavePlayer(account)).thenReturn(false);
        bodies.requestSelection(player); countdown(5); verify(player).kickPlayer(contains("needs attention")); verify(player,never()).teleport(room);
        bodies.quit(player); when(database.trySavePlayer(account)).thenReturn(true); play(); clearInvocations(player);
        doNothing().when(transitions).leave(eq(account),eq(Reason.SWITCH),any());
        bodies.requestSelection(player); countdown(5); verify(player).kickPlayer(contains("needs attention")); verify(player,never()).teleport(room);
    }

    @Test void configurationRejectsMissingWorldUnsafeKeysAndSharedSelectionWorld() {
        config.set("spawn-world","absent");assertThrows(IOException.class,()->new CharacterBodies(plugin,config));
        config.set("spawn-world","world");config.set("persistent-keys",List.of("rpcharacters:body"));assertThrows(IOException.class,()->new CharacterBodies(plugin,config));
        config.set("persistent-keys",List.of("INVALID KEY"));assertThrows(IOException.class,()->new CharacterBodies(plugin,config));
        config.set("persistent-keys",List.of());config.set("selection-world","world");assertThrows(IOException.class,()->new CharacterBodies(plugin,config));
    }
    @Test void selectorActionsDispatchCreationAndPlayWithoutSharingItsMutableRoomLocation() throws Exception {
        Location firstRoom=bodies.room();firstRoom.setX(100);assertEquals(.5,bodies.room().getX());
        when(migration.heir(owner)).thenReturn(first);assertEquals(first,bodies.migrationHeir(owner));
        ready();try(var creators=mockStatic(CreationManager.class)) {actions.create();creators.verify(()->CreationManager.initiateCreation(player));}
        bodies.requestSwitch(account,b);drain();verify(progress).ensureFresh(account,b);
        actions.play(a);drain();assertEquals(first,bodies.liveCharacter(player));
    }
    @Test void healthIsReappliedAfterEquipmentOnlyForThePublishedSession() throws Exception {
        var session=mock(net.tfminecraft.tlibs.character.CharacterSession.class);
        sessions.when(()->Characters.currentSession(player)).thenReturn(Optional.of(session));
        sessions.when(()->Characters.isCurrent(session)).thenReturn(true);
        play();verify(snapshot,times(2)).applyHealth(player);
        bodies.stow(account,"quit");bodies.quit(player);tag=new BodyTag.Room(first);
        sessions.when(()->Characters.isCurrent(session)).thenReturn(false);play();verify(snapshot,times(3)).applyHealth(player);
    }

    @Test void resourcesAreFrozenBeforeActivationAndRestoredAfterEquipmentAndForTheSameSessionOnly() throws Exception {
        var saved=mock(net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec.Resources.class);
        var replaced=mock(net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec.Resources.class);
        when(progress.resources(account,a)).thenReturn(saved);
        doAnswer(c->{when(progress.resources(account,a)).thenReturn(replaced);return null;}).when(progress).activate(account,a);
        var session=mock(net.tfminecraft.tlibs.character.CharacterSession.class);
        sessions.when(()->Characters.currentSession(player)).thenReturn(Optional.of(session));
        sessions.when(()->Characters.isCurrent(session)).thenReturn(true);
        play();
        InOrder order=inOrder(progress,a,snapshot,player,transitions);
        order.verify(progress).resources(account,a); order.verify(progress).activate(account,a); order.verify(a).tryActivate();
        order.verify(snapshot).apply(eq(player),anySet()); order.verify(progress).restoreResources(account,a,saved);
        order.verify(snapshot).applyHealth(player); order.verify(player).saveData();
        order.verify(transitions).selected(account); order.verify(progress).restoreResources(account,a,saved);
        order.verify(snapshot).applyHealth(player); order.verify(progress).save(account,a);
        verify(progress,never()).restoreResources(account,a,replaced);
        bodies.stow(account,"quit"); bodies.quit(player); tag=new BodyTag.Room(first); clearInvocations(progress);
        sessions.when(()->Characters.isCurrent(session)).thenReturn(false); play();
        verify(progress,times(1)).restoreResources(eq(account),eq(a),any()); verify(progress,never()).save(any(),any());
    }

    @Test void invalidResourceCopyBlocksActivationAndDeferredRestoreFailureClosesTheLiveSession() throws Exception {
        ready(); when(progress.resources(account,a)).thenThrow(new IllegalArgumentException("corrupt resource"));
        bodies.select(account,a); drain(); verify(progress,never()).activate(any(),any()); verify(snapshot,never()).apply(any(),anySet());
        bodies.quit(player); tag=new BodyTag.Room(first); doReturn(null).when(progress).resources(account,a);
        var session=mock(net.tfminecraft.tlibs.character.CharacterSession.class);
        sessions.when(()->Characters.currentSession(player)).thenReturn(Optional.of(session));
        sessions.when(()->Characters.isCurrent(session)).thenReturn(true);
        doNothing().doThrow(new IOException("resource restore")).when(progress).restoreResources(eq(account),eq(a),any());
        play(); verify(player,times(2)).kickPlayer(contains("needs attention")); verify(progress,never()).save(any(),any());
    }

    @Test void silentlyFailedPlayerSaveCannotReplayTheStowedBodyAfterAnActivationCrash() throws Exception {
        useRealLeases(); play();
        InOrder order=inOrder(leases,progress,snapshot,player,transitions);
        order.verify(leases).begin(owner,new BodyTag.Live(first,1)); order.verify(progress).activate(account,a);
        order.verify(snapshot).apply(eq(player),anySet()); order.verify(player).saveData(); order.verify(transitions).selected(account);
        // Paper logged a save failure and returned normally; disk still carries the previous ROOM tag.
        bodies.quit(player); tag=new BodyTag.Room(first); clearInvocations(player.getInventory(),store,progress);
        bodies.join(player); bodies.prepare(account); bodies.select(account,a); drain();
        verify(player).kickPlayer(contains("needs attention")); verify(player.getInventory(),never()).clear();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(progress,never()).activate(any(),any());
        diskLeases.validate(owner,new BodyTag.Live(first,1));
    }

    @Test void matchingLiveCrashRecoveryCommitsBeforeRemovingItsLeaseAndMissingLeasesBlockRawRecovery() throws Exception {
        useRealLeases(); tag=new BodyTag.Live(first,1); bodies.join(player);
        verify(player.getInventory(),never()).clear(); verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        bodies.quit(player); diskLeases.begin(owner,new BodyTag.Live(first,1)); clearInvocations(leases,player,store);
        bodies.join(player); drain();
        InOrder order=inOrder(leases,store,player);
        order.verify(leases).validate(owner,new BodyTag.Live(first,1));
        order.verify(store).write(owner,first,1,"join-recovery",snapshot); order.verify(player).saveData();
        order.verify(leases).complete(owner,new BodyTag.Live(first,1));
        diskLeases.validate(owner,new BodyTag.Room(first));
    }

    @Test void stowFailuresKeepLeaseEvidenceOnBothSidesOfTheBodyCommit() throws Exception {
        useRealLeases(); play(); doThrow(new IOException("before commit")).when(store).write(owner,first,1,"quit",snapshot);
        bodies.stow(account,"quit"); diskLeases.validate(owner,new BodyTag.Live(first,1));
        assertInstanceOf(BodyTag.Live.class,tag); verify(leases,never()).complete(any(),any());
        // Recover the raw live copy, then start another activation whose body commit succeeds but account save fails.
        bodies.quit(player); doReturn(null).when(store).write(owner,first,1,"join-recovery",snapshot);
        ready(); bodies.select(account,a); drain(); when(database.trySavePlayer(account)).thenReturn(false);
        bodies.stow(account,"after-commit"); verify(store).write(owner,first,1,"after-commit",snapshot);
        diskLeases.validate(owner,new BodyTag.Live(first,1)); assertInstanceOf(BodyTag.Live.class,tag);
    }

    @Test void silentRoomSaveFailureAfterStowCannotRecoverTheObsoleteLiveTag() throws Exception {
        useRealLeases(); play(); bodies.stow(account,"quit");
        InOrder order=inOrder(store,player,leases);
        order.verify(store).write(owner,first,1,"quit",snapshot); order.verify(player).saveData();
        order.verify(leases).complete(owner,new BodyTag.Live(first,1));
        bodies.quit(player); tag=new BodyTag.Live(first,1); clearInvocations(store,player.getInventory());
        bodies.join(player); verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        verify(player.getInventory(),never()).clear(); verify(player).kickPlayer(contains("needs attention"));
    }

    @Test void activationLeaseFailurePrecedesAnyProgressOrInventoryMutation() throws Exception {
        ready(); doThrow(new IOException("lease sync")).when(leases).begin(any(),any());
        bodies.select(account,a); drain(); verify(progress,never()).activate(any(),any());
        verify(snapshot,never()).apply(any(),anySet()); verify(transitions,never()).selected(any());
    }

    @Test void deathScreenQuitRetainsRawVanillaBodyAndLeaseUntilVanillaCanRespawn() throws Exception {
        useRealLeases(); play(); when(player.isDead()).thenReturn(true);
        clearInvocations(store,leases,player.getInventory(),progress,a);
        bodies.stow(account,"quit");
        verify(progress).save(account,a); verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        verify(player.getInventory(),never()).clear(); verify(leases,never()).complete(any(),any());
        verify(a,never()).setActiveState(false); verify(player,never()).spigot();
        assertEquals(new BodyTag.Live(first,1),tag); diskLeases.validate(owner,new BodyTag.Live(first,1));
    }

    @Test void reconnectRespawnsOnlyAfterMmoReadyAndStoresTheActualVanillaRespawnLocation() throws Exception {
        useRealLeases(); diskLeases.begin(owner,new BodyTag.Live(first,1)); tag=new BodyTag.Live(first,1);
        when(player.isDead()).thenReturn(true);
        Location bed=new Location(world,70.5,64,-20.5); bodies.rememberSpawn(owner,spawn);
        respawns.when(()->BodyRespawn.respawn(player)).thenAnswer(c->{assertTrue(bodies.isRespawning(player));when(player.isDead()).thenReturn(false);when(player.getLocation()).thenReturn(bed);bodies.respawned(player);return null;});
        bodies.join(player); assertSame(account,bodies.takeAccount(player));
        respawns.verifyNoInteractions(); verify(snapshot,never()).withLocation(any()); verify(player.getInventory(),never()).clear();
        assertTrue(bodies.isRespawning(player)); assertNull(actions);
        bodies.prepare(account); queued.removeFirst().run(); bodies.prepare(account); respawns.verify(()->BodyRespawn.respawn(player),times(1));
        verify(progress,never()).prepare(account); assertFalse(bodies.isApplying(player)); assertNull(actions);
        drain(); verify(snapshot).withLocation(bed); verify(snapshot).withFullHealth(); verify(snapshot,never()).withLocation(spawn);
        InOrder order=inOrder(store,player,leases,progress);
        order.verify(store).write(owner,first,1,"join-recovery",snapshot); order.verify(player).saveData();
        order.verify(leases).complete(owner,new BodyTag.Live(first,1)); order.verify(player).teleport(room); order.verify(progress).prepare(account);
        assertTrue(bodies.isSelecting(player)); assertNotNull(actions); assertEquals(new BodyTag.Room(first),tag);
        diskLeases.validate(owner,new BodyTag.Room(first));
    }

    @Test void clientRespawnBeforeMmoReadyIsCapturedWithoutRespawningTwiceAndOldCallbacksCannotWrite() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); Location bed=new Location(world,50,80,40); when(player.getLocation()).thenReturn(bed); bodies.respawned(player);
        bodies.prepare(account); verify(player,never()).spigot(); bodies.quit(player); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        // The next connection's now-alive vanilla body is recovered through the usual LIVE path.
        bodies.join(player); verify(snapshot).withLocation(bed); verify(store).write(owner,first,1,"join-recovery",snapshot);
    }

    @Test void failedVanillaRespawnNeverCapturesAPlayableZeroHealthBody() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true);
        bodies.join(player); bodies.prepare(account); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player.getInventory(),never()).clear();
        verify(player).kickPlayer(contains("needs attention")); assertInstanceOf(BodyTag.Live.class,tag); assertFalse(bodies.isApplying(player));
    }

    @Test void deathDuringRespawnRecoveryFailsButResourcePackCanDelayTheRoomAfterSuccessfulRecovery() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); bodies.respawned(player); bodies.prepare(account); when(player.isDead()).thenReturn(true); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player).kickPlayer(contains("needs attention"));
        bodies.quit(player); bodies.join(player); when(player.isDead()).thenReturn(false); bodies.respawned(player); when(player.teleport(room)).thenReturn(false);
        bodies.prepare(account); drain(); verify(player,times(1)).kickPlayer(contains("needs attention")); verify(progress,never()).prepare(account);
        assertFalse(bodies.isApplying(player)); assertNull(actions); assertEquals(new BodyTag.Room(first),tag);
        when(player.teleport(room)).thenReturn(true); drain(); verify(progress).prepare(account); assertNotNull(actions);
    }

    @Test void permanentDeathPreservesTheDeadBodyThenRespawnsOnlyTheRoomEntity() throws Exception {
        useRealLeases(); diskLeases.begin(owner,new BodyTag.Live(first,1)); tag=new BodyTag.Live(first,1);
        when(player.isDead()).thenReturn(true); when(a.getStatus()).thenReturn(Status.DEAD);
        bodies.join(player); verify(store).write(owner,first,1,"join-recovery",snapshot);
        assertEquals(new BodyTag.Room(first),tag); queued.removeFirst().run(); diskLeases.validate(owner,tag); verify(player,never()).spigot();
        respawns.when(()->BodyRespawn.respawn(player)).thenAnswer(c->{when(player.isDead()).thenReturn(false);when(player.getInventory().isEmpty()).thenReturn(false);bodies.respawned(player);return null;});
        bodies.prepare(account); drain();
        respawns.verify(()->BodyRespawn.respawn(player)); verify(store).quarantine(owner,"items-returned-after-stow",snapshot);
        verify(store,times(1)).write(any(),any(),anyLong(),anyString(),any()); verify(progress).prepare(account);
        assertEquals(Status.DEAD,a.getStatus()); assertEquals("This character cannot be played.",bodies.blockReason(account,a));
        verify(a,never()).setActiveState(true); verify(snapshot,never()).withFullHealth(); assertFalse(bodies.isRespawning(player));
    }

    @Test void staleDeadLiveCopiesAreQuarantinedBeforeRespawnAndMissingBodiesCannotRespawn() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true);
        when(store.read(owner,first)).thenReturn(Optional.of(record(first,2)));
        bodies.join(player); verify(store).quarantine(owner,"stale-live-copy",snapshot); assertEquals(new BodyTag.Room(first),tag);
        when(player.isDead()).thenReturn(false); bodies.respawned(player); bodies.prepare(account); drain(); verify(progress).prepare(account);
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        bodies.quit(player); when(player.isDead()).thenReturn(true); tag=new BodyTag.Live(first,1);
        when(store.read(owner,first)).thenReturn(Optional.empty()); bodies.join(player);
        verify(player).kickPlayer(contains("needs attention")); verify(player,never()).spigot();
    }

    @Test void deadRoomWaitsForRespawnAndDeadLegacyMigrationPreservesRawDataForStaff() throws Exception {
        tag=new BodyTag.Room(first); when(player.isDead()).thenReturn(true); bodies.join(player);
        assertNull(actions); verify(progress,never()).prepare(any());
        when(player.isDead()).thenReturn(false); bodies.respawned(player); bodies.prepare(account); drain();
        verify(progress).prepare(account); assertNotNull(actions); verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        bodies.quit(player); actions=null; tag=new BodyTag.Untagged(); when(player.isDead()).thenReturn(true);
        clearInvocations(migration,snapshot); bodies.join(player); verify(migration,never()).initialize(any(),any());
        bodies.prepare(account); drain(); verify(migration,never()).initialize(any(),any());
        assertNull(actions); assertFalse(bodies.isRespawning(player)); assertInstanceOf(BodyTag.Untagged.class,tag);
        verify(player).kickPlayer(contains("needs attention"));
    }

    @Test void quitAfterVanillaRespawnBeforeDeferredCaptureRetainsDurableFullHealthIntent() throws Exception {
        useRealLeases(); diskLeases.begin(owner,new BodyTag.Live(first,1)); tag=new BodyTag.Live(first,1);
        when(player.isDead()).thenReturn(true); bodies.join(player);
        assertTrue(diskLeases.pendingRespawn(owner,new BodyTag.Live(first,1)));
        when(player.isDead()).thenReturn(false); bodies.respawned(player); bodies.prepare(account); bodies.quit(player); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(snapshot,never()).withFullHealth();
        bodies.join(player); drain(); verify(snapshot).withFullHealth(); verify(store).write(owner,first,1,"join-recovery",snapshot);
        diskLeases.validate(owner,new BodyTag.Room(first));
        // A normal wounded live body has no respawn intent and keeps its exact health.
        bodies.quit(player); tag=new BodyTag.Live(first,1); diskLeases.begin(owner,new BodyTag.Live(first,1));
        clearInvocations(snapshot); bodies.join(player); verify(snapshot,never()).withFullHealth();
    }

    @Test void failureToPersistRespawnIntentPreservesTheDeadRawBodyAndPreventsRespawn() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true);
        doThrow(new IOException("respawn intent sync")).when(leases).markRespawn(owner,new BodyTag.Live(first,1));
        bodies.join(player); bodies.prepare(account); drain(); verify(player,never()).spigot();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player.getInventory(),never()).clear();
        verify(player).kickPlayer(contains("needs attention"));
    }

    @Test void mmoHealingWithoutPostRespawnStillRunsVanillaRespawnAndCannotStoreTheRoom() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); when(player.getLocation()).thenReturn(room);
        Location actual=new Location(world,50,80,40);
        respawns.when(()->BodyRespawn.respawn(player)).thenAnswer(c->{when(player.getLocation()).thenReturn(actual);bodies.respawned(player);return null;});
        bodies.prepare(account); drain(); respawns.verify(()->BodyRespawn.respawn(player));
        verify(snapshot).withLocation(actual); verify(snapshot,never()).withLocation(room);
        verify(player,never()).setHealth(anyDouble()); verify(store).write(owner,first,1,"join-recovery",snapshot);
    }

    @Test void livingHealthWithoutCompletedRespawnEventFailsBeforeAnyBodyCapture() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); when(player.getLocation()).thenReturn(room);
        bodies.prepare(account); drain(); respawns.verify(()->BodyRespawn.respawn(player));
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(snapshot,never()).withLocation(any());
        verify(player).kickPlayer(contains("needs attention"));
    }

    @Test void postRespawnFailureOrChangedTagNeverApprovesTheBodyAndLateEventsDoNothing() throws Exception {
        bodies.respawned(player); verify(leases,never()).completeRespawn(any(),any());
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        tag=new BodyTag.Live(second,1); bodies.respawned(player); verify(leases,never()).completeRespawn(any(),any());
        verify(player).kickPlayer(contains("needs attention")); bodies.quit(player);
        tag=new BodyTag.Live(first,1); bodies.join(player);
        doThrow(new IOException("completion sync")).when(leases).completeRespawn(any(),any());
        bodies.respawned(player); bodies.prepare(account); drain(); verify(player,times(2)).kickPlayer(contains("needs attention"));
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
    }

    @Test void interruptedRespawnCannotRecoverHealedRawOrOverwriteAGoodBodyWithARoomLocation() throws Exception {
        useRealLeases(); diskLeases.begin(owner,new BodyTag.Live(first,1)); diskLeases.markRespawn(owner,new BodyTag.Live(first,1));
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(false);
        bodies.join(player); verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        verify(player).kickPlayer(contains("needs attention")); bodies.quit(player);
        diskLeases.completeRespawn(owner,new BodyTag.Live(first,1)); when(player.getLocation()).thenReturn(room);
        bodies.join(player); verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        verify(player,times(2)).kickPlayer(contains("needs attention")); diskLeases.validate(owner,new BodyTag.Live(first,1));
        bodies.quit(player); when(player.isDead()).thenReturn(true); bodies.join(player);
        verify(player,times(3)).kickPlayer(contains("needs attention"));
    }

    @Test void pendingRespawnCannotRecoverAChangedTag() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); bodies.respawned(player); bodies.prepare(account); tag=new BodyTag.Live(second,1); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player.getInventory(),never()).clear();
        verify(player).kickPlayer(contains("needs attention"));
    }

}
