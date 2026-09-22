package net.tfminecraft.rpcharacters.professions;

import java.util.List;

public final class ProfessionItemSpec {

	private final String path;
	private final String material;
	private final String name;
	private final Integer modelData;
	private final List<String> enchants;
	private final boolean hideEnchants;
	private final List<String> lore;

	public ProfessionItemSpec(String path, String material, String name, Integer modelData,
			List<String> enchants, boolean hideEnchants, List<String> lore) {
		this.path = path;
		this.material = material;
		this.name = name;
		this.modelData = modelData;
		this.enchants = enchants != null ? List.copyOf(enchants) : List.of();
		this.hideEnchants = hideEnchants;
		this.lore = lore != null ? List.copyOf(lore) : List.of();
	}

	public static ProfessionItemSpec empty() {
		return new ProfessionItemSpec(null, null, null, null, List.of(), false, List.of());
	}

	public String getPath() {
		return path;
	}

	public String getMaterial() {
		return material;
	}

	public String getName() {
		return name;
	}

	public Integer getModelData() {
		return modelData;
	}

	public List<String> getEnchants() {
		return enchants;
	}

	public boolean isHideEnchants() {
		return hideEnchants;
	}

	public List<String> getLore() {
		return lore;
	}

	public boolean hasName() {
		return name != null;
	}

	public boolean hasModelData() {
		return modelData != null;
	}

	public boolean hasLore() {
		return !lore.isEmpty();
	}

	public boolean hasEnchants() {
		return !enchants.isEmpty();
	}
}
