package net.tfminecraft.rpcharacters.calendar;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class BirthdayValidator {

	private BirthdayValidator() {}

	public static String validateForCharacter(RPCharacter character, String birthdayIso) {
		if (character == null) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "No character found.");
		}
		if (birthdayIso == null || birthdayIso.isBlank()) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Invalid birthday.");
		}
		Race race = character.getRace();
		if (race == null) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Select a race before setting a birthday.");
		}
		int age = AgeCalculator.computeAgeYears(birthdayIso, FantasyCalendar.getCurrentDate());
		int ageMin = Cache.calendarAgeMinimum;
		int ageMax = race.getAgeMax();
		if (age < ageMin || age > ageMax) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Age must be between " + RPTexts.WARN + ageMin
					+ " " + RPTexts.ERROR + "and " + RPTexts.WARN + ageMax + " " + RPTexts.ERROR + "for your race.");
		}
		return null;
	}
}
