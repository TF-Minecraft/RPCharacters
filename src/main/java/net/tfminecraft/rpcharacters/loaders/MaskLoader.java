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
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.identity.MaskDefinition;

public final class MaskLoader implements LoaderInterface {

	private static final Map<String, MaskDefinition> masks = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		masks.clear();
		Cache.maskedLabel = config.getString("masked-label", "Masked");
		loadMasks(config);
	}

	private static void loadMasks(FileConfiguration config) {
		if (config.isList("masks")) {
			int index = 0;
			for (String path : config.getStringList("masks")) {
				if (path == null || path.isBlank()) {
					continue;
				}
				String id = "mask_" + index++;
				masks.put(id.toLowerCase(Locale.ROOT), MaskDefinition.fromItemPath(id, path.trim()));
			}
			return;
		}

		if (!config.isConfigurationSection("masks")) {
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("masks");
		for (String key : section.getKeys(false)) {
			ConfigurationSection maskSection = section.getConfigurationSection(key);
			if (maskSection == null) {
				continue;
			}
			masks.put(key.toLowerCase(Locale.ROOT), new MaskDefinition(key, maskSection));
		}
	}

	public static MaskDefinition resolveMask(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return null;
		}
		for (MaskDefinition mask : masks.values()) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, mask.getItem())) {
				return mask;
			}
		}
		return null;
	}
}
