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
	private static int knockoutBlindnessSeconds = 60;
	private static int lethalConfirmSeconds = 30;
	private static long freezePeriodTicks = 1L;
	private static int decisionSeconds = 300;
	private static int sameTargetCooldownHours = 24;
	private static boolean strikeDecayEnabled = false;
	private static int strikeDecayDays = 14;
	private static int helmetDonSeconds = 1;
	private static int chestplateDonSeconds = 4;
	private static int leggingsDonSeconds = 3;
	private static int bootsDonSeconds = 2;
	private static int donSlownessAmplifier = 1;

	private static String startWarning = "&ePvP will start in {seconds} seconds! &7Lethal kills in this fight give strikes, and a knocked out player's fate is up to the winner.";
	private static String countdown = "&c{count}";
	private static String startedTitle = "&cPVP STARTED";
	private static String situationDuration = "&eThis PvP situation lasts {minutes} minutes. &7After that, a new RP interaction is needed.";
	private static String endedTitle = "&c&lPVP SITUATION ENDED";
	private static String endedSubtitle = "&eA new RP interaction is needed";
	private static String startCancelled = "&7The PvP start was cancelled.";
	private static String noSituation = "&cYou have no PvP situation to end.";
	private static String lethal = "&cThis character is now in lethal PvP. &7It goes back to nonlethal when you log out, switch character or run &f/pvp nonlethal&7.";
	private static String nonlethal = "&aThis character is now in nonlethal PvP.";
	private static String lethalWarning = "&c&l⚠ Lethal PvP\n"
			+ "&7In a &f/pvp start &7fight, anyone you kill gets a strike at once. You decided not to spare anyone when you turned lethal on, so you can't spare, wound or maim them.\n"
			+ "&7If it's their last strike, or they're in an evil RP session, their character dies and only you can open their grave.\n"
			+ "&7Players you kill outside &f/pvp start &7fights get no strike.\n"
			+ "&7Lethal lasts until you log out, switch character or run &f/pvp nonlethal&7.";
	private static String lethalConfirmButton = "&c&l[Confirm]";
	private static String lethalConfirmHover = "&7Turn on lethal PvP for this character";
	private static String lethalConfirmPrompt = "&7Click &c[Confirm] &7or run &f/pvp lethal confirm &7within {seconds} seconds.";
	private static String lethalConfirmNone = "&cRun &f/pvp lethal &cfirst, then confirm within {seconds} seconds.";
	private static String alreadyLethal = "&7This character is already in lethal PvP.";
	private static String downedBlocked = "&cYou're knocked out. You can still talk, but you can't do that.";
	private static String autoStrikeVictim = "&c{killer} &cwas in lethal PvP, so their kill gave you a strike.";
	private static String usage = "&7Usage: /pvp start | end | lethal | nonlethal";
	private static String noCharacter = "&cYou need an active character to set PvP mode.";
	private static String current = "&7This character's PvP mode: {mode}";
	private static String playersOnly = "&cPlayers only.";
	private static String donProgress = "&eFastening your {piece} &7[{bar}&7] &f{seconds}s{queued}";
	private static String donQueued = " &7(+{count} queued)";
	private static String donDone = "&aYou've put on your {piece}.";
	private static String donInterrupted = "&cYou stopped fastening your {piece}.";
	private static String donMissing = "&cYou no longer have the {piece} you were putting on.";

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
		knockoutBlindnessSeconds = Math.max(0, config.getInt("knockout-blindness-seconds", 60));
		lethalConfirmSeconds = Math.max(1, config.getInt("lethal-confirm-seconds", 30));
		freezePeriodTicks = Math.max(1L, config.getLong("freeze-period-ticks", 1L));
		decisionSeconds = Math.max(1, config.getInt("strikes.decision-seconds", 300));
		sameTargetCooldownHours = Math.max(0, config.getInt("strikes.same-target-cooldown-hours", 24));
		strikeDecayEnabled = config.getBoolean("strikes.decay.enabled", false);
		strikeDecayDays = Math.max(1, config.getInt("strikes.decay.days", 14));
		helmetDonSeconds = Math.max(0, config.getInt("armour.don-seconds.helmet", 1));
		chestplateDonSeconds = Math.max(0, config.getInt("armour.don-seconds.chestplate", 4));
		leggingsDonSeconds = Math.max(0, config.getInt("armour.don-seconds.leggings", 3));
		bootsDonSeconds = Math.max(0, config.getInt("armour.don-seconds.boots", 2));
		donSlownessAmplifier = Math.max(-1, config.getInt("armour.slowness-amplifier", 1));

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
		lethalWarning = config.getString("messages.lethal-warning", lethalWarning);
		lethalConfirmButton = config.getString("messages.lethal-confirm-button", lethalConfirmButton);
		lethalConfirmHover = config.getString("messages.lethal-confirm-hover", lethalConfirmHover);
		lethalConfirmPrompt = config.getString("messages.lethal-confirm-prompt", lethalConfirmPrompt);
		lethalConfirmNone = config.getString("messages.lethal-confirm-none", lethalConfirmNone);
		alreadyLethal = config.getString("messages.already-lethal", alreadyLethal);
		downedBlocked = config.getString("messages.downed-blocked", downedBlocked);
		autoStrikeVictim = config.getString("messages.auto-strike-victim", autoStrikeVictim);
		usage = config.getString("messages.usage", usage);
		noCharacter = config.getString("messages.no-character", noCharacter);
		current = config.getString("messages.current", current);
		playersOnly = config.getString("messages.players-only", playersOnly);
		donProgress = config.getString("messages.don-progress", donProgress);
		donQueued = config.getString("messages.don-queued", donQueued);
		donDone = config.getString("messages.don-done", donDone);
		donInterrupted = config.getString("messages.don-interrupted", donInterrupted);
		donMissing = config.getString("messages.don-missing", donMissing);
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

	/** How long a knockout blinds, counted from when the player goes down. 0 for no blindness. */
	public static int getKnockoutBlindnessSeconds() {
		return knockoutBlindnessSeconds;
	}

	public static int getLethalConfirmSeconds() {
		return lethalConfirmSeconds;
	}

	public static long getLethalConfirmMs() {
		return lethalConfirmSeconds * 1000L;
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

	public static String getLethalWarning() {
		return lethalWarning;
	}

	public static String getLethalConfirmButton() {
		return lethalConfirmButton;
	}

	public static String getLethalConfirmHover() {
		return lethalConfirmHover;
	}

	public static String getLethalConfirmPrompt() {
		return lethalConfirmPrompt;
	}

	public static String getLethalConfirmNone() {
		return lethalConfirmNone;
	}

	public static String getAlreadyLethal() {
		return alreadyLethal;
	}

	public static String getDownedBlocked() {
		return downedBlocked;
	}

	public static String getAutoStrikeVictim() {
		return autoStrikeVictim;
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
}
