package net.tfminecraft.rpcharacters.persona;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class DescriptionValidator {

	private DescriptionValidator() {}

	public static String validate(String description) {
		if (description == null) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Description cannot be empty.");
		}
		String plain = ClueFormatter.stripColor(description).trim();
		if (plain.isEmpty()) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Description cannot be empty.");
		}
		int length = plain.length();
		if (length < Cache.characterDescriptionMinLength) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Description must be at least "
					+ Cache.characterDescriptionMinLength + " characters.");
		}
		if (length > Cache.characterDescriptionMaxLength) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Description cannot exceed "
					+ Cache.characterDescriptionMaxLength + " characters.");
		}
		return null;
	}
}
