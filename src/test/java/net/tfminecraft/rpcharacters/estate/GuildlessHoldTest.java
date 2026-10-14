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
import net.tfminecraft.tlibs.character.estate.Recipient;

/** A death with no living heir and no guild stays open for the guildless wait before it freezes. */
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
}
