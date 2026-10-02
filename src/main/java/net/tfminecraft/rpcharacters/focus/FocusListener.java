package net.tfminecraft.rpcharacters.focus;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.tlibs.TLibs;

public final class FocusListener implements Listener {

    private final FocusService service;

    public FocusListener(FocusService service) {
        this.service = service;
    }

    @EventHandler
    public void onCharacterActivated(CharacterActivatedEvent event) {
        Player owner = event.getOwner();
        if (owner == null || event.getCharacter() == null) {
            return;
        }
        if (event.getPrevious() != null) {
            service.savePrevious(owner, event.getPrevious());
        }
        service.activate(owner, event.getCharacter());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.deactivate(event.getPlayer());
    }

    // Not ignoreCancelled: Bukkit fires air clicks already cancelled. useItemInHand still respects protections.
    @EventHandler(priority = EventPriority.HIGH)
    public void onRestoreItemUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        // Like vanilla, clicking a chest, door or lever uses the block, not the held potion.
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking()
                && event.useInteractedBlock() != Event.Result.DENY
                && event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable()) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemStack expected = item.clone();
        FocusConfig.RestoreItem restore = restoreItemFor(item);
        if (restore == null || !player.getInventory().getItemInMainHand().equals(expected)) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);

        int added = service.restoreBy(player, restore.points);
        if (added < 0) {
            RPTexts.send(player, RPTexts.ERROR + "You need an active character to restore focus.");
            return;
        }
        if (added == 0) {
            RPTexts.send(player, RPTexts.WARN + "Your focus is already full.");
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
        RPTexts.send(player, RPTexts.SUCCESS + "+" + added + " focus " + RPTexts.MUTED + "("
                + service.getPoints(player) + "/" + service.getMax() + ")");
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.0f);
    }

    private static FocusConfig.RestoreItem restoreItemFor(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        for (FocusConfig.RestoreItem restore : FocusConfig.restoreItems) {
            if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, restore.item)) {
                return restore;
            }
        }
        return null;
    }
}
