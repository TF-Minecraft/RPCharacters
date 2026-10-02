package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import net.tfminecraft.tlibs.armour.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class WardrobeGuiTest extends WardrobeFixture {
    MockedStatic<WardrobeService> services;final List<Runnable> refreshCallbacks=new ArrayList<>();final List<String> selected=new ArrayList<>();String selectionError;
    @BeforeEach void mockServiceBoundary(){services=mockStatic(WardrobeService.class,call->{switch(call.getMethod().getName()){case "refreshActiveAsync":if(call.getArguments().length==2&&call.getArgument(1)!=null)refreshCallbacks.add(call.getArgument(1));return null;case "setActiveAndApply":selected.add(call.getArgument(1));WardrobeService.ActiveCallback callback=call.getArgument(2);if(callback!=null)callback.done(selectionError);return null;case "applyFor":return null;default:return call.callRealMethod();}});}
    @AfterEach void closeServiceBoundary(){services.close();}

    @Test void openingBuildsOwnedButtonsAndRefreshOrCloseOnlyAffectsWardrobeViews() {
        WardrobeGui.open(null);WardrobeGui.openNow(null);WardrobeGui.refreshOpen(null);assertFalse(WardrobeGui.closeIfOpen(null));WardrobeGui.openNow(player);assertFalse(WardrobeGui.closeIfOpen(player));WardrobeGui.refreshOpen(player);cache(snapshot());WardrobeGui.open(player);var top=player.getOpenInventory().getTopInventory();assertEquals(27,top.getSize());var holder=(WardrobeGuiHolder)top.getHolder();assertSame(player,holder.getOwner());assertSame(top,holder.getInventory());assertEquals(Material.GREEN_CONCRETE,top.getItem(11).getType());assertEquals("base",WardrobeGui.readSlotId(top.getItem(11)));assertTrue(top.getItem(11).getItemMeta().getLore().stream().anyMatch(line->line.contains("Active")));assertTrue(top.getItem(13).getItemMeta().getLore().stream().anyMatch(line->line.contains("Click to equip")));assertEquals(Material.RED_CONCRETE,top.getItem(15).getType());WardrobeGui.refreshOpen(player);assertNotSame(top,player.getOpenInventory().getTopInventory());assertTrue(WardrobeGui.closeIfOpen(player));assertFalse(WardrobeGui.closeIfOpen(player));player.disconnect();WardrobeGui.open(player);WardrobeGui.openNow(player);WardrobeGui.refreshOpen(player);assertFalse(WardrobeGui.closeIfOpen(player));
    }

    @Test void loadingCallbacksOpenOnlyOnlinePlayersWithAReadyCache() {
        WardrobeGui.open(player);assertEquals(1,refreshCallbacks.size());assertTrue(message().contains("Loading wardrobe"));refreshCallbacks.removeFirst().run();assertTrue(message().contains("Could not load"));WardrobeGui.open(player);cache(snapshot());refreshCallbacks.removeFirst().run();assertInstanceOf(WardrobeGuiHolder.class,player.getOpenInventory().getTopInventory().getHolder());WardrobeCache.clear(player);WardrobeGui.open(player);player.disconnect();assertDoesNotThrow(()->refreshCallbacks.removeFirst().run());
    }

    @Test void emptySlotsAndRankRequirementsComeFromVisibleLowestTierGroups() {
        var empty=new WardrobeSnapshot("alpha",null,2,new HashMap<>());cache(empty);WardrobeGui.openNow(player);var top=player.getOpenInventory().getTopInventory();assertEquals(Material.YELLOW_CONCRETE,top.getItem(11).getType());assertEquals(Material.YELLOW_CONCRETE,top.getItem(13).getType());assertEquals(Material.RED_CONCRETE,top.getItem(15).getType());assertTrue(top.getItem(15).getItemMeta().getLore().stream().anyMatch(line->line.contains("higher rank")));Cache.permissionGroups.add(group("hidden",0,false,"Hidden",3));Cache.permissionGroups.add(group("elite",3,true,"&aElite",3));Cache.permissionGroups.add(group("member",1,true,"Member",2));assertEquals("elite",WardrobeGui.getMinimumVisibleUnlockGroup(3).orElseThrow().getId());assertTrue(WardrobeGui.getUnlockRequirementLore(3).contains("Elite+"));assertTrue(WardrobeGui.unlockChatMessage(3).contains("§aElite+"));Cache.permissionGroups.clear();Cache.permissionGroups.add(group("fallback",1,true," ",3));assertTrue(WardrobeGui.getUnlockRequirementLore(3).contains("fallback+"));assertTrue(WardrobeGui.unlockChatMessage(3).contains("fallback+"));assertFalse(WardrobeGui.isUnlocked(empty,"unknown",null));assertEquals(1,WardrobeGui.minSlotsFor("base"));assertEquals(2,WardrobeGui.minSlotsFor("extra_1"));assertEquals(3,WardrobeGui.minSlotsFor("extra_2"));assertNull(WardrobeGui.formatGroupDisplayName(null));assertEquals(" ",WardrobeGui.formatGroupDisplayName(" "));
    }

    @Test void buttonDataIgnoresMissingMetadataAndNormalizesTags() {
        assertNull(WardrobeGui.readSlotId(null));assertNull(WardrobeGui.readSlotId(new ItemStack(Material.STONE)));var item=button(" ");assertNull(WardrobeGui.readSlotId(item));assertEquals("extra_1",WardrobeGui.readSlotId(button(" EXTRA_1 ")));assertFalse(WardrobeGui.isUnlocked(snapshot(),"base",slot("base",false,true,"Base")));
    }

    @Test void listenerIgnoresUnownedOutsideAndUnrelatedClicksButCancelsWardrobeTransfers() {
        var listener=new WardrobeListener();var unrelated=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,0,ClickType.LEFT,InventoryAction.PICKUP_ALL);listener.onWardrobeGuiClick(unrelated);assertFalse(unrelated.isCancelled());cache(snapshot());WardrobeGui.openNow(player);var outside=click(-999);listener.onWardrobeGuiClick(outside);assertTrue(outside.isCancelled());var bottom=click(27);listener.onWardrobeGuiClick(bottom);assertTrue(bottom.isCancelled());listener.onWardrobeGuiClick(click(0));var foreign=server.addPlayer();foreign.openInventory(player.getOpenInventory().getTopInventory());var foreignClick=new InventoryClickEvent(foreign.getOpenInventory(),InventoryType.SlotType.CONTAINER,11,ClickType.LEFT,InventoryAction.PICKUP_ALL);listener.onWardrobeGuiClick(foreignClick);assertTrue(foreignClick.isCancelled());assertTrue(selected.isEmpty());
    }

    @Test void nonPlayerHumanInventoryViewIsCancelledWithoutChoosingASkin() {
        cache(snapshot());WardrobeGui.openNow(player);var human=mock(HumanEntity.class);when(human.getInventory()).thenReturn(mock(PlayerInventory.class));var view=new org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock(human,player.getOpenInventory().getTopInventory());var event=new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,11,ClickType.LEFT,InventoryAction.PICKUP_ALL);new WardrobeListener().onWardrobeGuiClick(event);assertTrue(event.isCancelled());assertTrue(selected.isEmpty());
    }

    @Test void commandCannotResolveMissingOrUnusableNamedSlots() {
        cache(new WardrobeSnapshot("alpha",null,3,Map.of()));WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","missing name"});assertTrue(message().contains("Usage:"));assertTrue(selected.isEmpty());
    }

    @Test void listenerReportsLoadingLockedEmptyAndAlreadyActiveSlots() {
        cache(snapshot());WardrobeGui.openNow(player);var listener=new WardrobeListener();WardrobeCache.clear(player);listener.onWardrobeGuiClick(click(11));assertTrue(message().contains("still loading"));cache(snapshot());listener.onWardrobeGuiClick(click(15));assertTrue(message().contains("higher rank"));listener.onWardrobeGuiClick(click(11));assertTrue(message().contains("Already using"));var empty=new WardrobeSnapshot("alpha",null,3,new HashMap<>());cache(empty);WardrobeGui.openNow(player);listener.onWardrobeGuiClick(click(13));assertTrue(message().contains("That slot is empty"));assertTrue(selected.isEmpty());
    }

    @Test void successfulClickClosesThenReopensOnMainThreadAndFailureStaysClosed() {
        cache(snapshot());WardrobeGui.openNow(player);var listener=new WardrobeListener();listener.onWardrobeGuiClick(click(13));assertEquals(List.of("extra_1"),selected);assertTrue(message().contains("Equipped"));assertFalse(player.getOpenInventory().getTopInventory().getHolder() instanceof WardrobeGuiHolder);main.removeFirst().run();assertInstanceOf(WardrobeGuiHolder.class,player.getOpenInventory().getTopInventory().getHolder());selectionError="website unavailable";listener.onWardrobeGuiClick(click(13));assertTrue(message().contains("website unavailable"));assertTrue(main.isEmpty());selectionError=null;WardrobeGui.openNow(player);listener.onWardrobeGuiClick(click(13));player.disconnect();main.removeFirst().run();assertTrue(main.isEmpty());
    }

    @Test void queuedWardrobeReopenMustNotReplaceANewerInventory() {
        cache(snapshot());WardrobeGui.openNow(player);new WardrobeListener().onWardrobeGuiClick(click(13));var newer=server.createInventory(null,9,"New menu");player.openInventory(newer);main.removeFirst().run();assertSame(newer,player.getOpenInventory().getTopInventory(),"A completed wardrobe callback must not overwrite a later menu choice");
    }

    @Test void completedLoadingMustNotReplaceANewerInventory() {
        WardrobeGui.open(player);var newer=server.createInventory(null,9,"New menu");player.openInventory(newer);cache(snapshot());refreshCallbacks.removeFirst().run();assertSame(newer,player.getOpenInventory().getTopInventory(),"A delayed wardrobe load must not overwrite a later menu choice");
    }

    @Test void helmetChangesApplyAfterInventoryUpdatesAndQuitClearsAllCachedSkins() {
        var listener=new WardrobeListener();listener.onArmorEquip(new ArmorEquipEvent(player,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.BOOTS,null,null));listener.onArmorEquip(new ArmorEquipEvent(null,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.HELMET,null,null));assertTrue(main.isEmpty());listener.onArmorEquip(new ArmorEquipEvent(player,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.HELMET,null,null));services.verify(()->WardrobeService.applyFor(player),never());main.removeFirst().run();services.verify(()->WardrobeService.applyFor(player));listener.onArmorEquip(new ArmorEquipEvent(player,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.HELMET,null,null));cache(snapshot());WardrobeCache.captureAccountSkinIfNeeded(player);player.disconnect();main.removeFirst().run();listener.onArmorEquip(new ArmorEquipEvent(player,ArmorEquipEvent.EquipMethod.SHIFT_CLICK,ArmorType.HELMET,null,null));listener.onQuit(new PlayerQuitEvent(player,"quit"));assertNull(WardrobeCache.get(player));assertNull(WardrobeCache.getAccountSkin(player));
    }

    @Test void commandGuardsOpenLoadingAndAlreadyActiveCases() {
        var console=mock(CommandSender.class);assertTrue(WardrobeCommand.handle(console,"rp",new String[]{"wardrobe"}));verify(console).sendMessage("Players only.");active.set(null);assertTrue(WardrobeCommand.handle(player,"rp",new String[]{"wardrobe"}));assertTrue(message().contains("active character"));active.set(character);WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","extra_1"});assertTrue(message().contains("Loading wardrobe"));cache(snapshot());WardrobeCommand.handle(player,"rp",new String[]{"wardrobe"});assertInstanceOf(WardrobeGuiHolder.class,player.getOpenInventory().getTopInventory().getHolder());WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","base"});assertTrue(message().contains("Already using"));WardrobeCommand.handle(player,"custom",new String[]{"wardrobe","unknown"});assertTrue(message().contains("/custom wardrobe"));
    }

    @Test void commandResolvesNamesAndAliasesButRejectsAmbiguousAndEmptyNames() {
        var slots=new LinkedHashMap<String,WardrobeSlotData>();slots.put("base",slot("base",true,true,"One"));slots.put("extra_1",slot("extra_1",true,true,"Summer Coat"));slots.put("extra_2",slot("extra_2",false,false,null));var snapshot=new WardrobeSnapshot("alpha",null,3,slots);cache(snapshot);WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","Summer_Coat"});assertEquals(List.of("extra_1"),selected);assertTrue(message().contains("Equipped"));selectionError="no";WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","skin2"});assertTrue(message().contains("no"));slots.put("base",slot("base",true,true,"Summer Coat"));WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","Summer_Coat"});assertTrue(message().contains("Usage:"));WardrobeCommand.handle(player,"rp",new String[]{"wardrobe"," "});assertTrue(message().contains("Usage:"));WardrobeCommand.handle(player,"rp",new String[]{"wardrobe",null});assertTrue(message().contains("Usage:"));
    }

    @Test void tabCompletionUsesOnlyUsableSlotsAndDeduplicatesNames() {
        assertTrue(WardrobeCommand.tabComplete(player,new String[]{"wardrobe"}).isEmpty());assertEquals(List.of("base","extra_1","extra_2"),WardrobeCommand.tabComplete(player,new String[]{"wardrobe",""}));var slots=new LinkedHashMap<String,WardrobeSlotData>();slots.put("base",slot("base",true,true,"Base"));slots.put("extra_1",slot("extra_1",true,true,"Summer Coat"));slots.put("extra_2",slot("extra_2",true,true,"Summer Coat"));cache(new WardrobeSnapshot("alpha",null,3,slots));assertEquals(List.of("base","extra_1","Summer_Coat","extra_2"),WardrobeCommand.tabComplete(player,new String[]{"wardrobe",""}));assertEquals(List.of("Summer_Coat"),WardrobeCommand.tabComplete(player,new String[]{"wardrobe","SUM"}));slots.put("extra_2",slot("extra_2",false,false,null));assertFalse(WardrobeCommand.tabComplete(player,new String[]{"wardrobe",""}).contains("extra_2"));
    }

    @Test void pendingLoadingDoesNotOpenAfterTheActiveCharacterChanges() {
        WardrobeGui.open(player);var previous=player.getOpenInventory().getTopInventory();active.set(null);cache(snapshot());refreshCallbacks.removeFirst().run();assertSame(previous,player.getOpenInventory().getTopInventory());
    }
    @Test void aNamedSelectionSkipsUnusableEarlierSlots() {
        var slots=new LinkedHashMap<String,WardrobeSlotData>();slots.put("base",new WardrobeSlotData("base",true,true,true,false,null,null,null,"signature"));slots.put("extra_1",slot("extra_1",true,true,"Summer Coat"));cache(new WardrobeSnapshot("alpha",null,3,slots));WardrobeCommand.handle(player,"rp",new String[]{"wardrobe","Summer_Coat"});assertEquals(List.of("extra_1"),selected);
    }

    InventoryClickEvent click(int rawSlot){return new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,rawSlot,ClickType.LEFT,InventoryAction.PICKUP_ALL);}
    ItemStack button(String id){var item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();meta.getPersistentDataContainer().set(WardrobeGui.slotKey(),PersistentDataType.STRING,id);item.setItemMeta(meta);return item;}
    String message(){String next=player.nextMessage();assertNotNull(next,"Expected user feedback");return ChatColor.stripColor(next);}
    static PermissionGroupDefinition group(String id,int tier,boolean visible,String name,int slots){return new PermissionGroupDefinition(id,"rank."+id,name,tier,visible,Map.of(PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS,slots));}
}
