package net.tfminecraft.rpcharacters.paidchange;

import java.math.BigDecimal;
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

import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Posting;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Pay to reopen a locked creation stage. The edited character pays from its own pouch or bank
 * when the stage opens, and the payment stays held on the character until the edit session ends.
 * A changed character keeps the payment and raises the next price; an unchanged one gets the
 * denars back.
 */
public final class PaidChangeService {

	public enum ChargeStatus {
		PAID,
		UNAVAILABLE,
		INSUFFICIENT_FUNDS
	}

	public record ChargeResult(ChargeStatus status, PendingPaidChange pending, BigDecimal cost) {}

	public enum Outcome {
		/** The character changed: the payment stays and the change is counted. */
		KEPT,
		/** Nothing changed and the denars went back. */
		REFUNDED,
		/** Nothing changed but the refund failed; the payment stays held for a later retry. */
		REFUND_FAILED,
		/** The debit never went through, so there is nothing to keep or refund. */
		DISCARDED,
		/** The ledger can't say whether the debit went through; the payment stays held. */
		UNCONFIRMED
	}

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

	/**
	 * Takes the next price from the character's first account that covers it. A paid charge is
	 * held on the character and saved before the debit is posted.
	 */
	public static ChargeResult charge(UUID ownerId, PaidChangeRule rule, RPCharacter character) {
		BigDecimal cost = rule.costAfter(character.getPaidChangeCount(rule.getStageId()));
		String before = snapshot(character);
		if (cost.signum() <= 0) {
			return new ChargeResult(ChargeStatus.PAID,
					new PendingPaidChange(rule.getStageId(), rule.getLabel(), null, before), cost);
		}
		if (!wallet.available()) {
			return new ChargeResult(ChargeStatus.UNAVAILABLE, null, cost);
		}
		Payments.Charge taken = Payments.take(wallet, DenarWallet.PAID_CHANGE, rule.getStageId(),
				Payments.payer(ownerId, character), accountOrder, Payments.cents(cost),
				payment -> hold(ownerId, character, rule, payment, before));
		return switch (taken.status()) {
			case PAID -> new ChargeResult(ChargeStatus.PAID, character.getPendingPaidChange(), cost);
			case UNAVAILABLE -> new ChargeResult(ChargeStatus.UNAVAILABLE, null, cost);
			default -> new ChargeResult(ChargeStatus.INSUFFICIENT_FUNDS, null, cost);
		};
	}

	/** Holds the payment on the character and saves it; null drops a payment that was never taken. */
	private static boolean hold(UUID ownerId, RPCharacter character, PaidChangeRule rule, Payment payment,
			String before) {
		PendingPaidChange previous = character.getPendingPaidChange();
		character.setPendingPaidChange(
				payment == null ? null : new PendingPaidChange(rule.getStageId(), rule.getLabel(), payment, before));
		if (Payments.save(ownerId, character) || payment == null) {
			return true;
		}
		character.setPendingPaidChange(previous);
		return false;
	}

