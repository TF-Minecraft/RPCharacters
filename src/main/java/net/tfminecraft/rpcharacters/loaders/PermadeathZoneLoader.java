package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class PermadeathZoneLoader implements LoaderInterface {

	private static File configFile;
	private static int chancePerInjury = 10;
	private static int zoneCheckCooldownMs = 100;
	private static List<String> tutorialLines = new ArrayList<>();
	private static final Map<String, PermadeathZoneDefinition> zones = new HashMap<>();
	private static Location worldSpawn;

	@Override
	public void load(File file) {
		configFile = file;
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		chancePerInjury = Math.max(0, config.getInt("permadeath-chance-per-injury", 10));
		zoneCheckCooldownMs = Math.max(0, config.getInt("zone-check-cooldown-ms", 100));
		tutorialLines = new ArrayList<>(config.getStringList("tutorial"));
		worldSpawn = parseWorldSpawn(config.getConfigurationSection("world-spawn"));

		zones.clear();
		if (config.isConfigurationSection("permadeath-zones")) {
			ConfigurationSection section = config.getConfigurationSection("permadeath-zones");
			for (String key : section.getKeys(false)) {
				ConfigurationSection zoneSection = section.getConfigurationSection(key);
				if (zoneSection == null) {
					continue;
				}
				String name = zoneSection.getString("name");
				if (name == null || name.isBlank()) {
					name = stripLegacyDisplayName(zoneSection.getString("display-name", key));
				}
				zones.put(key.toLowerCase(Locale.ROOT), new PermadeathZoneDefinition(key, name));
			}
		}
	}

	private static Location parseWorldSpawn(ConfigurationSection section) {
		if (section == null) {
			return null;
		}
		String worldName = section.getString("world");
		if (worldName == null || worldName.isBlank()) {
			return null;
		}
		World world = Bukkit.getWorld(worldName);
		if (world == null) {
			return null;
		}
		double x = section.getDouble("x");
		double y = section.getDouble("y");
		double z = section.getDouble("z");
		float yaw = (float) section.getDouble("yaw");
		float pitch = (float) section.getDouble("pitch");
		return new Location(world, x, y, z, yaw, pitch);
	}

	public static int getChancePerInjury() {
		return chancePerInjury;
	}

	public static int getZoneCheckCooldownMs() {
		return zoneCheckCooldownMs;
	}

	public static List<String> getTutorialLines() {
		return Collections.unmodifiableList(tutorialLines);
	}

	public static PermadeathZoneDefinition getZone(String regionId) {
		if (regionId == null) {
			return null;
		}
		return zones.get(regionId.toLowerCase(Locale.ROOT));
	}

	public static Map<String, PermadeathZoneDefinition> getZones() {
		return Collections.unmodifiableMap(zones);
	}

	public static Location getWorldSpawn() {
		return worldSpawn;
	}

	public static void saveWorldSpawn(Location location) {
		if (configFile == null) {
			return;
		}
		FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
		if (location == null || location.getWorld() == null) {
			config.set("world-spawn", null);
		} else {
			ConfigurationSection section = config.createSection("world-spawn");
			section.set("world", location.getWorld().getName());
			section.set("x", location.getX());
			section.set("y", location.getY());
			section.set("z", location.getZ());
			section.set("yaw", location.getYaw());
			section.set("pitch", location.getPitch());
			config.set("world-spawn", section);
		}
		try {
			config.save(configFile);
		} catch (IOException e) {
			RPCharacters.plugin.getLogger().severe("Failed to save world spawn to zones.yml: " + e.getMessage());
		}
		worldSpawn = location;
	}

	private static String stripLegacyDisplayName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			return displayName;
		}
		String stripped = displayName;
		for (String prefix : List.of("Now entering ", "Now leaving ")) {
			if (stripped.regionMatches(true, 0, prefix, 0, prefix.length())) {
				stripped = stripped.substring(prefix.length()).trim();
				break;
			}
		}
		return stripped.isEmpty() ? displayName : stripped;
	}
}
