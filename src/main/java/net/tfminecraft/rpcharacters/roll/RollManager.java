package net.tfminecraft.rpcharacters.roll;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.RollLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class RollManager implements CommandExecutor, TabCompleter {

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, RPTexts.ERROR + "Only players can roll dice.");
			return true;
		}
		if (!checkPermission(player)) {
			return true;
		}
		if (args.length == 0) {
			handleDefaultRoll(player);
			return true;
		}
		handleRollArgs(player, args);
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length != 1) {
			return List.of();
		}
		String prefix = args[0].toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		for (String name : attributeNames()) {
			if (name.startsWith(prefix)) {
				out.add(name);
			}
		}
		return out;
	}

	public void handleDefaultRoll(Player player) {
		int min;
		int max;
		if (player.hasPermission(Cache.rollAltPermission)) {
			min = Cache.rollAltMin;
			max = Cache.rollAltMax;
		} else {
			min = Cache.rollDefaultMin;
			max = Cache.rollDefaultMax;
		}
		executeRoll(player, min, max, 0);
	}

	private void handleRollArgs(Player player, String[] args) {
		String firstArg = args[0];
		if (isAttribute(firstArg)) {
			executeRoll(player, Cache.rollD20Min, Cache.rollD20Max, AttributeRollResolver.resolveModifier(player, firstArg));
			return;
		}

		Integer max = parsePositiveInt(firstArg);
		if (max == null) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /roll [max|attribute] [+/-modifier]");
			RPTexts.send(player, RPTexts.ERROR + "Invalid roll maximum: " + RPTexts.WARN + firstArg);
			return;
		}

		int modifier = 0;
		if (args.length >= 2) {
			Integer parsedModifier = parseSignedInt(args[1]);
			if (parsedModifier == null) {
				RPTexts.send(player, RPTexts.ERROR + "Invalid modifier: " + RPTexts.WARN + args[1]);
				return;
			}
			modifier = parsedModifier;
		}

		executeRoll(player, 1, max, modifier);
	}

	private boolean checkPermission(Player player) {
		if (player.hasPermission(Cache.rollPermission)) {
			return true;
		}
		RPTexts.send(player, RPTexts.ERROR + "You do not have permission to roll dice.");
		return false;
	}

	public static void executeRoll(Player player, int min, int max, int modifier) {
		if (player == null) {
			return;
		}
		int low = Math.min(min, max);
		int high = Math.max(min, max);
		if (high < 1) {
			RPTexts.send(player, RPTexts.ERROR + "Invalid roll range.");
			return;
		}
		if (low < 1) {
			low = 1;
		}

		int roll = ThreadLocalRandom.current().nextInt(low, high + 1);
		String message = RollFormatter.format(player, roll, high, modifier);
		if (message.isEmpty()) {
			return;
		}

		broadcast(player, message);
	}

	private static void broadcast(Player origin, String message) {
		RPTexts.send(origin, message);

		int range = Cache.rollBroadcastRange;
		if (range <= 0) {
			for (Player target : origin.getServer().getOnlinePlayers()) {
				if (!target.getUniqueId().equals(origin.getUniqueId())) {
					RPTexts.send(target, message);
				}
			}
			return;
		}

		double rangeSq = (double) range * range;
		for (Player target : origin.getWorld().getPlayers()) {
			if (target.getUniqueId().equals(origin.getUniqueId())) {
				continue;
			}
			if (target.getLocation().distanceSquared(origin.getLocation()) <= rangeSq) {
				RPTexts.send(target, message);
			}
		}
	}

	private static Set<String> attributeNames() {
		Set<String> names = new LinkedHashSet<>();
		if (Cache.attributes != null) {
			for (String attribute : Cache.attributes) {
				if (attribute != null && !attribute.isBlank()) {
					names.add(attribute.toLowerCase(Locale.ROOT));
				}
			}
		}
		names.addAll(RollLoader.getAttributeIds());
		return names;
	}

	private static boolean isAttribute(String value) {
		if (value == null) {
			return false;
		}
		String normalized = value.toLowerCase(Locale.ROOT);
		if (RollLoader.isKnownAttribute(normalized)) {
			return true;
		}
		if (Cache.attributes == null) {
			return false;
		}
		for (String attribute : Cache.attributes) {
			if (attribute != null && attribute.equalsIgnoreCase(normalized)) {
				return true;
			}
		}
		return false;
	}

	private static Integer parsePositiveInt(String value) {
		try {
			int parsed = Integer.parseInt(value);
			return parsed >= 1 ? parsed : null;
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Integer parseSignedInt(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		try {
			return Integer.parseInt(trimmed);
		} catch (NumberFormatException ex) {
			if (trimmed.startsWith("+")) {
				try {
					return Integer.parseInt(trimmed.substring(1));
				} catch (NumberFormatException ignored) {
					return null;
				}
			}
			return null;
		}
	}
}
