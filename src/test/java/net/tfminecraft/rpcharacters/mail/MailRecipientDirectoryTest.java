package net.tfminecraft.rpcharacters.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.CharacterSkull;
import net.tfminecraft.rpcharacters.identity.*;
import net.tfminecraft.rpcharacters.wardrobe.*;
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

    private Path diskOwner;
    private RuntimeTestState directoryState;
    @BeforeEach void prepareDirectory() throws Exception {
        diskOwner = folder.resolve(owner.toString());
        directoryState = new RuntimeTestState(MailRecipientDirectory.class);
        // Isolate global fixture state; RuntimeTestState restores the original collections/flags.
        // Production paths are exercised through public APIs and actual files below.
        for (String name : List.of("ENTRIES", "TEXTURE_ATTEMPTS", "TEXTURE_WAITERS")) {
            var field = MailRecipientDirectory.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> map) map.clear();
            else ((Collection<?>) value).clear();
        }
        for (String name : List.of("textureRefreshRunning", "textureRefreshAgain")) {
            var field = MailRecipientDirectory.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, false);
        }
    }
    private final List<String> diskIds = new ArrayList<>();
    @AfterEach void cleanup() throws Exception {
        MailRecipientDirectory.remove(id);
        for (String value : diskIds) MailRecipientDirectory.remove(value);
        if (Files.exists(diskOwner)) try (var paths = Files.walk(diskOwner)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        directoryState.close();
    }

    private void loadDirectory(JSONObject json) throws Exception {
        Files.createDirectories(diskOwner);
        String characterId = String.valueOf(json.get("id"));
        diskIds.add(characterId);
        Files.writeString(diskOwner.resolve("record-" + diskIds.size() + ".json"), json.toJSONString());
        // Rewrite one record per character, so a later scan cannot resurrect an old version.
        for (int i = 0; i < diskIds.size() - 1; i++) if (diskIds.get(i).equals(characterId))
            Files.deleteIfExists(diskOwner.resolve("record-" + (i + 1) + ".json"));
        MailRecipientDirectory.scanFromDisk(folder.toFile());
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

    @Test void characterAddedDuringARefreshIsLookedUpBeforeCallbacksRun() throws Exception {
        String later = UUID.randomUUID().toString();
        RPCharacters previous = RPCharacters.plugin;
        RPCharacters plugin = mock(RPCharacters.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        RPCharacters.plugin = plugin;
        List<Runnable> async = new ArrayList<>();
        List<Runnable> sync = new ArrayList<>();
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
            .thenAnswer(call -> { async.add(call.getArgument(1)); return null; });
        when(scheduler.runTask(eq(plugin), any(Runnable.class)))
            .thenAnswer(call -> { sync.add(call.getArgument(1)); return null; });
        int[] callbacks = {0};
        try (var bukkit = mockStatic(Bukkit.class); var client = mockStatic(ProvinceSystemClient.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            client.when(() -> ProvinceSystemClient.fetchWardrobe(anyString(), anyString()))
                .thenReturn(ProvinceSystemClient.SimpleResult.fail("Read timed out"));
            loadDirectory(new JSONObject(Map.of("id", id, "name", "Recipient", "status", "ALIVE")));

            MailRecipientDirectory.refreshMissingTexturesAsync(() -> { throw new IllegalStateException("boom"); });
            loadDirectory(new JSONObject(Map.of("id", later, "name", "Later", "status", "ALIVE")));
            MailRecipientDirectory.refreshMissingTexturesAsync(() -> callbacks[0]++);
            async.removeFirst().run();
            assertTrue(sync.isEmpty(), "callbacks wait for the follow-up pass");
            assertEquals(1, async.size());
            async.removeFirst().run();
            client.verify(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), later), times(1));
            sync.forEach(Runnable::run);
            assertEquals(1, callbacks[0], "a failing callback does not stop the others");
        } finally {
            RPCharacters.plugin = previous;
            MailRecipientDirectory.remove(later);
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
    @Nested class RuntimeCoverage {
        ServerMock server;World world;RuntimeTestState state;RPCharacters plugin;Logger logger;Player player;RPCharacter character;PlayerData account;
        final List<AutoCloseable> boundaries=new ArrayList<>();final List<PlayerData> loaded=new ArrayList<>();final Map<UUID,PlayerData> accounts=new HashMap<>();
        final Deque<Runnable> async=new ArrayDeque<>(),main=new ArrayDeque<>();BukkitScheduler scheduler;MockedStatic<ProvinceSystemClient> api;MockedStatic<Bukkit> bukkit;MockedStatic<MaskService> masks;
        @BeforeEach void setup(){server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,MailRecipientDirectory.class,WardrobeCache.class);Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();world=server.addSimpleWorld("mail-world");plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);when(plugin.isEnabled()).thenReturn(true);RPCharacters.plugin=plugin;player=mock(Player.class);when(player.getUniqueId()).thenReturn(owner);when(player.getName()).thenReturn("Mailer");when(player.getServer()).thenReturn(server);when(player.isOnline()).thenReturn(true);when(player.getLocation()).thenAnswer(c->new Location(world,5,64,7));character=character(id);account=new PlayerData(player);account.addCharacter(character);
            var manager=boundary(PlayerManager.class);manager.when(PlayerManager::getOnlineData).thenReturn(loaded);manager.when(()->PlayerManager.get(any(UUID.class))).thenAnswer(c->accounts.get(c.getArgument(0)));
            scheduler=mock(BukkitScheduler.class);when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenAnswer(c->{async.add(c.getArgument(1));return mock(BukkitTask.class);});when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(c->{main.add(c.getArgument(1));return mock(BukkitTask.class);});
            bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);boundaries.add(bukkit);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);bukkit.when(()->Bukkit.getPlayer(owner)).thenReturn(null);api=boundary(ProvinceSystemClient.class);api.when(()->ProvinceSystemClient.fetchWardrobe(anyString(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("unavailable"));masks=boundary(MaskService.class);
        }
        @AfterEach void restore() throws Exception {for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close();MockBukkit.unmock();state.close();}
        <T> MockedStatic<T> boundary(Class<T> type){var m=mockStatic(type);boundaries.add(m);return m;}
        RPCharacter character(String cid){var c=spy(new RPCharacter(player));c.setId(cid);c.setName("Recipient "+cid);return c;}
        CharacterMailTarget target(String cid){return MailRecipientDirectory.listMailTargets().stream().filter(t->cid.equals(t.getCharacterId())).findFirst().orElse(null);}
        void listed(){MailRecipientDirectory.upsert(owner,character);}
        WardrobeSlotData slot(String key,String value,boolean filled){return new WardrobeSlotData(key,true,filled,true,false,null,null,value,"signature");}
        WardrobeSnapshot snapshot(String cid,String value){return new WardrobeSnapshot(cid,"base",1,Map.of("base",slot("base",value,true)));}
        String body(String cid,String value){return "{\"character_id\":\""+cid+"\",\"slots\":[{\"slot\":\"base\",\"filled\":true,\"texture_value\":\""+value+"\",\"texture_signature\":\"signature\"}]}";}
        String texture(ItemStack head){assertEquals(Material.PLAYER_HEAD,head.getType());var profile=((SkullMeta)head.getItemMeta()).getPlayerProfile();if(profile==null)return null;return profile.getProperties().stream().filter(p->"textures".equals(p.getName())).map(com.destroystokyo.paper.profile.ProfileProperty::getValue).findFirst().orElse(null);}
        void drain(){while(!async.isEmpty())async.remove().run();while(!main.isEmpty())main.remove().run();}

        @Test void diskScanLoadsAliasesColoursAndStoredLocationsAndSkipsCorruptRows() throws Exception {
            var json=new JSONObject(Map.of("id",id,"name","§cOriginal","alias","§aAlias","status","ALIVE","last-location",new JSONObject(Map.of("world","mail-world","x",1.5,"y",64L,"z",-2L)),"name-colour",NameColour.solid("#abcdef").toJsonObject()));loadDirectory(json);var t=target(id);assertNotNull(t);assertEquals(owner,t.getOwnerUuid());assertEquals("Alias",t.getDisplayPlain());assertTrue(t.getDisplayTab().endsWith("Alias"));assertEquals("mail-world",t.getWorldName());assertEquals(new Location(world,1.5,64,-2),t.getLocation());assertEquals(t.getLocation(),MailRecipientDirectory.getMailTargetLocation(owner,id));
            Files.writeString(diskOwner.resolve("corrupt.json"),"{");Files.writeString(diskOwner.resolve("array.json"),"[]");Files.writeString(diskOwner.resolve("ignored.txt"),"{}");Files.createDirectory(diskOwner.resolve("directory.json"));Files.writeString(diskOwner.resolve("invalid-status.json"),new JSONObject(Map.of("id","bad-status","status","unknown")).toJSONString());Files.writeString(diskOwner.resolve("missing-id.json"),"{}");Files.writeString(diskOwner.resolve("blank-id.json"),"{\"id\":\" \"}");MailRecipientDirectory.scanFromDisk(folder.toFile());assertEquals(1,MailRecipientDirectory.listMailTargets().size());
        }
        @Test void legacyDiskDefaultsAndMalformedLocationsRemainUsableWithoutCoordinates() throws Exception {var json=new JSONObject(Map.of("id",id,"name",17,"alias"," ","last-location",new JSONObject(Map.of("world","mail-world","x","bad","y",1L,"z",2L))));loadDirectory(json);assertEquals("",target(id).getDisplayPlain());assertNull(target(id).getLocation());json.put("name","Name");json.put("last-location",new JSONObject(Map.of("world","unloaded","x",1L,"y",2L,"z",3L)));loadDirectory(json);assertEquals("unloaded",target(id).getWorldName());assertNull(target(id).getLocation());assertNull(MailRecipientDirectory.getMailTargetLocation(owner,id));}
        @Test void persistedAliveStatusIsLocaleIndependent() throws Exception {Locale.setDefault(Locale.forLanguageTag("tr-TR"));loadDirectory(new JSONObject(Map.of("id",id,"name","Recipient","status","alive")));assertNotNull(target(id),"Turkish locale must not turn alive into ALİVE and hide the character");}
        @Test void onlineActiveCoordinatesAndNamesOverrideStoredValues(){character.setLastLocation("mail-world",1d,2d,3d);listed();character.setAlias("Live alias");doReturn(true).when(character).isActive();accounts.put(owner,account);loaded.add(account);var t=target(id);assertEquals("Live alias",t.getDisplayPlain());assertEquals(new Location(world,5,64,7),t.getLocation());assertEquals(t.getLocation(),MailRecipientDirectory.getMailTargetLocation(owner,id));doReturn(false).when(character).isActive();assertEquals(new Location(world,1,2,3),MailRecipientDirectory.getMailTargetLocation(owner,id));when(player.isOnline()).thenReturn(false);doReturn(true).when(character).isActive();assertEquals(new Location(world,1,2,3),target(id).getLocation());}
        @Test void onlineFallbackRespectsOptOutDeadCharactersAndOfflineOwnerLookup(){character.setLastLocation("mail-world",1d,2d,3d);loaded.add(null);loaded.add(account);accounts.put(owner,account);assertNotNull(target(id));character.setMailListed(false);assertNull(target(id));character.setMailListed(true);listed();character.setStatus(Status.DEAD);assertNull(target(id));character.setStatus(Status.ALIVE);doReturn(true).when(character).isActive();var offline=new PlayerData(owner);offline.addCharacter(character);accounts.put(owner,offline);bukkit.when(()->Bukkit.getPlayer(owner)).thenReturn(player);assertEquals(new Location(world,5,64,7),target(id).getLocation());}
        @Test void upsertPreservesTextureAndRejectsInvalidOrUnlistedRecords(){MailRecipientDirectory.upsert(null,character);MailRecipientDirectory.upsert(owner,null);var invalid=character(null);MailRecipientDirectory.upsert(owner,invalid);listed();MailRecipientDirectory.updateWardrobeTexture(owner,id,"texture","signature");character.setName("New name");listed();assertEquals("texture",target(id).getBaseTextureValue());assertEquals("signature",target(id).getBaseTextureSignature());assertEquals("New name",target(id).getDisplayPlain());character.setStatus(Status.DEAD);listed();assertNull(target(id));character.setStatus(Status.ALIVE);listed();character.setMailListed(false);listed();assertNull(target(id));}
        @Test void textureOnlyEntriesCannotBecomeRecipientsAndOwnerMismatchesCannotResolveLocations(){MailRecipientDirectory.updateWardrobeTexture(null,id,"v","s");MailRecipientDirectory.updateWardrobeTexture(owner,null,"v","s");MailRecipientDirectory.updateWardrobeTexture(owner," ","v","s");MailRecipientDirectory.updateWardrobeTexture(owner,id,null,"s");MailRecipientDirectory.updateWardrobeTexture(owner,id," ","s");MailRecipientDirectory.updateWardrobeTexture(owner,id,"v","s");assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());listed();assertEquals("v",target(id).getBaseTextureValue());assertNull(MailRecipientDirectory.getMailTargetLocation(owner,id));assertNull(MailRecipientDirectory.getMailTargetLocation(null,id));assertNull(MailRecipientDirectory.getMailTargetLocation(owner,null));assertNull(MailRecipientDirectory.getMailTargetLocation(owner," "));assertNull(MailRecipientDirectory.getMailTargetLocation(owner,"missing"));assertNull(MailRecipientDirectory.getMailTargetLocation(UUID.randomUUID(),id));MailRecipientDirectory.remove(null);MailRecipientDirectory.remove(id);assertNull(target(id));}
        @Test void baseSnapshotAndWardrobeFallbackIgnoreWrongCharacterOrEmptySlots(){listed();MailRecipientDirectory.cacheWardrobeSnapshot(null,snapshot(id,"v"));MailRecipientDirectory.cacheWardrobeSnapshot(owner,null);MailRecipientDirectory.cacheWardrobeSnapshot(owner,snapshot(null,"v"));MailRecipientDirectory.cacheWardrobeSnapshot(owner,new WardrobeSnapshot(id,null,1,Map.of()));MailRecipientDirectory.cacheWardrobeSnapshot(owner,new WardrobeSnapshot(id,null,1,Map.of("base",slot("base","v",false))));MailRecipientDirectory.cacheWardrobeSnapshot(owner,snapshot(id," "));assertNull(target(id).getBaseTextureValue());WardrobeCache.put(owner,snapshot("wrong","wrong"));assertNull(target(id).getBaseTextureValue());WardrobeCache.put(owner,new WardrobeSnapshot(id,null,1,Map.of()));assertNull(target(id).getBaseTextureValue());WardrobeCache.put(owner,snapshot(id,"fallback"));assertEquals("fallback",target(id).getBaseTextureValue());MailRecipientDirectory.cacheWardrobeSnapshot(owner,snapshot(id,"cached"));assertEquals("cached",target(id).getBaseTextureValue());}
        @Test void successfulRefreshCachesBaseTexturesAndCallbacksRunOnMain(){listed();api.when(()->ProvinceSystemClient.fetchWardrobe(owner.toString(),id)).thenReturn(ProvinceSystemClient.SimpleResult.success(body(id,"fetched")));var calls=new AtomicInteger();MailRecipientDirectory.refreshMissingTexturesAsync(calls::incrementAndGet);assertEquals(1,async.size());assertEquals(0,calls.get());async.remove().run();assertEquals("fetched",target(id).getBaseTextureValue());assertEquals(0,calls.get());main.remove().run();assertEquals(1,calls.get());MailRecipientDirectory.refreshMissingTexturesAsync(null);assertTrue(async.isEmpty());assertTrue(main.isEmpty());}
        @Test void disabledPluginDoesNotStartRefreshAndEmptyDirectoryStillCompletes(){RPCharacters.plugin=null;MailRecipientDirectory.refreshMissingTexturesAsync(()->fail("disabled"));RPCharacters.plugin=plugin;when(plugin.isEnabled()).thenReturn(false);MailRecipientDirectory.refreshMissingTexturesAsync(()->fail("disabled"));when(plugin.isEnabled()).thenReturn(true);var completed=new AtomicInteger();MailRecipientDirectory.refreshMissingTexturesAsync(completed::incrementAndGet);assertTrue(async.isEmpty());assertEquals(1,main.size());main.remove().run();assertEquals(1,completed.get());}
        @Test void clientExceptionStillFinishesWaitersAndLeavesRetryThrottled(){listed();api.when(()->ProvinceSystemClient.fetchWardrobe(owner.toString(),id)).thenThrow(new IllegalStateException("broken API"));var calls=new AtomicInteger();MailRecipientDirectory.refreshMissingTexturesAsync(calls::incrementAndGet);assertThrows(IllegalStateException.class,()->async.remove().run());main.remove().run();assertEquals(1,calls.get());MailRecipientDirectory.refreshMissingTexturesAsync(calls::incrementAndGet);assertTrue(async.isEmpty());main.remove().run();assertEquals(2,calls.get());}
        @Test void schedulingFailureMustNotWedgeRefreshOrThrottleAnUnstartedLookup(){listed();doThrow(new IllegalStateException("scheduler stopped")).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));var calls=new AtomicInteger();assertThrows(IllegalStateException.class,()->MailRecipientDirectory.refreshMissingTexturesAsync(calls::incrementAndGet));doAnswer(c->{async.add(c.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));MailRecipientDirectory.refreshMissingTexturesAsync(calls::incrementAndGet);assertEquals(1,async.size(),"An unstarted HTTP lookup must be retryable immediately");drain();assertEquals(2,calls.get(),"Every accepted waiter must complete after recovery");}
        @Test void fetchedSnapshotMustMatchTheRequestedCharacter(){listed();String secondId="other-"+id;var second=character(secondId);UUID secondOwner=UUID.randomUUID();MailRecipientDirectory.upsert(secondOwner,second);api.when(()->ProvinceSystemClient.fetchWardrobe(owner.toString(),id)).thenReturn(ProvinceSystemClient.SimpleResult.success(body(secondId,"wrong-person")));MailRecipientDirectory.refreshMissingTexturesAsync(null);drain();assertNotNull(target(secondId),"A mismatched response must not replace another owner's directory record");assertEquals(secondOwner,target(secondId).getOwnerUuid());assertNull(target(secondId).getBaseTextureValue());assertNull(target(id).getBaseTextureValue());}
        @Test void refreshCannotOverwriteATexturePublishedWhileTheFetchWasPending(){listed();api.when(()->ProvinceSystemClient.fetchWardrobe(owner.toString(),id)).thenAnswer(c->{MailRecipientDirectory.updateWardrobeTexture(owner,id,"newer","new-signature");return ProvinceSystemClient.SimpleResult.success(body(id,"older"));});MailRecipientDirectory.refreshMissingTexturesAsync(null);drain();assertEquals("newer",target(id).getBaseTextureValue());assertEquals("new-signature",target(id).getBaseTextureSignature());}
        @Test void routineCharacterSaveDuringFetchRetainsItsValidTextureResponse() {
            listed();
            api.when(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id)).thenAnswer(call -> {
                character.setName("Saved while loading");
                character.setLastLocation("mail-world", 8d, 65d, 9d);
                listed();
                return ProvinceSystemClient.SimpleResult.success(body(id, "fetched"));
            });
            MailRecipientDirectory.refreshMissingTexturesAsync(null);
            drain();
            assertEquals("Saved while loading", target(id).getDisplayPlain());
            assertEquals(new Location(world, 8, 65, 9), target(id).getLocation());
            assertEquals("fetched", target(id).getBaseTextureValue(),
                    "Saving the same character must not invalidate its in-flight texture lookup");
            assertEquals("signature", target(id).getBaseTextureSignature());
        }

        @Test void deletedAndRecreatedCharacterRejectsThePreviousGenerationsTexture() {
            listed();
            api.when(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id)).thenAnswer(call -> {
                MailRecipientDirectory.remove(id);
                listed();
                return ProvinceSystemClient.SimpleResult.success(body(id, "deleted-generation"));
            });
            MailRecipientDirectory.refreshMissingTexturesAsync(null);
            drain();
            assertNotNull(target(id));
            assertNull(target(id).getBaseTextureValue(), "Recreation must start a new lookup generation");
            api.when(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id))
                    .thenReturn(ProvinceSystemClient.SimpleResult.success(body(id, "recreated-generation")));
            MailRecipientDirectory.refreshMissingTexturesAsync(null);
            drain();
            assertEquals("recreated-generation", target(id).getBaseTextureValue());
        }

        @Test void diskRescanOfTheSameCharacterKeepsThePendingLookupValid() {
            listed();
            api.when(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id)).thenAnswer(call -> {
                loadDirectory(new JSONObject(Map.of("id", id, "name", "Reloaded recipient", "status", "ALIVE")));
                return ProvinceSystemClient.SimpleResult.success(body(id, "fetched-after-rescan"));
            });
            MailRecipientDirectory.refreshMissingTexturesAsync(null);
            drain();
            assertEquals("Reloaded recipient", target(id).getDisplayPlain());
            assertEquals("fetched-after-rescan", target(id).getBaseTextureValue());
        }

        @Test void changedOwnerRejectsThePreviousOwnersPendingResponse() {
            listed();
            UUID replacementOwner = UUID.randomUUID();
            api.when(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id)).thenAnswer(call -> {
                MailRecipientDirectory.upsert(replacementOwner, character);
                return ProvinceSystemClient.SimpleResult.success(body(id, "previous-owner"));
            });
            MailRecipientDirectory.refreshMissingTexturesAsync(null);
            drain();
            assertEquals(replacementOwner, target(id).getOwnerUuid());
            assertNull(target(id).getBaseTextureValue());
        }

        @Test void changingOwnerDoesNotCarryTheirPreviouslyPublishedTexture() {
            listed();
            MailRecipientDirectory.updateWardrobeTexture(owner, id, "previous-owner", "old-signature");
            UUID replacementOwner = UUID.randomUUID();
            MailRecipientDirectory.upsert(replacementOwner, character);
            assertEquals(replacementOwner, target(id).getOwnerUuid());
            assertNull(target(id).getBaseTextureValue());
            assertNull(target(id).getBaseTextureSignature());
        }

        @Test void routineSaveKeepsANewerTextureAndStillRejectsTheOldHttpResponse() {
            listed();
            api.when(() -> ProvinceSystemClient.fetchWardrobe(owner.toString(), id)).thenAnswer(call -> {
                MailRecipientDirectory.updateWardrobeTexture(owner, id, "newer", "new-signature");
                character.setName("Saved after texture publication");
                listed();
                return ProvinceSystemClient.SimpleResult.success(body(id, "older"));
            });
            MailRecipientDirectory.refreshMissingTexturesAsync(null);
            drain();
            assertEquals("Saved after texture publication", target(id).getDisplayPlain());
            assertEquals("newer", target(id).getBaseTextureValue());
            assertEquals("new-signature", target(id).getBaseTextureSignature());
        }
        @Test void queuedCallbackFailureDoesNotLoseOtherCallbacks(){listed();var calls=new AtomicInteger();MailRecipientDirectory.refreshMissingTexturesAsync(()->{throw new IllegalStateException("callback");});MailRecipientDirectory.refreshMissingTexturesAsync(calls::incrementAndGet);drain();assertEquals(1,calls.get());verify(logger).log(eq(Level.WARNING),contains("callback failed"),any(RuntimeException.class));}
        @Test void mailTargetDefensivelySnapshotsMutableLocation(){var location=new Location(world,1,2,3);var t=new CharacterMailTarget(owner,id,null,null,"mail-world",location,null,null);assertEquals("",t.getDisplayTab());assertEquals("",t.getDisplayPlain());assertEquals(Material.PLAYER_HEAD,t.getSkull().getType());location.setX(99);assertEquals(1,t.getLocation().getX(),"A published mail target is a location snapshot");var obtained=t.getLocation();obtained.setY(99);assertEquals(2,t.getLocation().getY());}
        @Test void skullTextureAndOwnerFactoriesProduceRealHeadMetadata(){assertEquals(Material.PLAYER_HEAD,CharacterSkull.steveHead().getType());assertNull(texture(CharacterSkull.fromTextures(null,null)));assertNull(texture(CharacterSkull.fromTextures(" ",null)));assertEquals(owner,((SkullMeta)CharacterSkull.ofOwner(player).getItemMeta()).getPlayerProfile().getId());assertNull(texture(CharacterSkull.ofOwner(null)));assertNull(texture(CharacterSkull.of(null)));assertNull(texture(CharacterSkull.ofActive(null)));assertEquals("value",texture(CharacterSkull.fromTextures("value","signature")));assertEquals("unsigned",texture(CharacterSkull.fromTextures("unsigned",null)));assertEquals("value",texture(new CharacterMailTarget(owner,id,"Name","Name",null,null,"value","signature").getSkull()));}
        @Test void skullUsesMaskedAndActiveSlotsBeforeBaseForAnActiveOnlineCharacter(){doReturn(true).when(character).isActive();var snap=new WardrobeSnapshot(id,"extra_1",3,Map.of("base",slot("base","base",true),"extra_1",slot("extra_1","extra",true),"masked",slot("masked","mask",true)));WardrobeCache.put(owner,snap);assertEquals("extra",texture(CharacterSkull.of(character)));masks.when(()->MaskService.isMasked(player)).thenReturn(true);assertEquals("mask",texture(CharacterSkull.of(character)));doReturn(false).when(character).isActive();assertEquals("base",texture(CharacterSkull.of(character)));}
        @Test void skullFallsBackThroughLastAppliedAccountBaseAndOwner(){doReturn(true).when(character).isActive();WardrobeCache.put(owner,new WardrobeSnapshot(id,"missing",1,Map.of("base",slot("base","base",true))));WardrobeCache.setLastApplied(player,new SkinTextures("last","signature"));assertEquals("last",texture(CharacterSkull.of(character)));WardrobeCache.clear(owner);WardrobeCache.put(owner,new WardrobeSnapshot(id,null,1,Map.of("base",slot("base","base",true))));try(var skins=mockStatic(SkinApplyHelper.class)){skins.when(()->SkinApplyHelper.readTextures(player)).thenReturn(new SkinTextures("account","signature"));WardrobeCache.captureAccountSkinIfNeeded(player);}assertEquals("account",texture(CharacterSkull.of(character)));WardrobeCache.clear(owner);WardrobeCache.put(owner,new WardrobeSnapshot(id," ",1,Map.of("base",slot("base","base",true))));assertEquals("base",texture(CharacterSkull.of(character)));WardrobeCache.put(owner,new WardrobeSnapshot(id,null,1,Map.of()));assertNull(texture(CharacterSkull.of(character)));WardrobeCache.put(owner,snapshot("wrong","wrong"));WardrobeCache.setLastApplied(player,new SkinTextures("last","signature"));assertEquals("last",texture(CharacterSkull.of(character)));}
        @Test void skullPublicGuardsAndProfileFailurePreserveAUsableFallback(){CharacterSkull.applyTextures(null,"v","s");var head=CharacterSkull.steveHead();CharacterSkull.applyTextures(head,null,"s");CharacterSkull.applyTextures(head,"","s");var stone=new ItemStack(Material.STONE);CharacterSkull.applyTextures(stone,"v","s");assertEquals(Material.STONE,stone.getType());bukkit.when(Bukkit::getServer).thenReturn(null);assertDoesNotThrow(()->CharacterSkull.applyTextures(head,"value","sig"));verify(logger).log(eq(Level.FINE),contains("Could not apply skull texture"));RPCharacters.plugin=null;assertDoesNotThrow(()->CharacterSkull.applyTextures(head,"value","sig"));}
        @Test void activeSkullUsesCurrentCharacterAndOwnerWhenNoCharacterIsActive(){try(var pluginApi=mockStatic(RPCharacters.class)){pluginApi.when(()->RPCharacters.getActiveCharacter(player)).thenReturn(character);WardrobeCache.put(owner,snapshot(id,"base"));assertEquals("base",texture(CharacterSkull.ofActive(player)));pluginApi.when(()->RPCharacters.getActiveCharacter(player)).thenReturn(null);assertEquals(owner,((SkullMeta)CharacterSkull.ofActive(player).getItemMeta()).getPlayerProfile().getId());}}

        @Test void scanHandlesMissingNonDirectoryAndUnreadableDirectoryListings() throws Exception {MailRecipientDirectory.scanFromDisk(folder.resolve("missing").toFile());Path regular=folder.resolve("file");Files.writeString(regular,"data");MailRecipientDirectory.scanFromDisk(regular.toFile());var deniedRoot=mock(java.io.File.class);when(deniedRoot.exists()).thenReturn(true);when(deniedRoot.isDirectory()).thenReturn(true);MailRecipientDirectory.scanFromDisk(deniedRoot);var deniedOwner=mock(java.io.File.class);when(deniedOwner.isDirectory()).thenReturn(true);when(deniedOwner.getName()).thenReturn(owner.toString());when(deniedRoot.listFiles()).thenReturn(new java.io.File[]{deniedOwner});MailRecipientDirectory.scanFromDisk(deniedRoot);Files.createDirectory(folder.resolve("not-a-uuid"));MailRecipientDirectory.scanFromDisk(folder.toFile());assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());}
        @Test void loadedCharacterWithoutAnIdIsSkippedAndUnknownStoredCharacterFallsBackToDirectory(){var malformed=character(null);account.addCharacter(malformed);loaded.add(account);accounts.put(owner,account);assertNotNull(target(id));assertEquals(1,MailRecipientDirectory.listMailTargets().size());account.getCharacters().remove(malformed);String directoryId="directory-"+id;var directoryCharacter=character(directoryId);directoryCharacter.setLastLocation("mail-world",1d,2d,3d);MailRecipientDirectory.upsert(owner,directoryCharacter);assertEquals(new Location(world,1,2,3),MailRecipientDirectory.getMailTargetLocation(owner,directoryId));}

        @Test void publicScanUsesTheConfiguredDirectoryAndHandlesAnEmptyListing(){try(var files=mockConstruction(java.io.File.class,(file,context)->{assertEquals(List.of("plugins/RPCharacters/data/characterdata"),context.arguments());when(file.exists()).thenReturn(true);when(file.isDirectory()).thenReturn(true);when(file.listFiles()).thenReturn(new java.io.File[0]);})){MailRecipientDirectory.scanFromDisk();assertEquals(1,files.constructed().size());assertTrue(MailRecipientDirectory.listMailTargets().isEmpty());}}
        @Test void successfulHttpWithMalformedOrEmptyTextureDataDoesNotCreateATexture(){String emptySlots=new JSONObject(Map.of("character_id",id,"slots",new org.json.simple.JSONArray())).toJSONString();for(String body:List.of("{","{}",emptySlots,body(id," "),body(id,"\u2003"))){MailRecipientDirectory.remove(id);listed();api.when(()->ProvinceSystemClient.fetchWardrobe(owner.toString(),id)).thenReturn(ProvinceSystemClient.SimpleResult.success(body));var complete=new AtomicInteger();MailRecipientDirectory.refreshMissingTexturesAsync(complete::incrementAndGet);drain();assertNull(target(id).getBaseTextureValue());assertEquals(1,complete.get());}}
    }
}
