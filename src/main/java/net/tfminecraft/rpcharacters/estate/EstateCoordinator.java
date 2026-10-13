package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.Event;

import net.tfminecraft.rpcharacters.estate.EstateDecider.Recheck;
import net.tfminecraft.rpcharacters.estate.EstateDecider.Verdict;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecidedEvent;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateService;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Outcome;

/**
 * Owns the estate clock: records at death, decisions when due, WAITING re-checks, participant calls and
 * their reports, reconcile and revival bookkeeping. Registered as the TLibs {@link EstateService}. Main thread.
 */
final class EstateCoordinator implements EstateService {
    /** Postponed decisions are listed for staff after this long. */
    static final Duration POSTPONE_WARNING = Duration.ofHours(6);

    /** Hooks back into the stored-body participant when a revive is forced. */
    interface BodyRevival {
        void forcedRevive(EstateRecord next);
    }

    private final EstateBook book;
    private final EstateWorld world;
    private final Clock clock;
    private final Duration wait;
    private final Logger logger;
    private final Consumer<UUID> rosterPush;
    private final Consumer<Event> events;
    private final Set<UUID> warnedPostponed = new HashSet<>();
    private BodyRevival bodies;

    EstateCoordinator(EstateBook book, EstateWorld world, Clock clock, Duration wait, Logger logger,
            Consumer<UUID> rosterPush, Consumer<Event> events) {
        this.book = book;
        this.world = world;
        this.clock = clock;
        this.wait = wait;
        this.logger = logger;
        this.rosterPush = rosterPush;
        this.events = events;
    }

    void bodies(BodyRevival bodies) {
        this.bodies = bodies;
    }

    EstateBook book() {
        return book;
    }

    EstateWorld world() {
        return world;
    }

    Instant now() {
        return clock.instant();
    }

    /** Called inside the kill, right after the durable death save, so a death always has its record. */
    void died(CharacterRef testator, String name, UUID deathId, Instant diedAt, UUID willHeir, long willRevision,
            boolean wasActive, String cause, UUID killerAccount, CharacterRef killer) {
        if (book.get(deathId).isPresent() || book.unreadable(deathId)) return;
        EstateRecord record = open(testator, name, deathId, diedAt, willHeir, willRevision);
        record.wasActive = wasActive;
        record.cause = cause;
        record.killerAccount = killerAccount;
        record.killer = killer;
        try {
            book.save(record);
            book.log("audit.log", "opened " + deathId + " for " + testator.characterId() + " (" + cause + ")");
            rosterPush.accept(testator.ownerId());
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Estate record for death " + deathId + " was not saved; reconcile will add it", error);
        }
    }

    private EstateRecord open(CharacterRef testator, String name, UUID deathId, Instant diedAt, UUID willHeir,
            long willRevision) {
        Instant now = clock.instant();
        EstateRecord record = new EstateRecord(deathId, testator);
        record.name = name;
        record.diedAt = diedAt;
        record.createdAt = now;
        record.dueAt = (diedAt == null ? now : diedAt).plus(wait);
        record.willHeir = willHeir;
        record.willRevision = willRevision;
        return record;
    }

    /**
     * Gives every dead character's current death a record (legacy deaths fall due one wait after now) and
     * reopens a cancelled record whose testator is still dead with that death.
     */
    void reconcile() {
        List<EstateWorld.Dead> deaths;
        try {
            deaths = world.dead();
        } catch (RuntimeException | LinkageError error) {
            logger.log(Level.SEVERE, "Estate reconcile could not list dead characters; it runs again next hour", error);
            return;
        }
        for (EstateWorld.Dead dead : deaths) {
            Optional<EstateRecord> existing = book.get(dead.deathId());
            try {
                if (existing.isEmpty() && !book.unreadable(dead.deathId())) {
                    EstateRecord record = open(dead.info().ref(), dead.info().name(), dead.deathId(), dead.diedAt(),
                            dead.willHeir(), dead.willRevision());
                    book.save(record);
                    book.log("audit.log", "reconciled " + dead.deathId() + " for " + dead.info().ref().characterId());
                    rosterPush.accept(dead.info().ref().ownerId());
                } else if (existing.isPresent() && existing.get().state == EstateState.CANCELLED) {
                    EstateRecord next = EstateBook.edit(existing.get());
                    next.state = EstateState.OPEN;
                    next.cancelledAt = null;
                    book.save(next);
                    book.log("audit.log", "reopened " + dead.deathId() + ": the testator is still dead");
                    rosterPush.accept(next.testator.ownerId());
                }
            } catch (IOException error) {
                logger.log(Level.SEVERE, "Could not reconcile the estate for death " + dead.deathId(), error);
            }
        }
    }

