package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.speechbubble.fake.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GraveVisualServicesTest extends GraveRuntimeFixture {
    @Test void lootTransferRestoresAllSlotsExtrasAndExperience() {
        Grave grave=grave(); grave.setItem(0,new ItemStack(Material.DIAMOND,3)); grave.setItem(36,new ItemStack(Material.IRON_BOOTS)); grave.setItem(40,new ItemStack(Material.SHIELD)); grave.addExtra(new ItemStack(Material.DIRT)); grave.getExtras().getFirst().setType(Material.AIR); grave.addExtra(new ItemStack(Material.EMERALD,2)); grave.setExperience(7);
        GraveLootDrop.TransferResult result=GraveLootDrop.transferToPlayer(player,grave,null);
        assertTrue(result.transferred()); assertFalse(result.overflowDropped()); assertTrue(grave.isEmpty()); assertEquals(7,player.getTotalExperience());
        assertEquals(3,player.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum()); assertTrue(player.getInventory().contains(Material.IRON_BOOTS)); assertTrue(player.getInventory().contains(Material.SHIELD)); assertTrue(player.getInventory().contains(Material.EMERALD));
        assertFalse(GraveLootDrop.transferToPlayer(null,grave,null).transferred()); assertFalse(GraveLootDrop.transferToPlayer(player,null,null).overflowDropped());
        assertFalse(GraveLootDrop.transferToPlayer(player,grave,new Location(null,1,65,1)).transferred());
    }

    @Test void overflowDropsAtTheSpecifiedLocationWithoutLeavingLootInTheGrave() {
        for(int slot=0;slot<player.getInventory().getSize();slot++) player.getInventory().setItem(slot,new ItemStack(Material.STONE,64));
        Grave grave=grave(); grave.setItem(0,new ItemStack(Material.DIAMOND,3)); grave.addExtra(new ItemStack(Material.EMERALD,2)); Location location=new Location(world,12,65,12);
        GraveLootDrop.TransferResult result=GraveLootDrop.transferToPlayer(player,grave,location); assertTrue(result.transferred()); assertTrue(result.overflowDropped()); assertTrue(grave.isEmpty());
        List<Item> dropped=world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast).toList(); assertEquals(2,dropped.size());
        assertEquals(Set.of(Material.DIAMOND,Material.EMERALD),new HashSet<>(dropped.stream().map(item -> item.getItemStack().getType()).toList()));
        assertTrue(dropped.stream().allMatch(item -> item.getLocation().distanceSquared(location)<3));
    }

    @Test void worldDropsEmptyAllGraveRegionsAndSpawnExperienceOrb() {
        Grave grave=grave(); grave.setItem(0,new ItemStack(Material.DIAMOND)); grave.addExtra(new ItemStack(Material.DIRT)); grave.getExtras().getFirst().setType(Material.AIR); grave.addExtra(new ItemStack(Material.EMERALD)); grave.setExperience(13); Location at=new Location(world,1,65,1);
        GraveLootDrop.dropAllToWorld(null,at); GraveLootDrop.dropAllToWorld(grave,null); GraveLootDrop.dropAllToWorld(grave,new Location(null,0,0,0)); assertFalse(grave.isEmpty());
        GraveLootDrop.dropAllToWorld(grave,at); assertTrue(grave.isEmpty()); assertEquals(2,world.getEntities().stream().filter(Item.class::isInstance).count());
        ExperienceOrb orb=(ExperienceOrb)world.getEntities().stream().filter(ExperienceOrb.class::isInstance).findFirst().orElseThrow(); assertEquals(13,orb.getExperience());
        GraveLootDrop.dropAllToWorld(grave,at); assertEquals(1,world.getEntities().stream().filter(ExperienceOrb.class::isInstance).count());
    }

    @Test void recoveryMessagesDistinguishOwnerLooterEmptyAndOverflowAndRemoveGraves() {
        Grave empty=grave(); manager.register(empty); assertFalse(GraveRecover.recover(player,empty,null)); assertEquals("§7empty",player.nextMessage()); assertNull(manager.getById(empty.getId()));
        Grave owned=grave(); owned.setExperience(1); manager.register(owned); assertTrue(GraveRecover.recover(player,owned,null)); assertEquals("§arecovered",player.nextMessage());
        for(int slot=0;slot<player.getInventory().getSize();slot++) player.getInventory().setItem(slot,new ItemStack(Material.STONE,64));
        Grave foreign=new Grave(null,UUID.randomUUID(),null); foreign.setItem(0,new ItemStack(Material.DIAMOND)); manager.register(foreign);
        assertTrue(GraveRecover.recover(player,foreign,null)); assertEquals("§alooted",player.nextMessage()); assertEquals("§efull",player.nextMessage()); assertNull(manager.getById(foreign.getId()));
        assertNull(GraveRecover.graveOverflowLocation(foreign)); assertNull(GraveRecover.graveOverflowLocation(foreign,null)); assertEquals(player.getLocation(),GraveRecover.graveOverflowLocation(foreign,player));
        GraveRecover.sendMessage(null,"hello"); GraveRecover.sendMessage(player,null); GraveRecover.sendMessage(player," "); assertNull(player.nextMessage());
    }

    @Test void forcedChunkTicketIsRemovedAfterSuccessAndAfterAnActionFailure() {
        Chunk chunk=mock(Chunk.class); Location location=mock(Location.class); when(location.getWorld()).thenReturn(world); when(location.getChunk()).thenReturn(chunk);
        when(chunk.addPluginChunkTicket(plugin)).thenReturn(true); AtomicInteger ran=new AtomicInteger();
        GraveChunkForceLoad.withForcedChunk(location,ran::incrementAndGet); assertEquals(1,ran.get()); verify(chunk).load(true); verify(chunk).removePluginChunkTicket(plugin);
        IllegalStateException failure=new IllegalStateException("action failed"); assertSame(failure,assertThrows(IllegalStateException.class,() -> GraveChunkForceLoad.withForcedChunk(location,() -> {throw failure;})));
        verify(chunk,times(2)).removePluginChunkTicket(plugin); GraveChunkForceLoad.withForcedChunk(location,null);
        GraveChunkForceLoad.withForcedChunk(null,ran::incrementAndGet); GraveChunkForceLoad.withForcedChunk(new Location(null,0,0,0),ran::incrementAndGet); assertEquals(3,ran.get());
    }

    @Test void ticketApiFailuresDoNotPreventTheActionOrEscapeCleanup() {
        Chunk chunk=mock(Chunk.class); Location location=mock(Location.class); when(location.getWorld()).thenReturn(world); when(location.getChunk()).thenReturn(chunk); AtomicInteger ran=new AtomicInteger();
        when(chunk.addPluginChunkTicket(plugin)).thenThrow(new IllegalStateException("ticket unavailable")); GraveChunkForceLoad.withForcedChunk(location,ran::incrementAndGet); assertEquals(1,ran.get()); verify(chunk,never()).removePluginChunkTicket(plugin);
        doReturn(true).when(chunk).addPluginChunkTicket(plugin); when(chunk.removePluginChunkTicket(plugin)).thenThrow(new IllegalStateException("cleanup unavailable"));
        assertDoesNotThrow(() -> GraveChunkForceLoad.withForcedChunk(location,ran::incrementAndGet)); assertEquals(2,ran.get());
    }

    @Test void expiryServiceSchedulesOnlyWhenEnabledAndCancelsPreviousTask() {
        GraveManager boundary=mock(GraveManager.class);
        try(var managers=mockStatic(GraveManager.class)) {
            managers.when(GraveManager::get).thenReturn(boundary); GraveExpiryService service=GraveExpiryService.get(); assertSame(service,GraveExpiryService.get()); service.start(); verifyNoInteractions(boundary);
            settings.when(GraveLoader::getExpireSeconds).thenReturn(10); settings.when(GraveLoader::isEnabled).thenReturn(false); service.start(); verifyNoInteractions(boundary);
            settings.when(GraveLoader::isEnabled).thenReturn(true); service.start(); verify(boundary).expireOverdue(); server.getScheduler().performTicks(2); verify(boundary).tickExpiry();
            service.start(); verify(boundary,times(2)).expireOverdue(); server.getScheduler().performTicks(2); verify(boundary,times(2)).tickExpiry();
            service.shutdown(); server.getScheduler().performTicks(5); verify(boundary,times(2)).tickExpiry(); service.shutdown();
        }
    }

    @Test void trackerSnapshotsSolidFeetOrGroundButSkipsSpectatorsDisabledAndAirbornePlayers() {
        LastSolidTracker tracker=LastSolidTracker.get(); assertSame(tracker,LastSolidTracker.get()); assertNull(tracker.getLastSolid((Player)null)); assertNull(tracker.getLastSolid((UUID)null)); assertNull(tracker.getLastSolid(player));
        tracker.start(); settings.when(GraveLoader::isEnabled).thenReturn(false); server.getScheduler().performTicks(2); assertNull(tracker.getLastSolid(player));
        settings.when(GraveLoader::isEnabled).thenReturn(true); player.setGameMode(GameMode.SPECTATOR); server.getScheduler().performTicks(2); assertNull(tracker.getLastSolid(player));
        player.setGameMode(GameMode.SURVIVAL); server.getScheduler().performTicks(2); assertNull(tracker.getLastSolid(player));
        world.getBlockAt(0,64,0).setType(Material.STONE); server.getScheduler().performTicks(2); assertEquals(new Location(world,0,64,0),tracker.getLastSolid(player));
        Location returned=tracker.getLastSolid(player.getUniqueId()); returned.setX(99); assertEquals(0,tracker.getLastSolid(player).getX());
        world.getBlockAt(0,65,0).setType(Material.STONE); tracker.start(); server.getScheduler().performTicks(2); assertEquals(new Location(world,0,65,0),tracker.getLastSolid(player));
        tracker.shutdown(); world.getBlockAt(0,65,0).setType(Material.AIR); server.getScheduler().performTicks(3); assertEquals(65,tracker.getLastSolid(player).getBlockY());
    }

    @Test void trackerIgnoresVoidLocationsWithoutBreakingFutureSnapshots() {
        player.teleport(new Location(world,0,world.getMinHeight()-2,0)); LastSolidTracker tracker=LastSolidTracker.get(); tracker.start();
        assertDoesNotThrow(() -> server.getScheduler().performTicks(2)); assertNull(tracker.getLastSolid(player));
        world.getBlockAt(0,world.getMinHeight(),0).setType(Material.AIR); player.teleport(new Location(world,0,world.getMinHeight(),0));
        assertDoesNotThrow(() -> server.getScheduler().performTicks(2)); assertNull(tracker.getLastSolid(player));
        player.teleport(new Location(world,0,world.getMaxHeight()+2,0)); assertDoesNotThrow(() -> server.getScheduler().performTicks(2)); assertNull(tracker.getLastSolid(player));
        player.teleport(new Location(world,0,65,0)); world.getBlockAt(0,64,0).setType(Material.STONE); server.getScheduler().performTicks(2);
        assertEquals(64,tracker.getLastSolid(player).getBlockY());
    }

    @Test void trackerToleratesAPlayerWhoseLocationWorldIsUnavailable() {
        Player unavailable=mock(Player.class); when(unavailable.getUniqueId()).thenReturn(UUID.randomUUID()); when(unavailable.getGameMode()).thenReturn(GameMode.SURVIVAL); when(unavailable.getLocation()).thenReturn(new Location(null,0,0,0));
        try(var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(unavailable)); LastSolidTracker.get().start(); server.getScheduler().performTicks(2); assertNull(LastSolidTracker.get().getLastSolid(unavailable));
        }
    }

    FakeTextDisplayPackets readyPackets() {
        FakeTextDisplayPackets packets=mock(FakeTextDisplayPackets.class); protocol.when(ProtocolLibBridge::isReady).thenReturn(true); protocol.when(ProtocolLibBridge::getPackets).thenReturn(packets); return packets;
    }
    Grave visibleGrave() { Grave grave=grave(); grave.getBlockLocation().getBlock().setType(Material.CHEST); grave.getBlockLocation().getChunk().load(); manager.register(grave); return grave; }

    @Test void visualsSpawnPerViewerLinesThenDestroyOldStacksWhenRefreshingOrLeavingRange() {
        FakeTextDisplayPackets packets=readyPackets(); Grave grave=visibleGrave(); GraveVisualManager visual=GraveVisualManager.get(); assertSame(visual,GraveVisualManager.get());
        identities.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn("Aria"); visual.refreshViewer(player);
        ArgumentCaptor<Location> locations=ArgumentCaptor.forClass(Location.class); ArgumentCaptor<Integer> ids=ArgumentCaptor.forClass(Integer.class);
        verify(packets).spawn(eq(player),ids.capture(),any(UUID.class),locations.capture(),eq("Aria"),eq(1f),eq(200)); assertEquals(-60000,ids.getValue());
        assertEquals(new Location(world,1.5,65.2,1.5),locations.getValue()); visual.refreshViewer(player); verify(packets).destroy(player,List.of(-60000));
        player.teleport(new Location(world,100,65,100)); visual.refreshViewer(player); verify(packets).destroy(player,List.of(-60001));
        visual.clearViewer(player.getUniqueId()); visual.clearViewer(UUID.randomUUID()); visual.removeGrave(grave.getId());
    }

    @Test void visualsRespectOfflinePlayersMissingProtocolAndMissingPacketAdapters() {
        GraveVisualManager visual=GraveVisualManager.get(); visual.refreshViewer(null); Player offline=mock(Player.class); visual.refreshViewer(offline); visual.refreshViewer(player);
        protocol.when(ProtocolLibBridge::isReady).thenReturn(true); visual.refreshViewer(player); FakeTextDisplayPackets packets=readyPackets(); Grave grave=visibleGrave(); visual.refreshViewer(player);
        protocol.when(ProtocolLibBridge::getPackets).thenReturn(null); visual.removeGrave(grave.getId()); verify(packets,never()).destroy(any(),anyList());
        protocol.when(ProtocolLibBridge::getPackets).thenReturn(packets); visual.refreshViewer(player); visual.clearViewer(player.getUniqueId()); verify(packets).destroy(eq(player),anyList());
    }

    @Test void visualsRevalidateTheGraveAfterPacketCallbacksChangeItsLocationOrBlock() {
        FakeTextDisplayPackets packets=readyPackets(); Grave grave=visibleGrave(); GraveVisualManager visual=GraveVisualManager.get(); visual.refreshViewer(player);
        doAnswer(call -> { grave.setBlockLocation(null); return null; }).when(packets).destroy(eq(player),anyList());
        visual.refreshViewer(player); verify(packets,times(1)).spawn(eq(player),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());
        grave.setBlockLocation(new Location(world,1,64,1)); doNothing().when(packets).destroy(eq(player),anyList()); visual.refreshViewer(player);
        doAnswer(call -> { grave.getBlockLocation().getBlock().setType(Material.AIR); return null; }).when(packets).destroy(eq(player),anyList());
        visual.refreshViewer(player); verify(packets,times(2)).spawn(eq(player),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());
    }

    @Test void removalAndShutdownDestroyEveryOnlineViewerStack() {
        FakeTextDisplayPackets packets=readyPackets(); Grave grave=visibleGrave(); GraveVisualManager visual=GraveVisualManager.get(); visual.refreshViewer(player);
        visual.removeGrave(UUID.randomUUID()); verify(packets,never()).destroy(any(),anyList()); visual.removeGrave(grave.getId()); verify(packets).destroy(eq(player),anyList());
        visual.refreshViewer(player); visual.shutdown(); verify(packets,times(2)).destroy(eq(player),anyList()); visual.shutdown();
        visual.refreshViewer(player); player.disconnect(); visual.clearViewer(player.getUniqueId()); verify(packets,times(2)).destroy(eq(player),anyList());
    }

    @Test void visualTicksWarnOnceForMissingProtocolThenResumeWhenItAppears() {
        GraveVisualManager visual=GraveVisualManager.get(); visual.startTicks(); server.getScheduler().performTicks(40); verify(logger,times(1)).warning(contains("require ProtocolLib"));
        FakeTextDisplayPackets packets=readyPackets(); visibleGrave(); server.getScheduler().performTicks(20); verify(packets).spawn(eq(player),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());
        protocol.when(ProtocolLibBridge::isReady).thenReturn(false); server.getScheduler().performTicks(20); verify(logger,times(2)).warning(contains("require ProtocolLib"));
    }

    @Test void visualShutdownCancelsItsRepeatingTaskAndRepeatedStartsDoNotDuplicateIt() {
        FakeTextDisplayPackets packets=readyPackets(); visibleGrave(); GraveVisualManager visual=GraveVisualManager.get(); visual.startTicks(); visual.startTicks();
        server.getScheduler().performTicks(20); verify(packets,times(1)).spawn(eq(player),anyInt(),any(),any(),anyString(),anyFloat(),anyInt());
        visual.shutdown(); clearInvocations(packets); server.getScheduler().performTicks(40); verifyNoInteractions(packets);
    }

    @Test void legacyHologramCleanupRemovesItsEntityAndClearsTheSavedIdentifier() {
        Grave grave=grave(); UUID id=UUID.randomUUID(); grave.setHologramId(id);
        try(var displays=mockStatic(TextDisplayHelper.class)) { GraveVisualManager.cleanupLegacyHologram(null); GraveVisualManager.cleanupLegacyHologram(grave); displays.verify(() -> TextDisplayHelper.removeDisplay(id)); assertNull(grave.getHologramId()); GraveVisualManager.cleanupLegacyHologram(grave); displays.verifyNoMoreInteractions(); }
    }

    @Test void hologramNamesKillerDetailsAndLootHintsRespectTheViewer() {
        Grave grave=grave(); identities.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn("Aria"); assertEquals(List.of(),GraveHologramTexts.baseLines(null)); assertEquals(List.of("Aria"),GraveHologramTexts.baseLines(grave));
        settings.when(GraveLoader::isHologramShowKiller).thenReturn(true); grave.setKillerDisplay("Killed by bandit"); assertEquals(List.of("Aria","Killed by bandit"),GraveHologramTexts.baseLines(grave));
        grave.setKillerDisplay(" "); grave.setKiller(player.getUniqueId()); assertEquals("Killed by Alex",GraveHologramTexts.baseLines(grave).get(1)); grave.setKiller(null); assertEquals(1,GraveHologramTexts.baseLines(grave).size());
        settings.when(GraveLoader::getMessageRobHint).thenReturn("&arob"); assertEquals(1,GraveHologramTexts.linesForViewer(player,grave).size()); assertEquals(1,GraveHologramTexts.linesForViewer(null,grave).size()); assertTrue(GraveHologramTexts.linesForViewer(player,null).isEmpty());
        Player other=server.addPlayer("Other"); grave.setLocked(false); assertEquals("§arob",GraveHologramTexts.linesForViewer(other,grave).getLast());
        settings.when(GraveLoader::getMessageRobHint).thenReturn(" "); assertEquals("",GraveHologramTexts.linesForViewer(other,grave).getLast());
        identities.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn(" "); assertEquals("Alex",GraveHologramTexts.baseLines(grave).getFirst());
        Grave unknown=new Grave(null,UUID.randomUUID(),null); OfflinePlayer missing=mock(OfflinePlayer.class); when(missing.getName()).thenReturn(" ");
        try(var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(unknown.getOwner())).thenReturn(missing); assertEquals("Unknown",GraveHologramTexts.baseLines(unknown).getFirst());
        }
    }

    @Test void hologramForAnUnownedGraveUsesItsUnknownNameFallback() {
        Grave orphan=new Grave(null,null,new Location(world,1,64,1)); manager.register(orphan);
        assertEquals(List.of("Unknown"),assertDoesNotThrow(() -> GraveHologramTexts.baseLines(orphan)));
    }

    @Test void hologramTimersShowRemainingTimeAndExpiryWithoutPrintingBlankTemplates() {
        Grave grave=grave(); assertNull(GraveTimerFormat.timerLine(grave)); settings.when(GraveLoader::getExpireSeconds).thenReturn(120); assertNull(GraveTimerFormat.timerLine(null));
        settings.when(GraveLoader::getHologramTimerFormat).thenReturn("&7{time} left"); assertTrue(GraveTimerFormat.timerLine(grave).endsWith(" left")); assertEquals(2,GraveHologramTexts.baseLines(grave).size());
        Grave expired=new Grave(null,player.getUniqueId(),null,null,false,true,1,0,null,null,null,null,null,null); settings.when(GraveLoader::getHologramTimerExpiring).thenReturn("&cexpired"); assertEquals("§cexpired",GraveTimerFormat.timerLine(expired));
        settings.when(GraveLoader::getHologramTimerExpiring).thenReturn(" "); assertNull(GraveTimerFormat.timerLine(expired)); settings.when(GraveLoader::getHologramTimerFormat).thenReturn(null); assertNull(GraveTimerFormat.timerLine(grave));
    }

    @Test void graveCommandUnlocksNewestOwnedGraveAndExplainsInvalidRequests() {
        GraveCommand executor=new GraveCommand(); Command command=mock(Command.class); when(command.getName()).thenReturn("other"); assertFalse(executor.onCommand(player,command,"grave",new String[0]));
        when(command.getName()).thenReturn("GrAvE"); CommandSender console=mock(CommandSender.class); assertTrue(executor.onCommand(console,command,"grave",new String[0])); verify(console).sendMessage(contains("Only players"));
        assertTrue(executor.onCommand(player,command,"grave",new String[0])); assertTrue(player.nextMessage().contains("Usage")); executor.onCommand(player,command,"grave",new String[]{"other"}); assertTrue(player.nextMessage().contains("Usage"));
        settings.when(GraveLoader::getMessageUnlockNone).thenReturn("none"); settings.when(GraveLoader::getMessageUnlockAlready).thenReturn("already"); settings.when(GraveLoader::getMessageUnlockSuccess).thenReturn("success");
        executor.onCommand(player,command,"grave",new String[]{"unlock"}); assertEquals("none",player.nextMessage()); Grave grave=grave(); manager.register(grave); executor.onCommand(player,command,"grave",new String[]{"UNLOCK"}); assertFalse(grave.isLocked()); assertEquals("success",player.nextMessage());
        executor.onCommand(player,command,"grave",new String[]{"unlock"}); assertEquals("already",player.nextMessage());
        assertEquals(List.of("unlock"),executor.onTabComplete(player,command,"grave",new String[]{"UN"})); assertTrue(executor.onTabComplete(player,command,"grave",new String[]{"x"}).isEmpty()); assertTrue(executor.onTabComplete(player,command,"grave",new String[0]).isEmpty());
    }

    @Test void lootRulesRejectMissingDataAndRecognizeOnlyEligibleTraits() {
        Grave grave=grave(); assertFalse(GraveLootRules.canRecover(null,grave)); assertFalse(GraveLootRules.canRecover(player,null)); assertFalse(GraveLootRules.canLootGrave(null)); assertTrue(GraveLootRules.canRecover(player,grave)); assertFalse(GraveLootRules.canSteal(null,grave));
        assertFalse(GraveLootRules.hasCanLootGravesTrait(player)); PlayerData data=mock(PlayerData.class); players.when(() -> PlayerManager.get(player)).thenReturn(data); assertFalse(GraveLootRules.hasCanLootGravesTrait(player));
        when(data.hasActiveCharacter()).thenReturn(true); assertFalse(GraveLootRules.hasCanLootGravesTrait(player)); RPCharacter character=mock(RPCharacter.class); when(data.getActiveCharacter()).thenReturn(character);
        Trait missing=mock(Trait.class), allowed=mock(Trait.class); TraitData traitData=mock(TraitData.class); when(allowed.getTraitData()).thenReturn(traitData); when(character.getTraits()).thenReturn(Arrays.asList(null,missing,allowed));
        assertFalse(GraveLootRules.hasCanLootGravesTrait(player)); when(traitData.canLootGraves()).thenReturn(true); assertTrue(GraveLootRules.hasCanLootGravesTrait(player));
        Grave foreign=new Grave(null,UUID.randomUUID(),null); foreign.setKiller(player.getUniqueId()); assertTrue(GraveLootRules.canSteal(player,foreign)); foreign.setKiller(UUID.randomUUID()); assertFalse(GraveLootRules.canSteal(player,foreign));
        player.addAttachment(plugin,"rpchar.grave.admin",true); assertTrue(GraveLootRules.canRecover(player,foreign)); assertTrue(GraveLootRules.canLootGrave(player));
    }

    @Test void killerDisplayResolvesPlayersProjectilesCustomMobsAndEnvironmentalDamage() {
        assertNull(GraveKillerDisplay.build(null,player)); Player killer=server.addPlayer("Killer"); identities.when(() -> DisplayIdentityService.resolveDisplay(killer)).thenReturn("§cBandit"); assertEquals("Killed by Bandit",GraveKillerDisplay.build(player,killer));
        identities.when(() -> DisplayIdentityService.resolveDisplay(killer)).thenReturn(" "); assertEquals("Killed by Killer",GraveKillerDisplay.build(player,killer));
        LivingEntity mob=mock(LivingEntity.class); when(mob.getCustomName()).thenReturn("§aGiant"); when(mob.getType()).thenReturn(EntityType.ZOMBIE);
        EntityDamageByEntityEvent event=mock(EntityDamageByEntityEvent.class); when(event.getDamager()).thenReturn(killer); player.setLastDamageCause(event); assertEquals("Killed by Killer",GraveKillerDisplay.build(player,null));
        when(event.getDamager()).thenReturn(mob); assertEquals("Killed by Giant",GraveKillerDisplay.build(player,null)); when(mob.getCustomName()).thenReturn("§a"); assertEquals("Killed by Zombie",GraveKillerDisplay.build(player,null));
        Projectile arrow=mock(Projectile.class); when(event.getDamager()).thenReturn(arrow); when(arrow.getShooter()).thenReturn(killer); assertEquals("Killed by Killer",GraveKillerDisplay.build(player,null));
        when(arrow.getShooter()).thenReturn(mob); assertEquals("Killed by Zombie",GraveKillerDisplay.build(player,null)); when(arrow.getShooter()).thenReturn(null); when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.PROJECTILE); assertEquals("Killed by Projectile",GraveKillerDisplay.build(player,null));
        when(event.getDamager()).thenReturn(mock(Entity.class)); assertEquals("Killed by Projectile",GraveKillerDisplay.build(player,null));
        EntityDamageEvent fall=mock(EntityDamageEvent.class); when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL); player.setLastDamageCause(fall); assertEquals("Killed by Fall",GraveKillerDisplay.build(player,null));
        player.setLastDamageCause(null); assertNull(GraveKillerDisplay.build(player,null)); assertNull(GraveKillerDisplay.formatDisplay("§a"));
        assertNull(GraveKillerDisplay.formatDamageCause((EntityDamageEvent.DamageCause)null)); assertNull(GraveKillerDisplay.formatEntityType(null)); assertNull(GraveKillerDisplay.formatEntityType(" "));
    }
}
