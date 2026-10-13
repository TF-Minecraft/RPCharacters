package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateDecidedEvent;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.GuildRef;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

class EstateCoordinatorTest {
    @TempDir Path root;
    private FlakyFiles files;
    private EstateTestKit kit;
    private CharacterRef aria;
    private CharacterRef kira;
    private EstateTestKit.Participant money;
    private final List<LogRecord> logs = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { logs.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void setUp() {
        files = new FlakyFiles();
        kit = new EstateTestKit(root, files);
        kit.logger.addHandler(capture);
        kit.logger.setUseParentHandlers(false);
        aria = kit.world.alive("Aria");
        kira = kit.world.alive("Kira");
        kit.world.kill(aria);
        money = new EstateTestKit.Participant("denareconomy");
        kit.world.participants.add(money);
    }

    @AfterEach
    void tearDown() {
        kit.logger.removeHandler(capture);
    }

    private boolean logged(Level level, String text) {
        return logs.stream().anyMatch(r -> r.getLevel() == level && r.getMessage().contains(text));
    }

    @Test
    void deathOpensADurableRecordThatFallsDueADayLater() throws Exception {
        EstateRecord record = kit.died(aria, kira.characterId());
        assertEquals(EstateState.OPEN, record.state);
        assertEquals(EstateTestKit.T0.plus(Duration.ofHours(24)), record.dueAt);
        assertEquals(kira.characterId(), record.willHeir);
        assertEquals(1, record.willRevision);
        assertEquals("COMMAND", record.cause);
        assertTrue(record.wasActive);
        assertEquals(List.of(aria.ownerId()), kit.pushes);
        assertTrue(files.steps.indexOf("write") < files.steps.indexOf("move " + record.deathId + ".json"));
        assertEquals("sync estates", files.steps.getLast().startsWith("append") ? files.steps.get(files.steps.size() - 2)
                : files.steps.getLast());
        EstateBook reloaded = new EstateBook(root.resolve("estates"), DurableFiles.DISK, kit.clock, kit.logger);
        reloaded.load();
        assertEquals(EstateJson.write(record), EstateJson.write(reloaded.get(record.deathId).orElseThrow()));
        assertTrue(Files.readString(root.resolve("estates/audit.log")).contains("opened " + record.deathId));
        kit.coordinator.died(aria, "Aria", record.deathId, Instant.now(), null, 0, false, "OTHER", null, null);
        assertEquals("COMMAND", kit.book.get(record.deathId).orElseThrow().cause);
    }

    @Test
    void aDeathWhoseRecordCannotBeSavedIsLeftForReconcile() {
        files.failWrites = true;
        UUID death = kit.world.deaths.get(aria.characterId());
        kit.coordinator.died(aria, "Aria", death, EstateTestKit.T0, null, 0, true, "COMMAND", null, null);
        assertTrue(kit.book.get(death).isEmpty());
        assertTrue(logged(Level.SEVERE, "reconcile will add it"));
        files.failWrites = false;
        kit.world.dead.add(new EstateWorld.Dead(kit.world.infos.get(aria.characterId()), death, EstateTestKit.T0, null, 0));
        kit.coordinator.reconcile();
        assertEquals(EstateTestKit.T0.plus(Duration.ofHours(24)), kit.book.get(death).orElseThrow().dueAt);
    }

    @Test
    void dueEstatesAreDecidedPublishedAndSettled() {
        EstateRecord record = kit.died(aria, kira.characterId());
        kit.clock.advance(Duration.ofHours(23));
        kit.coordinator.decideDue();
        assertEquals(EstateState.OPEN, kit.book.get(record.deathId).orElseThrow().state);
        kit.clock.advance(Duration.ofHours(1));
        money.answer = d -> Outcome.done(Map.of("cents", 34025, "postings", 1), List.of("estate:" + d.deathId() + ":1"));
        kit.coordinator.decideDue();
        EstateRecord decided = kit.book.get(record.deathId).orElseThrow();
        assertEquals(EstateState.DECIDED, decided.state);
        assertEquals(Recipient.character(kira), decided.decision.property().recipient());
        EstateDecidedEvent event = assertInstanceOf(EstateDecidedEvent.class, kit.events.getFirst());
        assertEquals(record.deathId, event.getDecision().deathId());
        assertEquals(1, money.asked.size());
        Report report = decided.participants.get("denareconomy");
        assertEquals("DONE", report.outcome());
        assertEquals(34025, report.counts().get("cents"));
        assertEquals(List.of("estate:" + record.deathId + ":1"), report.movedIds());
        assertTrue(kit.pushes.size() >= 3);
        kit.coordinator.decideDue();
        assertEquals(1, money.asked.size());
    }

    @Test
    void postponedDecisionsWaitQuietlyAreListedAfterSixHoursAndDecideWhenSimpleFactionsAnswers() {
        EstateRecord record = kit.died(aria, null);
        kit.world.defaultDeathGuild = DeathGuild.UNAVAILABLE;
        kit.clock.advance(Duration.ofHours(24));
        assertEquals("postponed: SimpleFactions has not answered", kit.coordinator.decide(record, kit.clock.instant()));
        EstateRecord postponed = kit.book.get(record.deathId).orElseThrow();
        assertEquals(kit.clock.instant(), postponed.postponedSince);
        Instant saved = postponed.updatedAt;
        kit.clock.advance(Duration.ofMinutes(1));
        kit.coordinator.decideDue();
        assertEquals(saved, kit.book.get(record.deathId).orElseThrow().updatedAt);
        assertFalse(logged(Level.WARNING, "has been postponed"));
        kit.clock.advance(Duration.ofHours(6));
        kit.coordinator.decideDue();
        kit.coordinator.decideDue();
        assertEquals(1, logs.stream().filter(r -> r.getMessage().contains("has been postponed")).count());
        kit.world.defaultDeathGuild = DeathGuild.NONE;
        kit.coordinator.decideDue();
        EstateRecord decided = kit.book.get(record.deathId).orElseThrow();
        assertEquals(EstateState.DECIDED, decided.state);
        assertNull(decided.postponedSince);
        assertNull(decided.postponeReason);
        assertTrue(decided.decision.anyFrozen());
    }

    @Test
    void aTestatorRevivedBehindTheRecordsBackIsCancelledAtTheDecisionAndReopenedIfStillDead() {
        EstateRecord record = kit.died(aria, null);
        kit.world.status(aria, CharacterStatus.ALIVE);
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        EstateRecord cancelled = kit.book.get(record.deathId).orElseThrow();
        assertEquals(EstateState.CANCELLED, cancelled.state);
        assertNotNull(cancelled.cancelledAt);
        kit.world.status(aria, CharacterStatus.DEAD);
        kit.world.dead.add(new EstateWorld.Dead(kit.world.infos.get(aria.characterId()), record.deathId, EstateTestKit.T0, null, 0));
        kit.coordinator.reconcile();
        EstateRecord reopened = kit.book.get(record.deathId).orElseThrow();
        assertEquals(EstateState.OPEN, reopened.state);
        assertNull(reopened.cancelledAt);
        kit.coordinator.reconcile();
        assertEquals(EstateState.OPEN, kit.book.get(record.deathId).orElseThrow().state);
    }

    @Test
    void reconcileGivesLegacyDeathsADayFromNowCopiesTheSavedWillAndSkipsUnreadableRecords() throws Exception {
        CharacterRef legacy = kit.world.alive("Ilvaran Thorne");
        UUID legacyDeath = kit.world.kill(legacy);
        CharacterRef broken = kit.world.alive("Broken");
        UUID brokenDeath = kit.world.kill(broken);
        Files.createDirectories(root.resolve("estates"));
        Files.writeString(root.resolve("estates/" + brokenDeath + ".json"), "{not json");
        kit.book.load();
        kit.clock.advance(Duration.ofHours(5));
        kit.world.dead.add(new EstateWorld.Dead(kit.world.infos.get(legacy.characterId()), legacyDeath, null, kira.characterId(), 3));
        kit.world.dead.add(new EstateWorld.Dead(kit.world.infos.get(broken.characterId()), brokenDeath, null, null, 0));
        kit.coordinator.reconcile();
        EstateRecord record = kit.book.get(legacyDeath).orElseThrow();
        assertEquals(kit.clock.instant().plus(Duration.ofHours(24)), record.dueAt);
        assertNull(record.diedAt);
        assertEquals(kira.characterId(), record.willHeir);
        assertEquals(3, record.willRevision);
        assertTrue(kit.book.get(brokenDeath).isEmpty());
        assertEquals("{not json", Files.readString(root.resolve("estates/" + brokenDeath + ".json")));
        files.failWrites = true;
        kit.world.dead.add(new EstateWorld.Dead(kit.world.infos.get(aria.characterId()), kit.world.deaths.get(aria.characterId()),
                null, null, 0));
        kit.coordinator.reconcile();
        assertTrue(logged(Level.SEVERE, "Could not reconcile"));
    }

    @Test
    void participantsAreRetriedHourlyUntilTheyAnswerAndFailuresBecomeRetries() {
        EstateTestKit.Participant shops = new EstateTestKit.Participant("bartershops");
        EstateTestKit.Participant broken = new EstateTestKit.Participant("broken");
        EstateTestKit.Participant locks = new EstateTestKit.Participant("thievery");
        kit.world.participants.addAll(List.of(shops, broken, locks));
        shops.answer = d -> Outcome.retry("ledger not ready");
        broken.answer = d -> { throw new IllegalStateException("boom"); };
        locks.answer = d -> Outcome.AT_RESTART;
        money.answer = d -> Outcome.blocked("conflict");
        EstateRecord record = kit.decided(aria, null);
        assertEquals("RETRY", record.participants.get("bartershops").outcome());
        assertTrue(record.participants.get("broken").reason().contains("boom"));
        kit.coordinator.hourly();
        assertEquals(2, shops.asked.size());
        assertEquals(2, broken.asked.size());
        assertEquals(1, locks.asked.size());
        assertEquals(1, money.asked.size());
        shops.answer = d -> Outcome.done();
        kit.coordinator.hourly();
        kit.coordinator.hourly();
        assertEquals(3, shops.asked.size());
        assertEquals("DONE", kit.book.get(record.deathId).orElseThrow().participants.get("bartershops").outcome());
        EstateTestKit.Participant late = new EstateTestKit.Participant("research");
        kit.world.participants.add(late);
        kit.coordinator.hourly();
        assertEquals(1, late.asked.size());
    }

    @Test
    void reportsKeepTheNewestRevisionAndSurviveAParticipantReportingDuringItsOwnSettle() {
        money.answer = d -> {
            kit.coordinator.report(d.deathId(), "thievery", d.revision(), Outcome.AT_RESTART);
            return Outcome.done();
        };
        EstateRecord record = kit.decided(aria, null);
        assertEquals("AT_RESTART", record.participants.get("thievery").outcome());
        assertEquals("DONE", record.participants.get("denareconomy").outcome());
        int pushes = kit.pushes.size();
        assertFalse(kit.coordinator.record(record.deathId, "thievery", 0,
                kit.coordinator.report(Outcome.AT_RESTART, 0)));
        assertEquals(pushes, kit.pushes.size());
        assertTrue(kit.coordinator.record(record.deathId, "thievery", 2, kit.coordinator.report(Outcome.done(), 2)));
        assertFalse(kit.coordinator.record(record.deathId, "thievery", 1, kit.coordinator.report(Outcome.retry("old"), 1)));
        assertFalse(kit.coordinator.record(UUID.randomUUID(), "thievery", 0, kit.coordinator.report(Outcome.done(), 0)));
        files.failWrites = true;
        assertFalse(kit.coordinator.record(record.deathId, "thievery", 3, kit.coordinator.report(Outcome.done(), 3)));
        assertTrue(logged(Level.SEVERE, "Could not save thievery's answer"));
    }

    @Test
    void aWaitingPartIsResolvedAsANewRevisionAndEveryParticipantAnswersIt() {
        UUID guild = kit.world.liveGuild("Ironhand", null, null);
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(guild, true, Optional.empty(), Optional.empty()));
        EstateRecord record = kit.decided(aria, null);
        assertTrue(record.decision.anyWaiting());
        kit.coordinator.recheckWaiting();
        assertEquals(0, kit.book.get(record.deathId).orElseThrow().decision.revision());
        kit.world.guilds.put(guild, GuildLookup.live(new GuildRef(guild, "Ironhand", Optional.empty(),
                Optional.of(kira.characterId()), "guild:" + guild)));
        kit.coordinator.recheckWaiting();
        EstateRecord revised = kit.book.get(record.deathId).orElseThrow();
        assertEquals(1, revised.decision.revision());
        assertEquals(Recipient.character(kira), revised.decision.property().recipient());
        assertEquals(1, revised.participants.get("denareconomy").revision());
        assertEquals(2, money.asked.size());
        assertEquals(2, kit.events.size());
        assertEquals(1, kit.coordinator.decision(record.deathId).orElseThrow().revision());
        files.failWrites = true;
        assertFalse(kit.coordinator.revise(revised, revised.decision.revise(revised.decision.money(),
                revised.decision.property(), revised.decision.guildLocks()), "test"));
        assertEquals(1, kit.book.get(record.deathId).orElseThrow().decision.revision());
    }

