package net.tfminecraft.rpcharacters.classpick;

import java.util.Locale;

import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
public final class ClassPickListener implements Listener, CommandExecutor {

	@Override
	public boolean onCommand(CommandSender sender, Command command,
			String label, String[] args) {
		if (sender instanceof Player player) {
			if (command.getName().equalsIgnoreCase("subclass")) {
				ClassPickGui.openSubclasses(player);
			} else {
				ClassPickGui.open(player);
			}
		}
		return true;
	}

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
		if (label.startsWith("mmocore:") || label.startsWith("rpcharacters:")) {
			label = label.substring(label.indexOf(':') + 1);
		}
		if (label.equals("subclass") || ClassPickService.settings().commands().contains(label)) {
			event.setCancelled(true);
			if (label.equals("subclass")) {
				ClassPickGui.openSubclasses(event.getPlayer());
			} else {
				ClassPickGui.open(event.getPlayer());
			}
		}
	}
}
