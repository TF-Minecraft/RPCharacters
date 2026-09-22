package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.objects.RemedyDefinition;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class RemedyLoader implements LoaderInterface {

	private static final Map<String, RemedyDefinition> remedies = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		remedies.clear();
		loadRemedies(config);
	}

	private static void loadRemedies(FileConfiguration config) {
		if (!config.isConfigurationSection("remedies")) {
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("remedies");
		for (String key : section.getKeys(false)) {
			ConfigurationSection remedySection = section.getConfigurationSection(key);
			if (remedySection == null) {
				continue;
			}
			RemedyDefinition definition = createDefinition(key, remedySection);
			if (definition == null) {
				continue;
			}
			remedies.put(key.toLowerCase(Locale.ROOT), definition);
		}
	}

	private static RemedyDefinition createDefinition(String key, ConfigurationSection remedySection) {
		String item = remedySection.getString("item", "");
		if (item == null || item.isBlank()) {
			RPCharacters.plugin.getLogger().warning("Remedy '" + key + "' has no item path — skipped.");
			return null;
		}

		List<String> healingTraits = new ArrayList<>();
		RemedyDefinition raw = new RemedyDefinition(key, remedySection);
		for (String traitId : raw.getTraits()) {
			if (InjuryProgressionLoader.isHealingTrait(traitId)) {
				healingTraits.add(traitId);
				continue;
			}
			RPCharacters.plugin.getLogger().warning(
					"Remedy '" + key + "' trait '" + traitId + "' is not a healing injury — skipped.");
		}
		if (healingTraits.isEmpty()) {
			RPCharacters.plugin.getLogger().warning("Remedy '" + key + "' has no healing trait(s) — skipped.");
			return null;
		}

		return new RemedyDefinition(key, item, healingTraits);
	}

	public static List<RemedyDefinition> resolveAll(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return List.of();
		}
		List<RemedyDefinition> matches = new ArrayList<>();
		for (RemedyDefinition remedy : remedies.values()) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, remedy.getItem())) {
				matches.add(remedy);
			}
		}
		return matches;
	}
}
