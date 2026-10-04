package net.tfminecraft.rpcharacters.classpick;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.player.profess.Subclass;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarEconomyWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeRule;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Class and subclass picks for the active character, in place of MMOCore class points. A
 * character's first class and first subclass are free. Other picks are class changes, priced like
 * the class creation stage: free while its lock window is open, then the class paid-change rule
 * (change-cost when there is no rule). Infinite points make every pick free.
 */
public final class ClassPickService {

	public record Settings(boolean enabled, boolean infinitePoints, BigDecimal changeCost,
			List<Account> accounts, Set<String> commands, String resetPermission) {
		public static final Settings DEFAULTS = new Settings(true, false, new BigDecimal("200.00"),
				List.of(Account.POUCH, Account.BANK), Set.of("class", "c"), "rpchar.class.reset");
	}

	public enum Status {
		CHOSEN,
		DISABLED,
		NO_CHARACTER,
		BUSY,
		UNKNOWN_CLASS,
		CURRENT_CLASS,
		LEVEL_TOO_LOW,
		UNAVAILABLE,
		INSUFFICIENT_FUNDS,
		NOT_APPLIED
	}

	/** {@code paidFrom} is null when the pick was free. */
	public record Result(Status status, PlayerClass target, BigDecimal cost, Account paidFrom) {}

	/** A displayed base class and the level its subclasses unlock at. */
	public record Option(PlayerClass playerClass, PlayerClass base, int requiredLevel) {
		public boolean isSubclass() {
			return base != null;
		}
	}

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private static volatile Settings settings = Settings.DEFAULTS;
	private static DenarWallet wallet = new DenarEconomyWallet();

	private ClassPickService() {}

	public static void configure(Settings replacement) {
		settings = replacement;
	}

	static void setWallet(DenarWallet replacement) {
		wallet = replacement;
	}

	public static Settings settings() {
		return settings;
	}

	public static boolean isEnabled() {
		return settings.enabled();
	}

	/** Displayed base classes in display order. */
	public static List<PlayerClass> baseClasses() {
		List<PlayerClass> classes = new ArrayList<>();
		Set<String> subclassIds = new HashSet<>();
		for (PlayerClass playerClass : MMOCore.plugin.classManager.getAll()) {
			for (Subclass subclass : playerClass.getSubclasses()) {
				subclassIds.add(subclass.getProfess().getId().toUpperCase(Locale.ROOT));
			}
		}
		for (PlayerClass playerClass : MMOCore.plugin.classManager.getAll()) {
			if (!subclassIds.contains(playerClass.getId().toUpperCase(Locale.ROOT))
					&& MmoCoreClassGuiHelper.isClassDisplayed(playerClass)) {
				classes.add(playerClass);
			}
		}
		classes.sort(Comparator.comparingInt(PlayerClass::getDisplayOrder));
		return classes;
	}

	/** The pick for {@code classId}: a displayed base class or one of their subclasses, else null. */
	public static Option option(String classId) {
		if (classId == null) {
			return null;
		}
		for (PlayerClass base : baseClasses()) {
			if (base.getId().equalsIgnoreCase(classId)) {
				return new Option(base, null, 0);
			}
		}
		for (PlayerClass base : MMOCore.plugin.classManager.getAll()) {
			for (Subclass subclass : base.getSubclasses()) {
				if (subclass.getProfess().getId().equalsIgnoreCase(classId)) {
					return new Option(subclass.getProfess(), base, subclass.getLevel());
				}
			}
		}
		return null;
	}

	/** Subclasses of the character's base class, including siblings of its current subclass. */
	public static List<Option> subclassOptions(RPCharacter character) {
		PlayerClass current = character.hasMMOClass() ? MMOCore.plugin.classManager.get(character.getMMOClass()) : null;
		if (current == null) {
			return List.of();
		}
		Option option = option(current.getId());
		PlayerClass base = option != null && option.isSubclass() ? option.base() : current;
		return base.getSubclasses().stream()
				.map(subclass -> new Option(subclass.getProfess(), base, subclass.getLevel())).toList();
	}

	/** Free for the first class, the first subclass in the current class family, and on infinite points. */
	public static BigDecimal price(RPCharacter character, Option option) {
		if (settings.infinitePoints() || !character.hasMMOClass()) {
			return BigDecimal.ZERO;
		}
		if (option.isSubclass() && !character.hasPickedSubclass()
				&& option.base().getId().equalsIgnoreCase(baseClassId(character))) {
			return BigDecimal.ZERO;
		}
		return changePrice(character);
	}

