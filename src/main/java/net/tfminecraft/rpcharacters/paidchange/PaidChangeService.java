package net.tfminecraft.rpcharacters.paidchange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Pay to reopen a locked creation stage. The player pays when the stage opens, and the denars
 * stay held on the edit session until they leave it. A changed character keeps the payment and
 * raises the next price; an unchanged one gets the denars back.
 */
public final class PaidChangeService {

	/** Denars held for an open paid stage. {@code account} is null when the change was free. */
	public record Pending(PaidChangeRule rule, UUID payerId, Account account, double amount, String before) {}

	public enum ChargeStatus {
		PAID,
		UNAVAILABLE,
		INSUFFICIENT_FUNDS
	}

	public record ChargeResult(ChargeStatus status, Pending pending, double cost) {}

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private static volatile Map<String, PaidChangeRule> rulesByStage = Map.of();
	private static volatile List<Account> accountOrder = List.of(Account.POUCH, Account.BANK);
	private static DenarWallet wallet = new DenarEconomyWallet();

	private PaidChangeService() {}

	public static void configure(Collection<PaidChangeRule> rules, List<Account> accounts) {
		Map<String, PaidChangeRule> byStage = new LinkedHashMap<>();
		for (PaidChangeRule rule : rules) {
			byStage.put(rule.getStageId().toLowerCase(Locale.ROOT), rule);
		}
		rulesByStage = Map.copyOf(byStage);
		accountOrder = accounts == null || accounts.isEmpty()
				? List.of(Account.POUCH, Account.BANK)
				: List.copyOf(accounts);
	}

	static void setWallet(DenarWallet replacement) {
		wallet = replacement;
	}

	public static PaidChangeRule ruleFor(Stage stage) {
		if (stage == null || stage.getId() == null) {
			return null;
		}
		return rulesByStage.get(stage.getId().toLowerCase(Locale.ROOT));
	}

	/** True when a locked stage can be reopened by paying. */
	public static boolean canPayToOpen(Stage stage) {
		return ruleFor(stage) != null && wallet.available();
	}

	/** Takes the next price from the first account that covers it. */
	public static ChargeResult charge(UUID payerId, PaidChangeRule rule, RPCharacter character) {
		double cost = rule.costAfter(character.getPaidChangeCount(rule.getId()));
		String before = snapshot(character);
		if (cost <= 0.0) {
			return new ChargeResult(ChargeStatus.PAID, new Pending(rule, payerId, null, 0.0, before), 0.0);
		}
		if (!wallet.available()) {
			return new ChargeResult(ChargeStatus.UNAVAILABLE, null, cost);
		}
		for (Account account : accountOrder) {
			if (wallet.balance(payerId, account) >= cost && wallet.withdraw(payerId, account, cost)) {
				return new ChargeResult(ChargeStatus.PAID, new Pending(rule, payerId, account, cost, before), cost);
			}
		}
		return new ChargeResult(ChargeStatus.INSUFFICIENT_FUNDS, null, cost);
	}

	/**
	 * Keeps the payment and counts the change when the character changed; otherwise refunds it.
	 * Returns true when the payment was kept.
	 */
	public static boolean resolve(Pending pending, RPCharacter character) {
		if (character != null && !snapshot(character).equals(pending.before())) {
			String ruleId = pending.rule().getId();
			character.setPaidChangeCount(ruleId, character.getPaidChangeCount(ruleId) + 1);
			return true;
		}
		if (pending.account() != null && pending.amount() > 0.0
				&& !wallet.deposit(pending.payerId(), pending.account(), pending.amount())) {
			LOG.log(Level.SEVERE, "Could not refund " + pending.amount() + " denars to "
					+ pending.payerId() + " (" + pending.account() + ") for a " + pending.rule().getId()
					+ " change they backed out of. Refund them by hand.");
		}
		return false;
	}

