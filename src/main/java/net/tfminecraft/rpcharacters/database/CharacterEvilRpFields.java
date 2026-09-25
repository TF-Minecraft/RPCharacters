package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/** Strike count, latest strike time and evil RP session end, stored under "evil-rp" in the character file. */
public final class CharacterEvilRpFields {
	private CharacterEvilRpFields() {}

	public static void load(RPCharacter character, JSONObject characterJson) {
		if (character == null || characterJson == null
				|| !(characterJson.get("evil-rp") instanceof Map<?, ?> evilRp)) {
			return;
		}
		if (evilRp.get("strikes") instanceof Number strikes) {
			character.setEvilRpStrikes(strikes.intValue());
		}
		if (evilRp.get("session-ends-at") instanceof Number endsAt) {
			character.setEvilRpSessionEndsAtMs(endsAt.longValue());
		}
		if (evilRp.get("last-strike-at") instanceof Number lastStrikeAt) {
			character.setLastStrikeAtMs(lastStrikeAt.longValue());
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, RPCharacter character) {
		if (character.getEvilRpStrikes() <= 0 && character.getEvilRpSessionEndsAtMs() <= 0) {
			return;
		}
		// Nested in a JSONObject so the epoch-ms long survives Database.save, which drops Long values.
		JSONObject evilRp = new JSONObject();
		evilRp.put("strikes", character.getEvilRpStrikes());
		if (character.getEvilRpSessionEndsAtMs() > 0) {
			evilRp.put("session-ends-at", character.getEvilRpSessionEndsAtMs());
		}
		if (character.getEvilRpStrikes() > 0 && character.getLastStrikeAtMs() > 0) {
			evilRp.put("last-strike-at", character.getLastStrikeAtMs());
		}
		defaults.put("evil-rp", evilRp);
	}
}
