package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.objects.FuelTemplate;
import net.tfminecraft.rpcharacters.objects.ProstheticReplacement;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.prosthetics.ProstheticInstallMatch;

public final class ProstheticLoader implements LoaderInterface {

	private static final Map<String, ProstheticReplacement> byInjuryId = new HashMap<>();
	private static final Map<String, ProstheticReplacement> byProstheticId = new HashMap<>();
	private static final List<ProstheticReplacement> loadOrder = new ArrayList<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		byInjuryId.clear();
		byProstheticId.clear();
		loadOrder.clear();

		if (!config.isConfigurationSection("replacements")) {
			return;
		}

		ConfigurationSection replacements = config.getConfigurationSection("replacements");
		for (String injuryId : replacements.getKeys(false)) {
			ConfigurationSection section = replacements.getConfigurationSection(injuryId);
			if (section == null) {
				continue;
			}

			Trait injuryTrait = TraitLoader.getByString(injuryId);
			if (injuryTrait == null) {
				RPCharacters.plugin.getLogger().warning(
						"Prosthetic replacement '" + injuryId + "' references unknown injury trait and was skipped.");
				continue;
			}
			if (!injuryTrait.getTraitData().isInjuryKey()) {
				RPCharacters.plugin.getLogger().warning(
						"Prosthetic replacement '" + injuryId + "' is not key injury and was skipped.");
				continue;
			}
			if (injuryTrait.getTraitData().hasDuration()) {
				RPCharacters.plugin.getLogger().warning(
						"Prosthetic replacement '" + injuryId + "' must be a permanent injury and was skipped.");
				continue;
			}

			Map<String, String> itemByTraitId = new LinkedHashMap<>();
			boolean valid = true;
			for (String traitId : section.getKeys(false)) {
				if (traitId == null || traitId.isBlank()) {
					continue;
				}
				String itemPath = section.getString(traitId);
				if (itemPath == null || itemPath.isBlank()) {
					RPCharacters.plugin.getLogger().warning(
							"Prosthetic '" + traitId + "' for '" + injuryId + "' has no item path and was skipped.");
					valid = false;
					break;
				}
				Trait prostheticTrait = TraitLoader.getByString(traitId);
				if (prostheticTrait == null) {
					RPCharacters.plugin.getLogger().warning(
							"Prosthetic '" + traitId + "' for '" + injuryId + "' is unknown and was skipped.");
					valid = false;
					break;
				}
				if (!prostheticTrait.getTraitData().isProstheticKey()) {
					RPCharacters.plugin.getLogger().warning(
							"Prosthetic '" + traitId + "' for '" + injuryId
									+ "' is not key prosthetic and was skipped.");
					valid = false;
					break;
				}
				if (prostheticTrait.hasFuelTemplate()) {
					FuelTemplate template = FuelTemplateLoader.getByString(prostheticTrait.getFuelTemplateId());
					if (template == null) {
						RPCharacters.plugin.getLogger().warning(
								"Prosthetic '" + traitId + "' references unknown fuel template '"
										+ prostheticTrait.getFuelTemplateId() + "' and was skipped.");
						valid = false;
						break;
					}
					if (prostheticTrait.getFuelCapacity() <= 0) {
						RPCharacters.plugin.getLogger().warning(
								"Prosthetic '" + traitId + "' has invalid fuel-capacity and was skipped.");
						valid = false;
						break;
					}
					if (!prostheticTrait.hasPoweredVariant() || prostheticTrait.getDepoweredVariant() == null) {
						RPCharacters.plugin.getLogger().warning(
								"Prosthetic '" + traitId + "' is fueled but missing powered/depowered blocks.");
						valid = false;
						break;
					}
				}
				String traitKey = traitId.toLowerCase(Locale.ROOT);
				if (byProstheticId.containsKey(traitKey)) {
					RPCharacters.plugin.getLogger().warning(
							"Prosthetic '" + traitId + "' is already mapped and was skipped.");
					valid = false;
					break;
				}
				itemByTraitId.put(traitKey, itemPath);
			}

			if (!valid || itemByTraitId.isEmpty()) {
				continue;
			}

			String injuryKey = injuryId.toLowerCase(Locale.ROOT);
			ProstheticReplacement replacement = new ProstheticReplacement(injuryKey, itemByTraitId);
			byInjuryId.put(injuryKey, replacement);
			loadOrder.add(replacement);
			for (String traitKey : itemByTraitId.keySet()) {
				byProstheticId.put(traitKey, replacement);
			}
		}
	}

	public static ProstheticReplacement getReplacement(String permanentInjuryId) {
		if (permanentInjuryId == null || permanentInjuryId.isBlank()) {
			return null;
		}
		return byInjuryId.get(permanentInjuryId.toLowerCase(Locale.ROOT));
	}

	public static ProstheticReplacement getReplacementForProsthetic(String prostheticTraitId) {
		if (prostheticTraitId == null || prostheticTraitId.isBlank()) {
			return null;
		}
		return byProstheticId.get(prostheticTraitId.toLowerCase(Locale.ROOT));
	}

	public static ProstheticInstallMatch resolveForItem(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return null;
		}
		for (ProstheticReplacement replacement : loadOrder) {
			for (Map.Entry<String, String> entry : replacement.getItemByTraitId().entrySet()) {
				if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, entry.getValue())) {
					return new ProstheticInstallMatch(replacement, entry.getKey(), entry.getValue());
				}
			}
		}
		return null;
	}
}
