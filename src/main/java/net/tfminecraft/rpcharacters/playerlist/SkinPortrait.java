package net.tfminecraft.rpcharacters.playerlist;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import org.bukkit.entity.Player;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * Draws a player's skin, front view, as a grid of tinted image sprites for a
 * dialog. Skins are fetched from Mojang's texture server and cached.
 */
public final class SkinPortrait {

	public static final int WIDTH = 16;
	public static final int HEIGHT = 32;
	/** Rendered width of one portrait row in GUI pixels (8px sprite + 1px gap). */
	public static final int ROW_WIDTH = WIDTH * 9;
	// These vanilla block-atlas textures are solid white and fully transparent.
	// Object sprites bypass the player's font. Bold adds a 1px advance to the
	// fixed 8px sprite, matching the client's 9px line spacing in both axes.
	private static final ObjectComponent PIXEL = sprite("block/lightning_rod_on");
	private static final ObjectComponent BLANK = sprite("block/redstone_dust_overlay");

	private static final String TEXTURE_HOST = "textures.minecraft.net";
	private static final int CACHE_SIZE = 256;
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<String, BufferedImage> CACHE = Collections.synchronizedMap(
			new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
					return size() > CACHE_SIZE;
				}
			});

	private SkinPortrait() {}

	/** A player's current skin texture, from their signed profile textures. */
	public record SkinTexture(String url, boolean slim) {}

	public static SkinTexture texture(Player player) {
		for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
			if ("textures".equals(property.getName())) {
				return parseTextures(property.getValue());
			}
		}
		return null;
	}

	static SkinTexture parseTextures(String base64) {
		try {
			JsonObject root = JsonParser.parseString(
					new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
			if (skin == null || !skin.has("url")) {
				return null;
			}
			URI uri = URI.create(skin.get("url").getAsString());
			if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost())) {
				return null;
			}
			boolean slim = skin.has("metadata")
					&& "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
			return new SkinTexture("https://" + TEXTURE_HOST + uri.getRawPath(), slim);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Fetches a skin image; completes with {@code null} if it cannot be loaded. */
	public static CompletableFuture<BufferedImage> fetch(SkinTexture texture) {
		if (texture == null) {
			return CompletableFuture.completedFuture(null);
		}
		BufferedImage cached = CACHE.get(texture.url());
		if (cached != null) {
			return CompletableFuture.completedFuture(cached);
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(texture.url()))
				.timeout(Duration.ofSeconds(5)).build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						return null;
					}
					try {
						BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
						if (image != null && image.getWidth() == 64
								&& (image.getHeight() == 64 || image.getHeight() == 32)) {
							CACHE.put(texture.url(), image);
							return image;
						}
					} catch (IOException ignored) {
						// Treated as no portrait.
					}
					return null;
				})
				.exceptionally(error -> null);
	}

	/** Front view of a 64x64 or legacy 64x32 skin: 16x32, base layers then overlays. */
	public static BufferedImage front(BufferedImage skin, boolean slim) {
		BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setComposite(AlphaComposite.SrcOver);
		int arm = slim ? 3 : 4;
		boolean legacy = skin.getHeight() == 32;
		copy(g, skin, 8, 8, 8, 8, 4, 0, false);
		copy(g, skin, 20, 20, 8, 12, 4, 8, false);
		copy(g, skin, 44, 20, arm, 12, 4 - arm, 8, false);
		copy(g, skin, 4, 20, 4, 12, 4, 20, false);
		if (legacy) {
			copy(g, skin, 44, 20, arm, 12, 12, 8, true);
			copy(g, skin, 4, 20, 4, 12, 8, 20, true);
		} else {
			copy(g, skin, 36, 52, arm, 12, 12, 8, false);
			copy(g, skin, 20, 52, 4, 12, 8, 20, false);
		}
		copy(g, skin, 40, 8, 8, 8, 4, 0, false);
		if (!legacy) {
			copy(g, skin, 20, 36, 8, 12, 4, 8, false);
			copy(g, skin, 44, 36, arm, 12, 4 - arm, 8, false);
			copy(g, skin, 52, 52, arm, 12, 12, 8, false);
			copy(g, skin, 4, 36, 4, 12, 4, 20, false);
			copy(g, skin, 4, 52, 4, 12, 8, 20, false);
		}
		g.dispose();
		return out;
	}

	private static void copy(Graphics2D g, BufferedImage skin, int sx, int sy, int w, int h,
			int dx, int dy, boolean mirror) {
		if (mirror) {
			g.drawImage(skin, dx + w, dy, dx, dy + h, sx, sy, sx + w, sy + h, null);
		} else {
			g.drawImage(skin, dx, dy, dx + w, dy + h, sx, sy, sx + w, sy + h, null);
		}
	}

	/** A standalone image grid; profile text must remain in a separate dialog body. */
	public static Component grid(BufferedImage front) {
		return Component.join(JoinConfiguration.newlines(), rows(front));
	}

	/** One row per image row, always containing exactly WIDTH equal-size sprites. */
	public static List<Component> rows(BufferedImage front) {
		List<Component> rows = new ArrayList<>(HEIGHT);
		for (int y = 0; y < HEIGHT; y++) {
			rows.add(row(front, y));
		}
		return rows;
	}

	static Component row(BufferedImage front, int y) {
		TextComponent.Builder row = Component.text();
		for (int x = 0; x < WIDTH; x++) {
			int argb = pixel(front, x, y);
			row.append(solid(argb)
					? PIXEL.color(TextColor.color(argb & 0xFFFFFF))
					: BLANK);
		}
		return row.build();
	}

	private static ObjectComponent sprite(String texture) {
		return Component.object(ObjectContents.sprite(Key.key("minecraft", "blocks"), Key.key("minecraft", texture)))
				.decorate(TextDecoration.BOLD).shadowColor(ShadowColor.none());
	}

	private static int pixel(BufferedImage image, int x, int y) {
		return image == null ? 0 : image.getRGB(x, y);
	}

	private static boolean solid(int argb) {
		return (argb >>> 24) >= 128;
	}
}
