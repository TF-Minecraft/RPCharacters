package net.tfminecraft.rpcharacters.party;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class PartyResult {

	public enum Kind {
		OK,
		FAIL,
		DISBANDED,
		MEMBER_LEFT,
		MEMBER_KICKED,
		MEMBER_JOINED
	}

	private final Kind kind;
	private final String message;
	private final List<UUID> notifyIds;

	private PartyResult(Kind kind, String message, List<UUID> notifyIds) {
		this.kind = kind;
		this.message = message;
		this.notifyIds = notifyIds != null ? List.copyOf(notifyIds) : List.of();
	}

	public static PartyResult ok(String message) {
		return new PartyResult(Kind.OK, message, List.of());
	}

	public static PartyResult fail(String message) {
		return new PartyResult(Kind.FAIL, message, List.of());
	}

	public static PartyResult withNotify(Kind kind, String message, List<UUID> notifyIds) {
		return new PartyResult(kind, message, notifyIds);
	}

	public boolean ok() {
		return kind != Kind.FAIL;
	}

	public Kind kind() {
		return kind;
	}

	public String message() {
		return message;
	}

	public List<UUID> notifyIds() {
		return notifyIds;
	}

	public List<UUID> notifyIdsOrEmpty() {
		return Collections.unmodifiableList(notifyIds);
	}
}
