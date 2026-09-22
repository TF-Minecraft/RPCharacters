package net.tfminecraft.rpcharacters.utils;

public final class TraitStateFormat {

	private static final long MS_PER_MINUTE = 60_000L;
	private static final long MS_PER_HOUR = 3_600_000L;
	private static final long MS_PER_DAY = 24 * MS_PER_HOUR;

	private TraitStateFormat() {
	}

	public static String formatRemaining(long remainingMs) {
		if (remainingMs <= 0L) {
			return "0m";
		}
		long remaining = remainingMs;
		long days = remaining / MS_PER_DAY;
		remaining %= MS_PER_DAY;
		long hours = remaining / MS_PER_HOUR;
		remaining %= MS_PER_HOUR;
		long minutes = (remaining + MS_PER_MINUTE - 1L) / MS_PER_MINUTE;
		if (minutes == 60L) {
			hours++;
			minutes = 0L;
		}
		if (hours == 24L) {
			days++;
			hours = 0L;
		}

		StringBuilder out = new StringBuilder();
		if (days > 0L) {
			out.append(days).append('d');
		}
		if (hours > 0L) {
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(hours).append('h');
		}
		if (minutes > 0L || out.length() == 0) {
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(minutes).append('m');
		}
		return out.toString();
	}

	public static String formatFuel(double current, double capacity) {
		return Math.round(current) + "/" + Math.round(capacity);
	}
}
