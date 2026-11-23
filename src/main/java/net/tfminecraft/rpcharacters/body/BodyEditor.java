package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Predicate;

import net.tfminecraft.rpcharacters.body.BodySnapshot.Container;

/**
 * Staff changes to a stowed character body, one slot per change. Every change asks the guard first (the body must
 * not be on a player or about to be applied), checks that the body is still at the revision staff listed, and
 * writes the next revision through {@link BodyStore}, which keeps the replaced one as {@code .body.prev}. Callers
 * run on the main thread, the same thread that activates and stows bodies, so nothing can slip between the checks
 * and the write.
 */
public final class BodyEditor {
    /** Player inventory storage slots; "first empty" never picks armour or the offhand. */
    static final int STORAGE_SLOTS = 36;

    /** Why a body cannot be changed right now, or null when it can. */
    @FunctionalInterface
    public interface Guard {
        String blocked(UUID owner, UUID character) throws IOException;
    }

    /** A refusal with a reason for staff; nothing was written. */
    public static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(String reason) {
            super(reason);
        }
    }

    /** The revision written, the slot changed and the item bytes put there or taken out. */
    public record Change(BodyRecord record, int slot, byte[] item) {
    }

    private final BodyStore store;
    private final Guard guard;

    public BodyEditor(BodyStore store, Guard guard) {
        this.store = store;
        this.guard = guard;
    }

    public BodyRecord read(UUID owner, UUID character) throws IOException {
        return store.read(owner, character).orElseThrow(() -> new Refused("This character has no stored body yet."));
    }

    /** Puts {@code item} into an empty slot; {@code slot} below 0 picks the first empty storage slot. */
    public Change put(UUID owner, UUID character, Container container, int slot, byte[] item, long expectedRev,
            String reason) throws IOException {
        if (item.length == 0) throw new IllegalArgumentException("Nothing to put");
        BodyRecord body = current(owner, character, expectedRev);
        int target = slot < 0 ? firstEmpty(body.body(), container) : slot;
        if (target < 0) throw new Refused("There is no empty slot left there.");
        if (body.body().slot(container, target).length > 0) throw new Refused("Slot " + target + " is not empty.");
        BodyRecord written = store.write(owner, character, body.rev(), reason, body.body().withSlot(container, target, item));
        return new Change(written, target, item.clone());
    }

    /** Empties a slot, only when it holds the item staff expect, and returns what it held. */
    public Change take(UUID owner, UUID character, Container container, int slot, Predicate<byte[]> expected,
            long expectedRev, String reason) throws IOException {
        BodyRecord body = current(owner, character, expectedRev);
        byte[] item = body.body().slot(container, slot);
        if (item.length == 0) throw new Refused("Slot " + slot + " is empty.");
        if (!expected.test(item)) throw new Refused("Slot " + slot + " holds a different item. List it again.");
        BodyRecord written = store.write(owner, character, body.rev(), reason, body.body().withSlot(container, slot, new byte[0]));
        return new Change(written, slot, item);
    }

    private BodyRecord current(UUID owner, UUID character, long expectedRev) throws IOException {
        String blocked = guard.blocked(owner, character);
        if (blocked != null) throw new Refused(blocked);
        BodyRecord body = read(owner, character);
        if (body.rev() != expectedRev) {
            throw new Refused("The body is at version " + body.rev() + ", not " + expectedRev + ". List it again.");
        }
        return body;
    }

    static int firstEmpty(BodySnapshot body, Container container) throws IOException {
        int count = body.slotCount(container);
        int limit = container == Container.INVENTORY ? Math.min(STORAGE_SLOTS, count) : count;
        for (int slot = 0; slot < limit; slot++) {
            if (body.slot(container, slot).length == 0) return slot;
        }
        return -1;
    }
}
