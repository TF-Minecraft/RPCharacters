package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/** How many paid changes each rule has had, stored under "paid-changes" in the character file. */
public final class CharacterPaidChangeFields {
	private CharacterPaidChangeFields() {}

	public static void load(RPCharacter character, JSONObject characterJson) {
		if (character == null || characterJson == null
				|| !(characterJson.get("paid-changes") instanceof Map<?, ?> counts)) {
			return;
		}
		for (Map.Entry<?, ?> entry : counts.entrySet()) {
			if (entry.getKey() instanceof String ruleId && entry.getValue() instanceof Number count) {
				character.setPaidChangeCount(ruleId, count.intValue());
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, RPCharacter character) {
		if (character.getPaidChangeCounts().isEmpty()) {
			return;
		}
		JSONObject counts = new JSONObject();
		counts.putAll(character.getPaidChangeCounts());
		defaults.put("paid-changes", counts);
	}
}
