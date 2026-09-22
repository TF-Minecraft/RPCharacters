package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

public final class ProfessionUpgradeDefinition {
	private final String id;
	private final String professionId;
	private final ProfessionItemSpec menuItem;
	private final int cost;
	private final String type;
	private final List<String> requirements;
	private final List<String> unlocks;

	public ProfessionUpgradeDefinition(String id, String professionId, ProfessionItemSpec menuItem, int cost,
			String type, List<String> requirements, List<String> unlocks) {
		this.id = id;
		this.professionId = professionId;
		this.menuItem = menuItem != null ? menuItem : ProfessionItemSpec.empty();
		this.cost = cost;
		this.type = type;
		this.requirements = requirements != null ? requirements : new ArrayList<>();
		this.unlocks = unlocks != null ? unlocks : new ArrayList<>();
	}

	public String getId() {
		return id;
	}

	public String getProfessionId() {
		return professionId;
	}

	public ItemStack getMenuItem() {
		return ProfessionItemFactory.build(menuItem);
	}

	public int getCost() {
		return cost;
	}

	public String getType() {
		return type;
	}

	public List<String> getRequirements() {
		return requirements;
	}

	public List<String> getUnlocks() {
		return unlocks;
	}
}
