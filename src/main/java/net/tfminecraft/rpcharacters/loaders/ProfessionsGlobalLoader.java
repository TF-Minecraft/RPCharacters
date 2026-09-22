package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.professions.ProfessionItemType;
import net.tfminecraft.rpcharacters.professions.ProfessionRegistry;

public class ProfessionsGlobalLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}
		Cache.professionMaxSpendingPoints = config.getInt("max_spending_points", 40);
		Cache.professionPermContext = config.getString("perm_context", "main");
		Cache.professionLockedBreeding = config.getStringList("lock_breeding");
		Cache.professionBreedingExp = config.getStringList("breeding_exp");
		Cache.professionAdminDebugMessages = config.getBoolean("admin-debug-messages", false);

		List<ProfessionItemType> types = new ArrayList<>();
		ConfigurationSection typesSection = config.getConfigurationSection("types");
		if (typesSection != null) {
			for (String key : typesSection.getKeys(false)) {
				types.add(new ProfessionItemType(key, typesSection.getStringList(key + ".mmoitem_types")));
			}
		}
		Cache.professionItemTypes = types;
		ProfessionRegistry.setItemTypes(types);
	}
}
