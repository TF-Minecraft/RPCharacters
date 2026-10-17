package net.tfminecraft.rpcharacters.mmocore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.experience.EXPSource;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.progress.CharacterProgress;
import net.tfminecraft.rpcharacters.progress.CharacterProgressService;
import net.tfminecraft.rpcharacters.progress.ProgressRouting;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Class EXP a character receives from its account's other characters, such as the EXP of shared Codex
 * discoveries. MMOCore throws away EXP given at a class's level cap, so the class takes only what it can
 * hold; the rest waits on the character and is added once it can level further, after a subclass pick
 * or a raised cap. Amounts are raw, before MMOCore's EXP stat and boosters, like a discovery's own reward.
 */
public final class CarriedExperience {
	public static final String SUBCOMMAND = "carryexp";
	private static final double EPSILON = 1e-6;
	private static final int MAX_STEPS = 1000;
	/** Carried EXP at the start of each player's batch still to be reported. Main thread only. */
	private static final Map<UUID, Double> NOTICES = new HashMap<>();

	private CarriedExperience() {}

	/**
	 * Gives EXP to the player's live character, carrying what its class cannot hold yet. While progress
	 * is shared by the account, it is plain MMOCore EXP. @return false when there is no character to give it to
	 */
	public static boolean carry(Player player, double amount) {
		if (player == null || !(amount > 0) || !Double.isFinite(amount)) {
			return false;
		}
		if (!ProgressRouting.enabled()) {
			PlayerData.get(player).giveExperience(amount, EXPSource.COMMAND);
			return true;
		}
		CharacterProgress progress = ProgressRouting.activeProgress(player);
		if (progress == null || ClassService.isApplying(player.getUniqueId())) {
			return false;
		}
		double before = progress.getCarriedExperience();
		progress.setCarriedExperience(before + amount);
		release(player, progress);
		noticeAfterBatch(player, before);
		return true;
	}

	/**
	 * Tells the player once a batch of carried rewards is done (they arrive many in one tick) how much
	 * EXP is waiting, if the batch added to it.
	 */
	private static void noticeAfterBatch(Player player, double before) {
		if (RPCharacters.plugin == null || NOTICES.putIfAbsent(player.getUniqueId(), before) != null) {
			return;
		}
		BooleanSupplier current = ProgressRouting.sessionGuard(player);
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			double start = NOTICES.remove(player.getUniqueId());
			CharacterProgress progress = ProgressRouting.activeProgress(player);
			if (current.getAsBoolean() && progress != null && progress.getCarriedExperience() > start + EPSILON) {
				RPTexts.send(player, RPTexts.MUTED + "Your class can't level further yet, so " + RPTexts.WARN
						+ format(progress.getCarriedExperience()) + " EXP" + RPTexts.MUTED
						+ " from your other characters is waiting. It is added once your class can level, such as after you pick a subclass with "
						+ RPTexts.COMMAND + "/class" + RPTexts.MUTED + ".");
			}
		});
	}

	/** Adds the live character's carried EXP that its class can hold now. */
	public static void release(Player player) {
		CharacterProgress progress = player == null ? null : ProgressRouting.activeProgress(player);
		if (progress == null || progress.getCarriedExperience() <= EPSILON || ClassService.isApplying(player.getUniqueId())) {
			return;
		}
		double before = progress.getCarriedExperience();
		release(player, progress);
		double added = before - progress.getCarriedExperience();
		if (added > EPSILON) {
			RPTexts.send(player, RPTexts.SUCCESS + format(added) + " carried EXP" + RPTexts.MUTED
					+ " from your other characters was added to your class.");
		}
	}

	/** Releases carried EXP next tick, only while the character that changed class is still live. */
	public static void scheduleRelease(Player player) {
		if (player == null || RPCharacters.plugin == null || ProgressRouting.activeProgress(player) == null) {
			return;
		}
		BooleanSupplier current = ProgressRouting.sessionGuard(player);
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			if (current.getAsBoolean()) {
				release(player);
			}
		});
	}

	/**
	 * Gives the carried EXP level by level until it runs out or the class reaches its cap, so MMOCore
	 * never drops any of it and each level-up claims its rewards. The character is saved by a coalesced
	 * checkpoint, since a batch of shared rewards carries EXP many times in one tick.
	 */
	private static void release(Player player, CharacterProgress progress) {
		PlayerData mmo = PlayerData.get(player);
		double multiplier = (1 + mmo.getStats().getStat("ADDITIONAL_EXPERIENCE") / 100)
				* MMOCore.plugin.boosterManager.getMultiplier(null);
		double left = progress.getCarriedExperience();
		for (int step = 0; step < MAX_STEPS && left > EPSILON && multiplier > 0 && !mmo.hasReachedMaxLevel(); step++) {
			double needed = Math.max(0, mmo.getLevelUpExperience() - mmo.getExperience()) / multiplier;
			double given = Math.min(left, needed);
			if (given <= 0) {
				break;
			}
			mmo.giveExperience(given, EXPSource.COMMAND);
			left -= given;
		}
		if (left != progress.getCarriedExperience()) {
			progress.setCarriedExperience(left <= EPSILON ? 0 : left);
		}
		CharacterProgressService.scheduleCheckpoint(player);
	}

	/** {@code /rpcharacter admin carryexp <player> <amount>}, for Codex and ConditionalEvents shared rewards. */
	public static boolean handle(CommandSender sender, String[] args, int index) {
		if (args.length != index + 2) {
			RPTexts.send(sender, RPTexts.ERROR + "Usage: /rpcharacter admin " + SUBCOMMAND + " <player> <amount>");
			return true;
		}
		Player player = Bukkit.getPlayerExact(args[index]);
		if (player == null) {
			RPTexts.send(sender, RPTexts.ERROR + args[index] + " is not online.");
			return true;
		}
		double amount;
		try {
			amount = Double.parseDouble(args[index + 1]);
		} catch (NumberFormatException invalid) {
			amount = Double.NaN;
		}
		if (!(amount > 0) || !Double.isFinite(amount)) {
			RPTexts.send(sender, RPTexts.ERROR + "The amount must be a positive number.");
			return true;
		}
		if (!carry(player, amount)) {
			RPTexts.send(sender, RPTexts.ERROR + player.getName() + " has no character in play, or its class is being applied; nothing was carried.");
			return true;
		}
		RPTexts.send(sender, RPTexts.MUTED + "Carried " + format(amount) + " EXP to " + player.getName() + ".");
		return true;
	}

	private static String format(double amount) {
		return amount == Math.rint(amount) ? String.valueOf((long) amount) : String.format(Locale.ROOT, "%.1f", amount);
	}
}
