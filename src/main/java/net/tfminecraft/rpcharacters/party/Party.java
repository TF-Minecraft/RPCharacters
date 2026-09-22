package net.tfminecraft.rpcharacters.party;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class Party {

	private final UUID id;
	private final String name;
	private final UUID leaderId;
	private final Set<UUID> memberIds = new LinkedHashSet<>();

	Party(UUID id, String name, UUID leaderId) {
		this.id = id;
		this.name = name;
		this.leaderId = leaderId;
		this.memberIds.add(leaderId);
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public UUID getLeaderId() {
		return leaderId;
	}

	public Set<UUID> getMemberIds() {
		return Collections.unmodifiableSet(memberIds);
	}

	boolean isLeader(UUID memberId) {
		return leaderId.equals(memberId);
	}

	boolean isMember(UUID memberId) {
		return memberIds.contains(memberId);
	}

	void addMember(UUID memberId) {
		memberIds.add(memberId);
	}

	void removeMember(UUID memberId) {
		memberIds.remove(memberId);
	}
}
