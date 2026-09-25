package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.SpriteObjectContents;

class SkinPortraitTest {

	private static final int HEAD = 0xFF110000;
	private static final int BODY = 0xFF220000;
	private static final int RIGHT_ARM = 0xFF330000;
	private static final int RIGHT_LEG = 0xFF440000;
	private static final int LEFT_ARM = 0xFF550000;
	private static final int LEFT_LEG = 0xFF660000;
	private static final int HAT = 0xFF770000;

	private static BufferedImage skin(int height) {
		BufferedImage skin = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
		fill(skin, 8, 8, 8, 8, HEAD);
		fill(skin, 20, 20, 8, 12, BODY);
		fill(skin, 44, 20, 4, 12, RIGHT_ARM);
		fill(skin, 4, 20, 4, 12, RIGHT_LEG);
		if (height == 64) {
			fill(skin, 36, 52, 4, 12, LEFT_ARM);
			fill(skin, 20, 52, 4, 12, LEFT_LEG);
		}
		return skin;
	}

	private static void fill(BufferedImage image, int x, int y, int w, int h, int argb) {
		for (int dx = 0; dx < w; dx++) {
			for (int dy = 0; dy < h; dy++) {
				image.setRGB(x + dx, y + dy, argb);
			}
		}
	}

	@Test
	void frontPlacesEachPart() {
		BufferedImage front = SkinPortrait.front(skin(64), false);
		assertEquals(HEAD, front.getRGB(7, 3));
		assertEquals(BODY, front.getRGB(7, 12));
		assertEquals(RIGHT_ARM, front.getRGB(0, 10));
		assertEquals(LEFT_ARM, front.getRGB(15, 10));
		assertEquals(RIGHT_LEG, front.getRGB(5, 25));
		assertEquals(LEFT_LEG, front.getRGB(10, 25));
		// Beside the head is empty.
		assertEquals(0, front.getRGB(0, 2) >>> 24);
	}

	@Test
	void slimArmsLeaveOuterColumnEmpty() {
		BufferedImage front = SkinPortrait.front(skin(64), true);
		assertEquals(0, front.getRGB(0, 10) >>> 24);
		assertEquals(RIGHT_ARM, front.getRGB(1, 10));
		assertEquals(LEFT_ARM, front.getRGB(14, 10));
		assertEquals(0, front.getRGB(15, 10) >>> 24);
	}

	@Test
	void legacySkinsMirrorTheRightLimbs() {
		BufferedImage front = SkinPortrait.front(skin(32), false);
		assertEquals(RIGHT_ARM, front.getRGB(15, 10));
		assertEquals(RIGHT_LEG, front.getRGB(10, 25));
	}

	@Test
	void hatOverlayCoversTheHead() {
		BufferedImage skin = skin(64);
		skin.setRGB(40, 8, HAT);
		BufferedImage front = SkinPortrait.front(skin, false);
		assertEquals(HAT, front.getRGB(4, 0));
		assertEquals(HEAD, front.getRGB(5, 0));
	}

	@Test
	void colouredAndTransparentRowsUseEqualSizeImageCells() {
		BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
		fill(front, 0, 0, 16, 1, BODY);
		var rows = SkinPortrait.rows(front);
		assertEquals(32, rows.size());
		for (int y = 0; y < 32; y++) {
			var children = rows.get(y).children();
			assertEquals(18, children.size());
			assertEquals(children.getFirst(), children.getLast());
			assertEquals("\u3000", assertInstanceOf(TextComponent.class, children.getFirst()).content());
			assertEquals("minecraft:uniform", children.getFirst().font().asString());
			for (Component cell : children.subList(1, 17)) {
				ObjectComponent object = assertInstanceOf(ObjectComponent.class, cell);
				SpriteObjectContents sprite = assertInstanceOf(SpriteObjectContents.class, object.contents());
				assertEquals("minecraft:blocks", sprite.atlas().asString());
				assertEquals(y == 0 ? "minecraft:block/lightning_rod_on" : "minecraft:block/redstone_dust_overlay",
						sprite.sprite().asString());
				assertEquals(TextDecoration.State.TRUE, cell.decoration(TextDecoration.BOLD));
				assertEquals(ShadowColor.none(), cell.shadowColor());
				if (y == 0) assertEquals(BODY & 0xFFFFFF, cell.color().value());
			}
		}
	}

	@Test
	void adjacentImageCellsKeepTheirOwnColoursAndTransparency() {
		BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
		front.setRGB(0, 0, HEAD);
		front.setRGB(1, 0, BODY);
		front.setRGB(2, 0, 0x00123456);
		var cells = SkinPortrait.row(front, 0).children().subList(1, 17);
		assertEquals(HEAD & 0xFFFFFF, cells.get(0).color().value());
		assertEquals(BODY & 0xFFFFFF, cells.get(1).color().value());
		SpriteObjectContents blank = (SpriteObjectContents) ((ObjectComponent) cells.get(2)).contents();
		assertEquals("minecraft:block/redstone_dust_overlay", blank.sprite().asString());
	}

	private static String textures(String url, boolean slim) {
		String metadata = slim ? ",\"metadata\":{\"model\":\"slim\"}" : "";
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"" + metadata + "}}}";
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void texturesUseMojangHostOverHttps() {
		SkinPortrait.SkinTexture texture = SkinPortrait.parseTextures(
				textures("http://textures.minecraft.net/texture/abc123", true));
		assertNotNull(texture);
		assertEquals("https://textures.minecraft.net/texture/abc123", texture.url());
		assertTrue(texture.slim());
		assertFalse(SkinPortrait.parseTextures(textures("http://textures.minecraft.net/texture/x", false)).slim());
	}

	@Test
	void texturesFromOtherHostsAreIgnored() {
		assertNull(SkinPortrait.parseTextures(textures("http://example.com/texture/abc", false)));
		assertNull(SkinPortrait.parseTextures("not base64"));
	}
}
