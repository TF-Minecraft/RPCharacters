package net.tfminecraft.rpcharacters.playerlist;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * {@code /rpcharacter admin hide <player> [on|off]}: staff hide or unhide the
 * character another online player is playing. Without on/off it only reports
 * the current state, since a blind toggle on someone else can flip it the
 * wrong way. {@code /rpcharacter sethidden} stays for your own characters.
 */
public final class HiddenCharacterAdminCommand {

	public static final String SUBCOMMAND = "hide";
	static final String USAGE = "Usage: /rpcharacter admin hide <player> [on|off]";

	private HiddenCharacterAdminCommand() {}

	public static boolean canUse(CommandSender sender) {
		return sender.hasPermission(Cache.personaCharacterHiddenPermission) || Permissions.isAdmin(sender);
	}

	public static boolean handle(CommandSender sender, String[] args, int index) {
		if (!canUse(sender)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to use this command.");
			return true;
		}
		if (args.length <= index || args.length > index + 2) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[index]);
		PlayerData data = target == null ? null : PlayerManager.get(target);
		RPCharacter character = data == null ? null : data.getActiveCharacter();
		if (character == null) {
			RPTexts.send(sender, RPTexts.ERROR + (target == null ? "No online player called " : "No active character for ")
					+ RPTexts.WARN + args[index] + RPTexts.ERROR + ".");
			return true;
		}
		String who = RPTexts.MUTED + "Character " + RPTexts.WARN + DisplayIdentityService.resolveCharacterName(character)
				+ RPTexts.MUTED + " (" + target.getName() + ")";
		if (args.length == index + 1) {
			RPTexts.send(sender, who + (character.isHidden() ? " is hidden." : " is not hidden."));
			return true;
		}
		String mode = args[index + 1].toLowerCase(Locale.ROOT);
		if (!mode.equals("on") && !mode.equals("off")) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		boolean hide = mode.equals("on");
		if (character.isHidden() == hide) {
			RPTexts.send(sender, who + (hide ? " is already hidden." : " is not hidden."));
			return true;
		}
		character.setHidden(hide);
		RPCharacters.getPlayerManager().savePlayer(target);
		HiddenCharacterListing.refresh(target);
		RPTexts.send(sender, who + (hide ? " is now hidden from TAB and /players." : " is no longer hidden."));
		if (!target.equals(sender)) {
			RPTexts.sendPrefixed(target, RPTexts.MUTED + (hide
					? "Staff hid your character: other players do not see you in TAB or /players. You stay visible in the world."
					: "Staff unhid your character: you are back in TAB and /players."));
		}
		return true;
	}
}
