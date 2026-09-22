package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.enums.Status;

public final class PermadeathAdminCommands {

	private PermadeathAdminCommands() {
	}

	public static boolean handleInjure(CommandSender sender, String[] args) {
		return handleInjure(sender, args, 1, "/rpcharacter injure <player> [character] [permanent]");
	}

	public static boolean handleInjure(CommandSender sender, String[] args, int playerArgIndex, String usage) {
		InjureTarget target = resolveInjureTarget(sender, args, playerArgIndex, usage);
		if (target == null) {
			return true;
		}
		if (!target.character.getStatus().equals(Status.ALIVE)) {
			RPTexts.send(sender, RPTexts.ERROR + "That character is not alive.");
			return true;
		}

		boolean applied = target.permanent
				? PermadeathService.applyRandomPermanentInjury(target.player, target.character)
				: PermadeathService.applyRandomInjury(target.player, target.character);
		if (!applied) {
			RPTexts.send(sender, RPTexts.ERROR + target.character.getName() + " has no injuries left to receive.");
			return true;
		}

		String injuryType = target.permanent ? "permanent injury" : "injury";
		RPTexts.send(sender, RPTexts.SUCCESS + "Applied a random " + injuryType + " to " + RPTexts.WARN
				+ target.character.getName() + RPTexts.SUCCESS + " (" + RPTexts.WARN + target.player.getName()
				+ RPTexts.SUCCESS + ").");
		return true;
	}

	public static boolean handlePermakill(CommandSender sender, String[] args) {
		return handlePermakill(sender, args, 1, "permakill");
	}

	public static boolean handlePermakill(CommandSender sender, String[] args, int playerArgIndex, String commandName) {
		ResolvedTarget target = resolveTarget(sender, args, playerArgIndex, commandName);
		if (target == null) {
			return true;
		}
		if (!target.character.getStatus().equals(Status.ALIVE)) {
			RPTexts.send(sender, RPTexts.ERROR + "That character is already dead.");
			return true;
		}
		if (!PermadeathService.killCharacter(target.player, target.character, PermakillCause.COMMAND)) {
			RPTexts.send(sender, RPTexts.ERROR + "Permakill was cancelled.");
			return true;
		}
		RPTexts.send(sender, RPTexts.SUCCESS + "Permanently killed " + RPTexts.WARN + target.character.getName()
				+ RPTexts.SUCCESS + " (" + RPTexts.WARN + target.player.getName() + RPTexts.SUCCESS + ").");
		return true;
	}

	private static InjureTarget resolveInjureTarget(CommandSender sender, String[] args, int playerArgIndex,
			String usage) {
		if (args.length <= playerArgIndex) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: " + usage);
			return null;
		}

		int endIndex = args.length - 1;
		boolean permanent = false;
		if (endIndex > playerArgIndex && args[endIndex].equalsIgnoreCase("permanent")) {
			permanent = true;
			endIndex--;
		}

		Player player = Bukkit.getPlayerExact(args[playerArgIndex]);
		if (player == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No player found.");
			return null;
		}

		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Player data not loaded for " + RPTexts.WARN + player.getName()
					+ RPTexts.ERROR + ".");
			return null;
		}

		RPCharacter character;
		if (endIndex > playerArgIndex) {
			character = findCharacter(pd, args[playerArgIndex + 1]);
			if (character == null) {
				RPTexts.send(sender, RPTexts.ERROR + "No character found matching " + RPTexts.WARN
						+ args[playerArgIndex + 1]
						+ RPTexts.ERROR + " for " + RPTexts.WARN + player.getName() + RPTexts.ERROR + ".");
				return null;
			}
		} else {
			if (!pd.hasActiveCharacter()) {
				RPTexts.send(sender, RPTexts.ERROR + player.getName() + " has no active character.");
				return null;
			}
			character = pd.getActiveCharacter();
		}

		return new InjureTarget(player, character, permanent);
	}

	private static ResolvedTarget resolveTarget(CommandSender sender, String[] args, int playerArgIndex,
			String commandName) {
		if (args.length <= playerArgIndex) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: /rpcharacter admin " + commandName + " <player> [character]");
			return null;
		}

		Player player = Bukkit.getPlayerExact(args[playerArgIndex]);
		if (player == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No player found.");
			return null;
		}

		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Player data not loaded for " + RPTexts.WARN + player.getName() + RPTexts.ERROR + ".");
			return null;
		}

		RPCharacter character;
		if (args.length > playerArgIndex + 1) {
			character = findCharacter(pd, args[playerArgIndex + 1]);
			if (character == null) {
				RPTexts.send(sender, RPTexts.ERROR + "No character found matching " + RPTexts.WARN + args[playerArgIndex + 1]
						+ RPTexts.ERROR + " for " + RPTexts.WARN + player.getName() + RPTexts.ERROR + ".");
				return null;
			}
		} else {
			if (!pd.hasActiveCharacter()) {
				RPTexts.send(sender, RPTexts.ERROR + player.getName() + " has no active character.");
				return null;
			}
			character = pd.getActiveCharacter();
		}

		return new ResolvedTarget(player, character);
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

	private static final class InjureTarget {
		private final Player player;
		private final RPCharacter character;
		private final boolean permanent;

		private InjureTarget(Player player, RPCharacter character, boolean permanent) {
			this.player = player;
			this.character = character;
			this.permanent = permanent;
		}
	}

	private static final class ResolvedTarget {
		private final Player player;
		private final RPCharacter character;

		private ResolvedTarget(Player player, RPCharacter character) {
			this.player = player;
			this.character = character;
		}
	}
}
