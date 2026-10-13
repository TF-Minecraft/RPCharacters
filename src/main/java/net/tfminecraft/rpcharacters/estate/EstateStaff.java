package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Decision;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.Holdings;

/** The staff side of {@code /estate}: every change is audit-logged with the staff member's name. */
final class EstateStaff {
    private final EstateCoordinator estates;
    private final EstateBook book;
    private final EstateWorld world;
    private final Logger logger;

    EstateStaff(EstateCoordinator estates, Logger logger) {
        this.estates = estates;
        this.book = estates.book();
        this.world = estates.world();
        this.logger = logger;
    }

    /** A death ID, a character ID, or a dead character's name (its latest death). */
    Optional<EstateRecord> find(String key) {
        try {
            UUID id = UUID.fromString(key);
            return book.get(id).or(() -> world.deathId(id).flatMap(book::get));
        } catch (IllegalArgumentException notUuid) {
            return book.all().stream().filter(r -> r.name.equalsIgnoreCase(key.trim()))
                    .reduce((older, newer) -> newer);
        }
    }

    String hold(EstateRecord record, String staff) {
        if (record.state != EstateState.OPEN) return "Only an open estate can be held; this one is " + record.state + ".";
        return change(record, staff, "held", next -> {
            next.state = EstateState.HELD;
            next.heldLeftMs = Math.max(0, Duration.between(estates.now(), record.dueAt).toMillis());
        });
    }

    String release(EstateRecord record, String staff) {
        if (record.state != EstateState.HELD) return "Only a held estate can be released; this one is " + record.state + ".";
        return change(record, staff, "released", next -> {
            next.state = EstateState.OPEN;
            next.dueAt = estates.now().plusMillis(record.heldLeftMs);
            next.heldLeftMs = null;
        });
    }

    String decideWithoutGuild(EstateRecord record, String staff) {
        if (record.state != EstateState.OPEN) return "Only an open estate can be decided; this one is " + record.state + ".";
        if (estates.now().isBefore(record.dueAt)) return "This estate is not due until " + record.dueAt + ".";
        String saved = change(record, staff, "decide --no-guild", next -> next.noGuild = true);
        if (!saved.startsWith("Done")) return saved;
        return "Decision without the guild: " + estates.decide(book.get(record.deathId).orElseThrow(), estates.now()) + ".";
    }

    /** {@code assign <deathId> <money|property|guildlocks|all> <character>}: WAITING or FROZEN parts only. */
    String assign(EstateRecord record, String part, String target, String staff) {
        if (!record.settling()) return "Only a decided estate can be assigned.";
        Optional<CharacterInfo> heir = living(target);
        if (heir.isEmpty()) return "No single living character matches " + target + ".";
        Named to = new Named(heir.get().ref(), heir.get().name());
        Decision d = record.decision;
        String which = part.toLowerCase(Locale.ROOT);
        boolean all = which.equals("all");
        if (!all && !List.of("money", "property", "guildlocks").contains(which)) {
            return "Parts are money, property, guildlocks or all.";
        }
        Part money = (all || which.equals("money")) && open(d.money()) ? Part.bank(to) : d.money();
        Part property = (all || which.equals("property")) && open(d.property()) ? Part.character(to) : d.property();
        Part locks = (all || which.equals("guildlocks")) && open(d.guildLocks()) ? Part.character(to) : d.guildLocks();
        if (money == d.money() && property == d.property() && locks == d.guildLocks()) {
            return "Nothing to assign: only WAITING or FROZEN parts can be assigned.";
        }
        return revise(record, d.revise(money, property, locks), staff, "assign " + which + " to " + to.ref().characterId());
    }

    /** {@code assign <deathId> money guild <name>}: FROZEN money to a live guild's treasury. */
    String assignMoneyToGuild(EstateRecord record, String guild, String staff) {
        if (!record.settling()) return "Only a decided estate can be assigned.";
        if (!record.decision.money().recipient().isFrozen()) return "Only FROZEN money can be sent to a guild.";
        GuildLookup lookup = world.guildByName(guild);
        if (lookup instanceof GuildLookup.Unavailable) return "SimpleFactions has not answered; try again later.";
        if (!(lookup instanceof GuildLookup.Live live)) return "No live guild is named " + guild + ".";
        Decision d = record.decision;
        Part money = Part.treasury(live.guild().treasuryAccount(), "GUILD", live.guild().name());
        return revise(record, d.revise(money, d.property(), d.guildLocks()), staff, "assign money to guild "
                + live.guild().incarnation());
    }

