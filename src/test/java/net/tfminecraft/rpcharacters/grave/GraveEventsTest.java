package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.pvp.*;
import net.tfminecraft.rpcharacters.speechbubble.fake.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class GraveEventsTest extends GraveRuntimeFixture {
    PlayerDeathEvent death(int experience) {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class); when(event.getEntity()).thenReturn(player);
        when(event.getDroppedExp()).thenReturn(experience); when(event.getDrops()).thenReturn(new ArrayList<>(List.of(new ItemStack(Material.DIAMOND))));
        boolean[] kept={false}; when(event.getKeepInventory()).thenAnswer(call -> kept[0]); doAnswer(call -> { kept[0]=call.getArgument(0); return null; }).when(event).setKeepInventory(anyBoolean());
        return event;
    }
    PlayerRespawnEvent respawn() { PlayerRespawnEvent event = mock(PlayerRespawnEvent.class); when(event.getPlayer()).thenReturn(player); return event; }
    Grave newest() { return manager.findNewestByOwner(player.getUniqueId()); }

    @Test void deathDoesNothingWhenDisabledKeptOrThereIsNoLoot() {
        GraveDeathListener listener = new GraveDeathListener(); PlayerDeathEvent event = death(0);
        settings.when(GraveLoader::isEnabled).thenReturn(false); listener.onPlayerDeath(event); verify(event, never()).setDroppedExp(anyInt());
        settings.when(GraveLoader::isEnabled).thenReturn(true); when(event.getKeepInventory()).thenReturn(true); listener.onPlayerDeath(event);
        when(event.getKeepInventory()).thenReturn(false); listener.onPlayerDeath(event); assertNull(newest()); assertEquals(1, event.getDrops().size());
        listener.onPlayerRespawn(respawn()); listener.keepItemsAfterPouchDrop(event); verify(event, never()).setKeepInventory(true);
    }

    @Test void deathStoresInventoryAndExperienceWhileKeptItemsReturnAtRespawn() {
        GraveDeathListener listener = new GraveDeathListener(); player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(1, new ItemStack(Material.PAPER)); player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD)); PlayerDeathEvent event = death(9); listener.onPlayerDeath(event);
        Grave grave = newest(); assertNotNull(grave); assertEquals(3, grave.getItem(0).getAmount()); assertEquals(9, grave.getExperience());
        assertEquals(Material.IRON_BOOTS, grave.getItem(36).getType()); assertEquals(Material.SHIELD, grave.getItem(40).getType()); assertNull(grave.getItem(1));
        assertTrue(event.getDrops().isEmpty()); verify(event).setDroppedExp(0); player.getInventory().clear(); listener.onPlayerRespawn(respawn());
        assertEquals(grave.getId(), GraveInsuranceTickets.boundGrave(player.getInventory().getItem(1)));
        assertTrue(player.nextMessage().contains("graves")); assertEquals("§eunlock", player.nextMessage()); assertEquals("§ainsured", player.nextMessage());
        assertNull(player.nextMessage()); listener.onPlayerRespawn(respawn()); assertNull(player.nextMessage());
    }

    @Test void deathsWithOnlyKeptItemsRestoreThoseItemsWithoutCreatingAnEmptyGrave() {
        ItemStack ticket=new ItemStack(Material.PAPER,2); player.getInventory().setItem(1,ticket);
        PlayerDeathEvent event=death(0); when(event.getDrops()).thenReturn(new ArrayList<>(List.of(ticket.clone())));
        GraveDeathListener listener=new GraveDeathListener(); listener.onPlayerDeath(event); listener.keepItemsAfterPouchDrop(event); if(!event.getKeepInventory()) player.getInventory().clear(); listener.onPlayerRespawn(respawn());
        assertEquals(ticket,player.getInventory().getItem(1),"Kept items must survive even when there is nothing to put in a grave");
        assertTrue(event.getDrops().isEmpty()); assertNull(newest()); assertNull(GraveInsuranceTickets.boundGrave(player.getInventory().getItem(1)));
    }

    @Test void keptItemsRemoveOnlyTheirOwnQuantityAcrossMatchingDropStacks() {
        player.getInventory().setItem(1,new ItemStack(Material.PAPER,2)); PlayerDeathEvent event=death(0);
        when(event.getDrops()).thenReturn(new ArrayList<>(List.of(new ItemStack(Material.PAPER),new ItemStack(Material.DIRT,3),new ItemStack(Material.PAPER,4))));
        GraveDeathListener listener=new GraveDeathListener(); listener.onPlayerDeath(event);
        assertEquals(List.of(new ItemStack(Material.DIRT,3),new ItemStack(Material.PAPER,3)),event.getDrops());
        player.getInventory().clear(); listener.onPlayerRespawn(respawn()); assertEquals(2,player.getInventory().getItem(1).getAmount()); assertNull(newest());
    }

    @Test void keptBundleOnlyDeathRestoresItsOriginalUnextractedContents() {
        settings.when(() -> GraveLoader.keepOutOfGrave(anyInt(),any())).thenAnswer(call -> { ItemStack item=call.getArgument(1); return item!=null && item.getType()==Material.BUNDLE; });
        ItemStack kept=bundle(new ItemStack(Material.PAPER),new ItemStack(Material.DIAMOND)); player.getInventory().setItem(0,kept);
        PlayerDeathEvent event=death(0); when(event.getDrops()).thenReturn(new ArrayList<>(List.of(kept.clone())));
        GraveDeathListener listener=new GraveDeathListener(); listener.onPlayerDeath(event); player.getInventory().clear(); listener.onPlayerRespawn(respawn());
        assertEquals(kept,player.getInventory().getItem(0)); assertEquals(List.of(new ItemStack(Material.PAPER),new ItemStack(Material.DIAMOND)),((BundleMeta)player.getInventory().getItem(0).getItemMeta()).getItems());
        assertTrue(event.getDrops().isEmpty()); assertNull(newest()); server.getScheduler().performOneTick(); assertTrue(player.getInventory().all(Material.PAPER).isEmpty());
    }

    @Test void excludedArmorAndOffhandReturnToTheirOriginalSlots() {
        settings.when(() -> GraveLoader.keepOutOfGrave(anyInt(), any())).thenAnswer(call -> (int) call.getArgument(0) >= 36 && !Grave.isBlank(call.getArgument(1)));
        player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS)); player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        GraveDeathListener listener = new GraveDeathListener(); PlayerDeathEvent event = death(5); listener.onPlayerDeath(event);
        Grave grave = newest(); assertNotNull(grave); assertNull(grave.getItem(36)); assertNull(grave.getOffhand());
        player.getInventory().clear(); listener.onPlayerRespawn(respawn());
        assertEquals(Material.IRON_BOOTS, player.getInventory().getBoots().getType()); assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
    }

    @Test void armorOrOffhandAloneCanCreateAGraveWithoutExperience() {
        GraveDeathListener listener = new GraveDeathListener(); player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
        listener.onPlayerDeath(death(0)); assertNotNull(newest()); manager.despawn(newest()); player.getInventory().clear();
        player.teleport(new Location(world, 8, 65, 8)); player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        listener.onPlayerDeath(death(0)); assertNotNull(newest());
    }

    @Test void failedPlacementLeavesVanillaDropsAndExperienceUntouched() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND)); GraveDeathListener listener = new GraveDeathListener();
        GraveManager unavailable = mock(GraveManager.class);
        try (var managers = mockStatic(GraveManager.class)) {
            managers.when(GraveManager::get).thenReturn(unavailable); PlayerDeathEvent noBlock = death(7); listener.onPlayerDeath(noBlock);
            assertEquals(1, noBlock.getDrops().size()); verify(noBlock, never()).setDroppedExp(anyInt());
            when(unavailable.findChestBlock(eq(player), any())).thenReturn(world.getBlockAt(1, 64, 1));
            PlayerDeathEvent noGrave = death(7); listener.onPlayerDeath(noGrave); assertEquals(1, noGrave.getDrops().size()); verify(noGrave, never()).setDroppedExp(anyInt());
        }
    }

    @Test void failedGraveSaveMustNotDiscardVanillaLootOrLeaveAPhantomGrave() throws Exception {
        Files.writeString(directory.resolve("graves"), "blocked storage"); player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        PlayerDeathEvent event = death(7); new GraveDeathListener().onPlayerDeath(event);
        assertEquals(1, event.getDrops().size(), "Failed persistence must retain the vanilla item drops");
        verify(event, never()).setDroppedExp(0); assertNull(newest()); assertEquals(Material.AIR, world.getBlockAt(0, 5, 0).getType());
    }

    @Test void creatingAGraveDoesNotReuseAnOrdinaryChestContainingOtherPlayersItems() {
        Block occupied=world.getBlockAt(4,64,4); occupied.setType(Material.CHEST); Chest chest=(Chest)occupied.getState();
        chest.getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,7)); occupied.getRelative(BlockFace.UP).setType(Material.STONE);
        assertEquals(new ItemStack(Material.DIAMOND,7),((Chest)occupied.getState()).getBlockInventory().getItem(0));
        player.teleport(occupied.getLocation()); player.getInventory().setItem(0,new ItemStack(Material.EMERALD));
        new GraveDeathListener().onPlayerDeath(death(0)); Grave grave=newest(); assertNotNull(grave);
        assertNotEquals(occupied.getLocation(),grave.getBlockLocation(),"Existing stored items must not become a grave block");
        assertEquals(new ItemStack(Material.DIAMOND,7),((Chest)occupied.getState()).getBlockInventory().getItem(0));
    }

    @Test void lastResortPlacementAlsoPreservesOccupiedContainersWhenNoAirIsAvailable() {
        int y=world.getMaxHeight()-1; for(int x=0;x<16;x++) for(int z=0;z<16;z++) world.getBlockAt(x,y,z).setType(Material.STONE);
        Block occupied=world.getBlockAt(4,y,4); occupied.setType(Material.CHEST); Chest chest=(Chest)occupied.getState(); chest.getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
        assertEquals(new ItemStack(Material.DIAMOND,7),((Chest)occupied.getState()).getBlockInventory().getItem(0));
        player.teleport(occupied.getLocation()); player.getInventory().setItem(0,new ItemStack(Material.EMERALD)); PlayerDeathEvent event=death(0);
        new GraveDeathListener().onPlayerDeath(event); assertNull(newest(),"Last-resort placement must not consume a container's inventory"); assertEquals(1,event.getDrops().size());
        assertEquals(new ItemStack(Material.DIAMOND,7),((Chest)occupied.getState()).getBlockInventory().getItem(0));
    }

    @Test void pvpVictimsKeepTheirInventoryOnlyAfterPouchDropsAndOnlyForUncancelledDeaths() {
        PlayerMock killer = server.addPlayer("Killer"); strikes.when(() -> PvpStrikeService.graveContext(eq(player), anyBoolean())).thenReturn(new PvpStrikeService.GraveContext(true, false, killer.getUniqueId()));
        GraveDeathListener listener = new GraveDeathListener(); PlayerDeathEvent event = death(7); listener.onPlayerDeath(event);
        verify(event, never()).setKeepInventory(true); assertNull(newest()); listener.keepItemsAfterPouchDrop(event);
        verify(event).setKeepInventory(true); assertTrue(event.getDrops().isEmpty()); listener.keepItemsAfterPouchDrop(event); verify(event, times(1)).setKeepInventory(true);
        PlayerDeathEvent cancelled = death(7); when(cancelled.isCancelled()).thenReturn(true); listener.onPlayerDeath(cancelled); listener.keepItemsAfterPouchDrop(cancelled);
        verify(cancelled, never()).setKeepInventory(true); assertEquals(1, cancelled.getDrops().size());
    }

    @Test void evilPvpVictimLeavesUnlockedGraveAndGetsTheCorrectNotice() {
        PlayerMock killer = server.addPlayer("Killer"); strikes.when(() -> PvpStrikeService.graveContext(eq(player), anyBoolean())).thenReturn(new PvpStrikeService.GraveContext(true, true, killer.getUniqueId()));
        identities.when(() -> DisplayIdentityService.resolveDisplay(killer)).thenReturn("§cBandit"); player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        GraveDeathListener listener = new GraveDeathListener(); listener.onPlayerDeath(death(1));
        assertFalse(newest().isLocked()); assertEquals(killer.getUniqueId(), newest().getKiller()); assertEquals("Killed by Bandit", newest().getKillerDisplay());
        listener.onPlayerRespawn(respawn()); assertTrue(player.nextMessage().contains("graves")); assertEquals("§cpublic", player.nextMessage());
    }

    @Test void splitInsuranceTicketIsReturnedOneTickAfterRespawn() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND)); player.getInventory().setItem(1, new ItemStack(Material.PAPER, 3));
        GraveDeathListener listener = new GraveDeathListener(); listener.onPlayerDeath(death(0)); UUID id = newest().getId();
        player.getInventory().clear(); listener.onPlayerRespawn(respawn()); assertEquals(2, player.getInventory().getItem(1).getAmount());
        assertNull(GraveInsuranceTickets.boundGrave(player.getInventory().getItem(1))); server.getScheduler().performOneTick();
        assertTrue(Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).anyMatch(item -> id.equals(GraveInsuranceTickets.boundGrave(item))));
    }

    @Test void splitTicketDropsOnOverflowAndOfflineRecipientsAreIgnored() {
        for (boolean online : List.of(true, false)) {
            player.getInventory().clear(); player.getInventory().setItem(0, new ItemStack(Material.DIAMOND)); player.getInventory().setItem(1, new ItemStack(Material.PAPER, 2));
            player.teleport(new Location(world, online ? 8 : 12, 65, 8)); GraveDeathListener listener = new GraveDeathListener(); listener.onPlayerDeath(death(0));
            player.getInventory().clear(); listener.onPlayerRespawn(respawn()); for (int slot=0;slot<player.getInventory().getSize();slot++) player.getInventory().setItem(slot,new ItemStack(Material.STONE,64));
            long before = world.getEntities().stream().filter(Item.class::isInstance).count(); if (!online) player.disconnect();
            server.getScheduler().performOneTick(); assertEquals(before + (online ? 1 : 0), world.getEntities().stream().filter(Item.class::isInstance).count());
        }
    }

    @Test void bundledTicketIsExtractedAndBoundWhileBlankNoticesStaySilent() {
        settings.when(GraveLoader::getMessagePlaced).thenReturn(" "); settings.when(GraveLoader::getMessageUnlockHint).thenReturn(null); settings.when(GraveLoader::getMessageInsuranceBound).thenReturn("");
        ItemStack bundle = bundle(new ItemStack(Material.PAPER), new ItemStack(Material.DIAMOND)); player.getInventory().setItem(0,bundle);
        GraveDeathListener listener = new GraveDeathListener(); listener.onPlayerDeath(death(0)); Grave grave=newest(); assertNotNull(grave);
        assertEquals(List.of(new ItemStack(Material.DIAMOND)), ((BundleMeta)grave.getItem(0).getItemMeta()).getItems());
        player.getInventory().clear(); listener.onPlayerRespawn(respawn()); server.getScheduler().performOneTick();
        assertEquals(grave.getId(), GraveInsuranceTickets.boundGrave(player.getInventory().getItem(0))); assertNull(player.nextMessage());
    }

    PlayerInteractEvent interact(Action action, EquipmentSlot hand) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getAction()).thenReturn(action);
        when(event.getHand()).thenReturn(hand); when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT); return event;
    }

    @Test void insuranceInteractionIgnoresWrongActionsHandsDisabledSettingsAndOrdinaryItems() {
        GraveInsuranceListener listener = new GraveInsuranceListener(); PlayerInteractEvent event=interact(Action.LEFT_CLICK_AIR,EquipmentSlot.HAND); listener.onPlayerInteract(event); verify(event,never()).setCancelled(true);
        event=interact(Action.RIGHT_CLICK_AIR,EquipmentSlot.OFF_HAND); listener.onPlayerInteract(event); verify(event,never()).setCancelled(true);
        event=interact(Action.RIGHT_CLICK_BLOCK,EquipmentSlot.HAND); when(event.useItemInHand()).thenReturn(Event.Result.DENY); listener.onPlayerInteract(event); verify(event,never()).setCancelled(true);
        event=interact(Action.RIGHT_CLICK_AIR,EquipmentSlot.HAND); settings.when(GraveLoader::isEnabled).thenReturn(false); listener.onPlayerInteract(event);
        settings.when(GraveLoader::isEnabled).thenReturn(true); settings.when(GraveLoader::isInsuranceEnabled).thenReturn(false); listener.onPlayerInteract(event);
        settings.when(GraveLoader::isInsuranceEnabled).thenReturn(true); listener.onPlayerInteract(event); verify(event,never()).setCancelled(true);
    }

    @Test void unboundAndMissingGravesGiveExplanationsAndRemoveObsoleteTicketLore() {
        GraveInsuranceListener listener = new GraveInsuranceListener(); player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));
        PlayerInteractEvent event=interact(Action.RIGHT_CLICK_AIR,EquipmentSlot.HAND); listener.onPlayerInteract(event); verify(event).setCancelled(true); assertEquals("§enone",player.nextMessage());
        Grave missing=grave(); player.getInventory().setItemInMainHand(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),missing));
        listener.onPlayerInteract(interact(Action.RIGHT_CLICK_BLOCK,EquipmentSlot.HAND)); assertNull(GraveInsuranceTickets.boundGrave(player.getInventory().getItemInMainHand())); assertEquals("§egone",player.nextMessage());
        settings.when(GraveLoader::getMessageInsuranceNone).thenReturn(" "); listener.onPlayerInteract(event); assertNull(player.nextMessage());
    }

    @Test void insuranceRecoversRemotelyAndConsumesExactlyOneTicket() {
        for (int amount : List.of(1,3)) {
            Grave grave=grave(); grave.setExperience(5); manager.register(grave);
            ItemStack ticket=GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),grave); ticket.setAmount(amount); player.getInventory().setItemInMainHand(ticket);
            new GraveInsuranceListener().onPlayerInteract(interact(Action.RIGHT_CLICK_AIR,EquipmentSlot.HAND)); assertNull(manager.getById(grave.getId()));
            if(amount==1) assertTrue(player.getInventory().getItemInMainHand().getType().isAir()); else assertEquals(amount-1,player.getInventory().getItemInMainHand().getAmount());
        }
        assertEquals(10,player.getTotalExperience());
    }

    @Test void reusableOrEmptyInsuranceTicketIsUnboundAfterTheGraveDisappears() {
        settings.when(GraveLoader::isInsuranceConsume).thenReturn(false); Grave full=grave(); full.setExperience(1); manager.register(full);
        player.getInventory().setItemInMainHand(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),full)); new GraveInsuranceListener().onPlayerInteract(interact(Action.RIGHT_CLICK_AIR,EquipmentSlot.HAND));
        assertEquals(Material.PAPER,player.getInventory().getItemInMainHand().getType()); assertNull(GraveInsuranceTickets.boundGrave(player.getInventory().getItemInMainHand()));
        settings.when(GraveLoader::isInsuranceConsume).thenReturn(true); Grave empty=grave(); manager.register(empty);
        player.getInventory().setItemInMainHand(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),empty)); new GraveInsuranceListener().onPlayerInteract(interact(Action.RIGHT_CLICK_AIR,EquipmentSlot.HAND));
        assertEquals(Material.PAPER,player.getInventory().getItemInMainHand().getType()); assertNull(GraveInsuranceTickets.boundGrave(player.getInventory().getItemInMainHand()));
    }

    @Test void insuranceBindingsPreserveExistingLoreAndSplitOnlyTheFirstEligibleTicket() {
        assertNull(GraveInsuranceTickets.bindableSlot(null)); assertNull(GraveInsuranceTickets.bindableSlot(Map.of()));
        assertFalse(GraveInsuranceTickets.bind(null,grave()).bound()); assertFalse(GraveInsuranceTickets.bind(Map.of(),grave()).bound());
        assertNull(GraveInsuranceTickets.bindTicket(null,grave())); assertNull(GraveInsuranceTickets.bindTicket(new ItemStack(Material.AIR),grave()));
        assertNull(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),null)); assertFalse(GraveInsuranceTickets.bind(Map.of(0,new ItemStack(Material.STONE)),grave()).bound());
        Grave live=grave(); manager.register(live); ItemStack bound=GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),live);
        ItemStack ticket=new ItemStack(Material.PAPER,3); var meta=ticket.getItemMeta(); meta.setLore(List.of("Original lore")); ticket.setItemMeta(meta);
        Map<Integer,ItemStack> stash=new HashMap<>(); stash.put(1,bound); stash.put(5,ticket); Grave target=grave();
        GraveInsuranceTickets.Binding binding=GraveInsuranceTickets.bind(stash,target); assertTrue(binding.bound()); assertEquals(2,stash.get(5).getAmount());
        assertEquals(live.getId(),GraveInsuranceTickets.boundGrave(stash.get(1))); assertEquals(target.getId(),GraveInsuranceTickets.boundGrave(binding.split()));
        GraveInsuranceTickets.unbind(binding.split()); assertEquals(List.of("Original lore"),binding.split().getItemMeta().getLore());
        assertFalse(GraveInsuranceTickets.bind(Map.of(1,bound),target).bound());
    }

    @Test void bindingParsingRejectsInvalidTagsAndSupportsAbsentLocationOrLore() {
        assertNull(GraveInsuranceTickets.boundGrave(null)); assertNull(GraveInsuranceTickets.boundGrave(new ItemStack(Material.PAPER)));
        GraveInsuranceTickets.unbind(null); GraveInsuranceTickets.unbind(new ItemStack(Material.PAPER));
        for (String value : List.of(" ","not-a-uuid")) { ItemStack ticket=new ItemStack(Material.PAPER); var meta=ticket.getItemMeta(); meta.getPersistentDataContainer().set(GraveKeys.insuranceGraveId(),PersistentDataType.STRING,value); ticket.setItemMeta(meta); assertNull(GraveInsuranceTickets.boundGrave(ticket)); }
        Grave orphan=new Grave(null,player.getUniqueId(),null); ItemStack ticket=GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),orphan); assertFalse(ticket.getItemMeta().hasLore());
        orphan.setBlockLocation(new Location(null,1,2,3)); ticket=GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),orphan); assertTrue(ticket.getItemMeta().getLore().getFirst().contains("unknown"));
        settings.when(GraveLoader::getInsuranceBoundLore).thenReturn(" "); assertFalse(GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),grave()).getItemMeta().hasLore());
    }

    @Test void realBundleExtractionSearchesAllInventoryRegionsAndPreservesLiveBindings() {
        settings.when(GraveLoader::isInsuranceEnabled).thenReturn(false); assertNull(GraveInsuranceExtract.pull(null,null,null,null)); settings.when(GraveLoader::isInsuranceEnabled).thenReturn(true);
        assertNull(GraveInsuranceExtract.pull(null,null,null,null));
        ItemStack[] storage={new ItemStack(Material.PAPER,3)}; assertEquals(1,GraveInsuranceExtract.pull(storage,null,null,null).getAmount()); assertEquals(2,storage[0].getAmount());
        ItemStack[] armor={new ItemStack(Material.PAPER)}; assertNotNull(GraveInsuranceExtract.pull(null,armor,null,null)); assertNull(armor[0]);
        ItemStack[] offhand={new ItemStack(Material.PAPER)}; assertNotNull(GraveInsuranceExtract.pull(null,null,offhand,null)); assertNull(offhand[0]);
        ItemStack kept=bundle(new ItemStack(Material.PAPER)); assertNotNull(GraveInsuranceExtract.pull(null,null,null,List.of(kept))); assertFalse(((BundleMeta)kept.getItemMeta()).hasItems());
        Grave live=grave(); manager.register(live); ItemStack bound=GraveInsuranceTickets.bindTicket(new ItemStack(Material.PAPER),live);
        assertNull(GraveInsuranceExtract.pull(new ItemStack[]{null,new ItemStack(Material.AIR),bound,bundle()},null,null,List.of(new ItemStack(Material.STONE))));
        ItemStack nested=bundle(bundle(new ItemStack(Material.PAPER,2))); assertNotNull(GraveInsuranceExtract.pull(new ItemStack[]{nested},null,null,null));
        assertEquals(1,((BundleMeta)((BundleMeta)nested.getItemMeta()).getItems().getFirst().getItemMeta()).getItems().getFirst().getAmount());
    }

    @Test void graveSlotsHonorExclusionsAndEmptinessAcrossEveryStorageRegion() {
        Grave grave=grave(); grave.setItem(-1,new ItemStack(Material.DIAMOND)); grave.setItem(41,new ItemStack(Material.DIAMOND)); assertNull(grave.getItem(-1)); assertNull(grave.getItem(41));
        for(int slot:List.of(0,36,40)) { grave.setItem(slot,new ItemStack(Material.DIAMOND)); assertFalse(grave.isEmpty()); grave.setItem(slot,new ItemStack(Material.PAPER)); assertNull(grave.getItem(slot)); }
        grave.addExtra(new ItemStack(Material.DIAMOND)); assertFalse(grave.isEmpty()); grave.clearExtras(); assertTrue(grave.isEmpty());
        grave.addExtra(new ItemStack(Material.DIAMOND)); grave.getExtras().getFirst().setType(Material.AIR); assertTrue(grave.isEmpty()); grave.clearExtras();
        settings.when(() -> GraveLoader.isExcludedItem(any())).thenReturn(true); grave.addExtra(new ItemStack(Material.STONE)); assertTrue(grave.isEmpty());
        grave.setExperience(-1); assertEquals(0,grave.getExperience()); grave.setItem(40,new ItemStack(Material.SHIELD)); assertFalse(grave.isEmpty());
    }

    @Test void rightClickRecoversOwnedGravesAndPreventsNormalChestUse() {
        GraveInteractListener listener=new GraveInteractListener(); PlayerInteractEvent other=interact(Action.LEFT_CLICK_BLOCK,EquipmentSlot.HAND); listener.onInteract(other); verify(other,never()).setCancelled(true);
        listener.onInteract(interact(Action.RIGHT_CLICK_BLOCK,EquipmentSlot.HAND)); Grave grave=grave(); grave.setExperience(3); manager.register(grave);
        PlayerInteractEvent off=interact(Action.RIGHT_CLICK_BLOCK,EquipmentSlot.OFF_HAND); when(off.getClickedBlock()).thenReturn(grave.getBlockLocation().getBlock()); listener.onInteract(off); verify(off).setCancelled(true); assertSame(grave,manager.getById(grave.getId()));
        PlayerInteractEvent event=interact(Action.RIGHT_CLICK_BLOCK,EquipmentSlot.HAND); when(event.getClickedBlock()).thenReturn(grave.getBlockLocation().getBlock()); listener.onInteract(event);
        verify(event).setUseInteractedBlock(Event.Result.DENY); assertEquals(3,player.getTotalExperience()); assertNull(manager.getById(grave.getId()));
    }

    @Test void lockedForeignGraveShowsLockedMessageButAllowsStealingPluginsToHandleIt() {
        Grave grave=new Grave(null,UUID.randomUUID(),new Location(world,1,64,1)); manager.register(grave); GraveInteractListener listener=new GraveInteractListener();
        PlayerInteractEvent event=interact(Action.RIGHT_CLICK_BLOCK,EquipmentSlot.HAND); when(event.getClickedBlock()).thenReturn(grave.getBlockLocation().getBlock()); listener.onInteract(event); assertEquals("§clocked",player.nextMessage());
        try(var rules=mockStatic(GraveLootRules.class)) { rules.when(() -> GraveLootRules.canSteal(player,grave)).thenReturn(true); listener.onInteract(event); assertNull(player.nextMessage()); }
    }

    @Test void graveBlocksResistBreaksExplosionsPistonsAndHoppers() {
        Grave grave=grave(); Block protectedBlock=grave.getBlockLocation().getBlock(); protectedBlock.setType(Material.CHEST); manager.register(grave);
        Block ordinary=world.getBlockAt(8,64,8); GraveInteractListener listener=new GraveInteractListener();
        BlockBreakEvent breaking=new BlockBreakEvent(protectedBlock,player); listener.onBreak(breaking); assertTrue(breaking.isCancelled());
        BlockBreakEvent ordinaryBreak=new BlockBreakEvent(ordinary,player); listener.onBreak(ordinaryBreak); assertFalse(ordinaryBreak.isCancelled());
        List<Block> blocks=new ArrayList<>(List.of(ordinary,protectedBlock)); EntityExplodeEvent explosion=mock(EntityExplodeEvent.class); when(explosion.blockList()).thenReturn(blocks); listener.onEntityExplode(explosion); assertEquals(List.of(ordinary),blocks);
        blocks.add(protectedBlock); BlockExplodeEvent blockExplosion=mock(BlockExplodeEvent.class); when(blockExplosion.blockList()).thenReturn(blocks); listener.onBlockExplode(blockExplosion); assertEquals(List.of(ordinary),blocks);
        BlockPistonExtendEvent extend=mock(BlockPistonExtendEvent.class); when(extend.getBlocks()).thenReturn(List.of(ordinary,protectedBlock)); listener.onPistonExtend(extend); verify(extend).setCancelled(true);
        when(extend.getBlocks()).thenReturn(List.of(ordinary)); when(extend.getBlock()).thenReturn(protectedBlock.getRelative(BlockFace.WEST)); when(extend.getDirection()).thenReturn(BlockFace.EAST); listener.onPistonExtend(extend); verify(extend,times(2)).setCancelled(true);
        when(extend.getBlock()).thenReturn(ordinary); listener.onPistonExtend(extend); verify(extend,times(2)).setCancelled(true);
        BlockPistonRetractEvent retract=mock(BlockPistonRetractEvent.class); when(retract.getBlocks()).thenReturn(List.of(protectedBlock)); listener.onPistonRetract(retract); verify(retract).setCancelled(true);
        when(retract.getBlocks()).thenReturn(List.of(ordinary)); listener.onPistonRetract(retract); verify(retract,times(1)).setCancelled(true);
        Inventory graveInventory=((Chest)protectedBlock.getState()).getInventory(); InventoryOpenEvent open=mock(InventoryOpenEvent.class); when(open.getInventory()).thenReturn(graveInventory); listener.onInventoryOpen(open); verify(open).setCancelled(true);
        Inventory loose=mock(Inventory.class); when(loose.getLocation()).thenReturn(protectedBlock.getLocation()); InventoryMoveItemEvent hopper=mock(InventoryMoveItemEvent.class); when(hopper.getSource()).thenReturn(loose); listener.onHopperMove(hopper); verify(hopper).setCancelled(true);
        when(hopper.getSource()).thenReturn(null); when(hopper.getDestination()).thenReturn(graveInventory); listener.onHopperMove(hopper); verify(hopper,times(2)).setCancelled(true);
        when(hopper.getDestination()).thenReturn(mock(Inventory.class)); listener.onHopperMove(hopper); verify(hopper,times(2)).setCancelled(true);
    }
}

