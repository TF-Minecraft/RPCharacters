package net.tfminecraft.rpcharacters.objects;

/**
 * Per-realm minimum donator tier for web character creator access.
 */
public final class WebCreatorRealmAccess {

	private final int minTier;
	private final String minGroupId;

	public WebCreatorRealmAccess(int minTier, String minGroupId) {
		this.minTier = Math.max(0, minTier);
		this.minGroupId = minGroupId == null ? "" : minGroupId.trim();
	}

	public int getMinTier() {
		return minTier;
	}

	public String getMinGroupId() {
		return minGroupId;
	}
}