    @Test
    void theServiceAnswersForDecidedEstatesAndTheLatestDeath() {
        EstateRecord open = kit.died(aria, null);
        assertTrue(kit.coordinator.decision(open.deathId).isEmpty());
        assertEquals(Optional.of(EstateState.OPEN), kit.coordinator.stateOf(aria.characterId()));
        assertTrue(kit.coordinator.stateOf(kira.characterId()).isEmpty());
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        assertEquals(1, kit.coordinator.decided().size());
        assertEquals(Optional.of(EstateState.DECIDED), kit.coordinator.stateOf(aria.characterId()));
        kit.coordinator.revived(open.deathId, false, "Staff");
        assertTrue(kit.coordinator.decided().isEmpty());
        assertTrue(kit.coordinator.decision(open.deathId).isPresent());
        assertTrue(kit.coordinator.decision(UUID.randomUUID()).isEmpty());
    }

    @Test
    void aDecidedEstateHoldsARevivalUntilEveryPartHasMoved() {
        assertTrue(kit.coordinator.reviveBlockers(UUID.randomUUID()).isEmpty());
        EstateRecord open = kit.died(aria, null);
        assertTrue(kit.coordinator.reviveBlockers(open.deathId).isEmpty());
        EstateTestKit.Participant locks = new EstateTestKit.Participant("thievery");
        EstateTestKit.Participant shops = new EstateTestKit.Participant("bartershops");
        EstateTestKit.Participant letters = new EstateTestKit.Participant("birdmessenger");
        kit.world.participants.addAll(List.of(locks, shops, letters));
        locks.answer = d -> Outcome.AT_RESTART;
        shops.answer = d -> Outcome.blocked("leftover intent");
        letters.answer = d -> Outcome.retry("mail not loaded");
        UUID guild = kit.world.liveGuild("Ironhand", null, null);
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(guild, true, Optional.empty(), Optional.empty()));
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        EstateTestKit.Participant late = new EstateTestKit.Participant("research");
        kit.world.participants.add(late);
        List<String> blockers = kit.coordinator.reviveBlockers(open.deathId);
        assertEquals(List.of("part of the estate waits for the death guild to have a leader",
                "thievery moves its records at the next restart",
                "bartershops is blocked: leftover intent (staff: /estate retry or /estate waive)",
                "birdmessenger has not finished: mail not loaded", "research has not answered yet"), blockers);
        assertEquals("x has not answered yet", EstateCoordinator.describe("x",
                new Report("DONE", 0, null, Map.of(), List.of(), EstateTestKit.T0), 1));
    }

    @Test
    void revivesCancelOpenEstatesAndMarkDecidedOnesSoParticipantsStop() {
        EstateRecord open = kit.died(aria, null);
        kit.coordinator.revived(open.deathId, false, "Staff");
        assertEquals(EstateState.CANCELLED, kit.book.get(open.deathId).orElseThrow().state);
        Instant before = kit.book.get(open.deathId).orElseThrow().updatedAt;
        kit.clock.advance(Duration.ofMinutes(1));
        kit.coordinator.revived(open.deathId, false, "Staff");
        assertEquals(before, kit.book.get(open.deathId).orElseThrow().updatedAt);
        kit.coordinator.revived(UUID.randomUUID(), false, "Staff");

        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        EstateRecord decided = kit.decided(bran, null);
        List<EstateRecord> forcedBodies = new ArrayList<>();
        kit.coordinator.bodies(forcedBodies::add);
        kit.coordinator.revived(decided.deathId, true, "Staff");
        EstateRecord revived = kit.book.get(decided.deathId).orElseThrow();
        assertNotNull(revived.revivedAt);
        assertTrue(revived.revivedNote.contains("parts not yet moved stay with the character"));
        assertEquals(1, forcedBodies.size());
        int asked = money.asked.size();
        kit.coordinator.hourly();
        assertEquals(asked, money.asked.size());

        CharacterRef cai = kit.world.alive("Cai");
        kit.world.kill(cai);
        EstateRecord plain = kit.decided(cai, null);
        kit.coordinator.revived(plain.deathId, false, "Staff");
        assertTrue(kit.book.get(plain.deathId).orElseThrow().revivedNote.contains("after the estate passed"));

        CharacterRef dag = kit.world.alive("Dag");
        kit.world.kill(dag);
        EstateRecord failing = kit.died(dag, null);
        files.failWrites = true;
        kit.coordinator.revived(failing.deathId, false, "Staff");
        assertEquals(EstateState.OPEN, kit.book.get(failing.deathId).orElseThrow().state);
        assertTrue(logged(Level.SEVERE, "was saved but its estate was not updated"));
        kit.clock.advance(Duration.ofDays(1));
        assertTrue(kit.coordinator.decide(kit.book.get(failing.deathId).orElseThrow(), kit.clock.instant()).startsWith("not saved"));
    }
}
