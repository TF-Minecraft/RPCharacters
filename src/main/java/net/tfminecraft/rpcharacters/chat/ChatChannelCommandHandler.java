package net.tfminecraft.rpcharacters.chat;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ChatChannelCommandHandler implements CommandExecutor, TabCompleter {

	private static final String CHANNEL_COMMAND = "channel";
	private static final String TOGGLE_COMMAND = "channeltoggle";

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, Cache.chatRunAsPlayerMessage);
			return true;
		}

		String name = command.getName().toLowerCase(Locale.ROOT);
		if (CHANNEL_COMMAND.equals(name)) {
			return handleChannelSwitch(player, label, args);
		}
		if (TOGGLE_COMMAND.equals(name)) {
			return handleChannelToggle(player, label, args);
		}
		return true;
	}

	private boolean handleChannelSwitch(Player player, String label, String[] args) {
		if (!Cache.chatChannelSwitcherEnabled) {
			RPTexts.send(player, Cache.chatChannelSwitchDisabledMessage);
			return true;
		}

		if (args == null || args.length == 0) {
			ChatChannel active = ChatChannelPreferenceManager.get().getActiveChannel(player);
			String activeId = active != null ? active.getId() : Cache.chatDefaultChannel;
			RPTexts.send(player, Cache.chatChannelCurrentMessage.replace("{channel}", activeId));
			return true;
		}

		if (args.length != 1) {
			RPTexts.send(player, Cache.chatChannelInvalidUseMessage.replace("{usage}", label + " <channel>"));
			return true;
		}

		String channelId = resolveChannelId(args[0]);
		if (channelId == null || !Cache.chatSwitchableChannels.contains(channelId)) {
			RPTexts.send(player, Cache.chatChannelInvalidChannelMessage
					.replace("{channels}", String.join(", ", Cache.chatSwitchableChannels)));
			return true;
		}

		ChatChannel channel = ChatLoader.getChannel(channelId);
		if (channel == null) {
			RPTexts.send(player, Cache.chatChannelInvalidChannelMessage
					.replace("{channels}", String.join(", ", Cache.chatSwitchableChannels)));
			return true;
		}

		String current = ChatChannelPreferenceManager.get().getSwitchedChannelId(player);
		if (channelId.equals(current) || (current == null && channelId.equals(Cache.chatDefaultChannel))) {
			RPTexts.send(player, Cache.chatChannelAlreadySwitchedMessage);
			return true;
		}

		ChatChannelPreferenceManager.get().setSwitchedChannel(player, channelId);
		RPTexts.send(player, Cache.chatChannelSwitchedMessage.replace("{channel}", channelId));
		return true;
	}

	private boolean handleChannelToggle(Player player, String label, String[] args) {
		if (!Cache.chatChannelTogglerEnabled) {
			RPTexts.send(player, Cache.chatChannelToggleDisabledMessage);
			return true;
		}

		if (args == null || args.length != 1) {
			RPTexts.send(player, Cache.chatChannelInvalidUseMessage.replace("{usage}", label + " <channel>"));
			return true;
		}

		String channelId = resolveChannelId(args[0]);
		if (channelId == null || !Cache.chatToggleableChannels.contains(channelId)) {
			RPTexts.send(player, Cache.chatChannelInvalidChannelMessage
					.replace("{channels}", String.join(", ", Cache.chatToggleableChannels)));
			return true;
		}

		if (ChatLoader.getChannel(channelId) == null) {
			RPTexts.send(player, Cache.chatChannelInvalidChannelMessage
					.replace("{channels}", String.join(", ", Cache.chatToggleableChannels)));
			return true;
		}

		boolean visible = ChatChannelPreferenceManager.get().toggleChannelVisibility(player, channelId);
		String template = visible
				? Cache.chatChannelToggledOnMessage
				: Cache.chatChannelToggledOffMessage;
		RPTexts.send(player, template.replace("{channel}", channelId));
		return true;
	}

	private static String resolveChannelId(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		String normalized = input.toLowerCase(Locale.ROOT);
		if (ChatLoader.getChannel(normalized) != null) {
			return normalized;
		}
		String fromCommand = ChatLoader.resolveChannelFromCommand(normalized);
		return fromCommand != null ? fromCommand : null;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
		if (args.length != 1) {
			return List.of();
		}
		String name = command.getName().toLowerCase(Locale.ROOT);
		List<String> options = CHANNEL_COMMAND.equals(name)
				? Cache.chatSwitchableChannels
				: TOGGLE_COMMAND.equals(name) ? Cache.chatToggleableChannels : List.of();
		String prefix = args[0].toLowerCase(Locale.ROOT);
		return options.stream()
				.filter(option -> option.startsWith(prefix))
				.collect(Collectors.toList());
	}
}
