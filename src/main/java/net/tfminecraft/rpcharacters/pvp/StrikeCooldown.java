package net.tfminecraft.rpcharacters.pvp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How long one player must wait before striking the same character again.
 * A cooldown of 0 is off. Each killer has their own clock on that character.
 */
public final class StrikeCooldown {

	private StrikeCooldown() {
	}

	public static boolean blocks(Map<UUID, Long> strikesByKiller, UUID killerId, long nowMs, long cooldownMs) {
		if (killerId == null || strikesByKiller == null) {
			return false;
		}
		return blocks(strikesByKiller.get(killerId), nowMs, cooldownMs);
	}

	public static boolean blocks(Long struckAtMs, long nowMs, long cooldownMs) {
		if (cooldownMs <= 0L || struckAtMs == null || struckAtMs <= 0L) {
			return false;
		}
		return nowMs - struckAtMs < cooldownMs;
	}

	public static long remainingMs(Map<UUID, Long> strikesByKiller, UUID killerId, long nowMs, long cooldownMs) {
		if (killerId == null || strikesByKiller == null) {
			return 0L;
		}
		Long struckAtMs = strikesByKiller.get(killerId);
		if (struckAtMs == null) {
			return 0L;
		}
		return remainingMs(struckAtMs, nowMs, cooldownMs);
	}

	public static long remainingMs(long struckAtMs, long nowMs, long cooldownMs) {
		if (cooldownMs <= 0L) {
			return 0L;
		}
		return Math.max(0L, struckAtMs + cooldownMs - nowMs);
	}

	/**
	 * Remembers {@code killerId} at {@code atMs} and drops strikes older than the cooldown.
	 * A cooldown of 0 leaves the map as it was and does not record a new strike.
	 */
	public static Map<UUID, Long> record(Map<UUID, Long> existing, UUID killerId, long atMs, long cooldownMs) {
		Map<UUID, Long> next = new LinkedHashMap<>();
		if (existing != null) {
			for (Map.Entry<UUID, Long> entry : existing.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
					continue;
				}
				if (cooldownMs <= 0L || atMs - entry.getValue() < cooldownMs) {
					next.put(entry.getKey(), entry.getValue());
				}
			}
		}
		if (cooldownMs > 0L && killerId != null && atMs > 0L) {
			next.put(killerId, atMs);
		}
		return next;
	}

	/** Rounded up to the next minute, so a strike that still blocks never reads as ready. */
	public static String formatRemaining(long remainingMs) {
		long minutes = (Math.max(0L, remainingMs) + 59_999L) / 60_000L;
		if (minutes < 1L) {
			minutes = 1L;
		}
		long hours = minutes / 60L;
		long mins = minutes % 60L;
		if (hours <= 0L) {
			return mins + "m";
		}
		if (mins == 0L) {
			return hours + "h";
		}
		return hours + "h " + mins + "m";
	}
}
