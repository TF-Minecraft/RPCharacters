package net.tfminecraft.RPCharacters.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

import net.tfminecraft.RPCharacters.Database.CharacterNutritionFields;

class CharacterNutritionTest {

    @Test
    void rawScoreStaysAboveTheEffectiveCap() {
        RPCharacter character = new RPCharacter(null);
        character.setRawDietScore(80);
        character.setDietScore(80);

        assertEquals(80, character.getRawDietScore());
        assertEquals(40, character.getDietScore());

        character.setRawDietScore(-4);
        assertEquals(0, character.getRawDietScore());
    }

    @Test
    void legacyDietScoreInitializesRawAndEffective() {
        RPCharacter character = new RPCharacter(null);
        JSONObject json = new JSONObject();
        json.put("diet-score", 18L);

        CharacterNutritionFields.load(character, json);

        assertEquals(18, character.getRawDietScore());
        assertEquals(18, character.getDietScore());
    }

    @Test
    void rawAndEffectiveLoadIndependently() {
        RPCharacter character = new RPCharacter(null);
        JSONObject json = new JSONObject();
        json.put("raw-diet-score", 80L);
        json.put("diet-score", 32L);
        json.put("last-diet-tier", "good");

        CharacterNutritionFields.load(character, json);

        assertEquals(80, character.getRawDietScore());
        assertEquals(32, character.getDietScore());
        assertEquals("good", character.getLastDietTierId());

        HashMap<String, Object> saved = new HashMap<>();
        CharacterNutritionFields.save(saved, character);
        assertEquals(80, saved.get("raw-diet-score"));
        assertEquals(32, saved.get("diet-score"));
    }
}
