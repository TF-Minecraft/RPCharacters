package net.tfminecraft.rpcharacters.persona;

import java.util.Optional;
import java.util.regex.Pattern;

public final class NameColourParser {

	private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9A-Fa-f]{6}$");

	private NameColourParser() {}

	public static Optional<String> parse(String input) {
		if (input == null || input.isBlank()) {
			return Optional.empty();
		}
		String trimmed = input.trim();
		if (trimmed.startsWith("#")) {
			trimmed = trimmed.substring(1);
		}
		if (!HEX_PATTERN.matcher(trimmed).matches()) {
			return Optional.empty();
		}
		return Optional.of("#" + trimmed.toLowerCase());
	}
}
