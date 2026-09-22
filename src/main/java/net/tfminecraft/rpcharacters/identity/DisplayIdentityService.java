package net.tfminecraft.rpcharacters.identity;

import java.util.Collections;
import java.util.List;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class DisplayIdentityService {

	private DisplayIdentityService() {}

	public static String resolveCharacterName(Player player) {
		RPCharacter character = getActiveCharacter(player);
		return character != null && character.getName() != null ? character.getName() : "";
	}

	public static String resolveCharacterName(RPCharacter character) {
		return character != null && character.getName() != null ? character.getName() : "";
	}

	public static String resolveEffectivePlain(Player player) {
		RPCharacter character = getActiveCharacter(player);
		return resolveEffectivePlain(character);
	}

	public static String resolveEffectivePlain(RPCharacter character) {
		if (character == null) {
			return "";
		}
		return character.getEffectiveDisplayPlain();
	}

	public static String resolveDisplayTab(Player player) {
		RPCharacter character = getActiveCharacter(player);
		return resolveDisplayTab(character);
	}

	/** Alias or name with colour; never mask or temp alias. */
	public static String resolveDisplayTab(RPCharacter character) {
		if (character == null) {
			return Cache.personaNoCharacterFallback;
		}
		return formatColouredDisplay(character);
	}

	public static String resolveDisplaySafe(Player player) {
		PlayerData data = getPlayerData(player);
		if (data == null) {
			return Cache.personaNoCharacterFallback;
		}
		RPCharacter active = data.getActiveCharacter();
		RPCharacter displayChar = active;
		if (active != null && active.isHidden()) {
			displayChar = data.findFacadeCharacter();
		}
		if (displayChar == null) {
			return Cache.personaNoCharacterFallback;
		}
		return formatColouredDisplay(displayChar);
	}

	public static String resolveDisplay(Player player) {
		if (MaskService.isMasked(player)) {
			return MaskService.getMaskedLabel();
		}
		return resolveDisplayUnmasked(player);
	}

	/** Chat / placeholders when mask should not apply (non-masked channels). */
	public static String resolveDisplayUnmasked(Player player) {
		String tempAlias = TempAliasService.getPlain(player);
		if (!tempAlias.isEmpty()) {
			RPCharacter active = getActiveCharacter(player);
			if (active != null) {
				return applyNameColour(tempAlias, active);
			}
			return tempAlias;
		}
		return resolveDisplayTab(player);
	}

	public static String resolveDisplay(RPCharacter character) {
		if (character != null && character.getOwner() != null && MaskService.isMasked(character.getOwner())) {
			return MaskService.getMaskedLabel();
		}
		return resolveDisplayUnmasked(character);
	}

	public static String resolveDisplayUnmasked(RPCharacter character) {
		if (character != null && character.getOwner() != null) {
			String tempAlias = TempAliasService.getPlain(character.getOwner());
			if (!tempAlias.isEmpty()) {
				return applyNameColour(tempAlias, character);
			}
		}
		return resolveDisplayTab(character);
	}

	public static boolean isMasked(Player player) {
		return MaskService.isMasked(player);
	}

	private static String formatColouredDisplay(RPCharacter character) {
		String plain = character.getEffectiveDisplayPlain();
		return colourPlain(plain, character.getNameColour());
	}

	public static String colourPlain(String plain, NameColour colour) {
		if (plain == null || plain.isEmpty()) {
			return "";
		}
		List<String> hexCodes = colour != null ? colour.getHexCodes() : Collections.emptyList();
		String coloured = StringFormatter.applyColourGradient(ClueFormatter.stripColor(plain), hexCodes);
		return prefixDisplayTabWhite(coloured);
	}

	/**
	 * Leading white reset so GUI item names (skulls, etc.) are not italic and
	 * plain names without a gradient still render correctly.
	 */
	public static String ensureDisplayTabWhite(String displayTab) {
		return prefixDisplayTabWhite(displayTab);
	}

	private static String prefixDisplayTabWhite(String coloured) {
		if (coloured == null || coloured.isEmpty()) {
			return coloured == null ? "" : coloured;
		}
		if (coloured.startsWith(RPTexts.WHITE)) {
			return coloured;
		}
		return RPTexts.WHITE + coloured;
	}

	private static String applyNameColour(String plain, RPCharacter character) {
		NameColour colour = character.getNameColour();
		List<String> hexCodes = colour != null ? colour.getHexCodes() : Collections.emptyList();
		return StringFormatter.applyColourGradient(ClueFormatter.stripColor(plain), hexCodes);
	}

	private static RPCharacter getActiveCharacter(Player player) {
		PlayerData data = getPlayerData(player);
		return data != null ? data.getActiveCharacter() : null;
	}

	private static PlayerData getPlayerData(Player player) {
		if (player == null) {
			return null;
		}
		return PlayerManager.get(player);
	}
}
