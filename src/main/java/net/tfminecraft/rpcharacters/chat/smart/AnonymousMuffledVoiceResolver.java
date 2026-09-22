package net.tfminecraft.rpcharacters.chat.smart;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.chat.ChatChannel;

public final class AnonymousMuffledVoiceResolver {

	private AnonymousMuffledVoiceResolver() {}

	public static boolean shouldAnonymize(Player speaker, Player listener, ChatChannel channel,
			double intelligibility, SmartMessageSettings settings) {
		if (!settings.isAnonymousMuffledVoiceEnabled()) {
			return false;
		}
		if (speaker == null || listener == null || channel == null || settings == null) {
			return false;
		}
		if (speaker.equals(listener)) {
			return false;
		}
		if (channel.getRange() <= 0) {
			return false;
		}
		if (intelligibility >= settings.getAnonymousMuffledMaxIntelligibility()) {
			return false;
		}
		return !listener.hasLineOfSight(speaker);
	}

	public static String resolveDisplayName(String displayName, boolean anonymize, SmartMessageSettings settings) {
		if (!anonymize) {
			return displayName;
		}
		String anonymous = settings.getAnonymousMuffledDisplay();
		return anonymous != null && !anonymous.isBlank() ? anonymous : "???";
	}
}
