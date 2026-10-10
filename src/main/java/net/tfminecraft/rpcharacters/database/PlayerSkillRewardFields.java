package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.PlayerData;

/** Exp-table reward skill points already in the account total, stored as "account-reward-skill-points". */
public final class PlayerSkillRewardFields {
	private PlayerSkillRewardFields() {}

	public static void load(PlayerData pd, JSONObject playerJson) {
		if (pd == null || playerJson == null) {
			return;
		}
		if (playerJson.get("account-reward-skill-points") instanceof Number points) {
			pd.setAccountRewardSkillPoints(points.intValue());
		}
	}

	public static void save(Map<String, Object> defaults, PlayerData pd) {
		if (pd.getAccountRewardSkillPoints() <= 0) {
			return;
		}
		defaults.put("account-reward-skill-points", pd.getAccountRewardSkillPoints());
	}
}
