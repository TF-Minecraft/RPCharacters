package net.tfminecraft.rpcharacters.wardrobe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Cached wardrobe for one character (plugin pull).
 */
public final class WardrobeSnapshot {

	public static final String SLOT_BASE = "base";
	public static final String SLOT_EXTRA_1 = "extra_1";
	public static final String SLOT_EXTRA_2 = "extra_2";
	public static final String SLOT_MASKED = "masked";

	private final String characterId;
	private String activeSlot;
	private final int swappableSlots;
	private final Map<String, WardrobeSlotData> slots;

	public WardrobeSnapshot(
		String characterId,
		String activeSlot,
		int swappableSlots,
		Map<String, WardrobeSlotData> slots
	) {
		this.characterId = characterId;
		this.activeSlot = activeSlot;
		this.swappableSlots = swappableSlots;
		this.slots = slots;
	}

	public String getCharacterId() {
		return characterId;
	}

	public String getActiveSlot() {
		return activeSlot;
	}

	public void setActiveSlot(String activeSlot) {
		this.activeSlot = activeSlot;
	}

	public int getSwappableSlots() {
		return swappableSlots;
	}

	public Map<String, WardrobeSlotData> getSlots() {
		return Collections.unmodifiableMap(slots);
	}

	public WardrobeSlotData getSlot(String slot) {
		if (slot == null) {
			return null;
		}
		return slots.get(slot.trim().toLowerCase());
	}

	public static WardrobeSnapshot parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			Object parsed = new JSONParser().parse(body);
			if (!(parsed instanceof JSONObject)) {
				return null;
			}
			JSONObject root = (JSONObject) parsed;
			String characterId = stringOf(root.get("character_id"));
			String active = stringOf(root.get("active_slot"));
			if (active != null && active.isEmpty()) {
				active = null;
			}
			int swappable = 1;
			Object sw = root.get("swappable_slots");
			if (sw instanceof Number) {
				swappable = ((Number) sw).intValue();
			}
			Map<String, WardrobeSlotData> map = new LinkedHashMap<>();
			Object slotsRaw = root.get("slots");
			if (slotsRaw instanceof JSONArray) {
				JSONArray arr = (JSONArray) slotsRaw;
				for (Object row : arr) {
					if (!(row instanceof JSONObject)) {
						continue;
					}
					JSONObject s = (JSONObject) row;
					String slot = stringOf(s.get("slot")).toLowerCase();
					if (slot.isEmpty()) {
						continue;
					}
					boolean unlocked = boolOf(s.get("unlocked"), false);
					boolean filled = boolOf(s.get("filled"), false);
					boolean signed = boolOf(s.get("signed"), false)
						|| boolOf(s.get("has_signature"), false);
					String model = stringOf(s.get("model"));
					if (model.isEmpty()) {
						model = null;
					}
					String displayName = stringOf(s.get("display_name"));
					if (displayName.isEmpty()) {
						displayName = null;
					}
					boolean applyPending = boolOf(s.get("apply_pending"), false);
					String value = stringOf(s.get("texture_value"));
					String signature = stringOf(s.get("texture_signature"));
					if (value.isEmpty()) {
						value = null;
					}
					if (signature.isEmpty()) {
						signature = null;
					}
					if (value != null && signature != null) {
						signed = true;
					}
					map.put(
						slot,
						new WardrobeSlotData(
							slot,
							unlocked,
							filled,
							signed,
							applyPending,
							model,
							displayName,
							value,
							signature
						)
					);
				}
			}
			return new WardrobeSnapshot(characterId, active, swappable, map);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private static boolean boolOf(Object value, boolean fallback) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value == null) {
			return fallback;
		}
		String s = String.valueOf(value).trim().toLowerCase();
		if (s.equals("true") || s.equals("1")) {
			return true;
		}
		if (s.equals("false") || s.equals("0")) {
			return false;
		}
		return fallback;
	}
}
