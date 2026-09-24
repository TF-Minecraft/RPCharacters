package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FocusStoreTest {
    @TempDir Path root;

    @Test
    void preservesCharacterMetadataAndTimestampWhenSavingBalance() throws Exception {
        Path folder = root.resolve("focus");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("alice.json"),
                "{\"characterId\":\"alice\",\"ownerUuid\":\"account\",\"points\":42,\"lastRegenMs\":1234}");
        var store = new FocusStore(folder.toFile());
        FocusData data = store.load("alice");
        assertEquals(42, data.getPoints());
        data.setPoints(17);
        store.save(data);
        FocusData saved = store.load("alice");
        assertEquals(17, saved.getPoints());
        assertEquals("alice", saved.getCharacterId());
        assertEquals("account", saved.getOwnerUuid());
        assertEquals(1234, saved.getLastRegenMs());
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

}
