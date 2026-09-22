package net.tfminecraft.rpcharacters.party;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.PartyLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PartyCommand {

	public static final String SUBCOMMAND = "party";

	private PartyCommand() {}

	public static boolean handle(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, PartyLoader.getPlayersOnly());
			return true;
		}
		if (args == null || args.length < 2) {
			RPTexts.send(player, PartyLoader.getUsage());
			return true;
		}

		String sub = args[1].toLowerCase(Locale.ROOT);
		return switch (sub) {
			case "create" -> handleCreate(player, args);
			case "invite" -> handleInvite(player, args);
			case "join" -> handleJoin(player);
			case "leave" -> handleLeave(player);
			case "kick" -> handleKick(player, args);
			case "info" -> handleInfo(player);
			default -> {
				RPTexts.send(player, PartyLoader.getUsage());
				yield true;
			}
		};
	}

	private static boolean handleCreate(Player player, String[] args) {
		if (args.length < 3) {
			RPTexts.send(player, PartyLoader.getUsage());
			return true;
		}
		String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
		deliver(player, PartyManager.get().create(player.getUniqueId(), name));
		return true;
	}

	private static boolean handleInvite(Player player, String[] args) {
		if (args.length != 3) {
			RPTexts.send(player, PartyLoader.getUsage());
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[2]);
		if (target == null) {
			RPTexts.send(player, PartyLoader.getTargetNotFound().replace("{player}", args[2]));
			return true;
		}
		PartyResult result = PartyManager.get().invite(player.getUniqueId(), target.getUniqueId());
		deliver(player, result);
		if (result.ok() && result.notifyIds().contains(target.getUniqueId())) {
			Party party = PartyManager.get().getParty(player.getUniqueId());
			if (party != null) {
				RPTexts.send(target, PartyLoader.getInvitedTarget()
						.replace("{leader}", player.getName())
						.replace("{name}", party.getName()));
			}
		}
		return true;
	}

	private static boolean handleJoin(Player player) {
		PartyResult result = PartyManager.get().join(player.getUniqueId());
		deliver(player, result);
		if (result.ok() && result.kind() == PartyResult.Kind.MEMBER_JOINED) {
			for (UUID notifyId : result.notifyIds()) {
				Player online = Bukkit.getPlayer(notifyId);
				if (online != null) {
					RPTexts.send(online, PartyLoader.getJoinedNotify().replace("{player}", player.getName()));
				}
			}
		}
		return true;
	}

	private static boolean handleLeave(Player player) {
		deliver(player, PartyManager.get().leave(player.getUniqueId()));
		return true;
	}

	private static boolean handleKick(Player player, String[] args) {
		if (args.length != 3) {
			RPTexts.send(player, PartyLoader.getUsage());
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[2]);
		if (target == null) {
			RPTexts.send(player, PartyLoader.getTargetNotFound().replace("{player}", args[2]));
			return true;
		}
		PartyResult result = PartyManager.get().kick(player.getUniqueId(), target.getUniqueId());
		deliver(player, result);
		if (result.ok() && result.kind() == PartyResult.Kind.MEMBER_KICKED) {
			RPTexts.send(target, PartyLoader.getKickedNotify());
		}
		return true;
	}

	private static boolean handleInfo(Player player) {
		Party party = PartyManager.get().getParty(player.getUniqueId());
		if (party == null) {
			RPTexts.send(player, PartyLoader.getNotInParty());
			return true;
		}
		for (String line : PartyManager.get().buildInfoLines(party)) {
			RPTexts.send(player, line);
		}
		return true;
	}

	private static void deliver(Player player, PartyResult result) {
		switch (result.kind()) {
			case MEMBER_LEFT -> {
				notifyMembers(result.notifyIds(), result.message());
				return;
			}
			case DISBANDED -> {
				if (result.message() != null && !result.message().isBlank()) {
					RPTexts.send(player, result.message());
				}
				for (UUID memberId : result.notifyIds()) {
					if (memberId.equals(player.getUniqueId())) {
						continue;
					}
					Player online = Bukkit.getPlayer(memberId);
					if (online != null) {
						RPTexts.send(online, PartyLoader.getDisbanded());
					}
				}
				return;
			}
			default -> {
			}
		}

		if (result.message() != null && !result.message().isBlank()) {
			RPTexts.send(player, result.message());
		}
	}

	private static void notifyMembers(List<UUID> memberIds, String message) {
		for (UUID memberId : memberIds) {
			Player online = Bukkit.getPlayer(memberId);
			if (online != null) {
				RPTexts.send(online, message);
			}
		}
	}

	public static List<String> tabComplete(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			return List.of();
		}
		if (args.length == 2) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			List<String> options = List.of("create", "invite", "join", "leave", "kick", "info");
			List<String> matches = new ArrayList<>();
			for (String option : options) {
				if (option.startsWith(prefix)) {
					matches.add(option);
				}
			}
			return matches;
		}
		if (args.length == 3) {
			String sub = args[1].toLowerCase(Locale.ROOT);
			if ("invite".equals(sub) || "kick".equals(sub)) {
				Party party = PartyManager.get().getParty(player.getUniqueId());
				if (party == null || !party.isLeader(player.getUniqueId())) {
					return List.of();
				}
				String prefix = args[2].toLowerCase(Locale.ROOT);
				List<String> matches = new ArrayList<>();
				for (Player online : Bukkit.getOnlinePlayers()) {
					if ("kick".equals(sub) && online.equals(player)) {
						continue;
					}
					if ("invite".equals(sub) && PartyManager.get().getParty(online.getUniqueId()) != null) {
						continue;
					}
					if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
						matches.add(online.getName());
					}
				}
				return matches;
			}
		}
		return List.of();
	}
}
