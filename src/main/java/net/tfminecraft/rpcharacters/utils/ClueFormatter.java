package net.tfminecraft.rpcharacters.utils;

import java.util.List;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.display.TextWrapUtil;

public final class ClueFormatter {

	private ClueFormatter() {}

	/** Visible characters per lore line — matches typical SimpleFactions / GUI item lore width. */
	public static final int LORE_LINE_LENGTH = 32;

	public static String stripColor(String input) {
		return TextWrapUtil.stripColor(input);
	}

	public static int plainLength(String input) {
		return stripColor(input).length();
	}

	public static String format(String input) {
		String plain = stripColor(input);
		if (plain.isEmpty()) {
			return RPTexts.formatGui(RPTexts.MUTED);
		}
		return RPTexts.formatGui(RPTexts.MUTED + plain);
	}

	public static String validate(String input) {
		String plain = stripColor(input);
		if (plain.length() < Cache.clueMinLength) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Clue must be at least " + Cache.clueMinLength + " characters.");
		}
		if (plain.length() > Cache.clueMaxLength) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Clue cannot exceed " + Cache.clueMaxLength + " characters.");
		}
		return null;
	}

	public static String lengthRangeMessage() {
		return RPTexts.formatDisplay(RPTexts.ERROR + "Clue must be between " + Cache.clueMinLength + " and "
				+ Cache.clueMaxLength + " characters.");
	}

	public static List<String> wrapLore(String formattedClue) {
		return wrapLore(formattedClue, LORE_LINE_LENGTH);
	}

	public static List<String> wrapLore(String formattedClue, int maxLineLength) {
		return TextWrapUtil.wrapLines(formattedClue, maxLineLength, RPTexts.MUTED);
	}
}