abstract class GraveRuntimeFixture {
    @TempDir Path directory;
    ServerMock server; World world; PlayerMock player; RPCharacters plugin; Logger logger; GraveManager manager;
    RuntimeTestState state; MockedStatic<GraveLoader> settings; MockedStatic<ProtocolLibBridge> protocol;
    MockedStatic<PvpStrikeService> strikes; MockedStatic<PvpStartSessions> starts; MockedStatic<PlayerManager> players;
    MockedStatic<DisplayIdentityService> identities; final List<InstanceState> instances=new ArrayList<>();
    @BeforeEach void setupRuntime() throws Exception {
        server=MockBukkit.mock(); state=new RuntimeTestState(RPCharacters.class); world=server.addSimpleWorld("graves"); player=server.addPlayer("Alex"); player.teleport(new Location(world,0,65,0));
        plugin=mock(RPCharacters.class); logger=mock(Logger.class); when(plugin.getName()).thenReturn("RPCharacters"); when(plugin.namespace()).thenReturn("rpcharacters");
        when(plugin.getDataFolder()).thenReturn(directory.toFile()); when(plugin.getLogger()).thenReturn(logger); when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true); RPCharacters.plugin=plugin;
        manager=GraveManager.get(); for(Object singleton:List.of(manager,LastSolidTracker.get(),GraveExpiryService.get(),GraveVisualManager.get())) instances.add(new InstanceState(singleton));
        settings=mockStatic(GraveLoader.class); settings.when(GraveLoader::isEnabled).thenReturn(true); settings.when(GraveLoader::isInsuranceEnabled).thenReturn(true); settings.when(GraveLoader::isInsuranceConsume).thenReturn(true);
        settings.when(GraveLoader::getMaterial).thenReturn(Material.CHEST); settings.when(GraveLoader::getSnapshotIntervalTicks).thenReturn(2L); settings.when(GraveLoader::getHologramRadius).thenReturn(32.0); settings.when(GraveLoader::getHologramOffsetY).thenReturn(1.2);
        settings.when(() -> GraveLoader.isInsuranceItem(any())).thenAnswer(call -> { ItemStack item=call.getArgument(0);return item!=null && item.getType()==Material.PAPER; });
        settings.when(() -> GraveLoader.keepOutOfGrave(anyInt(),any())).thenAnswer(call -> { ItemStack item=call.getArgument(1);return item!=null && item.getType()==Material.PAPER; });
        settings.when(GraveLoader::getMessagePlaced).thenReturn("§e{x},{y},{z} in {world}"); settings.when(GraveLoader::getMessageUnlockHint).thenReturn("&eunlock"); settings.when(GraveLoader::getMessageUnlockedByStrike).thenReturn("&cpublic");
        settings.when(GraveLoader::getMessageInsuranceBound).thenReturn("&ainsured"); settings.when(GraveLoader::getInsuranceBoundLore).thenReturn("&7{world} {x},{y},{z}");
        settings.when(GraveLoader::getMessageInsuranceNone).thenReturn("&enone"); settings.when(GraveLoader::getMessageInsuranceGone).thenReturn("&egone"); settings.when(GraveLoader::getMessageLocked).thenReturn("&clocked");
        settings.when(GraveLoader::getMessageEmpty).thenReturn("&7empty"); settings.when(GraveLoader::getMessageRecovered).thenReturn("&arecovered"); settings.when(GraveLoader::getMessageLooted).thenReturn("&alooted"); settings.when(GraveLoader::getMessageInventoryFull).thenReturn("&efull");
        protocol=mockStatic(ProtocolLibBridge.class); strikes=mockStatic(PvpStrikeService.class); strikes.when(() -> PvpStrikeService.graveContext(any(),anyBoolean())).thenReturn(new PvpStrikeService.GraveContext(false,false,null));
        starts=mockStatic(PvpStartSessions.class); players=mockStatic(PlayerManager.class); identities=mockStatic(DisplayIdentityService.class);
    }
    @AfterEach void restoreRuntime() throws Exception {
        LastSolidTracker.get().shutdown(); GraveExpiryService.get().shutdown(); GraveVisualManager.get().shutdown();
        try {
            for(MockedStatic<?> boundary:new MockedStatic<?>[]{identities,players,starts,strikes,protocol,settings}) if(boundary!=null) boundary.close();
            for(int i=instances.size()-1;i>=0;i--) instances.get(i).close(); if(state!=null) state.close();
        } finally { MockBukkit.unmock(); }
    }
    Grave grave() { return new Grave(null,player.getUniqueId(),new Location(world,1,64,1)); }
    static ItemStack bundle(ItemStack... contents) { ItemStack item=new ItemStack(Material.BUNDLE); BundleMeta meta=(BundleMeta)item.getItemMeta(); meta.setItems(List.of(contents)); item.setItemMeta(meta); return item; }
    static final class InstanceState implements AutoCloseable {
        final Object instance; final Map<Field,Object> original=new HashMap<>(), contents=new HashMap<>();
        @SuppressWarnings("rawtypes") InstanceState(Object instance) throws Exception { this.instance=instance; for(Field field:instance.getClass().getDeclaredFields()) { if(Modifier.isStatic(field.getModifiers())) continue; field.setAccessible(true); Object value=field.get(instance); original.put(field,value); if(value instanceof Map map) { contents.put(field,new HashMap<>(map)); map.clear(); } else if(!Modifier.isFinal(field.getModifiers()) && field.getType()==boolean.class) field.setBoolean(instance,false); else if(org.bukkit.scheduler.BukkitTask.class.isAssignableFrom(field.getType())) field.set(instance,null); } }
        @SuppressWarnings({"rawtypes","unchecked"}) public void close() throws Exception { for(var entry:original.entrySet()) { Field field=entry.getKey(); Object value=entry.getValue(); if(value instanceof Map map) { map.clear(); map.putAll((Map)contents.get(field)); } else if(!Modifier.isFinal(field.getModifiers())) field.set(instance,value); } }
    }
}
