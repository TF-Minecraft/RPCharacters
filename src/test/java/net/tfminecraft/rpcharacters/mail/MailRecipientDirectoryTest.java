package net.tfminecraft.rpcharacters.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class MailRecipientDirectoryTest {
    private final UUID owner = UUID.randomUUID();
    private final String id = UUID.randomUUID().toString();

    @AfterEach void cleanup() {
        MailRecipientDirectory.remove(id);
    }

    private void loadDirectory(JSONObject json) throws Exception {
        Method load = MailRecipientDirectory.class.getDeclaredMethod("upsertFromJson", UUID.class, JSONObject.class);
        load.setAccessible(true);
        load.invoke(null, owner, json);
    }

    @Test void savedOptOutIsRespectedAndWardrobeRefreshCannotReintroduceIt() throws Exception {
        JSONObject json = new JSONObject(Map.of("id", id, "name", "Recipient", "status", "ALIVE"));
        try (var bukkit = mockStatic(Bukkit.class); var manager = mockStatic(PlayerManager.class)) {
            loadDirectory(json);
            assertEquals(1, MailRecipientDirectory.listMailTargets().size());
            json.put("mail-listed", false);
            loadDirectory(json);
            MailRecipientDirectory.updateWardrobeTexture(owner, id, "texture", "signature");
            assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());
            json.put("mail-listed", true);
            loadDirectory(json);
            assertEquals(1, MailRecipientDirectory.listMailTargets().size());
            json.put("status", "DEAD");
            loadDirectory(json);
            MailRecipientDirectory.updateWardrobeTexture(owner, id, "texture", "signature");
            assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());
        }
    }

    @Test void liveOptOutOverridesCachedEntryAndOnlineFallback() {
        RPCharacter character = new RPCharacter(null);
        character.setId(id);
        character.setName("Recipient");
        PlayerData data = mock(PlayerData.class);
        when(data.getUniqueId()).thenReturn(owner);
        when(data.getCharacters(Status.ALIVE)).thenReturn(List.of(character));
        when(data.getCharacterById(id)).thenReturn(character);
        try (var bukkit = mockStatic(Bukkit.class); var manager = mockStatic(PlayerManager.class)) {
            manager.when(PlayerManager::getOnlineData).thenReturn(List.of(data));
            manager.when(() -> PlayerManager.get(owner)).thenReturn(data);
            MailRecipientDirectory.upsert(owner, character);
            assertEquals(1, MailRecipientDirectory.listMailTargets().size());
            character.setMailListed(false);
            assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());
            MailRecipientDirectory.upsert(owner, character);
            assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());
            character.setMailListed(true);
            assertEquals(1, MailRecipientDirectory.listMailTargets().size());
            MailRecipientDirectory.upsert(owner, character);
            manager.when(PlayerManager::getOnlineData).thenReturn(List.of());
            manager.when(() -> PlayerManager.get(owner)).thenReturn(null);
            assertEquals(1, MailRecipientDirectory.listMailTargets().size());
        }
    }

    @Test void preferenceRoundTripsThroughCharacterPersistenceAndLegacyDefaultsToListed() throws Exception {
        RPCharacter original = new RPCharacter(null);
        assertTrue(original.isMailListed());
        Method save = Database.class.getDeclaredMethod("savePersonaFields", HashMap.class, RPCharacter.class);
        Method load = Database.class.getDeclaredMethod("loadPersonaFields", RPCharacter.class, JSONObject.class);
        save.setAccessible(true);
        load.setAccessible(true);
        Database database = new Database();
        for (boolean listed : List.of(false, true)) {
            original.setMailListed(listed);
            HashMap<String, Object> fields = new HashMap<>();
            save.invoke(database, fields, original);
            RPCharacter restored = new RPCharacter(null);
            load.invoke(database, restored, new JSONObject(fields));
            assertEquals(listed, restored.isMailListed());
        }
        original.setMailListed(false);
        load.invoke(database, original, new JSONObject());
        assertTrue(original.isMailListed());
    }
}