    private String revise(EstateRecord record, Decision decision, String staff, String what) {
        if (!estates.revise(record, decision, "staff " + staff + ": " + what)) return "The change could not be saved.";
        return "Done: revision " + decision.revision() + ". Participants were called again.";
    }

    String retry(EstateRecord record, String participant, String staff) {
        Report report = record.participants.get(participant);
        if (!record.settling() || report == null || !report.outcome().equals("BLOCKED")) {
            return participant + " is not blocked on this estate.";
        }
        String saved = change(record, staff, "retry " + participant, next -> next.participants.remove(participant));
        if (saved.startsWith("Done")) estates.settle(record.deathId, false);
        return saved;
    }

    String waive(EstateRecord record, String participant, String note, String staff) {
        Report report = record.participants.get(participant);
        if (!record.settling() || report == null || !report.outcome().equals("BLOCKED")
                || report.revision() != record.decision.revision()) {
            return participant + " is not blocked at the current revision.";
        }
        return change(record, staff, "waive " + participant + ": " + note, next -> next.participants.put(participant,
                new Report("WAIVED", report.revision(), note, report.counts(), report.movedIds(), estates.now())));
    }

    String revert(EstateRecord record, String staff) {
        if (!record.settling()) return "Only a decided estate can be reverted.";
        return change(record, staff, "reverted", next -> {
            next.state = EstateState.REVERTED;
            next.revertedAt = estates.now();
        });
    }

    private interface Change {
        void apply(EstateRecord next);
    }

    private String change(EstateRecord record, String staff, String what, Change change) {
        try {
            EstateRecord next = EstateBook.edit(record);
            change.apply(next);
            book.save(next);
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Staff change to estate " + record.deathId + " was not saved", error);
            return "The change could not be saved.";
        }
        book.log("audit.log", "staff " + staff + " " + what + " on " + record.deathId);
        logger.info("[estate] " + staff + " " + what + " on " + record.deathId);
        return "Done.";
    }

    private static boolean open(Part part) {
        return part.recipient().isWaiting() || part.recipient().isFrozen();
    }

    private Optional<CharacterInfo> living(String target) {
        try {
            return world.info(UUID.fromString(target)).filter(info -> info.status() == CharacterStatus.ALIVE);
        } catch (IllegalArgumentException notUuid) {
            List<CharacterInfo> found = world.findByName(target);
            return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
        }
    }

    /** {@code /estate list [filter]}; empty estates show only when asked for. */
    List<String> list(String filter) {
        List<String> lines = new ArrayList<>();
        for (EstateRecord record : book.all()) {
            if (matches(record, filter)) lines.add(summary(record));
        }
        if (lines.isEmpty()) lines.add("No estates match.");
        return lines;
    }

    private boolean matches(EstateRecord r, String filter) {
        long revision = r.decision == null ? -1 : r.decision.revision();
        return switch (filter == null ? "" : filter.toLowerCase(Locale.ROOT)) {
            case "" -> !empty(r);
            case "open" -> r.state == EstateState.OPEN;
            case "held" -> r.state == EstateState.HELD;
            case "waiting" -> r.settling() && r.decision.anyWaiting();
            case "frozen" -> r.settling() && r.decision.anyFrozen();
            case "retry" -> r.settling() && world.participants().stream()
                    .anyMatch(p -> r.participants.get(p.id()) == null || !r.participants.get(p.id()).settles(revision));
            case "postponed" -> r.state == EstateState.OPEN && r.postponeReason != null;
            case "empty" -> empty(r);
            default -> false;
        };
    }

    /** Decided, nothing waiting, no collection, and no participant holds anything for the testator. */
    boolean empty(EstateRecord r) {
        if (r.decision == null || r.state == EstateState.OPEN || r.state == EstateState.HELD || r.decision.anyWaiting()) {
            return false;
        }
        if (r.body != null && (!r.body.stacks.isEmpty() || r.body.delivered > 0)) return false;
        for (Report report : r.participants.values()) {
            if (report.counts().values().stream().anyMatch(n -> n > 0) || !report.movedIds().isEmpty()) return false;
        }
        return holdings(r).isEmpty();
    }