	/** Charges for a locked stage and holds the payment on the session. False leaves the stage shut. */
	public static boolean payToOpen(Player player, CharacterCreation cc, Stage stage) {
		settle(cc);
		PaidChangeRule rule = ruleFor(stage);
		if (rule == null) {
			RPTexts.send(player, RPTexts.ERROR + "That choice is locked and can no longer be edited.");
			return false;
		}
		ChargeResult result = charge(player.getUniqueId(), rule, cc.getCharacter());
		switch (result.status()) {
			case UNAVAILABLE -> {
				RPTexts.send(player, RPTexts.ERROR + "You can't pay to change your " + rule.getLabel()
						+ " right now. Try again later.");
				return false;
			}
			case INSUFFICIENT_FUNDS -> {
				UUID id = player.getUniqueId();
				RPTexts.send(player, RPTexts.ERROR + "Changing your " + rule.getLabel() + " costs "
						+ formatDenars(result.cost()) + ". You have " + formatDenars(wallet.balance(id, Account.POUCH))
						+ " in your pouch and " + formatDenars(wallet.balance(id, Account.BANK)) + " in the bank.");
				return false;
			}
			default -> {
			}
		}
		Pending pending = result.pending();
		cc.setPendingPaidChange(pending);
		if (pending.amount() > 0.0) {
			RPTexts.send(player, RPTexts.SUCCESS + "Paid " + formatDenars(pending.amount()) + " from your "
					+ pending.account().displayName() + " to change your " + rule.getLabel() + ".");
			RPTexts.send(player, RPTexts.MUTED + "Leave without changing it and you get the denars back.");
		}
		return true;
	}

	/** Keeps or refunds the payment held on this session, if any. Safe to call more than once. */
	public static void settle(CharacterCreation cc) {
		if (cc == null) {
			return;
		}
		Pending pending = cc.takePendingPaidChange();
		if (pending == null) {
			return;
		}
		Player player = cc.getPlayer();
		RPCharacter character = cc.getCharacter();
		PaidChangeRule rule = pending.rule();
		if (resolve(pending, character)) {
			double next = rule.costAfter(character.getPaidChangeCount(rule.getId()));
			tell(player, RPTexts.SUCCESS + "Your " + rule.getLabel() + " change is paid for. The next one costs "
					+ formatDenars(next) + ".");
			if (player != null && RPCharacters.getPlayerManager() != null) {
				RPCharacters.getPlayerManager().savePlayer(player);
			}
		} else if (pending.amount() > 0.0) {
			tell(player, RPTexts.SUCCESS + "Your " + rule.getLabel() + " is unchanged, so "
					+ formatDenars(pending.amount()) + " went back to your " + pending.account().displayName() + ".");
		}
	}

	private static void tell(Player player, String message) {
		if (player != null) {
			RPTexts.send(player, message);
		}
	}

	/**
	 * Summary lore for a stage with a paid-change rule, or null to keep the default lore.
	 * Free window: how long it stays free and the next two prices. Locked: the price to change now
	 * and the one after.
	 */
	public static List<String> summaryLore(Stage stage, RPCharacter character, boolean locked) {
		PaidChangeRule rule = ruleFor(stage);
		if (rule == null || character == null) {
			return null;
		}
		int paid = character.getPaidChangeCount(rule.getId());
		String next = formatDenars(rule.costAfter(paid));
		String after = formatDenars(rule.costAfter(paid + 1));
		List<String> lines = new ArrayList<>();
		if (!locked) {
			lines.add(RPTexts.MUTED + "Click to change");
			long remaining = StageEditLock.lockRemainingMs(stage, character);
			if (remaining > 0L) {
				lines.add(RPTexts.MUTED + "Free to change for: " + RPTexts.WARN + AgeFormatter.formatCountdown(remaining));
				lines.add(RPTexts.MUTED + "Then: " + RPTexts.WARN + next + RPTexts.MUTED + ", next: " + RPTexts.WARN + after);
			}
			return lines;
		}
		if (!wallet.available()) {
			return null;
		}
		lines.add(RPTexts.WARN + "Click to change for " + next);
		lines.add(RPTexts.MUTED + "The change after costs " + RPTexts.WARN + after);
		lines.add(RPTexts.MUTED + "Refunded if you keep your " + rule.getLabel());
		return lines;
	}

	public static String formatDenars(double amount) {
		String number = amount == Math.rint(amount)
				? String.format(Locale.ROOT, "%,d", (long) amount)
				: String.format(Locale.ROOT, "%,.2f", amount);
		return number + (amount == 1.0 ? " denar" : " denars");
	}

	/** Everything a creation stage can change, so any edit made after paying shows up. */
	static String snapshot(RPCharacter character) {
		List<String> traitIds = new ArrayList<>();
		for (Trait trait : character.getTraits()) {
			if (trait != null) {
				traitIds.add(trait.getId());
			}
		}
		traitIds.sort(null);
		return String.join("\u0000",
				String.valueOf(character.getMMOClass()),
				character.getRace() == null ? "null" : String.valueOf(character.getRace().getId()),
				String.join(",", traitIds),
				String.valueOf(character.getName()),
				String.valueOf(character.getPersonaDescription()),
				String.valueOf(character.getBirthday()));
	}
}
