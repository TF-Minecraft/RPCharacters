package net.tfminecraft.rpcharacters.will;

import java.util.UUID;

/**
 * A character's will: the heir it names, or none. {@code revision} grows with every change, so the
 * website can tell a stale request; {@code lastRequest} is the last website request applied.
 */
public record CharacterWill(UUID heir, long revision, Long updatedAtMs, String lastRequest) {
    public static final CharacterWill NONE = new CharacterWill(null, 0, null, null);

    public CharacterWill {
        if (revision < 0) throw new IllegalArgumentException("Will revisions start at 0");
    }

    public boolean named() {
        return heir != null;
    }

    /**
     * The next revision naming {@code next} (null revokes), stamped at {@code atMs}. An in-game change
     * ({@code request} null) keeps the last website request, so its lost acknowledgement still replays as applied.
     */
    public CharacterWill change(UUID next, long atMs, String request) {
        return new CharacterWill(next, revision + 1, atMs, request == null ? lastRequest : request);
    }
}
