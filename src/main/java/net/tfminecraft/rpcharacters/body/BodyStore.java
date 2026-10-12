package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;

import org.bukkit.Bukkit;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;

/**
 * Stowed character bodies, one checksummed file per character at {@code <root>/<owner>/<character>.body}.
 * A write replaces the file atomically only when the caller names the stored revision, keeps the replaced
 * revision as {@code .body.prev}, and forces both to disk. Nothing is cached, and unreadable files fail closed.
 */
public final class BodyStore {
    private static final String MAGIC = "RPCHARACTERS-BODY";
    private static final int FORMAT = 1;
    private static final long MAX_FILE_BYTES = 64L << 20;
    private static final int MAX_REASON = 200;
    private static final Set<String> BODY_KEYS = Set.of("kind", "owner", "character", "rev", "dataVersion", "savedAt",
            "reason", "body");
    private static final Set<String> QUARANTINE_KEYS = Set.of("kind", "owner", "dataVersion", "savedAt", "reason",
            "body");

    private final Path root;
    private final BodyFiles files;
    private final Clock clock;
    private final IntSupplier dataVersion;

    public BodyStore(Path root) {
        this(root, BodyFiles.DISK, Clock.systemUTC(), () -> Bukkit.getUnsafe().getDataVersion());
    }

    BodyStore(Path root, BodyFiles files, Clock clock, IntSupplier dataVersion) {
        this.root = root.toAbsolutePath().normalize();
        this.files = files;
        this.clock = clock;
        this.dataVersion = dataVersion;
    }

