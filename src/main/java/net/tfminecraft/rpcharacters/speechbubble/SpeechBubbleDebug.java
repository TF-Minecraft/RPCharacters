package net.tfminecraft.rpcharacters.speechbubble;

import net.tfminecraft.rpcharacters.loaders.SpeechBubbleLoader;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class SpeechBubbleDebug {

	private static final String PREFIX = "[SpeechBubbles] ";

	private SpeechBubbleDebug() {}

	public static boolean isEnabled() {
		return SpeechBubbleLoader.getSettings().isDebugMessages();
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
