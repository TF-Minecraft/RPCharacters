package net.tfminecraft.rpcharacters.chat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Registry for {@link ChatRecipientResolver} implementations.
 * <p>
 * Registrations are not cleared on chat.yml reload; the registering plugin should
 * {@link #unregister(String)} on disable.
 */
public final class ChatRecipientResolverRegistry {

	private static final Logger LOGGER = Logger.getLogger("RPCharacters");
	private static final ConcurrentHashMap<String, ChatRecipientResolver> RESOLVERS = new ConcurrentHashMap<>();
	private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();

	private ChatRecipientResolverRegistry() {}

	public static void register(String id, ChatRecipientResolver resolver) {
		if (id == null || id.isBlank() || resolver == null) {
			return;
		}
		RESOLVERS.put(id, resolver);
		WARNED_MISSING.remove(id);
	}

	public static void unregister(String id) {
		if (id == null || id.isBlank()) {
			return;
		}
		RESOLVERS.remove(id);
		WARNED_MISSING.remove(id);
	}

	public static Set<Player> resolve(String id, Player sender, ChatChannel channel) {
		if (id == null || id.isBlank() || sender == null || channel == null) {
			return Collections.emptySet();
		}

		ChatRecipientResolver resolver = RESOLVERS.get(id);
		if (resolver == null) {
			if (WARNED_MISSING.add(id)) {
				logWarning("No chat recipient resolver registered for '" + id
						+ "' (channel " + channel.getId() + ")");
			}
			return Collections.emptySet();
		}

		try {
			Set<Player> resolved = resolver.resolve(sender, channel);
			if (resolved == null || resolved.isEmpty()) {
				return Collections.emptySet();
			}
			return new HashSet<>(resolved);
		} catch (RuntimeException e) {
			logWarning("Chat recipient resolver '" + id + "' failed: " + e.getMessage());
			return Collections.emptySet();
		}
	}

	static void clearForTests() {
		RESOLVERS.clear();
		WARNED_MISSING.clear();
	}

	static boolean hasResolver(String id) {
		return id != null && !id.isBlank() && RESOLVERS.containsKey(id);
	}

	/**
	 * Test-only entry point that invokes a registered resolver without requiring a {@link Player}.
	 */
	static Set<Player> invokeRegisteredForTests(String id, ChatChannel channel) {
		if (id == null || id.isBlank() || channel == null) {
			return Collections.emptySet();
		}
		ChatRecipientResolver resolver = RESOLVERS.get(id);
		if (resolver == null) {
			return Collections.emptySet();
		}
		try {
			Set<Player> resolved = resolver.resolve(null, channel);
			if (resolved == null || resolved.isEmpty()) {
				return Collections.emptySet();
			}
			return new HashSet<>(resolved);
		} catch (RuntimeException e) {
			return Collections.emptySet();
		}
	}

	private static void logWarning(String message) {
		if (RPCharacters.plugin != null) {
			RPCharacters.plugin.getLogger().warning(message);
		} else {
			LOGGER.warning(message);
		}
	}
}
