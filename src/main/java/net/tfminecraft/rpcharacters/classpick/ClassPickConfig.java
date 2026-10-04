package net.tfminecraft.rpcharacters.classpick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.classpick.ClassPickService.Settings;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;

/** Reads the {@code class-selection} section of config.yml into {@link ClassPickService}. */
public final class ClassPickConfig {
	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private ClassPickConfig() {}

	public static void load(ConfigurationSection section) {
		if (section == null) {
			ClassPickService.configure(Settings.DEFAULTS);
			return;
		}
		Settings defaults = Settings.DEFAULTS;
		ClassPickService.configure(new Settings(
				section.getBoolean("enabled", defaults.enabled()),
				section.getBoolean("infinite-points", defaults.infinitePoints()),
				readCost(section.get("change-cost"), defaults.changeCost()),
				readAccounts(section, defaults.accounts()),
				readCommands(section, defaults.commands()),
				readPermission(section, defaults.resetPermission())));
	}

	private static BigDecimal readCost(Object value, BigDecimal fallback) {
		if (value == null) {
			return fallback;
		}
		if (value instanceof Number number && number.doubleValue() >= 0) {
			return new BigDecimal(number.toString()).setScale(2, RoundingMode.HALF_UP);
		}
		LOG.warning("[RPCharacters] class-selection.change-cost: ignored '" + value
				+ "'. It must be a number of 0 or more.");
		return fallback;
	}

	private static List<Account> readAccounts(ConfigurationSection section, List<Account> fallback) {
		if (!section.isList("accounts")) {
			return fallback;
		}
		List<Account> accounts = new ArrayList<>();
		for (String name : section.getStringList("accounts")) {
			try {
				accounts.add(Account.valueOf(name.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				LOG.warning("[RPCharacters] class-selection.accounts: unknown account '" + name
						+ "'. Use pouch or bank.");
			}
		}
		return accounts.isEmpty() ? fallback : List.copyOf(accounts);
	}

	private static String readPermission(ConfigurationSection section, String fallback) {
		String permission = section.getString("reset-permission", "").trim();
		return permission.isEmpty() ? fallback : permission;
	}

	private static Set<String> readCommands(ConfigurationSection section, Set<String> fallback) {
		if (!section.isList("commands")) {
			return fallback;
		}
		Set<String> commands = new LinkedHashSet<>();
		for (String raw : section.getStringList("commands")) {
			String command = raw.trim().toLowerCase(Locale.ROOT);
			command = command.startsWith("/") ? command.substring(1) : command;
			if (!command.isEmpty()) {
				commands.add(command);
			}
		}
		return Set.copyOf(commands);
	}
}
