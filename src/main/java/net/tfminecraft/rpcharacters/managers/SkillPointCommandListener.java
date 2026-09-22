package net.tfminecraft.rpcharacters.managers;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import net.tfminecraft.rpcharacters.mmocore.ClassService;

public class SkillPointCommandListener implements Listener {

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onServerCommand(ServerCommandEvent event) {
		scheduleIfSkillPointCommand(event.getCommand(), event.getSender());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
		scheduleIfSkillPointCommand(event.getMessage(), event.getPlayer());
	}

	private static void scheduleIfSkillPointCommand(String raw, CommandSender sender) {
		Player target = resolveSkillPointCommandTarget(raw);
		if (target != null) {
			ClassService.scheduleSyncSkillPoints(target, sender);
		}
	}

	static Player resolveSkillPointCommandTarget(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String command = raw.trim();
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		String[] parts = command.split("\\s+");
		if (parts.length < 6) {
			return null;
		}
		if (!parts[0].equalsIgnoreCase("mmocore")) {
			return null;
		}
		if (!parts[1].equalsIgnoreCase("admin")) {
			return null;
		}
		if (!parts[2].equalsIgnoreCase("skill-points")) {
			return null;
		}
		if (!parts[3].equalsIgnoreCase("give") && !parts[3].equalsIgnoreCase("set")) {
			return null;
		}
		return Bukkit.getPlayer(parts[4]);
	}
}
