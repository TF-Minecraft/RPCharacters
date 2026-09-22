package net.tfminecraft.rpcharacters.party;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PartyPersistenceTest {
    @TempDir Path dir;
    @AfterEach void clear() { PartyManager.clearForTests(); }

    private Path start() {
        PartyManager.clearForTests();
        Path file = dir.resolve("parties.json");
        PartyManager.get().load(file);
        return file;
    }

    private void restart(Path file) {
        PartyManager.clearForTests();
        PartyManager.get().load(file);
    }

    @Test void membershipNameAndLeadershipSurviveLogoutAndRestart() {
        Path file = start();
        UUID leader = UUID.randomUUID(), member = UUID.randomUUID();
        var manager = PartyManager.get();
        assertTrue(manager.create(leader, "Robo's friends").ok());
        manager.invite(leader, member);
        manager.join(member);
        UUID partyId = manager.getParty(leader).getId();
        manager.handleQuit(member);
        manager.handleQuit(leader);
        assertSame(manager.getParty(leader), manager.getParty(member));
        restart(file);
        Party party = manager.getParty(member);
        assertNotNull(party);
        assertEquals(partyId, party.getId());
        assertEquals("Robo's friends", party.getName());
        assertEquals(leader, party.getLeaderId());
        assertSame(party, manager.getParty(leader));
        assertFalse(manager.create(member, "Second party").ok());
    }

    @Test void leaveKickAndDisbandStayRemovedAfterRestart() {
        Path file = start();
        UUID leader = UUID.randomUUID(), member = UUID.randomUUID(), kicked = UUID.randomUUID();
        var manager = PartyManager.get();
        manager.create(leader, "Robo");
        manager.invite(leader, member); manager.join(member);
        manager.invite(leader, kicked); manager.join(kicked);
        manager.leave(member); manager.kick(leader, kicked);
        restart(file);
        assertNull(manager.getParty(member)); assertNull(manager.getParty(kicked));
        assertNotNull(manager.getParty(leader));
        manager.leave(leader);
        restart(file);
        assertNull(manager.getParty(leader));
    }

    @Test void pendingInvitesDoNotBecomeMembershipAfterRestart() {
        Path file = start();
        UUID leader = UUID.randomUUID(), invitee = UUID.randomUUID();
        PartyManager.get().create(leader, "Robo");
        PartyManager.get().invite(leader, invitee);
        restart(file);
        assertNull(PartyManager.get().getPendingInvite(invitee));
        assertNull(PartyManager.get().getParty(invitee));
    }

    @Test void invalidFileIsNotSilentlyOverwritten() throws Exception {
        Path file = start();
        Files.writeString(file, "broken json");
        assertThrows(UncheckedIOException.class, () -> PartyManager.get().load(file));
        assertEquals("broken json", Files.readString(file));
    }
}
