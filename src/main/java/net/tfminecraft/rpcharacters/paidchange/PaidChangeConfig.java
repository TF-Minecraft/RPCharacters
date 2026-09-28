package net.tfminecraft.rpcharacters.paidchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;

/** Reads the {@code paid-changes} section of config.yml into {@link PaidChangeService}. */
public final class PaidChangeConfig {
	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private PaidChangeConfig() {}

	public static void load(ConfigurationSection section) {
		List<PaidChangeRule> rules = new ArrayList<>();
		List<Account> accounts = new ArrayList<>();
		if (section != null) {
			for (String name : section.getStringList("accounts")) {
				try {
					accounts.add(Account.valueOf(name.trim().toUpperCase(Locale.ROOT)));
				} catch (IllegalArgumentException e) {
					LOG.warning("[RPCharacters] paid-changes.accounts: unknown account '" + name
							+ "'. Use pouch or bank.");
				}
			}
			ConfigurationSection ruleSection = section.getConfigurationSection("rules");
			if (ruleSection != null) {
				for (String id : ruleSection.getKeys(false)) {
					PaidChangeRule rule = readRule(id, ruleSection.getConfigurationSection(id));
					if (rule != null) {
						rules.add(rule);
					}
				}
			}
		}
		PaidChangeService.configure(rules, accounts);
	}

	private static PaidChangeRule readRule(String id, ConfigurationSection rule) {
		if (rule == null) {
			return null;
		}
		String stageId = rule.getString("stage");
		if (stageId == null || stageId.isBlank()) {
			LOG.warning("[RPCharacters] paid-changes.rules." + id + " has no stage; skipped.");
			return null;
		}
		List<Double> costs = new ArrayList<>();
		for (Object value : rule.getList("costs", List.of())) {
			if (value instanceof Number number && number.doubleValue() >= 0.0) {
				costs.add(number.doubleValue());
			} else {
				LOG.warning("[RPCharacters] paid-changes.rules." + id + ".costs: ignored '" + value
						+ "'. Costs must be numbers of 0 or more.");
			}
		}
		if (costs.isEmpty()) {
			LOG.warning("[RPCharacters] paid-changes.rules." + id + " has no costs; skipped.");
			return null;
		}
		return new PaidChangeRule(id, stageId.trim(), rule.getString("label", id), costs);
	}
}
