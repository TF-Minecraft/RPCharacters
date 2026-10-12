package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;

class BodyStoreTest {
    @TempDir
    Path temp;

    private PlayerMock player;
    private Path root;
    private FaultyFiles files;
    private BodyStore store;
    private final UUID owner = UUID.randomUUID();
    private final UUID character = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T08:00:00.123456789Z"), ZoneOffset.UTC);

    /** Real disk steps that can be told to fail at the nth call of one step. */
    static final class FaultyFiles implements BodyFiles {
        final List<String> calls = new ArrayList<>();
        String failing;
        int remaining;
        boolean partial;

        void failOn(String step, int occurrence) {
            failing = step;
            remaining = occurrence;
        }

        private void step(String step, Path path) throws IOException {
            calls.add(step + " " + path.getFileName());
            if (step.equals(failing) && --remaining == 0) {
                failing = null;
                throw new IOException("injected " + step + " failure");
            }
        }

        @Override
        public void write(Path file, byte[] bytes) throws IOException {
            if (partial && "write".equals(failing) && remaining == 1) {
                Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
            }
            step("write", file);
            BodyFiles.DISK.write(file, bytes);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            step("move", target);
            BodyFiles.DISK.move(source, target);
        }

        @Override
        public void syncDirectory(Path directory) throws IOException {
            step("sync", directory);
            BodyFiles.DISK.syncDirectory(directory);
        }
    }

