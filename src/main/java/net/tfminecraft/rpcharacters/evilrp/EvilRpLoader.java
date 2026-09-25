package net.tfminecraft.rpcharacters.evilrp;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;

public final class EvilRpLoader implements LoaderInterface {

	private static int sessionMinutes = 30;

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		sessionMinutes = Math.max(1, config.getInt("session-minutes", 30));
	}

	public static int getSessionMinutes() {
		return sessionMinutes;
	}

	public static long getSessionMs() {
		return sessionMinutes * 60_000L;
	}
}
