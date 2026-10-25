package net.tfminecraft.rpcharacters.classpick;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * {@code /rpcharacter admin unlockclass|lockclass <player> <classId>}: staff give or take a hidden
 * class from a player's active character through {@link ClassUnlocks}. The caller checks the admin
 * permission.
 */
public final class ClassUnlockCommand {

	public static final String UNLOCK = "unlockclass";
	public static final String LOCK = "lockclass";

	private ClassUnlockCommand() {}

	public static boolean isSubcommand(String arg) {
		return UNLOCK.equalsIgnoreCase(arg) || LOCK.equalsIgnoreCase(arg);
	}

	/** {@code args[index - 1]} is the subcommand, then the player and the class id. */
	public static boolean handle(CommandSender sender, String[] args, int index) {
		boolean unlock = args[index - 1].equalsIgnoreCase(UNLOCK);
		if (args.length != index + 2) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: /rpcharacter admin " + (unlock ? UNLOCK : LOCK)
					+ " <player> <classId>");
			return true;
		}
		Player player = Bukkit.getPlayerExact(args[index]);
		RPCharacter character = ClassUnlocks.activeCharacter(player);
		if (character == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No online player with an active living character named "
					+ RPTexts.WARN + args[index] + RPTexts.ERROR + ".");
			return true;
		}
		String id = ClassUnlocks.normalise(args[index + 1]);
		PlayerClass playerClass = MMOCore.plugin.classManager.get(id);
		if (unlock && playerClass == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No class by the id " + RPTexts.WARN + id + RPTexts.ERROR + ".");
			return true;
		}
		String name = playerClass == null ? id : ClassPickService.className(playerClass);
		String who = RPTexts.WARN + character.getName() + RPTexts.MUTED + " (" + player.getName() + ")";
		if (ClassUnlocks.isUnlocked(player, id) == unlock) {
			RPTexts.send(sender, RPTexts.WARN + name + RPTexts.MUTED + " is already " + (unlock ? "unlocked" : "locked")
					+ " for " + who + RPTexts.MUTED + ".");
			return true;
		}
		if (!(unlock ? ClassUnlocks.unlock(player, id) : ClassUnlocks.lock(player, id))) {
			RPTexts.send(sender, RPTexts.ERROR + "Could not save " + RPTexts.WARN + character.getName() + RPTexts.ERROR
					+ ", so nothing changed. See the console.");
			return true;
		}
		RPTexts.send(sender, unlock
				? RPTexts.SUCCESS + "Unlocked " + name + RPTexts.SUCCESS + " for " + who + RPTexts.SUCCESS
						+ ". They can pick it in " + RPTexts.COMMAND + "/class" + RPTexts.SUCCESS + "."
				: RPTexts.SUCCESS + "Locked " + name + RPTexts.SUCCESS + " for " + who + RPTexts.SUCCESS
						+ ". Their current class did not change.");
		return true;
	}

	/** Candidates for {@code admin unlockclass|lockclass <player> <classId>}, before prefix filtering. */
	public static List<String> tabComplete(String[] args) {
		List<String> completions = new ArrayList<>();
		if (args.length == 3) {
			Bukkit.getOnlinePlayers().forEach(online -> completions.add(online.getName()));
		} else if (args.length == 4 && args[1].equalsIgnoreCase(LOCK)) {
			completions.addAll(ClassUnlocks.unlocked(Bukkit.getPlayerExact(args[2])));
		} else if (args.length == 4 && MMOCore.plugin != null && MMOCore.plugin.classManager != null) {
			Player target = Bukkit.getPlayerExact(args[2]);
			for (PlayerClass playerClass : MMOCore.plugin.classManager.getAll()) {
				if (!ClassUnlocks.isUnlocked(target, playerClass.getId())) {
					completions.add(playerClass.getId());
				}
			}
		}
		return completions;
	}
}
