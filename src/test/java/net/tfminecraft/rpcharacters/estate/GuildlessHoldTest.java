package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.GuildRef;
import net.tfminecraft.tlibs.character.estate.Recipient;

/** A death with no living heir and no guild, or in a realm with no leader, stays open for the guildless wait. */
class GuildlessHoldTest {
    private static final Duration WEEK = Duration.ofDays(7);

    @TempDir Path root;
    private EstateTestKit kit;
    private CharacterRef aria;
    private CharacterRef kira;

    @BeforeEach
    void setUp() {
        kit = new EstateTestKit(root, DurableFiles.DISK, WEEK);
        aria = kit.world.alive("Aria");
        kira = kit.world.alive("Kira");
        kit.world.kill(aria);
    }

    private EstateRecord now(EstateRecord record) {
        return kit.book.get(record.deathId).orElseThrow();
    }

    @Test
    void noWillAndNoGuildStaysOpenForAWeekThenFreezes() throws Exception {
        EstateRecord record = kit.died(aria, null);
        kit.clock.advance(Duration.ofHours(24));
        kit.pushes.clear();
        kit.coordinator.decideDue();
        EstateRecord held = now(record);
        assertEquals(EstateState.OPEN, held.state);
        assertEquals(EstateTestKit.T0.plus(WEEK), held.dueAt);
        assertEquals(1, kit.pushes.size());
        assertTrue(Files.readString(root.resolve("estates/audit.log"))
                .contains("held " + record.deathId + " until " + held.dueAt + ": no heir and no guild"));
        kit.clock.advance(Duration.ofDays(5));
        kit.coordinator.decideDue();
        assertEquals(EstateState.OPEN, now(record).state);
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        EstateRecord decided = now(record);
        assertEquals(EstateState.DECIDED, decided.state);
        assertEquals(Recipient.FROZEN, decided.decision.money().recipient());
        assertEquals(Recipient.FROZEN, decided.decision.property().recipient());
        assertEquals(Recipient.FROZEN, decided.decision.guildLocks().recipient());
    }

    @Test
    void aWillHeirWhoDiedFirstIsHeldTooAndAReviveInsideTheWeekCancels() {
        kit.world.kill(kira);
        EstateRecord record = kit.died(aria, kira.characterId());
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        assertEquals(EstateState.OPEN, now(record).state);
        kit.world.status(aria, CharacterStatus.ALIVE);
        kit.clock.advance(Duration.ofDays(6));
        kit.coordinator.decideDue();
        assertEquals(EstateState.CANCELLED, now(record).state);
    }

    @Test
    void aLivingHeirOrADeathGuildDecidesAfterADay() {
        EstateRecord withHeir = kit.died(aria, kira.characterId());
        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        UUID guild = kit.world.liveGuild("Riverwatch", kira, null);
        kit.world.deathGuilds.put(bran.characterId(),
                DeathGuild.guild(guild, true, Optional.of(kira.characterId()), Optional.empty()));
        EstateRecord inGuild = kit.died(bran, null);
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        assertEquals(EstateState.DECIDED, now(withHeir).state);
        assertEquals(EstateState.DECIDED, now(inGuild).state);
    }

    @Test
    void aDeathInARealmWithNoLeaderWaitsTheWeekEvenWithAnHeir() throws Exception {
        UUID realm = UUID.randomUUID();
        kit.world.guilds.put(realm, GuildLookup.live(new GuildRef(realm, "Riverwatch", Optional.of(realm),
                Optional.empty(), "guild:" + realm)));
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(realm, true, Optional.empty(), Optional.of(realm)));
        EstateRecord record = kit.died(aria, kira.characterId());
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        EstateRecord held = now(record);
        assertEquals(EstateState.OPEN, held.state);
        assertEquals(EstateTestKit.T0.plus(WEEK), held.dueAt);
        assertTrue(Files.readString(root.resolve("estates/audit.log"))
                .contains("held " + record.deathId + " until " + held.dueAt + ": the realm has no leader"));

        kit.clock.advance(Duration.ofDays(6));
        kit.coordinator.decideDue();
        assertEquals(EstateState.OPEN, now(record).state);
        assertEquals("the realm has no leader", now(record).postponeReason);

