package net.tfminecraft.rpcharacters.objects;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PermissionGroupDefinition {

	public static final String KEY_NAME_COLOUR_STOPS = "name-colour-stops";
	public static final String KEY_CHARACTER_SWITCH_COOLDOWN_DAYS = "character-switch-cooldown-days";
	public static final String KEY_MAX_ALIVE_CHARACTERS = "max-alive-characters";
	public static final String KEY_WARDROBE_SKIN_SLOTS = "wardrobe-skin-slots";

	private final String id;
	private final String permission;
	private final String displayName;
	private final int tier;
	private final boolean visible;
	private final Map<String, Integer> perks;

	public PermissionGroupDefinition(String id, String permission, String displayName, int tier,
			boolean visible, Map<String, Integer> perks) {
		this.id = id;
		this.permission = permission;
		this.displayName = displayName;
		this.tier = tier;
		this.visible = visible;
		this.perks = perks != null ? Collections.unmodifiableMap(new HashMap<>(perks)) : Map.of();
	}

	public String getId() {
		return id;
	}

	public String getPermission() {
		return permission;
	}

	public String getDisplayName() {
		return displayName;
	}

	public int getTier() {
		return tier;
	}

	public boolean isVisible() {
		return visible;
	}

	public Map<String, Integer> getPerks() {
		return perks;
	}

	public int getPerk(String key, int fallback) {
		return perks.getOrDefault(key, fallback);
	}
}
