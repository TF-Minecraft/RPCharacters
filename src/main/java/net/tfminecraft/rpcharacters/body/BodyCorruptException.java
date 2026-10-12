package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.file.Path;

/** A body file that cannot be trusted. The character stays unplayable until staff repair it. */
public final class BodyCorruptException extends IOException {
    private static final long serialVersionUID = 1L;

    private final transient Path file;

    BodyCorruptException(Path file, String message, Throwable cause) {
        super(message + ": " + file, cause);
        this.file = file;
    }

    public Path file() {
        return file;
    }
}
