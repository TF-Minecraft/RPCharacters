package net.tfminecraft.rpcharacters.paidchange;

import java.util.UUID;

import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;

/**
 * Denars taken to open a paid stage, held on the character (and saved with it) until the change
 * is kept or refunded. {@code account} is null when the change was free. {@code before} is the
 * character snapshot at payment.
 */
public record PendingPaidChange(String stageId, String label, UUID payerId, Account account, double amount,
		String before) {}
