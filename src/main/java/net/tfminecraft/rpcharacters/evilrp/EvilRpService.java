package net.tfminecraft.rpcharacters.evilrp;

import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.pvp.PvpStrikeService;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

/**
 * Evil RP sessions and strikes. Any evil play (lockpicking, robbing, pickpocketing,
 * looting a locked grave) starts or resets a timed session on the active character.
 * Strikes come from {@code /pvp start} fights; while a session runs, any strike kills.
 */
public final class EvilRpService {

	/** Only players online when their session ran out are told; stale timestamps clear quietly. */
	private static final long END_NOTICE_WINDOW_MS = 5_000L;
	private static final int DECAY_CHECK_TICKS = 60;

	private static BukkitTask tickTask;
	private static int ticksSinceDecayCheck;

	private EvilRpService() {
	}

	public static void start() {
		shutdown();
		tickTask = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin, EvilRpService::tick, 20L, 20L);
	}

	public static void shutdown() {
		if (tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
	}
	/**
	 * Records an evil play by the player's active character, starting a session or resetting
	 * it to full length. Returns false when there is no living active character.
	 */
	public static boolean recordPlay(Player player) {
		RPCharacter character = activeCharacter(player);
		if (character == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		boolean continuing = isInSession(character.getEvilRpSessionEndsAtMs(), now);
		character.setEvilRpSessionEndsAtMs(now + EvilRpLoader.getSessionMs());
		RPCharacters.getPlayerManager().savePlayer(player);

		// Chat rather than the action bar, which the MMOCore HUD redraws every tick.
		int minutes = EvilRpLoader.getSessionMinutes();
		if (continuing) {
			RPTexts.send(player, RPTexts.MUTED + "Evil RP session reset to " + RPTexts.WARN + minutes + " minutes"
					+ RPTexts.MUTED + ".");
			return true;
		}
		boolean shown = TutorialService.show(player, TutorialService.EVIL_RP, Map.of(
				"minutes", String.valueOf(minutes),
				"strikes", String.valueOf(character.getEvilRpStrikes())));
		if (!shown) {
			RPTexts.send(player, RPTexts.ERROR + "Evil RP session started: " + RPTexts.WARN + minutes + " minutes"
					+ RPTexts.MUTED + " (" + character.getEvilRpStrikes() + "/" + StrikeOutcome.MAX_STRIKES
					+ " strikes).");
		}
		return true;
	}

	public static boolean isInSession(RPCharacter character) {
		return character != null && isInSession(character.getEvilRpSessionEndsAtMs(), System.currentTimeMillis());
	}

	public static boolean isInSession(Player player) {
		return isInSession(activeCharacter(player));
	}

	static boolean isInSession(long endsAtMs, long nowMs) {
		return endsAtMs > nowMs;
	}

	public static long remainingMs(RPCharacter character) {
		if (character == null) {
			return 0L;
		}
		return Math.max(0L, character.getEvilRpSessionEndsAtMs() - System.currentTimeMillis());
	}

	/** Switching characters mid-session, or while a killer decides, would dodge the strike. */
	public static boolean blocksCharacterSwitch(Player player) {
		return isInSession(player) || PvpStrikeService.hasPendingDecision(player);
	}

	public static void sendSwitchBlocked(Player player) {
		if (!isInSession(player) && PvpStrikeService.hasPendingDecision(player)) {
			RPTexts.send(player, RPTexts.ERROR + "You can't switch characters while your killer decides whether to strike you.");
			return;
		}
		RPTexts.send(player, RPTexts.ERROR + "You can't switch characters during an evil RP session"
				+ (isInSession(player)
						? " (" + formatRemaining(remainingMs(activeCharacter(player))) + " left)."
						: "."));
	}

	public static void sendJoinReminder(Player player) {
		RPCharacter character = activeCharacter(player);
		if (!isInSession(character)) {
			return;
		}
		RPTexts.send(player, RPTexts.WARN + "Your evil RP session is still running "
				+ RPTexts.MUTED + "(" + formatRemaining(remainingMs(character)) + " left).");
	}

	/** Adds a strike and applies what it costs: a healing injury, a permanent one, then death. */
	public static StrikeOutcome applyStrike(Player player, RPCharacter character) {
		return applyStrike(player, character, null, true);
	}

	/**
	 * Same as {@link #applyStrike(Player, RPCharacter)}. {@code killEntity} is false when the
	 * player already died for this strike, so a killing strike doesn't kill them a second time.
	 */
	public static StrikeOutcome applyStrike(Player player, RPCharacter character, Player killer, boolean killEntity) {
		applyDecay(character, System.currentTimeMillis());
		int strike = character.getEvilRpStrikes() + 1;
		character.setEvilRpStrikes(strike);
		character.setLastStrikeAtMs(System.currentTimeMillis());
		StrikeOutcome outcome = StrikeOutcome.forStrike(strike);
		RPCharacters.plugin.getLogger().info("Strike " + strike + " for " + player.getName()
				+ " (" + character.getName() + "): " + outcome);

		switch (outcome) {
			case HEALING_INJURY -> {
				Trait injury = PermadeathService.giveRandomInjury(player, character);
				showStrike(player, strike, injury, "You got a healing injury.");
			}
			case PERMANENT_INJURY -> {
				Trait injury = PermadeathService.givePermanentInjury(player, character);
				showStrike(player, strike, injury, "You got a permanent injury. One more strike and "
						+ character.getName() + " dies.");
			}
			case DEATH -> killByStrike(player, character, killer, killEntity);
		}
		RPCharacters.getPlayerManager().savePlayer(player);
		return outcome;
	}

	/** Kills the character for a strike: the third one, or any strike during an evil RP session. */
	public static boolean killByStrike(Player player, RPCharacter character, Player killer, boolean killEntity) {
		if (PermadeathService.killCharacter(player, character, PermakillCause.STRIKES, killer, killEntity)) {
			return true;
		}
		RPCharacters.plugin.getLogger().warning("Killing strike for " + player.getName()
				+ " (" + character.getName() + ") did not kill the character; the permakill was cancelled.");
		return false;
	}

	/**
	 * Takes off any strikes that have worn off, when decay is enabled in pvp.yml.
	 * Returns true when the character changed and needs saving.
	 */
	public static boolean applyDecay(RPCharacter character, long nowMs) {
		if (character == null || !PvpLoader.isStrikeDecayEnabled()) {
			return false;
		}
		StrikeDecay.Result result = StrikeDecay.apply(character.getEvilRpStrikes(), character.getLastStrikeAtMs(),
				nowMs, PvpLoader.getStrikeDecayMs());
		if (result.strikes() == character.getEvilRpStrikes()
				&& result.lastStrikeAtMs() == character.getLastStrikeAtMs()) {
			return false;
		}
		character.setEvilRpStrikes(result.strikes());
		character.setLastStrikeAtMs(result.lastStrikeAtMs());
		return true;
	}

	public static String formatRemaining(long remainingMs) {
		long totalSeconds = (remainingMs + 999L) / 1000L;
		return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		boolean checkDecay = ++ticksSinceDecayCheck >= DECAY_CHECK_TICKS;
		if (checkDecay) {
			ticksSinceDecayCheck = 0;
		}
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (checkDecay) {
				decayAll(player, now);
			}
			RPCharacter character = activeCharacter(player);
			if (character == null) {
				continue;
			}
			long endsAt = character.getEvilRpSessionEndsAtMs();
			if (endsAt <= 0L || isInSession(endsAt, now)) {
				continue;
			}
			character.setEvilRpSessionEndsAtMs(0L);
			if (now - endsAt < END_NOTICE_WINDOW_MS) {
				RPTexts.send(player, RPTexts.SUCCESS + "Your evil RP session has ended.");
			}
		}
	}

	private static void decayAll(Player player, long now) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		boolean changed = false;
		for (RPCharacter character : pd.getCharacters(Status.ALIVE)) {
			changed |= applyDecay(character, now);
		}
		if (changed) {
			RPCharacters.getPlayerManager().savePlayer(player);
		}
	}

	private static void showStrike(Player player, int strike, Trait injury, String detail) {
		String subtitle = injury != null ? TraitChangeService.resolveGainedMessage(injury) : " ";
		RPTexts.longTitle(player, RPTexts.ERROR + "Strike " + strike, subtitle);
		String outcome = injury != null ? detail : "There were no injuries left to give.";
		RPTexts.send(player, RPTexts.ERROR + "Strike " + strike + " of " + StrikeOutcome.MAX_STRIKES + ". "
				+ RPTexts.MUTED + outcome);
	}

	private static RPCharacter activeCharacter(Player player) {
		if (player == null) {
			return null;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return null;
		}
		RPCharacter character = pd.getActiveCharacter();
		return character.getStatus() == Status.ALIVE ? character : null;
	}
}
