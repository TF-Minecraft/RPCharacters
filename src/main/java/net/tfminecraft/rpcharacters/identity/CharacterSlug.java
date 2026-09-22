package net.tfminecraft.rpcharacters.identity;

import java.util.Locale;

import net.tfminecraft.rpcharacters.utils.ClueFormatter;

public final class CharacterSlug {

	private CharacterSlug() {}

	public static String fromDisplayName(String name) {
		if (name == null || name.isBlank()) {
			return "character";
		}
		String plain = ClueFormatter.stripColor(name).trim().toLowerCase(Locale.ROOT);
		plain = plain.replace(' ', '_');
		plain = plain.replaceAll("_+", "_");
		plain = plain.replaceAll("[^a-z0-9_]", "");
		plain = plain.replaceAll("^_+|_+$", "");
		if (plain.isEmpty()) {
			return "character";
		}
		return plain;
	}
}
