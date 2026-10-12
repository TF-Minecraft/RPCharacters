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
    MockedConstruction<BodyStore> stores;
    MockedConstruction<BodyLeaseStore> leaseStores;
    MockedConstruction<CharacterBodyMigration> migrations;
    MockedConstruction<CharacterProgressService> progresses;
    BodyTag tag;
    CharacterSelectView.Actions actions;
    YamlConfiguration config;

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
        for(Class<?> type:List.of(ProfessionIntegrator.class,MmoInventorySlots.class,net.tfminecraft.rpcharacters.wardrobe.WardrobeService.class,net.tfminecraft.rpcharacters.joinsafety.JoinUnstuckListener.class,net.tfminecraft.rpcharacters.evilrp.EvilRpService.class))mocks.add(mockStatic(type));
        groups=mockStatic(PermissionGroupService.class);mocks.add(groups);
        sessions=mockStatic(Characters.class);mocks.add(sessions);
        tags=mockStatic(BodyTag.class);mocks.add(tags);tag=new BodyTag.Untagged();tags.when(()->BodyTag.read(player)).thenAnswer(c->tag);
        tags.when(()->BodyTag.room(eq(player),any())).thenAnswer(c->{tag=new BodyTag.Room(c.getArgument(1));return null;});
        tags.when(()->BodyTag.live(eq(player),any(),anyLong())).thenAnswer(c->{tag=new BodyTag.Live(c.getArgument(1),c.getArgument(2));return null;});
        view=mockStatic(CharacterSelectView.class);mocks.add(view);view.when(()->CharacterSelectView.open(eq(player),eq(account),any())).thenAnswer(c->{actions=c.getArgument(2);return null;});
        config=new YamlConfiguration(); config.set("persistent-keys",List.of("accessory:test"));
        bodies=new CharacterBodies(plugin,config);rpc.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
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
    void ready(){bodies.join(player);bodies.takeAccount(player);bodies.prepare(account);}
    void play(){ready();bodies.select(account,a);drain();assertTrue(bodies.hasLiveBody(player));}
    void useRealLeases() throws Exception {
        doAnswer(c->{diskLeases.validate(c.getArgument(0),c.getArgument(1));return null;}).when(leases).validate(any(),any());
        doAnswer(c->{diskLeases.begin(c.getArgument(0),c.getArgument(1));return null;}).when(leases).begin(any(),any());
        doAnswer(c->{diskLeases.complete(c.getArgument(0),c.getArgument(1));return null;}).when(leases).complete(any(),any());
        doAnswer(c->{diskLeases.markRespawn(c.getArgument(0),c.getArgument(1));return null;}).when(leases).markRespawn(any(),any());
        doAnswer(c->diskLeases.pendingRespawn(c.getArgument(0),c.getArgument(1))).when(leases).pendingRespawn(any(),any());
    }
    @AfterEach void cleanup() throws Exception {CreationManager.activeCreators.remove(player);for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();}

    @Test void joinRetainsOriginalLocationBeforeClearingAndNeverActivatesAutomatically() throws Exception {
        Location original=new Location(world,30,80,40);bodies.rememberSpawn(owner,original);bodies.join(player);
        verify(snapshot).withLocation(original);verify(migration).initialize(account,snapshot);
        verify(player).closeInventory();verify(player).saveData();verify(player.getInventory()).clear();verify(player.getEnderChest()).clear();
        assertTrue(bodies.isSelecting(player));assertFalse(bodies.hasLiveBody(player));assertEquals(new BodyTag.Room(first),tag);
        assertSame(account,bodies.takeAccount(player));assertNull(bodies.takeAccount(player));
        verify(other).hidePlayer(plugin,player);verify(player).hidePlayer(plugin,other);verify(transitions,never()).selected(any());
        bodies.prepare(account);verify(progress).prepare(account);verify(migration).ensureBody(account,a);assertNotNull(actions);
        assertTrue(actions.isCurrent(player,account));assertFalse(actions.isCurrent(other,account));assertNull(actions.blockReason(a));
        actions.leave();verify(player).kickPlayer("See you next time.");
    }
    @Test void selectionRestoresBeforePublishingTheSessionAndQuitStowsBeforeClearing() throws Exception {
        play();assertEquals(first,bodies.liveCharacter(player));assertFalse(bodies.isSelecting(player));assertFalse(bodies.isApplying(player));
        InOrder order=inOrder(progress,a,snapshot,player,transitions);order.verify(progress).activate(account,a);order.verify(a).tryActivate();order.verify(snapshot).apply(eq(player),anySet());order.verify(player).teleport(spawn);order.verify(player).saveData();order.verify(transitions).selected(account);
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
        bodies.join(player);assertTrue(bodies.blockReason(account,a).contains("loading"));bodies.prepare(account);
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
    @Test void refusedClassOrTeleportDoesNotPublishReadySession() throws Exception {
        ready();when(a.tryActivate()).thenReturn(false);bodies.select(account,a);drain();verify(transitions,never()).selected(any());
        bodies.quit(player);tag=new BodyTag.Room(first);when(a.tryActivate()).thenReturn(true);when(player.teleport(spawn)).thenReturn(false);ready();bodies.select(account,a);drain();verify(transitions,never()).selected(any());assertFalse(bodies.hasLiveBody(player));
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
        play();assertTrue(bodies.prepareDeath(account,a));verify(progress).save(account,a);
        bodies.markDead(account,a);when(a.getStatus()).thenReturn(Status.DEAD);a.setActiveState(false);assertTrue(bodies.awaitingDeadBody(player));
        bodies.checkpoint();verify(progress,times(1)).save(account,a);
        bodies.finishDeath(player);drain();verify(store).write(owner,first,1,"permadeath",snapshot);verify(progress,times(1)).save(account,a);
        assertTrue(bodies.isSelecting(player));assertFalse(bodies.awaitingDeadBody(player));assertNotNull(actions);verify(player).teleport(room);
    }
    @Test void failedDeathProgressSaveRefusesPermanentDeath() throws Exception {
        play();doThrow(new IOException("progress")).when(progress).save(account,a);assertFalse(bodies.prepareDeath(account,a));verify(player).kickPlayer(contains("needs attention"));
    }
    @Test void staleDeathCallbackDoesNotActOnReconnectedAccount() throws Exception {
        play();bodies.markDead(account,a);bodies.finishDeath(player);var reconnected=new PlayerData(player);players.when(()->PlayerManager.get(player)).thenReturn(reconnected);drain();verify(store,never()).write(any(),any(),anyLong(),eq("permadeath"),any());
    }
    @Test void rosterChangesPrepareNewRecordsOnlyWhileSelecting() throws Exception {
        bodies.rosterChanged(account);verify(progress,never()).ensureFresh(any(),any());ready();bodies.rosterChanged(account);drain();verify(progress).ensureFresh(account,a);verify(progress).ensureFresh(account,b);
        doThrow(new IOException("new record")).when(progress).ensureFresh(account,a);bodies.rosterChanged(account);verify(player).kickPlayer(contains("needs attention"));
    }
    @Test void switchRequestsRequireARealLogoutAndShutdownStowsThenClosesProgress() throws Exception {
        play();bodies.requestSwitch(account,a);verify(player,never()).kickPlayer(anyString());bodies.requestSwitch(account,b);verify(player).kickPlayer(contains("Reconnect"));
        bodies.checkpoint();verify(progress).save(account,a);bodies.shutdown();verify(store).write(owner,first,1,"shutdown",snapshot);verify(progress).close();
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
        bodies.join(player);
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
        when(player.isDead()).thenReturn(true); var spigot=mock(Player.Spigot.class); when(player.spigot()).thenReturn(spigot);
        Location bed=new Location(world,70.5,64,-20.5); bodies.rememberSpawn(owner,spawn);
        doAnswer(c->{assertTrue(bodies.isRespawning(player));when(player.isDead()).thenReturn(false);when(player.getLocation()).thenReturn(bed);return null;}).when(spigot).respawn();
        bodies.join(player); assertSame(account,bodies.takeAccount(player));
        verifyNoInteractions(spigot); verify(snapshot,never()).withLocation(any()); verify(player.getInventory(),never()).clear();
        assertTrue(bodies.isRespawning(player)); assertNull(actions);
        bodies.prepare(account); bodies.prepare(account); verify(spigot,times(1)).respawn();
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
        when(player.isDead()).thenReturn(false); Location bed=new Location(world,50,80,40); when(player.getLocation()).thenReturn(bed);
        bodies.prepare(account); verify(player,never()).spigot(); bodies.quit(player); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        // The next connection's now-alive vanilla body is recovered through the usual LIVE path.
        bodies.join(player); verify(snapshot).withLocation(bed); verify(store).write(owner,first,1,"join-recovery",snapshot);
    }

    @Test void failedVanillaRespawnNeverCapturesAPlayableZeroHealthBody() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); when(player.spigot()).thenReturn(mock(Player.Spigot.class));
        bodies.join(player); bodies.prepare(account); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player.getInventory(),never()).clear();
        verify(player).kickPlayer(contains("needs attention")); assertInstanceOf(BodyTag.Live.class,tag); assertFalse(bodies.isApplying(player));
    }

    @Test void deathDuringRespawnRecoveryAndRefusedRoomTeleportStayFailClosed() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); bodies.prepare(account); when(player.isDead()).thenReturn(true); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player).kickPlayer(contains("needs attention"));
        bodies.quit(player); bodies.join(player); when(player.isDead()).thenReturn(false); when(player.teleport(room)).thenReturn(false);
        bodies.prepare(account); drain(); verify(player,times(2)).kickPlayer(contains("needs attention")); verify(progress,never()).prepare(account);
        assertFalse(bodies.isApplying(player));
    }

    @Test void permanentDeathPreservesTheDeadBodyThenRespawnsOnlyTheRoomEntity() throws Exception {
        useRealLeases(); diskLeases.begin(owner,new BodyTag.Live(first,1)); tag=new BodyTag.Live(first,1);
        when(player.isDead()).thenReturn(true); when(a.getStatus()).thenReturn(Status.DEAD);
        bodies.join(player); verify(store).write(owner,first,1,"join-recovery",snapshot);
        assertEquals(new BodyTag.Room(first),tag); diskLeases.validate(owner,tag); verify(player,never()).spigot();
        var spigot=mock(Player.Spigot.class); when(player.spigot()).thenReturn(spigot);
        doAnswer(c->{when(player.isDead()).thenReturn(false);when(player.getInventory().isEmpty()).thenReturn(false);return null;}).when(spigot).respawn();
        bodies.prepare(account); drain();
        verify(spigot).respawn(); verify(store).quarantine(owner,"items-returned-after-stow",snapshot);
        verify(store,times(1)).write(any(),any(),anyLong(),anyString(),any()); verify(progress).prepare(account);
        assertEquals(Status.DEAD,a.getStatus()); assertEquals("This character cannot be played.",bodies.blockReason(account,a));
        verify(a,never()).setActiveState(true); verify(snapshot,never()).withFullHealth(); assertFalse(bodies.isRespawning(player));
    }

    @Test void staleDeadLiveCopiesAreQuarantinedBeforeRespawnAndMissingBodiesCannotRespawn() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true);
        when(store.read(owner,first)).thenReturn(Optional.of(record(first,2)));
        bodies.join(player); verify(store).quarantine(owner,"stale-live-copy",snapshot); assertEquals(new BodyTag.Room(first),tag);
        when(player.isDead()).thenReturn(false); bodies.prepare(account); drain(); verify(progress).prepare(account);
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any());
        bodies.quit(player); when(player.isDead()).thenReturn(true); tag=new BodyTag.Live(first,1);
        when(store.read(owner,first)).thenReturn(Optional.empty()); bodies.join(player);
        verify(player).kickPlayer(contains("needs attention")); verify(player,never()).spigot();
    }

    @Test void deadRoomWaitsForRespawnAndDeadLegacyMigrationPreservesRawDataForStaff() throws Exception {
        tag=new BodyTag.Room(first); when(player.isDead()).thenReturn(true); bodies.join(player);
        assertNull(actions); verify(progress,never()).prepare(any());
        when(player.isDead()).thenReturn(false); bodies.prepare(account); drain();
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
        when(player.isDead()).thenReturn(false); bodies.prepare(account); bodies.quit(player); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(snapshot,never()).withFullHealth();
        bodies.join(player); verify(snapshot).withFullHealth(); verify(store).write(owner,first,1,"join-recovery",snapshot);
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

    @Test void pendingRespawnCannotRecoverAChangedTag() throws Exception {
        tag=new BodyTag.Live(first,1); when(player.isDead()).thenReturn(true); bodies.join(player);
        when(player.isDead()).thenReturn(false); bodies.prepare(account); tag=new BodyTag.Live(second,1); drain();
        verify(store,never()).write(any(),any(),anyLong(),anyString(),any()); verify(player.getInventory(),never()).clear();
        verify(player).kickPlayer(contains("needs attention"));
    }

}
