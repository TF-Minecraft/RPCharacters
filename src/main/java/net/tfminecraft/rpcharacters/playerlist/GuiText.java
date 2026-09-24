package net.tfminecraft.rpcharacters.playerlist;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Legacy text helpers for dialog layouts. Widths approximate the default
 * Minecraft font in GUI pixels (glyph advance including its 1px gap).
 */
public final class GuiText {

	private static final char SECTION = '§';
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
			.character(SECTION).hexColors().useUnusualXRepeatedCharacterHexFormat().build();

	private GuiText() {}

	/** Converts a section-coded legacy string (including §x hex) to a component. */
	public static Component component(String legacy) {
		return LEGACY.deserialize(legacy == null ? "" : legacy);
	}

	public static int charWidth(char c) {
		return switch (c) {
			case 'i', '!', ',', '.', ':', ';', '|', '\'' -> 2;
			case 'l', '`' -> 3;
			case 't', 'I', '[', ']', ' ' -> 4;
			case 'f', 'k', '<', '>', '"', '(', ')', '*', '{', '}' -> 5;
			case '@', '~' -> 7;
			default -> 6;
		};
	}

	/** Width of a section-coded legacy string; bold characters are 1px wider. */
	public static int width(String legacy) {
		if (legacy == null) {
			return 0;
		}
		int width = 0;
		boolean bold = false;
		for (int i = 0; i < legacy.length(); i++) {
			char c = legacy.charAt(i);
			if (c == SECTION && i + 1 < legacy.length()) {
				char code = Character.toLowerCase(legacy.charAt(++i));
				if (code == 'l') {
					bold = true;
				} else if (code == 'r' || code == 'x' || isColour(code)) {
					bold = false;
				}
				continue;
			}
			width += charWidth(c) + (bold ? 1 : 0);
		}
		return width;
	}

	private static boolean isColour(char code) {
		return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
	}

	/** Strips section codes, keeping only visible text. */
	public static String plain(String legacy) {
		if (legacy == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(legacy.length());
		for (int i = 0; i < legacy.length(); i++) {
			char c = legacy.charAt(i);
			if (c == SECTION && i + 1 < legacy.length()) {
				i++;
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	/**
	 * Pads a line to {@code target} pixels with 4px spaces and 5px bold spaces,
	 * so centred dialog lines of equal width share a left edge.
	 */
	public static Component padded(String legacy, int target) {
		int gap = Math.max(0, target - width(legacy));
		int spaces = gap / 4;
		int bold = Math.min(spaces, gap % 4);
		return Component.text()
				.append(component(legacy))
				.append(Component.text(" ".repeat(spaces - bold)))
				.append(Component.text(" ".repeat(bold)).decorate(TextDecoration.BOLD))
				.build();
	}

	/** Pixel width that {@link #padded} produces for a line, for tests and layout checks. */
	public static int paddedWidth(String legacy, int target) {
		int gap = Math.max(0, target - width(legacy));
		int spaces = gap / 4;
		int bold = Math.min(spaces, gap % 4);
		return width(legacy) + (spaces - bold) * 4 + bold * 5;
	}

	/** Word-wraps a line to {@code maxWidth}, carrying its leading colour codes onto each line. */
	public static List<String> wrap(String legacy, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (legacy == null || legacy.isEmpty()) {
			lines.add("");
			return lines;
		}
		if (width(legacy) <= maxWidth) {
			lines.add(legacy);
			return lines;
		}
		int codes = 0;
		while (codes + 1 < legacy.length() && legacy.charAt(codes) == SECTION) {
			codes += 2;
		}
		String prefix = legacy.substring(0, codes);
		StringBuilder current = new StringBuilder();
		for (String word : plain(legacy).split(" ")) {
			String candidate = current.length() == 0 ? word : current + " " + word;
			if (width(candidate) > maxWidth && current.length() > 0) {
				lines.add(prefix + current);
				current = new StringBuilder(word);
			} else {
				current = new StringBuilder(candidate);
			}
		}
		if (current.length() > 0) {
			lines.add(prefix + current);
		}
		return lines;
	}
}