    /**
     * Closes estates whose testator is readable and no longer dead with that death: a revive whose estate
     * update failed after the ALIVE save. Open or held estates are cancelled; decided ones are marked revived,
     * so no participant acts for a living character (design E12).
     */
    void closeRevived() {
        for (EstateRecord record : book.all()) {
            boolean open = record.state == EstateState.OPEN || record.state == EstateState.HELD;
            if (!open && !record.settling()) continue;
            try {
                if (!revivedBehindOurBack(record)) continue;
                EstateRecord next = EstateBook.edit(record);
                if (open) {
                    next.state = EstateState.CANCELLED;
                    next.cancelledAt = clock.instant();
                } else {
                    next.revivedAt = clock.instant();
                    next.revivedNote = "The character was found alive; parts not yet moved stay with the character";
                }
                book.save(next);
                book.log("audit.log", "closed " + record.deathId + ": the testator is no longer dead with this death");
                rosterPush.accept(next.testator.ownerId());
            } catch (IOException | RuntimeException | LinkageError error) {
                logger.log(Level.SEVERE, "Could not close the estate of revived death " + record.deathId, error);
            }
        }
    }

    /** Readable, and no longer dead with this death. Unreadable testators are left alone. */
    private boolean revivedBehindOurBack(EstateRecord record) {
        UUID testator = record.testator.characterId();
        if (world.unreadable(testator)) return false;
        boolean dead = world.info(testator).map(info -> info.status() == net.tfminecraft.tlibs.character.CharacterStatus.DEAD)
                .orElse(false);
        return !dead || !world.deathId(testator).equals(Optional.of(record.deathId));
    }

    /** Decides every open estate that is due. Runs every minute, at boot and when SimpleFactions starts. */
    void decideDue() {
        Instant now = clock.instant();
        for (EstateRecord record : book.all()) {
            if (record.state != EstateState.OPEN || now.isBefore(record.dueAt)) continue;
            try {
                decide(record, now);
            } catch (RuntimeException | LinkageError error) {
                logger.log(Level.SEVERE, "Deciding estate " + record.deathId + " failed; it is tried again next minute", error);
            }
        }
    }

    /** One decision attempt; staff use it directly for {@code --no-guild}. Returns the outcome in words. */
    String decide(EstateRecord record, Instant now) {
        Verdict verdict = EstateDecider.decide(record, world, now);
        try {
            EstateRecord next = EstateBook.edit(record);
            switch (verdict) {
                case Verdict.Decided decided -> {
                    next.state = EstateState.DECIDED;
                    next.decision = decided.decision();
                    next.postponedSince = null;
                    next.postponeReason = null;
                    book.save(next);
                    book.log("audit.log", "decided " + next.deathId + " revision 0");
                    published(next);
                    return "decided";
                }
                case Verdict.Cancel cancel -> {
                    next.state = EstateState.CANCELLED;
                    next.cancelledAt = now;
                    book.save(next);
                    book.log("audit.log", "cancelled " + next.deathId + ": " + cancel.reason());
                    rosterPush.accept(next.testator.ownerId());
                    return "cancelled: " + cancel.reason();
                }
                case Verdict.Postpone postpone -> {
                    if (!postpone.reason().equals(record.postponeReason)) {
                        next.postponedSince = record.postponedSince == null ? now : record.postponedSince;
                        next.postponeReason = postpone.reason();
                        book.save(next);
                    }
                    warnLong(next, now);
                    return "postponed: " + postpone.reason();
                }
            }
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Could not save the decision for death " + record.deathId, error);
            return "not saved: " + error.getMessage();
        }
    }

