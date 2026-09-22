package net.tfminecraft.rpcharacters.objects;

import org.bukkit.configuration.ConfigurationSection;

public final class SkillPointTomeDefinition {

	private final String id;
	private final String item;
	private final int skillPoints;

	public SkillPointTomeDefinition(String id, ConfigurationSection config) {
		this.id = id;
		this.item = config.getString("item", "");
		this.skillPoints = config.getInt("skill-points", 0);
	}

	public String getId() {
		return id;
	}

	public String getItem() {
		return item;
	}

	public int getSkillPoints() {
		return skillPoints;
	}
}
