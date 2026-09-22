package net.tfminecraft.rpcharacters.objects;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

public final class MagnifyingGlassDefinition {

	private final String id;
	private final String item;
	private final double discoveryBonus;
	private final int investigationCost;
	private final double searchRadius;
	private final Map<String, Integer> requires = new HashMap<>();

	public MagnifyingGlassDefinition(String id, ConfigurationSection config) {
		this.id = id;
		this.item = config.getString("item", "");
		this.discoveryBonus = config.getDouble("discovery-bonus", 0.0);
		this.investigationCost = Math.max(0, config.getInt("investigation-cost", 1));
		this.searchRadius = config.getDouble("search-radius", 3.0);
		if (config.isConfigurationSection("requires")) {
			ConfigurationSection req = config.getConfigurationSection("requires");
			for (String key : req.getKeys(false)) {
				requires.put(key.toLowerCase(), req.getInt(key, 0));
			}
		}
	}

	public String getId() {
		return id;
	}

	public String getItem() {
		return item;
	}

	public double getDiscoveryBonus() {
		return discoveryBonus;
	}

	public int getInvestigationCost() {
		return investigationCost;
	}

	public double getSearchRadius() {
		return searchRadius;
	}

	public Map<String, Integer> getRequires() {
		return requires;
	}
}
