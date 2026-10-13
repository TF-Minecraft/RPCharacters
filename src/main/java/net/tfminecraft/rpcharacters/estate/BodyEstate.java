package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.tfminecraft.rpcharacters.body.BodyConflictException;
import net.tfminecraft.rpcharacters.body.BodyCorruptException;
import net.tfminecraft.rpcharacters.body.BodyRecord;
import net.tfminecraft.rpcharacters.body.BodyStore;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Body;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

/**
 * The {@code rpcharacters-body} participant: strips the testator's stowed body (storage, armour, offhand and
 * ender chest) into a collection for the property recipient, through a state machine kept in the estate
 * record so a crash at any step neither loses nor duplicates items (design "RPCharacters: the stored body").
 */
final class BodyEstate implements EstateParticipant, EstateCoordinator.BodyRevival {
    static final String ID = "rpcharacters-body";
    static final String STRIPPING = "STRIPPING";
    static final String COLLECTABLE = "COLLECTABLE";
    static final String DISCARDED = "DISCARDED";

    /** The two body queries the participant needs, so tests need no character-body runtime. */
    interface Bodies {
        BodyStore store();

        boolean hasLease(UUID owner, UUID character) throws IOException;
    }

    private final EstateBook book;
    private final Bodies bodies;
    private final Predicate<EstateDecision> guard;
    private final Logger logger;

