package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;

/** Settings and messages for {@code /duel}, read from duel.yml. */
public final class DuelLoader implements LoaderInterface {

	private static final Map<String, String> DEFAULT_MESSAGES = defaultMessages();

	private static int challengeRange = 16;
	private static int challengeSeconds = 30;
	private static int countdownSeconds = 3;
	private static int leashRange = 32;
	private static int maxMinutes = 10;
	private static int announceRadius = 24;
	private static final Map<String, String> messages = new LinkedHashMap<>(DEFAULT_MESSAGES);

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		challengeRange = Math.max(1, config.getInt("challenge-range", 16));
		challengeSeconds = Math.max(1, config.getInt("challenge-seconds", 30));
		countdownSeconds = Math.max(0, config.getInt("countdown-seconds", 3));
		leashRange = Math.max(1, config.getInt("leash-range", 32));
		maxMinutes = Math.max(0, config.getInt("max-minutes", 10));
		announceRadius = Math.max(0, config.getInt("announce-radius", 24));
		messages.clear();
		for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
			messages.put(entry.getKey(), config.getString("messages." + entry.getKey(), entry.getValue()));
		}
	}

	public static int getChallengeRange() {
		return challengeRange;
	}

	public static long getChallengeMs() {
		return challengeSeconds * 1000L;
	}

	public static int getChallengeSeconds() {
		return challengeSeconds;
	}

	public static int getCountdownSeconds() {
		return countdownSeconds;
	}

	public static int getLeashRange() {
		return leashRange;
	}

	/** 0 when duels have no time limit. */
	public static long getMaxMs() {
		return maxMinutes * 60_000L;
	}

	/** 0 when nobody else is told about duels. */
	public static int getAnnounceRadius() {
		return announceRadius;
	}

	/** The configured text for a message key, or the key itself if it is unknown. */
	public static String message(String key) {
		return messages.getOrDefault(key, key);
	}

	private static Map<String, String> defaultMessages() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("usage", "&7Usage: /duel [player] | accept [player] | decline [player] | cancel | yield");
		m.put("players-only", "&cPlayers only.");
		m.put("no-target", "&cLook at the player you want to duel, or name them: /duel <player>");
		m.put("not-found", "&cNobody called {name} is online.");
		m.put("self", "&cYou can't duel yourself.");
		m.put("too-far", "&c{name} is too far away. Stand within {range} blocks to challenge them.");
		m.put("you-busy", "&cYou can't duel right now: you're {why}.");
		m.put("other-busy", "&c{name} can't duel right now: they're {why}.");
		m.put("why-duelling", "already duelling");
		m.put("why-fight", "in a PvP fight");
		m.put("why-battle", "in a battle");
		m.put("why-down", "knocked out");
		m.put("why-character", "without an active character");
		m.put("why-gamemode", "not in survival");
		m.put("challenge-sent", "&eYou challenged {name} to a duel. &7They have {seconds} seconds to accept.");
		m.put("challenge-received", "&e{name} challenges you to a duel. &7Nobody dies or loses anything: what the duel takes is given back.");
		m.put("challenge-replaced", "&7Your earlier challenge to {name} was withdrawn.");
		m.put("challenge-expired-challenger", "&7{name} didn't answer your duel challenge.");
		m.put("challenge-expired-target", "&7The duel challenge from {name} has expired.");
		m.put("no-challenge", "&cYou have no duel challenge to answer.");
		m.put("declined-challenger", "&7{name} declined your duel.");
		m.put("declined-target", "&7You declined the duel with {name}.");
		m.put("withdrawn-challenger", "&7You withdrew your duel challenge to {name}.");
		m.put("withdrawn-target", "&7{name} withdrew their duel challenge.");
		m.put("nothing-to-cancel", "&cYou have no open duel challenge.");
		m.put("not-duelling", "&cYou aren't in a duel.");
		m.put("countdown", "&6{count}");
		m.put("fight-title", "&6&lDUEL");
		m.put("started", "&6Your duel with {name} has begun. &7Only their hits count, and you get back what they take when it ends. &f/duel yield &7gives up.");
		m.put("announce-start", "&7{a} and {b} begin a duel.");
		m.put("won", "&6You won the duel against {name}.");
		m.put("lost", "&7{name} won the duel. Your wounds from it are mended.");
		m.put("yield-winner", "&6{name} yielded. You won the duel.");
		m.put("yield-loser", "&7You yielded the duel to {name}. Your wounds from it are mended.");
		m.put("announce-win", "&7{winner} won a duel against {loser}.");
		m.put("announce-yield", "&7{loser} yielded a duel to {winner}.");
		m.put("draw", "&7Your duel with {name} is over: {reason}. Your wounds from it are mended.");
		m.put("reason-timeout", "time ran out");
		m.put("reason-apart", "you moved too far apart");
		m.put("reason-left", "{name} left");
		m.put("reason-fight", "a real fight was called");
		m.put("reason-battle", "a battle started");
		m.put("reason-gamemode", "{name} is no longer in survival");
		m.put("reason-died", "{name} died");
		m.put("reason-stopped", "the server is restarting");
		m.put("interrupted", "&cYour duel with {name} was interrupted: {victim} got into another fight. &7Only the damage from the duel was given back.");
		return m;
	}
}
