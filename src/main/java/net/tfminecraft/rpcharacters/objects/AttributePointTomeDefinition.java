package net.tfminecraft.rpcharacters.objects;

import org.bukkit.configuration.ConfigurationSection;

public final class AttributePointTomeDefinition {

	private final String id;
	private final String item;
	private final int attributePoints;

	public AttributePointTomeDefinition(String id, ConfigurationSection config) {
		this.id = id;
		this.item = config.getString("item", "");
		this.attributePoints = config.getInt("attribute-points", 0);
	}

	public String getId() {
		return id;
	}

	public String getItem() {
		return item;
	}

	public int getAttributePoints() {
		return attributePoints;
	}
}
