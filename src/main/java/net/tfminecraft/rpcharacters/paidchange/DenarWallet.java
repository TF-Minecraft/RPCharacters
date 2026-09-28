package net.tfminecraft.rpcharacters.paidchange;

import java.util.UUID;

/** The denar accounts a paid change is charged to. */
public interface DenarWallet {
	enum Account {
		POUCH,
		BANK;

		public String displayName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	boolean available();

	double balance(UUID playerId, Account account);

	/** Takes the whole amount, or nothing when the account cannot cover it. */
	boolean withdraw(UUID playerId, Account account, double amount);

	boolean deposit(UUID playerId, Account account, double amount);
}
