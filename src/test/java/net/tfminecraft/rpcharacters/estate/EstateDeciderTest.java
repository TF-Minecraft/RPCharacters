package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.estate.EstateDecider.Recheck;
import net.tfminecraft.rpcharacters.estate.EstateDecider.Verdict;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Decision;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.Recipient;

class EstateDeciderTest {
    @TempDir Path root;

    private EstateTestKit kit;
    private CharacterRef testator;
    private CharacterRef heir;
    private CharacterRef leader;

    private EstateRecord estate(UUID willHeir) {
        kit = kit == null ? new EstateTestKit(root) : kit;
        UUID death = kit.world.kill(testator);
        EstateRecord record = new EstateRecord(death, testator);
        record.name = "Testator";
        record.willHeir = willHeir;
        return record;
    }

    private Decision decide(EstateRecord record) {
        Verdict verdict = EstateDecider.decide(record, kit.world, EstateTestKit.T0);
        return assertInstanceOf(Verdict.Decided.class, verdict).decision();
    }

    private void setUp() {
        kit = new EstateTestKit(root);
        testator = kit.world.alive("Testator");
        heir = kit.world.alive("Kira");
        leader = kit.world.alive("Leader");
    }

    private void guild(boolean live, CharacterRef guildLeader, UUID realm) {
        UUID incarnation = live ? kit.world.liveGuild("Ironhand", guildLeader, realm) : UUID.randomUUID();
        kit.world.deathGuilds.put(testator.characterId(), DeathGuild.guild(incarnation, live,
                Optional.ofNullable(guildLeader).map(CharacterRef::characterId), Optional.ofNullable(realm)));
    }

    private static void assertPart(Part part, String shown, String label) {
        assertEquals(shown, part.shown());
        assertEquals(label, part.label());
    }

    @Test
    void heirWithLiveLedGuildSplitsGuildLocksToTheLeader() {
        setUp();
        guild(true, leader, null);
        Decision d = decide(estate(heir.characterId()));
        assertEquals(Recipient.account("char:" + heir.characterId() + ":bank"), d.money().recipient());
        assertEquals(Recipient.character(heir), d.property().recipient());
        assertEquals(Recipient.character(leader), d.guildLocks().recipient());
        assertEquals(Recipient.character(heir), d.letters().recipient());
        assertEquals("Kira", d.heir().name());
        assertEquals(0, d.revision());
    }

    @Test
    void heirWithLeaderlessGuildWaitsOnlyForGuildLocks() {
        setUp();
        guild(true, null, null);
        Decision d = decide(estate(heir.characterId()));
        assertTrue(d.guildLocks().recipient().isWaiting());
        assertEquals(Recipient.character(heir), d.property().recipient());
        assertTrue(d.anyWaiting());
    }

    @Test
    void heirTakesGuildLocksWhenTheGuildIsGoneOrThereIsNone() {
        setUp();
        guild(false, null, null);
        assertEquals(Recipient.character(heir), decide(estate(heir.characterId())).guildLocks().recipient());
        setUp();
        Decision d = decide(estate(heir.characterId()));
        assertEquals(Recipient.character(heir), d.guildLocks().recipient());
        assertNull(d.deathGuild());
    }

    @Test
    void noHeirSendsMoneyToTheGuildAndEverythingElseToItsLeader() {
        setUp();
        guild(true, leader, null);
        Decision d = decide(estate(null));
        assertTrue(d.money().recipient().account().orElseThrow().startsWith("guild:"));
        assertPart(d.money(), "GUILD", "Ironhand");
        assertEquals(Recipient.character(leader), d.property().recipient());
        assertEquals(Recipient.character(leader), d.guildLocks().recipient());
        assertPart(d.letters(), "SENDER", null);
    }

    @Test
    void noHeirAndNoLeaderWaitsForProperty() {
        setUp();
        guild(true, null, null);
        Decision d = decide(estate(null));
        assertPart(d.money(), "GUILD", "Ironhand");
        assertTrue(d.property().recipient().isWaiting());
        assertTrue(d.guildLocks().recipient().isWaiting());
    }

    @Test
    void goneGuildSendsMoneyToALiveRealmAndFreezesProperty() {
        setUp();
        UUID realm = kit.world.liveGuild("Capital", leader, null);
        guild(false, null, realm);
        Decision d = decide(estate(null));
        assertEquals(Recipient.account("guild:" + realm), d.money().recipient());
        assertPart(d.money(), "REALM", "Capital");
        assertTrue(d.property().recipient().isFrozen());
        assertTrue(d.guildLocks().recipient().isFrozen());
        assertEquals(realm, d.deathRealm());
    }

    @Test
    void goneGuildAndRealmOrNoRealmFreezeEverything() {
        setUp();
        guild(false, null, UUID.randomUUID());
        Decision gone = decide(estate(null));
        assertTrue(gone.money().recipient().isFrozen() && gone.property().recipient().isFrozen());
        setUp();
        guild(false, null, null);
        assertTrue(decide(estate(null)).money().recipient().isFrozen());
    }

    @Test
    void guildlessWithoutHeirFreezesEverythingAndReturnsLetters() {
        setUp();
        Decision d = decide(estate(null));
        assertTrue(d.money().recipient().isFrozen());
        assertTrue(d.property().recipient().isFrozen());
        assertTrue(d.guildLocks().recipient().isFrozen());
        assertTrue(d.anyFrozen());
        assertEquals(Recipient.SENDER, d.letters().recipient());
    }

