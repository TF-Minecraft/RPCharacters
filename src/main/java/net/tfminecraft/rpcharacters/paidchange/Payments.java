package net.tfminecraft.rpcharacters.paidchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Posting;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;

/**
 * Charging a character and giving the money back, crash-safe. The payment is held on the
 * character and written to disk before its debit is posted; settling asks the ledger whether that
 * debit applied, and a refund always reuses the stored refund ID.
 */
public final class Payments {
	public enum Taken {
		PAID,
		UNAVAILABLE,
		INSUFFICIENT_FUNDS
	}

	/** {@code payment} is set when PAID, and when the ledger could not say whether it was taken. */
	public record Charge(Taken status, Payment payment) {}

	/** Whether a held payment's debit went through. */
	public enum Debit {
		APPLIED,
		/** Never posted, or refused: nothing was taken. */
		NOT_APPLIED,
		/** The ledger can't say right now. */
		UNKNOWN
	}

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	/** Writes one character file now; false when it could not be written. */
	static BiPredicate<UUID, RPCharacter> persist = Payments::saveNow;
	static BooleanSupplier heirsReady = Characters::legacyHeirsReady;
	static Function<UUID, Optional<CharacterRef>> heirs = Characters::legacyHeir;

	private Payments() {}

	public static CharacterRef payer(UUID ownerId, RPCharacter character) {
		return new CharacterRef(ownerId, UUID.fromString(character.getId()));
	}

	/** Whole cents of a configured price. */
	public static long cents(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
	}

	public static BigDecimal denars(long cents) {
		return BigDecimal.valueOf(cents, 2);
	}

	/** Writes the character's file now, so a held payment survives a crash. */
	public static boolean save(UUID ownerId, RPCharacter character) {
		return persist.test(ownerId, character);
	}

	private static boolean saveNow(UUID ownerId, RPCharacter character) {
		PlayerManager manager = RPCharacters.getPlayerManager();
		return manager != null && manager.saveCharacterNow(ownerId, character);
	}

	/**
	 * Takes {@code cents} from the first account in {@code order} that covers it. {@code hold}
	 * records a payment on the character and saves it, returning false when it couldn't be saved;
	 * it is called with null to drop a payment whose debit never applied.
	 */
	public static Charge take(DenarWallet wallet, String reason, String what, CharacterRef payer, List<Account> order,
			long cents, Predicate<Payment> hold) {
		for (Account account : order) {
			if (wallet.balance(payer, account) < cents) {
				continue;
			}
			Payment payment = Payment.open(payer, account, cents, what);
			if (!hold.test(payment)) {
				LOG.warning("[RPCharacters] Could not save the payment for " + payment.debitOpId() + "; nothing was taken.");
				return new Charge(Taken.UNAVAILABLE, null);
			}
			Posting posted = wallet.withdraw(payment.debitOpId(), reason, payer, account, cents);
			Debit debit = posted == Posting.APPLIED ? Debit.APPLIED : debited(wallet, payment);
			if (debit == Debit.APPLIED) {
				return new Charge(Taken.PAID, payment);
			}
			if (debit == Debit.UNKNOWN) {
				LOG.warning("[RPCharacters] DenarEconomy could not confirm " + payment.debitOpId()
						+ "; the payment stays held and is settled when the owner next joins.");
				return new Charge(Taken.UNAVAILABLE, payment);
			}
			hold.test(null);
			if (posted != Posting.REFUSED) {
				return new Charge(Taken.UNAVAILABLE, null);
			}
		}
		return new Charge(Taken.INSUFFICIENT_FUNDS, null);
	}

	/** Asks the ledger whether the payment's debit applied. A legacy payment was always taken. */
	public static Debit debited(DenarWallet wallet, Payment payment) {
		if (payment.isLegacy()) {
			return Debit.APPLIED;
		}
		Optional<Posting> stored = wallet.status(payment.debitOpId());
		if (stored.isEmpty()) {
			return Debit.NOT_APPLIED;
		}
		return switch (stored.get()) {
			case APPLIED -> Debit.APPLIED;
			case UNAVAILABLE -> Debit.UNKNOWN;
			default -> Debit.NOT_APPLIED;
		};
	}

	/**
	 * Gives the payment back with its stored refund ID, so a repeat never pays twice. A legacy
	 * payment goes to the account's legacy heir, and waits (UNAVAILABLE) until heirs are known.
	 */
	public static Posting refund(DenarWallet wallet, Payment payment) {
		CharacterRef to = payment.payer();
		if (payment.isLegacy()) {
			Optional<CharacterRef> heir = heirsReady.getAsBoolean() ? heirs.apply(payment.legacyAccount())
					: Optional.empty();
			if (heir.isEmpty()) {
				LOG.warning("[RPCharacters] No legacy heir yet for " + payment.legacyAccount() + "; "
						+ payment.refundOpId() + " waits.");
				return Posting.UNAVAILABLE;
			}
			to = heir.get();
		}
		Posting result = wallet.deposit(payment.refundOpId(), DenarWallet.REFUND, to, payment.account(), payment.cents());
		if (result == Posting.CONFLICT) {
			LOG.severe("[RPCharacters] Refund " + payment.refundOpId() + " clashes with an earlier posting; check it by hand.");
		}
		return result;
	}
}