    BodyEstate(EstateBook book, Bodies bodies, Predicate<EstateDecision> guard, Logger logger) {
        this.book = book;
        this.bodies = bodies;
        this.guard = guard;
        this.logger = logger;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Outcome settle(EstateDecision decision) {
        if (!guard.test(decision)) return Outcome.retry("estate guard does not hold");
        EstateRecord record = book.get(decision.deathId()).orElse(null);
        if (record == null) return Outcome.retry("no estate record");
        try {
            readdress(record);
            if (!decision.property().isCharacter()) return Outcome.done();
            if (record.body != null && !STRIPPING.equals(record.body.state)) return done(record);
            return strip(record, decision);
        } catch (BodyCorruptException error) {
            return Outcome.blocked("stored body is corrupt: " + error.getMessage());
        } catch (BodyConflictException error) {
            return Outcome.retry("stored body changed during the strip");
        } catch (IOException error) {
            logger.log(Level.WARNING, "Body strip for " + decision.deathId() + " will be retried", error);
            return Outcome.retry("storage error: " + error.getMessage());
        }
    }

    private Outcome strip(EstateRecord record, EstateDecision decision) throws IOException {
        CharacterRef testator = decision.testator();
        UUID owner = testator.ownerId();
        UUID character = testator.characterId();
        if (bodies.hasLease(owner, character)) return Outcome.retry("the body is still in play");
        Optional<BodyRecord> read = bodies.store().read(owner, character);
        if (read.isEmpty()) return Outcome.retry("no stored body yet");
        BodyRecord body = read.get();
        String reason = "estate:" + record.deathId;
        Body state = record.body;
        if (state == null && reason.equals(body.reason())) return rederive(record, body);
        if (state != null && body.rev() == state.expectedRev + 1 && reason.equals(body.reason())) {
            return collectable(record, state.stacks, state.expectedRev);
        }
        if (state != null && body.rev() != state.expectedRev) {
            return Outcome.blocked("stored body moved on while it was being stripped");
        }
        List<String> stacks = encode(body.body().possessions());
        if (state == null) {
            if (stacks.isEmpty()) return collectable(record, stacks, body.rev());
            EstateRecord next = EstateBook.edit(record);
            next.body = new Body();
            next.body.state = STRIPPING;
            next.body.expectedRev = body.rev();
            next.body.stacks = stacks;
            next.body.addressedTo = named(decision);
            book.save(next);
            record = next;
        }
        bodies.store().write(owner, character, body.rev(), reason, body.body().stripped());
        return collectable(record, stacks, body.rev());
    }

    /** The strip committed but the record never said so: take the items back from {@code .body.prev}. */
    private Outcome rederive(EstateRecord record, BodyRecord body) throws IOException {
        Optional<BodyRecord> previous = bodies.store().previous(body.ownerId(), body.characterId());
        if (previous.isEmpty() || previous.get().rev() != body.rev() - 1) {
            return Outcome.blocked("stripped body has no matching previous revision");
        }
        return collectable(record, encode(previous.get().body().possessions()), previous.get().rev());
    }

    private Outcome collectable(EstateRecord record, List<String> stacks, long expectedRev) throws IOException {
        EstateRecord next = EstateBook.edit(record);
        Named recipient = next.body == null ? named(next.toDecision()) : next.body.addressedTo;
        next.body = next.body == null ? new Body() : next.body;
        next.body.state = COLLECTABLE;
        next.body.expectedRev = expectedRev;
        next.body.stacks = new ArrayList<>(stacks);
        next.body.addressedTo = recipient;
        book.save(next);
        book.log("audit.log", "stripped body for " + next.deathId + ": " + stacks.size() + " stacks to "
                + recipient.ref().characterId());
        return done(next);
    }

    private static Outcome done(EstateRecord record) {
        int stacks = record.body.stacks.size() + record.body.delivered;
        return Outcome.done(Map.of("stacks", stacks), stacks == 0 ? List.of() : List.of("body:" + record.testator.ownerId()
                + "/" + record.testator.characterId()));
    }

    private Named named(EstateDecision decision) {
        EstateRecord record = book.get(decision.deathId()).orElseThrow();
        return new Named(decision.property().character().orElseThrow(), record.decision.property().label());
    }

    /**
     * Collections still addressed to this testator, or parked on this estate, follow its property
     * recipient; while that is WAITING or FROZEN they are parked here for staff (design R15).
     */
    private void readdress(EstateRecord estate) throws IOException {
        UUID testator = estate.testator.characterId();
        EstateRecord.Part property = estate.decision.property();
        for (EstateRecord other : book.all()) {
            Body body = other.body;
            if (other.deathId.equals(estate.deathId) || body == null || !body.collectable() || body.stacks.isEmpty()) continue;
            boolean mine = body.addressedTo != null && body.addressedTo.ref().characterId().equals(testator);
            if (!mine && !estate.deathId.equals(body.readdressVia)) continue;
            Named to = property.recipient().character().map(ref -> new Named(ref, property.label())).orElse(null);
            if (to != null && to.equals(body.addressedTo)) continue;
            if (to == null && body.addressedTo == null) continue;
            EstateRecord next = EstateBook.edit(other);
            next.body.addressedTo = to;
            next.body.readdressVia = to == null ? estate.deathId : null;
            book.save(next);
            book.log("audit.log", "re-addressed collection " + other.deathId + " via " + estate.deathId + " to "
                    + (to == null ? "staff" : to.ref().characterId()));
        }
    }

    /** The body is read only at the strip; the will screen names what it will take. */
    @Override
    public Holdings describe(UUID characterId) {
        return new Holdings(Map.of(), List.of("Ender chest, and everything carried after a death that leaves no grave"));
    }

    /**
     * A forced revive while STRIPPING follows the observed body: an uncommitted strip discards the
     * collection, a committed one keeps it collectable (design N1).
     */
    @Override
    public void forcedRevive(EstateRecord next) {
        Body body = next.body;
        if (body == null || !STRIPPING.equals(body.state)) return;
        try {
            Optional<BodyRecord> read = bodies.store().read(next.testator.ownerId(), next.testator.characterId());
            if (read.isEmpty()) return;
            if (read.get().rev() == body.expectedRev) {
                body.state = DISCARDED;
                body.stacks = new ArrayList<>();
            } else if (read.get().rev() == body.expectedRev + 1 && ("estate:" + next.deathId).equals(read.get().reason())) {
                body.state = COLLECTABLE;
            }
        } catch (IOException error) {
            logger.log(Level.WARNING, "Could not read the body of " + next.deathId + " during a forced revive", error);
        }
    }

    private static List<String> encode(List<byte[]> stacks) {
        List<String> encoded = new ArrayList<>();
        for (byte[] stack : stacks) encoded.add(Base64.getEncoder().encodeToString(stack));
        return encoded;
    }
}
