package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@code /pvp start}. The issuer can close it with {@code /pvp end}.
 * Otherwise it stays open until the timeout. Players who die before it closes
 * do not see the ended title.
 */
public final class PvpSituation {

	private final UUID issuerId;
	private final Set<UUID> participants;
	private final Set<UUID> died = ConcurrentHashMap.newKeySet();
	private boolean open = true;
	private boolean started;

	public PvpSituation(UUID issuerId, Collection<UUID> participants) {
		this.issuerId = issuerId;
		Set<UUID> ids = ConcurrentHashMap.newKeySet();
		if (participants != null) {
			for (UUID participant : participants) {
				if (participant != null) {
					ids.add(participant);
				}
			}
		}
		this.participants = Set.copyOf(ids);
	}

	public UUID issuerId() {
		return issuerId;
	}

	public Set<UUID> participants() {
		return participants;
	}

	public boolean isOpen() {
		return open;
	}

	public boolean hasStarted() {
		return started;
	}

	public boolean includes(UUID playerId) {
		return playerId != null && participants.contains(playerId);
	}

	public boolean hasDied(UUID playerId) {
		return playerId != null && died.contains(playerId);
	}

	public void markStarted() {
		if (open) {
			started = true;
		}
	}

	public void markDeath(UUID playerId) {
		if (open && includes(playerId)) {
			died.add(playerId);
		}
	}

	/**
	 * Close this situation. After the countdown has finished, the result is every
	 * participant who has not died. Closing a countdown that has not finished, or
	 * closing twice, returns nobody.
	 */
	public List<UUID> close() {
		if (!open) {
			return List.of();
		}
		open = false;
		if (!started) {
			return List.of();
		}
		List<UUID> recipients = new ArrayList<>();
		for (UUID participant : participants) {
			if (!died.contains(participant)) {
				recipients.add(participant);
			}
		}
		return List.copyOf(recipients);
	}
}
