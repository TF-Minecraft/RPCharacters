package net.tfminecraft.rpcharacters.chat.smart;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.PlayerData;

public final class CharismaHearingCalculator {

	private CharismaHearingCalculator() {}

	public static int getCharisma(Player player, SmartMessageSettings settings) {
		if (player == null || settings == null) {
			return 0;
		}
		String attribute = settings.getCharismaAttribute();
		if (attribute == null || attribute.isBlank()) {
			return 0;
		}
		try {
			return PlayerData.get(player.getUniqueId())
					.getAttributes()
					.getInstance(attribute)
					.getTotal();
		} catch (Exception e) {
			return 0;
		}
	}

	public static double applyBoost(Player listener, double rawIntelligibility, SmartMessageSettings settings) {
		if (listener == null || settings == null || !settings.isCharismaHearingEnabled()) {
			return rawIntelligibility;
		}
		if (rawIntelligibility < settings.getMinAudible() || rawIntelligibility >= settings.getFullClarity()) {
			return rawIntelligibility;
		}
		int charisma = getCharisma(listener, settings);
		double boostFactor = computeBoostFactor(charisma, rawIntelligibility, settings);
		if (boostFactor <= 0.0) {
			return rawIntelligibility;
		}
		double fullClarity = settings.getFullClarity();
		double effective = rawIntelligibility + boostFactor * (fullClarity - rawIntelligibility);
		return clamp(effective, settings.getMinAudible(), fullClarity);
	}

	public static double computeBoostFactor(int charisma, double rawIntelligibility, SmartMessageSettings settings) {
		if (settings == null || charisma <= 0) {
			return 0.0;
		}
		double scale = settings.getCharismaScale();
		if (scale <= 0.0) {
			return 0.0;
		}
		double boostFactor = settings.getCharismaMaxBoost() * Math.min(1.0, charisma / scale);
		if (rawIntelligibility < settings.getLowIntelligibilityThreshold()) {
			boostFactor *= settings.getCharismaPlaceholderMultiplier();
		}
		return boostFactor;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
