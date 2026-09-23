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

import io.lumine.mythic.lib.api.item.NBTItem;
import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.identity.IaItemPath;
import net.tfminecraft.rpcharacters.identity.MaskDefinition;

public final class MaskLoader implements LoaderInterface {

	private static final String CUSTOM_FILE = "custom-masks.yml";

	private static final Map<String, MaskDefinition> masks = new HashMap<>();
	private static File bundledFile;
	private static File customFile;
	private static long customStamp = Long.MIN_VALUE;

	@Override
	public void load(File configFile) {
		bundledFile = configFile;
		File parent = configFile.getParentFile();
		customFile = parent == null ? new File(CUSTOM_FILE) : new File(parent, CUSTOM_FILE);
		reloadAll();
	}

	public static MaskDefinition resolveMask(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return null;
		}
		refreshCustomMasks();
		MaskDefinition[] snapshot;
		synchronized (MaskLoader.class) {
			snapshot = masks.values().toArray(new MaskDefinition[0]);
		}
		for (MaskDefinition mask : snapshot) {
			if (matches(item, mask.getItem())) {
				return mask;
			}
		}
		return null;
	}

	private static synchronized void reloadAll() {
		masks.clear();
		FileConfiguration bundled = loadYaml(bundledFile);
		if (bundled != null) {
			Cache.maskedLabel = bundled.getString("masked-label", "Masked");
			loadMasks(bundled, "");
		}
		long stamp = -1L;
		if (customFile != null && customFile.isFile()) {
			stamp = customFile.lastModified();
			FileConfiguration custom = loadYaml(customFile);
			if (custom != null) {
				loadMasks(custom, "custom_");
			}
		}
		customStamp = stamp;
	}

	/** Re-read custom-masks.yml when ArmourShop writes or deletes a mask. */
	private static void refreshCustomMasks() {
		File file = customFile;
		long stamp = file != null && file.isFile() ? file.lastModified() : -1L;
		if (stamp != customStamp) {
			reloadAll();
		}
	}

	private static FileConfiguration loadYaml(File configFile) {
		if (configFile == null) {
			return null;
		}
		FileConfiguration config = new YamlConfiguration();
		if (!configFile.isFile()) {
			return config;
		}
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}
		return config;
	}

	/**
	 * {@code prefix} keeps bundled and custom entries from replacing each other.
	 * {@link MaskDefinition} ids stay the original keys.
	 */
	private static void loadMasks(FileConfiguration config, String prefix) {
		String keyPrefix = prefix == null ? "" : prefix;
		if (config.isList("masks")) {
			int index = 0;
			for (String path : config.getStringList("masks")) {
				if (path == null || path.isBlank()) {
					continue;
				}
				String id = "mask_" + index++;
				masks.put(
					(keyPrefix + id).toLowerCase(Locale.ROOT),
					MaskDefinition.fromItemPath(id, path.trim())
				);
			}
			return;
		}

		if (!config.isConfigurationSection("masks")) {
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("masks");
		for (String key : section.getKeys(false)) {
			String mapKey = (keyPrefix + key).toLowerCase(Locale.ROOT);
			ConfigurationSection maskSection = section.getConfigurationSection(key);
			if (maskSection == null) {
				String path = section.getString(key);
				if (path != null && !path.isBlank()) {
					masks.put(mapKey, MaskDefinition.fromItemPath(key, path.trim()));
				}
				continue;
			}
			masks.put(mapKey, new MaskDefinition(key, maskSection));
		}
	}

	private static boolean matches(ItemStack item, String path) {
		if (path == null || path.isBlank()) {
			return false;
		}
		try {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
				return true;
			}
		} catch (RuntimeException ignored) {
			// TLibs or ItemsAdder can be unavailable; the ia tag still identifies a skinned mask.
		}
		return iaTagMatches(item, path);
	}

	private static boolean iaTagMatches(ItemStack item, String path) {
		if (IaItemPath.mythicIaTag(path) == null) {
			return false;
		}
		try {
			NBTItem nbt = NBTItem.get(item);
			if (nbt == null || !nbt.hasTag("ia")) {
				return false;
			}
			return IaItemPath.tagMatches(path, nbt.getString("ia"));
		} catch (RuntimeException ignored) {
			return false;
		}
	}
}
