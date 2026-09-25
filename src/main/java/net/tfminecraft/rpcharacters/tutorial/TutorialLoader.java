package net.tfminecraft.rpcharacters.tutorial;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;

public final class TutorialLoader implements LoaderInterface {

	private static final Map<String, List<String>> tutorials = new HashMap<>();

	@Override
	public void load(File file) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		tutorials.clear();
		ConfigurationSection section = config.getConfigurationSection("tutorials");
		if (section == null) {
			return;
		}
		for (String id : section.getKeys(false)) {
			List<String> lines = section.getStringList(id + ".lines");
			if (!lines.isEmpty()) {
				tutorials.put(id.toLowerCase(Locale.ROOT), List.copyOf(lines));
			}
		}
	}

	public static List<String> getLines(String id) {
		if (id == null) {
			return Collections.emptyList();
		}
		return tutorials.getOrDefault(id.toLowerCase(Locale.ROOT), Collections.emptyList());
	}

	public static boolean exists(String id) {
		return id != null && tutorials.containsKey(id.toLowerCase(Locale.ROOT));
	}

	public static List<String> getIds() {
		return tutorials.keySet().stream().sorted().toList();
	}
}
