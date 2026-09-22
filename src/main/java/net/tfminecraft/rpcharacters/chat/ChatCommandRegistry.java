package net.tfminecraft.rpcharacters.chat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
	private static final Set<String> registered = new HashSet<>();

	private ChatCommandRegistry() {}

	public static void sync(Plugin plugin) {
		unregisterAll();
		List<String> labels = ChatLoader.getChannelCommands();
		for (String label : labels) {
			registerCommand(plugin, label);
		}
	}

	private static void registerCommand(Plugin plugin, String label) {
		String normalized = label.toLowerCase(Locale.ROOT);
		if (registered.contains(normalized)) {
			return;
		}
		try {
			Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
			constructor.setAccessible(true);
			PluginCommand command = constructor.newInstance(normalized, plugin);
			command.setExecutor(EXECUTOR);
			command.setDescription("RP chat channel");
			getCommandMap().register(plugin.getName().toLowerCase(Locale.ROOT), command);
			registered.add(normalized);
		} catch (ReflectiveOperationException e) {
			plugin.getLogger().warning("Failed to register chat command /" + label + ": " + e.getMessage());
		}
	}

	private static void unregisterAll() {
		if (registered.isEmpty()) {
			return;
		}
		try {
			CommandMap commandMap = getCommandMap();
			Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
			knownCommandsField.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Command> knownCommands = (java.util.Map<String, Command>) knownCommandsField.get(commandMap);
			for (String label : new HashSet<>(registered)) {
				knownCommands.remove(label);
			}
		} catch (ReflectiveOperationException e) {
			Bukkit.getLogger().warning("[RPCharacters] Failed to unregister chat commands: " + e.getMessage());
		}
		registered.clear();
	}

	private static CommandMap getCommandMap() throws ReflectiveOperationException {
		Method method = Bukkit.getServer().getClass().getMethod("getCommandMap");
		return (CommandMap) method.invoke(Bukkit.getServer());
	}
}
