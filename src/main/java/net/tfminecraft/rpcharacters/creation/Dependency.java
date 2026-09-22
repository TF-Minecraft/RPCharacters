package net.tfminecraft.rpcharacters.creation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;

public class Dependency {
	private String type;
	private String mode;
	private List<String> dependencies = new ArrayList<>();
	
	public Dependency(ConfigurationSection config) {
		this.type = config.getString("type");
		this.mode = config.getString("mode");
		this.dependencies = config.getStringList("depends-on");
	}
	
	public String getType() {
		return type;
	}
	public String getMode() {
		return mode;
	}
	public List<String> getDependencies() {
		return dependencies;
	}
	
	public boolean check(RPCharacter c) {
		return checkExclude(c, "noneTrait");
	}
	public boolean checkExclude(RPCharacter c, String id) {
		if (type.equalsIgnoreCase("trait")) {
			Set<String> traitIds = c.getTraits().stream()
									.map(Trait::getId)
									.filter(traitId -> !traitId.equalsIgnoreCase(id))
									.collect(Collectors.toSet());
			return matchesTraitIds(traitIds);
		} else if (type.equalsIgnoreCase("race")) {
			for (String s : dependencies) {
				if (c.getRace().getId().equalsIgnoreCase(s)) return true;
			}
		}
		return false;
	}

	/** Trait requirements against an in-progress id set. Race dependencies stay on the character. */
	public boolean satisfiedBy(Set<String> traitIds) {
		if (type == null || !type.equalsIgnoreCase("trait") || traitIds == null) {
			return false;
		}
		return matchesTraitIds(traitIds);
	}

	public boolean satisfiedByExcluding(Set<String> traitIds, String excludedId) {
		if (type == null || !type.equalsIgnoreCase("trait") || traitIds == null) {
			return false;
		}
		Set<String> filtered = traitIds.stream()
				.filter(traitId -> traitId != null && !traitId.equalsIgnoreCase(excludedId))
				.collect(Collectors.toSet());
		return matchesTraitIds(filtered);
	}

	private boolean matchesTraitIds(Set<String> traitIds) {
		if (mode.equalsIgnoreCase("all")) {
			for (String s : dependencies) {
				if (!traitIds.contains(s)) return false;
			}
			return true;
		}
		if (mode.equalsIgnoreCase("one-or-more")) {
			for (String s : dependencies) {
				if (traitIds.contains(s)) return true;
			}
			return false;
		}
		return false;
	}
	@Override
	public String toString() {
		return "Dependency{" +
				"type='" + type + '\'' +
				", mode='" + mode + '\'' +
				", dependencies=" + dependencies +
				'}';
	}
}
