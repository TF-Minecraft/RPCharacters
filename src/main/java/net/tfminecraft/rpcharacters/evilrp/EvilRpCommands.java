package net.tfminecraft.rpcharacters.evilrp;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** {@code /rpcharacter strikes}, {@code /rpcharacter spare} and {@code /rpcharacter admin strikes}. */
public final class EvilRpCommands {

	public static final String ADMIN_USAGE = "/rpcharacter admin strikes <view|add|remove|set|endsession> <player> ...";

	private EvilRpCommands() {
	}

	public static boolean handleOwnStrikes(Player player) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character.");
			return true;
		}
		sendSummary(player, pd.getActiveCharacter(), "You have");
		return true;
	}

	public static boolean handleSpare(Player player, String[] args) {
		if (args.length != 2) {
			RPTexts.send(player, RPTexts.ERROR + "Use the [Spare] button in chat after knocking someone out.");
			return true;
		}
		UUID victimId;
		try {
			victimId = UUID.fromString(args[1]);
		} catch (IllegalArgumentException ex) {
			Player victim = Bukkit.getPlayerExact(args[1]);
			victimId = victim != null ? victim.getUniqueId() : null;
		}
		EvilRpService.spare(player, victimId);
		return true;
	}

	/** {@code args[actionIndex]} is the action, followed by the player. */
	public static boolean handleAdmin(CommandSender sender, String[] args, int actionIndex) {
		if (args.length < actionIndex + 2) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: " + ADMIN_USAGE);
			return true;
		}
		String action = args[actionIndex].toLowerCase();
		int playerIndex = actionIndex + 1;
		Player player = Bukkit.getPlayerExact(args[playerIndex]);
		if (player == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No player found.");
			return true;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Player data not loaded for " + RPTexts.WARN + player.getName()
					+ RPTexts.ERROR + ".");
			return true;
		}

		switch (action) {
			case "view" -> {
				RPCharacter character = resolveCharacter(sender, pd, player, args, playerIndex + 1, args.length);
				if (character != null) {
					sendSummary(sender, character, character.getName() + " (" + player.getName() + ") has");
				}
			}
			case "add" -> {
				int end = args.length;
				boolean quiet = end > playerIndex + 1 && args[end - 1].equalsIgnoreCase("quiet");
				if (quiet) {
					end--;
				}
				RPCharacter character = resolveCharacter(sender, pd, player, args, playerIndex + 1, end);
				if (character == null || !requireAlive(sender, character)) {
					return true;
				}
				if (quiet) {
					character.setEvilRpStrikes(character.getEvilRpStrikes() + 1);
					RPCharacters.getPlayerManager().savePlayer(player);
					RPTexts.send(sender, RPTexts.SUCCESS + "Added a strike to " + RPTexts.WARN + character.getName()
							+ RPTexts.SUCCESS + " without any injury or death. They now have "
							+ RPTexts.WARN + character.getEvilRpStrikes() + RPTexts.SUCCESS + ".");
					return true;
				}
				StrikeOutcome outcome = EvilRpService.applyStrike(player, character);
				RPTexts.send(sender, RPTexts.SUCCESS + "Added strike " + RPTexts.WARN + character.getEvilRpStrikes()
						+ RPTexts.SUCCESS + " to " + RPTexts.WARN + character.getName() + RPTexts.SUCCESS + ": "
						+ describe(outcome) + ".");
			}
			case "remove" -> {
				RPCharacter character = resolveCharacter(sender, pd, player, args, playerIndex + 1, args.length);
				if (character == null) {
					return true;
				}
				if (character.getEvilRpStrikes() <= 0) {
					RPTexts.send(sender, RPTexts.ERROR + character.getName() + " has no strikes.");
					return true;
				}
				setStrikes(sender, player, character, character.getEvilRpStrikes() - 1);
			}
			case "set" -> {
				if (args.length < playerIndex + 2) {
					RPTexts.send(sender, RPTexts.ERROR + "Usage: /rpcharacter admin strikes set <player> <count> [character]");
					return true;
				}
				int count;
				try {
					count = Integer.parseInt(args[playerIndex + 1]);
				} catch (NumberFormatException ex) {
					RPTexts.send(sender, RPTexts.ERROR + "Count must be a number.");
					return true;
				}
				if (count < 0 || count >= StrikeOutcome.MAX_STRIKES) {
					RPTexts.send(sender, RPTexts.ERROR + "Count must be between 0 and " + (StrikeOutcome.MAX_STRIKES - 1)
							+ ". Use " + RPTexts.COMMAND + "add" + RPTexts.ERROR + " for a third strike.");
					return true;
				}
				RPCharacter character = resolveCharacter(sender, pd, player, args, playerIndex + 2, args.length);
				if (character != null) {
					setStrikes(sender, player, character, count);
				}
			}
			case "endsession" -> {
				RPCharacter character = resolveCharacter(sender, pd, player, args, playerIndex + 1, args.length);
				if (character == null) {
					return true;
				}
				character.setEvilRpSessionEndsAtMs(0L);
				RPCharacters.getPlayerManager().savePlayer(player);
				RPTexts.send(sender, RPTexts.SUCCESS + "Ended the evil RP session for " + RPTexts.WARN
						+ character.getName() + RPTexts.SUCCESS + ".");
			}
			default -> RPTexts.send(sender, RPTexts.ERROR + "Usage: " + ADMIN_USAGE);
		}
		return true;
	}

	private static void sendSummary(CommandSender sender, RPCharacter character, String subject) {
		RPTexts.send(sender, RPTexts.MUTED + subject + " " + RPTexts.WARN + character.getEvilRpStrikes() + "/"
				+ StrikeOutcome.MAX_STRIKES + RPTexts.MUTED + " strikes.");
		if (EvilRpService.isInSession(character)) {
			RPTexts.send(sender, RPTexts.MUTED + "Evil RP session: " + RPTexts.WARN
					+ EvilRpService.formatRemaining(EvilRpService.remainingMs(character)) + RPTexts.MUTED + " left.");
		} else {
			RPTexts.send(sender, RPTexts.MUTED + "No evil RP session running.");
		}
	}

	private static void setStrikes(CommandSender sender, Player player, RPCharacter character, int count) {
		character.setEvilRpStrikes(count);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(sender, RPTexts.SUCCESS + "Set " + RPTexts.WARN + character.getName() + RPTexts.SUCCESS
				+ " to " + RPTexts.WARN + count + RPTexts.SUCCESS + " strikes. Injuries were not changed.");
	}

	private static String describe(StrikeOutcome outcome) {
		return switch (outcome) {
			case HEALING_INJURY -> "healing injury";
			case PERMANENT_INJURY -> "permanent injury";
			case DEATH -> "character killed";
		};
	}

	private static boolean requireAlive(CommandSender sender, RPCharacter character) {
		if (character.getStatus() == Status.ALIVE) {
			return true;
		}
		RPTexts.send(sender, RPTexts.ERROR + "That character is not alive.");
		return false;
	}

	/** Uses {@code args[index]} as the character when present before {@code end}, else the active one. */
	private static RPCharacter resolveCharacter(CommandSender sender, PlayerData pd, Player player, String[] args,
			int index, int end) {
		if (index < end) {
			RPCharacter found = findCharacter(pd, args[index]);
			if (found == null) {
				RPTexts.send(sender, RPTexts.ERROR + "No character found matching " + RPTexts.WARN + args[index]
						+ RPTexts.ERROR + " for " + RPTexts.WARN + player.getName() + RPTexts.ERROR + ".");
			}
			return found;
		}
		if (!pd.hasActiveCharacter()) {
			RPTexts.send(sender, RPTexts.ERROR + player.getName() + " has no active character.");
			return null;
		}
		return pd.getActiveCharacter();
	}

	private static RPCharacter findCharacter(PlayerData pd, String query) {
		RPCharacter bySlug = pd.getCharacterBySlug(query);
		if (bySlug != null) {
			return bySlug;
		}
		RPCharacter byId = pd.getCharacterById(query);
		if (byId != null) {
			return byId;
		}
		for (RPCharacter character : pd.getCharacters()) {
			if (character.getName() != null && character.getName().equalsIgnoreCase(query)) {
				return character;
			}
		}
		return null;
	}
}
