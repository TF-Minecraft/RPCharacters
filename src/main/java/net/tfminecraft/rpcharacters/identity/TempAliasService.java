package net.tfminecraft.rpcharacters.identity;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.persona.AliasValidator;

public final class TempAliasService {

	private TempAliasService() {}

	public static String getPlain(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data == null) {
			return "";
		}
		String alias = data.getTempAlias();
		return alias != null ? alias : "";
	}

	public static String set(Player player, String input) {
		String error = AliasValidator.validate(input, false);
		if (error != null) {
			return error;
		}
		return setPlain(player, input);
	}

	public static String setPlain(Player player, String input) {
		PlayerData data = PlayerManager.get(player);
		if (data == null) {
			return RPTexts.formatDisplay(RPTexts.ERROR + "Player data not loaded.");
		}
		data.setTempAlias(ClueFormatter.stripColor(input).trim());
		return null;
	}

	public static void clear(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data != null) {
			data.clearTempAlias();
		}
	}
}
