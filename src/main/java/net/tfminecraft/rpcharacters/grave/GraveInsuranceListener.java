package net.tfminecraft.rpcharacters.grave;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public final class GraveInsuranceListener implements Listener {

	// Right-click-air events arrive already cancelled (no block to use), so check the item use instead.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
	public void onPlayerInteract(PlayerInteractEvent event) {
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		if (event.useItemInHand() == Event.Result.DENY) {
			return;
		}
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		if (!GraveLoader.isEnabled() || !GraveLoader.isInsuranceEnabled()) {
			return;
		}

		Player player = event.getPlayer();
		ItemStack item = player.getInventory().getItemInMainHand();
		if (!GraveLoader.isInsuranceItem(item)) {
			return;
		}

		event.setCancelled(true);
		UUID graveId = GraveInsuranceTickets.boundGrave(item);
		if (graveId == null) {
			send(player, GraveLoader.getMessageInsuranceNone());
			return;
		}
		Grave grave = GraveManager.get().getById(graveId);
		if (grave == null) {
			unbind(player, item);
			send(player, GraveLoader.getMessageInsuranceGone());
			return;
		}

		// The grave may be anywhere, so hold its chunk loaded while it is emptied and removed.
		boolean[] recovered = new boolean[1];
		GraveChunkForceLoad.withForcedChunk(grave.getBlockLocation(),
				() -> recovered[0] = GraveRecover.recover(player, grave, player.getLocation()));
		if (!recovered[0] || !GraveLoader.isInsuranceConsume()) {
			if (GraveManager.get().getById(graveId) == null) {
				unbind(player, item);
			}
			return;
		}
		if (item.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			item.setAmount(item.getAmount() - 1);
		}
	}

	private static void unbind(Player player, ItemStack item) {
		GraveInsuranceTickets.unbind(item);
		player.getInventory().setItemInMainHand(item);
	}

	private static void send(Player player, String message) {
		if (player == null || message == null || message.isBlank()) {
			return;
		}
		player.sendMessage(StringFormatter.formatHex(message.replace('&', '\u00A7')));
	}
}
