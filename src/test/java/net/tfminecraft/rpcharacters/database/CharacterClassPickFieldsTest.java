package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

class CharacterClassPickFieldsTest {
    @SuppressWarnings("unchecked")
    @Test
    void unlockedClassesRoundTripAndFilesWithoutThemLoadWithNone() throws Exception {
        RPCharacter character = new RPCharacter(mock(Player.class));
        HashMap<String, Object> none = new HashMap<>();
        CharacterClassPickFields.save(none, character);
        assertTrue(none.isEmpty());
        CharacterClassPickFields.load(character, new JSONObject());
        CharacterClassPickFields.load(character, null);
        CharacterClassPickFields.load(null, new JSONObject());
        assertTrue(character.getUnlockedClasses().isEmpty());
        character.addUnlockedClass("blood_mage");
        character.addUnlockedClass("Shade");
        assertFalse(character.addUnlockedClass("SHADE"));
        assertThrows(UnsupportedOperationException.class, () -> character.getUnlockedClasses().add("X"));
        HashMap<String, Object> saved = new HashMap<>();
        CharacterClassPickFields.save(saved, character);
        assertEquals(Set.of("unlocked-classes"), saved.keySet());
        JSONObject json = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());
        RPCharacter loaded = new RPCharacter(mock(Player.class));
        CharacterClassPickFields.load(loaded, json);
        assertEquals(List.of("BLOOD_MAGE", "SHADE"), List.copyOf(loaded.getUnlockedClasses()));
        assertTrue(loaded.removeUnlockedClass("shade"));
        assertFalse(loaded.removeUnlockedClass("shade"));
        RPCharacter odd = new RPCharacter(mock(Player.class));
        JSONObject oddJson = new JSONObject();
        oddJson.put("unlocked-classes", Arrays.asList(" ", 5, null, " x "));
        CharacterClassPickFields.load(odd, oddJson);
        assertEquals(Set.of("X"), odd.getUnlockedClasses());
        oddJson.put("unlocked-classes", "BLOOD_MAGE");
        RPCharacter scalar = new RPCharacter(mock(Player.class));
        CharacterClassPickFields.load(scalar, oddJson);
        assertTrue(scalar.getUnlockedClasses().isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void classSkillPointPoolsRoundTripAndSkipOddEntries() throws Exception {
        RPCharacter character = new RPCharacter(mock(Player.class));
        assertTrue(character.getClassSkillPoints("bloodsworn").isEmpty());
        assertTrue(character.getClassSkillPoints(null).isEmpty());
        character.setClassSkillPoints("bloodsworn", 8);
        character.setClassSkillPoints("Sanguinar", -3);
        assertEquals(8, character.getClassSkillPoints("BLOODSWORN").getAsInt());
        assertEquals(0, character.getClassSkillPoints("sanguinar").getAsInt());
        assertThrows(UnsupportedOperationException.class, () -> character.getClassSkillPoints().put("X", 1));
        HashMap<String, Object> saved = new HashMap<>();
        CharacterClassPickFields.save(saved, character);
        assertEquals(Set.of("class-skill-points"), saved.keySet());
        JSONObject json = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());
        RPCharacter loaded = new RPCharacter(mock(Player.class));
        CharacterClassPickFields.load(loaded, json);
        assertEquals(java.util.Map.of("BLOODSWORN", 8, "SANGUINAR", 0), loaded.getClassSkillPoints());
        loaded.removeClassSkillPoints("bloodsworn");
        assertTrue(loaded.getClassSkillPoints("BLOODSWORN").isEmpty());
        JSONObject odd = new JSONObject();
        HashMap<Object, Object> pools = new HashMap<>();
        pools.put(" ", 1);
        pools.put("shade", "x");
        pools.put(" Seer ", 2L);
        pools.put(5, 5);
        odd.put("class-skill-points", pools);
        RPCharacter oddCharacter = new RPCharacter(mock(Player.class));
        CharacterClassPickFields.load(oddCharacter, odd);
        assertEquals(java.util.Map.of("SEER", 2), oddCharacter.getClassSkillPoints());
        odd.put("class-skill-points", "BLOODSWORN");
        RPCharacter scalar = new RPCharacter(mock(Player.class));
        CharacterClassPickFields.load(scalar, odd);
        assertTrue(scalar.getClassSkillPoints().isEmpty());
    }
}
