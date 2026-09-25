package net.tfminecraft.rpcharacters.profile;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.PersonaService;

public final class ProfileFormatter {

	public static final List<String> DEFAULT_SHEET_FORMAT = List.of(
			"&fGender: &e{gender}",
			"&fAge: &e{age}",
			"&fBirthday: &e{birthday}",
			"&fRace: &e{race}",
			"",
			"&fDescription:",
			"&7{description}");

	private ProfileFormatter() {}

	public static List<String> format(RPCharacter character) {
		return format(character, Cache.profileFormatLines);
	}

	public static List<String> formatSheet(RPCharacter character) {
		return format(character, Cache.profileSheetFormatLines);
	}

	private static List<String> format(RPCharacter character, List<String> templates) {
		List<String> lines = new ArrayList<>();
		for (String template : templates) {
			if (template == null) {
				lines.add("");
				continue;
			}
			String withTokens = template
					.replace("{display_tab}", DisplayIdentityService.resolveDisplayTab(character))
					.replace("{gender}", PersonaService.resolveGender(character))
					.replace("{age}", PersonaService.resolveAge(character))
					.replace("{birthday}", PersonaService.resolveBirthday(character))
					.replace("{race}", PersonaService.resolveRace(character))
					.replace("{description}", PersonaService.resolveDescription(character));
			lines.add(StringFormatter.formatHex(withTokens.replace('&', '\u00A7')));
		}
		return lines;
	}
}
