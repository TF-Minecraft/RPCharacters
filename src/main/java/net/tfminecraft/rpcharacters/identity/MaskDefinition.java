package net.tfminecraft.rpcharacters.identity;

import org.bukkit.configuration.ConfigurationSection;

public final class MaskDefinition {

	private final String id;
	private final String item;

	public MaskDefinition(String id, ConfigurationSection config) {
		this.id = id;
		this.item = config.getString("item", "");
	}

	public static MaskDefinition fromItemPath(String id, String itemPath) {
		return new MaskDefinition(id, itemPath);
	}

	private MaskDefinition(String id, String item) {
		this.id = id;
		this.item = item;
	}

	public String getId() {
		return id;
	}

	public String getItem() {
		return item;
	}
}
