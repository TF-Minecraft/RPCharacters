package net.tfminecraft.rpcharacters.tutorial;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** {@code /rpcharacter tutorial dismiss <id>} and {@code /rpcharacter admin tutorial reset <player> [id]}. */
public final class TutorialCommands {

	private TutorialCommands() {
	}

	public static boolean handle(Player player, String[] args) {
		if (args.length != 3 || !args[1].equalsIgnoreCase("dismiss")) {
			RPTexts.send(player, RPTexts.ERROR + "Usage: /rpcharacter tutorial dismiss <tutorial>");
			return true;
		}
		return dismiss(player, args[2]);
	}

	public static boolean dismiss(Player player, String id) {
		if (!TutorialService.dismiss(player, id)) {
			RPTexts.send(player, RPTexts.ERROR + "There's no tutorial called " + RPTexts.WARN + id + RPTexts.ERROR + ".");
			return true;
		}
		RPTexts.sendPrefixed(player, RPTexts.SUCCESS + "Got it! " + RPTexts.MUTED + "You won't see that tutorial again.");
		return true;
	}

	/** {@code args[actionIndex]} is {@code reset}, followed by the player and an optional tutorial id. */
	public static boolean handleAdmin(CommandSender sender, String[] args, int actionIndex) {
		if (args.length < actionIndex + 2 || !args[actionIndex].equalsIgnoreCase("reset")) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: /rpcharacter admin tutorial reset <player> [tutorial]");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[actionIndex + 1]);
		PlayerData pd = target != null ? PlayerManager.get(target) : null;
		if (pd == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No player found.");
			return true;
		}
		if (args.length > actionIndex + 2) {
			pd.setTutorialDismissed(args[actionIndex + 2], false);
		} else {
			pd.clearDismissedTutorials();
		}
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Reset tutorials for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + ". They will show again.");
		return true;
	}
}
