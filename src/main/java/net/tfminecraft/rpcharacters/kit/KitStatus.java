package net.tfminecraft.rpcharacters.kit;

/**
 * Per-character kit claim state. Missing/null on disk is treated as eligible on
 * claim (lazy stamp) so pre-kit characters match website customise rules.
 */
public enum KitStatus {
	ELIGIBLE,
	GRANTED,
	INELIGIBLE;

	public static KitStatus fromStorage(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return KitStatus.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public String toStorage() {
		return name().toLowerCase();
	}
}
