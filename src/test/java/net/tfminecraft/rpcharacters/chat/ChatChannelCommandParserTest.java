package net.tfminecraft.rpcharacters.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.loaders.ChatLoader;

class ChatChannelCommandParserTest {

	private static final String TEST_CHANNELS = """
			channels:
			  action:
			    commands: [me]
			    format: '&e* {message}'
			  looc:
			    commands: [looc]
			    format: '{message}'
			""";

	@BeforeEach
	void loadChannels() throws IOException {
		var file = Files.createTempFile("chat-parser-test", ".yml");
		Files.writeString(file, TEST_CHANNELS);
		new ChatLoader().load(file.toFile());
		Files.deleteIfExists(file);
	}

	@AfterEach
	void clearChannels() throws IOException {
		var file = Files.createTempFile("chat-parser-empty", ".yml");
		Files.writeString(file, "default: rp\n");
		new ChatLoader().load(file.toFile());
		Files.deleteIfExists(file);
	}

	@Test
	void parsesMeAsActionChannel() {
		ParsedChannelCommand parsed = ChatChannelCommandParser.parse("/me hello");

		assertNotNull(parsed);
		assertEquals("me", parsed.label());
		assertEquals("action", parsed.channel().getId());
		assertEquals("hello", parsed.message());
	}

	@Test
	void parsesNamespacedMeCommand() {
		ParsedChannelCommand parsed = ChatChannelCommandParser.parse("/minecraft:me hello");

		assertNotNull(parsed);
		assertEquals("me", parsed.label());
		assertEquals("action", parsed.channel().getId());
		assertEquals("hello", parsed.message());
	}

	@Test
	void parsesLabelCaseInsensitively() {
		ParsedChannelCommand parsed = ChatChannelCommandParser.parse("/ME waves");

		assertNotNull(parsed);
		assertEquals("me", parsed.label());
		assertEquals("waves", parsed.message());
	}

	@Test
	void returnsNullForNonChannelCommand() {
		assertNull(ChatChannelCommandParser.parse("/rpcharacter create"));
	}

	@Test
	void parsesMeWithoutMessage() {
		ParsedChannelCommand parsed = ChatChannelCommandParser.parse("/me");

		assertNotNull(parsed);
		assertEquals("me", parsed.label());
		assertTrue(parsed.message().isEmpty());
		assertTrue(!parsed.hasMessage());
	}

	@Test
	void parsesLoocChannel() {
		ParsedChannelCommand parsed = ChatChannelCommandParser.parse("/looc test");

		assertNotNull(parsed);
		assertEquals("looc", parsed.label());
		assertEquals("looc", parsed.channel().getId());
		assertEquals("test", parsed.message());
	}
}
