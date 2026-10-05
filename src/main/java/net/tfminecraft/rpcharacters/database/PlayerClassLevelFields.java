package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.PlayerData;

/** The shared class level and experience, stored as "account-class-level" and "account-class-exp" in the player file. */
public final class PlayerClassLevelFields {
	private PlayerClassLevelFields() {}

	public static void load(PlayerData pd, JSONObject playerJson) {
		if (pd == null || playerJson == null) {
			return;
		}
		if (playerJson.get("account-class-level") instanceof Number level) {
			double experience = playerJson.get("account-class-exp") instanceof Number exp ? exp.doubleValue() : 0.0;
			pd.setAccountClassProgress(level.intValue(), experience);
		}
	}

	public static void save(Map<String, Object> defaults, PlayerData pd) {
		if (!pd.hasAccountClassLevel()) {
			return;
		}
		defaults.put("account-class-level", pd.getAccountClassLevel());
		defaults.put("account-class-exp", pd.getAccountClassExperience());
	}
}
