package net.tfminecraft.rpcharacters.profile;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.PersonaService;

public final class ProfileFormatter {

	private ProfileFormatter() {}

	public static List<String> format(Player target, RPCharacter character) {
		List<String> lines = new ArrayList<>();
		for (String template : Cache.profileFormatLines) {
			if (template == null) {
				lines.add("");
				continue;
			}
			String withTokens = template
					.replace("{display_tab}", DisplayIdentityService.resolveDisplayTab(target))
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
