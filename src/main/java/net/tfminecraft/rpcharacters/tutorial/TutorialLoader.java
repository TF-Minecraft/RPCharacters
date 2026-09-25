package net.tfminecraft.rpcharacters.tutorial;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.rpcharacters.RPCharacters;
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
			// Keep the tutorials from the last good load.
			return;
		}

		tutorials.clear();
		addMissing(tutorials, config);
		// Servers keep their old tutorials.yml, so tutorials added in later versions come from the jar.
		addMissing(tutorials, bundled());
	}

	/** Adds each tutorial in {@code config} that {@code into} doesn't have yet. */
	static void addMissing(Map<String, List<String>> into, FileConfiguration config) {
		ConfigurationSection section = config != null ? config.getConfigurationSection("tutorials") : null;
		if (section == null) {
			return;
		}
		for (String id : section.getKeys(false)) {
			List<String> lines = section.getStringList(id + ".lines");
			if (!lines.isEmpty()) {
				into.putIfAbsent(id.toLowerCase(Locale.ROOT), List.copyOf(lines));
			}
		}
	}

	private static FileConfiguration bundled() {
		if (RPCharacters.plugin == null) {
			return null;
		}
		InputStream stream = RPCharacters.plugin.getResource("tutorials.yml");
		if (stream == null) {
			return null;
		}
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return YamlConfiguration.loadConfiguration(reader);
		} catch (IOException e) {
			return null;
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
