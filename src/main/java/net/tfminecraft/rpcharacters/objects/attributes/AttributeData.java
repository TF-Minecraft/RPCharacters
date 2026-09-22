package net.tfminecraft.rpcharacters.objects.attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;

public class AttributeData {
	private List<AttributeModifier> modifiers = new ArrayList<>();
	private List<ExperienceModifier> xpModifiers = new ArrayList<>();
	
	public AttributeData(ConfigurationSection config) {
		if(config.contains("attribute-modifiers")) {
			for(String s : config.getStringList("attribute-modifiers")) {
				modifiers.add(new AttributeModifier(s));
			}
		}
		if(config.contains("experience-modifiers")) {
			Set<String> set = config.getConfigurationSection("experience-modifiers").getKeys(false);

			List<String> list = new ArrayList<String>(set);
			
			for(String key : list) {
				xpModifiers.add(new ExperienceModifier(key, config.getConfigurationSection("experience-modifiers."+key)));
			}
		}
	}
	public AttributeData() {
		if(Cache.attributes.size() > 0) {
			for(String s : Cache.attributes) {
				modifiers.add(new AttributeModifier(s, 0));
			}
		}
		if(Cache.professions.size() > 0) {
			for(String s : Cache.professions) {
				if(s.split("\\(").length > 1) {
					String prof = s.split("\\(")[0];
					String alias = s.split("\\(")[1].replace(")", "");
					xpModifiers.add(new ExperienceModifier(prof, alias, Cache.startingProfessionFactor));
				} else {
					xpModifiers.add(new ExperienceModifier(s, s, Cache.startingProfessionFactor));
				}
			}
		}
	}

	public AttributeData(AttributeData other) {
		this();
		if (other != null) {
			mergeFrom(other);
		}
	}
	
	public boolean hasModifiers() {
		if(modifiers.size() > 0) return true;
		if(xpModifiers.size() > 0) return true;
		return false;
	}
	
	public List<AttributeModifier> getModifiers() {
		return modifiers;
	}

	public boolean hasModifier(AttributeModifier m) {
		for(AttributeModifier mod : modifiers) {
			if(mod.getType().equalsIgnoreCase(m.getType())) return true;
		}
		return false;
	}
	public boolean hasXPModifier(ExperienceModifier m) {
		for(ExperienceModifier mod : xpModifiers) {
			if(mod.getProfession().equalsIgnoreCase(m.getProfession())) return true;
		}
		return false;
	}
	
	private void modify(AttributeModifier m) {
		for(AttributeModifier mod : modifiers) {
			if(mod.getType().equalsIgnoreCase(m.getType())) {
				mod.add(m.getAmount());
			}
		}
	}
	private void xpModify(ExperienceModifier m) {
		for(ExperienceModifier mod : xpModifiers) {
			if(mod.getProfession().equalsIgnoreCase(m.getProfession())) {
				mod.modify(m.getModifier());
			}
		}
	}
	
	public int getAmount(AttributeModifier m) {
		for(AttributeModifier mod : modifiers) {
			if(mod.getType().equalsIgnoreCase(m.getType())) return mod.getAmount();
		}
		return 0;
	}
	public int getAmount(ExperienceModifier m) {
		for(ExperienceModifier mod : xpModifiers) {
			if(mod.getProfession().equalsIgnoreCase(m.getProfession())) return mod.getModifier();
		}
		return 0;
	}
	
	public void mergeFrom(AttributeData data) {
		for(AttributeModifier m : data.getModifiers()) {
			addModifier(m);
		}
		for(ExperienceModifier m : data.getExperienceModifiers()) {
			addXPModifier(m);
		}
	}
	
	public void addModifier(AttributeModifier m) {
		if(hasModifier(m)) {
			modify(m);
		} else {
			modifiers.add(new AttributeModifier(m.getType(), m.getAmount()));
		}
	}
	public void addXPModifier(ExperienceModifier m) {
		if(hasXPModifier(m)) {
			xpModify(m);
		} else {
			xpModifiers.add(new ExperienceModifier(m, 0));
		}
	}
	public void mergeFromReverse(AttributeData data) {
		for(AttributeModifier m : data.getModifiers()) {
			addModifier(new AttributeModifier(m.getType(), m.getAmount()*-1));
		}
		for(ExperienceModifier m : data.getExperienceModifiers()) {
			addXPModifier(new ExperienceModifier(m, m.getModifier()*-2));
		}
	}

	public void clearAll() {
		modifiers.clear();
		xpModifiers.clear();
	}

	public List<ExperienceModifier> getExperienceModifiers() {
		return xpModifiers;
	}
}