	/**
	 * Settles the payment held on the character. A payment whose debit never applied is dropped
	 * without a refund. Otherwise a changed character keeps it and counts the change, and an
	 * unchanged one is refunded with the stored refund ID. The hold is cleared only once one of
	 * those happened.
	 */
	public static Outcome resolve(RPCharacter character) {
		PendingPaidChange pending = character.getPendingPaidChange();
		if (pending == null) {
			return null;
		}
		Payment payment = pending.payment();
		Payments.Debit debit = payment == null ? Payments.Debit.APPLIED : Payments.debited(wallet, payment);
		if (debit == Payments.Debit.UNKNOWN) {
			return Outcome.UNCONFIRMED;
		}
		if (debit == Payments.Debit.NOT_APPLIED) {
			LOG.info("[RPCharacters] " + payment.debitOpId() + " never went through; dropping its hold.");
			character.setPendingPaidChange(null);
			return Outcome.DISCARDED;
		}
		if (!snapshot(character).equals(pending.before())) {
			character.setPaidChangeCount(pending.stageId(),
					(int) Math.min(Integer.MAX_VALUE, (long) character.getPaidChangeCount(pending.stageId()) + 1));
			character.setPendingPaidChange(null);
			return Outcome.KEPT;
		}
		if (payment != null && Payments.refund(wallet, payment) != Posting.APPLIED) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not post " + payment.refundOpId() + " for a "
					+ pending.label() + " change; keeping it held to retry.");
			return Outcome.REFUND_FAILED;
		}
		character.setPendingPaidChange(null);
		return Outcome.REFUNDED;
	}

	/** Charges for a locked stage and holds the payment on the character. False leaves the stage shut. */
	public static boolean payToOpen(Player player, CharacterCreation cc, Stage stage) {
		settle(cc);
		RPCharacter character = cc.getCharacter();
		if (character.getPendingPaidChange() != null) {
			RPTexts.send(player, RPTexts.ERROR + "Your last refund hasn't gone through yet. Try again later.");
			return false;
		}
		PaidChangeRule rule = ruleFor(stage);
		if (rule == null) {
			RPTexts.send(player, RPTexts.ERROR + "That choice is locked and can no longer be edited.");
			return false;
		}
		ChargeResult result = charge(player.getUniqueId(), rule, character);
		switch (result.status()) {
			case UNAVAILABLE -> {
				RPTexts.send(player, RPTexts.ERROR + "You can't pay to change your " + rule.getLabel()
						+ " right now. Try again later.");
				return false;
			}
			case INSUFFICIENT_FUNDS -> {
				CharacterRef payer = Payments.payer(player.getUniqueId(), character);
				RPTexts.send(player, RPTexts.ERROR + "Changing your " + rule.getLabel() + " costs "
						+ formatDenars(result.cost()) + ". You have " + formatCents(wallet.balance(payer, Account.POUCH))
						+ " in your pouch and " + formatCents(wallet.balance(payer, Account.BANK)) + " in the bank.");
				return false;
			}
			default -> {
			}
		}
		PendingPaidChange pending = result.pending();
		// A paid hold is already set and saved; a free one needs no save.
		character.setPendingPaidChange(pending);
		if (pending.cents() > 0) {
			RPTexts.send(player, RPTexts.SUCCESS + "Paid " + formatCents(pending.cents()) + " from your "
					+ pending.account().displayName() + " to change your " + rule.getLabel() + ".");
			RPTexts.send(player, RPTexts.MUTED + "Leave without changing it and you get the denars back.");
		}
		return true;
	}

	/** Keeps or refunds the payment held on this session's character, if any. Safe to call more than once. */
	public static void settle(CharacterCreation cc) {
		if (cc == null || cc.getCharacter() == null) {
			return;
		}
		RPCharacter character = cc.getCharacter();
		PendingPaidChange pending = character.getPendingPaidChange();
		Outcome outcome = resolve(character);
		if (outcome == null) {
			return;
		}
		tell(cc.getPlayer(), outcomeMessage(pending, outcome, character));
	}

	/**
	 * Settles a payment left held by a crash or a failed refund. Run when the owner joins, before
	 * any edit session exists. Returns the player message, or null when there was nothing to settle.
	 */
	public static String recover(RPCharacter character) {
		PendingPaidChange pending = character == null ? null : character.getPendingPaidChange();
		Outcome outcome = pending == null ? null : resolve(character);
		return outcome == null ? null : outcomeMessage(pending, outcome, character);
	}

	private static String outcomeMessage(PendingPaidChange pending, Outcome outcome, RPCharacter character) {
		switch (outcome) {
			case KEPT -> {
				PaidChangeRule rule = rulesByStage.get(pending.stageId());
				String next = rule == null ? ""
						: " The next one costs " + formatDenars(rule.costAfter(character.getPaidChangeCount(pending.stageId()))) + ".";
				return RPTexts.SUCCESS + "Your " + pending.label() + " change is paid for." + next;
			}
			case REFUNDED -> {
				if (pending.cents() <= 0) {
					return null;
				}
				return RPTexts.SUCCESS + "Your " + pending.label() + " is unchanged, so "
						+ formatCents(pending.cents()) + " went back to your " + pending.account().displayName() + ".";
			}
			case DISCARDED -> {
				return null;
			}
			case UNCONFIRMED -> {
				return RPTexts.ERROR + "Your " + formatCents(pending.cents()) + " payment for a " + pending.label()
						+ " change couldn't be checked yet. It will be settled when you next join.";
			}
			default -> {
				return RPTexts.ERROR + "Your " + formatCents(pending.cents()) + " refund couldn't go through yet. "
						+ "It will be retried when you next join.";
			}
		}
	}

	private static void tell(Player player, String message) {
		if (player != null && message != null) {
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
		int paid = character.getPaidChangeCount(rule.getStageId());
		String next = formatDenars(rule.costAfter(paid));
		String after = formatDenars(rule.costAfter((int) Math.min(Integer.MAX_VALUE, (long) paid + 1)));
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

	public static String formatCents(long cents) {
		return formatDenars(Payments.denars(cents));
	}

	public static String formatDenars(BigDecimal amount) {
		BigDecimal whole = amount.stripTrailingZeros();
		String number = whole.scale() <= 0
				? String.format(Locale.ROOT, "%,d", whole.toBigInteger())
				: String.format(Locale.ROOT, "%,.2f", amount);
		return number + (amount.compareTo(BigDecimal.ONE) == 0 ? " denar" : " denars");
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
