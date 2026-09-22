package net.tfminecraft.rpcharacters.objects.trait;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;

public final class TraitVariant {

	private final String name;
	private final List<String> description;
	private final AttributeData attributeData;
	private final List<PotionData> potionEffects;

	public TraitVariant(ConfigurationSection config) {
		if (config.contains("name")) {
			name = StringFormatter.formatHex(config.getString("name"));
		} else {
			name = null;
		}

		List<String> lines = new ArrayList<>();
		for (String line : config.getStringList("description")) {
			if (line == null || line.isBlank()) {
				continue;
			}
			lines.add(StringFormatter.formatHex(line));
		}
		description = Collections.unmodifiableList(lines);
		attributeData = new AttributeData(config);

		List<PotionData> effects = new ArrayList<>();
		if (config.contains("potion-effects")) {
			for (String raw : config.getStringList("potion-effects")) {
				PotionData potion = new PotionData(raw);
				if (potion.isValid()) {
					effects.add(potion);
				}
			}
		}
		potionEffects = Collections.unmodifiableList(effects);
	}

	public String getName() {
		return name;
	}

	public List<String> getDescription() {
		return description;
	}

	public AttributeData getAttributeData() {
		return attributeData;
	}

	public List<PotionData> getPotionEffects() {
		return potionEffects;
	}

	public boolean hasPotionEffects() {
		return !potionEffects.isEmpty();
	}
}
