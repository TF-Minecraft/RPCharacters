package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Per-stage change state in the character file: paid change counts under "paid-changes" and the
 * stage revision each lock window was opened for under "stage-revisions". Both are keyed by stage id.
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
	}
}
