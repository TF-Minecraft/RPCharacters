package net.tfminecraft.rpcharacters.chat;

public final class ChatFormatUtil {

	private ChatFormatUtil() {}

	/**
	 * Extracts the color/format codes immediately preceding {@code {message}} in a channel format string.
	 * Falls back to {@code &7} when no message token or no codes are found.
	 */
	public static String extractMessageColorPrefix(String format) {
		if (format == null || format.isEmpty()) {
			return "&7";
		}

		int messageToken = format.indexOf("{message}");
		if (messageToken < 0) {
			return "&7";
		}

		String before = format.substring(0, messageToken);
		StringBuilder codes = new StringBuilder();
		int index = before.length();

		while (index > 0) {
			if (index >= 9 && before.regionMatches(index - 9, "&#", 0, 2)) {
				String hex = before.substring(index - 9, index);
				if (hex.matches("&#[0-9A-Fa-f]{6}")) {
					codes.insert(0, hex);
					index -= 9;
					continue;
				}
			}
			if (index >= 7 && before.charAt(index - 7) == '#') {
				String hex = before.substring(index - 7, index);
				if (hex.matches("#[0-9A-Fa-f]{6}")) {
					codes.insert(0, hex);
					index -= 7;
					continue;
				}
			}
			if (index >= 2 && before.charAt(index - 2) == '&') {
				char code = before.charAt(index - 1);
				if (isLegacyColorCode(code)) {
					codes.insert(0, "&" + code);
					index -= 2;
					continue;
				}
			}
			break;
		}

		return codes.length() > 0 ? codes.toString() : "&7";
	}

	private static boolean isLegacyColorCode(char code) {
		char lower = Character.toLowerCase(code);
		return (lower >= '0' && lower <= '9')
				|| (lower >= 'a' && lower <= 'f')
				|| lower == 'k' || lower == 'l' || lower == 'm' || lower == 'n' || lower == 'o' || lower == 'r';
	}
}
