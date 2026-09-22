package net.tfminecraft.rpcharacters.objects.races;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public class Race {
	
	private String id;
	private String name;
	private List<String> desc = new ArrayList<String>();
	private boolean isShown;
	
	private RaceData data;

	public Race(String key, ConfigurationSection config) {
		this.id = key;
		this.name = StringFormatter.formatHex(config.getString("name"));
		for(String s : config.getStringList("description")) {
			desc.add(StringFormatter.formatHex(s));
		}
		this.isShown = config.getBoolean("shown");
		this.data = new RaceData(config);
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

	public boolean isShown() {
		return isShown;
	}

	public RaceData getRaceData() {
		return data;
	}

	public int getAgeMax() {
		return data != null ? data.getAgeMax() : 100;
	}

}
