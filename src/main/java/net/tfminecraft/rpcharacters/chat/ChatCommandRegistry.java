package net.tfminecraft.rpcharacters.chat;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.loaders.ChatLoader;

public final class ChatCommandRegistry {

	private static final ChatChannelExecutor EXECUTOR = new ChatChannelExecutor();
	private static final Set<PluginCommand> registered = new HashSet<>();

	private ChatCommandRegistry() {}

	public static void sync(Plugin plugin) {
		if (!unregisterAll()) return;
		List<String> labels = ChatLoader.getChannelCommands();
		for (String label : labels) {
			registerCommand(plugin, label);
		}
	}

	private static void registerCommand(Plugin plugin, String label) {
		String normalized = label.toLowerCase(Locale.ROOT);
		if (registered.stream().anyMatch(command -> command.getName().equals(normalized))) {
			return;
		}
		try {
			Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
			constructor.setAccessible(true);
			PluginCommand command = constructor.newInstance(normalized, plugin);
			command.setExecutor(EXECUTOR);
			command.setDescription("RP chat channel");
			Bukkit.getCommandMap().register(plugin.getName().toLowerCase(Locale.ROOT), command);
			registered.add(command);
		} catch (ReflectiveOperationException | RuntimeException e) {
			plugin.getLogger().warning("Failed to register chat command /" + label + ": " + e.getMessage());
		}
	}

	private static boolean unregisterAll() {
		if (registered.isEmpty()) {
			return true;
		}
		try {
			CommandMap commandMap = Bukkit.getCommandMap();
			var knownCommands = commandMap.getKnownCommands();
			List<String> ownedLabels = knownCommands.entrySet().stream()
					.filter(entry -> registered.contains(entry.getValue())).map(java.util.Map.Entry::getKey).toList();
			ownedLabels.forEach(knownCommands::remove);
			for (PluginCommand command : registered) {
				command.unregister(commandMap);
			}
			registered.clear();
			return true;
		} catch (RuntimeException e) {
			Bukkit.getLogger().warning("[RPCharacters] Failed to unregister chat commands: " + e.getMessage());
			return false;
		}
	}
}
