package net.tfminecraft.rpcharacters.party;

import java.util.UUID;

final class PartyInvite {

	private final UUID partyId;
	private final UUID leaderId;
	private final UUID targetId;
	private final long expiresAtMillis;

	PartyInvite(UUID partyId, UUID leaderId, UUID targetId, long expiresAtMillis) {
		this.partyId = partyId;
		this.leaderId = leaderId;
		this.targetId = targetId;
		this.expiresAtMillis = expiresAtMillis;
	}

	UUID getPartyId() {
		return partyId;
	}

	UUID getLeaderId() {
		return leaderId;
	}

	UUID getTargetId() {
		return targetId;
	}

	boolean isExpired() {
		return System.currentTimeMillis() > expiresAtMillis;
	}
}
