package net.tfminecraft.rpcharacters.utils;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/**
 * Text colours for RPCharacters. See COLOUR-PALETTE.md at workspace root.
 */
public final class RPTexts {

	// Display (chat, titles, action bar)
	public static final String ERROR = "§c";
	public static final String SUCCESS = "§a";
	public static final String WARN = "§e";
	public static final String ACCENT = "§6";
	public static final String INFO = "§b";
	public static final String COMMAND = "§a";
	public static final String MUTED = "§7";
	public static final String RESET = "§r";
	public static final String WHITE = "§f";

	// GUI (item lore, inventory titles)
	public static final String GUI_SUCCESS = "#87d65c";
	public static final String GUI_WARN = "#d6cf69";
	public static final String GUI_ACCENT = "#c9a24f";
	public static final String GUI_INFO = "#56ccf2";
	public static final String GUI_COMMAND = "#32ed73";

	private RPTexts() {
	}

	public static String formatDisplay(String raw) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}
		return raw.replace('&', '\u00A7');
	}

	public static String formatGui(String raw) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}
		return StringFormatter.formatHex(raw.replace('&', '\u00A7'));
	}

	/** @deprecated Use {@link #formatGui(String)} for lore or {@link #formatDisplay(String)} for chat. */
	@Deprecated
	public static String format(String raw) {
		return formatGui(raw);
	}

	public static void send(CommandSender sender, String raw) {
		sender.sendMessage(formatDisplay(raw));
	}

	public static void title(Player player, String title, String subtitle) {
		player.sendTitle(formatDisplay(title), formatDisplay(subtitle), 10, 60, 20);
	}

	public static void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
		player.sendTitle(formatDisplay(title), formatDisplay(subtitle), fadeIn, stay, fadeOut);
	}

	/** Title visible for ~20 seconds (400 ticks stay). */
	public static void longTitle(Player player, String title, String subtitle) {
		title(player, title, subtitle, 10, 400, 20);
	}

	public static String separator() {
		return MUTED + "----------------------------------------";
	}

	public static String enterTitle(String zoneName) {
		return MUTED + "Now entering " + WARN + zoneName;
	}

	public static String leaveTitle(String zoneName) {
		return MUTED + "Now leaving " + WARN + zoneName;
	}

	public static String prefix(String label) {
		return ACCENT + "[RPCharacters] " + MUTED;
	}

	public static void sendPrefixed(CommandSender sender, String raw) {
		send(sender, prefix("") + raw);
	}

	/** Lore spacer that resets inherited formatting (avoids default purple italic). */
	public static String spacer() {
		return formatGui(RESET + MUTED + " ");
	}

	/** Muted bullet line for plain text. */
	public static String bullet(String plainText) {
		return formatGui(RESET + MUTED + "- " + RESET + plainText);
	}

	/** Muted bullet prefix before text that is already colour-formatted. */
	public static String bulletFormatted(String formattedText) {
		return formatGui(RESET + MUTED + "- ") + formattedText;
	}

	public static String mutedParenthetical(String plainText) {
		return formatGui(" " + MUTED + "(" + plainText + ")");
	}

	public static String joinFormatted(java.util.List<String> formattedParts, String separator) {
		if (formattedParts == null || formattedParts.isEmpty()) {
			return formatGui(MUTED + "Not selected");
		}
		String formattedSeparator = formatGui(RESET + separator);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < formattedParts.size(); i++) {
			if (i > 0) {
				builder.append(formattedSeparator);
			}
			builder.append(formattedParts.get(i));
		}
		return builder.toString();
	}

	/** Ensures plain lore text always has an explicit colour (defaults to muted gray). */
	public static String lore(String plainText) {
		return formatGui(RESET + MUTED + plainText);
	}

	public static String labeled(String label, String formattedSuffix) {
		return formatGui(RESET + MUTED + label) + formattedSuffix;
	}
}
