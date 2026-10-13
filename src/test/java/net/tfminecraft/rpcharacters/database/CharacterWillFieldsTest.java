package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.will.CharacterWill;

class CharacterWillFieldsTest {
    @SuppressWarnings("unchecked")
    @Test
    void aWillRoundTripsThroughTheCharacterFileAndNoWillWritesNothing() {
        RPCharacter character = new RPCharacter(mock(Player.class));
        HashMap<String, Object> none = new HashMap<>();
        CharacterWillFields.save(none, character);
        assertTrue(none.isEmpty());
        CharacterWillFields.load(character, new JSONObject());
        assertSame(CharacterWill.NONE, character.getWill());
        UUID heir = UUID.randomUUID();
        character.setWill(new CharacterWill(heir, 3, 99L, "req"));
        HashMap<String, Object> saved = new HashMap<>();
        CharacterWillFields.save(saved, character);
        assertEquals(4, saved.size());
        RPCharacter loaded = new RPCharacter(mock(Player.class));
        JSONObject json = new JSONObject();
        json.putAll(saved);
        CharacterWillFields.load(loaded, json);
        assertEquals(character.getWill(), loaded.getWill());
        character.setWill(new CharacterWill(null, 4, null, null));
        HashMap<String, Object> revoked = new HashMap<>();
        CharacterWillFields.save(revoked, character);
        assertEquals(1, revoked.size());
        JSONObject bare = new JSONObject();
        bare.putAll(revoked);
        CharacterWillFields.load(loaded, bare);
        assertEquals(new CharacterWill(null, 4, null, null), loaded.getWill());
        assertThrows(NullPointerException.class, () -> loaded.setWill(null));
    }
}
