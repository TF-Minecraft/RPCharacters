package net.tfminecraft.rpcharacters.classpick;

import java.util.Locale;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Runs the class picker's clicks, and opens it for the configured commands (MMOCore's
 * {@code /class} and {@code /c} by default) so MMOCore's class points never come into play.
 */
public final class ClassPickListener implements Listener {

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof ClassPickGui.Holder holder)) {
			return;
		}
		event.setCancelled(true);
		if (event.getClickedInventory() == event.getInventory() && event.getWhoClicked() instanceof Player player) {
			ClassPickGui.click(player, holder, event.getSlot());
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getInventory().getHolder() instanceof ClassPickGui.Holder) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent event) {
		if (!ClassPickService.isEnabled()) {
			return;
		}
		String label = event.getMessage().substring(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (label.startsWith("mmocore:")) {
			label = label.substring("mmocore:".length());
		}
		if (ClassPickService.settings().commands().contains(label)) {
			event.setCancelled(true);
			ClassPickGui.open(event.getPlayer());
		}
	}
}