    private Map<String, Holdings> holdings(EstateRecord r) {
        Map<String, Holdings> found = new java.util.TreeMap<>();
        for (EstateParticipant participant : world.participants()) {
            if (participant.id().equals(BodyEstate.ID)) continue;
            try {
                Holdings held = participant.describe(r.testator.characterId());
                if (!held.isEmpty()) found.put(participant.id(), held);
            } catch (RuntimeException | LinkageError error) {
                logger.log(Level.WARNING, "Estate participant " + participant.id() + " could not describe holdings", error);
            }
        }
        return found;
    }

    private String summary(EstateRecord r) {
        StringBuilder line = new StringBuilder(r.deathId.toString().substring(0, 8)).append(' ').append(r.name)
                .append(" [").append(r.state).append(']');
        if (r.state == EstateState.OPEN || r.state == EstateState.HELD) line.append(" due ").append(r.dueAt);
        if (r.postponeReason != null) line.append(" postponed since ").append(r.postponedSince).append(": ")
                .append(r.postponeReason);
        if (r.decision != null) line.append(' ').append(parts(r.decision)).append(" | ").append(answers(r));
        return line.toString();
    }

    private static String parts(Decision d) {
        return "money " + shown(d.money()) + ", property " + shown(d.property()) + ", guild locks "
                + shown(d.guildLocks()) + ", letters " + shown(d.letters());
    }

    private static String shown(Part part) {
        return part.label() == null ? part.shown() : part.shown() + " " + part.label();
    }

    private String answers(EstateRecord r) {
        List<String> answers = new ArrayList<>();
        for (EstateParticipant participant : world.participants()) {
            Report report = r.participants.get(participant.id());
            answers.add(participant.id() + " " + (report == null ? "no answer" : report.outcome().equals("AT_RESTART")
                    ? "awaiting restart" : report.outcome() + " r" + report.revision()));
        }
        return answers.isEmpty() ? "no participants" : String.join(", ", answers);
    }

    /** {@code /estate show}: one estate in full. */
    List<String> show(EstateRecord r) {
        List<String> lines = new ArrayList<>();
        lines.add("Estate " + r.deathId + " of " + r.name + " (" + r.testator.characterId() + ", account "
                + r.testator.ownerId() + ")");
        lines.add("State " + r.state + "; died " + (r.diedAt == null ? "before records began" : r.diedAt) + "; due "
                + r.dueAt + (r.heldLeftMs == null ? "" : "; held with " + Duration.ofMillis(r.heldLeftMs) + " left"));
        lines.add("Will heir " + (r.willHeir == null ? "none" : r.willHeir) + " (will revision " + r.willRevision
                + "); cause " + r.cause + "; was active " + r.wasActive + "; killer "
                + (r.killer == null ? r.killerAccount : r.killer.characterId()));
        if (r.postponeReason != null) lines.add("Postponed since " + r.postponedSince + ": " + r.postponeReason);
        if (r.decision == null) {
            holdings(r).forEach((id, held) -> lines.add("Holds (" + id + "): " + held.counts() + " " + held.labels()));
        } else {
            Decision d = r.decision;
            lines.add("Decided " + d.decidedAt() + ", revision " + d.revision() + (r.noGuild ? ", without the guild" : "")
                    + "; heir " + (d.heir() == null ? "none" : d.heir().name()) + "; death guild " + d.deathGuild()
                    + "; realm " + d.deathRealm());
            lines.add(parts(d));
            for (EstateParticipant participant : world.participants()) {
                Report report = r.participants.get(participant.id());
                lines.add(report == null ? participant.id() + ": no answer"
                        : participant.id() + ": " + (report.outcome().equals("AT_RESTART") ? "awaiting restart"
                                : report.outcome()) + " at revision " + report.revision()
                                + (report.reason() == null ? "" : " (" + report.reason() + ")") + " " + report.counts()
                                + (report.movedIds().isEmpty() ? "" : " moved " + report.movedIds()));
            }
        }
        if (r.body != null) {
            lines.add("Body " + r.body.state + ": " + r.body.stacks.size() + " stacks left, " + r.body.delivered
                    + " collected, for " + (r.body.addressedTo == null ? "staff (/estate collect)" : r.body.addressedTo.name()));
        }
        if (r.revivedAt != null) lines.add("Revived " + r.revivedAt + ": " + r.revivedNote);
        if (r.revertedAt != null) lines.add("Reverted " + r.revertedAt + ".");
        if (r.cancelledAt != null) lines.add("Cancelled " + r.cancelledAt + ".");
        return lines;
    }
}
