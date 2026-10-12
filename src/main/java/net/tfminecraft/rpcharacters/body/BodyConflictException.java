package net.tfminecraft.rpcharacters.body;

import java.io.IOException;

/** The stored body moved on since the caller read it; nothing was written. */
public final class BodyConflictException extends IOException {
    private static final long serialVersionUID = 1L;

    private final long expectedRev;
    private final long actualRev;

    BodyConflictException(long expectedRev, long actualRev) {
        super("Character body is at revision " + actualRev + ", not " + expectedRev);
        this.expectedRev = expectedRev;
        this.actualRev = actualRev;
    }

    public long expectedRev() {
        return expectedRev;
    }

    public long actualRev() {
        return actualRev;
    }
}
