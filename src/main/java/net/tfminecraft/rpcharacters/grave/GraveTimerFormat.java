package net.tfminecraft.rpcharacters.grave;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public final class GraveTimerFormat {

	private GraveTimerFormat() {
	}

	public static String timerLine(Grave grave) {
		int expireSeconds = GraveLoader.getExpireSeconds();
		if (expireSeconds <= 0 || grave == null) {
			return null;
		}
		long remaining = GraveExpiryLogic.remainingMillis(
				grave.getCreated(), System.currentTimeMillis(), expireSeconds);
		String template = remaining <= 0
				? GraveLoader.getHologramTimerExpiring()
				: applyTimePlaceholder(GraveLoader.getHologramTimerFormat(), remaining);
		return formatMessage(template);
	}

	static String applyTimePlaceholder(String template, long remainingMillis) {
		if (template == null) {
			return null;
		}
		return template.replace("{time}", GraveExpiryLogic.formatRemainingTime(remainingMillis));
	}

	private static String formatMessage(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		return StringFormatter.formatHex(message.replace('&', '\u00A7'));
	}
}
