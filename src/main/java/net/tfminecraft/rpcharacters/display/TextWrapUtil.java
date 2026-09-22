package net.tfminecraft.rpcharacters.display;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;

public final class TextWrapUtil {

	private TextWrapUtil() {}

	public static String stripColor(String input) {
		if (input == null) {
			return "";
		}
		return ChatColor.stripColor(input).trim();
	}

	/**
	 * Splits text into lines at word boundaries without exceeding {@code maxLineLength} visible characters.
	 * Each line is prefixed with {@code colorPrefix}.
	 */
	public static List<String> wrapLines(String text, int maxLineLength, String colorPrefix) {
		List<String> lines = new ArrayList<>();
		String prefix = colorPrefix != null ? colorPrefix : "";
		String plain = stripColor(text);
		if (plain.isEmpty()) {
			lines.add(prefix.isEmpty() ? "§7" : prefix);
			return lines;
		}

		int lineLength = Math.max(1, maxLineLength);
		String[] words = plain.split("\\s+");
		StringBuilder current = new StringBuilder();

		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (current.length() == 0) {
				current.append(word);
				continue;
			}
			if (current.length() + 1 + word.length() <= lineLength) {
				current.append(' ').append(word);
			} else {
				lines.add(prefix + current);
				current = new StringBuilder(word);
			}
		}
		if (current.length() > 0) {
			lines.add(prefix + current);
		}
		return lines;
	}
}
