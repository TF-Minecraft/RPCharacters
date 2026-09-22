package net.tfminecraft.rpcharacters.wipe;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.api.GatewayClient;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;

/**
 * {@code /rpcharacter wipe website|tagged [confirm]}. Admin only, console allowed.
 * The first call arms a confirm for that sender; the confirm expires after 30s.
 */
public final class WipeCommand {

	private static final String WEBSITE = "website";
	private static final String TAGGED = "tagged";
	private static final long CONFIRM_TTL_MS = 30_000L;
	private static final String USAGE = "Usage: /rpcharacter wipe <website|tagged> [confirm]";

	private static final Map<String, Pending> PENDING = new HashMap<>();

	private WipeCommand() {}

	private static final class Pending {
		private final String action;
		private final long expiresAtMs;

		private Pending(String action, long expiresAtMs) {
			this.action = action;
			this.expiresAtMs = expiresAtMs;
		}
	}

	public static boolean handle(CommandSender sender, String[] args) {
		if (!Permissions.isAdmin(sender)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to use this command.");
			return true;
		}
		String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
		if (!WEBSITE.equals(action) && !TAGGED.equals(action)) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		if (args.length == 2) {
			return prelude(sender, action);
		}
		if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		if (!takeConfirm(sender, action)) {
			return true;
		}
		if (WEBSITE.equals(action)) {
			return wipeWebsite(sender);
		}
		return wipeTagged(sender);
	}

	private static boolean prelude(CommandSender sender, String action) {
		if (WEBSITE.equals(action)) {
			String realm = GatewayClient.realmId();
			if (realm == null) {
				RPTexts.send(sender, RPTexts.ERROR + "Could not read the realm id from TFMCWeb. Website wipe aborted.");
				return true;
			}
			RPTexts.send(sender, RPTexts.WARN + "Website wipe target: realm " + RPTexts.ACCENT + realm + RPTexts.WARN + ".");
			RPTexts.send(sender, RPTexts.ERROR
					+ "This deletes every website character row for this realm, including pending donor creates.");
		} else {
			RPTexts.send(sender, RPTexts.WARN + "Tagged characters on disk: "
					+ RPTexts.ACCENT + CharacterWipeService.countTagged() + RPTexts.WARN + ".");
		}
		PENDING.put(key(sender), new Pending(action, System.currentTimeMillis() + CONFIRM_TTL_MS));
		RPTexts.send(sender, RPTexts.COMMAND + "Type /rpcharacter wipe " + action + " confirm"
				+ RPTexts.WARN + " within 30 seconds.");
		return true;
	}

	/** True when this sender armed this exact action and it has not expired. */
	private static boolean takeConfirm(CommandSender sender, String action) {
		String key = key(sender);
		Pending pending = PENDING.get(key);
		if (pending == null || !pending.action.equals(action)) {
			RPTexts.send(sender, RPTexts.ERROR + "Nothing to confirm.");
			return false;
		}
		PENDING.remove(key);
		if (System.currentTimeMillis() > pending.expiresAtMs) {
			RPTexts.send(sender, RPTexts.ERROR + "Confirm expired. Run the wipe command again.");
			return false;
		}
		return true;
	}

	private static boolean wipeWebsite(CommandSender sender) {
		String realm = GatewayClient.realmId();
		if (realm == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Could not read the realm id from TFMCWeb. Website wipe aborted.");
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

	private static boolean wipeTagged(CommandSender sender) {
		CharacterWipeService.WipeResult result = CharacterWipeService.wipeTagged();
		if (result.charactersDeleted == 0) {
			RPTexts.send(sender, RPTexts.WARN + "No tagged characters found.");
			return true;
		}
		RPTexts.send(sender, RPTexts.SUCCESS + "Deleted " + result.charactersDeleted
				+ " tagged character(s) across " + result.playersTouched + " player(s).");
		if (Cache.devCharacters) {
			// Roster push is off while the flag is on, so the site has no mirror to clean.
			return true;
		}
		String realm = GatewayClient.realmId();
		if (realm == null) {
			RPTexts.send(sender, RPTexts.MUTED + "Skipped website cleanup: realm id unavailable.");
			return true;
		}
		List<String> ids = result.deletedIds;
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			ProvinceSystemClient.SimpleResult cleanup =
					ProvinceSystemClient.deleteCharacters(realm, ids);
			if (cleanup.ok) {
				return;
			}
			RPCharacters.plugin.getLogger().warning(
				"[wipe] website cleanup failed for " + ids.size() + " id(s): " + cleanup.error
			);
			Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> RPTexts.send(
				sender,
				RPTexts.MUTED + "Website cleanup for those ids failed: " + cleanup.error
			));
		});
		return true;
	}

	private static String key(CommandSender sender) {
		return sender.getName().toLowerCase(Locale.ROOT);
	}
}