	/** The character's class, or the base class of its subclass. */
	private static String baseClassId(RPCharacter character) {
		Option current = option(character.getMMOClass());
		return current != null && current.isSubclass() ? current.base().getId() : character.getMMOClass();
	}

	/** The class creation stage, whose lock window and paid-change rule price class changes. */
	static Stage classStage() {
		for (Stage stage : StageLoader.oList) {
			if (CreationManager.isClassStage(stage)) {
				return stage;
			}
		}
		return null;
	}

	/** Free while the class stage's lock window is open, then the class paid-change rule's next cost. */
	static BigDecimal changePrice(RPCharacter character) {
		Stage stage = classStage();
		PaidChangeRule rule = PaidChangeService.ruleFor(stage);
		if (rule == null) {
			return settings.changeCost();
		}
		if (StageEditLock.canEdit(character.getOwner(), stage, character)) {
			return BigDecimal.ZERO;
		}
		return rule.costAfter(character.getPaidChangeCount(rule.getStageId()));
	}

	/** Why {@code player} can't pick a class right now, or null when they can. */
	public static Status blocker(Player player) {
		if (!settings.enabled()) {
			return Status.DISABLED;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return Status.NO_CHARACTER;
		}
		return CreationManager.activeCreators.containsKey(player) ? Status.BUSY : null;
	}

	public static Result choose(Player player, String classId) {
		Status blocked = blocker(player);
		if (blocked != null) {
			return new Result(blocked, null, BigDecimal.ZERO, null);
		}
		PlayerData pd = PlayerManager.get(player);
		RPCharacter character = pd.getActiveCharacter();
		Option option = option(classId);
		if (option == null) {
			return new Result(Status.UNKNOWN_CLASS, null, BigDecimal.ZERO, null);
		}
		PlayerClass target = option.playerClass();
		if (option.isSubclass() && subclassOptions(character).stream()
				.noneMatch(allowed -> allowed.playerClass().getId().equalsIgnoreCase(classId))) {
			return new Result(Status.UNKNOWN_CLASS, target, BigDecimal.ZERO, null);
		}
		if (target.getId().equalsIgnoreCase(character.getMMOClass())) {
			return new Result(Status.CURRENT_CLASS, target, BigDecimal.ZERO, null);
		}
		if (net.Indyuce.mmocore.api.player.PlayerData.get(player).getLevel() < option.requiredLevel()) {
			return new Result(Status.LEVEL_TOO_LOW, target, BigDecimal.ZERO, null);
		}
		BigDecimal cost = price(character, option);
		Account paidFrom = null;
		if (cost.signum() > 0) {
			if (!wallet.available()) {
				return new Result(Status.UNAVAILABLE, target, cost, null);
			}
			paidFrom = withdraw(player.getUniqueId(), cost);
			if (paidFrom == null) {
				return new Result(Status.INSUFFICIENT_FUNDS, target, cost, null);
			}
		}
		String oldClassId = character.getMMOClass();
		character.setMMOClass(target.getId());
		if (!applied(player, target.getId())) {
			character.setMMOClass(oldClassId);
			refund(player.getUniqueId(), paidFrom, cost);
			return new Result(Status.NOT_APPLIED, target, cost, null);
		}
		if (option.isSubclass()) {
			character.setSubclassPicked(true);
		}
		if (paidFrom != null) {
			character.setPaidClassPicks(character.getPaidClassPicks() + 1);
			PaidChangeRule rule = PaidChangeService.ruleFor(classStage());
			if (rule != null) {
				// Shared with paid class-stage edits, so the next change costs the next price.
				character.setPaidChangeCount(rule.getStageId(), character.getPaidChangeCount(rule.getStageId()) + 1);
			}
		}
		CharacterLifecycle.notifyClassChange(player, pd.getUniqueId(), character, oldClassId, target.getId());
		RPCharacters.getPlayerManager().savePlayer(player);
		return new Result(Status.CHOSEN, target, cost, paidFrom);
	}

	private static boolean applied(Player player, String classId) {
		try {
			return ClassService.applyClass(player, classId);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Class " + classId + " could not be applied for " + player.getName(), e);
			return false;
		}
	}

