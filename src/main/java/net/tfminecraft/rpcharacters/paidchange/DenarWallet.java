package net.tfminecraft.rpcharacters.paidchange;

import java.math.BigDecimal;
import java.util.UUID;

/** The denar accounts a paid change is charged to. Amounts are exact, in cents (scale 2). */
public interface DenarWallet {
	enum Account {
		POUCH,
		BANK;

		public String displayName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	boolean available();

	BigDecimal balance(UUID playerId, Account account);

	/** Takes the whole amount, or nothing when the account cannot cover it. */
	boolean withdraw(UUID playerId, Account account, BigDecimal amount);

	boolean deposit(UUID playerId, Account account, BigDecimal amount);
}