        kit.world.guilds.put(realm, GuildLookup.GONE);
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(realm, false, Optional.empty(), Optional.of(realm)));
        kit.clock.advance(Duration.ofHours(1));
        kit.coordinator.decideDue();
        EstateRecord decided = now(record);
        assertEquals(EstateState.DECIDED, decided.state);
        assertEquals(Recipient.character(kira), decided.decision.guildLocks().recipient());
    }

    @Test
    void aRealmStillWithoutALeaderAWeekLaterNoLongerHoldsTheEstate() {
        UUID realm = UUID.randomUUID();
        kit.world.guilds.put(realm, GuildLookup.live(new GuildRef(realm, "Riverwatch", Optional.of(realm),
                Optional.empty(), "guild:" + realm)));
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(realm, true, Optional.empty(), Optional.of(realm)));
        EstateRecord record = kit.died(aria, null);
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        kit.clock.advance(Duration.ofDays(12));
        kit.coordinator.decideDue();
        assertEquals(EstateState.OPEN, now(record).state);
        kit.clock.advance(Duration.ofDays(1));
        kit.coordinator.decideDue();
        EstateRecord decided = now(record);
        assertEquals(EstateState.DECIDED, decided.state);
        assertEquals(Recipient.account("guild:" + realm), decided.decision.money().recipient());
        assertTrue(decided.decision.guildLocks().recipient().isWaiting());
    }

    @Test
    void aGuildWithNoLeaderInALedRealmOrARealmWithALeaderDecidesAfterADay() {
        UUID realm = kit.world.liveGuild("Crown", kira, null);
        UUID guild = kit.world.liveGuild("Smiths", null, realm);
        kit.world.deathGuilds.put(aria.characterId(), DeathGuild.guild(guild, true, Optional.empty(), Optional.of(realm)));
        EstateRecord inGuild = kit.died(aria, null);
        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        kit.world.deathGuilds.put(bran.characterId(),
                DeathGuild.guild(realm, true, Optional.of(kira.characterId()), Optional.of(realm)));
        EstateRecord inRealm = kit.died(bran, null);
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        assertEquals(EstateState.DECIDED, now(inGuild).state);
        assertEquals(EstateState.DECIDED, now(inRealm).state);
    }

    @Test
    void staffHoldTimeCountsOnTopAndNoGuildDecidesAtOnce() {
        EstateStaff staff = new EstateStaff(kit.coordinator, kit.logger);
        EstateRecord record = kit.died(aria, null);
        kit.clock.advance(Duration.ofHours(12));
        assertEquals("Done.", staff.hold(record, "Mod"));
        kit.clock.advance(Duration.ofDays(2));
        assertEquals("Done.", staff.release(now(record), "Mod"));
        Instant released = kit.clock.instant().plus(Duration.ofHours(12));
        assertEquals(released, now(record).dueAt);
        kit.clock.advance(Duration.ofHours(12));
        kit.coordinator.decideDue();
        assertEquals(released.plus(Duration.ofDays(6)), now(record).dueAt);
        kit.clock.advance(Duration.ofDays(6));
        kit.coordinator.decideDue();
        assertEquals(EstateState.DECIDED, now(record).state);

        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        EstateRecord other = kit.died(bran, null);
        kit.clock.advance(Duration.ofHours(24));
        assertEquals("Decision without the guild: decided.", staff.decideWithoutGuild(other, "Mod"));
        assertEquals(EstateState.DECIDED, now(other).state);
    }

    @Test
    void legacyDeathsCountTheWeekFromTheirRecord() {
        CharacterRef legacy = kit.world.alive("Ilvaran Thorne");
        UUID death = kit.world.kill(legacy);
        kit.world.dead.add(new EstateWorld.Dead(kit.world.infos.get(legacy.characterId()), death, null, null, 0));
        kit.coordinator.reconcile();
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        EstateRecord held = kit.book.get(death).orElseThrow();
        assertEquals(EstateState.OPEN, held.state);
        assertEquals(EstateTestKit.T0.plus(WEEK), held.dueAt);
    }

    @Test
    void aStaffHoldLongerThanTheWeekStillAddsTheWeekOnce() {
        EstateStaff staff = new EstateStaff(kit.coordinator, kit.logger);
        EstateRecord record = kit.died(aria, null);
        assertEquals("Done.", staff.hold(record, "Mod"));
        kit.clock.advance(Duration.ofDays(8));
        assertEquals("Done.", staff.release(now(record), "Mod"));
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        EstateRecord held = now(record);
        assertEquals(EstateState.OPEN, held.state);
        assertTrue(held.guildlessHeld);
        assertEquals(kit.clock.instant().plus(Duration.ofDays(6)), held.dueAt);
        kit.clock.advance(Duration.ofDays(6));
        kit.coordinator.decideDue();
        assertEquals(EstateState.DECIDED, now(record).state);
    }

    @Test
    void aWaitChangedAtARestartAddsTheDifferenceOnceThenDecides() {
        EstateRecord record = kit.died(aria, null);
        EstateCoordinator restarted = new EstateCoordinator(kit.book, kit.world, kit.clock, Duration.ofHours(48), WEEK,
                kit.logger, kit.pushes::add, kit.events::add);
        kit.clock.advance(Duration.ofHours(24));
        restarted.decideDue();
        assertEquals(EstateTestKit.T0.plus(Duration.ofHours(24 + 120)), now(record).dueAt);
        kit.clock.advance(Duration.ofHours(120));
        restarted.decideDue();
        assertEquals(EstateState.DECIDED, now(record).state);
    }

    @Test
    void equalWaitsDecideAtTheFirstDueTime() {
        EstateRecord record = kit.died(aria, null);
        EstateCoordinator equal = new EstateCoordinator(kit.book, kit.world, kit.clock, WEEK, WEEK, kit.logger,
                kit.pushes::add, kit.events::add);
        kit.clock.advance(Duration.ofHours(24));
        equal.decideDue();
        EstateRecord decided = now(record);
        assertEquals(EstateState.DECIDED, decided.state);
        assertFalse(decided.guildlessHeld);
    }

    @Test
    void aPostponedEstateThatTurnsOutGuildlessLosesItsPostponeReason() {
        EstateRecord record = kit.died(aria, null);
        kit.world.defaultDeathGuild = DeathGuild.UNAVAILABLE;
        kit.clock.advance(Duration.ofHours(24));
        kit.coordinator.decideDue();
        assertNotNull(now(record).postponeReason);
        kit.world.defaultDeathGuild = DeathGuild.NONE;
        kit.clock.advance(Duration.ofMinutes(1));
        kit.coordinator.decideDue();
        EstateRecord held = now(record);
        assertEquals(EstateState.OPEN, held.state);
        assertNull(held.postponedSince);
        assertNull(held.postponeReason);
    }

    @Test
    void recordsWrittenBeforeTheMarkerReadAsNotHeld() throws Exception {
        EstateRecord record = kit.died(aria, null);
        String old = EstateJson.write(record).replace("  \"guildlessHeld\": false,\n", "");
        assertFalse(old.contains("guildlessHeld"));
        assertFalse(EstateJson.read(old).guildlessHeld);
    }
}
