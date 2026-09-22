package net.tfminecraft.rpcharacters.injuries;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class RpInjureListener implements Listener {

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof RpInjureGui.Holder holder)) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!player.equals(holder.getOwner())) {
			return;
		}
		if (event.getClickedInventory() == null
				|| !event.getClickedInventory().equals(event.getView().getTopInventory())) {
			return;
		}
		ItemStack item = event.getCurrentItem();
		if (holder.getKind() == RpInjureGui.Kind.PICKER) {
			String traitId = RpInjureGui.readTraitId(item);
			if (traitId == null || traitId.isBlank()) {
				return;
			}
			RpInjureService.chooseInjury(player, traitId);
			return;
		}
		if (holder.getKind() == RpInjureGui.Kind.ACCEPT) {
			String action = RpInjureGui.readAction(item);
			if ("accept".equals(action)) {
				RpInjureService.accept(player);
			} else if ("decline".equals(action)) {
				RpInjureService.decline(player);
			}
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getInventory().getHolder() instanceof RpInjureGui.Holder)) {
			return;
		}
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}
		RpInjureService.cancelFromClose(player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		RpInjureService.cancelInvolving(event.getPlayer(), null);
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		RpInjureService.cancelInvolving(event.getEntity(), null);
	}
}
