package net.tfminecraft.rpcharacters.paidchange;

import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;

/**
 * A paid stage held open on the character (and saved with it) until the change is kept or
 * refunded. {@code payment} is null when the change was free. {@code before} is the character
 * snapshot at payment.
 */
public record PendingPaidChange(String stageId, String label, Payment payment, String before) {

	public long cents() {
		return payment == null ? 0L : payment.cents();
	}

	public Account account() {
		return payment == null ? null : payment.account();
	}
}
