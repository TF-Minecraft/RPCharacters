package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.chat.smart.MuffleRule;
import net.tfminecraft.rpcharacters.chat.smart.SmartMessageDebug;
import net.tfminecraft.rpcharacters.chat.smart.SmartMessageSettings;

public final class SmartMessageLoader implements LoaderInterface {

	private static final SmartMessageSettings settings = new SmartMessageSettings();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		settings.setEnabled(config.getBoolean("enabled", true));
		settings.setDebugMessages(config.getBoolean("debug-messages", false));
		settings.setGenericMuffle(config.getBoolean("generic-muffle", true));
		settings.setSenderHearsSelf(config.getBoolean("sender-hears-self", true));
		settings.setFadeStartPercent(config.getDouble("fade-start-percent", 0.6));
		settings.setMinAudible(config.getDouble("min-audible", 0.05));
		settings.setFullClarity(config.getDouble("full-clarity", 0.85));
		settings.setLowIntelligibilityThreshold(config.getDouble("low-intelligibility-threshold", 0.15));
		settings.setLowIntelligibilityPlaceholder(
				config.getString("low-intelligibility-placeholder", "&7*muffled voices*"));
		settings.setRayStep(Math.max(0.1, config.getDouble("ray-step", 0.5)));
		settings.setOcclusionMaxWeight(Math.max(0.1, config.getDouble("occlusion-max-weight", 3.0)));
		settings.setOcclusionCurve(Math.max(0.1, config.getDouble("occlusion-curve", 2.2)));

		ConfigurationSection anchorSection = config.getConfigurationSection("listener-anchor-search");
		if (anchorSection != null) {
			settings.setListenerAnchorSearch(anchorSection.getBoolean("enabled", true));
			settings.setListenerAnchorDiagonals(anchorSection.getBoolean("diagonals", true));
			settings.setListenerAnchorEarlyExitClearLos(anchorSection.getBoolean("early-exit-clear-los", true));
		} else {
			settings.setListenerAnchorSearch(true);
			settings.setListenerAnchorDiagonals(true);
			settings.setListenerAnchorEarlyExitClearLos(true);
		}

		ConfigurationSection occlusionSection = config.getConfigurationSection("sound-occlusion");
		if (occlusionSection != null) {
			settings.setCollisionFillThreshold(
					Math.max(0.0, Math.min(1.0, occlusionSection.getDouble("collision-fill-threshold", 0.30))));
		} else {
			settings.setCollisionFillThreshold(0.30);
		}

		loadBlockAttenuation(config.getConfigurationSection("block-attenuation"));
		loadMuffleRules(config.getMapList("muffle-rules"));
		loadCharismaHearing(config.getConfigurationSection("charisma-hearing"));
		loadAnonymousMuffledVoice(config.getConfigurationSection("anonymous-muffled-voice"));
		loadPlaceholderSuppression(config.getConfigurationSection("placeholder-suppression"));

		if (settings.isDebugMessages() && RPCharacters.plugin != null) {
			SmartMessageDebug.log("config-load", "debug-messages enabled");
		}
	}

	private static void loadPlaceholderSuppression(ConfigurationSection section) {
		if (section == null) {
			settings.setPlaceholderSuppressionEnabled(true);
			return;
		}
		settings.setPlaceholderSuppressionEnabled(section.getBoolean("enabled", true));
	}

	private static void loadAnonymousMuffledVoice(ConfigurationSection section) {
		if (section == null) {
			settings.setAnonymousMuffledVoiceEnabled(true);
			settings.setAnonymousMuffledMaxIntelligibility(0.65);
			settings.setAnonymousMuffledDisplay("???");
			return;
		}
		settings.setAnonymousMuffledVoiceEnabled(section.getBoolean("enabled", true));
		settings.setAnonymousMuffledMaxIntelligibility(
				Math.max(0.0, Math.min(1.0, section.getDouble("max-intelligibility", 0.65))));
		settings.setAnonymousMuffledDisplay(section.getString("anonymous-display", "???"));
	}

	private static void loadCharismaHearing(ConfigurationSection section) {
		if (section == null) {
			settings.setCharismaHearingEnabled(true);
			settings.setCharismaAttribute("charisma");
			settings.setCharismaMaxBoost(0.20);
			settings.setCharismaScale(40.0);
			settings.setCharismaPlaceholderMultiplier(0.5);
			return;
		}
		settings.setCharismaHearingEnabled(section.getBoolean("enabled", true));
		settings.setCharismaAttribute(section.getString("attribute", "charisma"));
		settings.setCharismaMaxBoost(section.getDouble("max-boost", 0.20));
		settings.setCharismaScale(Math.max(1.0, section.getDouble("scale", 40.0)));
		settings.setCharismaPlaceholderMultiplier(
				Math.max(0.0, Math.min(1.0, section.getDouble("placeholder-boost-multiplier", 0.5))));
	}

	private static void loadBlockAttenuation(ConfigurationSection section) {
		Map<Material, Double> attenuation = new HashMap<>();
		double defaultWeight = 0.28;
		if (section != null) {
			defaultWeight = section.getDouble("default", defaultWeight);
			for (String key : section.getKeys(false)) {
				if ("default".equalsIgnoreCase(key)) {
					continue;
				}
				Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
				if (material != null) {
					attenuation.put(material, section.getDouble(key));
				}
			}
		}
		settings.setDefaultBlockAttenuation(defaultWeight);
		settings.setBlockAttenuation(attenuation);
	}

	private static void loadMuffleRules(List<Map<?, ?>> rawRules) {
		List<MuffleRule> rules = new ArrayList<>();
		if (rawRules == null) {
			settings.setMuffleRules(rules);
			return;
		}
		for (Map<?, ?> raw : rawRules) {
			if (raw == null) {
				continue;
			}
			String replace = stringValue(raw.get("replace"));
			String to = stringValue(raw.get("to"));
			double min = doubleValue(raw.get("min-intelligibility"), 0.0);
			double max = doubleValue(raw.get("max-intelligibility"), 1.0);
			int percentage = (int) doubleValue(raw.get("percentage"), 0);
			if (!replace.isEmpty()) {
				rules.add(new MuffleRule(replace, to, min, max, percentage));
			}
		}
		settings.setMuffleRules(rules);
	}

	private static String stringValue(Object value) {
		return value != null ? value.toString() : "";
	}

	private static double doubleValue(Object value, double fallback) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value != null) {
			try {
				return Double.parseDouble(value.toString());
			} catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}

	public static SmartMessageSettings getSettings() {
		return settings;
	}
}
