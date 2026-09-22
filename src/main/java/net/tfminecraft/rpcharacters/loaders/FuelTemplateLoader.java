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
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.objects.FuelTemplate;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class FuelTemplateLoader implements LoaderInterface {

	private static final Map<String, FuelTemplate> templates = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		templates.clear();
		for (String key : config.getKeys(false)) {
			ConfigurationSection section = config.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			FuelTemplate template = new FuelTemplate(key, section);
			if (!template.isValid()) {
				RPCharacters.plugin.getLogger().warning(
						"Fuel template '" + key + "' is invalid and was skipped.");
				continue;
			}
			templates.put(key.toLowerCase(Locale.ROOT), template);
		}
	}

	public static FuelTemplate getByString(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		return templates.get(id.toLowerCase(Locale.ROOT));
	}

	public static FuelTemplate getByItem(String itemPath) {
		if (itemPath == null || itemPath.isBlank()) {
			return null;
		}
		for (FuelTemplate template : templates.values()) {
			if (template.getItem().equalsIgnoreCase(itemPath)) {
				return template;
			}
		}
		return null;
	}

	public static Map<String, FuelTemplate> getAll() {
		return Collections.unmodifiableMap(templates);
	}

	public static FuelTemplate resolveForItem(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return null;
		}
		for (FuelTemplate template : templates.values()) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, template.getItem())) {
				return template;
			}
		}
		return null;
	}
}
