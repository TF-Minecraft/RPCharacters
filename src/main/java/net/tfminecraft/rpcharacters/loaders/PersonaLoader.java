package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.Cache;

public class PersonaLoader implements LoaderInterface {

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		if (config.isConfigurationSection("permissions")) {
			Cache.personaSetPermission = config.getString("permissions.set", Cache.personaSetPermission);
			Cache.personaNamecolourPermission = config.getString("permissions.namecolour", Cache.personaNamecolourPermission);
			Cache.personaDescriptionColorsPermission = config.getString("permissions.description-colors",
					Cache.personaDescriptionColorsPermission);
			Cache.personaOverridePermission = config.getString("permissions.override", Cache.personaOverridePermission);
			Cache.personaBypassCooldownPermission = config.getString("permissions.bypass-cooldown",
					Cache.personaBypassCooldownPermission);
			Cache.personaTempaliasPermission = config.getString("permissions.tempalias", Cache.personaTempaliasPermission);
			Cache.personaCharacterHiddenPermission = config.getString("permissions.character-hidden",
					Cache.personaCharacterHiddenPermission);
		}

		Cache.personaNoCharacterFallback = StringFormatter.formatHex(config.getString("no-character-fallback", ""));

		if (config.isConfigurationSection("display-name")) {
			Cache.personaDisplayNameMinLength = config.getInt("display-name.length-minimum",
					Cache.personaDisplayNameMinLength);
			Cache.personaDisplayNameMaxLength = config.getInt("display-name.length-maximum",
					Cache.personaDisplayNameMaxLength);
		}
		Cache.personaAliasMinLength = Cache.personaDisplayNameMinLength;
		Cache.personaAliasMaxLength = Cache.personaDisplayNameMaxLength;

		if (config.isConfigurationSection("alias")) {
			if (config.isConfigurationSection("alias.length")) {
				Cache.personaAliasMinLength = config.getInt("alias.length.minimum", Cache.personaAliasMinLength);
				Cache.personaAliasMaxLength = config.getInt("alias.length.maximum", Cache.personaAliasMaxLength);
			}
			Cache.personaAliasAllowedChars = config.getString("alias.allowed-chars", Cache.personaAliasAllowedChars);
			Cache.personaAliasCooldownSeconds = config.getInt("alias.cooldown-seconds", Cache.personaAliasCooldownSeconds);
		}

		if (config.isConfigurationSection("gender")) {
			List<String> genders = config.getStringList("gender.values");
			Cache.personaGenders = genders != null ? new ArrayList<>(genders) : new ArrayList<>();
			Cache.personaGenderDefault = config.getString("gender.default", Cache.personaGenderDefault);
			Cache.personaGenderCooldownSeconds = config.getInt("gender.cooldown-seconds", Cache.personaGenderCooldownSeconds);
		}

		if (config.isConfigurationSection("description")) {
			Cache.personaDescriptionMinLength = config.getInt("description.length-minimum", Cache.personaDescriptionMinLength);
			Cache.personaDescriptionCooldownSeconds = config.getInt("description.cooldown-seconds",
					Cache.personaDescriptionCooldownSeconds);
			Cache.personaDescriptionDefaultTemplate = config.getString("description.default-template",
					Cache.personaDescriptionDefaultTemplate);
		}
	}
}
