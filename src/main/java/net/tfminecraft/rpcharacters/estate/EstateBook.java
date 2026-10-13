package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every estate record, kept in memory and on disk under {@code data/estates}. A save is durable (temporary
 * file, fsync, atomic move, directory fsync) before it returns; a record that cannot be read stays on disk
 * and its death is never given a second record.
 */
final class EstateBook {
    private final Path folder;
    private final DurableFiles files;
    private final Clock clock;
    private final Logger logger;
    private final Map<UUID, EstateRecord> records = new LinkedHashMap<>();
    private final Set<UUID> unreadable = new HashSet<>();

    EstateBook(Path folder, DurableFiles files, Clock clock, Logger logger) {
        this.folder = folder.toAbsolutePath().normalize();
        this.files = files;
        this.clock = clock;
        this.logger = logger;
    }

    /** Loads every record; unreadable files are logged and kept out of reconcile. */
    void load() throws IOException {
        records.clear();
        unreadable.clear();
        if (!Files.isDirectory(folder)) return;
        List<Path> paths;
        try (var listing = Files.list(folder)) {
            paths = listing.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path path : paths) {
            String stem = path.getFileName().toString().replace(".json", "");
            try {
                EstateRecord record = EstateJson.read(Files.readString(path, StandardCharsets.UTF_8));
                if (!record.deathId.toString().equals(stem)) throw new IOException("File name and deathId differ");
                records.put(record.deathId, record);
            } catch (IOException | RuntimeException error) {
                markUnreadable(stem);
                logger.log(Level.SEVERE, "Estate record " + path + " cannot be read; staff must repair it", error);
            }
        }
    }

    private void markUnreadable(String stem) {
        try {
            unreadable.add(UUID.fromString(stem));
        } catch (IllegalArgumentException ignored) {
            // Not an estate file name; nothing can collide with it.
        }
    }

    boolean unreadable(UUID deathId) {
        return unreadable.contains(deathId);
    }

    Optional<EstateRecord> get(UUID deathId) {
        return Optional.ofNullable(records.get(deathId));
    }

    Collection<EstateRecord> all() {
        List<EstateRecord> sorted = new ArrayList<>(records.values());
        sorted.sort(Comparator.comparing((EstateRecord r) -> r.createdAt).thenComparing(r -> r.deathId));
        return sorted;
    }

    /**
     * A copy to change and then {@link #save}. The stored record is replaced only once the copy is on
     * disk, so a failed save leaves memory as it was.
     */
    static EstateRecord edit(EstateRecord record) throws IOException {
        return EstateJson.read(EstateJson.write(record));
    }

    /**
     * Stamps {@code updatedAt}, writes the record durably, then stores it as read back from what was written.
     * Every stored record is therefore exactly what a restart loads, so the decision it gives participants is
     * equal (to the millisecond) to the one they were given before, until a new revision.
     */
    void save(EstateRecord record) throws IOException {
        record.updatedAt = clock.instant();
        String json = EstateJson.write(record);
        write(folder.resolve(record.deathId + ".json"), json.getBytes(StandardCharsets.UTF_8));
        records.put(record.deathId, EstateJson.read(json));
    }

    /** Appends one line to a log beside the records, such as {@code audit.log}. Failures are logged only. */
    void log(String name, String line) {
        try {
            directory();
            files.append(folder.resolve(name), (clock.instant() + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            logger.log(Level.WARNING, "Could not append to estate " + name + ": " + line, error);
        }
    }

    private void write(Path target, byte[] bytes) throws IOException {
        directory();
        Path temp = folder.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            files.write(temp, bytes);
            files.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        files.syncDirectory(folder);
    }

    private void directory() throws IOException {
        if (Files.isDirectory(folder)) return;
        Files.createDirectories(folder);
        files.syncDirectory(folder.getParent());
    }
}
