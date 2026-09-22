package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleDebug;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleSettings;

public final class SpeechBubbleLoader implements LoaderInterface {

	private static final SpeechBubbleSettings settings = new SpeechBubbleSettings();

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

		ConfigurationSection display = config.getConfigurationSection("display");
		if (display != null) {
			settings.setScale((float) display.getDouble("scale", settings.getScale()));
			settings.setMaxCharactersPerLine(display.getInt("max-characters-per-line", settings.getMaxCharactersPerLine()));
			settings.setLineSpacing(display.getDouble("line-spacing", settings.getLineSpacing()));
			settings.setFirstLineOffset(display.getDouble("first-line-offset", settings.getFirstLineOffset()));
			settings.setHeightAboveHead(display.getDouble("height-above-head", settings.getHeightAboveHead()));
			settings.setMaxStackedUtterances(display.getInt("max-stacked-utterances", settings.getMaxStackedUtterances()));
			settings.setUtteranceTimeoutSeconds(display.getInt("utterance-timeout-seconds", settings.getUtteranceTimeoutSeconds()));
			settings.setBobAmplitude(display.getDouble("bob-amplitude", settings.getBobAmplitude()));
			settings.setBobPeriodTicks(display.getInt("bob-period-ticks", settings.getBobPeriodTicks()));
			double lerpFactor = display.getDouble("follow-lerp-factor", settings.getFollowLerpFactor());
			settings.setFollowLerpFactor(Math.max(0.0, Math.min(1.0, lerpFactor)));
		}

		if (settings.isDebugMessages() && RPCharacters.plugin != null) {
			SpeechBubbleDebug.log("config-load",
					"enabled=" + settings.isEnabled()
							+ ", scale=" + settings.getScale()
							+ ", heightAboveHead=" + settings.getHeightAboveHead()
							+ ", maxLineLen=" + settings.getMaxCharactersPerLine());
		}
	}

	public static SpeechBubbleSettings getSettings() {
		return settings;
	}
}
