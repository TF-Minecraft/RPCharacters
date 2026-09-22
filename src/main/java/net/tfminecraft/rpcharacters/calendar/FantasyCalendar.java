package net.tfminecraft.rpcharacters.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import net.tfminecraft.rpcharacters.Cache;

public final class FantasyCalendar {

	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

	private FantasyCalendar() {}

	public static LocalDate getCurrentDate() {
		return toFantasyDate(LocalDate.now());
	}

	public static int getCurrentFantasyYear() {
		return toFantasyYear(LocalDate.now().getYear());
	}

	public static int toFantasyYear(int irlYear) {
		return irlYear - Cache.calendarYearOffset;
	}

	public static LocalDate toFantasyDate(LocalDate realDate) {
		if (realDate == null) {
			return null;
		}
		int fantasyYear = toFantasyYear(realDate.getYear());
		return LocalDate.of(fantasyYear, realDate.getMonth(), realDate.getDayOfMonth());
	}

	public static String toIso(LocalDate fantasyDate) {
		if (fantasyDate == null) {
			return null;
		}
		return fantasyDate.format(ISO);
	}

	public static LocalDate fromIso(String iso) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(iso.trim(), ISO);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	/**
	 * Parses fantasy display dates such as {@code 30.10.351} or {@code 30/10/351}.
	 */
	public static LocalDate fromDisplayDate(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String normalized = input.trim().replace(',', '.');
		String[] parts = normalized.split("[./]");
		if (parts.length != 3) {
			return null;
		}
		try {
			int day = Integer.parseInt(parts[0].trim());
			int month = Integer.parseInt(parts[1].trim());
			int year = Integer.parseInt(parts[2].trim());
			return LocalDate.of(year, month, day);
		} catch (NumberFormatException | DateTimeParseException ex) {
			return null;
		}
	}

	public static String parseBirthdayInput(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		LocalDate fromDisplay = fromDisplayDate(input);
		if (fromDisplay != null) {
			return toIso(fromDisplay);
		}
		LocalDate fromIso = fromIso(input);
		return fromIso != null ? toIso(fromIso) : null;
	}

	public static String formatFantasyYear(int fantasyYear) {
		if (Cache.calendarEraSuffix == null || Cache.calendarEraSuffix.isBlank()) {
			return Integer.toString(fantasyYear);
		}
		return fantasyYear + " " + Cache.calendarEraSuffix;
	}

	public static String formatFantasyYear(long timestamp) {
		int irlYear = Instant.ofEpochMilli(timestamp)
				.atZone(ZoneId.systemDefault())
				.getYear();
		return formatFantasyYear(toFantasyYear(irlYear));
	}

	public static String formatFantasyDate(LocalDate date) {
		if (date == null) {
			return Cache.calendarAgeUnsetLabel;
		}
		return String.format("%02d/%02d/%s",
				date.getDayOfMonth(),
				date.getMonthValue(),
				formatFantasyYear(date.getYear()));
	}

	public static String formatBirthday(String iso) {
		if (iso == null || iso.isBlank()) {
			return Cache.calendarAgeUnsetLabel;
		}
		LocalDate birthday = fromIso(iso);
		if (birthday == null) {
			return Cache.calendarAgeUnsetLabel;
		}
		return formatFantasyDate(birthday);
	}

	public static String formatDate(long timestamp) {
		var zonedDateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());
		int day = zonedDateTime.getDayOfMonth();
		int month = zonedDateTime.getMonthValue();
		String fantasyYear = formatFantasyYear(timestamp);
		return String.format("%02d/%02d/%s", day, month, fantasyYear);
	}
}