    @BeforeEach
    void setup() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        root = temp.resolve("data").resolve("bodies");
        files = new FaultyFiles();
        store = new BodyStore(root, files, clock, () -> 4440);
    }

    @AfterEach
    void cleanup() {
        MockBukkit.unmock();
    }

    @Test
    void storesRevisionsDurablyAndKeepsThePreviousOne() throws Exception {
        assertEquals(Optional.empty(), store.read(owner, character));
        BodySnapshot first = body(1);

        BodyRecord one = store.write(owner, character, 0, "new-character", first);

        assertEquals(new BodyRecord(owner, character, 1, 4440, clock.instant(), "new-character", first), one);
        assertEquals(Optional.of(one), store.read(owner, character));
        assertTrue(files.calls.containsAll(List.of("sync data", "sync bodies", "sync " + owner)), files.calls.toString());
        byte[] firstBytes = Files.readAllBytes(bodyFile());

        BodyRecord two = store.write(owner, character, 1, "quit", body(2));

        assertEquals(2, two.rev());
        assertEquals(Optional.of(two), store.read(owner, character));
        assertArrayEquals(firstBytes, Files.readAllBytes(previousFile()));
        assertEquals(Set.of(character + ".body", character + ".body.prev"), names(root.resolve(owner.toString())));
        String text = Files.readString(bodyFile());
        assertTrue(text.startsWith("RPCHARACTERS-BODY 1 "), text);
        assertTrue(text.contains("\"reason\": \"quit\""), text);
    }

    @Test
    void publicStoreUsesTheServerDataVersion() throws Exception {
        BodyStore disk = new BodyStore(temp.resolve("relative-check"));
        BodyRecord saved = disk.write(owner, character, 0, "new-character", body(1));
        assertEquals(1, saved.dataVersion());
        assertEquals(Optional.of(saved), disk.read(owner, character));
    }

    @Test
    void staleRevisionsAreRefusedWithoutWriting() throws Exception {
        assertConflict(() -> store.write(owner, character, 1, "quit", body(1)), 1, 0);
        store.write(owner, character, 0, "new-character", body(1));
        byte[] stored = Files.readAllBytes(bodyFile());

        assertConflict(() -> store.write(owner, character, 0, "quit", body(2)), 0, 1);
        assertConflict(() -> store.write(owner, character, 3, "quit", body(1)), 3, 1);

        assertArrayEquals(stored, Files.readAllBytes(bodyFile()));
        assertFalse(Files.exists(previousFile()));
    }

    @Test
    void retryingACommittedBodyReturnsTheStoredRevision() throws Exception {
        BodyRecord saved = store.write(owner, character, 0, "quit", body(1));
        byte[] stored = Files.readAllBytes(bodyFile());
        BodyStore later = new BodyStore(root, files, Clock.offset(clock, java.time.Duration.ofMinutes(5)), () -> 4440);

        assertEquals(saved, later.write(owner, character, 0, "join-recovery", body(1)));
        assertArrayEquals(stored, Files.readAllBytes(bodyFile()));
    }

    @Test
    void failedFirstWritesLeaveNoBody() throws Exception {
        for (String step : List.of("write", "move")) {
            files.failOn(step, 1);
            files.partial = true;
            assertThrows(IOException.class, () -> store.write(owner, character, 0, "new-character", body(1)), step);
            assertEquals(Optional.empty(), store.read(owner, character), step);
            assertEquals(Set.of(), names(root.resolve(owner.toString())), step);
        }
    }

    @Test
    void failedReplacementsKeepTheStoredRevision() throws Exception {
        BodyRecord one = store.write(owner, character, 0, "new-character", body(1));
        byte[] stored = Files.readAllBytes(bodyFile());
        // Steps of a replacement: write .prev temp, move .prev, sync, write body temp, move body, sync.
        String[][] failures = { { "write", "1" }, { "move", "1" }, { "sync", "1" }, { "write", "2" }, { "move", "2" } };
        for (String[] failure : failures) {
            files.failOn(failure[0], Integer.parseInt(failure[1]));
            assertThrows(IOException.class, () -> store.write(owner, character, 1, "quit", body(2)), failure[0]);
            assertEquals(Optional.of(one), store.read(owner, character), failure[0]);
            assertArrayEquals(stored, Files.readAllBytes(bodyFile()));
            Set<String> remaining = names(root.resolve(owner.toString()));
            assertTrue(Set.of(character + ".body", character + ".body.prev").containsAll(remaining),
                    "No failed-write temporary files may remain: " + remaining);
            if (Files.exists(previousFile())) assertArrayEquals(stored, Files.readAllBytes(previousFile()));
        }
    }

    @Test
    void anUnconfirmedCommitCanBeRetried() throws Exception {
        store.write(owner, character, 0, "new-character", body(1));
        files.failOn("sync", 2);

        assertThrows(IOException.class, () -> store.write(owner, character, 1, "quit", body(2)));

        BodyRecord committed = store.read(owner, character).orElseThrow();
        assertEquals(2, committed.rev());
        files.calls.clear();
        assertEquals(committed, store.write(owner, character, 1, "quit", body(2)));
        assertEquals("sync " + owner, files.calls.get(files.calls.size() - 1));
    }

    @Test
    void unreadableFilesFailClosed() throws Exception {
        store.write(owner, character, 0, "new-character", body(1));
        String good = Files.readString(bodyFile());
        String payload = good.substring(good.indexOf('\n') + 1);
        List<Consumer<Path>> breaks = List.of(
                file -> write(file, good.replace("\"rev\": 1", "\"rev\": 7")),
                file -> write(file, good.replaceFirst("RPCHARACTERS-BODY", "SOMETHING-ELSE")),
                file -> write(file, good.replaceFirst("RPCHARACTERS-BODY 1", "RPCHARACTERS-BODY 2")),
                file -> write(file, good.replaceFirst("\n", " extra\n")),
                file -> write(file, good.substring(0, good.indexOf('\n'))),
                file -> write(file, ""),
                file -> write(file, sealed(payload.replace(owner.toString(), UUID.randomUUID().toString()))),
                file -> write(file, sealed(payload.replace(character.toString(), UUID.randomUUID().toString()))),
                file -> write(file, sealed(payload.replace("\"owner\": \"" + owner, "\"owner\": \"nobody"))),
                file -> write(file, sealed(payload.replace("\"kind\": \"body\"", "\"kind\": \"quarantine\""))),
                file -> write(file, sealed(payload.replace("\"rev\": 1", "\"rev\": 0"))),
                file -> write(file, sealed(payload.replace("\"dataVersion\": 4440", "\"dataVersion\": 4441"))),
                file -> write(file, sealed(payload.replace("\"dataVersion\": 4440", "\"dataVersion\": 0"))),
                file -> write(file, sealed(payload.replace("2026-10-12T08:00:00.123456789Z", "yesterday"))),
                file -> write(file, sealed(payload.replace("\"reason\": \"new-character\"", "\"reason\": \" \""))),
                file -> write(file, sealed(payload.replace("\"heldSlot\": 0", "\"heldSlot\": 12"))),
                file -> write(file, sealed(payload.replace("\"reason\"", "\"surprise\": 1,\n  \"reason\""))),
                file -> write(file, sealed("[]")),
                file -> writeBytes(file, sealed(new byte[] { '{', '"', (byte) 0xC3, '"', ':', '1', '}' })),
                file -> {
                    try {
                        Files.delete(file);
                        Files.createDirectory(file);
                    } catch (IOException error) {
                        throw new AssertionError(error);
                    }
                },
                file -> {
                    try (RandomAccessFile huge = new RandomAccessFile(file.toFile(), "rw")) {
                        huge.setLength((64L << 20) + 1);
                    } catch (IOException error) {
                        throw new AssertionError(error);
                    }
                });
        for (int index = 0; index < breaks.size(); index++) {
            deleteRecursively(bodyFile());
            write(bodyFile(), good);
            assertNotNull(store.read(owner, character).orElseThrow());
            breaks.get(index).accept(bodyFile());
            BodyCorruptException error = assertThrows(BodyCorruptException.class, () -> store.read(owner, character),
                    "break " + index);
            assertEquals(bodyFile(), error.file());
            assertThrows(BodyCorruptException.class, () -> store.write(owner, character, 1, "quit", body(2)));
        }
    }

    @Test
    void aMissingBodyWithABackupIsNotTreatedAsNew() throws Exception {
        store.write(owner, character, 0, "new-character", body(1));
        store.write(owner, character, 1, "quit", body(2));
        Files.delete(bodyFile());

        assertThrows(BodyCorruptException.class, () -> store.read(owner, character));
        assertThrows(BodyCorruptException.class, () -> store.write(owner, character, 0, "new-character", body(1)));
    }

    @Test
    void diskErrorsAreNotReportedAsCorruption() throws Exception {
        store.write(owner, character, 0, "new-character", body(1));
        Files.setPosixFilePermissions(bodyFile(), PosixFilePermissions.fromString("---------"));
        try {
            IOException error = assertThrows(IOException.class, () -> store.read(owner, character));
            assertFalse(error instanceof BodyCorruptException, error.toString());
        } finally {
            Files.setPosixFilePermissions(bodyFile(), PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void quarantineKeepsEveryCapture() throws Exception {
        BodySnapshot leftover = body(3);

        Path first = store.quarantine(owner, "room-leftovers", leftover);
        Path second = store.quarantine(owner, "room-leftovers", leftover);

        assertNotEquals(first, second);
        assertEquals(root.resolve(owner.toString()).resolve("quarantine"), first.getParent());
        assertEquals(new BodyQuarantine(owner, 4440, clock.instant(), "room-leftovers", leftover), store.readQuarantine(first));
        assertEquals(store.readQuarantine(first), store.readQuarantine(second));
        assertEquals(Optional.empty(), store.read(owner, character));

        store.write(owner, character, 0, "new-character", body(1));
        assertThrows(BodyCorruptException.class, () -> store.readQuarantine(bodyFile()));
        String quarantined = Files.readString(first);
        write(first, sealed(quarantined.substring(quarantined.indexOf('\n') + 1)
                .replace("\"kind\": \"quarantine\"", "\"kind\": \"body\"")));
        assertThrows(BodyCorruptException.class, () -> store.readQuarantine(first));
        assertThrows(BodyCorruptException.class, () -> store.readQuarantine(root.resolve("missing.body")));
    }

    @Test
    void rejectsArgumentsThatWouldWriteNonsense() {
        assertThrows(IllegalArgumentException.class, () -> store.write(owner, character, -1, "quit", body(1)));
        assertThrows(IllegalArgumentException.class, () -> store.write(owner, character, Long.MAX_VALUE - 1, "quit", body(1)));
        assertThrows(IllegalArgumentException.class, () -> store.write(owner, character, 0, null, body(1)));
        assertThrows(IllegalArgumentException.class, () -> store.write(owner, character, 0, " ", body(1)));
        assertThrows(IllegalArgumentException.class, () -> store.write(owner, character, 0, "x".repeat(201), body(1)));
        assertThrows(NullPointerException.class, () -> store.write(owner, character, 0, "quit", null));
        assertThrows(NullPointerException.class, () -> store.quarantine(null, "quit", body(1)));
        assertThrows(NullPointerException.class, () -> store.quarantine(owner, "quit", null));
        assertThrows(IllegalArgumentException.class, () -> store.quarantine(owner, "", body(1)));
        assertFalse(Files.exists(root));
    }

    @Test
    void recordsRefuseImpossibleValues() throws Exception {
        BodySnapshot body = body(1);
        Instant now = clock.instant();
        assertThrows(NullPointerException.class, () -> new BodyRecord(null, character, 1, 1, now, "r", body));
        assertThrows(NullPointerException.class, () -> new BodyRecord(owner, null, 1, 1, now, "r", body));
        assertThrows(NullPointerException.class, () -> new BodyRecord(owner, character, 1, 1, null, "r", body));
        assertThrows(NullPointerException.class, () -> new BodyRecord(owner, character, 1, 1, now, null, body));
        assertThrows(NullPointerException.class, () -> new BodyRecord(owner, character, 1, 1, now, "r", null));
        assertThrows(IllegalArgumentException.class, () -> new BodyRecord(owner, character, 0, 1, now, "r", body));
        assertThrows(IllegalArgumentException.class, () -> new BodyRecord(owner, character, 1, 0, now, "r", body));
        assertThrows(NullPointerException.class, () -> new BodyQuarantine(null, 1, now, "r", body));
        assertThrows(NullPointerException.class, () -> new BodyQuarantine(owner, 1, null, "r", body));
        assertThrows(NullPointerException.class, () -> new BodyQuarantine(owner, 1, now, null, body));
        assertThrows(NullPointerException.class, () -> new BodyQuarantine(owner, 1, now, "r", null));
        assertThrows(IllegalArgumentException.class, () -> new BodyQuarantine(owner, 0, now, "r", body));
    }

    private BodySnapshot body(int diamonds) throws IOException {
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, diamonds));
        return BodySnapshot.capture(player, Set.of()).withLocation(new Location(player.getWorld(), diamonds, 64, 0));
    }

    private Path bodyFile() {
        return root.resolve(owner.toString()).resolve(character + ".body");
    }

    private Path previousFile() {
        return root.resolve(owner.toString()).resolve(character + ".body.prev");
    }

    private static void assertConflict(org.junit.jupiter.api.function.Executable write, long expected, long actual) {
        BodyConflictException conflict = assertThrows(BodyConflictException.class, write);
        assertEquals(expected, conflict.expectedRev());
        assertEquals(actual, conflict.actualRev());
    }

    private static Set<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return Set.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static String sealed(String payload) {
        return "RPCHARACTERS-BODY 1 " + Hashing.sha256().hashString(payload, StandardCharsets.UTF_8) + "\n" + payload;
    }

    private static byte[] sealed(byte[] payload) {
        byte[] header = ("RPCHARACTERS-BODY 1 " + Hashing.sha256().hashBytes(payload) + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, bytes, header.length, payload.length);
        return bytes;
    }

    private static void write(Path file, String text) {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) Files.delete(path);
        else Files.deleteIfExists(path);
    }

    @SuppressWarnings("unused")
    private static JsonObject payload(Path file) throws IOException {
        String text = Files.readString(file);
        return BodyJson.parse(text.substring(text.indexOf('\n') + 1));
    }
}
