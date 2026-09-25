package net.tfminecraft.rpcharacters.database;

import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;

/** Tutorials the player clicked [Got It] on, stored as "dismissed-tutorials" in the player file. */
public final class PlayerTutorialFields {
	private PlayerTutorialFields() {}

	public static void load(PlayerData pd, JSONObject playerJson) {
		if (pd == null || playerJson == null) {
			return;
		}
		if (playerJson.get("dismissed-tutorials") instanceof List<?> dismissed) {
			for (Object id : dismissed) {
				if (id != null) {
					pd.setTutorialDismissed(id.toString(), true);
				}
			}
		}
		// Written before tutorials shared one list; kept so old dismissals still count.
		if (Boolean.parseBoolean(String.valueOf(playerJson.get("permadeath-tutorial-dismissed")))) {
			pd.setTutorialDismissed(TutorialService.PERMADEATH_ZONE, true);
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, PlayerData pd) {
		if (pd.getDismissedTutorials().isEmpty()) {
			return;
		}
		JSONArray dismissed = new JSONArray();
		dismissed.addAll(pd.getDismissedTutorials());
		defaults.put("dismissed-tutorials", dismissed);
	}
}
