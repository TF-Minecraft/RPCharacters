package net.tfminecraft.rpcharacters.objects.trait;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.creation.Dependency;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.utils.DurationParser;

public class TraitData {
	private int cost;
	private List<String> exclusive = new ArrayList<>();
	private List<PotionData> potionEffects = new ArrayList<>();
	private String key;
	
	private Dependency dependency;
	private AttributeData data;
	private int requiredAccountPlaytimeSeconds;
	private long durationMs;
	private String fuelTemplateId;
	private double fuelCapacity;
	private TraitVariant poweredVariant;
	private TraitVariant depoweredVariant;
	private Material icon;
	private boolean blockOffhand;
	private boolean canLootGraves;
	
	public TraitData(ConfigurationSection config) {
		key = config.getString("key");
		if(config.contains("cost")) {
			cost = config.getInt("cost");
		} else {
			cost = 0;
		}
		if(config.contains("mutually-exclusive")) {
			exclusive = config.getStringList("mutually-exclusive");
		}
		if(config.contains("dependency")) {
			dependency = new Dependency(config.getConfigurationSection("dependency"));
		}
		if(config.contains("potion-effects")) {
			for(String s : config.getStringList("potion-effects")) {
				PotionData potion = new PotionData(s);
				if(potion.isValid()) {
					potionEffects.add(potion);
				}
			}
		}
		data = new AttributeData(config);
		if (config.contains("required-account-playtime")) {
			double hours = config.getDouble("required-account-playtime");
			if (hours > 0) {
				requiredAccountPlaytimeSeconds = (int) Math.round(hours * 3600.0);
			}
		}
		if (config.contains("duration")) {
			long parsed = DurationParser.parseLockTimeMs(config.getString("duration"));
			if (parsed > 0) {
				durationMs = parsed;
			}
		}
		if (config.contains("fuel-template")) {
			fuelTemplateId = config.getString("fuel-template");
		}
		if (config.contains("fuel-capacity")) {
			fuelCapacity = config.getDouble("fuel-capacity");
		}
		if (config.isConfigurationSection("powered")) {
			poweredVariant = new TraitVariant(config.getConfigurationSection("powered"));
		}
		if (config.isConfigurationSection("depowered")) {
			depoweredVariant = new TraitVariant(config.getConfigurationSection("depowered"));
		}
		blockOffhand = config.getBoolean("block-offhand", false);
		canLootGraves = config.getBoolean("can-loot-graves", false);
		if (config.contains("icon")) {
			String raw = config.getString("icon");
			if (raw != null && !raw.isBlank()) {
				try {
					icon = Material.valueOf(raw.trim().toUpperCase());
					if (icon == Material.AIR) {
						icon = null;
					}
				} catch (IllegalArgumentException ignored) {
					icon = null;
				}
			}
		}
	}
	
	public boolean hasDependency() {
		if(dependency != null) return true;
		return false;
	}
	
	public Dependency getDependency() {
		return dependency;
	}

	public void setDependency(Dependency dependency) {
		this.dependency = dependency;
	}
	public String getKey() {
		return key;
	}
	public boolean hasCost() {
		if(cost != 0) return true;
		return false;
	}

	public int getCost() {
		return cost;
	}
	public boolean hasExclusives() {
		if(exclusive.size() > 0) return true;
		return false;
	}

	public List<String> getExclusive() {
		return exclusive;
	}
	
	public boolean isExclusive(String s) {
		for(String e : exclusive) {
			if(e.equalsIgnoreCase(s)) return true;
		}
		return false;
	}

	public AttributeData getAttributeData() {
		return data;
	}

	public boolean hasPotionEffects() {
		if(potionEffects.size() > 0) return true;
		return false;
	}

	public List<PotionData> getPotionEffects() {
		return potionEffects;
	}

	public int getRequiredAccountPlaytimeSeconds() {
		return requiredAccountPlaytimeSeconds;
	}

	public boolean hasDuration() {
		return durationMs > 0;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public boolean hasFuelTemplate() {
		return fuelTemplateId != null && !fuelTemplateId.isBlank();
	}

	public String getFuelTemplateId() {
		return fuelTemplateId;
	}

	public double getFuelCapacity() {
		return fuelCapacity;
	}

	public boolean hasPoweredVariant() {
		return poweredVariant != null;
	}

	public TraitVariant getPoweredVariant() {
		return poweredVariant;
	}

	public TraitVariant getDepoweredVariant() {
		return depoweredVariant;
	}

	public boolean isInjuryKey() {
		return key != null && key.equalsIgnoreCase("injury");
	}

	public boolean isProstheticKey() {
		return key != null && key.equalsIgnoreCase("prosthetic");
	}

	public boolean hasIcon() {
		return icon != null;
	}

	public Material getIcon() {
		return icon;
	}

	public boolean blocksOffhand() {
		return blockOffhand;
	}

	public boolean canLootGraves() {
		return canLootGraves;
	}
}
