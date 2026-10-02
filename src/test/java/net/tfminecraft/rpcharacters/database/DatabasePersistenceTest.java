package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.rpcharacters.kit.*;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory;
import net.tfminecraft.rpcharacters.utils.Integrator;
import org.json.simple.*;
import org.junit.jupiter.api.Test;

class DatabasePersistenceTest extends DatabaseFixture {
    @Test void fullyPopulatedPlayerAndCharacterRoundTripWithoutLosingProgress() throws Exception {
        write(playerFile,richPlayerJson()); write(characterFile("alpha"),richCharacterJson("alpha"));
        var loaded=db.loadPlayer(player); assertNotNull(loaded); var character=loaded.getCharacters().getFirst();
        loaded.takePendingMmoAttributeRemoves(); // The successful login already consumed the previous creation layer.
        character.setPaidChangeCount("class",2); character.setStageRevision("class",3,1700000000L);
        try(var integrations=mockConstruction(Integrator.class,(integrator,context) -> when(integrator.getRemoveList(player,character)).thenReturn(List.of("strength.4","dexterity.2")))) {
            db.savePlayer(loaded); assertEquals(1,integrations.constructed().size());
        }
        JSONObject playerJson=read(playerFile),characterJson=read(characterFile("alpha"));
        assertEquals("true",playerJson.get("eighteen")); assertEquals(1234567890123L,playerJson.get("last-character-switch-ms")); assertEquals(array("strength.4","dexterity.2"),playerJson.get("to remove"));
        assertEquals(12,((Number)playerJson.get("account-skill-points-total")).intValue()); assertEquals(6,((Number)playerJson.get("account-attribute-points-total")).intValue()); assertEquals(3,((Number)((JSONObject)playerJson.get("account-profession-points")).get("forager")).intValue());
        assertEquals("alpha",characterJson.get("id")); assertEquals("Aria",characterJson.get("name")); assertEquals(77,((Number)characterJson.get("online-playtime-seconds")).intValue()); assertEquals("false",characterJson.get("mail-listed")); assertEquals("false",characterJson.get("pvp-lethal"));
        var fresh=new Database().loadPlayer(player); assertNotNull(fresh); var restored=fresh.getCharacters().getFirst();
        assertEquals(character.getId(),restored.getId()); assertEquals(character.getRace().getId(),restored.getRace().getId()); assertEquals(character.getConversationCounts(),restored.getConversationCounts()); assertEquals(character.getConversationLastAtMs(),restored.getConversationLastAtMs());
        assertEquals(character.getNameColour().getHexCodes(),restored.getNameColour().getHexCodes()); assertEquals(character.getPersonaDescription(),restored.getPersonaDescription()); assertEquals(character.getExtraAttributeAllocation(),restored.getExtraAttributeAllocation()); assertEquals(character.getProfessionUpgrades(),restored.getProfessionUpgrades()); assertEquals(character.getForfeitedProfessionPoints(),restored.getForfeitedProfessionPoints()); assertEquals(character.getLastLocationX(),restored.getLastLocationX()); assertEquals(20D,restored.getFuel("fuel")); assertEquals(2,restored.getPaidChangeCount("class")); assertEquals(3,restored.getStageRevision("class")); assertEquals(123,restored.getFoodValue());
        playtime.verify(() -> CharacterPlaytimeDirectory.upsert(owner,character));
    }

    @Test void emptyAccountsAndMinimalCharactersPersistWithoutOptionalFields() throws Exception {
        var data=new PlayerData(owner); data.setCreatedAtEpochSeconds(0); db.savePlayer(data);
        var account=read(playerFile); assertEquals("false",account.get("eighteen")); assertEquals(array(),account.get("completed stages")); assertFalse(account.containsKey("account-skill-points-total")); assertFalse(account.containsKey("created-at"));
        var character=new RPCharacter(null); character.setId("minimal"); character.setName("Minimal"); character.setRace(race); character.setCreatedAtEpochSeconds(0);
        db.saveCharacter(data,character); var json=read(characterFile("minimal"));
        assertEquals("minimal",json.get("id")); assertFalse(json.containsKey("class")); assertFalse(json.containsKey("created-at")); assertFalse(json.containsKey("trait-state")); assertFalse(json.containsKey("last-location")); assertFalse(json.containsKey("profession-upgrades")); assertEquals(RPCharacter.MAX_FOOD_VALUE,((Number)json.get("food-value")).intValue());
        data.addCharacter(character); db.savePlayer(data); assertTrue(Files.exists(characterFile("minimal"))); playtime.verify(() -> CharacterPlaytimeDirectory.upsert(owner,character),times(2));
    }

