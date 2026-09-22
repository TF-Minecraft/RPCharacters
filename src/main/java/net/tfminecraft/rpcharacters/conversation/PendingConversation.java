package net.tfminecraft.rpcharacters.conversation;

import java.util.UUID;

public class PendingConversation {

	private final String speakerCharacterId;
	private final String listenerCharacterId;
	private final UUID speakerPlayerId;
	private final long expiresAtMs;

	public PendingConversation(String speakerCharacterId, String listenerCharacterId, UUID speakerPlayerId,
			long expiresAtMs) {
		this.speakerCharacterId = speakerCharacterId;
		this.listenerCharacterId = listenerCharacterId;
		this.speakerPlayerId = speakerPlayerId;
		this.expiresAtMs = expiresAtMs;
	}

	public String getSpeakerCharacterId() {
		return speakerCharacterId;
	}

	public String getListenerCharacterId() {
		return listenerCharacterId;
	}

	public UUID getSpeakerPlayerId() {
		return speakerPlayerId;
	}

	public long getExpiresAtMs() {
		return expiresAtMs;
	}

	public boolean isExpired(long nowMs) {
		return nowMs > expiresAtMs;
	}

	public static String key(String listenerCharacterId, String speakerCharacterId) {
		return listenerCharacterId + ":" + speakerCharacterId;
	}
}
