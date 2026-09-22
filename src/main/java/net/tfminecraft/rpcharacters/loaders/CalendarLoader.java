package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;

public final class CalendarLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		Cache.calendarYearOffset = config.getInt("year-offset", Cache.calendarYearOffset);
		Cache.calendarEraSuffix = config.getString("era-suffix", Cache.calendarEraSuffix);

		if (config.isConfigurationSection("age")) {
			Cache.calendarAgeMinimum = Math.max(0, config.getInt("age.minimum", Cache.calendarAgeMinimum));
			Cache.calendarAgeUnsetLabel = config.getString("age.unset-label", Cache.calendarAgeUnsetLabel);
		}
	}
}
