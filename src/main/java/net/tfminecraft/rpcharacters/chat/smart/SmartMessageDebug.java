package net.tfminecraft.rpcharacters.chat.smart;

import net.tfminecraft.rpcharacters.loaders.SmartMessageLoader;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class SmartMessageDebug {

	private static final String PREFIX = "[SmartMessages] ";

	private SmartMessageDebug() {}

	public static boolean isEnabled() {
		return SmartMessageLoader.getSettings().isDebugMessages();
	}

	public static void log(String stage, String message) {
		if (!isEnabled()) {
			return;
		}
		if (RPCharacters.plugin != null) {
			RPCharacters.plugin.getLogger().info(PREFIX + stage + ": " + message);
		}
	}

	public static void logSkip(String stage, String reason) {
		log(stage, "SKIP — " + reason);
	}
}