    @Test void failedLoginRemovalsSurviveOnlineAndOfflineSavesWithoutAnActiveCharacter() {
        assertAll(
                () -> pendingRemovalRoundTrip(false, false),
                () -> pendingRemovalRoundTrip(false, true));
    }

    @Test void failedLoginRemovalsTakePrecedenceOverDifferentActiveCharacterModifiers() {
        assertAll(
                () -> pendingRemovalRoundTrip(true, false),
                () -> pendingRemovalRoundTrip(true, true));
    }

    private void pendingRemovalRoundTrip(boolean active, boolean offlineLoad) throws Exception {
        List<String> pending = List.of("strength.2", "dexterity.1");
        // Start each online/offline scenario from independent, complete persisted records.
        write(playerFile, richPlayerJson());
        JSONObject originalCharacter = richCharacterJson("alpha");
        originalCharacter.put("active", Boolean.toString(active));
        write(characterFile("alpha"), originalCharacter);
        PlayerData loaded = offlineLoad ? new Database().loadPlayerData(owner) : new Database().loadPlayer(player);
        assertNotNull(loaded);
        assertEquals(active, loaded.hasActiveCharacter());
        assertEquals(offlineLoad ? null : player, loaded.getPlayer());
        assertPendingRemovals(loaded, pending);
        RPCharacter current = loaded.getCharacters().getFirst();
        current.getAttributeData().addModifier(
                new net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier("strength", 9));
        assertEquals(List.of("strength.9"), new Integrator().getRemoveList(player, current),
                "The active character's current layer must differ from the pending old layer");
        loaded.setLastKitClaimAtMs("offline-edit", 987654321L);
        assertTrue(new Database().trySavePlayer(loaded));
        assertEquals(array("strength.2", "dexterity.1"), read(playerFile).get("to remove"),
                "Saving before MMO readiness must retain the exact unapplied queue");
        assertPendingRemovals(loaded, pending);

        PlayerData offline = new Database().loadPlayerData(owner);
        assertNotNull(offline);
        assertNull(offline.getPlayer());
        assertPendingRemovals(offline, pending);
        assertTrue(new Database().trySavePlayer(offline));
        PlayerData onlineAgain = new Database().loadPlayer(player);
        assertNotNull(onlineAgain);
        assertPendingRemovals(onlineAgain, pending);
        assertEquals(987654321L, onlineAgain.getLastKitClaimAtMs("offline-edit"));
        assertEquals(List.of("intro", "race"), onlineAgain.getCompletedStages());
        assertEquals(12, onlineAgain.getAccountSkillPointsTotal());
        assertEquals("Aria", onlineAgain.getCharacters().getFirst().getName());
        assertEquals(active, onlineAgain.hasActiveCharacter());
    }

    private void assertPendingRemovals(PlayerData data, List<String> expected) {
        List<String> actual = data.takePendingMmoAttributeRemoves();
        data.setPendingMmoAttributeRemoves(actual);
        assertEquals(expected, actual, "Inspection must preserve the pending work for save/retry");
    }

    @Test void characterCollectionFailureMustPreserveExistingBytes() throws Exception {
        Path file=characterFile("alpha"); String original=richCharacterJson("alpha").toJSONString(); Files.writeString(file,original);
        var invalid=spy(character("alpha",false)); doThrow(new IllegalStateException("metadata unavailable")).when(invalid).getName();
        assertDoesNotThrow(() -> db.saveCharacter(new PlayerData(owner),invalid));
        assertEquals(original,Files.readString(file),"Never truncate a record before collecting its replacement"); playtime.verifyNoInteractions(); mail.verifyNoInteractions();
    }

