package net.tfminecraft.rpcharacters.wipe;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.api.GatewayClient;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;

/**
 * {@code /rpcharacter wipe website [confirm]}. Admin only, console allowed.
 * The first call arms a confirm for that sender; the confirm expires after 30s.
 */
public final class WipeCommand {

	private static final String WEBSITE = "website";
	private static final long CONFIRM_TTL_MS = 30_000L;
	private static final String USAGE = "Usage: /rpcharacter wipe website [confirm]";

	private record Confirmation(String realm, long expiresAtMs) {}
	/** A confirmation authorizes only the realm shown to this sender. */
	private static final Map<String, Confirmation> PENDING = new HashMap<>();

	private WipeCommand() {}

	public static boolean handle(CommandSender sender, String[] args) {
		if (!Permissions.isAdmin(sender)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to use this command.");
			return true;
		}
		if (args.length < 2 || !WEBSITE.equalsIgnoreCase(args[1])) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		if (args.length == 2) {
			return prelude(sender);
		}
		if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		String confirmedRealm = takeConfirm(sender);
		if (confirmedRealm == null) {
			return true;
		}
		return wipeWebsite(sender, confirmedRealm);
	}

	private static boolean prelude(CommandSender sender) {
		String realm = GatewayClient.realmId();
		if (realm == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Could not read the realm id from TFMCWeb. Website wipe aborted.");
			return true;
		}
		RPTexts.send(sender, RPTexts.WARN + "Website wipe target: realm " + RPTexts.ACCENT + realm + RPTexts.WARN + ".");
		RPTexts.send(sender, RPTexts.ERROR
				+ "This deletes every website character row for this realm, including pending donor creates.");
		PENDING.put(key(sender), new Confirmation(realm, System.currentTimeMillis() + CONFIRM_TTL_MS));
		RPTexts.send(sender, RPTexts.COMMAND + "Type /rpcharacter wipe website confirm"
				+ RPTexts.WARN + " within 30 seconds.");
		return true;
	}

	/** The realm this sender armed, only while the confirmation remains valid. */
	private static String takeConfirm(CommandSender sender) {
		Confirmation confirmation = PENDING.remove(key(sender));
		if (confirmation == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Nothing to confirm.");
			return null;
		}
		if (System.currentTimeMillis() > confirmation.expiresAtMs()) {
			RPTexts.send(sender, RPTexts.ERROR + "Confirm expired. Run the wipe command again.");
			return null;
		}
		return confirmation.realm();
	}

	private static boolean wipeWebsite(CommandSender sender, String confirmedRealm) {
		String realm = GatewayClient.realmId();
		if (realm == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Could not read the realm id from TFMCWeb. Website wipe aborted.");
			return true;
		}
		if (!realm.equals(confirmedRealm)) {
			RPTexts.send(sender, RPTexts.ERROR + "The realm changed since confirmation. Run the wipe command again.");
			return true;
		}
		RPTexts.send(sender, RPTexts.COMMAND + "Wiping website character data for realm " + realm + "...");
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			ProvinceSystemClient.RealmWipeResult result =
					ProvinceSystemClient.wipeRealmCharacterData(realm);
			Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
				if (!result.ok) {
					RPTexts.send(sender, RPTexts.ERROR + "Website wipe failed: " + result.error);
					RPCharacters.plugin.getLogger().warning(
						"[wipe] website wipe failed for realm " + realm + ": " + result.error
					);
					return;
				}
				RPTexts.send(sender, RPTexts.SUCCESS
						+ "Wiped website character data for realm " + result.realmId + ".");
				RPTexts.send(sender, RPTexts.MUTED + "Removed " + result.total
						+ " row(s) and " + result.pngsDeleted + " skin file(s).");
			});
		});
		return true;
	}

	private static String key(CommandSender sender) {
		return sender.getName().toLowerCase(Locale.ROOT);
	}
}
