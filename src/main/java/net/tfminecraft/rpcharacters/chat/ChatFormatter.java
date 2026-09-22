package net.tfminecraft.rpcharacters.chat;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.MaskService;

public final class ChatFormatter {

	private ChatFormatter() {}

	public static String format(ChatChannel channel, Player sender, String displayName, String message) {
		if (channel == null || sender == null) {
			return "";
		}
		String template = channel.getFormat();
		if (template == null || template.isEmpty()) {
			return "";
		}

		String displayToken;
		String playerToken;
		if (channel.isMasked() && MaskService.isMasked(sender)) {
			String maskedLabel = MaskService.getMaskedLabel();
			displayToken = maskedLabel;
			playerToken = maskedLabel;
		} else {
			displayToken = displayName != null && !displayName.isEmpty()
				? displayName
				: DisplayIdentityService.resolveDisplayUnmasked(sender);
			playerToken = sender.getName();
		}

		String withTokens = template
				.replace("{display}", displayToken)
				.replace("{display_tab}", DisplayIdentityService.resolveDisplayTab(sender))
				.replace("{player}", playerToken)
				.replace("{message}", message != null ? message : "");
		return StringFormatter.formatHex(withTokens.replace('&', '\u00A7'));
	}
}
