package net.tfminecraft.rpcharacters.focus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies legacy files without replacing either owner's existing state. */
final class FocusMigration {
    private FocusMigration() {}

    static void copyLegacy(Path legacy, Path destination) throws IOException {
        copyMissing(legacy.resolve("focus.yml"), destination.resolve("focus.yml"));
        Path source = legacy.resolve("data/focus");
        if (Files.notExists(source)) return;
        try (var files = Files.list(source)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                copyMissing(file, destination.resolve("data/focus").resolve(file.getFileName()));
            }
        }
    }

    private static void copyMissing(Path source, Path destination) throws IOException {
        if (Files.notExists(source)) return;
        if (!Files.notExists(destination)) {
            if (!Files.isRegularFile(destination)) throw new IOException("Not a regular file: " + destination);
            return;
        }
        if (!Files.isRegularFile(source)) throw new IOException("Not a regular file: " + source);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".focus-migration-", ".tmp");
        try {
            Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // No REPLACE_EXISTING: a retry must never clobber state already owned by RPCharacters.
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
