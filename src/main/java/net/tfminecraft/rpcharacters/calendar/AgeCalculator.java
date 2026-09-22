package net.tfminecraft.rpcharacters.calendar;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import net.tfminecraft.rpcharacters.Cache;

public final class AgeCalculator {

	private AgeCalculator() {}

	public static int computeAgeYears(String birthdayIso, LocalDate now) {
		LocalDate birthday = FantasyCalendar.fromIso(birthdayIso);
		if (birthday == null || now == null) {
			return 0;
		}
		int years = Period.between(birthday, now).getYears();
		return Math.max(0, years);
	}

	public static String formatAge(String birthdayIso) {
		if (birthdayIso == null || birthdayIso.isBlank()) {
			return Cache.calendarAgeUnsetLabel;
		}
		int age = computeAgeYears(birthdayIso, FantasyCalendar.getCurrentDate());
		return Integer.toString(age);
	}

	public static String birthdayFromAge(int ageYears, LocalDate now) {
		return birthdayFromAge(ageYears, now, null);
	}

	/**
	 * Pick a birthday that yields {@code ageYears} on {@code now}.
	 * When {@code salt} is non-blank, the day offset is deterministic for
	 * (salt, age) so the same age always maps to the same birthday.
	 */
	public static String birthdayFromAge(int ageYears, LocalDate now, String salt) {
		if (ageYears < 0) {
			return null;
		}
		if (now == null) {
			now = FantasyCalendar.getCurrentDate();
		}
		LocalDate earliest = now.minusYears(ageYears + 1L).plusDays(1);
		LocalDate latest = now.minusYears(ageYears);
		long daySpan = ChronoUnit.DAYS.between(earliest, latest);
		if (daySpan < 0) {
			return FantasyCalendar.toIso(latest);
		}
		int offset;
		if (daySpan == 0) {
			offset = 0;
		} else if (salt == null || salt.isBlank()) {
			offset = ThreadLocalRandom.current().nextInt((int) daySpan + 1);
		} else {
			offset = saltedOffset(salt, ageYears, (int) daySpan);
		}
		LocalDate birthday = earliest.plusDays(offset);
		return FantasyCalendar.toIso(birthday);
	}

	/** FNV-1a 32-bit; must match ProvinceSystem fantasyCalendar.hashSaltU32. */
	static int saltedOffset(String salt, int ageYears, int daySpan) {
		int h = fnv1a32(salt + ":" + ageYears);
		return Integer.remainderUnsigned(h, daySpan + 1);
	}

	static int fnv1a32(String input) {
		int h = 0x811c9dc5;
		for (int i = 0; i < input.length(); i++) {
			h ^= input.charAt(i);
			h *= 0x01000193;
		}
		return h;
	}
}
