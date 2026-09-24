package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

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
	void rowsMergeRunsOfOneColour() {
		BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
		fill(front, 0, 0, 16, 1, BODY);
		Component row = SkinPortrait.rows(front).get(0);
		assertEquals(1, row.children().size());
		assertEquals(SkinPortrait.PIXEL.repeat(16), ((TextComponent) row.children().get(0)).content());
		Component blank = SkinPortrait.rows(front).get(1);
		assertEquals(SkinPortrait.BLANK.repeat(16), ((TextComponent) blank.children().get(0)).content());
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
