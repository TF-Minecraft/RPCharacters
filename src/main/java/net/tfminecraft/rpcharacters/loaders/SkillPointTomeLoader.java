package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.objects.SkillPointTomeDefinition;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class SkillPointTomeLoader implements LoaderInterface {

	private static final Map<String, SkillPointTomeDefinition> tomes = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		tomes.clear();
		loadTomes(config);
	}

	private static void loadTomes(FileConfiguration config) {
		if (!config.isConfigurationSection("skill-point-tomes")) {
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("skill-point-tomes");
		for (String key : section.getKeys(false)) {
			ConfigurationSection tomeSection = section.getConfigurationSection(key);
			if (tomeSection == null) {
				continue;
			}
			SkillPointTomeDefinition definition = new SkillPointTomeDefinition(key, tomeSection);
			if (definition.getItem() == null || definition.getItem().isBlank()) {
				RPCharacters.plugin.getLogger().warning("Skill point tome '" + key + "' has no item path — skipped.");
				continue;
			}
			if (definition.getSkillPoints() < 1) {
				RPCharacters.plugin.getLogger().warning("Skill point tome '" + key + "' has invalid skill-points — skipped.");
				continue;
			}
			tomes.put(key.toLowerCase(Locale.ROOT), definition);
		}
	}

	public static SkillPointTomeDefinition resolve(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return null;
		}
		for (SkillPointTomeDefinition tome : tomes.values()) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, tome.getItem())) {
				return tome;
			}
		}
		return null;
	}
}
