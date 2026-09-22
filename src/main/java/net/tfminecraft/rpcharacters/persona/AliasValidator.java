package net.tfminecraft.rpcharacters.persona;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class AliasValidator {

	private AliasValidator() {}

	public static String validate(String alias) {
		return validate(alias, true);
	}

	public static String validate(String alias, boolean enforceMaxLength) {
		if (alias == null) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Alias cannot be empty.");
		}
		String plain = ClueFormatter.stripColor(alias);
		if (plain.isEmpty()) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Alias cannot be empty.");
		}
		String lengthError = validateLength(plain, "Alias", enforceMaxLength);
		if (lengthError != null) {
			return lengthError;
		}
		String allowed = Cache.personaAliasAllowedChars;
		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);
			if (!isAllowed(c, allowed)) {
				return RPTexts.formatDisplay(RPTexts.ERROR + "Alias contains disallowed character: "
						+ RPTexts.WARN + c);
			}
		}
		return null;
	}

	public static String validateCharacterName(String name) {
		if (name == null || name.isBlank()) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Name cannot be empty.");
		}
		String trimmed = name.trim();
		if (containsColourCodes(trimmed)) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Colour codes are not allowed in character names. Use "
					+ RPTexts.COMMAND + "/char namecolour " + RPTexts.ERROR + "for display colour.");
		}
		return validateLength(trimmed, "Name", true);
	}

	private static boolean containsColourCodes(String input) {
		if (input == null) {
			return false;
		}
		String stripped = ClueFormatter.stripColor(input);
		if (!stripped.equals(input)) {
			return true;
		}
		return input.indexOf('&') >= 0 || input.indexOf('§') >= 0
				|| input.matches("(?i).*(#[a-f0-9]{6}).*");
	}

	public static String validateLength(String plain, String label) {
		return validateLength(plain, label, true);
	}

	public static String validateLength(String plain, String label, boolean enforceMaxLength) {
		int length = plain.length();
		if (length < Cache.personaDisplayNameMinLength) {
			return RPTexts.formatDisplay(RPTexts.ERROR + label + " must be at least "
					+ Cache.personaDisplayNameMinLength + " characters.");
		}
		if (enforceMaxLength && length > Cache.personaDisplayNameMaxLength) {
			return RPTexts.formatDisplay(RPTexts.ERROR + label + " cannot exceed "
					+ Cache.personaDisplayNameMaxLength + " characters.");
		}
		return null;
	}

	private static boolean isAllowed(char c, String allowed) {
		if (allowed == null) {
			return false;
		}
		for (int i = 0; i < allowed.length(); i++) {
			char allowedChar = allowed.charAt(i);
			if (Character.isLetter(c) && Character.isLetter(allowedChar)) {
				if (Character.toLowerCase(c) == Character.toLowerCase(allowedChar)) {
					return true;
				}
			} else if (c == allowedChar) {
				return true;
			}
		}
		return false;
	}
}
