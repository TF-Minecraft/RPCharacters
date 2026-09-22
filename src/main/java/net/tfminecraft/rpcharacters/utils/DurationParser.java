package net.tfminecraft.rpcharacters.utils;

public final class DurationParser {

	private static final long MS_PER_HOUR = 3_600_000L;
	private static final long MS_PER_DAY = 24 * MS_PER_HOUR;
	private static final long MS_PER_MONTH = 30 * MS_PER_DAY;
	private static final long EPOCH_THRESHOLD = 1_000_000_000L;

	private DurationParser() {}

	/**
	 * Parses lock durations like {@code 5d}, {@code 24h}, {@code 1mo}.
	 * Returns {@code -1} when unset or invalid (never locks).
	 */
	public static long parseLockTimeMs(String raw) {
		if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("-1")) {
			return -1L;
		}
		String trimmed = raw.trim().toLowerCase();
		if (trimmed.endsWith("mo")) {
			String number = trimmed.substring(0, trimmed.length() - 2).trim();
			Integer months = parsePositiveInt(number);
			return months == null ? -1L : months * MS_PER_MONTH;
		}
		if (trimmed.endsWith("d")) {
			String number = trimmed.substring(0, trimmed.length() - 1).trim();
			Integer days = parsePositiveInt(number);
			return days == null ? -1L : days * MS_PER_DAY;
		}
		if (trimmed.endsWith("h")) {
			String number = trimmed.substring(0, trimmed.length() - 1).trim();
			Integer hours = parsePositiveInt(number);
			return hours == null ? -1L : hours * MS_PER_HOUR;
		}
		return -1L;
	}

	/** Parses short durations like {@code 30s}, {@code 5m}, {@code 1h}. */
	public static long parseShortDurationMs(String raw) {
		if (raw == null || raw.isBlank()) {
			return -1L;
		}
		String trimmed = raw.trim().toLowerCase();
		if (trimmed.endsWith("ms")) {
			String number = trimmed.substring(0, trimmed.length() - 2).trim();
			Integer millis = parsePositiveInt(number);
			return millis == null ? -1L : millis;
		}
		if (trimmed.endsWith("s")) {
			String number = trimmed.substring(0, trimmed.length() - 1).trim();
			Integer seconds = parsePositiveInt(number);
			return seconds == null ? -1L : seconds * 1_000L;
		}
		if (trimmed.endsWith("m")) {
			String number = trimmed.substring(0, trimmed.length() - 1).trim();
			Integer minutes = parsePositiveInt(number);
			return minutes == null ? -1L : minutes * 60_000L;
		}
		if (trimmed.endsWith("h")) {
			String number = trimmed.substring(0, trimmed.length() - 1).trim();
			Integer hours = parsePositiveInt(number);
			return hours == null ? -1L : hours * MS_PER_HOUR;
		}
		Integer seconds = parsePositiveInt(trimmed);
		return seconds == null ? -1L : seconds * 1_000L;
	}

	/**
	 * Resolves persisted creation timestamps from new or legacy JSON values.
	 */
	public static int resolveCreatedAtEpochSeconds(boolean hasCreatedAt, int createdAt, boolean hasLegacy, int legacy,
			java.io.File sourceFile) {
		if (hasCreatedAt) {
			return Math.max(0, createdAt);
		}
		if (hasLegacy && legacy >= EPOCH_THRESHOLD) {
			return legacy;
		}
		if (sourceFile != null && sourceFile.exists()) {
			return (int) Math.max(0L, sourceFile.lastModified() / 1000L);
		}
		return (int) (System.currentTimeMillis() / 1000L);
	}

	private static Integer parsePositiveInt(String number) {
		if (number == null || number.isBlank()) {
			return null;
		}
		try {
			int value = Integer.parseInt(number);
			return value > 0 ? value : null;
		} catch (NumberFormatException ex) {
			return null;
		}
	}
}
