package net.tfminecraft.rpcharacters.database;

import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.PlayerData;

/** Skill and attribute points from items, stored as "item-skill-points" and "item-attribute-points". */
public final class PlayerItemPointFields {
	private PlayerItemPointFields() {}

	public static void load(PlayerData pd, JSONObject playerJson) {
		if (pd == null || playerJson == null) {
			return;
		}
		if (playerJson.get("item-skill-points") instanceof Number points) {
			pd.setItemSkillPoints(points.intValue());
		}
		if (playerJson.get("item-attribute-points") instanceof Number points) {
			pd.setItemAttributePoints(points.intValue());
		}
	}

	public static void save(Map<String, Object> defaults, PlayerData pd) {
		if (pd.getItemSkillPoints() > 0) {
			defaults.put("item-skill-points", pd.getItemSkillPoints());
		}
		if (pd.getItemAttributePoints() > 0) {
			defaults.put("item-attribute-points", pd.getItemAttributePoints());
		}
	}
}
