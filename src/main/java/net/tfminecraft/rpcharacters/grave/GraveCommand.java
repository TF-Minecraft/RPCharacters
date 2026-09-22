package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class GraveCommand implements CommandExecutor, TabCompleter {

	public static final String COMMAND = "grave";

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!COMMAND.equalsIgnoreCase(command.getName())) {
			return false;
		}
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, RPTexts.ERROR + "Only players can use this command.");
			return true;
		}
		if (args.length == 0 || !args[0].equalsIgnoreCase("unlock")) {
			GraveRecover.sendMessage(player, "&eUsage: /grave unlock");
			return true;
		}
		Grave grave = GraveManager.get().findNewestByOwner(player.getUniqueId());
		if (grave == null) {
			GraveRecover.sendMessage(player, GraveLoader.getMessageUnlockNone());
			return true;
		}
		if (!grave.isLocked()) {
			GraveRecover.sendMessage(player, GraveLoader.getMessageUnlockAlready());
			return true;
		}
		grave.setLocked(false);
		grave.flush();
		GraveRecover.sendMessage(player, GraveLoader.getMessageUnlockSuccess());
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			List<String> options = new ArrayList<>();
			String prefix = args[0].toLowerCase(Locale.ROOT);
			if ("unlock".startsWith(prefix)) {
				options.add("unlock");
			}
			return options;
		}
		return Collections.emptyList();
	}
}
