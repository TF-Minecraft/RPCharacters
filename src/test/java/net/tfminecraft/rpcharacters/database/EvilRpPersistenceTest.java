package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;

class EvilRpPersistenceTest {

    @Test
    void strikesAndSessionEndSurviveASaveAndLoad() throws Exception {
        RPCharacter character = new RPCharacter(null);
        character.setEvilRpStrikes(2);
        character.setEvilRpSessionEndsAtMs(1_790_000_000_123L);

        HashMap<String, Object> saved = new HashMap<>();
        CharacterEvilRpFields.save(saved, character);
        // Round-trip through text like the character file does, so longs come back as Long.
        JSONObject reparsed = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());

        RPCharacter loaded = new RPCharacter(null);
        CharacterEvilRpFields.load(loaded, reparsed);
        assertEquals(2, loaded.getEvilRpStrikes());
        assertEquals(1_790_000_000_123L, loaded.getEvilRpSessionEndsAtMs());
    }

    @Test
    void cleanCharactersWriteNothing() {
        HashMap<String, Object> saved = new HashMap<>();
        CharacterEvilRpFields.save(saved, new RPCharacter(null));
        assertTrue(saved.isEmpty());
    }

    @Test
    void oldPermadeathDismissalCarriesOver() {
        PlayerData pd = new PlayerData(UUID.randomUUID());
        PlayerTutorialFields.load(pd, new JSONObject(Map.of("permadeath-tutorial-dismissed", "true")));

        assertTrue(pd.hasDismissedTutorial(TutorialService.PERMADEATH_ZONE));
        assertFalse(pd.hasDismissedTutorial(TutorialService.EVIL_RP));
    }

    @Test
    void dismissedTutorialsRoundTrip() {
        PlayerData pd = new PlayerData(UUID.randomUUID());
        pd.setTutorialDismissed("Evil-RP", true);

        HashMap<String, Object> saved = new HashMap<>();
        PlayerTutorialFields.save(saved, pd);
        assertEquals(List.of("evil-rp"), saved.get("dismissed-tutorials"));

        PlayerData loaded = new PlayerData(UUID.randomUUID());
        JSONArray ids = new JSONArray();
        ids.add("evil-rp");
        PlayerTutorialFields.load(loaded, new JSONObject(Map.of("dismissed-tutorials", ids)));
        assertTrue(loaded.hasDismissedTutorial(TutorialService.EVIL_RP));
    }
}
