package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;

class PlayerListDialogsTest {

	@Test
	void profileTextCannotChangePortraitRows() {
		BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
		var shortCard = PlayerListDialogs.sheetSections(List.of("Short"), front);
		String longLine = "Wide custom-font profile text ".repeat(20);
		var longCard = PlayerListDialogs.sheetSections(List.of("§l(Name)", longLine), front);
		assertEquals(2, longCard.size());
		assertEquals(shortCard.get(0), longCard.get(0));
		assertEquals(512, objects(longCard.get(0)));
		assertEquals("\n".repeat(31), text(longCard.get(0)));
		// No padding or server-estimated wraps: the client lays out its own font.
		assertEquals("(Name)\n" + longLine, text(longCard.get(1)));
		assertEquals(0, objects(longCard.get(1)));
	}

	@Test
	void unavailablePortraitStillShowsProfile() {
		var sections = PlayerListDialogs.sheetSections(List.of("§aName", "Description"), null);
		assertEquals(1, sections.size());
		assertEquals("Name\nDescription", text(sections.get(0)));
	}

	private static String text(Component component) {
		StringBuilder out = new StringBuilder(component instanceof TextComponent text ? text.content() : "");
		for (Component child : component.children()) out.append(text(child));
		return out.toString();
	}

	private static int objects(Component component) {
		return (component instanceof ObjectComponent ? 1 : 0)
				+ component.children().stream().mapToInt(PlayerListDialogsTest::objects).sum();
	}
}
