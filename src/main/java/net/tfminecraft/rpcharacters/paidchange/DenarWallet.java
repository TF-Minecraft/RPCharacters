package net.tfminecraft.rpcharacters.paidchange;

import java.util.Optional;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * A character's denar accounts on the DenarEconomy ledger. Amounts are whole cents. Every change
 * is a posting with an operation ID: posting the same ID again returns the first result without
 * moving anything, so a retry never pays twice.
 */
public interface DenarWallet {
	/** Ledger reason for a paid character-stage change. */
	String PAID_CHANGE = "rpc:paid-change";
	/** Ledger reason for a paid class pick. */
	String CLASS_PICK = "rpc:class-pick";
	/** Ledger reason for money given back. */
	String REFUND = "rpc:refund";

	enum Account {
		POUCH,
		BANK;

		public String displayName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** What became of a posting, mirroring DenarEconomy's result. Only APPLIED moved money. */
	enum Posting {
		APPLIED,
		/** The account could not cover it, or the account is frozen. */
		REFUSED,
		/** The operation ID was already used for different money. */
		CONFLICT,
		/** The ledger could not be reached, or could not say. */
		UNAVAILABLE
	}

	boolean available();

	/** Balance in cents; zero when the ledger is unavailable. */
	long balance(CharacterRef character, Account account);

	/** Takes {@code cents} from the character's account into the mint, all or nothing. */
	Posting withdraw(String opId, String reason, CharacterRef character, Account account, long cents);

	/** Gives {@code cents} from the mint to the character's account. */
	Posting deposit(String opId, String reason, CharacterRef character, Account account, long cents);

	/**
	 * The stored result of an operation: empty when it was never posted, UNAVAILABLE when the
	 * ledger can't say right now.
	 */
	Optional<Posting> status(String opId);
}
