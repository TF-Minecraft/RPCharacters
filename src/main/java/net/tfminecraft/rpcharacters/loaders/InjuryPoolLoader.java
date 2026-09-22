package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.DurationParser;

public final class InjuryPoolLoader implements LoaderInterface {

	private static final long DEFAULT_HEALING_TICK_INTERVAL_MS = 60_000L;
	private static final long MIN_HEALING_TICK_INTERVAL_TICKS = 20L;

	private static final Map<String, Integer> weights = new HashMap<>();
	private static long healingTickIntervalMs = DEFAULT_HEALING_TICK_INTERVAL_MS;

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		healingTickIntervalMs = resolveHealingTickIntervalMs(config.getString("healing-tick-interval"));

		weights.clear();
		if (!config.isConfigurationSection("injuries")) {
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("injuries");
		for (String key : section.getKeys(false)) {
			ConfigurationSection injurySection = section.getConfigurationSection(key);
			if (injurySection == null) {
				continue;
			}
			int weight = injurySection.getInt("weight", 0);
			if (weight < 1) {
				RPCharacters.plugin.getLogger().warning("Injury '" + key + "' has invalid weight — skipped.");
				continue;
			}
			if (TraitLoader.getByString(key) == null) {
				RPCharacters.plugin.getLogger().warning("Injury '" + key + "' references unknown trait — skipped.");
				continue;
			}
			Trait trait = TraitLoader.getByString(key);
			if (!trait.getTraitData().hasDuration()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury pool entry '" + key + "' is not a healing injury (no duration) — skipped.");
				continue;
			}
			weights.put(key.toLowerCase(Locale.ROOT), weight);
		}
	}

	public static long getHealingTickIntervalMs() {
		return healingTickIntervalMs;
	}

	public static long getHealingTickIntervalTicks() {
		long ticks = healingTickIntervalMs / 50L;
		return Math.max(MIN_HEALING_TICK_INTERVAL_TICKS, ticks);
	}

	private static long resolveHealingTickIntervalMs(String raw) {
		long parsed = DurationParser.parseShortDurationMs(raw);
		if (parsed > 0L) {
			return parsed;
		}
		if (raw != null && !raw.isBlank()) {
			RPCharacters.plugin.getLogger().warning(
					"Invalid healing-tick-interval '" + raw + "' - using default 1m.");
		}
		return DEFAULT_HEALING_TICK_INTERVAL_MS;
	}

	public static Set<String> getPoolTraitIds() {
		return Collections.unmodifiableSet(weights.keySet());
	}

	public static int countRemainingInjuries(Set<String> ownedTraitIds) {
		int count = 0;
		for (String traitId : weights.keySet()) {
			if (!ownsTrait(ownedTraitIds, traitId)) {
				count++;
			}
		}
		return count;
	}

	public static Trait pickRandom(Set<String> ownedTraitIds) {
		List<String> eligible = new ArrayList<>();
		List<Integer> eligibleWeights = new ArrayList<>();
		int total = 0;

		for (Map.Entry<String, Integer> entry : weights.entrySet()) {
			if (ownsTrait(ownedTraitIds, entry.getKey())) {
				continue;
			}
			eligible.add(entry.getKey());
			eligibleWeights.add(entry.getValue());
			total += entry.getValue();
		}

		if (eligible.isEmpty() || total <= 0) {
			return null;
		}

		int roll = ThreadLocalRandom.current().nextInt(total);
		int cumulative = 0;
		for (int i = 0; i < eligible.size(); i++) {
			cumulative += eligibleWeights.get(i);
			if (roll < cumulative) {
				return TraitLoader.getByString(eligible.get(i));
			}
		}

		return TraitLoader.getByString(eligible.get(eligible.size() - 1));
	}

	private static boolean ownsTrait(Set<String> ownedTraitIds, String traitId) {
		for (String owned : ownedTraitIds) {
			if (owned.equalsIgnoreCase(traitId)) {
				return true;
			}
		}
		return false;
	}
}
