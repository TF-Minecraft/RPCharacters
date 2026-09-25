package net.tfminecraft.rpcharacters.tutorial;

import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/**
 * Chat tutorials from tutorials.yml. Each one repeats until the player clicks [Got It],
 * which is remembered per player in their player data.
 */
public final class TutorialService {

	public static final String PERMADEATH_ZONE = "permadeath-zone";
	public static final String EVIL_RP = "evil-rp";
	public static final String PVP_STRIKES = "pvp-strikes";

	private static final String GOT_IT_LABEL = "[Got It]";
	private static final int SEPARATOR_WIDTH = 40;

	private TutorialService() {
	}

	/** Shows the tutorial unless the player dismissed it. Returns whether it was sent. */
	public static boolean show(Player player, String id) {
		return show(player, id, Map.of());
	}

	public static boolean show(Player player, String id, Map<String, String> placeholders) {
		if (player == null || isDismissed(player, id)) {
			return false;
		}
		List<String> lines = TutorialLoader.getLines(id);
		if (lines.isEmpty()) {
			return false;
		}
		RPTexts.send(player, RPTexts.separator());
		for (String line : lines) {
			player.sendMessage(formatLine(line, placeholders));
		}
		player.sendMessage(gotItRow(id));
		return true;
	}

	public static boolean isDismissed(Player player, String id) {
		PlayerData pd = PlayerManager.get(player);
		return pd != null && pd.hasDismissedTutorial(id);
	}

	public static boolean dismiss(Player player, String id) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !TutorialLoader.exists(id)) {
			return false;
		}
		pd.setTutorialDismissed(id, true);
		RPCharacters.getPlayerManager().savePlayer(player);
		return true;
	}

	static String formatLine(String line, Map<String, String> placeholders) {
		if (line == null || line.isEmpty()) {
			return line;
		}
		String filled = line;
		for (Map.Entry<String, String> entry : placeholders.entrySet()) {
			filled = filled.replace("{" + entry.getKey() + "}", entry.getValue());
		}
		return StringFormatter.formatHex(filled.replace('&', '§'));
	}

	private static Component gotItRow(String id) {
		String dashes = "-".repeat((SEPARATOR_WIDTH - GOT_IT_LABEL.length()) / 2);

		// ComponentBuilder carried bold into both the newline and the italic second line.
		Component hover = Component.text("Click to dismiss", NamedTextColor.GREEN)
				.decorate(TextDecoration.BOLD)
				.append(Component.text("\nYou won't see this tutorial again.", NamedTextColor.GRAY)
						.decorate(TextDecoration.BOLD, TextDecoration.ITALIC));
		Component gotIt = Component.text(GOT_IT_LABEL, NamedTextColor.GREEN)
				.decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
				.clickEvent(ClickEvent.runCommand("/rpcharacter tutorial dismiss " + id))
				.hoverEvent(HoverEvent.showText(hover));

		return Component.empty()
				.append(Component.text(dashes, NamedTextColor.GRAY))
				.append(gotIt)
				.append(Component.text(dashes, NamedTextColor.GRAY));
	}
}
