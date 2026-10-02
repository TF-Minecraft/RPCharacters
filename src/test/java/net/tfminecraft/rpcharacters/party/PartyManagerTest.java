package net.tfminecraft.rpcharacters.party;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.loaders.PartyLoader;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartyManagerTest {
    @TempDir Path folder;
    private RuntimeTestState state;

	private UUID leader;
	private UUID member;
	private UUID outsider;

	@BeforeEach
	void setUp() {
        state = new RuntimeTestState(PartyLoader.class);
		PartyManager.clearForTests();
		leader = UUID.randomUUID();
		member = UUID.randomUUID();
		outsider = UUID.randomUUID();
	}

	@AfterEach
	void tearDown() {
		PartyManager.clearForTests();
        state.close();
	}

	@Test
	void createAddsLeaderAsMember() {
		PartyResult result = PartyManager.get().create(leader, "Scouts");

		assertTrue(result.ok());
		Party party = PartyManager.get().getParty(leader);
		assertNotNull(party);
		assertEquals("Scouts", party.getName());
		assertTrue(party.isLeader(leader));
		assertTrue(party.isMember(leader));
		assertEquals(1, party.getMemberIds().size());
	}

	@Test
	void inviteAndJoinAddsMember() {
		PartyManager.get().create(leader, "Scouts");

		PartyResult invite = PartyManager.get().invite(leader, member);
		assertTrue(invite.ok());

		PartyResult join = PartyManager.get().join(member);
		assertTrue(join.ok());
		assertEquals(PartyResult.Kind.MEMBER_JOINED, join.kind());

		Party party = PartyManager.get().getParty(member);
		assertNotNull(party);
		assertTrue(party.isMember(member));
		assertEquals(2, party.getMemberIds().size());
	}

	@Test
	void nonLeaderCannotInvite() {
		PartyManager.get().create(leader, "Scouts");
		PartyManager.get().invite(leader, member);
		PartyManager.get().join(member);

		PartyResult invite = PartyManager.get().invite(member, outsider);
		assertFalse(invite.ok());
	}

	@Test
	void leaderLeaveDisbandsParty() {
		PartyManager.get().create(leader, "Scouts");
		PartyManager.get().invite(leader, member);
		PartyManager.get().join(member);

		PartyResult leave = PartyManager.get().leave(leader);
		assertTrue(leave.ok());
		assertEquals(PartyResult.Kind.DISBANDED, leave.kind());
		assertNull(PartyManager.get().getParty(leader));
		assertNull(PartyManager.get().getParty(member));
	}

	@Test
	void memberLeaveKeepsParty() {
		PartyManager.get().create(leader, "Scouts");
		PartyManager.get().invite(leader, member);
		PartyManager.get().join(member);

		PartyResult leave = PartyManager.get().leave(member);
		assertTrue(leave.ok());
		assertEquals(PartyResult.Kind.MEMBER_LEFT, leave.kind());

		Party party = PartyManager.get().getParty(leader);
		assertNotNull(party);
		assertFalse(party.isMember(member));
		assertNull(PartyManager.get().getParty(member));
	}

	@Test
	void leaderQuitKeepsParty() {
		PartyManager.get().create(leader, "Scouts");
		PartyManager.get().invite(leader, member);
		PartyManager.get().join(member);

		PartyManager.get().handleQuit(leader);

		assertNotNull(PartyManager.get().getParty(leader));
		assertNotNull(PartyManager.get().getParty(member));
	}

	@Test
	void kickRemovesMember() {
		PartyManager.get().create(leader, "Scouts");
		PartyManager.get().invite(leader, member);
		PartyManager.get().join(member);

		PartyResult kick = PartyManager.get().kick(leader, member);
		assertTrue(kick.ok());
		assertEquals(PartyResult.Kind.MEMBER_KICKED, kick.kind());
		assertNull(PartyManager.get().getParty(member));
		assertNotNull(PartyManager.get().getParty(leader));
	}

	@Test
	void cannotJoinWithoutInvite() {
		PartyResult join = PartyManager.get().join(member);
		assertFalse(join.ok());
		assertNull(PartyManager.get().getParty(member));
	}

	@Test
	void onePartyPerPlayer() {
		PartyManager.get().create(leader, "Scouts");

		PartyResult second = PartyManager.get().create(leader, "Other");
		assertFalse(second.ok());
	}
    @Test void publicInputAndMembershipGuardsRejectInvalidRequests(){var m=PartyManager.get();assertNull(m.getParty(null));assertNull(m.getPendingInvite(null));assertNull(m.getPendingInvite(member));assertFalse(m.create(null,"Name").ok());assertFalse(m.create(leader,null).ok());assertFalse(m.create(leader," ").ok());assertFalse(m.create(leader,"x".repeat(100)).ok());assertFalse(m.create(leader,"§a").ok());assertFalse(m.invite(null,member).ok());assertFalse(m.invite(leader,null).ok());assertFalse(m.invite(leader,leader).ok());assertFalse(m.invite(leader,member).ok());assertFalse(m.join(null).ok());assertFalse(m.join(member).ok());assertFalse(m.kick(null,member).ok());assertFalse(m.kick(leader,null).ok());assertFalse(m.kick(leader,leader).ok());assertFalse(m.kick(leader,member).ok());assertFalse(m.leave(member).ok());assertTrue(m.buildInfoLines(null).isEmpty());}
    @Test void nullLeaveIsARejectedRequestRatherThanAConcurrentMapCrash(){assertFalse(assertDoesNotThrow(()->PartyManager.get().leave(null)).ok());}
    @Test void duplicateInvitationsAndOtherPartyMembersAreRejected(){var m=PartyManager.get();m.create(leader,"A");assertTrue(m.invite(leader,member).ok());assertFalse(m.invite(leader,member).ok());m.join(member);assertFalse(m.join(member).ok());assertFalse(m.invite(leader,member).ok());m.create(outsider,"B");assertFalse(m.invite(leader,outsider).ok());assertFalse(m.kick(member,outsider).ok());assertFalse(m.kick(leader,outsider).ok());assertSame(m.getParty(leader),m.getParty(member));assertNotSame(m.getParty(leader),m.getParty(outsider));}
    @Test void expiredInvitesCanBeReplacedAndJoinReportsExpiry() throws Exception {var config=new YamlConfiguration();config.set("invite-expiry-seconds",1);var file=folder.resolve("config.yml");config.save(file.toFile());new PartyLoader().load(file.toFile());var m=PartyManager.get();m.create(leader,"A");m.invite(leader,member);m.invite(leader,outsider);var invite=m.getPendingInvite(member);assertEquals(m.getParty(leader).getId(),invite.getPartyId());assertEquals(leader,invite.getLeaderId());assertEquals(member,invite.getTargetId());assertFalse(invite.isExpired());Thread.sleep(1_025);assertTrue(invite.isExpired());assertNull(m.getPendingInvite(member));assertFalse(m.join(outsider).ok());assertNull(m.getPendingInvite(outsider));assertTrue(m.invite(leader,member).ok());assertTrue(m.join(member).ok());}
    @Test void disbandInvalidatesOnlyItsOwnPendingInvitations(){var m=PartyManager.get();UUID otherLeader=UUID.randomUUID(),otherTarget=UUID.randomUUID();m.create(leader,"A");m.create(otherLeader,"B");m.invite(leader,member);m.invite(otherLeader,otherTarget);m.leave(leader);assertNull(m.getPendingInvite(member));assertNotNull(m.getPendingInvite(otherTarget));assertFalse(m.join(member).ok());assertTrue(m.join(otherTarget).ok());}
    @Test void partyNamesStripEveryFormattingSyntaxBeforeLengthValidation(){var result=PartyManager.get().create(leader," &aGreen #abcdefHex §cRed ");assertTrue(result.ok());assertEquals("Green Hex Red",PartyManager.get().getParty(leader).getName());}
    @Test void displayNamesPreferOnlineThenOfflineAndUuidFallback(){assertEquals("Unknown",PartyManager.displayName(null));try(var bukkit=mockStatic(Bukkit.class)){assertEquals(leader.toString(),PartyManager.displayName(leader));bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));var online=mock(Player.class);when(online.getName()).thenReturn("Online");bukkit.when(()->Bukkit.getPlayer(leader)).thenReturn(online);assertEquals("Online",PartyManager.displayName(leader));var offline=mock(OfflinePlayer.class);when(offline.getName()).thenReturn("Offline");bukkit.when(()->Bukkit.getOfflinePlayer(member)).thenReturn(offline);assertEquals("Offline",PartyManager.displayName(member));var unknown=mock(OfflinePlayer.class);bukkit.when(()->Bukkit.getOfflinePlayer(outsider)).thenReturn(unknown);assertEquals(outsider.toString(),PartyManager.displayName(outsider));}}
    @Test void partyResultAndMembershipSnapshotsAreImmutable(){var ids=new ArrayList<>(List.of(leader));var result=PartyResult.withNotify(PartyResult.Kind.OK,null,ids);ids.add(member);assertEquals(List.of(leader),result.notifyIds());assertThrows(UnsupportedOperationException.class,()->result.notifyIds().add(member));assertThrows(UnsupportedOperationException.class,()->result.notifyIdsOrEmpty().clear());assertTrue(PartyResult.withNotify(PartyResult.Kind.OK,"",null).notifyIds().isEmpty());PartyManager.get().create(leader,"A");assertThrows(UnsupportedOperationException.class,()->PartyManager.get().getParty(leader).getMemberIds().clear());}
    Path persistentParty(boolean joined){Path file=folder.resolve("parties.json");var m=PartyManager.get();m.load(file);assertTrue(m.create(leader,"Persisted").ok());if(joined){m.invite(leader,member);m.join(member);}return file;}
    MockedStatic<Files> failWrites(){return mockStatic(Files.class,call->{if(call.getMethod().getName().equals("writeString"))throw new IOException("disk full");return call.callRealMethod();});}
    @Test void failedCreateDoesNotPublishAnUnpersistedParty() throws Exception {var file=persistentParty(false);String original=Files.readString(file);try(var files=failWrites()){assertThrows(UncheckedIOException.class,()->PartyManager.get().create(outsider,"Failed"));assertNull(PartyManager.get().getParty(outsider),"Failed persistence must roll back membership publication");}assertEquals(original,Files.readString(file));try(var files=Files.list(folder)){assertEquals(1,files.filter(p->p.getFileName().toString().endsWith(".json")).count());}}
    @Test void failedJoinPreservesMembershipAndTheRetryableInvitation() throws Exception {var file=persistentParty(false);var m=PartyManager.get();m.invite(leader,member);var invite=m.getPendingInvite(member);var party=m.getParty(leader);String original=Files.readString(file);try(var files=failWrites()){assertThrows(UncheckedIOException.class,()->m.join(member));assertNull(m.getParty(member));assertEquals(Set.of(leader),party.getMemberIds());assertSame(invite,m.getPendingInvite(member));}assertEquals(original,Files.readString(file));}
    @Test void failedLeavePreservesTheExistingPartyMembership() throws Exception {var file=persistentParty(true);var m=PartyManager.get();var party=m.getParty(leader);String original=Files.readString(file);try(var files=failWrites()){assertThrows(UncheckedIOException.class,()->m.leave(member));assertSame(party,m.getParty(member));assertEquals(Set.of(leader,member),party.getMemberIds());}assertEquals(original,Files.readString(file));}
    @Test void failedKickPreservesTheExistingPartyMembership() throws Exception {var file=persistentParty(true);var m=PartyManager.get();var party=m.getParty(leader);String original=Files.readString(file);try(var files=failWrites()){assertThrows(UncheckedIOException.class,()->m.kick(leader,member));assertSame(party,m.getParty(member));assertEquals(Set.of(leader,member),party.getMemberIds());}assertEquals(original,Files.readString(file));}
    @Test void failedDisbandPreservesMembersAndOutstandingInvitations() throws Exception {var file=persistentParty(true);var m=PartyManager.get();var party=m.getParty(leader);m.invite(leader,outsider);var invite=m.getPendingInvite(outsider);String original=Files.readString(file);try(var files=failWrites()){assertThrows(UncheckedIOException.class,()->m.leave(leader));assertSame(party,m.getParty(leader));assertSame(party,m.getParty(member));assertSame(invite,m.getPendingInvite(outsider));}assertEquals(original,Files.readString(file));}
    @Test void corruptReloadPreservesTheCurrentMembershipAndOriginalFile() throws Exception {var file=persistentParty(true);var party=PartyManager.get().getParty(leader);Path corrupt=folder.resolve("corrupt.json");Files.writeString(corrupt,"invalid");assertThrows(UncheckedIOException.class,()->PartyManager.get().load(corrupt));assertSame(party,PartyManager.get().getParty(member));assertEquals("invalid",Files.readString(corrupt));PartyManager.get().leave(member);PartyManager.clearForTests();PartyManager.get().load(file);assertNull(PartyManager.get().getParty(member));}

}
