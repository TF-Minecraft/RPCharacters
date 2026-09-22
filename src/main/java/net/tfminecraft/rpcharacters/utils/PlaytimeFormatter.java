package net.tfminecraft.rpcharacters.utils;

public final class PlaytimeFormatter {

	private PlaytimeFormatter() {}

	public static String formatHoursRemaining(int secondsRemaining) {
		if (secondsRemaining <= 0) {
			return "0h";
		}
		int hours = (secondsRemaining + 3599) / 3600;
		return hours + "h";
	}
}
