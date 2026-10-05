package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.armour.ArmorType;
import net.tfminecraft.tlibs.interfaces.LoaderInterface;

public final class PvpLoader implements LoaderInterface {

	private static int startRadius = 16;
	private static int startWarnSeconds = 10;
	private static int startCountdownFrom = 5;
	private static int startActiveMinutes = 15;
	private static int knockoutSeconds = 30;
	private static int blindnessAmplifier = 4;
	private static long freezePeriodTicks = 1L;
	private static int decisionSeconds = 30;
	private static int sameTargetCooldownHours = 24;
	private static boolean strikeDecayEnabled = false;
	private static int strikeDecayDays = 14;
	private static int helmetDonSeconds = 1;
	private static int chestplateDonSeconds = 20;
	private static int leggingsDonSeconds = 12;
	private static int bootsDonSeconds = 8;
	private static int donSlownessAmplifier = 1;
	private static int recentArmourSeconds = 180;

	private static String startWarning = "&ePvP will start in {seconds} seconds! &7Deaths in this fight can cost strikes.";
	private static String countdown = "&c{count}";
	private static String startedTitle = "&cPVP STARTED";
	private static String situationDuration = "&eThis PvP situation lasts {minutes} minutes. &7After that, a new RP interaction is needed.";
	private static String endedTitle = "&c&lPVP SITUATION ENDED";
	private static String endedSubtitle = "&eA new RP interaction is needed";
	private static String startCancelled = "&7The PvP start was cancelled.";
	private static String noSituation = "&cYou have no PvP situation to end.";
	private static String lethal = "&aThis character is now in lethal PvP.";
	private static String nonlethal = "&aThis character is now in nonlethal PvP.";
	private static String usage = "&7Usage: /pvp start | end | lethal | nonlethal";
	private static String noCharacter = "&cYou need an active character to set PvP mode.";
	private static String current = "&7This character's PvP mode: {mode}";
	private static String playersOnly = "&cPlayers only.";
	private static String armourLocked = "&cYou can't put armour on during a PvP situation.";
	private static String donProgress = "&eFastening your {piece} &7[{bar}&7] &f{seconds}s{queued}";
	private static String donQueued = " &7(+{count} queued)";
	private static String donDone = "&aYou've put on your {piece}.";
	private static String donInterrupted = "&cYou stopped fastening your {piece}.";
	private static String donMissing = "&cYou no longer have the {piece} you were putting on.";
	private static String armourStripped = "&cYou hadn't finished fastening your {pieces}. It's back in your inventory.";
	private static String armourStrippedOthers = "&7{name} hadn't finished fastening their armour.";

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
		startActiveMinutes = Math.max(0, config.getInt("start-active-minutes", 15));
		knockoutSeconds = Math.max(1, config.getInt("knockout-seconds", 30));
		blindnessAmplifier = Math.max(0, config.getInt("blindness-amplifier", 4));
		freezePeriodTicks = Math.max(1L, config.getLong("freeze-period-ticks", 1L));
		decisionSeconds = Math.max(1, config.getInt("strikes.decision-seconds", 30));
		sameTargetCooldownHours = Math.max(0, config.getInt("strikes.same-target-cooldown-hours", 24));
		strikeDecayEnabled = config.getBoolean("strikes.decay.enabled", false);
		strikeDecayDays = Math.max(1, config.getInt("strikes.decay.days", 14));
		helmetDonSeconds = Math.max(0, config.getInt("armour.don-seconds.helmet", 1));
		chestplateDonSeconds = Math.max(0, config.getInt("armour.don-seconds.chestplate", 20));
		leggingsDonSeconds = Math.max(0, config.getInt("armour.don-seconds.leggings", 12));
		bootsDonSeconds = Math.max(0, config.getInt("armour.don-seconds.boots", 8));
		donSlownessAmplifier = Math.max(-1, config.getInt("armour.slowness-amplifier", 1));
		recentArmourSeconds = Math.max(0, config.getInt("armour.recent-seconds", 180));

