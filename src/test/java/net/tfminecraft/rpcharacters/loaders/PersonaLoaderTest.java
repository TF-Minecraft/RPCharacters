package net.tfminecraft.rpcharacters.loaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.playerlist.GuiText;

class PersonaLoaderTest {

	@TempDir Path directory;

	@Test
	void unknownNameRendersItsConfiguredColourInsteadOfLiteralHex() throws Exception {
		assertFallback("#ffffff§oUnknown", "Unknown", 0xFFFFFF, TextDecoration.ITALIC);
	}

	@Test
	void customFallbackSupportsHexAndAmpersandFormatting() throws Exception {
		assertFallback("#12ab34&lNewcomer", "Newcomer", 0x12AB34, TextDecoration.BOLD);
	}

	private void assertFallback(String configured, String expectedName, int colour, TextDecoration decoration)
			throws Exception {
		String previous = Cache.personaNoCharacterFallback;
		try {
			Path config = directory.resolve("persona.yml");
			Files.writeString(config, "no-character-fallback: '" + configured + "'\n");
			new PersonaLoader().load(config.toFile());
			String safe = DisplayIdentityService.resolveDisplaySafe(null);
			assertEquals(safe, DisplayIdentityService.resolveDisplayTab((RPCharacter) null));
			var component = GuiText.component(safe);
			assertEquals(expectedName, PlainTextComponentSerializer.plainText().serialize(component));
			assertEquals(colour, component.color().value());
			assertEquals(TextDecoration.State.TRUE, component.decoration(decoration));
			assertEquals(GuiText.width("§" + (decoration == TextDecoration.BOLD ? "l" : "o") + expectedName),
					GuiText.width(safe));
		} finally {
			Cache.personaNoCharacterFallback = previous;
		}
	}
}
