package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.inventory.ItemStack;

public final class ProfessionDefinition {
	private final String id;
	private final String name;
	private final ProfessionItemSpec menuItem;
	private final List<ProfessionUpgradeDefinition> upgrades;

	public ProfessionDefinition(String id, String name, ProfessionItemSpec menuItem,
			List<ProfessionUpgradeDefinition> upgrades) {
		this.id = id;
		this.name = name;
		this.menuItem = menuItem != null ? menuItem : ProfessionItemSpec.empty();
		this.upgrades = upgrades != null ? upgrades : new ArrayList<>();
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public ItemStack getMenuItem() {
		return ProfessionItemFactory.build(menuItem);
	}

	public List<ProfessionUpgradeDefinition> getUpgrades() {
		return Collections.unmodifiableList(upgrades);
	}
}
