package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BodyLeaseStoreTest {
    @TempDir Path temp;
    final UUID owner = UUID.randomUUID(), character = UUID.randomUUID();
    final BodyTag.Live live = new BodyTag.Live(character, 7);

    @Test void liveAuthorityRequiresTheExactDurableLeaseAndCompletionRemovesOnlyThatLease() throws Exception {
        Path root = temp.resolve("data/body-leases");
        BodyFiles files = spy(BodyFiles.DISK);
        BodyLeaseStore store = new BodyLeaseStore(root, files);
        store.validate(owner, new BodyTag.Untagged());
        store.validate(owner, new BodyTag.Room(character));
        assertThrows(IOException.class, () -> store.validate(owner, live));
        assertThrows(IOException.class, () -> store.complete(owner, live));
        store.begin(owner, live);
        verify(files).syncDirectory(temp);
        verify(files).syncDirectory(root.getParent());
        verify(files).syncDirectory(root);
        new BodyLeaseStore(root).validate(owner, live);
        assertThrows(IOException.class, () -> store.validate(owner, new BodyTag.Room(character)));
        assertThrows(IOException.class, () -> store.validate(owner, new BodyTag.Untagged()));
        assertThrows(IOException.class, () -> store.validate(owner, new BodyTag.Live(UUID.randomUUID(), 7)));
        assertThrows(IOException.class, () -> store.complete(owner, new BodyTag.Live(character, 8)));
        Path marker = root.resolve(owner + ".json");
        byte[] before = Files.readAllBytes(marker);
        assertThrows(IOException.class, () -> store.begin(owner, new BodyTag.Live(character, 8)));
        assertArrayEquals(before, Files.readAllBytes(marker));
        store.complete(owner, live);
        store.validate(owner, new BodyTag.Room(character));
        assertThrows(IOException.class, () -> store.validate(owner, live));
        verify(files, times(2)).syncDirectory(root);
        assertThrows(IllegalArgumentException.class, () -> store.begin(owner, new BodyTag.Live(character, 0)));
    }

    @Test void partialWriteNeverPublishesAnActivationOrLeavesTemporaryFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("leases"));
        BodyFiles files = spy(BodyFiles.DISK);
        doAnswer(call -> {
            Files.writeString(call.getArgument(0), "partial");
            throw new IOException("write failed");
        }).when(files).write(any(Path.class), any(byte[].class));
        BodyLeaseStore store = new BodyLeaseStore(root, files);
        assertThrows(IOException.class, () -> store.begin(owner, live));
        store.validate(owner, new BodyTag.Untagged());
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
    }

    @Test void failedDirectoryBarrierRetainsPublishedEvidenceAndNeverApprovesASecondActivation() throws Exception {
        Path root = Files.createDirectory(temp.resolve("leases"));
        BodyFiles files = spy(BodyFiles.DISK);
        doThrow(new IOException("directory sync failed")).when(files).syncDirectory(root);
        BodyLeaseStore store = new BodyLeaseStore(root, files);
        assertThrows(IOException.class, () -> store.begin(owner, live));
        store.validate(owner, live);
        assertThrows(IOException.class, () -> store.validate(owner, new BodyTag.Room(character)));
        assertThrows(IOException.class, () -> store.begin(owner, live));
        assertThrows(IOException.class, () -> store.complete(owner, live));
        store.validate(owner, new BodyTag.Room(character));
        assertThrows(IOException.class, () -> store.validate(owner, live));
    }

    @Test void respawnIntentSurvivesRestartAndOnlyTheMatchingLeaseCanMarkOrReadIt() throws Exception {
        Path root = Files.createDirectory(temp.resolve("leases"));
        BodyFiles files = spy(BodyFiles.DISK); BodyLeaseStore store = new BodyLeaseStore(root, files);
        assertThrows(IOException.class, () -> store.markRespawn(owner, live));
        store.begin(owner, live); assertFalse(store.pendingRespawn(owner, live));
        store.markRespawn(owner, live); store.markRespawn(owner, live);
        verify(files,times(2)).write(any(),any()); verify(files).move(any(),eq(root.resolve(owner+".json")));
        verify(files,times(2)).syncDirectory(root);
        assertTrue(new BodyLeaseStore(root).pendingRespawn(owner, live));
        assertThrows(IOException.class, () -> store.markRespawn(owner,new BodyTag.Live(character,8)));
        assertThrows(IOException.class, () -> store.pendingRespawn(owner,new BodyTag.Live(character,8)));
        store.complete(owner,live); store.validate(owner,new BodyTag.Room(character));
    }

    @Test void failedRespawnIntentCommitRetainsTheOriginalLeaseOrPublishedIntentWithoutLosingEvidence() throws Exception {
        Path root = Files.createDirectory(temp.resolve("leases"));
        BodyFiles files = spy(BodyFiles.DISK); BodyLeaseStore store = new BodyLeaseStore(root, files);
        store.begin(owner,live);
        doThrow(new IOException("rename")).when(files).move(any(),any());
        assertThrows(IOException.class, () -> store.markRespawn(owner,live)); assertFalse(store.pendingRespawn(owner,live));
        try(var entries=Files.list(root)) {assertEquals(1,entries.count());}
        doCallRealMethod().when(files).move(any(),any());
        doThrow(new IOException("directory sync")).when(files).syncDirectory(root);
        assertThrows(IOException.class, () -> store.markRespawn(owner,live)); assertTrue(store.pendingRespawn(owner,live));
        new BodyLeaseStore(root).validate(owner,live);
    }

    @Test void corruptOrWrongOwnerLeasesArePreservedAndFailClosed() throws Exception {
        Path root = Files.createDirectory(temp.resolve("leases"));
        BodyLeaseStore store = new BodyLeaseStore(root);
        store.begin(owner, live);
        Path file = root.resolve(owner + ".json");
        String valid = Files.readString(file);
        for (String invalid : List.of("{", valid.replace(owner.toString(), UUID.randomUUID().toString()),
                valid.replace(character.toString(), "broken"), valid.replace("\"rev\":7", "\"rev\":0"),
                valid.replace("\"rev\":7", "\"rev\":7.5"), valid.replace("\"pendingRespawn\":false", "\"pendingRespawn\":\"false\""), valid.replace("\"format\":1", "\"format\":2"),
                valid.replace("\"format\":1,", ""), valid.replace("}", ",\"extra\":true}"))) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> store.validate(owner, live), invalid);
            assertThrows(IOException.class, () -> store.complete(owner, live), invalid);
            assertEquals(invalid, Files.readString(file));
        }
        Files.writeString(file, "x".repeat(2049));
        assertThrows(IOException.class, () -> store.validate(owner, live));
        Files.delete(file); Files.createDirectory(file);
        assertThrows(IOException.class, () -> store.validate(owner, live));
        Files.delete(file); Path target = temp.resolve("actual"); Files.writeString(target, valid); Files.createSymbolicLink(file, target);
        assertThrows(IOException.class, () -> store.validate(owner, live));
        assertEquals(valid, Files.readString(target));
    }
}