    /** Empty only when the character has never had a stored body. */
    public synchronized Optional<BodyRecord> read(UUID ownerId, UUID characterId) throws IOException {
        Path file = bodyFile(ownerId, characterId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(previous(file), LinkOption.NOFOLLOW_LINKS)) {
                throw new BodyCorruptException(file, "Character body is missing but its previous revision remains", null);
            }
            return Optional.empty();
        }
        JsonObject payload = open(file);
        try {
            BodyJson.keys(payload, "body record", BODY_KEYS);
            if (!BodyJson.string(payload, "kind").equals("body")) throw new IOException("Not a character body");
            if (!BodyJson.uuid(payload, "owner").equals(ownerId) || !BodyJson.uuid(payload, "character").equals(characterId)) {
                throw new IOException("Character body belongs to another character");
            }
            return Optional.of(new BodyRecord(ownerId, characterId, BodyJson.integer(payload, "rev", 1, Long.MAX_VALUE - 1),
                    version(payload), instant(payload), reason(payload), BodySnapshot.fromJson(BodyJson.object(payload, "body"))));
        } catch (IOException error) {
            throw new BodyCorruptException(file, error.getMessage(), error);
        }
    }

    /**
     * Stores {@code body} as revision {@code expectedRev + 1}; {@code expectedRev} is 0 for a first body. Retrying
     * the same body after its revision committed returns that revision, so recovery can repeat a write safely.
     */
    public synchronized BodyRecord write(UUID ownerId, UUID characterId, long expectedRev, String reason,
            BodySnapshot body) throws IOException {
        Objects.requireNonNull(body, "body");
        if (expectedRev < 0 || expectedRev >= Long.MAX_VALUE - 1) throw new IllegalArgumentException("Invalid body revision");
        String why = requireReason(reason);
        Path file = bodyFile(ownerId, characterId);
        Optional<BodyRecord> current = read(ownerId, characterId);
        long actual = current.map(BodyRecord::rev).orElse(0L);
        if (actual != expectedRev) {
            if (actual == expectedRev + 1 && current.get().body().equals(body)) {
                files.syncDirectory(file.getParent());
                return current.get();
            }
            throw new BodyConflictException(expectedRev, actual);
        }
        BodyRecord next = new BodyRecord(ownerId, characterId, expectedRev + 1, dataVersion.getAsInt(), clock.instant(),
                why, body);
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "body");
        payload.addProperty("owner", ownerId.toString());
        payload.addProperty("character", characterId.toString());
        payload.addProperty("rev", next.rev());
        payload.addProperty("dataVersion", next.dataVersion());
        payload.addProperty("savedAt", next.savedAt().toString());
        payload.addProperty("reason", why);
        payload.add("body", body.toJson());
        directory(file.getParent());
        if (current.isPresent()) commit(previous(file), Files.readAllBytes(file));
        commit(file, envelope(payload));
        return next;
    }

    /** Keeps a body no character can safely own. Every call makes a new file. */
    public synchronized Path quarantine(UUID ownerId, String reason, BodySnapshot body) throws IOException {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(body, "body");
        String why = requireReason(reason);
        Instant savedAt = clock.instant();
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "quarantine");
        payload.addProperty("owner", ownerId.toString());
        payload.addProperty("dataVersion", dataVersion.getAsInt());
        payload.addProperty("savedAt", savedAt.toString());
        payload.addProperty("reason", why);
        payload.add("body", body.toJson());
        Path folder = root.resolve(ownerId.toString()).resolve("quarantine");
        directory(folder);
        Path file = folder.resolve(savedAt.toEpochMilli() + "-" + UUID.randomUUID() + ".body");
        commit(file, envelope(payload));
        return file;
    }

    public synchronized BodyQuarantine readQuarantine(Path file) throws IOException {
        JsonObject payload = open(file);
        try {
            BodyJson.keys(payload, "quarantined body", QUARANTINE_KEYS);
            if (!BodyJson.string(payload, "kind").equals("quarantine")) throw new IOException("Not a quarantined body");
            return new BodyQuarantine(BodyJson.uuid(payload, "owner"), version(payload), instant(payload),
                    reason(payload), BodySnapshot.fromJson(BodyJson.object(payload, "body")));
        } catch (IOException error) {
            throw new BodyCorruptException(file, error.getMessage(), error);
        }
    }

    private Path bodyFile(UUID ownerId, UUID characterId) {
        return root.resolve(ownerId.toString()).resolve(characterId + ".body");
    }

    private static Path previous(Path file) {
        return file.resolveSibling(file.getFileName() + ".prev");
    }

    /** Creates missing folders and forces each new entry into its parent. */
    private void directory(Path folder) throws IOException {
        if (Files.isDirectory(folder)) return;
        directory(folder.getParent());
        Files.createDirectory(folder);
        files.syncDirectory(folder.getParent());
    }

    private void commit(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            files.write(temp, bytes);
            files.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        files.syncDirectory(target.getParent());
    }

    private static byte[] envelope(JsonObject payload) {
        byte[] body = BodyJson.PRETTY.toJson(payload).getBytes(StandardCharsets.UTF_8);
        byte[] header = (MAGIC + " " + FORMAT + " " + Hashing.sha256().hashBytes(body) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, bytes, header.length, body.length);
        return bytes;
    }

    /** Reads a body file and checks its header and checksum; I/O failures stay plain {@link IOException}s. */
    private static JsonObject open(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) {
            throw new BodyCorruptException(file, "Character body is not a regular file of a sane size", null);
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            int newline = 0;
            while (newline < bytes.length && bytes[newline] != '\n') newline++;
            String[] header = new String(bytes, 0, newline, StandardCharsets.US_ASCII).split(" ", -1);
            if (header.length != 3 || !header[0].equals(MAGIC) || !header[1].equals(String.valueOf(FORMAT))) {
                throw new IOException("Unknown character body header");
            }
            byte[] body = newline < bytes.length ? Arrays.copyOfRange(bytes, newline + 1, bytes.length) : new byte[0];
            if (!Hashing.sha256().hashBytes(body).toString().equals(header[2])) {
                throw new IOException("Character body checksum does not match");
            }
            return BodyJson.parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString());
        } catch (CharacterCodingException error) {
            throw new BodyCorruptException(file, "Character body is not UTF-8", error);
        } catch (IOException error) {
            throw new BodyCorruptException(file, error.getMessage(), error);
        }
    }

    private int version(JsonObject payload) throws IOException {
        int version = (int) BodyJson.integer(payload, "dataVersion", 1, Integer.MAX_VALUE);
        if (version > dataVersion.getAsInt()) throw new IOException("Character body was saved by a newer Minecraft version");
        return version;
    }

    private static Instant instant(JsonObject payload) throws IOException {
        try {
            return Instant.parse(BodyJson.string(payload, "savedAt"));
        } catch (DateTimeParseException error) {
            throw BodyJson.invalid("savedAt");
        }
    }

    private static String reason(JsonObject payload) throws IOException {
        String reason = BodyJson.string(payload, "reason");
        if (!validReason(reason)) throw BodyJson.invalid("reason");
        return reason;
    }

    private static String requireReason(String reason) {
        if (!validReason(reason)) throw new IllegalArgumentException("A body write needs a short reason");
        return reason;
    }

    private static boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= MAX_REASON;
    }
}
