package net.tfminecraft.rpcharacters.classpick;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.StageRevisions;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeRule;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * {@code /rpcharacter admin classpicks <player> [reset|freesubclass|paid <count>]}: staff read or
 * change the class pick state of the character another online player is playing.
 * <ul>
 * <li>no action: show the class, the free window, the paid change count and what picks cost;</li>
 * <li>{@code reset}: what {@code resetclasses} does, for this character only. Its class stage
 * window starts again now, paid change prices start over and its next subclass is free;</li>
 * <li>{@code freesubclass}: only make the next subclass pick free;</li>
 * <li>{@code paid <count>}: set how many paid class changes it has made, which picks the next
 * price from the class paid-change rule.</li>
 * </ul>
 * The class itself is never changed. Uses the {@code resetclasses} permission.
 */
public final class ClassPickAdminCommand {

	public static final String SUBCOMMAND = "classpicks";
	public static final List<String> ACTIONS = List.of("reset", "freesubclass", "paid");
	static final String USAGE = "Usage: /rpcharacter admin classpicks <player> [reset|freesubclass|paid <count>]";

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private ClassPickAdminCommand() {}

	public static boolean canUse(CommandSender sender) {
		return ClassPickReset.canUse(sender);
	}

	public static boolean handle(CommandSender sender, String[] args, int index) {
		if (!canUse(sender)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to use this command.");
			return true;
		}
		if (args.length <= index || args.length > index + 3) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[index]);
		PlayerData data = target == null ? null : PlayerManager.get(target);
		RPCharacter character = data == null ? null : data.getActiveCharacter();
		if (character == null) {
			RPTexts.send(sender, RPTexts.ERROR + (target == null ? "No online player called " : "No active character for ")
					+ RPTexts.WARN + args[index] + RPTexts.ERROR + ".");
			return true;
		}
		Stage stage = ClassPickService.classStage();
		PaidChangeRule rule = PaidChangeService.ruleFor(stage);
		String who = RPTexts.MUTED + "Character " + RPTexts.WARN + DisplayIdentityService.resolveCharacterName(character)
				+ RPTexts.MUTED + " (" + target.getName() + ")";
		if (args.length == index + 1) {
			sendInfo(sender, who, character, stage, rule);
			return true;
		}
		String action = args[index + 1].toLowerCase(Locale.ROOT);
		boolean paid = action.equals("paid");
		if (!ACTIONS.contains(action) || paid != (args.length == index + 3)) {
			RPTexts.send(sender, RPTexts.ERROR + USAGE);
			return true;
		}
		if (character.getPendingClassPick() != null || character.getPendingPaidChange() != null) {
			RPTexts.send(sender, who + RPTexts.ERROR + " has a class payment still being settled. Try again in a moment.");
			return true;
		}
		String done;
		switch (action) {
			case "reset" -> {
				if (stage == null) {
					RPTexts.send(sender, RPTexts.ERROR + "There is no class stage in stages.yml to reset.");
					return true;
				}
				reset(character, stage, rule, Instant.now().getEpochSecond());
				done = " can change class and subclass for free " + window(stage) + ", paid change prices start over"
						+ " and the next subclass is free.";
			}
			case "freesubclass" -> {
				character.setSubclassPicked(false);
				done = " gets its next subclass pick for free.";
			}
			default -> {
				if (rule == null) {
					RPTexts.send(sender, RPTexts.ERROR + "There is no class paid-change rule in config.yml, so class"
							+ " changes always cost " + ClassPickService.priceText(ClassPickService.settings().changeCost()) + ".");
					return true;
				}
				Integer count = parseCount(args[index + 2]);
				if (count == null) {
					RPTexts.send(sender, RPTexts.ERROR + "The count must be a whole number from 0 up.");
					return true;
				}
				character.setPaidChangeCount(rule.getStageId(), count);
				done = " has " + count + " paid class changes. The next paid change costs "
						+ ClassPickService.priceText(rule.costAfter(count)) + ".";
			}
		}
		RPCharacters.getPlayerManager().savePlayer(target);
		LOG.info("[RPCharacters] " + sender.getName() + " ran classpicks " + action
				+ (paid ? " " + args[index + 2] : "") + " for " + target.getName() + " (character " + character.getId() + ").");
		RPTexts.send(sender, who + RPTexts.SUCCESS + done);
		if (!target.equals(sender) && !action.equals("paid")) {
			RPTexts.sendPrefixed(target, RPTexts.SUCCESS + (action.equals("reset")
					? "Staff reset your class picks: class and subclass changes are free " + window(stage) + "."
					: "Staff made your next subclass pick free."));
		}
		return true;
	}

	/** One character's {@code resetclasses}: a fresh class window from now, no paid changes, a free subclass. */
	static void reset(RPCharacter character, Stage stage, PaidChangeRule rule, long nowEpochSeconds) {
		String key = StageRevisions.key(stage);
		character.setStageRevision(key, Math.max(stage.getRevision(), character.getStageRevision(key)), nowEpochSeconds);
		character.setPaidChangeCount(key, 0);
		if (rule != null) {
			character.setPaidChangeCount(rule.getStageId(), 0);
		}
		character.setSubclassPicked(false);
	}

	private static void sendInfo(CommandSender sender, String who, RPCharacter character, Stage stage, PaidChangeRule rule) {
		String className = character.hasMMOClass() ? character.getMMOClass() : "none";
		RPTexts.send(sender, who + RPTexts.MUTED + ": class " + RPTexts.WARN + className);
		long freeFor = stage == null ? 0L : StageEditLock.lockRemainingMs(stage, character);
		boolean open = stage != null && StageEditLock.canEdit(stage, character);
		RPTexts.send(sender, RPTexts.MUTED + "Free class changes: " + RPTexts.WARN + (!open ? "no, window closed"
				: freeFor > 0 ? "for " + AgeFormatter.formatCountdown(freeFor) : "always"));
		RPTexts.send(sender, RPTexts.MUTED + "Next subclass pick free: " + RPTexts.WARN
				+ (ClassPickService.freeSubclassOpen(character) ? "yes" : "no"));
		if (rule == null) {
			RPTexts.send(sender, RPTexts.MUTED + "Class changes cost: " + RPTexts.WARN
					+ ClassPickService.priceText(ClassPickService.settings().changeCost()));
			return;
		}
		int paid = character.getPaidChangeCount(rule.getStageId());
		RPTexts.send(sender, RPTexts.MUTED + "Paid class changes: " + RPTexts.WARN + paid + RPTexts.MUTED
				+ ", next paid change costs " + RPTexts.WARN + ClassPickService.priceText(rule.costAfter(paid)));
	}

	private static String window(Stage stage) {
		return stage.getLockTimeMs() < 0 ? "always" : "for " + AgeFormatter.formatCountdown(stage.getLockTimeMs());
	}

	private static Integer parseCount(String token) {
		try {
			int count = Integer.parseInt(token);
			return count < 0 ? null : count;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
