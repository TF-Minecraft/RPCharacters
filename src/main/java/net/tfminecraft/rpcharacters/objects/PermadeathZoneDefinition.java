package net.tfminecraft.rpcharacters.objects;

public final class PermadeathZoneDefinition {

	private final String regionId;
	private final String name;

	public PermadeathZoneDefinition(String regionId, String name) {
		this.regionId = regionId;
		this.name = name;
	}

	public String getRegionId() {
		return regionId;
	}

	public String getName() {
		return name;
	}

	/** @deprecated use {@link #getName()} */
	@Deprecated
	public String getDisplayName() {
		return name;
	}
}
