package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;

/** Durable activation evidence: Paper's player-data save does not report failed writes. */
final class BodyLeaseStore {
    private record Lease(BodyTag.Live body, boolean pendingRespawn, boolean respawnCompleted) {}
    private final Path root;
    private final BodyFiles files;

    BodyLeaseStore(Path root) {
        this(root, BodyFiles.DISK);
    }

    BodyLeaseStore(Path root, BodyFiles files) {
        this.root = root.toAbsolutePath().normalize();
        this.files = files;
    }

    /** A room tag with an outstanding activation, or a live tag without its exact lease, is ambiguous. */
    synchronized void validate(UUID owner, BodyTag tag) throws IOException {
        Optional<Lease> lease = read(owner);
        if (tag instanceof BodyTag.Live live) {
            if (lease.isEmpty() || !live.equals(lease.get().body())) {
                throw new IOException("Live body does not match its durable activation lease");
            }
        } else if (lease.isPresent()) {
            throw new IOException("Body activation is incomplete; retain the lease and player data for recovery");
        }
    }

    /** Commit before restoring any playable items. Existing evidence is never replaced. */
    synchronized void begin(UUID owner, BodyTag.Live live) throws IOException {
        if (live.rev() < 1) throw new IllegalArgumentException("An activation needs a stored body revision");
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("owner", owner.toString());
        json.addProperty("character", live.characterId().toString());
        json.addProperty("rev", live.rev());
        json.addProperty("pendingRespawn", false);
        json.addProperty("respawnCompleted", false);
        directory(root);
        Path temporary = root.resolve(".lease-" + UUID.randomUUID() + ".tmp");
        try {
            files.write(temporary, BodyJson.GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            // A hard link publishes atomically without replacing an existing lease on Unix.
            Files.createLink(file(owner), temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
        files.syncDirectory(root);
    }

    /** Persist before a dead live body can respawn, including an early client respawn before MMO load. */
    synchronized void markRespawn(UUID owner, BodyTag.Live live) throws IOException {
        validate(owner, live);
        if (read(owner).orElseThrow().pendingRespawn()) return;
        JsonObject json = BodyJson.parse(Files.readString(file(owner), StandardCharsets.UTF_8));
        json.addProperty("pendingRespawn", true);
        replace(owner, json);
    }

    synchronized void completeRespawn(UUID owner, BodyTag.Live live) throws IOException {
        if (!pendingRespawn(owner, live)) throw new IOException("Body has no pending respawn");
        JsonObject json = BodyJson.parse(Files.readString(file(owner), StandardCharsets.UTF_8));
        json.addProperty("respawnCompleted", true);
        replace(owner, json);
    }

    private void replace(UUID owner, JsonObject json) throws IOException {
        Path temporary = root.resolve(".respawn-" + UUID.randomUUID() + ".tmp");
        try {
            files.write(temporary, BodyJson.GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            files.move(temporary, file(owner));
            files.syncDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized boolean pendingRespawn(UUID owner, BodyTag.Live live) throws IOException {
        validate(owner, live);
        return read(owner).orElseThrow().pendingRespawn();
    }

    synchronized boolean respawnCompleted(UUID owner, BodyTag.Live live) throws IOException {
        validate(owner, live);
        return read(owner).orElseThrow().respawnCompleted();
    }

    /** Only after the stowed body committed and the cleared player was saved with a room tag. */
    synchronized void complete(UUID owner, BodyTag.Live live) throws IOException {
        validate(owner, live);
        Files.delete(file(owner));
        files.syncDirectory(root);
    }

    /** Whether the account's activation lease names this character, whose body is then still in player data. */
    synchronized boolean names(UUID owner, UUID character) throws IOException {
        return read(owner).map(lease -> lease.body().characterId().equals(character)).orElse(false);
    }

    private Optional<Lease> read(UUID owner) throws IOException {
        Path file = file(owner);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 2048) {
            throw new IOException("Body activation lease is not a regular file of a sane size");
        }
        JsonObject json = BodyJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        BodyJson.keys(json, "body activation lease", Set.of("format", "owner", "character", "rev", "pendingRespawn", "respawnCompleted"));
        BodyJson.integer(json, "format", 1, 1);
        if (!owner.equals(BodyJson.uuid(json, "owner"))) throw new IOException("Body activation lease belongs to another account");
        boolean pending = BodyJson.bool(json, "pendingRespawn"), completed = BodyJson.bool(json, "respawnCompleted");
        if (completed && !pending) throw new IOException("Body respawn completion has no pending intent");
        return Optional.of(new Lease(new BodyTag.Live(BodyJson.uuid(json, "character"),
                BodyJson.integer(json, "rev", 1, Long.MAX_VALUE - 1)), pending, completed));
    }

    private Path file(UUID owner) {
        return root.resolve(owner + ".json");
    }

    private void directory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) return;
        directory(directory.getParent());
        Files.createDirectory(directory);
        files.syncDirectory(directory.getParent());
    }
}
