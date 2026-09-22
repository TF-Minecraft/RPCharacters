package net.tfminecraft.rpcharacters.chat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ChatChannelExecutor implements CommandExecutor {

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, RPTexts.ERROR + "Players only.");
			return true;
		}

		String raw = "/" + label;
		if (args != null && args.length > 0) {
			raw = raw + " " + String.join(" ", args);
		}

		ParsedChannelCommand parsed = ChatChannelCommandParser.parse(raw);
		if (parsed == null) {
			return true;
		}

		if (!parsed.hasMessage()) {
			RPTexts.send(player, RPTexts.ERROR + "Usage: /" + parsed.label() + " <message>");
			return true;
		}

		ChatManager.dispatch(player, parsed.channel(), parsed.message(), true);
		return true;
	}
}
