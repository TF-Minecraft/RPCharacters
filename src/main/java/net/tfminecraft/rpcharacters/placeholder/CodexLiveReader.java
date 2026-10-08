package net.tfminecraft.rpcharacters.placeholder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Online Codex accounts, read from Codex's own player map. Codex drops a player
 * from that map on quit, so this is only the live overlay on top of the saved files.
 */
final class CodexLiveReader {
	private CodexLiveReader() {}

	static Map<UUID, Map<String, Set<String>>> current() throws ReflectiveOperationException {
		PluginManager plugins = Bukkit.getPluginManager();
		if (plugins == null) {
			return Map.of();
		}
		Plugin plugin = plugins.getPlugin("Codex");
		if (plugin == null || !plugin.isEnabled()) {
			return Map.of();
		}
		return read(plugin);
	}

	static Map<UUID, Map<String, Set<String>>> read(Object plugin) throws ReflectiveOperationException {
		Object manager = plugin.getClass().getMethod("getPlayerDataManager").invoke(plugin);
		if (manager == null) {
			return Map.of();
		}
		Object raw = manager.getClass().getMethod("getPlayers").invoke(manager);
		if (!(raw instanceof Map<?, ?> players)) {
			return Map.of();
		}
		List<Object> values = new ArrayList<>();
		for (Object value : players.values()) {
			values.add(value);
		}
		Map<UUID, Map<String, Set<String>>> result = new HashMap<>();
		for (Object value : values) {
			if (value == null) {
				continue;
			}
			Object uuidRaw = value.getClass().getMethod("getUuid").invoke(value);
			if (!(uuidRaw instanceof UUID uuid)) {
				continue;
			}
			result.put(uuid, categoriesOf(value));
		}
		return result;
	}

	private static Map<String, Set<String>> categoriesOf(Object player) throws ReflectiveOperationException {
		Object raw = player.getClass().getMethod("getCategories").invoke(player);
		if (!(raw instanceof Iterable<?> categories)) {
			return Map.of();
		}
		Map<String, Set<String>> byCategory = new HashMap<>();
		for (Object category : categories) {
			if (category == null) {
				continue;
			}
			Object nameRaw = category.getClass().getMethod("getName").invoke(category);
			if (!(nameRaw instanceof String name) || name.isBlank()) {
				continue;
			}
			Object discoveriesRaw = category.getClass().getMethod("getDiscoveries").invoke(category);
			if (!(discoveriesRaw instanceof Iterable<?> discoveries)) {
				continue;
			}
			Set<String> ids = new HashSet<>();
			for (Object discovery : discoveries) {
				if (discovery == null) {
					continue;
				}
				Object idRaw = discovery.getClass().getMethod("getDiscoveryName").invoke(discovery);
				if (!(idRaw instanceof String id) || id.isBlank()) {
					continue;
				}
				ids.add(id.trim().toLowerCase(Locale.ROOT));
			}
			if (!ids.isEmpty()) {
				byCategory.put(name.trim().toLowerCase(Locale.ROOT), Set.copyOf(ids));
			}
		}
		return byCategory;
	}
}
