package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

class EstateStaffTest {
    @TempDir Path root;
    private FlakyFiles files;
    private EstateTestKit kit;
    private EstateStaff staff;
    private CharacterRef aria;
    private CharacterRef kira;
    private EstateTestKit.Participant money;

    @BeforeEach
    void setUp() {
        files = new FlakyFiles();
        kit = new EstateTestKit(root, files);
        staff = new EstateStaff(kit.coordinator, kit.logger);
        aria = kit.world.alive("Aria");
        kira = kit.world.alive("Kira");
        kit.world.kill(aria);
        money = new EstateTestKit.Participant("denareconomy");
        kit.world.participants.add(money);
    }

    private EstateRecord fresh(EstateRecord record) {
        return kit.book.get(record.deathId).orElseThrow();
    }

    private EstateRecord openEstate(String name) {
        CharacterRef ref = kit.world.alive(name);
        kit.world.kill(ref);
        return kit.died(ref, null);
    }

    private String audit() throws Exception {
        return Files.readString(root.resolve("estates/audit.log"));
    }

    @Test
    void estatesAreFoundByDeathCharacterOrDeadName() {
        EstateRecord record = kit.died(aria, null);
        assertSame(record, staff.find(record.deathId.toString()).orElseThrow());
        assertSame(record, staff.find(aria.characterId().toString()).orElseThrow());
        assertSame(record, staff.find(" aria ").orElseThrow());
        assertTrue(staff.find("nobody").isEmpty());
        assertTrue(staff.find(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void holdStopsTheClockAndReleaseRestartsItWithTheTimeLeft() throws Exception {
        EstateRecord record = kit.died(aria, null);
        kit.clock.advance(Duration.ofHours(20));
        assertEquals("Done.", staff.hold(record, "Mod"));
        assertEquals(Duration.ofHours(4).toMillis(), fresh(record).heldLeftMs);
        assertTrue(staff.hold(fresh(record), "Mod").startsWith("Only an open estate"));
        kit.clock.advance(Duration.ofDays(3));
        kit.coordinator.decideDue();
        assertEquals(EstateState.HELD, fresh(record).state);
        assertEquals("Done.", staff.release(fresh(record), "Mod"));
        assertEquals(kit.clock.instant().plus(Duration.ofHours(4)), fresh(record).dueAt);
        assertTrue(staff.release(fresh(record), "Mod").startsWith("Only a held estate"));
        assertTrue(audit().contains("staff Mod held on " + record.deathId));
        files.failWrites = true;
        assertEquals("The change could not be saved.", staff.hold(fresh(record), "Mod"));
    }

    @Test
    void decidingWithoutTheGuildNeedsAnOpenDueEstate() {
        kit.world.defaultDeathGuild = DeathGuild.UNAVAILABLE;
        EstateRecord record = kit.died(aria, kira.characterId());
        assertTrue(staff.decideWithoutGuild(record, "Mod").startsWith("This estate is not due until"));
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        assertEquals("SimpleFactions has not answered", fresh(record).postponeReason);
        assertEquals("Decision without the guild: decided.", staff.decideWithoutGuild(fresh(record), "Mod"));
        EstateRecord decided = fresh(record);
        assertTrue(decided.noGuild);
        assertEquals(Recipient.character(kira), decided.decision.guildLocks().recipient());
        assertTrue(staff.decideWithoutGuild(decided, "Mod").startsWith("Only an open estate"));
        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        EstateRecord other = kit.died(bran, null);
        kit.clock.advance(Duration.ofDays(1));
        files.failWrites = true;
        assertEquals("The change could not be saved.", staff.decideWithoutGuild(other, "Mod"));
    }

    @Test
    void assignSetsOnlyWaitingOrFrozenPartsAndCallsParticipantsAtTheNewRevision() throws Exception {
        EstateRecord record = kit.decided(aria, null);
        assertEquals("No single living character matches nobody.", staff.assign(record, "property", "nobody", "Mod"));
        assertEquals("Parts are money, property, guildlocks or all.", staff.assign(record, "letters", "Kira", "Mod"));
        assertEquals("Done: revision 1. Participants were called again.", staff.assign(record, "property", "Kira", "Mod"));
        EstateRecord once = fresh(record);
        assertEquals(Recipient.character(kira), once.decision.property().recipient());
        assertTrue(once.decision.money().recipient().isFrozen());
        assertEquals(1, money.asked.getLast().revision());
        assertEquals("Done: revision 2. Participants were called again.",
                staff.assign(once, "all", kira.characterId().toString(), "Mod"));
        EstateRecord all = fresh(record);
        assertEquals(Recipient.account("char:" + kira.characterId() + ":bank"), all.decision.money().recipient());
        assertEquals(Recipient.character(kira), all.decision.guildLocks().recipient());
        assertEquals("Nothing to assign: only WAITING or FROZEN parts can be assigned.", staff.assign(all, "money", "Kira", "Mod"));
        assertTrue(audit().contains("assign all to " + kira.characterId()));
        assertEquals("No single living character matches " + aria.characterId() + ".",
                staff.assign(all, "money", aria.characterId().toString(), "Mod"));
        files.failWrites = true;
        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        files.failWrites = false;
        EstateRecord frozen = kit.decided(bran, null);
        files.failWrites = true;
        assertEquals("The change could not be saved.", staff.assign(frozen, "guildlocks", "Kira", "Mod"));
        files.failWrites = false;
        assertEquals("Only a decided estate can be assigned.", staff.assign(openEstate("Cai"), "all", "Kira", "Mod"));
    }

    @Test
    void frozenMoneyCanBeSentToALiveGuildByNameOrIncarnation() {
        EstateRecord record = kit.decided(aria, null);
        UUID guild = kit.world.liveGuild("Ironhand", null, null);
        kit.world.guildNames.put("Shared", GuildLookup.UNAVAILABLE);
        assertTrue(staff.assignMoneyToGuild(record, "Shared", "Mod").contains("incarnation ID"));
        kit.world.guildNames.put("Silent", null);
        assertTrue(staff.assignMoneyToGuild(record, "Silent", "Mod").contains("incarnation ID"));
        assertEquals("No live guild is named Gone.", staff.assignMoneyToGuild(record, "Gone", "Mod"));
        assertEquals("Done: revision 1. Participants were called again.", staff.assignMoneyToGuild(record, "Ironhand", "Mod"));
        assertEquals(Recipient.account("guild:" + guild), fresh(record).decision.money().recipient());
        assertEquals("Only FROZEN money can be sent to a guild.", staff.assignMoneyToGuild(fresh(record), "Ironhand", "Mod"));
        assertEquals("Only a decided estate can be assigned.",
                staff.assignMoneyToGuild(openEstate("Cai"), "Ironhand", "Mod"));
    }

    @Test
    void retryAndWaiveHandleBlockedParticipantsOnly() {
        money.answer = d -> Outcome.blocked("conflict");
        EstateRecord record = kit.decided(aria, null);
        assertEquals("thievery is not blocked on this estate.", staff.retry(record, "thievery", "Mod"));
        assertEquals("thievery is not blocked at the current revision.", staff.waive(record, "thievery", "x", "Mod"));
        money.answer = d -> Outcome.done();
        assertEquals("Done.", staff.retry(fresh(record), "denareconomy", "Mod"));
        assertEquals("DONE", fresh(record).participants.get("denareconomy").outcome());
        assertEquals(2, money.asked.size());
        money.answer = d -> Outcome.blocked("conflict");
        staff.assign(fresh(record), "all", "Kira", "Mod");
        assertEquals("Done.", staff.waive(fresh(record), "denareconomy", "moved by hand", "Mod"));
        EstateRecord waived = fresh(record);
        assertEquals("WAIVED", waived.participants.get("denareconomy").outcome());
        assertEquals("moved by hand", waived.participants.get("denareconomy").reason());
        assertTrue(kit.coordinator.reviveBlockers(record.deathId).isEmpty());
        kit.coordinator.hourly();
        assertEquals(3, money.asked.size());
    }

    @Test
    void theNoGuildOverrideIsWithdrawnWhenTheDecisionStillWaits() {
        kit.world.defaultDeathGuild = DeathGuild.UNAVAILABLE;
        EstateRecord record = kit.died(aria, kira.characterId());
        kit.world.unreadable.add(kira.characterId());
        kit.clock.advance(Duration.ofDays(1));
        String answer = staff.decideWithoutGuild(record, "Mod");
        assertTrue(answer.contains("postponed: the will heir's account cannot be read"), answer);
        assertTrue(answer.contains("override was withdrawn"), answer);
        assertFalse(fresh(record).noGuild);
        kit.world.unreadable.clear();
        kit.world.defaultDeathGuild = DeathGuild.NONE;
        UUID guild = kit.world.liveGuild("Ironhand", kira, null);
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(guild, true, Optional.of(kira.characterId()), Optional.empty()));
        kit.coordinator.decideDue();
        assertEquals(guild, fresh(record).decision.deathGuild());
    }

    @Test
    void aReleasedHoldWithoutTimeLeftFallsDueAtOnce() throws Exception {
        EstateRecord record = kit.died(aria, null);
        staff.hold(record, "Mod");
        EstateRecord repaired = EstateBook.edit(fresh(record));
        repaired.heldLeftMs = null;
        kit.book.save(repaired);
        assertEquals("Done.", staff.release(fresh(record), "Mod"));
        assertEquals(kit.clock.instant(), fresh(record).dueAt);
    }

    @Test
    void aCollectionIsStrandedOnlyWhenItsRecipientIsDeadAndItsEstateWillNotReaddressIt() throws Exception {
        EstateRecord record = kit.died(aria, null);
        assertFalse(staff.stranded(record));
        EstateRecord parked = EstateBook.edit(record);
        parked.body = new EstateRecord.Body();
        assertFalse(staff.stranded(parked));
        parked.body.addressedTo = new EstateRecord.Named(kira, "Kira");
        assertFalse(staff.stranded(parked));
        kit.world.unreadable.add(kira.characterId());
        assertFalse(staff.stranded(parked));
        kit.world.unreadable.clear();
        kit.world.kill(kira);
        assertTrue(staff.stranded(parked));
        EstateRecord kiraEstate = kit.died(kira, null);
        assertFalse(staff.stranded(parked));
        staff.hold(kiraEstate, "Mod");
        assertFalse(staff.stranded(parked));
        staff.release(fresh(kiraEstate), "Mod");
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        assertFalse(staff.stranded(parked));
        staff.revert(fresh(kiraEstate), "Mod");
        assertTrue(staff.stranded(parked));
        kit.world.infos.remove(kira.characterId());
        assertTrue(staff.stranded(parked));
    }

    @Test
    void revertStopsEveryParticipant() {
        EstateRecord open = kit.died(aria, null);
        assertEquals("Only a decided estate can be reverted.", staff.revert(open, "Mod"));
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        assertEquals("Done.", staff.revert(fresh(open), "Mod"));
        assertEquals(EstateState.REVERTED, fresh(open).state);
        assertTrue(kit.coordinator.decided().isEmpty());
        assertEquals(EstateState.REVERTED, kit.coordinator.decision(open.deathId).map(d -> EstateState.REVERTED).orElseThrow());
    }

    @Test
    void listsFilterEstatesAndHideEmptyOnesUnlessAsked() {
        EstateTestKit.Participant locks = new EstateTestKit.Participant("thievery");
        kit.world.participants.add(locks);
        EstateRecord empty = kit.decided(aria, null);
        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        money.holdings = new Holdings(Map.of("cents", 34025), List.of());
        EstateRecord frozen = kit.decided(bran, null);
        money.holdings = Holdings.NONE;
        CharacterRef cai = kit.world.alive("Cai");
        kit.world.kill(cai);
        UUID guild = kit.world.liveGuild("Ironhand", null, null);
        kit.world.deathGuilds.put(cai.characterId(), DeathGuild.guild(guild, true, Optional.empty(), Optional.empty()));
        locks.answer = d -> Outcome.AT_RESTART;
        EstateRecord waiting = kit.decided(cai, null);
        CharacterRef dag = kit.world.alive("Dag");
        kit.world.kill(dag);
        EstateRecord open = kit.died(dag, null);
        staff.hold(open, "Mod");
        CharacterRef eli = kit.world.alive("Eli");
        kit.world.kill(eli);
        kit.world.defaultDeathGuild = DeathGuild.UNAVAILABLE;
        EstateRecord postponed = kit.decided(eli, null);

        assertTrue(staff.list(null).stream().noneMatch(line -> line.contains("Aria")));
        assertTrue(staff.list("empty").getFirst().contains("Aria"));
        money.holdings = new Holdings(Map.of("cents", 1), List.of());
        assertTrue(staff.list("frozen").stream().anyMatch(line -> line.contains("Bran")));
        money.holdings = Holdings.NONE;
        assertEquals(1, staff.list("waiting").size());
        assertTrue(staff.list("waiting").getFirst().contains("thievery awaiting restart"));
        assertTrue(staff.list("held").getFirst().contains("Dag [HELD] due"));
        assertTrue(staff.list("open").getFirst().contains("Eli [OPEN]"));
        assertTrue(staff.list("postponed").getFirst().contains("postponed since"));
        assertTrue(staff.list("retry").stream().anyMatch(line -> line.contains("Cai")));
        assertEquals(List.of("No estates match."), staff.list("nonsense"));
        assertFalse(staff.empty(waiting));
        assertFalse(staff.empty(fresh(postponed)));
        EstateTestKit.Participant broken = new EstateTestKit.Participant("broken") {
            @Override
            public Holdings describe(UUID characterId) {
                throw new IllegalStateException("cannot describe");
            }
        };
        kit.world.participants.add(broken);
        assertTrue(staff.empty(fresh(empty)));
        kit.world.participants.add(new BodyEstate(kit.book, null, d -> true, net.tfminecraft.rpcharacters.body.BodySnapshot.Kept.NONE, kit.logger));
        assertTrue(staff.empty(fresh(empty)));
        EstateRecord withBody = fresh(empty);
        withBody.body = new EstateRecord.Body();
        withBody.body.delivered = 1;
        assertFalse(staff.empty(withBody));
        withBody.body = new EstateRecord.Body();
        assertTrue(staff.empty(withBody));
        withBody.participants.put("x", new EstateRecord.Report("DONE", 0, null, Map.of("locks", 2), List.of(), EstateTestKit.T0));
        assertFalse(staff.empty(withBody));
        withBody.participants.put("x", new EstateRecord.Report("DONE", 0, null, Map.of(), List.of("a"), EstateTestKit.T0));
        assertFalse(staff.empty(withBody));
        assertFalse(staff.empty(fresh(frozen)) && staff.empty(fresh(open)));
    }

    @Test
    void showPrintsOneEstateInFull() {
        money.holdings = new Holdings(Map.of("cents", 34025), List.of("340.25 in pouch and bank"));
        EstateRecord open = kit.died(aria, kira.characterId());
        List<String> before = staff.show(open);
        assertTrue(before.get(1).contains("State OPEN"));
        assertTrue(before.stream().anyMatch(line -> line.contains("Holds (denareconomy)")));
        staff.hold(open, "Mod");
        assertTrue(staff.show(fresh(open)).get(1).contains("held with"));
        staff.release(fresh(open), "Mod");
        EstateTestKit.Participant locks = new EstateTestKit.Participant("thievery");
        EstateTestKit.Participant shops = new EstateTestKit.Participant("bartershops");
        kit.world.participants.addAll(List.of(locks, shops));
        locks.answer = d -> Outcome.AT_RESTART;
        money.answer = d -> Outcome.done(Map.of("cents", 34025), List.of("estate:x:1"));
        kit.clock.advance(Duration.ofDays(2));
        kit.coordinator.decideDue();
        kit.world.participants.add(new EstateTestKit.Participant("research"));
        EstateRecord decided = fresh(open);
        decided.body = new EstateRecord.Body();
        decided.body.state = "COLLECTABLE";
        decided.body.addressedTo = new EstateRecord.Named(kira, "Kira");
        decided.revivedAt = EstateTestKit.T0;
        decided.revivedNote = "forced";
        decided.revertedAt = EstateTestKit.T0;
        decided.cancelledAt = EstateTestKit.T0;
        decided.postponedSince = EstateTestKit.T0;
        decided.postponeReason = "waiting";
        decided.killer = kira;
        String shown = String.join("\n", staff.show(decided));
        assertTrue(shown.contains("heir Kira"), shown);
        assertTrue(shown.contains("Still holds (denareconomy)"), shown);
        assertTrue(shown.contains("thievery: awaiting restart at revision 0"), shown);
        assertTrue(shown.contains("denareconomy: DONE at revision 0 {cents=34025} moved [estate:x:1]"), shown);
        assertTrue(shown.contains("research: no answer"), shown);
        assertTrue(shown.contains("for Kira"), shown);
        assertTrue(shown.contains("Revived"), shown);
        assertTrue(shown.contains("Reverted"), shown);
        assertTrue(shown.contains("Cancelled"), shown);
        assertTrue(shown.contains("Postponed since"), shown);
        decided.body.addressedTo = null;
        decided.noGuild = true;
        decided.diedAt = null;
        decided.participants.put("bartershops", new EstateRecord.Report("BLOCKED", 0, "intent", Map.of(), List.of(),
                EstateTestKit.T0));
        String parked = String.join("\n", staff.show(decided));
        assertTrue(parked.contains("staff (/estate collect)"), parked);
        assertTrue(parked.contains("without the guild"), parked);
        assertTrue(parked.contains("before records began"), parked);
        assertTrue(parked.contains("(intent)"), parked);
        assertEquals(1, kit.world.participants.stream().filter(p -> p.id().equals("research")).count());
    }

    @Test
    void longLabelListsAreCutForChat() {
        assertEquals("[a, b]", EstateStaff.labels(List.of("a", "b")));
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < EstateStaff.LABELS + 3; i++) many.add("l" + i);
        assertEquals(many.subList(0, EstateStaff.LABELS) + " and 3 more", EstateStaff.labels(many));
    }
}
