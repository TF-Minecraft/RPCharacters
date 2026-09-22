package net.tfminecraft.rpcharacters.grave;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public final class GraveInsuranceListener implements Listener {

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
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

		Grave grave = GraveManager.get().findNewestByOwner(player.getUniqueId());
		if (grave == null) {
			send(player, GraveLoader.getMessageInsuranceNone());
			return;
		}

		event.setCancelled(true);
		boolean recovered = GraveRecover.recover(player, grave, player.getLocation());
		if (!recovered || !GraveLoader.isInsuranceConsume()) {
			return;
		}
		if (item.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			item.setAmount(item.getAmount() - 1);
		}
	}

	private static void send(Player player, String message) {
		if (player == null || message == null || message.isBlank()) {
			return;
		}
		player.sendMessage(StringFormatter.formatHex(message.replace('&', '\u00A7')));
	}
}
