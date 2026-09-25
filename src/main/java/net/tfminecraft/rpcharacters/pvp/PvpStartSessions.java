package net.tfminecraft.rpcharacters.pvp;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players who were online when a {@code /pvp start} countdown finished.
 * The mark lasts until it expires, they die, or they log out.
 */
public final class PvpStartSessions {

	private static final Map<UUID, Long> activeUntilMs = new ConcurrentHashMap<>();

	private PvpStartSessions() {
	}

	public static void begin(Collection<UUID> players, long nowMs, long durationMs) {
		if (players == null || durationMs <= 0L) {
			return;
		}
		long until = nowMs + durationMs;
		for (UUID playerId : players) {
			if (playerId == null) {
				continue;
			}
			activeUntilMs.merge(playerId, until, Math::max);
		}
	}

	public static boolean isActive(UUID playerId, long nowMs) {
		if (playerId == null) {
			return false;
		}
		Long until = activeUntilMs.get(playerId);
		if (until == null) {
			return false;
		}
		if (nowMs >= until) {
			activeUntilMs.remove(playerId, until);
			return false;
		}
		return true;
	}

	public static void end(UUID playerId) {
		if (playerId != null) {
			activeUntilMs.remove(playerId);
		}
	}

	static void clear() {
		activeUntilMs.clear();
	}
}
