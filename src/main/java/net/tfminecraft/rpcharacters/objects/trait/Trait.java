package net.tfminecraft.rpcharacters.objects.trait;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public class Trait {
	private String id;
	private String name;
	private List<String> desc = new ArrayList<String>();
	private String gainedMessage;
	private String lostMessage;
	
	private TraitData data;
	
	public Trait(String key, ConfigurationSection config) {
		this.id = key;
		this.name = StringFormatter.formatHex(config.getString("name"));
		for(String s : config.getStringList("description")) {
			if (s == null || s.isBlank()) {
				continue;
			}
			desc.add(StringFormatter.formatHex(s));
		}
		if (config.contains("gained-message")) {
			gainedMessage = StringFormatter.formatHex(config.getString("gained-message"));
		}
		if (config.contains("lost-message")) {
			lostMessage = StringFormatter.formatHex(config.getString("lost-message"));
		}
		this.data = new TraitData(config);
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public List<String> getDesc() {
		return desc;
	}

	public TraitData getTraitData() {
		return data;
	}

	public String getGainedMessage() {
		return gainedMessage;
	}

	public String getLostMessage() {
		return lostMessage;
	}

	public boolean hasDuration() {
		return data.hasDuration();
	}

	public long getDurationMs() {
		return data.getDurationMs();
	}

	public boolean hasFuelTemplate() {
		return data.hasFuelTemplate();
	}

	public String getFuelTemplateId() {
		return data.getFuelTemplateId();
	}

	public double getFuelCapacity() {
		return data.getFuelCapacity();
	}

	public boolean hasPoweredVariant() {
		return data.hasPoweredVariant();
	}

	public TraitVariant getPoweredVariant() {
		return data.getPoweredVariant();
	}

	public TraitVariant getDepoweredVariant() {
		return data.getDepoweredVariant();
	}

	public boolean hasIcon() {
		return data.hasIcon();
	}

	public Material getIcon() {
		return data.getIcon();
	}
}
