package net.tfminecraft.rpcharacters.prison;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.permadeath.PermadeathAdminCommands;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Prison roleplay: staff mark an imprisoned character with {@code /rpcharacter admin prison}, and its player
 * can switch into it and back out again without waiting for the character switch cooldown. The cooldown
 * still holds between the account's other characters. It counts from the last unmarked character the
 * player entered, so a marked character cannot carry a player from one free character to another.
 */
public final class PrisonSwitch {

	public static final String SUBCOMMAND = "prison";
	public static final String PERMISSION = "rpchar.prison";
	static final String USAGE = "/rpcharacter admin " + SUBCOMMAND + " <player> [character] [on|off]";

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private PrisonSwitch() {}

	/** Id of the character the switch cooldown counts from, or null when no cooldown can apply. */
	static String cooldownAnchor(PlayerData account, String lastId) {
		String anchor = account.getLastFreeCharacterId() != null ? account.getLastFreeCharacterId() : lastId;
		if (anchor == null) {
			return null;
		}
		RPCharacter from = account.getCharacterById(anchor);
		return from != null && from.isPrisonSwitch() ? null : anchor;
	}

	/**
	 * Whether a running switch cooldown holds a move into {@code target} from {@code lastId}, the character
	 * the player last played: only a move away from a living unmarked character into another unmarked one.
	 */
	public static boolean cooldownApplies(PlayerData account, String lastId, RPCharacter target) {
		if (target.isPrisonSwitch() || target.getId().equalsIgnoreCase(lastId)) {
			return false;
		}
		String anchor = cooldownAnchor(account, lastId);
		if (anchor == null || anchor.equalsIgnoreCase(target.getId())) {
			return false;
		}
		RPCharacter from = account.getCharacterById(anchor);
		return from != null && from.getStatus() == Status.ALIVE;
	}

	/** Records an entered character: only a move between unmarked characters starts the cooldown. */
	public static void entered(PlayerData account, String lastId, RPCharacter target) {
		String anchor = cooldownAnchor(account, lastId);
		if (target.isPrisonSwitch() || target.getId().equalsIgnoreCase(lastId)) {
			if (account.getLastFreeCharacterId() == null) {
				account.setLastFreeCharacterId(anchor);
			}
			return;
		}
		if (lastId != null && anchor != null && !anchor.equalsIgnoreCase(target.getId())) {
			account.recordCharacterSwitch();
		}
		account.setLastFreeCharacterId(target.getId());
	}

	public static boolean canUse(CommandSender sender) {
		return sender.hasPermission(PERMISSION) || Permissions.isAdmin(sender);
	}

	/** {@code /rpcharacter admin prison <player> [character] [on|off]}; without on/off the mark flips. */
	public static boolean handle(CommandSender sender, String[] args, int index) {
		if (!canUse(sender)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to use this command.");
			return true;
		}
		int end = args.length;
		Boolean wanted = null;
		if (end > index + 1 && (args[end - 1].equalsIgnoreCase("on") || args[end - 1].equalsIgnoreCase("off"))) {
			wanted = args[end - 1].equalsIgnoreCase("on");
			end--;
		}
		if (end <= index || end > index + 2) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: " + USAGE);
			return true;
		}
		Player player = Bukkit.getPlayerExact(args[index]);
		PlayerData account = player == null ? null : PlayerManager.get(player);
		if (account == null) {
			RPTexts.send(sender, RPTexts.ERROR + "No online player with loaded characters named " + RPTexts.WARN
					+ args[index] + RPTexts.ERROR + ".");
			return true;
		}
		RPCharacter character = end == index + 2
				? PermadeathAdminCommands.findCharacter(account, args[index + 1])
				: account.getActiveCharacter();
		if (character == null || character.getStatus() != Status.ALIVE) {
			RPTexts.send(sender, RPTexts.ERROR + (end == index + 2
					? "No living character matching " + RPTexts.WARN + args[index + 1] + RPTexts.ERROR + " for "
					: "No active character for ") + RPTexts.WARN + player.getName() + RPTexts.ERROR
					+ ". Name the character: " + USAGE);
			return true;
		}
		boolean marked = wanted != null ? wanted : !character.isPrisonSwitch();
		character.setPrisonSwitch(marked);
		RPCharacters.getPlayerManager().savePlayer(player);
		LOG.info("[RPCharacters] " + sender.getName() + " turned prison switching " + (marked ? "on" : "off")
				+ " for " + character.getName() + " (" + character.getId() + ") of " + player.getName() + ".");
		String note;
		if (marked) {
			RPTexts.send(sender, RPTexts.SUCCESS + "Prison switching is on for " + RPTexts.WARN + character.getName()
					+ RPTexts.SUCCESS + " (" + RPTexts.WARN + player.getName() + RPTexts.SUCCESS
					+ "). They can switch into and out of this character without the switch cooldown.");
			note = RPTexts.WARN + character.getName() + RPTexts.INFO + " is in prison. You can switch"
					+ " to and from them at any time without the character switch cooldown.";
		} else {
			RPTexts.send(sender, RPTexts.SUCCESS + "Prison switching is off for " + RPTexts.WARN + character.getName()
					+ RPTexts.SUCCESS + " (" + RPTexts.WARN + player.getName() + RPTexts.SUCCESS
					+ "). The normal switch cooldown applies again.");
			note = RPTexts.WARN + character.getName() + RPTexts.INFO + " is out of prison. The"
					+ " character switch cooldown applies to them again.";
		}
		// Chat waits for Play in character selection, so there the note goes into the selection, which redraws.
		if (RPCharacters.getCharacterBodies() != null) {
			RPCharacters.getCharacterBodies().feedback(player, RPTexts.formatDisplay(note));
		} else {
			RPTexts.send(player, note);
		}
		return true;
	}
}
