package net.tfminecraft.rpcharacters.database;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;

/**
 * Per-stage change state in the character file: paid change counts under "paid-changes", the
 * stage revision each lock window was opened for under "stage-revisions" (both keyed by stage id),
 * and denars held for an open paid stage under "paid-change-pending".
 */
public final class CharacterStageChangeFields {
	private CharacterStageChangeFields() {}

	public static void load(RPCharacter character, JSONObject characterJson) {
		if (character == null || characterJson == null) {
			return;
		}
		if (characterJson.get("paid-changes") instanceof Map<?, ?> counts) {
			for (Map.Entry<?, ?> entry : counts.entrySet()) {
				if (entry.getKey() instanceof String stageId && entry.getValue() instanceof Number count) {
					character.setPaidChangeCount(stageId, count.intValue());
				}
			}
		}
		if (characterJson.get("paid-change-pending") instanceof Map<?, ?> pending) {
			character.setPendingPaidChange(readPending(pending));
		}
		if (characterJson.get("stage-revisions") instanceof Map<?, ?> revisions) {
			for (Map.Entry<?, ?> entry : revisions.entrySet()) {
				if (entry.getKey() instanceof String stageId && entry.getValue() instanceof Map<?, ?> mark
						&& mark.get("revision") instanceof Number revision) {
					long since = mark.get("since") instanceof Number n ? n.longValue() : 0L;
					character.setStageRevision(stageId, revision.intValue(), since);
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
			held.put("payer", pending.payerId().toString());
			if (pending.account() != null) {
				held.put("account", pending.account().name());
			}
			held.put("amount", pending.amount());
			held.put("before", pending.before());
			defaults.put("paid-change-pending", held);
		}
	}

	private static PendingPaidChange readPending(Map<?, ?> held) {
		try {
			Account account = held.get("account") instanceof String name ? Account.valueOf(name) : null;
			double amount = held.get("amount") instanceof Number n ? n.doubleValue() : 0.0;
			return new PendingPaidChange((String) held.get("stage"), (String) held.get("label"),
					UUID.fromString((String) held.get("payer")), account, amount, (String) held.get("before"));
		} catch (RuntimeException e) {
			Logger.getLogger("RPCharacters").warning("[RPCharacters] Unreadable paid-change-pending entry " + held
					+ "; refund it by hand if denars were taken.");
			return null;
		}
	}
}
