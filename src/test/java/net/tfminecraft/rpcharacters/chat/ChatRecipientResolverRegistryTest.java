package net.tfminecraft.rpcharacters.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChatRecipientResolverRegistryTest {

	@AfterEach
	void tearDown() {
		ChatRecipientResolverRegistry.clearForTests();
	}

	@Test
	void registerAndHasResolver() {
		ChatRecipientResolverRegistry.register("simplefactions:guild", (s, c) -> Collections.emptySet());

		assertTrue(ChatRecipientResolverRegistry.hasResolver("simplefactions:guild"));
	}

	@Test
	void resolveReturnsEmptyWhenResolverMissing() {
		ChatChannel channel = testChannel("gooc", "simplefactions:guild");

		assertTrue(ChatRecipientResolverRegistry.resolve("simplefactions:guild", null, channel).isEmpty());
	}

	@Test
	void unregisterRemovesResolver() {
		ChatRecipientResolverRegistry.register("simplefactions:guild", (s, c) -> Collections.emptySet());
		ChatRecipientResolverRegistry.unregister("simplefactions:guild");

		assertFalse(ChatRecipientResolverRegistry.hasResolver("simplefactions:guild"));
	}

	@Test
	void invokeRegisteredForTestsCallsResolverWithChannel() {
		ChatChannel channel = testChannel("pooc", "rpcharacters:party");
		AtomicReference<String> resolvedChannelId = new AtomicReference<>();
		AtomicBoolean invoked = new AtomicBoolean();

		ChatRecipientResolverRegistry.register("rpcharacters:party", (s, c) -> {
			invoked.set(true);
			resolvedChannelId.set(c.getId());
			return Set.of();
		});

		ChatRecipientResolverRegistry.invokeRegisteredForTests("rpcharacters:party", channel);

		assertTrue(invoked.get());
		assertEquals("pooc", resolvedChannelId.get());
	}

	private static ChatChannel testChannel(String id, String resolverId) {
		YamlConfiguration config = new YamlConfiguration();
		config.set("commands", java.util.List.of(id));
		config.set("format", "&f{message}");
		config.set("recipient-resolver", resolverId);
		return new ChatChannel(id, config);
	}
}
