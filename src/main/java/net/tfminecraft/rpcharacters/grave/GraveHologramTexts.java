package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;

public final class GraveHologramTexts {

	private GraveHologramTexts() {}

	public static List<String> baseLines(Grave grave) {
		List<String> lines = new ArrayList<>();
		if (grave == null) {
			return lines;
		}
		Player owner = Bukkit.getPlayer(grave.getOwner());
		String name = owner != null ? characterOrName(owner) : offlineName(grave.getOwner());
		lines.add(name);
		if (GraveLoader.isHologramShowKiller()) {
			String killerLine = killerLine(grave);
			if (killerLine != null && !killerLine.isBlank()) {
				lines.add(killerLine);
			}
		}
		String timerLine = GraveTimerFormat.timerLine(grave);
		if (timerLine != null && !timerLine.isBlank()) {
			lines.add(timerLine);
		}
		return lines;
	}

	private static String killerLine(Grave grave) {
		String stored = grave.getKillerDisplay();
		if (stored != null && !stored.isBlank()) {
			return stored;
		}
		if (grave.getKiller() != null) {
			return "Killed by " + offlineName(grave.getKiller());
		}
		return null;
	}

	public static List<String> linesForViewer(Player viewer, Grave grave) {
		List<String> lines = new ArrayList<>(baseLines(grave));
		if (showsLootHint(viewer, grave)) {
			lines.add(formatMessage(GraveLoader.getMessageRobHint()));
		}
		return lines;
	}

	private static boolean showsLootHint(Player viewer, Grave grave) {
		if (viewer == null || grave == null || grave.isOwner(viewer.getUniqueId())) {
			return false;
		}
		return GraveLootRules.canSteal(viewer, grave) || !grave.isLocked();
	}

	private static String formatMessage(String message) {
		if (message == null || message.isBlank()) {
			return "";
		}
		return StringFormatter.formatHex(message.replace('&', '\u00A7'));
	}

	private static String characterOrName(Player player) {
		if (player == null) {
			return "Unknown";
		}
		String character = DisplayIdentityService.resolveCharacterName(player);
		if (character != null && !character.isBlank()) {
			return character;
		}
		return player.getName() != null ? player.getName() : "Unknown";
	}

	private static String offlineName(java.util.UUID id) {
		if (id == null) {
			return "Unknown";
		}
		String name = Bukkit.getOfflinePlayer(id).getName();
		return name != null && !name.isBlank() ? name : "Unknown";
	}
}
