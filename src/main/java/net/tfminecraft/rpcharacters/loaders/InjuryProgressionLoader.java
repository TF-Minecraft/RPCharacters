package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class InjuryProgressionLoader implements LoaderInterface {

	private static final Map<String, String> progression = new HashMap<>();
	/** Injury id to the group it shares a body part with. */
	private static final Map<String, String> groups = new HashMap<>();
	private static final Map<String, Integer> limits = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		progression.clear();
		groups.clear();
		limits.clear();
		loadProgression(config.getConfigurationSection("progression"));
		loadGroups(config.getConfigurationSection("groups"));
		loadLimits(config.getConfigurationSection("limits"));
	}

	private static void loadProgression(ConfigurationSection section) {
		if (section == null) {
			return;
		}
		for (String healingId : section.getKeys(false)) {
			String permanentId = section.getString(healingId);
			if (permanentId == null || permanentId.isBlank()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression '" + healingId + "' has no target and was skipped.");
				continue;
			}

			Trait healingTrait = TraitLoader.getByString(healingId);
			if (healingTrait == null) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression '" + healingId + "' references unknown healing trait and was skipped.");
				continue;
			}
			if (!healingTrait.getTraitData().hasDuration()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression source '" + healingId + "' has no duration and was skipped.");
				continue;
			}

			Trait permanentTrait = TraitLoader.getByString(permanentId);
			if (permanentTrait == null) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression '" + healingId + "' -> '" + permanentId
								+ "' references unknown permanent trait and was skipped.");
				continue;
			}
			if (permanentTrait.getTraitData().hasDuration()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression target '" + permanentId + "' has duration and was skipped.");
				continue;
			}
			if (!permanentTrait.getTraitData().isInjuryKey()) {
				RPCharacters.plugin.getLogger().warning(
						"Injury progression target '" + permanentId + "' is not key injury and was skipped.");
				continue;
			}

			progression.put(healingId.toLowerCase(Locale.ROOT), permanentId.toLowerCase(Locale.ROOT));
		}
	}

	private static void loadGroups(ConfigurationSection section) {
		if (section == null) {
			return;
		}
		for (String name : section.getKeys(false)) {
			String group = name.toLowerCase(Locale.ROOT);
			if (TraitLoader.getByString(group) != null) {
				RPCharacters.plugin.getLogger().warning(
						"Injury group '" + name + "' has the name of a trait and was skipped.");
				continue;
			}
			for (String member : section.getStringList(name)) {
				String id = member == null ? "" : member.trim().toLowerCase(Locale.ROOT);
				Trait trait = id.isEmpty() ? null : TraitLoader.getByString(id);
				if (trait == null || !trait.getTraitData().isInjuryKey()) {
					RPCharacters.plugin.getLogger().warning(
							"Injury group '" + name + "' entry '" + member + "' is not an injury and was skipped.");
				} else if (progression.containsKey(id) || progression.containsValue(id)) {
					RPCharacters.plugin.getLogger().warning(
							"Injury group '" + name + "' entry '" + member
									+ "' already has a body part from progression and was skipped.");
				} else if (groups.containsKey(id)) {
					RPCharacters.plugin.getLogger().warning(
							"Injury group '" + name + "' entry '" + member + "' is already in group '"
									+ groups.get(id) + "' and was skipped.");
				} else {
					groups.put(id, group);
				}
			}
		}
	}

	private static void loadLimits(ConfigurationSection section) {
		if (section == null) {
			return;
		}
		for (String bodyPart : section.getKeys(false)) {
			if (!isPermanentInjury(bodyPart) && !groups.containsValue(bodyPart.toLowerCase(Locale.ROOT))) {
				RPCharacters.plugin.getLogger().warning(
						"Injury limit '" + bodyPart + "' is not a permanent injury or an injury group and was skipped.");
				continue;
			}
			int limit = section.getInt(bodyPart, 0);
			if (limit < 1) {
				RPCharacters.plugin.getLogger().warning(
						"Injury limit '" + bodyPart + "' must be at least 1 and was skipped.");
				continue;
			}
			limits.put(bodyPart.toLowerCase(Locale.ROOT), limit);
		}
	}

	/** Configured injury slots for this body part (a permanent injury or an injury group), or 0 when unset. */
	public static int getLimit(String bodyPart) {
		if (bodyPart == null || bodyPart.isBlank()) {
			return 0;
		}
		return limits.getOrDefault(bodyPart.toLowerCase(Locale.ROOT), 0);
	}

	/** The injury group this injury shares a body part with, or null. */
	public static String getGroup(String traitId) {
		if (traitId == null || traitId.isBlank()) {
			return null;
		}
		return groups.get(traitId.toLowerCase(Locale.ROOT));
	}

	public static String getPermanentId(String healingTraitId) {
		if (healingTraitId == null || healingTraitId.isBlank()) {
			return null;
		}
		return progression.get(healingTraitId.toLowerCase(Locale.ROOT));
	}

	public static boolean isHealingTrait(String traitId) {
		Trait trait = TraitLoader.getByString(traitId);
		return trait != null && trait.getTraitData().hasDuration();
	}

	public static boolean isPermanentInjury(String traitId) {
		Trait trait = TraitLoader.getByString(traitId);
		return trait != null
				&& trait.getTraitData().isInjuryKey()
				&& !trait.getTraitData().hasDuration();
	}

	public static Map<String, String> getProgressionMap() {
		return Collections.unmodifiableMap(progression);
	}
}
