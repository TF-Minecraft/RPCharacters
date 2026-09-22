package net.tfminecraft.rpcharacters.utils;

public final class AgeFormatter {

	private static final long SECONDS_PER_HOUR = 3_600L;
	private static final long SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR;
	private static final long SECONDS_PER_MONTH = 30 * SECONDS_PER_DAY;
	private static final long SECONDS_PER_YEAR = 365 * SECONDS_PER_DAY;

	private AgeFormatter() {}

	public static String formatAge(long seconds) {
		if (seconds <= 0) {
			return "0h";
		}
		long remaining = seconds;
		long years = remaining / SECONDS_PER_YEAR;
		remaining %= SECONDS_PER_YEAR;
		long months = remaining / SECONDS_PER_MONTH;
		remaining %= SECONDS_PER_MONTH;
		long days = remaining / SECONDS_PER_DAY;
		remaining %= SECONDS_PER_DAY;
		long hours = remaining / SECONDS_PER_HOUR;

		StringBuilder out = new StringBuilder();
		appendUnit(out, years, "y");
		appendUnit(out, months, "mo");
		appendUnit(out, days, "d");
		appendUnit(out, hours, "h");
		if (out.length() == 0) {
			return "0h";
		}
		return out.toString().trim();
	}

	public static String formatCountdown(long millis) {
		if (millis <= 0) {
			return "0s";
		}
		long remaining = millis / 1000L;
		long days = remaining / 86_400L;
		remaining %= 86_400L;
		long hours = remaining / 3_600L;
		remaining %= 3_600L;
		long minutes = remaining / 60L;
		long seconds = remaining % 60L;

		StringBuilder out = new StringBuilder();
		appendUnit(out, days, "d");
		appendUnit(out, hours, "h");
		appendUnit(out, minutes, "m");
		appendUnit(out, seconds, "s");
		if (out.length() == 0) {
			return "0s";
		}
		return out.toString().trim();
	}

	private static void appendUnit(StringBuilder out, long value, String suffix) {
		if (value <= 0) {
			return;
		}
		if (out.length() > 0) {
			out.append(' ');
		}
		out.append(value).append(suffix);
	}
}
