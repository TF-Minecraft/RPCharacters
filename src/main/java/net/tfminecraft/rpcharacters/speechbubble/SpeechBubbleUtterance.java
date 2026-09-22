package net.tfminecraft.rpcharacters.speechbubble;

import java.util.List;
import java.util.UUID;

public final class SpeechBubbleUtterance {

	private final UUID id;
	private final List<String> lines;
	private final long expiresAt;

	public SpeechBubbleUtterance(List<String> lines, long expiresAt) {
		this.id = UUID.randomUUID();
		this.lines = List.copyOf(lines);
		this.expiresAt = expiresAt;
	}

	public UUID getId() {
		return id;
	}

	public List<String> getLines() {
		return lines;
	}

	public long getExpiresAt() {
		return expiresAt;
	}

	public boolean isExpired(long now) {
		return now >= expiresAt;
	}
}