    private void warnLong(EstateRecord record, Instant now) {
        if (record.postponedSince.plus(POSTPONE_WARNING).isAfter(now) || !warnedPostponed.add(record.deathId)) return;
        logger.warning("Estate " + record.deathId + " of " + record.name + " has been postponed since "
                + record.postponedSince + ": " + record.postponeReason + ". See /estate list postponed.");
    }

    /** Re-checks WAITING parts against the death guild now. Runs hourly and when SimpleFactions starts. */
    void recheckWaiting() {
        for (EstateRecord record : book.all()) {
            if (!record.settling() || !record.decision.anyWaiting()) continue;
            try {
                if (EstateDecider.recheck(record, world) instanceof Recheck.Revised revised) {
                    revise(record, revised.decision(), "WAITING resolved");
                }
            } catch (RuntimeException | LinkageError error) {
                logger.log(Level.SEVERE, "Re-checking estate " + record.deathId + " failed; it is tried again later", error);
            }
        }
    }

    /** Saves a new revision, then publishes it and calls every participant again. */
    boolean revise(EstateRecord record, EstateRecord.Decision decision, String why) {
        try {
            EstateRecord next = EstateBook.edit(record);
            next.decision = decision;
            book.save(next);
            book.log("audit.log", "revised " + next.deathId + " to revision " + decision.revision() + ": " + why);
            published(next);
            return true;
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Could not save revision " + decision.revision() + " of " + record.deathId, error);
            return false;
        }
    }

    private void published(EstateRecord record) {
        events.accept(new EstateDecidedEvent(record.toDecision()));
        rosterPush.accept(record.testator.ownerId());
        settleLogged(record.deathId, true);
    }

    private void settleLogged(UUID deathId, boolean all) {
        try {
            settle(deathId, all);
        } catch (RuntimeException | LinkageError error) {
            logger.log(Level.SEVERE, "Settling estate " + deathId + " failed; it is tried again next hour", error);
        }
    }

    /**
     * Calls participants for a decided estate. With {@code all} false only those that have not answered the
     * current revision, or answered RETRY, are called (the hourly retry).
     */
    void settle(UUID deathId, boolean all) {
        EstateRecord record = book.get(deathId).orElse(null);
        if (record == null || !record.settling() || revivedBehindOurBack(record)) return;
        EstateDecision decision = record.toDecision();
        for (EstateParticipant participant : world.participants()) {
            String id = participant.id();
            Report last = book.get(deathId).orElseThrow().participants.get(id);
            if (!all && last != null && last.revision() >= decision.revision() && !last.outcome().equals("RETRY")) continue;
            Outcome outcome;
            try {
                outcome = participant.settle(decision);
            } catch (RuntimeException | LinkageError error) {
                logger.log(Level.WARNING, "Estate participant " + id + " failed on " + deathId, error);
                outcome = Outcome.retry("failed: " + error);
            }
            record(deathId, id, decision.revision(), report(outcome, decision.revision()));
        }
    }

    /** Hourly: reconcile, WAITING re-checks and participant retries. */
    void hourly() {
        reconcile();
        closeRevived();
        recheckWaiting();
        for (EstateRecord record : book.all()) settleLogged(record.deathId, false);
    }

    @Override
    public Optional<EstateDecision> decision(UUID deathId) {
        return book.get(deathId).filter(r -> r.decision != null
                && (r.state == EstateState.DECIDED || r.state == EstateState.REVERTED)).map(EstateRecord::toDecision);
    }

    @Override
    public List<EstateDecision> decided() {
        return book.all().stream().filter(EstateRecord::settling).map(EstateRecord::toDecision).toList();
    }

    @Override
    public Optional<EstateState> stateOf(UUID characterId) {
        return world.deathId(characterId).flatMap(book::get).map(r -> r.state);
    }

