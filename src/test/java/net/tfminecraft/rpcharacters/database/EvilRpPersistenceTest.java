package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
        character.setLastStrikeAtMs(1_789_000_000_456L);

        HashMap<String, Object> saved = new HashMap<>();
        CharacterEvilRpFields.save(saved, character);
        // Round-trip through text like the character file does, so longs come back as Long.
        JSONObject reparsed = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());

        RPCharacter loaded = new RPCharacter(null);
        CharacterEvilRpFields.load(loaded, reparsed);
        assertEquals(2, loaded.getEvilRpStrikes());
        assertEquals(1_790_000_000_123L, loaded.getEvilRpSessionEndsAtMs());
        assertEquals(1_789_000_000_456L, loaded.getLastStrikeAtMs());
    }

    @Test
    void killerCooldownSurvivesAfterTheStrikesAreGone() throws Exception {
        UUID killer = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RPCharacter character = new RPCharacter(null);
        Map<UUID, Long> strikes = new LinkedHashMap<>();
        strikes.put(killer, 1_700_000_000_000L);
        strikes.put(other, 1_700_000_100_000L);
        character.setStrikesByKiller(strikes);

        HashMap<String, Object> saved = new HashMap<>();
        CharacterEvilRpFields.save(saved, character);
        JSONObject reparsed = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());

        RPCharacter loaded = new RPCharacter(null);
        CharacterEvilRpFields.load(loaded, reparsed);
        assertEquals(0, loaded.getEvilRpStrikes());
        assertEquals(1_700_000_000_000L, loaded.getStrikesByKiller().get(killer));
        assertEquals(1_700_000_100_000L, loaded.getStrikesByKiller().get(other));
    }

    @Test
    void aBadKillerIdDoesNotDropTheRest() {
        UUID killer = UUID.fromString("11111111-1111-1111-1111-111111111111");
        JSONObject byKiller = new JSONObject();
        byKiller.put("not-a-uuid", 50L);
        byKiller.put("bad-time", "nope");
        byKiller.put(killer.toString(), 80L);
        JSONObject evilRp = new JSONObject();
        evilRp.put("strikes-by-killer", byKiller);

        RPCharacter loaded = new RPCharacter(null);
        CharacterEvilRpFields.load(loaded, new JSONObject(Map.of("evil-rp", evilRp)));
        assertEquals(Map.of(killer, 80L), loaded.getStrikesByKiller());
    }

    @Test
    void blankKillerEntriesAreNotKept() {
        RPCharacter character = new RPCharacter(null);
        character.setStrikesByKiller(null);
        assertTrue(character.getStrikesByKiller().isEmpty());

        UUID kept = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Map<UUID, Long> messy = new HashMap<>();
        messy.put(null, 1L);
        messy.put(UUID.randomUUID(), 0L);
        messy.put(kept, 5L);
        character.setStrikesByKiller(messy);
        assertEquals(Map.of(kept, 5L), character.getStrikesByKiller());
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
