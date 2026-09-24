package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FocusMigrationTest {
    @TempDir Path root;

    @Test
    void copiesLegacyBytesAndNeverOverwritesEitherOwnerOnRetry() throws Exception {
        Path legacy = root.resolve("TFMCCore");
        Path owner = root.resolve("RPCharacters");
        Files.createDirectories(legacy.resolve("data/focus"));
        String record = "{\"characterId\":\"alice\",\"ownerUuid\":\"account\",\"points\":42,\"lastRegenMs\":1234}";
        Files.writeString(legacy.resolve("focus.yml"), "max: 190\nbase_per_hour: 13\n");
        Files.writeString(legacy.resolve("data/focus/alice.json"), record);
        FocusMigration.copyLegacy(legacy, owner);
        assertEquals(record, Files.readString(owner.resolve("data/focus/alice.json")));
        assertEquals(Files.readString(legacy.resolve("focus.yml")), Files.readString(owner.resolve("focus.yml")));
        var store = new FocusStore(owner.resolve("data/focus").toFile());
        FocusData data = store.load("alice");
        assertEquals(42, data.getPoints());
        assertEquals(1234, data.getLastRegenMs());
        data.setPoints(17);
        store.save(data);
        Files.writeString(owner.resolve("focus.yml"), "max: 180\n");
        FocusMigration.copyLegacy(legacy, owner);
        assertEquals(17, store.load("alice").getPoints());
        assertEquals("max: 180\n", Files.readString(owner.resolve("focus.yml")));
        assertEquals(record, Files.readString(legacy.resolve("data/focus/alice.json")));
    }

    @Test
    void partialMigrationCanRetryAfterFilesystemFailure() throws Exception {
        Path legacy = root.resolve("TFMCCore");
        Path owner = root.resolve("RPCharacters");
        Files.createDirectories(legacy.resolve("data/focus"));
        Files.writeString(legacy.resolve("focus.yml"), "max: 170\n");
        Files.writeString(legacy.resolve("data/focus/alice.json"), "{\"points\":7,\"lastRegenMs\":100}");
        Files.createDirectories(owner);
        Files.writeString(owner.resolve("data"), "obstruction");
        assertThrows(IOException.class, () -> FocusMigration.copyLegacy(legacy, owner));
        assertEquals("max: 170\n", Files.readString(owner.resolve("focus.yml")));
        assertTrue(Files.isRegularFile(legacy.resolve("data/focus/alice.json")));
        Files.delete(owner.resolve("data"));
        FocusMigration.copyLegacy(legacy, owner);
        assertEquals(7, new FocusStore(owner.resolve("data/focus").toFile()).load("alice").getPoints());
    }

    @Test
    void corruptExistingRecordIsAnErrorNotAbsentOrFreshState() throws Exception {
        Path folder = root.resolve("focus");
        Files.createDirectories(folder);
        Path file = folder.resolve("alice.json");
        var store = new FocusStore(folder.toFile());
        for (String invalid : new String[] {"broken", "null", "{}", "{\"characterId\":\"other\",\"points\":42,\"lastRegenMs\":1}"}) {
            Files.writeString(file, invalid);
            assertThrows(IllegalStateException.class, () -> store.load("alice"));
            assertEquals(invalid, Files.readString(file));
        }
        assertNull(store.load("missing"));
    }

    @Test
    void nullFractionalAndOutOfRangeBalancesAndTimestampsAreRejected() throws Exception {
        Path folder = root.resolve("focus");
        Files.createDirectories(folder);
        Path file = folder.resolve("alice.json");
        var store = new FocusStore(folder.toFile());
        for (String points : new String[] {"null", "\"12\"", "1.5", "2147483648", "-1"}) {
            String invalid = "{\"points\":" + points + ",\"lastRegenMs\":100}";
            Files.writeString(file, invalid);
            assertThrows(IllegalStateException.class, () -> store.load("alice"));
            assertEquals(invalid, Files.readString(file));
        }
        for (String timestamp : new String[] {"null", "\"12\"", "1.5", "9223372036854775808", "-1"}) {
            String invalid = "{\"points\":12,\"lastRegenMs\":" + timestamp + "}";
            Files.writeString(file, invalid);
            assertThrows(IllegalStateException.class, () -> store.load("alice"));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void legacyResearchImportPreservesPointsTimestampAndSource() throws Exception {
        Path research = root.resolve("research");
        Files.createDirectories(research);
        Path old = research.resolve("account.json");
        String original = "{\"mental_points\":23,\"last_regen_ms\":7000}";
        Files.writeString(old, original);
        var store = new FocusStore(root.resolve("focus").toFile(), research.toFile());
        FocusData data = store.migrateFromResearch("alice", "account");
        assertEquals("alice", data.getCharacterId());
        assertEquals("account", data.getOwnerUuid());
        assertEquals(23, data.getPoints());
        assertEquals(7000, data.getLastRegenMs());
        assertEquals(original, Files.readString(old));
        Files.writeString(old, "{invalid");
        assertThrows(IllegalStateException.class, () -> store.migrateFromResearch("alice", "account"));
        assertNull(store.load("alice"));
    }
}
