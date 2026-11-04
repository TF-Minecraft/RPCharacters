package net.tfminecraft.rpcharacters.wardrobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Swappable wardrobe slots: {@code base}, then {@code extra_1} … {@code extra_4}.
 * The website (TFMCWeb {@code wardrobe.py}) uses the same ids and the same maximum.
 */
public final class WardrobeSlots {

	/** Most swappable skins a character can hold (base + 4 extras). Ranks grant up to this. */
	public static final int MAX_SWAPPABLE = 5;

	private static final List<String> SWAPPABLE;

	static {
		List<String> ids = new ArrayList<>();
		ids.add(WardrobeSnapshot.SLOT_BASE);
		for (int i = 1; i < MAX_SWAPPABLE; i++) {
			ids.add("extra_" + i);
		}
		SWAPPABLE = Collections.unmodifiableList(ids);
	}

	private WardrobeSlots() {}

	/** Swappable slot ids in fill order. */
	public static List<String> swappable() {
		return SWAPPABLE;
	}

	/** Position of a swappable slot (0 = base), or -1 for masked and unknown ids. */
	public static int indexOf(String slot) {
		if (slot == null) {
			return -1;
		}
		return SWAPPABLE.indexOf(slot.trim().toLowerCase(Locale.ROOT));
	}

	/** Skins a rank must grant before this slot unlocks (base needs 1, Skin N needs N). */
	public static int requiredSkins(String slot) {
		return Math.max(1, indexOf(slot) + 1);
	}

	/** Default label: Base, Skin 2 … Skin 5, Masked; other ids unchanged. */
	public static String label(String slot) {
		if (slot == null) {
			return "Skin";
		}
		String key = slot.trim().toLowerCase(Locale.ROOT);
		if (WardrobeSnapshot.SLOT_MASKED.equals(key)) {
			return "Masked";
		}
		int index = SWAPPABLE.indexOf(key);
		if (index == 0) {
			return "Base";
		}
		if (index > 0) {
			return "Skin " + (index + 1);
		}
		return slot;
	}

	/**
	 * Slot id from a command argument: an id ({@code base}, {@code extra_2}, {@code extra2}),
	 * or a skin number ({@code 3}, {@code skin3}, {@code skin_3}). Null when it names none.
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		if (SWAPPABLE.contains(s)) {
			return s;
		}
		boolean extra = s.startsWith("extra");
		String number;
		if (extra) {
			number = s.substring(5);
		} else if (s.startsWith("skin")) {
			number = s.startsWith("skin_") ? s.substring(5) : s.substring(4);
		} else {
			number = s;
		}
		if (number.isEmpty() || number.length() > 2 || !number.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return null;
		}
		// extraN is the Nth extra (index N); a skin number counts base as 1.
		int index = extra ? Integer.parseInt(number) : Integer.parseInt(number) - 1;
		int lowest = extra ? 1 : 0;
		return index >= lowest && index < MAX_SWAPPABLE ? SWAPPABLE.get(index) : null;
	}
}