	private static Account withdraw(UUID playerId, BigDecimal cost) {
		for (Account account : settings.accounts()) {
			if (wallet.balance(playerId, account).compareTo(cost) >= 0 && wallet.withdraw(playerId, account, cost)) {
				return account;
			}
		}
		return null;
	}

	private static void refund(UUID playerId, Account account, BigDecimal cost) {
		if (account != null && !wallet.deposit(playerId, account, cost)) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not refund " + cost + " denars to " + playerId + " ("
					+ account.displayName() + ") for a class pick that was not applied. Refund it by hand.");
		}
	}

	/** Player-facing text for {@code result}. */
	public static String message(Result result) {
		String name = result.target() == null ? "" : className(result.target());
		return switch (result.status()) {
			case CHOSEN -> {
				String paid = result.paidFrom() == null ? ""
						: " Paid " + PaidChangeService.formatDenars(result.cost()) + " from your "
								+ result.paidFrom().displayName() + ".";
				yield RPTexts.SUCCESS + "You are now a " + name + RPTexts.SUCCESS + "." + paid;
			}
			case DISABLED -> RPTexts.ERROR + "Class picks are turned off on this server.";
			case NO_CHARACTER -> RPTexts.ERROR + "You need an active character to pick a class.";
			case BUSY -> RPTexts.ERROR + "Finish your character session first.";
			case UNKNOWN_CLASS -> RPTexts.ERROR + "That class can't be picked.";
			case CURRENT_CLASS -> RPTexts.WARN + "You are already a " + name + RPTexts.WARN + ".";
			case LEVEL_TOO_LOW -> RPTexts.ERROR + name + RPTexts.ERROR + " unlocks at level " + requiredLevel(result.target()) + ".";
			case UNAVAILABLE -> RPTexts.ERROR + "You can't pay for a class change right now. Try again later.";
			case INSUFFICIENT_FUNDS -> RPTexts.ERROR + "Changing your class costs " + PaidChangeService.formatDenars(result.cost())
					+ ". You don't have that much in your pouch or the bank.";
			case NOT_APPLIED -> RPTexts.ERROR + "Your class couldn't be changed right now. Try again in a moment."
					+ (result.cost().signum() > 0 ? " You were not charged." : "");
		};
	}

	public static String className(PlayerClass playerClass) {
		return MmoCoreClassGuiHelper.formatLine(playerClass.getName());
	}

	private static int requiredLevel(PlayerClass target) {
		Option option = option(target == null ? null : target.getId());
		return option == null ? 0 : option.requiredLevel();
	}

	/** {@code heading}, then what this character's picks cost. */
	public static List<String> pricingLore(RPCharacter character, String heading) {
		List<String> lines = new ArrayList<>();
		lines.add(heading);
		if (settings.infinitePoints()) {
			lines.add(RPTexts.MUTED + "Class picks: " + RPTexts.WARN + "Free");
			return lines;
		}
		if (!character.hasPickedSubclass()) {
			lines.add(RPTexts.MUTED + "First subclass: " + RPTexts.WARN + "Free");
		}
		Stage stage = classStage();
		PaidChangeRule rule = PaidChangeService.ruleFor(stage);
		if (rule == null) {
			lines.add(RPTexts.MUTED + "Other picks: " + RPTexts.WARN + priceText(settings.changeCost()));
			return lines;
		}
		int paid = character.getPaidChangeCount(rule.getStageId());
		long freeFor = StageEditLock.lockRemainingMs(stage, character);
		if (StageEditLock.canEdit(character.getOwner(), stage, character)) {
			lines.add(RPTexts.MUTED + "Class changes: " + RPTexts.WARN + "Free"
					+ (freeFor > 0 ? RPTexts.MUTED + " for " + RPTexts.WARN + AgeFormatter.formatCountdown(freeFor) : ""));
			if (freeFor <= 0) {
				return lines;
			}
		} else {
			lines.add(RPTexts.MUTED + "Class changes: " + RPTexts.WARN + priceText(rule.costAfter(paid)));
			paid++;
		}
		lines.add(RPTexts.MUTED + "Then: " + RPTexts.WARN + priceText(rule.costAfter(paid)));
		return lines;
	}

	/** Price text for a lore line: "Free" or "200 denars". */
	public static String priceText(BigDecimal cost) {
		return cost.signum() <= 0 ? "Free" : PaidChangeService.formatDenars(cost);
	}
}
