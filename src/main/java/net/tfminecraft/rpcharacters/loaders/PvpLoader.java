package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;

public final class PvpLoader implements LoaderInterface {

	private static int startRadius = 16;
	private static int startWarnSeconds = 10;
	private static int startCountdownFrom = 5;
	private static int knockoutSeconds = 30;
	private static int blindnessAmplifier = 4;
	private static long freezePeriodTicks = 1L;

	private static String startWarning = "&ePvP will start in {seconds} seconds!";
	private static String countdown = "&c{count}";
	private static String startedTitle = "&cPVP STARTED";
	private static String lethal = "&aThis character is now in lethal PvP.";
	private static String nonlethal = "&aThis character is now in nonlethal PvP.";
	private static String usage = "&7Usage: /pvp start | lethal | nonlethal";
	private static String noCharacter = "&cYou need an active character to set PvP mode.";
	private static String current = "&7This character's PvP mode: {mode}";
	private static String playersOnly = "&cPlayers only.";

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		startRadius = Math.max(0, config.getInt("start-radius", 16));
		startWarnSeconds = Math.max(1, config.getInt("start-warn-seconds", 10));
		startCountdownFrom = Math.max(1, config.getInt("start-countdown-from", 5));
		knockoutSeconds = Math.max(1, config.getInt("knockout-seconds", 30));
		blindnessAmplifier = Math.max(0, config.getInt("blindness-amplifier", 4));
		freezePeriodTicks = Math.max(1L, config.getLong("freeze-period-ticks", 1L));

		startWarning = config.getString("messages.start-warning", startWarning);
		countdown = config.getString("messages.countdown", countdown);
		startedTitle = config.getString("messages.started-title", startedTitle);
		lethal = config.getString("messages.lethal", lethal);
		nonlethal = config.getString("messages.nonlethal", nonlethal);
		usage = config.getString("messages.usage", usage);
		noCharacter = config.getString("messages.no-character", noCharacter);
		current = config.getString("messages.current", current);
		playersOnly = config.getString("messages.players-only", playersOnly);
	}

	public static int getStartRadius() {
		return startRadius;
	}

	public static int getStartWarnSeconds() {
		return startWarnSeconds;
	}

	public static int getStartCountdownFrom() {
		return startCountdownFrom;
	}

	public static int getKnockoutSeconds() {
		return knockoutSeconds;
	}

	public static int getBlindnessAmplifier() {
		return blindnessAmplifier;
	}

	public static long getFreezePeriodTicks() {
		return freezePeriodTicks;
	}

	public static String getStartWarning() {
		return startWarning;
	}

	public static String getCountdown() {
		return countdown;
	}

	public static String getStartedTitle() {
		return startedTitle;
	}

	public static String getLethal() {
		return lethal;
	}

	public static String getNonlethal() {
		return nonlethal;
	}

	public static String getUsage() {
		return usage;
	}

	public static String getNoCharacter() {
		return noCharacter;
	}

	public static String getCurrent() {
		return current;
	}

	public static String getPlayersOnly() {
		return playersOnly;
	}
}
