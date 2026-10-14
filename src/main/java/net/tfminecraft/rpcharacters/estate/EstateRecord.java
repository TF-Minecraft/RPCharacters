package net.tfminecraft.rpcharacters.estate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Recipient;

/**
 * One death's estate, stored at {@code data/estates/<deathId>.json}. Main thread only; every change is
 * saved through {@link EstateBook#save} before anyone acts on it.
 */
final class EstateRecord {
    /** A character with the name shown for it. */
    record Named(CharacterRef ref, String name) {
        Named {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(name, "name");
        }
    }

    /** One part's recipient, with how the site shows it: CHARACTER, GUILD, REALM, WAITING, FROZEN or SENDER. */
    record Part(Recipient recipient, String shown, String label) {
        static final Part WAITING = new Part(Recipient.WAITING, "WAITING", null);
        static final Part FROZEN = new Part(Recipient.FROZEN, "FROZEN", null);
        static final Part SENDER = new Part(Recipient.SENDER, "SENDER", null);

        static Part character(Named named) {
            return new Part(Recipient.character(named.ref()), "CHARACTER", named.name());
        }

        static Part bank(Named named) {
            return new Part(Recipient.account("char:" + named.ref().characterId() + ":bank"), "CHARACTER", named.name());
        }

        static Part treasury(String account, String shown, String label) {
            return new Part(Recipient.account(account), shown, label);
        }
    }

    /** The recipients fixed at the decision; a new revision replaces it whole. */
    record Decision(Instant decidedAt, long revision, Named heir, UUID deathGuild, UUID deathRealm, Part money,
            Part property, Part guildLocks, Part letters) {
        boolean anyWaiting() {
            return property.recipient().isWaiting() || guildLocks.recipient().isWaiting();
        }

        boolean anyFrozen() {
            return money.recipient().isFrozen() || property.recipient().isFrozen() || guildLocks.recipient().isFrozen();
        }

        Decision revise(Part nextMoney, Part nextProperty, Part nextGuildLocks) {
            return new Decision(decidedAt, revision + 1, heir, deathGuild, deathRealm, nextMoney, nextProperty,
                    nextGuildLocks, letters);
        }
    }

    /** A participant's last answer, and the decision revision it answered. WAIVED is set by staff. */
    record Report(String outcome, long revision, String reason, Map<String, Integer> counts, List<String> movedIds,
            Instant at) {
        Report {
            counts = Map.copyOf(counts);
            movedIds = List.copyOf(movedIds);
        }

        boolean settles(long current) {
            return revision == current && (outcome.equals("DONE") || outcome.equals("WAIVED"));
        }
    }

    /** The stored body's strip and what is left to collect. */
    static final class Body {
        String state;
        long expectedRev;
        List<String> stacks = new ArrayList<>();
        Named addressedTo;
        UUID readdressVia;
        int delivered;

        boolean collectable() {
            return "COLLECTABLE".equals(state);
        }
    }

    final UUID deathId;
    final CharacterRef testator;
    String name;
    Instant diedAt;
    Instant createdAt;
    Instant dueAt;
    EstateState state = EstateState.OPEN;
    Long heldLeftMs;
    UUID willHeir;
    long willRevision;
    Boolean wasActive;
    String cause;
    UUID killerAccount;
    CharacterRef killer;
    Instant postponedSince;
    String postponeReason;
    boolean noGuild;
    /** The guildless wait was added to {@code dueAt}; it is added once. */
    boolean guildlessHeld;
    Decision decision;
    final Map<String, Report> participants = new TreeMap<>();
    Body body;
    Instant revivedAt;
    String revivedNote;
    Instant cancelledAt;
    Instant revertedAt;
    Instant updatedAt;

    EstateRecord(UUID deathId, CharacterRef testator) {
        this.deathId = Objects.requireNonNull(deathId, "deathId");
        this.testator = Objects.requireNonNull(testator, "testator");
    }

    /** Participants act only on decided estates that were neither reverted nor revived. */
    boolean settling() {
        return state == EstateState.DECIDED && revivedAt == null;
    }

    EstateDecision toDecision() {
        Decision d = decision;
        return new EstateDecision(deathId, testator, Optional.ofNullable(diedAt), d.decidedAt(),
                Optional.ofNullable(d.heir()).map(Named::ref), Optional.ofNullable(d.deathGuild()),
                Optional.ofNullable(d.deathRealm()), d.money().recipient(), d.property().recipient(),
                d.guildLocks().recipient(), d.letters().recipient(), d.revision());
    }
}
