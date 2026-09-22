package net.tfminecraft.RPCharacters.Database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.RPCharacters.Objects.RPCharacter;

public final class CharacterNutritionFields {
    private CharacterNutritionFields() {}

    public static void load(RPCharacter character, JSONObject characterJson) {
        if (character == null || characterJson == null) {
            return;
        }
        boolean hasRaw = characterJson.containsKey("raw-diet-score");
        boolean hasDiet = characterJson.containsKey("diet-score");
        if (hasRaw && hasDiet) {
            character.setRawDietScore(intValue(characterJson.get("raw-diet-score")));
            character.setDietScore(intValue(characterJson.get("diet-score")));
        } else if (hasDiet) {
            int legacy = intValue(characterJson.get("diet-score"));
            character.setRawDietScore(legacy);
            character.setDietScore(legacy);
        } else if (hasRaw) {
            int raw = intValue(characterJson.get("raw-diet-score"));
            character.setRawDietScore(raw);
            character.setDietScore(raw);
        }
        if (characterJson.containsKey("last-diet-tier")) {
            Object raw = characterJson.get("last-diet-tier");
            if (raw != null) {
                character.setLastDietTierId(raw.toString());
            }
        }
    }

    public static void save(Map<String, Object> defaults, RPCharacter character) {
        if (defaults == null || character == null) {
            return;
        }
        defaults.put("food-value", character.getFoodValue());
        defaults.put("raw-diet-score", character.getRawDietScore());
        defaults.put("diet-score", character.getDietScore());
        if (character.getLastDietTierId() != null && !character.getLastDietTierId().isBlank()) {
            defaults.put("last-diet-tier", character.getLastDietTierId());
        }
    }

    private static int intValue(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw));
    }
}
