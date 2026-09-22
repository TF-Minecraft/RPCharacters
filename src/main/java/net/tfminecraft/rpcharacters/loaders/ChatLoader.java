package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.ChatCommandRegistry;

public final class ChatLoader implements LoaderInterface {

	private static final Map<String, ChatChannel> channels = new HashMap<>();
	private static final Map<String, String> commandToChannel = new HashMap<>();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		channels.clear();
		commandToChannel.clear();

		Cache.chatBypassCooldownPermission = config.getString("bypass-cooldown-perm",
				Cache.chatBypassCooldownPermission);
		Cache.chatDefaultChannel = config.getString("default", Cache.chatDefaultChannel);
		Cache.chatNoCharacterMessage = config.getString("no-character-message", Cache.chatNoCharacterMessage);

		loadChannelSwitcher(config);
		loadChannelToggler(config);
		loadMessages(config);

		if (!config.isConfigurationSection("channels")) {
			syncCommands();
			return;
		}

		ConfigurationSection section = config.getConfigurationSection("channels");
		for (String key : section.getKeys(false)) {
			ConfigurationSection channelSection = section.getConfigurationSection(key);
			if (channelSection == null) {
				continue;
			}
			ChatChannel channel = new ChatChannel(key, channelSection);
			String channelId = key.toLowerCase(Locale.ROOT);
			channels.put(channelId, channel);
			for (String command : channel.getCommands()) {
				if (command != null && !command.isBlank()) {
					commandToChannel.put(command.toLowerCase(Locale.ROOT), channelId);
				}
			}
		}

		syncCommands();
	}

	private static void loadChannelSwitcher(FileConfiguration config) {
		ConfigurationSection section = config.getConfigurationSection("channel-switcher");
		if (section == null) {
			if (Cache.chatSwitchableChannels.isEmpty()) {
				Cache.chatSwitchableChannels = List.of("rp", "ooc", "looc", "whisper", "shout", "yell", "action");
			}
			return;
		}
		Cache.chatChannelSwitcherEnabled = section.getBoolean("enabled", true);
		List<String> channels = normalizeChannelIds(section.getStringList("switchable-channels"));
		if (!channels.isEmpty()) {
			Cache.chatSwitchableChannels = channels;
		} else if (Cache.chatSwitchableChannels.isEmpty()) {
			Cache.chatSwitchableChannels = List.of("rp", "ooc", "looc", "whisper", "shout", "yell", "action");
		}
	}

	private static void loadChannelToggler(FileConfiguration config) {
		ConfigurationSection section = config.getConfigurationSection("channel-toggler");
		if (section == null) {
			if (Cache.chatToggleableChannels.isEmpty()) {
				Cache.chatToggleableChannels = List.of("looc", "ooc", "helper", "admin");
			}
			return;
		}
		Cache.chatChannelTogglerEnabled = section.getBoolean("enabled", true);
		List<String> channels = normalizeChannelIds(section.getStringList("toggleable-channels"));
		if (!channels.isEmpty()) {
			Cache.chatToggleableChannels = channels;
		} else if (Cache.chatToggleableChannels.isEmpty()) {
			Cache.chatToggleableChannels = List.of("looc", "ooc", "helper", "admin");
		}
	}

	private static void loadMessages(FileConfiguration config) {
		ConfigurationSection section = config.getConfigurationSection("messages");
		if (section == null) {
			return;
		}
		Cache.chatRunAsPlayerMessage = section.getString("run-as-player", Cache.chatRunAsPlayerMessage);
		Cache.chatChannelSwitchDisabledMessage = section.getString("channel-switch-disabled",
				Cache.chatChannelSwitchDisabledMessage);
		Cache.chatChannelToggleDisabledMessage = section.getString("channel-toggle-disabled",
				Cache.chatChannelToggleDisabledMessage);
		Cache.chatChannelInvalidUseMessage = section.getString("invalid-use", Cache.chatChannelInvalidUseMessage);
		Cache.chatChannelInvalidChannelMessage = section.getString("invalid-channel",
				Cache.chatChannelInvalidChannelMessage);
		Cache.chatChannelAlreadySwitchedMessage = section.getString("already-switched",
				Cache.chatChannelAlreadySwitchedMessage);
		Cache.chatChannelSwitchedMessage = section.getString("switched", Cache.chatChannelSwitchedMessage);
		Cache.chatChannelCurrentMessage = section.getString("current-channel", Cache.chatChannelCurrentMessage);
		Cache.chatChannelToggledOnMessage = section.getString("toggled-on", Cache.chatChannelToggledOnMessage);
		Cache.chatChannelToggledOffMessage = section.getString("toggled-off", Cache.chatChannelToggledOffMessage);
		Cache.chatChannelCantUseWhenToggledOffMessage = section.getString("cant-use-when-toggled-off",
				Cache.chatChannelCantUseWhenToggledOffMessage);
	}

	private static List<String> normalizeChannelIds(List<String> channelIds) {
		List<String> normalized = new ArrayList<>();
		if (channelIds == null) {
			return normalized;
		}
		for (String channelId : channelIds) {
			if (channelId == null || channelId.isBlank()) {
				continue;
			}
			String lower = channelId.toLowerCase(Locale.ROOT);
			if (!normalized.contains(lower)) {
				normalized.add(lower);
			}
		}
		return List.copyOf(normalized);
	}

	private static void syncCommands() {
		if (RPCharacters.plugin != null) {
			ChatCommandRegistry.sync(RPCharacters.plugin);
		}
	}

	public static ChatChannel getChannel(String channelId) {
		if (channelId == null) {
			return null;
		}
		return channels.get(channelId.toLowerCase(Locale.ROOT));
	}

	public static String resolveChannelFromCommand(String commandLabel) {
		if (commandLabel == null) {
			return null;
		}
		return commandToChannel.get(commandLabel.toLowerCase(Locale.ROOT));
	}

	public static ChatChannel getDefaultChannel() {
		return getChannel(Cache.chatDefaultChannel);
	}

	public static List<String> getChannelCommands() {
		List<String> commands = new ArrayList<>();
		for (ChatChannel channel : channels.values()) {
			for (String command : channel.getCommands()) {
				if (command == null || command.isBlank()) {
					continue;
				}
				String normalized = command.toLowerCase(Locale.ROOT);
				if (!commands.contains(normalized)) {
					commands.add(normalized);
				}
			}
		}
		String defaultChannel = Cache.chatDefaultChannel != null
				? Cache.chatDefaultChannel.toLowerCase(Locale.ROOT)
				: "rp";
		commands.sort((a, b) -> {
			if (a.equals(defaultChannel)) {
				return -1;
			}
			if (b.equals(defaultChannel)) {
				return 1;
			}
			return a.compareTo(b);
		});
		return List.copyOf(commands);
	}
}
