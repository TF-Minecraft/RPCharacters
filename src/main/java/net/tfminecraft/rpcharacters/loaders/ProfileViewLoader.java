package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;

public final class ProfileViewLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		Cache.profilePermission = config.getString("permission", Cache.profilePermission);
		if (config.isConfigurationSection("view")) {
			Cache.profileRequireSneak = config.getBoolean("view.require-sneak", Cache.profileRequireSneak);
			Cache.profileRequireEmptyHand = config.getBoolean("view.require-empty-hand", Cache.profileRequireEmptyHand);
			Cache.profileViewCooldownSeconds = Math.max(0, config.getInt("view.cooldown-seconds",
					Cache.profileViewCooldownSeconds));
		}

		List<String> format = config.getStringList("format");
		Cache.profileFormatLines = format != null ? new ArrayList<>(format) : new ArrayList<>();
	}
}