    @Override
    public void report(UUID deathId, String participantId, long revision, Outcome outcome) {
        record(deathId, participantId, revision, report(outcome, revision));
    }

    /** Stores a participant's answer unless it is older than the one held or changes nothing. */
    boolean record(UUID deathId, String participantId, long revision, Report report) {
        EstateRecord current = book.get(deathId).orElse(null);
        if (current == null || current.decision == null || current.decision.revision() != revision) {
            logger.warning("Ignored " + participantId + "'s answer for " + deathId + " at revision " + revision
                    + ": it is not the estate's current revision");
            return false;
        }
        Report last = current.participants.get(participantId);
        if (last != null && sameAnswer(last, report)) return false;
        try {
            EstateRecord next = EstateBook.edit(current);
            next.participants.put(participantId, report);
            book.save(next);
            rosterPush.accept(next.testator.ownerId());
            return true;
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Could not save " + participantId + "'s answer for " + deathId, error);
            return false;
        }
    }

    private static boolean sameAnswer(Report a, Report b) {
        return a.outcome().equals(b.outcome()) && a.revision() == b.revision() && a.counts().equals(b.counts())
                && a.movedIds().equals(b.movedIds()) && java.util.Objects.equals(a.reason(), b.reason());
    }

    Report report(Outcome outcome, long revision) {
        Instant at = clock.instant();
        return switch (outcome) {
            case Outcome.Done done -> new Report("DONE", revision, null, done.counts(), done.movedIds(), at);
            case Outcome.Retry retry -> new Report("RETRY", revision, retry.reason(), Map.of(), List.of(), at);
            case Outcome.AtRestart ignored -> new Report("AT_RESTART", revision, null, Map.of(), List.of(), at);
            case Outcome.Blocked blocked -> new Report("BLOCKED", revision, blocked.reason(), Map.of(), List.of(), at);
        };
    }

    /** Why a revive must wait, or an empty list when it may go ahead (design E12). */
    List<String> reviveBlockers(UUID deathId) {
        EstateRecord record = book.get(deathId).orElse(null);
        if (record == null || !record.settling()) return List.of();
        List<String> blockers = new ArrayList<>();
        long revision = record.decision.revision();
        if (record.decision.anyWaiting()) blockers.add("part of the estate waits for the death guild to have a leader");
        for (EstateParticipant participant : world.participants()) {
            Report report = record.participants.get(participant.id());
            if (report != null && report.settles(revision)) continue;
            blockers.add(describe(participant.id(), report, revision));
        }
        return blockers;
    }

    static String describe(String id, Report report, long revision) {
        if (report == null || report.revision() < revision) return id + " has not answered yet";
        return switch (report.outcome()) {
            case "AT_RESTART" -> id + " moves its records at the next restart";
            case "BLOCKED" -> id + " is blocked: " + report.reason() + " (staff: /estate retry or /estate waive)";
            default -> id + " has not finished: " + report.reason();
        };
    }

    /**
     * Records a revive that was already saved: an open or held estate is cancelled; a decided one keeps
     * what moved and stops every participant (design E12).
     */
    void revived(UUID deathId, boolean forced, String by) {
        EstateRecord record = book.get(deathId).orElse(null);
        if (record == null) return;
        Instant now = clock.instant();
        try {
            EstateRecord next = EstateBook.edit(record);
            if (record.state == EstateState.OPEN || record.state == EstateState.HELD) {
                next.state = EstateState.CANCELLED;
                next.cancelledAt = now;
            } else if (record.settling()) {
                next.revivedAt = now;
                next.revivedNote = forced ? "Revive forced by " + by + "; parts not yet moved stay with the character"
                        : "Revived by " + by + " after the estate passed";
                if (forced && bodies != null) bodies.forcedRevive(next);
            } else {
                return;
            }
            book.save(next);
            book.log("audit.log", (forced ? "forced revive of " : "revive of ") + deathId + " by " + by + ": " + next.state);
            rosterPush.accept(next.testator.ownerId());
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Revive of " + deathId + " was saved but its estate was not updated; "
                    + "the hourly pass closes it", error);
        }
    }
}
