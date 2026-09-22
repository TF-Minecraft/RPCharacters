package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProfessionRegistry {
	private static final List<ProfessionDefinition> professions = new ArrayList<>();
	private static final List<ProfessionUpgradeDefinition> upgrades = new ArrayList<>();
	private static final List<ProfessionItemType> itemTypes = new ArrayList<>();

	private ProfessionRegistry() {}

	public static void clear() {
		professions.clear();
		upgrades.clear();
		itemTypes.clear();
	}

	public static void setProfessions(List<ProfessionDefinition> loaded) {
		professions.clear();
		professions.addAll(loaded);
	}

	public static void setUpgrades(List<ProfessionUpgradeDefinition> loaded) {
		upgrades.clear();
		upgrades.addAll(loaded);
	}

	public static void setItemTypes(List<ProfessionItemType> loaded) {
		itemTypes.clear();
		itemTypes.addAll(loaded);
	}

	public static List<ProfessionDefinition> getProfessions() {
		return Collections.unmodifiableList(professions);
	}

	public static List<ProfessionUpgradeDefinition> getUpgrades() {
		return Collections.unmodifiableList(upgrades);
	}

	public static List<ProfessionItemType> getItemTypes() {
		return Collections.unmodifiableList(itemTypes);
	}

	public static ProfessionDefinition getProfession(String id) {
		if (id == null) {
			return null;
		}
		for (ProfessionDefinition profession : professions) {
			if (profession.getId().equalsIgnoreCase(id)) {
				return profession;
			}
		}
		return null;
	}

	public static ProfessionUpgradeDefinition getUpgrade(String id) {
		if (id == null) {
			return null;
		}
		for (ProfessionUpgradeDefinition upgrade : upgrades) {
			if (upgrade.getId().equalsIgnoreCase(id)) {
				return upgrade;
			}
		}
		return null;
	}
}
