package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Class picker state in the character file, under "class-picks": whether the free subclass pick
 * is used ("subclass-picked") and how many picks were paid for ("paid").
 */
public final class CharacterClassPickFields {
	private CharacterClassPickFields() {}

	public static void load(RPCharacter character, JSONObject characterJson) {
		if (character == null || characterJson == null
				|| !(characterJson.get("class-picks") instanceof Map<?, ?> picks)) {
			return;
		}
		character.setSubclassPicked(Boolean.TRUE.equals(picks.get("subclass-picked")));
		if (picks.get("paid") instanceof Number paid) {
			character.setPaidClassPicks(paid.intValue());
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, RPCharacter character) {
		if (!character.hasPickedSubclass() && character.getPaidClassPicks() == 0) {
			return;
		}
		JSONObject picks = new JSONObject();
		picks.put("subclass-picked", character.hasPickedSubclass());
		picks.put("paid", character.getPaidClassPicks());
		defaults.put("class-picks", picks);
	}
}