    @Test void playerCollectionFailureMustPreserveExistingBytes() throws Exception {
        String original=richPlayerJson().toJSONString(); Files.writeString(playerFile,original); var invalid=spy(new PlayerData(owner)); doThrow(new IllegalStateException("stages unavailable")).when(invalid).getCompletedStages();
        assertDoesNotThrow(() -> db.savePlayer(invalid)); assertEquals(original,Files.readString(playerFile));
    }

    @Test void rejectedCharacterSaveKeepsPreviousFile() throws Exception {
        Path file=characterFile("alpha"); String original=richCharacterJson("alpha").toJSONString(); Files.writeString(file,original);
        var writer=spy(new Database()); doReturn(false).when(writer).save(any(File.class),any()); writer.saveCharacter(new PlayerData(owner),character("alpha",false));
        assertEquals(original,Files.readString(file),"Persistence returning false must leave the previous record intact");
    }

    @Test void rejectedCharacterSaveMustNotPublishUnpersistedDirectoryChanges() {
        var writer=spy(new Database()); doReturn(false).when(writer).save(any(File.class),any()); writer.saveCharacter(new PlayerData(owner),character("alpha",false));
        playtime.verifyNoInteractions(); mail.verifyNoInteractions();
    }

    @Test void rejectedPlayerSaveKeepsPreviousFile() throws Exception {
        String original=richPlayerJson().toJSONString(); Files.writeString(playerFile,original); var writer=spy(new Database()); doReturn(false).when(writer).save(any(File.class),any());
        writer.savePlayer(new PlayerData(owner)); assertEquals(original,Files.readString(playerFile));
    }

    @Test void failedFilesystemTargetsRemainDirectoriesAndOtherFilesSurvive() throws Exception {
        Files.createDirectory(playerFile); db.savePlayer(new PlayerData(owner)); assertTrue(Files.isDirectory(playerFile)); Files.delete(playerFile);
        Path target=characterFile("blocked"); Files.createDirectory(target); Path survivor=characterFile("survivor"); Files.writeString(survivor,"untouched"); db.saveCharacter(new PlayerData(owner),character("blocked",false));
        assertTrue(Files.isDirectory(target)); assertEquals("untouched",Files.readString(survivor)); playtime.verifyNoInteractions(); mail.verifyNoInteractions();
    }

    @Test void genericSaveAndTypedAccessorsUseLoadedValuesThenDefaults() throws Exception {
        write(playerFile,obj("completed stages",array(),"text","&aPersisted","bool","true","integer","42","double","4.5","object",obj("nested",true),"array",array("stored"),"invalid","not-a-number")); db.loadPlayerData(owner);
        var defaults=new HashMap<String,Object>(); defaults.put("text","fallback"); defaults.put("absent","&bDefault"); defaults.put("other-object",obj("new",1)); defaults.put("other-array",array("new"));
        assertEquals("&aPersisted",db.getRawData("text",defaults)); assertEquals("missing",db.getRawData("missing",defaults)); assertEquals("§aPersisted",db.getString("text",defaults)); assertEquals("§bDefault",db.getString("absent",defaults)); assertTrue(db.getBoolean("bool",defaults)); assertFalse(db.getBoolean("missing",defaults));
        assertEquals(42D,db.getInteger("integer",defaults)); assertEquals(4.5,db.getDouble("double",defaults)); assertEquals(-1,db.getInteger("invalid",defaults)); assertEquals(-1,db.getDouble("invalid",defaults));
        assertEquals(obj("nested",true),db.getObject("object",defaults)); assertEquals(obj("new",1),db.getObject("other-object",defaults)); assertTrue(db.getObject("missing",defaults).isEmpty()); assertEquals(array("stored"),db.getArray("array",defaults)); assertEquals(array("new"),db.getArray("other-array",defaults)); assertTrue(db.getArray("missing",defaults).isEmpty());
        var values=new HashMap<String,Object>(); values.put("text","default"); values.put("bool",false); values.put("integer",1); values.put("double",0.0); values.put("object",obj()); values.put("array",array()); values.put("unsupported",new Object()); values.put("long",Long.MAX_VALUE);
        Path file=temp.resolve("data.json"); assertTrue(db.save(file.toFile(),values)); var saved=read(file);
        assertEquals("§aPersisted",saved.get("text")); assertEquals(true,saved.get("bool")); assertEquals(42,((Number)saved.get("integer")).intValue()); assertEquals(Long.MAX_VALUE,saved.get("long")); assertFalse(saved.containsKey("unsupported"));
        assertFalse(db.save(temp.toFile(),values)); assertTrue(Files.isDirectory(temp));
    }

