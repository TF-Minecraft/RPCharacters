package net.tfminecraft.rpcharacters.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ChatChannelTest {

	@Test
	void parsesRecipientResolverFromConfig() {
		YamlConfiguration config = new YamlConfiguration();
		config.set("commands", java.util.List.of("gooc"));
		config.set("format", "&f[&6GOOC&f] &f{player}: &7{message}");
		config.set("recipient-resolver", "simplefactions:guild");

		ChatChannel channel = new ChatChannel("gooc", config);

		assertEquals("simplefactions:guild", channel.getRecipientResolverId());
		assertTrue(channel.usesRecipientResolver());
	}

	@Test
	void channelWithoutResolverUsesLegacyRangeBehavior() {
		YamlConfiguration config = new YamlConfiguration();
		config.set("commands", java.util.List.of("looc"));
		config.set("format", "&f{message}");
		config.set("range", 20);

		ChatChannel channel = new ChatChannel("looc", config);

		assertFalse(channel.usesRecipientResolver());
		assertEquals("", channel.getRecipientResolverId());
		assertEquals(20, channel.getRange());
	}
}
