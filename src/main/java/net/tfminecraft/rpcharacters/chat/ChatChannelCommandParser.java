package net.tfminecraft.rpcharacters.chat;

import java.util.Locale;

import net.tfminecraft.rpcharacters.loaders.ChatLoader;

public final class ChatChannelCommandParser {

	private ChatChannelCommandParser() {}

	public static ParsedChannelCommand parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}

		String trimmed = raw.stripLeading();
		if (!trimmed.startsWith("/")) {
			return null;
		}

		String withoutSlash = trimmed.substring(1);
		if (withoutSlash.isEmpty()) {
			return null;
		}

		int space = withoutSlash.indexOf(' ');
		String labelToken = space < 0 ? withoutSlash : withoutSlash.substring(0, space);
		String label = normalizeLabel(labelToken);
		if (label.isEmpty()) {
			return null;
		}

		String channelId = ChatLoader.resolveChannelFromCommand(label);
		if (channelId == null) {
			return null;
		}

		ChatChannel channel = ChatLoader.getChannel(channelId);
		if (channel == null) {
			return null;
		}

		String message = space < 0 ? "" : withoutSlash.substring(space + 1).stripLeading();
		return new ParsedChannelCommand(label, channel, message);
	}

	static String normalizeLabel(String labelToken) {
		if (labelToken == null || labelToken.isBlank()) {
			return "";
		}
		String label = labelToken;
		int colon = label.indexOf(':');
		if (colon >= 0 && colon < label.length() - 1) {
			label = label.substring(colon + 1);
		}
		return label.toLowerCase(Locale.ROOT);
	}
}
