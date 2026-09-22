package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.WebCreatorRealmAccess;

public final class WebCreatorLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		Map<String, WebCreatorRealmAccess> byRealm = new HashMap<>();
		if (config.isConfigurationSection("by-realm")) {
			ConfigurationSection section = config.getConfigurationSection("by-realm");
			for (String realmId : section.getKeys(false)) {
				ConfigurationSection realmSection = section.getConfigurationSection(realmId);
				if (realmSection == null) {
					continue;
				}
				int minTier = Math.max(0, realmSection.getInt("min-tier", 0));
				String minGroupId = realmSection.getString("min-group-id", "").trim();
				byRealm.put(
					realmId.trim().toLowerCase(Locale.ROOT),
					new WebCreatorRealmAccess(minTier, minGroupId)
				);
			}
		}
		Cache.webCreatorAccessByRealm = byRealm;
	}
}
