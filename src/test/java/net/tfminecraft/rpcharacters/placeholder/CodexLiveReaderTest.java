package net.tfminecraft.rpcharacters.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CodexLiveReaderTest {
	@Test void absentOrDisabledCodexHasNoOnlinePlayers() throws Exception {
		PluginManager manager = mock(PluginManager.class);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(null);
			assertTrue(CodexLiveReader.current().isEmpty());
			bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
			assertTrue(CodexLiveReader.current().isEmpty());
			Plugin disabled = mock(Plugin.class);
			when(manager.getPlugin("Codex")).thenReturn(disabled);
			when(disabled.isEnabled()).thenReturn(false);
			assertTrue(CodexLiveReader.current().isEmpty());
			when(disabled.isEnabled()).thenReturn(true);
			assertThrows(ReflectiveOperationException.class, CodexLiveReader::current);
			MemoryCodex codex = new MemoryCodex();
			UUID kept = UUID.randomUUID();
			codex.manager.players.put(kept, player(kept, List.of(category("research", List.of(discovery("Alchemy"))))));
			when(manager.getPlugin("Codex")).thenReturn(codexPlugin(codex));
			Map<UUID, Map<String, Set<String>>> live = CodexLiveReader.current();
			assertEquals(set("alchemy"), live.get(kept).get("research"));
		}
	}

	@Test void readsLiveDiscoveriesAndSkipsIncompleteRows() throws Exception {
		UUID kept = UUID.randomUUID();
		UUID empty = UUID.randomUUID();
		MemoryCodex codex = new MemoryCodex();
		codex.manager.players.put(kept, player(kept, nullable(
				category(" Research ", nullable(discovery(" Alchemy "), discovery("Alchemy"), discovery("  "), discovery(null), null)),
				category("   ", List.of(discovery("alchemy"))),
				category(7, List.of(discovery("alchemy"))),
				null,
				category("special", "not-a-list"),
				category("empty", List.of(discovery(" "))))));
		codex.manager.players.put(empty, player(empty, null));
		codex.manager.players.put(UUID.randomUUID(), player("not-a-uuid", List.of()));
		codex.manager.players.put(UUID.randomUUID(), null);

		Map<UUID, Map<String, Set<String>>> live = CodexLiveReader.read(codex);
		assertEquals(2, live.size());
		assertEquals(set("alchemy"), live.get(kept).get("research"));
		assertTrue(live.get(empty).isEmpty());

		codex.manager = null;
		assertTrue(CodexLiveReader.read(codex).isEmpty());
		codex.manager = new MemoryCodex.Manager();
		codex.manager.rawPlayers = "not-a-map";
		assertTrue(CodexLiveReader.read(codex).isEmpty());
		assertThrows(ReflectiveOperationException.class, () -> CodexLiveReader.read(new Object()));
	}

	@Test void utilityConstructorCanBeInvoked() throws Exception {
		var ctor = CodexLiveReader.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		ctor.newInstance();
	}

	private interface CodexPlugin extends Plugin {
		Object getPlayerDataManager();
	}

	private static Plugin codexPlugin(MemoryCodex codex) {
		return (Plugin) Proxy.newProxyInstance(CodexLiveReaderTest.class.getClassLoader(), new Class<?>[] { CodexPlugin.class },
				(proxy, method, args) -> switch (method.getName()) {
					case "isEnabled" -> true;
					case "getPlayerDataManager" -> codex.getPlayerDataManager();
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "Codex";
					default -> fallback(method.getReturnType());
				});
	}

	private static Object fallback(Class<?> type) {
		if (!type.isPrimitive() || type == void.class) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0d;
		}
		if (type == float.class) {
			return 0f;
		}
		return 0;
	}

	private static List<Object> nullable(Object... values) {
		List<Object> list = new ArrayList<>();
		for (Object value : values) {
			list.add(value);
		}
		return list;
	}

	private static java.util.Set<String> set(String value) {
		return java.util.Set.of(value);
	}

	private static MemoryCodex.MemoryPlayer player(Object uuid, Object categories) {
		MemoryCodex.MemoryPlayer player = new MemoryCodex.MemoryPlayer();
		player.uuid = uuid;
		player.categories = categories;
		return player;
	}

	private static MemoryCodex.MemoryCategory category(Object name, Object discoveries) {
		MemoryCodex.MemoryCategory category = new MemoryCodex.MemoryCategory();
		category.name = name;
		category.discoveries = discoveries;
		return category;
	}

	private static MemoryCodex.MemoryDiscovery discovery(Object name) {
		MemoryCodex.MemoryDiscovery discovery = new MemoryCodex.MemoryDiscovery();
		discovery.discoveryName = name;
		return discovery;
	}

	static final class MemoryCodex {
		Manager manager = new Manager();

		public Manager getPlayerDataManager() {
			return manager;
		}

		static final class Manager {
			Map<UUID, MemoryPlayer> players = new HashMap<>();
			Object rawPlayers = players;

			public Object getPlayers() {
				return rawPlayers;
			}
		}

		static final class MemoryPlayer {
			Object uuid;
			Object categories;

			public Object getUuid() {
				return uuid;
			}

			public Object getCategories() {
				return categories;
			}
		}

		static final class MemoryCategory {
			Object name;
			Object discoveries;

			public Object getName() {
				return name;
			}

			public Object getDiscoveries() {
				return discoveries;
			}
		}

		static final class MemoryDiscovery {
			Object discoveryName;

			public Object getDiscoveryName() {
				return discoveryName;
			}
		}
	}
}