    @Test void rejectedNumericSerializationLeavesTargetBytesIntact() throws Exception {
        write(playerFile,obj("completed stages",array())); db.loadPlayerData(owner); Path file=temp.resolve("prior.json"); Files.writeString(file,"{\"prior\":true}");
        var defaults=new HashMap<String,Object>(); defaults.put("bad",Double.NaN); assertFalse(db.save(file.toFile(),defaults)); assertEquals("{\"prior\":true}",Files.readString(file));
    }

    @Test void failedStagingInReadOnlyDirectoryKeepsExistingWritableFileIntact() throws Exception {
        write(playerFile,obj("completed stages",array())); db.loadPlayerData(owner);
        Path directory=Files.createDirectory(temp.resolve("read-only"));
        Path file=directory.resolve("record.json"); String original="{\"original\":true}";
        Files.writeString(file,original);
        var permissions=Files.getPosixFilePermissions(directory);
        var defaults=new HashMap<String,Object>(); defaults.put("replacement","new value");
        try {
            Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("r-x------"));
            assertFalse(db.save(file.toFile(),defaults),"A replacement must be staged before touching the existing file");
            assertEquals(original,Files.readString(file));
            try(var children=Files.list(directory)) {assertEquals(List.of(file),children.toList());}
        } finally {Files.setPosixFilePermissions(directory,permissions);}
    }

    @Test void partialStagingWriteLeavesPreviousJsonAndNoTemporaryFile() throws Exception {
        write(playerFile,obj("completed stages",array())); db.loadPlayerData(owner);
        Path file=temp.resolve("record.json"); String original="{\"original\":true}";
        Files.writeString(file,original);
        var defaults=new HashMap<String,Object>(); defaults.put("replacement","new value");
        var stage=new AtomicReference<Path>();
        try(var files=mockStatic(Files.class,invocation -> {
            if(invocation.getMethod().getName().equals("writeString")) {
                Path path=invocation.getArgument(0); stage.set(path);
                Files.write(path,"{\"partial\":".getBytes(StandardCharsets.UTF_8));
                throw new IOException("simulated disk full during staged write");
            }
            return invocation.callRealMethod();
        })) {
            assertFalse(db.save(file.toFile(),defaults));
        }
        assertNotNull(stage.get(),"Exercise failure after a staging file exists");
        assertNotEquals(file,stage.get()); assertEquals(original,Files.readString(file));
        assertFalse(Files.exists(stage.get()),"Failed staging files must be removed");
        try(var children=Files.list(temp)) {assertEquals(List.of(file),children.toList());}
    }

    @Test void unsupportedAtomicMovePreservesOriginalAndRemovesStagingFile() throws Exception {
        write(playerFile,obj("completed stages",array())); db.loadPlayerData(owner);
        Path file=temp.resolve("record.json"); Files.writeString(file,"{\"original\":true}");
        var defaults=new HashMap<String,Object>(); defaults.put("name","Éowyn 雪");
        var rejectedAtomicMove=new AtomicBoolean();
        try(var files=mockStatic(Files.class,invocation -> {
            if(invocation.getMethod().getName().equals("move")) {
                CopyOption[] options=(CopyOption[])invocation.getRawArguments()[2];
                if(Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                    rejectedAtomicMove.set(true);
                    throw new AtomicMoveNotSupportedException(invocation.getArgument(0).toString(),file.toString(),"simulated unsupported filesystem");
                }
            }
            return invocation.callRealMethod();
        })) {
            assertFalse(db.save(file.toFile(),defaults));
        }
        assertTrue(rejectedAtomicMove.get()); assertEquals(obj("original",true),read(file));
        try(var children=Files.list(temp)) {assertEquals(List.of(file),children.toList());}
    }

    @Test void stagingCleanupFailureKeepsOriginalAndPreservesBothIoErrors() throws Exception {
        write(playerFile,obj("completed stages",array())); db.loadPlayerData(owner);
        Path file=temp.resolve("record.json"); String original="{\"original\":true}";
        Files.writeString(file,original);
        var defaults=new HashMap<String,Object>(); defaults.put("replacement","new value");
        var stage=new AtomicReference<Path>();
        var writeFailure=new IOException("disk full"); var cleanupFailure=new IOException("temporary file cannot be deleted");
        try {
            try(var files=mockStatic(Files.class,invocation -> {
                if(invocation.getMethod().getName().equals("writeString")) {
                    Path path=invocation.getArgument(0); stage.set(path);
                    Files.write(path,"partial".getBytes(StandardCharsets.UTF_8));
                    throw writeFailure;
                }
                if(invocation.getMethod().getName().equals("deleteIfExists") && Objects.equals(stage.get(),invocation.getArgument(0))) {
                    throw cleanupFailure;
                }
                return invocation.callRealMethod();
            })) {
                assertFalse(db.save(file.toFile(),defaults));
            }
            assertEquals(original,Files.readString(file)); assertNotNull(stage.get());
            assertEquals("partial",Files.readString(stage.get()));
            assertArrayEquals(new Throwable[]{cleanupFailure},writeFailure.getSuppressed());
        } finally {if(stage.get()!=null)Files.deleteIfExists(stage.get());}
    }

    @Test void characterIdentifierCannotReplaceAFileOutsideItsOwnersDirectory() throws Exception {
        String outsideId="outside-"+owner; Path outside=characterFolder.getParent().resolve(outsideId+".json");
        String original="{\"belongs-to-another-record\":true}"; Files.writeString(outside,original);
        try {
            db.saveCharacter(new PlayerData(owner),character("../"+outsideId,false));
            assertEquals(original,Files.readString(outside),"Character identifiers must stay inside their owner's directory");
            playtime.verifyNoInteractions(); mail.verifyNoInteractions();
        } finally {Files.deleteIfExists(outside);}
    }

    @Test void nullableTransientMapEntriesAreNotSerializedAsValidState() throws Exception {
        var character=spy(character("alpha",false)); var kits=new LinkedHashMap<String,KitCustomiseData>(); kits.put("missing",null); kits.put("plain",new KitCustomiseData("plain",null,List.of("Lore"),null,""));
        doReturn(kits).when(character).getKitCustomisations(); var states=new LinkedHashMap<String,TraitInstanceState>(); states.put("missing",null); states.put("empty",new TraitInstanceState()); var valid=new TraitInstanceState(); valid.setFuel(5D); states.put("fuel",valid); doReturn(states).when(character).getTraitStateMap();
        character.setKitStatus(KitStatus.ELIGIBLE); character.setKitStatus("other",KitStatus.GRANTED); doReturn(null).when(character).getConversationCounts(); doReturn(null).when(character).getConversationLastAtMs();
        db.saveCharacter(new PlayerData(owner),character); var json=read(characterFile("alpha"));
        assertEquals(Set.of("plain"),((JSONObject)json.get("kit-customisations")).keySet()); assertEquals(Set.of("fuel"),((JSONObject)json.get("trait-state")).keySet()); assertTrue(((JSONObject)json.get("conversations")).isEmpty()); assertEquals("eligible",json.get("kit-status"));
    }

    @Test void playerLogAppendsTimestampedActionsAndHandlesUnwritableTarget() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); when(player.getName()).thenReturn("ITest"+owner.toString().substring(0,8));
        Path file=Path.of("plugins/RPCharacters/logs",player.getName().substring(0,1).toLowerCase(Locale.ROOT),player.getName()+".txt");
        Path legacyPath=Path.of("plugins/RPCharacters/logs",player.getName().substring(0,1).toLowerCase(),player.getName()+".txt");
        try {
            Database.log(player,"first action"); Database.log(player,"second action"); var lines=Files.readAllLines(file);
            assertEquals(2,lines.size()); assertTrue(lines.get(0).matches("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\] first action")); assertTrue(lines.get(1).endsWith("second action"));
            Files.delete(file); Files.createDirectory(file); assertDoesNotThrow(() -> Database.log(player,"cannot write")); assertTrue(Files.isDirectory(file));
        } finally {removeOwnedTree(file); removeOwnedTree(legacyPath);}
    }
}
