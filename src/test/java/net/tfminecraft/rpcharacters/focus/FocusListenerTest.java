package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;

class FocusListenerTest extends FocusRuntimeFixture {
    FocusService service; FocusListener listener; ItemAPI items;

    @BeforeEach void setupListener() {
        service = new FocusService(plugin, store); listener = new FocusListener(service); service.activate(player, character);
        FocusConfig.restoreItems.add(new FocusConfig.RestoreItem("v.POTION", 50));
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS); boundary(TLibs.class).when(TLibs::getItemAPI).thenReturn(items);
        when(items.getChecker().checkItemWithPath(any(ItemStack.class), eq("v.POTION")))
            .thenAnswer(call -> call.<ItemStack>getArgument(0).getType() == Material.POTION);
    }

    ItemStack hold(Material material, int amount) { var item = new ItemStack(material, amount); player.getInventory().setItemInMainHand(item); return item; }
    PlayerInteractEvent click(Action action, EquipmentSlot hand, Block block) {
        var event = new PlayerInteractEvent(player, action, player.getInventory().getItemInMainHand(), block, BlockFace.UP, hand);
        event.setUseItemInHand(Event.Result.DEFAULT); event.setUseInteractedBlock(Event.Result.DEFAULT); return event;
    }
    PlayerInteractEvent drink() { var event = click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, null); listener.onRestoreItemUse(event); return event; }
    void assertHeld(Material material, int amount) { var item = player.getInventory().getItemInMainHand(); assertEquals(material, item.getType()); assertEquals(amount, item.getAmount()); }

    @Test void ordinaryActionsOffhandAndExplicitItemDenialsNeverRestore() {
        hold(Material.POTION, 2); assertTrue(service.trySpend(player, 100));
        for (Action action : List.of(Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK, Action.PHYSICAL)) {
            listener.onRestoreItemUse(click(action, EquipmentSlot.HAND, null));
        }
        listener.onRestoreItemUse(click(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, null));
        var denied = click(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, null); denied.setUseItemInHand(Event.Result.DENY);
        listener.onRestoreItemUse(denied); assertEquals(50, service.getPoints(player)); assertHeld(Material.POTION, 2);
        verify(items.getChecker(), never()).checkItemWithPath(any(), any()); assertNull(player.nextMessage());
    }

    @Test void clickingAnInteractableBlockUsesTheBlockUnlessSneakingOrBlockUseDenied() {
        hold(Material.POTION, 3); service.trySpend(player, 100);
        var chest = player.getWorld().getBlockAt(1, 64, 1); chest.setType(Material.CHEST);
        var open = click(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, chest); listener.onRestoreItemUse(open);
        assertEquals(Event.Result.DEFAULT, open.useItemInHand()); assertHeld(Material.POTION, 3); assertEquals(50, service.getPoints(player));
        player.setSneaking(true); var sneaking = click(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, chest); listener.onRestoreItemUse(sneaking);
        assertEquals(Event.Result.DENY, sneaking.useItemInHand()); assertEquals(100, service.getPoints(player)); assertHeld(Material.POTION, 2);
        player.setSneaking(false); var denied = click(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, chest); denied.setUseInteractedBlock(Event.Result.DENY);
        listener.onRestoreItemUse(denied); assertEquals(150, service.getPoints(player)); assertHeld(Material.POTION, 1);
    }

    @Test void airAndUnmatchedItemsAreNotConsumedAndOrdinaryBlocksAllowRestoring() {
        service.trySpend(player, 100); hold(Material.AIR, 1); drink(); assertEquals(50, service.getPoints(player));
        hold(Material.DIAMOND, 3); drink(); assertHeld(Material.DIAMOND, 3);
        var stone = player.getWorld().getBlockAt(1, 64, 1); stone.setType(Material.STONE); hold(Material.POTION, 1);
        listener.onRestoreItemUse(click(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, stone));
        assertEquals(100, service.getPoints(player)); assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
    }

    @Test void potionUsesActualHeldStackEvenIfTheEventCarriesAnotherItem() {
        service.trySpend(player, 100); hold(Material.POTION, 2);
        var event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, new ItemStack(Material.DIAMOND), null, BlockFace.UP, EquipmentSlot.HAND);
        event.setUseItemInHand(Event.Result.DEFAULT); listener.onRestoreItemUse(event);
        assertHeld(Material.POTION, 1); assertEquals(100, service.getPoints(player));
        assertEquals("§a+50 focus §7(100/150)", player.nextMessage());
        verify(player).playSound(any(Location.class), eq(Sound.ENTITY_GENERIC_DRINK), eq(.8f), eq(1f));
    }

    @Test void fullFocusAndInactiveCharactersKeepThePotionAndExplainWhy() {
        hold(Material.POTION, 2); var full = drink(); assertEquals(Event.Result.DENY, full.useItemInHand());
        assertEquals("§eYour focus is already full.", player.nextMessage()); assertHeld(Material.POTION, 2);
        accounts.clear(); drink(); assertEquals("§cYou need an active character to restore focus.", player.nextMessage());
        assertHeld(Material.POTION, 2); verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
    }

    @Test void potionRestoresOnlyMissingFocusAndConsumesExactlyOne() {
        service.trySpend(player, 10); hold(Material.POTION, 2); drink();
        assertEquals(150, service.getPoints(player)); assertHeld(Material.POTION, 1);
        assertEquals("§a+10 focus §7(150/150)", player.nextMessage());
    }

    @Test void activationWithMissingOwnerOrCharacterDoesNotReplaceTheBalance() {
        service.trySpend(player, 25);
        listener.onCharacterActivated(new CharacterActivatedEvent(null, player.getUniqueId(), character, null));
        listener.onCharacterActivated(new CharacterActivatedEvent(player, player.getUniqueId(), null, character));
        assertEquals(125, service.getPoints(player));
        listener.onQuit(new PlayerQuitEvent(player, (String) null));
        assertEquals(125, store.load(character.getId()).getPoints()); assertEquals(0, service.getPoints(player));
    }

    @Test void replacingTheHeldPotionDuringExternalMatchingCannotEraseTheNewStackOrGrantFocus() {
        service.trySpend(player, 100); hold(Material.POTION, 1);
        var checker = items.getChecker();
        doAnswer(call -> {
            hold(Material.DIAMOND, 3); return true;
        }).when(checker).checkItemWithPath(any(ItemStack.class), eq("v.POTION"));
        drink(); assertHeld(Material.DIAMOND, 3); assertEquals(50, service.getPoints(player));
        verify(player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
    }
}
