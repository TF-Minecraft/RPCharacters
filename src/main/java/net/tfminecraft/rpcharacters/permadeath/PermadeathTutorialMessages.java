package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PermadeathTutorialMessages {

	private static final String GOT_IT_LABEL = "[Got It]";
	private static final int SEPARATOR_WIDTH = 40;

	private PermadeathTutorialMessages() {
	}

	public static void send(Player player) {
		RPTexts.send(player, RPTexts.separator());

		for (String line : PermadeathZoneLoader.getTutorialLines()) {
			player.sendMessage(formatTutorialLine(line));
		}

		int sideDashes = (SEPARATOR_WIDTH - GOT_IT_LABEL.length()) / 2;
		String dashes = "-".repeat(sideDashes);

		Component left = Component.text(dashes, NamedTextColor.GRAY);

		// ComponentBuilder carried bold into both the newline and the italic second line.
		Component hover = Component.text("Click to dismiss", NamedTextColor.GREEN)
				.decorate(TextDecoration.BOLD)
				.append(Component.text("\nYou won't see this tutorial again.", NamedTextColor.GRAY)
						.decorate(TextDecoration.BOLD, TextDecoration.ITALIC));
		Component gotIt = Component.text(GOT_IT_LABEL, NamedTextColor.GREEN)
				.decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
				.clickEvent(ClickEvent.runCommand("/rpcharacter dismisspdwarning"))
				.hoverEvent(HoverEvent.showText(hover));

		Component right = Component.text(dashes, NamedTextColor.GRAY);

		Component row = Component.empty().append(left).append(gotIt).append(right);
		player.sendMessage(row);
	}

	private static String formatTutorialLine(String line) {
		if (line == null || line.isEmpty()) {
			return line;
		}
		return StringFormatter.formatHex(line.replace('&', '\u00A7'));
	}
}
