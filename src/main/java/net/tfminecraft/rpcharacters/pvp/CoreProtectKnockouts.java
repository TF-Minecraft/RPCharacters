package net.tfminecraft.rpcharacters.pvp;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Reports nonlethal knockouts to CoreProtect, which logs every hit between players but can't see
 * a knockout itself because the knockout blow is cancelled. CoreProtect is reached by reflection
 * so RPCharacters needs no load order against it, and works without it or with older versions.
 */
public final class CoreProtectKnockouts {
	private static final Logger LOG = Logger.getLogger("RPCharacters");
	private static boolean warned;

	private CoreProtectKnockouts() {
	}

	/** Logs the knockout of {@code victim} by {@code attacker}; true if CoreProtect queued it. */
	public static boolean log(Player attacker, Player victim) {
		if (attacker == null || victim == null) {
			return false;
		}
		try {
			Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
			if (plugin == null || !plugin.isEnabled()) {
				return false;
			}
			Object api = plugin.getClass().getMethod("getAPI").invoke(plugin);
			if (api == null) {
				return false;
			}
			Method logKnockout = api.getClass().getMethod("logPvpKnockout", Player.class, Player.class);
			return Boolean.TRUE.equals(logKnockout.invoke(api, attacker, victim));
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!warned) {
				warned = true;
				LOG.log(Level.WARNING, "[RPCharacters] CoreProtect could not log a knockout; it needs a version with logPvpKnockout", e);
			}
			return false;
		}
	}

	static void resetWarning() {
		warned = false;
	}
}
