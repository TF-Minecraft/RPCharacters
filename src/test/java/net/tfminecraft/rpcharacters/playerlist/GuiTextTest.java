package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GuiTextTest {

	@Test
	void widthCountsGlyphAdvances() {
		// A=6, space=4, i=2, l=3
		assertEquals(15, GuiText.width("A il"));
	}

	@Test
	void widthIgnoresColourCodesAndAddsBold() {
		assertEquals(12, GuiText.width("§aAA"));
		assertEquals(14, GuiText.width("§lAA"));
		// A colour code ends bold, like the client.
		assertEquals(13, GuiText.width("§lA§cA"));
	}

	@Test
	void widthSkipsHexColourCodes() {
		assertEquals(6, GuiText.width("§x§f§f§0§0§0§0A"));
	}

	@Test
	void plainStripsCodes() {
		assertEquals("Aspect", GuiText.plain("§f§x§a§a§0§0§0§0Aspect"));
	}

	@Test
	void paddingReachesTargetWidth() {
		for (int target = 40; target < 80; target++) {
			assertEquals(target, GuiText.paddedWidth("Name", target), "target " + target);
		}
	}

	@Test
	void paddingNeverShrinksLongLines() {
		assertEquals(GuiText.width("A long line"), GuiText.paddedWidth("A long line", 10));
	}

	@Test
	void wrapSplitsOnWordsAndKeepsLeadingColour() {
		List<String> lines = GuiText.wrap("§7aaaa bbbb cccc", 60);
		assertEquals(List.of("§7aaaa bbbb", "§7cccc"), lines);
		for (String line : lines) {
			assertTrue(GuiText.width(line) <= 60);
		}
	}

	@Test
	void wrapLeavesShortLinesAlone() {
		assertEquals(List.of("§eShort"), GuiText.wrap("§eShort", 190));
	}
}
