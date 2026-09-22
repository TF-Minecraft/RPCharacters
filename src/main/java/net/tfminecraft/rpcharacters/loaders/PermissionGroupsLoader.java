package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;

public final class PermissionGroupsLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		Map<String, Integer> defaults = new HashMap<>();
		if (config.isConfigurationSection("defaults")) {
			ConfigurationSection section = config.getConfigurationSection("defaults");
			for (String key : section.getKeys(false)) {
				defaults.put(key, section.getInt(key));
			}
		}
		Cache.permissionGroupDefaults = defaults;

		List<PermissionGroupDefinition> groups = new ArrayList<>();
		if (config.isConfigurationSection("groups")) {
			ConfigurationSection groupsSection = config.getConfigurationSection("groups");
			int autoTier = 0;
			for (String id : groupsSection.getKeys(false)) {
				ConfigurationSection groupSection = groupsSection.getConfigurationSection(id);
				if (groupSection == null) {
					continue;
				}
				String permission = groupSection.getString("permission", "");
				String displayName = groupSection.getString("display-name", id);
				int tier = groupSection.contains("tier")
						? groupSection.getInt("tier")
						: autoTier++;
				boolean visible = groupSection.getBoolean("visible", true);
				Map<String, Integer> perks = new HashMap<>();
				for (String key : groupSection.getKeys(false)) {
					if ("permission".equals(key) || "display-name".equals(key)
							|| "tier".equals(key) || "visible".equals(key)) {
						continue;
					}
					perks.put(key, groupSection.getInt(key));
				}
				groups.add(new PermissionGroupDefinition(id, permission, displayName, tier, visible, perks));
			}
			groups.sort(java.util.Comparator.comparingInt(PermissionGroupDefinition::getTier));
		}
		Cache.permissionGroups = groups;
	}
}
