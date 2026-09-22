package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.professions.ProfessionDefinition;
import net.tfminecraft.rpcharacters.professions.ProfessionItemFactory;
import net.tfminecraft.rpcharacters.professions.ProfessionRegistry;
import net.tfminecraft.rpcharacters.professions.ProfessionUpgradeDefinition;

public class ProfessionLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		String id = configFile.getName().replace(".yml", "");
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			Bukkit.getLogger().severe("[RPCharacters] Failed to load profession file: " + configFile.getName());
			e.printStackTrace();
			return;
		}
		String name = config.getString("name", id);
		List<ProfessionUpgradeDefinition> upgradeDefs = new ArrayList<>();
		Set<String> seenUpgradeIds = new HashSet<>();
		ConfigurationSection upgradesSection = config.getConfigurationSection("upgrades");
		if (upgradesSection != null) {
			for (String upgradeKey : upgradesSection.getKeys(false)) {
				ConfigurationSection upgradeSection = upgradesSection.getConfigurationSection(upgradeKey);
				if (upgradeSection == null) {
					continue;
				}
				List<String> requirements = upgradeSection.contains("requires")
						? upgradeSection.getStringList("requires")
						: new ArrayList<>();
				ProfessionUpgradeDefinition upgrade = new ProfessionUpgradeDefinition(
						upgradeKey,
						id,
						ProfessionItemFactory.snapshot(upgradeSection.get("item")),
						upgradeSection.getInt("cost", 0),
						upgradeSection.getString("type", "permission"),
						requirements,
						upgradeSection.getStringList("unlocks"));
				upgradeDefs.add(upgrade);
				if (!seenUpgradeIds.add(upgradeKey.toLowerCase())) {
					Bukkit.getLogger().warning("[RPCharacters] Duplicate upgrade id in " + configFile.getName() + ": "
							+ upgradeKey);
				}
			}
		}
		ProfessionDefinition profession = new ProfessionDefinition(
				id,
				name,
				ProfessionItemFactory.snapshot(config.get("item")),
				upgradeDefs);
		List<ProfessionDefinition> professions = new ArrayList<>(ProfessionRegistry.getProfessions());
		professions.removeIf(p -> p.getId().equalsIgnoreCase(id));
		professions.add(profession);
		ProfessionRegistry.setProfessions(professions);

		List<ProfessionUpgradeDefinition> allUpgrades = new ArrayList<>();
		for (ProfessionDefinition loaded : professions) {
			allUpgrades.addAll(loaded.getUpgrades());
		}
		ProfessionRegistry.setUpgrades(allUpgrades);
	}

	public static void loadAll(File folder) {
		if (!folder.exists() || !folder.isDirectory()) {
			return;
		}
		File[] files = folder.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.isFile() && file.getName().endsWith(".yml")) {
				new ProfessionLoader().load(file);
			}
		}
	}

	public static void reload(File folder) {
		ProfessionRegistry.setProfessions(new ArrayList<>());
		ProfessionRegistry.setUpgrades(new ArrayList<>());
		loadAll(folder);
	}
}
