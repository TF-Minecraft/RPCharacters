package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;

public final class RollLoader implements LoaderInterface {

	private static final Map<String, Map<Integer, Integer>> attributeModifiers = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		attributeModifiers.clear();

		Cache.rollPermission = config.getString("use-perm", Cache.rollPermission);
		Cache.rollAltPermission = config.getString("alt-perm", Cache.rollAltPermission);

		if (config.isConfigurationSection("default")) {
			Cache.rollDefaultMin = config.getInt("default.min", Cache.rollDefaultMin);
			Cache.rollDefaultMax = config.getInt("default.max", Cache.rollDefaultMax);
		}
		if (config.isConfigurationSection("alternative")) {
			Cache.rollAltMin = config.getInt("alternative.min", Cache.rollAltMin);
			Cache.rollAltMax = config.getInt("alternative.max", Cache.rollAltMax);
		}
		if (config.isConfigurationSection("d20")) {
			Cache.rollD20Min = config.getInt("d20.min", Cache.rollD20Min);
			Cache.rollD20Max = config.getInt("d20.max", Cache.rollD20Max);
		}
		if (config.isConfigurationSection("broadcast")) {
			Cache.rollBroadcastText = config.getString("broadcast.text", Cache.rollBroadcastText);
			Cache.rollBroadcastRange = config.getInt("broadcast.range", Cache.rollBroadcastRange);
		}

		loadAttributeModifiers(config);
	}

	private static void loadAttributeModifiers(FileConfiguration config) {
		if (!config.isConfigurationSection("attribute-modifiers")) {
			return;
		}
		ConfigurationSection root = config.getConfigurationSection("attribute-modifiers");
		for (String attributeId : root.getKeys(false)) {
			ConfigurationSection section = root.getConfigurationSection(attributeId);
			if (section == null) {
				continue;
			}
			Map<Integer, Integer> table = new HashMap<>();
			for (String key : section.getKeys(false)) {
				try {
					int attributeValue = Integer.parseInt(key);
					table.put(attributeValue, section.getInt(key));
				} catch (NumberFormatException ignored) {
				}
			}
			attributeModifiers.put(attributeId.toLowerCase(Locale.ROOT), table);
		}
	}

	public static boolean isKnownAttribute(String attributeId) {
		if (attributeId == null || attributeId.isBlank()) {
			return false;
		}
		return attributeModifiers.containsKey(attributeId.toLowerCase(Locale.ROOT));
	}

	public static Set<String> getAttributeIds() {
		return Collections.unmodifiableSet(attributeModifiers.keySet());
	}

	public static int getModifier(String attributeId, int attributeValue) {
		if (attributeId == null) {
			return 0;
		}
		Map<Integer, Integer> table = attributeModifiers.get(attributeId.toLowerCase(Locale.ROOT));
		if (table == null) {
			return 0;
		}
		Integer modifier = table.get(clampAttributeValue(attributeValue));
		return modifier != null ? modifier : 0;
	}

	private static int clampAttributeValue(int value) {
		return Math.max(0, Math.min(20, value));
	}
}
