package net.tfminecraft.rpcharacters.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class MailRecipientDirectoryTest {
    @TempDir Path folder;
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

    @Test void textureRefreshIsSharedAndDoesNotRetryFailedLookupsOnEveryOpen() throws Exception {
        RPCharacters previous = RPCharacters.plugin;
        RPCharacters plugin = mock(RPCharacters.class);
        when(plugin.isEnabled()).thenReturn(true);
        RPCharacters.plugin = plugin;
        List<Runnable> async = new ArrayList<>();
        List<Runnable> sync = new ArrayList<>();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
            .thenAnswer(call -> { async.add(call.getArgument(1)); return null; });
        when(scheduler.runTask(eq(plugin), any(Runnable.class)))
            .thenAnswer(call -> { sync.add(call.getArgument(1)); return null; });
        int[] callbacks = {0};
        Runnable callback = () -> callbacks[0]++;
        try (var bukkit = mockStatic(Bukkit.class); var client = mockStatic(ProvinceSystemClient.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            client.when(() -> ProvinceSystemClient.fetchWardrobe(anyString(), anyString()))
                .thenReturn(ProvinceSystemClient.SimpleResult.fail("Read timed out"));
            loadDirectory(new JSONObject(Map.of("id", id, "name", "Recipient", "status", "ALIVE")));

            MailRecipientDirectory.refreshMissingTexturesAsync(callback);
            MailRecipientDirectory.refreshMissingTexturesAsync(callback);
            assertEquals(1, async.size(), "a second open joins the refresh already running");
            async.removeFirst().run();
            client.verify(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id), times(1));
            sync.forEach(Runnable::run);
            sync.clear();
            assertEquals(2, callbacks[0]);

            MailRecipientDirectory.refreshMissingTexturesAsync(callback);
            assertTrue(async.isEmpty(), "a failed lookup is not retried straight away");
            sync.forEach(Runnable::run);
            assertEquals(3, callbacks[0]);
        } finally {
            RPCharacters.plugin = previous;
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
        // saveCharacter initializes the serializer from an empty character document.
        var jsonField = Database.class.getDeclaredField("json");
        jsonField.setAccessible(true);
        jsonField.set(database, new JSONObject());
        for (boolean listed : List.of(false, true)) {
            original.setMailListed(listed);
            HashMap<String, Object> fields = new HashMap<>();
            save.invoke(database, fields, original);
            RPCharacter restored = new RPCharacter(null);
            Path file = folder.resolve("character.json");
            assertTrue(database.save(file.toFile(), fields));
            JSONObject persisted;
            try (var reader = Files.newBufferedReader(file)) {
                persisted = (JSONObject) new JSONParser().parse(reader);
            }
            load.invoke(database, restored, persisted);
            assertEquals(listed, restored.isMailListed());
            persisted.put("id", id);
            persisted.put("name", "Recipient");
            try (var bukkit = mockStatic(Bukkit.class); var manager = mockStatic(PlayerManager.class)) {
                loadDirectory(persisted);
                assertEquals(listed ? 1 : 0, MailRecipientDirectory.listMailTargets().size());
            }
        }
        original.setMailListed(false);
        load.invoke(database, original, new JSONObject());
        assertTrue(original.isMailListed());
    }
}
