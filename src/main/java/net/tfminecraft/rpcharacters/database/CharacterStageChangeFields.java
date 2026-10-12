package net.tfminecraft.rpcharacters.database;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.Payment;
import net.tfminecraft.rpcharacters.paidchange.Payments;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;

/**
 * Per-stage change state in the character file: paid change counts under "paid-changes", the
 * stage revision each lock window was opened for under "stage-revisions" (both keyed by stage id),
 * and denars held for an open paid stage under "paid-change-pending". A pending entry from before
 * the character ledger (an account "payer" and an "amount", no operation IDs) loads as a legacy
 * payment, refunded to the account's legacy heir with {@code refund-legacy:<character>:<stage>}.
 */
public final class CharacterStageChangeFields {
	private CharacterStageChangeFields() {}

	/** The old wallet already took this from the account's money; null when it was free. */
	private static Payment legacyPayment(RPCharacter character, String stage, Map<?, ?> held) {
		UUID accountId = UUID.fromString((String) held.get("payer"));
		long cents = Payments.cents(new BigDecimal(String.valueOf(held.get("amount"))));
		if (!(held.get("account") instanceof String name) || cents <= 0) {
			return null;
		}
		return Payment.legacy(accountId, Account.valueOf(name), cents,
				"refund-legacy:" + character.getId() + ":" + stage.toLowerCase(Locale.ROOT));
	}

	public static void load(RPCharacter character, JSONObject characterJson) {
		if (character == null || characterJson == null) {
			return;
		}
		if (characterJson.get("paid-changes") instanceof Map<?, ?> counts) {
			for (Map.Entry<?, ?> entry : counts.entrySet()) {
				if (entry.getKey() instanceof String stageId && entry.getValue() instanceof Number count) {
					character.setPaidChangeCount(stageId, (int) count.doubleValue());
				}
			}
		}
		if (characterJson.get("paid-change-pending") instanceof Map<?, ?> pending) {
			character.setPendingPaidChange(readPending(character, pending));
		}
		if (characterJson.get("stage-revisions") instanceof Map<?, ?> revisions) {
			for (Map.Entry<?, ?> entry : revisions.entrySet()) {
				if (entry.getKey() instanceof String stageId && entry.getValue() instanceof Map<?, ?> mark
						&& mark.get("revision") instanceof Number revision) {
					long since = mark.get("since") instanceof Number n ? n.longValue() : 0L;
					character.setStageRevision(stageId, (int) revision.doubleValue(), since);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, RPCharacter character) {
		if (!character.getPaidChangeCounts().isEmpty()) {
			JSONObject counts = new JSONObject();
			counts.putAll(character.getPaidChangeCounts());
			defaults.put("paid-changes", counts);
		}
		if (!character.getStageRevisions().isEmpty()) {
			JSONObject revisions = new JSONObject();
			for (Map.Entry<String, Integer> entry : character.getStageRevisions().entrySet()) {
				JSONObject mark = new JSONObject();
				mark.put("revision", entry.getValue());
				long since = character.getStageRevisionSince(entry.getKey());
				if (since > 0L) {
					mark.put("since", since);
				}
				revisions.put(entry.getKey(), mark);
			}
			defaults.put("stage-revisions", revisions);
		}
		PendingPaidChange pending = character.getPendingPaidChange();
		if (pending != null) {
			JSONObject held = new JSONObject();
			held.put("stage", pending.stageId());
			held.put("label", pending.label());
			if (pending.payment() != null) {
				held.put("payment", pending.payment().toJson());
			}
			held.put("before", pending.before());
			defaults.put("paid-change-pending", held);
		}
	}

	private static PendingPaidChange readPending(RPCharacter character, Map<?, ?> held) {
		try {
			if (!(held.get("stage") instanceof String stage) || !(held.get("before") instanceof String)) {
				throw new IllegalArgumentException("No stage or snapshot");
			}
			Payment payment = held.get("payment") instanceof Map<?, ?> json ? Payment.fromJson(json)
					: held.containsKey("payer") ? legacyPayment(character, stage, held) : null;
			return new PendingPaidChange(stage, (String) held.get("label"), payment, (String) held.get("before"));
		} catch (RuntimeException e) {
			Logger.getLogger("RPCharacters").warning("[RPCharacters] Unreadable paid-change-pending entry " + held
					+ "; refund it by hand if denars were taken.");
			return null;
		}
	}
}
