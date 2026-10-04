package net.tfminecraft.rpcharacters.database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/** Strike count, latest strike time, per-killer cooldowns and evil RP session end, stored under "evil-rp". */
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
		if (evilRp.get("strikes-by-killer") instanceof Map<?, ?> byKiller) {
			Map<UUID, Long> strikes = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : byKiller.entrySet()) {
				if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof Number at)) {
					continue;
				}
				try {
					strikes.put(UUID.fromString(id), at.longValue());
				} catch (IllegalArgumentException ex) {
					// A bad id in an old file should not drop the rest of the character.
				}
			}
			character.setStrikesByKiller(strikes);
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, RPCharacter character) {
		JSONObject byKiller = strikesByKiller(character);
		if (character.getEvilRpStrikes() <= 0 && character.getEvilRpSessionEndsAtMs() <= 0 && byKiller.isEmpty()) {
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
		if (!byKiller.isEmpty()) {
			evilRp.put("strikes-by-killer", byKiller);
		}
		defaults.put("evil-rp", evilRp);
	}

	@SuppressWarnings("unchecked")
	private static JSONObject strikesByKiller(RPCharacter character) {
		JSONObject byKiller = new JSONObject();
		for (Map.Entry<UUID, Long> entry : character.getStrikesByKiller().entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
				byKiller.put(entry.getKey().toString(), entry.getValue());
			}
		}
		return byKiller;
	}
}
