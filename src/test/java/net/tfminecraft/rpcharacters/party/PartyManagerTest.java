package net.tfminecraft.rpcharacters.party;

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

	private UUID leader;
	private UUID member;
	private UUID outsider;

	@BeforeEach
	void setUp() {
		PartyManager.clearForTests();
		leader = UUID.randomUUID();
		member = UUID.randomUUID();
		outsider = UUID.randomUUID();
	}

	@AfterEach
	void tearDown() {
		PartyManager.clearForTests();
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
}
