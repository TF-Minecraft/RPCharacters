package net.tfminecraft.rpcharacters.paidchange;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Denars a character pays, with the operation IDs that take and return them. The record is saved
 * before the debit is posted, so a crash leaves enough to ask the ledger what happened.
 *
 * <p>A legacy payment was taken by the old account-wide wallet before characters had their own
 * money: {@code payer} and {@code debitOpId} are null, and a refund goes to the account's legacy
 * heir.
 */
public record Payment(CharacterRef payer, UUID legacyAccount, Account account, long cents, String debitOpId,
		String refundOpId) {

	/** A new payment for {@code what} (a stage ID, or "class-pick"), with fresh operation IDs. */
	public static Payment open(CharacterRef payer, Account account, long cents, String what) {
		String debit = "paid:" + payer.characterId() + ":" + what.toLowerCase(Locale.ROOT) + ":" + UUID.randomUUID();
		return new Payment(payer, null, account, cents, debit, "refund:" + debit.substring("paid:".length()));
	}

	/** A payment the old wallet took from {@code accountId}; it refunds once with {@code refundOpId}. */
	public static Payment legacy(UUID accountId, Account account, long cents, String refundOpId) {
		return new Payment(null, accountId, account, cents, null, refundOpId);
	}

	public boolean isLegacy() {
		return payer == null;
	}

	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		JSONObject json = new JSONObject();
		if (isLegacy()) {
			json.put("legacy-account", legacyAccount.toString());
		} else {
			json.put("owner", payer.ownerId().toString());
			json.put("character", payer.characterId().toString());
			json.put("debit-op", debitOpId);
		}
		json.put("account", account.name());
		json.put("cents", cents);
		json.put("refund-op", refundOpId);
		return json;
	}

	/** @throws RuntimeException when the entry is incomplete */
	public static Payment fromJson(Map<?, ?> json) {
		Account account = Account.valueOf((String) json.get("account"));
		long cents = ((Number) json.get("cents")).longValue();
		String refund = requireText(json.get("refund-op"));
		if (json.get("legacy-account") instanceof String legacy) {
			return legacy(UUID.fromString(legacy), account, cents, refund);
		}
		CharacterRef payer = new CharacterRef(UUID.fromString((String) json.get("owner")),
				UUID.fromString((String) json.get("character")));
		return new Payment(payer, null, account, cents, requireText(json.get("debit-op")), refund);
	}

	private static String requireText(Object value) {
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException("Missing operation ID");
		}
		return text;
	}
}
