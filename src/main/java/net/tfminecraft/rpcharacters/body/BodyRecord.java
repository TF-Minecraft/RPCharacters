package net.tfminecraft.rpcharacters.body;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One stored revision of a character's body. Revisions start at 1. */
public record BodyRecord(UUID ownerId, UUID characterId, long rev, int dataVersion, Instant savedAt, String reason,
        BodySnapshot body) {
    public BodyRecord {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(savedAt, "savedAt");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(body, "body");
        if (rev < 1) throw new IllegalArgumentException("Body revisions start at 1");
        if (dataVersion < 1) throw new IllegalArgumentException("Body data version must be positive");
    }
}
