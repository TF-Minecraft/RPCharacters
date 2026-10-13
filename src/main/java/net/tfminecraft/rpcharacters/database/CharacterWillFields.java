package net.tfminecraft.rpcharacters.database;

import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.will.CharacterWill;

/**
 * The will in the character file: {@code will-heir}, {@code will-revision}, {@code will-updated-at-ms}
 * and {@code will-last-request}. A character that never had a will writes none of them.
 */
public final class CharacterWillFields {
	private CharacterWillFields() {}

	public static void load(RPCharacter character, JSONObject json) {
		if (!(json.get("will-revision") instanceof Number revision)) return;
		UUID heir = json.get("will-heir") instanceof String text ? UUID.fromString(text) : null;
		Long updated = json.get("will-updated-at-ms") instanceof Number at ? at.longValue() : null;
		String request = json.get("will-last-request") instanceof String text ? text : null;
		character.setWill(new CharacterWill(heir, revision.longValue(), updated, request));
	}

	public static void save(Map<String, Object> defaults, RPCharacter character) {
		CharacterWill will = character.getWill();
		if (will.revision() == 0) return;
		defaults.put("will-revision", will.revision());
		if (will.heir() != null) defaults.put("will-heir", will.heir().toString());
		if (will.updatedAtMs() != null) defaults.put("will-updated-at-ms", will.updatedAtMs());
		if (will.lastRequest() != null) defaults.put("will-last-request", will.lastRequest());
	}
}
