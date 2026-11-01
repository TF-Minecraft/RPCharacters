package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class InjuryProgressionLoader implements LoaderInterface {

	private static final Map<String, String> progression = new HashMap<>();
	private static final Map<String, Integer> limits = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		progression.clear();
		limits.clear();
		loadLimits(config.getConfigurationSection("limits"));
		if (!config.isConfigurationSection("progression")) {
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("progression");
		for (String healingId : section.getKeys(false)) {
			String permanentId = section.getString(healingId);
			if (permanentId == null || permanentId.isBlank()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression '" + healingId + "' has no target and was skipped.");
				continue;
			}

			Trait healingTrait = TraitLoader.getByString(healingId);
			if (healingTrait == null) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression '" + healingId + "' references unknown healing trait and was skipped.");
				continue;
			}
			if (!healingTrait.getTraitData().hasDuration()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression source '" + healingId + "' has no duration and was skipped.");
				continue;
			}

			Trait permanentTrait = TraitLoader.getByString(permanentId);
			if (permanentTrait == null) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression '" + healingId + "' -> '" + permanentId
								+ "' references unknown permanent trait and was skipped.");
				continue;
			}
			if (permanentTrait.getTraitData().hasDuration()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression target '" + permanentId + "' has duration and was skipped.");
				continue;
			}
			if (!permanentTrait.getTraitData().isInjuryKey()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression target '" + permanentId + "' is not key injury and was skipped.");
				continue;
			}

			progression.put(healingId.toLowerCase(Locale.ROOT), permanentId.toLowerCase(Locale.ROOT));
		}
	}

	private static void loadLimits(ConfigurationSection section) {
		if (section == null) {
			return;
		}
		for (String permanentId : section.getKeys(false)) {
			if (!isPermanentInjury(permanentId)) {
				RPCharacters.plugin.getLogger().warning(
						"Injury limit '" + permanentId + "' is not a permanent injury and was skipped.");
				continue;
			}
			int limit = section.getInt(permanentId, 0);
			if (limit < 1) {
				RPCharacters.plugin.getLogger().warning(
						"Injury limit '" + permanentId + "' must be at least 1 and was skipped.");
				continue;
			}
			limits.put(permanentId.toLowerCase(Locale.ROOT), limit);
		}
	}

	/** Configured injury slots for the body part this permanent injury names, or 0 when unset. */
	public static int getLimit(String permanentId) {
		if (permanentId == null || permanentId.isBlank()) {
			return 0;
		}
		return limits.getOrDefault(permanentId.toLowerCase(Locale.ROOT), 0);
	}

	public static String getPermanentId(String healingTraitId) {
		if (healingTraitId == null || healingTraitId.isBlank()) {
			return null;
		}
		return progression.get(healingTraitId.toLowerCase(Locale.ROOT));
	}

	public static boolean isHealingTrait(String traitId) {
		Trait trait = TraitLoader.getByString(traitId);
		return trait != null && trait.getTraitData().hasDuration();
	}

	public static boolean isPermanentInjury(String traitId) {
		Trait trait = TraitLoader.getByString(traitId);
		return trait != null
				&& trait.getTraitData().isInjuryKey()
				&& !trait.getTraitData().hasDuration();
	}

	public static Map<String, String> getProgressionMap() {
		return Collections.unmodifiableMap(progression);
	}
}