    @Test
    void deadOrUnknownHeirCountsAsNoHeirAndADeadLeaderAsNoLeader() {
        setUp();
        kit.world.status(heir, CharacterStatus.DEAD);
        kit.world.status(leader, CharacterStatus.DEAD);
        guild(true, leader, null);
        Decision d = decide(estate(heir.characterId()));
        assertNull(d.heir());
        assertTrue(d.property().recipient().isWaiting());
        setUp();
        assertNull(decide(estate(UUID.randomUUID())).heir());
        setUp();
        assertNull(decide(estate(testator.characterId())).heir());
    }

    @Test
    void theTestatorCannotBeTheLeaderWhoInherits() {
        setUp();
        guild(true, testator, null);
        assertTrue(decide(estate(null)).property().recipient().isWaiting());
    }

    @Test
    void unreadableTestatorHeirOrSimpleFactionsPostpone() {
        setUp();
        EstateRecord record = estate(heir.characterId());
        kit.world.unreadable.add(heir.characterId());
        assertEquals("the will heir's account cannot be read",
                assertInstanceOf(Verdict.Postpone.class, EstateDecider.decide(record, kit.world, EstateTestKit.T0)).reason());
        kit.world.unreadable.add(testator.characterId());
        assertEquals("the testator's account cannot be read",
                assertInstanceOf(Verdict.Postpone.class, EstateDecider.decide(record, kit.world, EstateTestKit.T0)).reason());
        setUp();
        EstateRecord waiting = estate(null);
        kit.world.deathGuilds.put(testator.characterId(), DeathGuild.UNAVAILABLE);
        assertInstanceOf(Verdict.Postpone.class, EstateDecider.decide(waiting, kit.world, EstateTestKit.T0));
        waiting.noGuild = true;
        assertTrue(decide(waiting).money().recipient().isFrozen());
        setUp();
        UUID realm = UUID.randomUUID();
        kit.world.guilds.put(realm, GuildLookup.UNAVAILABLE);
        guild(false, null, realm);
        assertEquals("SimpleFactions cannot find the realm", assertInstanceOf(Verdict.Postpone.class,
                EstateDecider.decide(estate(null), kit.world, EstateTestKit.T0)).reason());
    }

    @Test
    void aTestatorWhoIsNoLongerDeadWithThisDeathCancels() {
        setUp();
        EstateRecord record = estate(null);
        kit.world.deaths.put(testator.characterId(), UUID.randomUUID());
        assertInstanceOf(Verdict.Cancel.class, EstateDecider.decide(record, kit.world, EstateTestKit.T0));
        kit.world.deaths.put(testator.characterId(), record.deathId);
        kit.world.status(testator, CharacterStatus.ALIVE);
        assertInstanceOf(Verdict.Cancel.class, EstateDecider.decide(record, kit.world, EstateTestKit.T0));
        kit.world.infos.remove(testator.characterId());
        assertInstanceOf(Verdict.Cancel.class, EstateDecider.decide(record, kit.world, EstateTestKit.T0));
    }

    @Test
    void waitingPartsGoToANewLeaderOrFreezeWhenTheGuildIsGone() {
        setUp();
        guild(true, null, null);
        EstateRecord record = estate(null);
        record.decision = decide(record);
        UUID g = record.decision.deathGuild();
        kit.world.guilds.put(g, GuildLookup.UNAVAILABLE);
        assertInstanceOf(Recheck.Unchanged.class, EstateDecider.recheck(record, kit.world));
        kit.world.guilds.remove(g);
        kit.world.liveGuild("Ironhand", null, null);
        kit.world.guilds.put(g, kit.world.guildNames.get("Ironhand"));
        assertEquals("the death guild has no living leader",
                assertInstanceOf(Recheck.Unchanged.class, EstateDecider.recheck(record, kit.world)).reason());
        kit.world.guilds.put(g, GuildLookup.live(new net.tfminecraft.tlibs.character.estate.GuildRef(g, "Ironhand",
                Optional.empty(), Optional.of(leader.characterId()), "guild:" + g)));
        Decision led = assertInstanceOf(Recheck.Revised.class, EstateDecider.recheck(record, kit.world)).decision();
        assertEquals(1, led.revision());
        assertEquals(Recipient.character(leader), led.property().recipient());
        assertEquals(Recipient.character(leader), led.guildLocks().recipient());
        kit.world.guilds.put(g, GuildLookup.GONE);
        Decision gone = assertInstanceOf(Recheck.Revised.class, EstateDecider.recheck(record, kit.world)).decision();
        assertTrue(gone.property().recipient().isFrozen());
        assertTrue(gone.guildLocks().recipient().isFrozen());
    }

    @Test
    void aWaitingGuildLockGoesToTheWillHeirOnceTheGuildIsGone() {
        setUp();
        guild(true, null, null);
        EstateRecord record = estate(heir.characterId());
        record.decision = decide(record);
        kit.world.guilds.put(record.decision.deathGuild(), GuildLookup.GONE);
        Decision d = assertInstanceOf(Recheck.Revised.class, EstateDecider.recheck(record, kit.world)).decision();
        assertEquals(Recipient.character(heir), d.guildLocks().recipient());
        assertEquals(Recipient.character(heir), d.property().recipient());
    }
}
