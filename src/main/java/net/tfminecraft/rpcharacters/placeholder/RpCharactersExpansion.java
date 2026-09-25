package net.tfminecraft.rpcharacters.placeholder;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.PersonaService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

public final class RpCharactersExpansion extends PlaceholderExpansion {

	@Override
	public String getIdentifier() {
		return "rpcharacters";
	}

	@Override
	public String getAuthor() {
		return "Drefvelin";
	}

	@Override
	public String getVersion() {
		return RPCharacters.plugin.getPluginMeta().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public String onRequest(OfflinePlayer offlinePlayer, String params) {
		if (params == null) {
			return null;
		}
		Player player = offlinePlayer != null ? offlinePlayer.getPlayer() : null;
		if (player == null) {
			return resolveOffline(params);
		}
		return resolve(player, params);
	}

	private String resolveOffline(String params) {
		switch (params.toLowerCase()) {
			case "name":
			case "display":
			case "display_tab":
			case "display_safe":
				return Cache.personaNoCharacterFallback;
			case "gender":
				return Cache.personaGenderDefault;
			case "race":
			case "age":
				return Cache.calendarAgeUnsetLabel;
			case "birthday":
				return Cache.calendarAgeUnsetLabel;
			case "description":
			default:
				return "";
		}
	}

	private String resolve(Player player, String params) {
		switch (params.toLowerCase()) {
			case "name": {
				String name = DisplayIdentityService.resolveCharacterName(player);
				return name.isEmpty() ? Cache.personaNoCharacterFallback : name;
			}
			case "display":
				return DisplayIdentityService.resolveDisplay(player);
			case "display_tab":
				return DisplayIdentityService.resolveDisplayTab(player);
			case "display_safe":
				return DisplayIdentityService.resolveDisplaySafe(player);
			case "race":
				return PersonaService.resolveRace(player);
			case "gender":
				return PersonaService.resolveGender(player);
			case "age":
				return PersonaService.resolveAge(player);
			case "birthday":
				return PersonaService.resolveBirthday(player);
			case "description":
				return PersonaService.resolveDescription(player);
			case "evilrp_strikes": {
				PlayerData pd = PlayerManager.get(player);
				return pd != null && pd.hasActiveCharacter()
						? String.valueOf(pd.getActiveCharacter().getEvilRpStrikes()) : "0";
			}
			case "evilrp_in_session":
				return String.valueOf(EvilRpService.isInSession(player));
			case "evilrp_session_left": {
				PlayerData pd = PlayerManager.get(player);
				RPCharacter active = pd != null && pd.hasActiveCharacter() ? pd.getActiveCharacter() : null;
				return EvilRpService.isInSession(active)
						? EvilRpService.formatRemaining(EvilRpService.remainingMs(active)) : "";
			}
			default:
				return null;
		}
	}
}