		startWarning = config.getString("messages.start-warning", startWarning);
		countdown = config.getString("messages.countdown", countdown);
		startedTitle = config.getString("messages.started-title", startedTitle);
		situationDuration = config.getString("messages.situation-duration", situationDuration);
		endedTitle = config.getString("messages.ended-title", endedTitle);
		endedSubtitle = config.getString("messages.ended-subtitle", endedSubtitle);
		startCancelled = config.getString("messages.start-cancelled", startCancelled);
		noSituation = config.getString("messages.no-situation", noSituation);
		lethal = config.getString("messages.lethal", lethal);
		nonlethal = config.getString("messages.nonlethal", nonlethal);
		usage = config.getString("messages.usage", usage);
		noCharacter = config.getString("messages.no-character", noCharacter);
		current = config.getString("messages.current", current);
		playersOnly = config.getString("messages.players-only", playersOnly);
		armourLocked = config.getString("messages.armour-locked", armourLocked);
		donProgress = config.getString("messages.don-progress", donProgress);
		donQueued = config.getString("messages.don-queued", donQueued);
		donDone = config.getString("messages.don-done", donDone);
		donInterrupted = config.getString("messages.don-interrupted", donInterrupted);
		donMissing = config.getString("messages.don-missing", donMissing);
		armourStripped = config.getString("messages.armour-stripped", armourStripped);
		armourStrippedOthers = config.getString("messages.armour-stripped-others", armourStrippedOthers);
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

	public static int getStartActiveMinutes() {
		return startActiveMinutes;
	}

	public static long getStartActiveMs() {
		return startActiveMinutes * 60_000L;
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

	public static int getDecisionSeconds() {
		return decisionSeconds;
	}

	public static long getDecisionMs() {
		return decisionSeconds * 1000L;
	}

	public static int getSameTargetCooldownHours() {
		return sameTargetCooldownHours;
	}

	/** 0 when the cooldown is disabled. */
	public static long getSameTargetCooldownMs() {
		return sameTargetCooldownHours * 3_600_000L;
	}

	public static boolean isStrikeDecayEnabled() {
		return strikeDecayEnabled;
	}

	public static long getStrikeDecayMs() {
		return strikeDecayDays * 86_400_000L;
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

	public static String getSituationDuration() {
		return situationDuration;
	}

	public static String getEndedTitle() {
		return endedTitle;
	}

	public static String getEndedSubtitle() {
		return endedSubtitle;
	}

	public static String getStartCancelled() {
		return startCancelled;
	}

	public static String getNoSituation() {
		return noSituation;
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

	/** How long putting on a piece in this slot takes. 0 equips it at once. */
	public static long getDonMs(ArmorType type) {
		int seconds = switch (type) {
			case HELMET -> helmetDonSeconds;
			case CHESTPLATE -> chestplateDonSeconds;
			case LEGGINGS -> leggingsDonSeconds;
			case BOOTS -> bootsDonSeconds;
		};
		return seconds * 1000L;
	}

	/** -1 when fastening armour does not slow the player. */
	public static int getDonSlownessAmplifier() {
		return donSlownessAmplifier;
	}

	/** 0 when /pvp start takes nothing off. */
	public static long getRecentArmourMs() {
		return recentArmourSeconds * 1000L;
	}

	public static String getArmourLocked() {
		return armourLocked;
	}

	public static String getDonProgress() {
		return donProgress;
	}

	public static String getDonQueued() {
		return donQueued;
	}

	public static String getDonDone() {
		return donDone;
	}

	public static String getDonInterrupted() {
		return donInterrupted;
	}

	public static String getDonMissing() {
		return donMissing;
	}

	public static String getArmourStripped() {
		return armourStripped;
	}

	public static String getArmourStrippedOthers() {
		return armourStrippedOthers;
	}
}
