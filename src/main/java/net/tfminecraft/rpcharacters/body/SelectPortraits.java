package net.tfminecraft.rpcharacters.body;

import java.awt.image.BufferedImage;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.bukkit.Bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait.SkinTexture;

/** Read-only skin previews. No wardrobe cache, player profile or character state is changed. */
public final class SelectPortraits {

	private SelectPortraits() {}

	/** Immutable inputs only: Bukkit/player state must be captured before calling. */
	public static CompletableFuture<BufferedImage> load(UUID owner, String characterId, SkinTexture accountFallback) {
		return load(owner, characterId, accountFallback,
				command -> Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, command));
	}

	static CompletableFuture<BufferedImage> load(UUID owner, String characterId, SkinTexture accountFallback,
			Executor executor) {
		try {
			return CompletableFuture.supplyAsync(() -> texture(owner, characterId, accountFallback), executor)
					.thenCompose(texture -> SkinPortrait.fetch(texture)
							.thenApply(image -> image == null ? null : SkinPortrait.front(image, texture.slim())))
					.exceptionally(error -> null);
		} catch (RuntimeException unavailable) {
			return CompletableFuture.completedFuture(null);
		}
	}

	static SkinTexture texture(UUID owner, String characterId, SkinTexture accountFallback) {
		try {
			var response = ProvinceSystemClient.fetchWornSkin(owner.toString(), characterId);
			if (!response.ok) return accountFallback;
			JsonObject body = JsonParser.parseString(response.body).getAsJsonObject();
			if (!characterId.equalsIgnoreCase(body.get("character_id").getAsString())) return accountFallback;
			SkinTexture texture = SkinPortrait.parseTextures(body.get("value").getAsString());
			return texture == null ? accountFallback : texture;
		} catch (RuntimeException unavailable) {
			return accountFallback;
		}
	}
}
