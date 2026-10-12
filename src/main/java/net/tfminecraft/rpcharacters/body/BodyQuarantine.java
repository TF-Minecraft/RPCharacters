package net.tfminecraft.rpcharacters.body;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A body set aside for staff because no character could safely own it. */
public record BodyQuarantine(UUID ownerId, int dataVersion, Instant savedAt, String reason, BodySnapshot body) {
    public BodyQuarantine {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(savedAt, "savedAt");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(body, "body");
        if (dataVersion < 1) throw new IllegalArgumentException("Body data version must be positive");
    }
}
