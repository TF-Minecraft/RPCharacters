package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.playerlist.PlayerListSettings;

public final class PlayerListLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		PlayerListSettings defaults = PlayerListSettings.defaults();
		Map<String, String> tags = new LinkedHashMap<>();
		ConfigurationSection ranks = config.getConfigurationSection("ranks");
		if (ranks != null) {
			for (String group : ranks.getKeys(false)) {
				tags.put(group.toLowerCase(Locale.ROOT), ranks.getString(group, ""));
			}
		}
		Cache.playerList = new PlayerListSettings(
				config.getString("permission", defaults.permission()),
				config.getBoolean("quick-action.enabled", defaults.quickAction()),
				config.getBoolean("portrait", defaults.portrait()),
				config.getString("title", defaults.title()),
				config.getString("header", defaults.header()),
				config.getString("quick-action.label", defaults.quickActionLabel()),
				tags);
	}
}
