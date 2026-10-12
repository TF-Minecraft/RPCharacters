package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient.SimpleResult;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait.SkinTexture;

class SelectPortraitsTest {

	private static final UUID OWNER = UUID.randomUUID();
	private static final String CHARACTER = UUID.randomUUID().toString();
	private static final SkinTexture FALLBACK = new SkinTexture("https://textures.minecraft.net/texture/account", false);

	private static String response(String id, String url) {
		String profile = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\",\"metadata\":{\"model\":\"slim\"}}}}";
		JsonObject body = new JsonObject();
		body.addProperty("character_id", id);
		body.addProperty("value", Base64.getEncoder().encodeToString(profile.getBytes(StandardCharsets.UTF_8)));
		return body.toString();
	}

	@Test void previewReadsOnlyTheRequestedCharactersWornSkin() {
		try (var client = mockStatic(ProvinceSystemClient.class)) {
			client.when(() -> ProvinceSystemClient.fetchWornSkin(OWNER.toString(), CHARACTER))
					.thenReturn(SimpleResult.success(response(CHARACTER, "http://textures.minecraft.net/texture/character")));
			SkinTexture texture = SelectPortraits.texture(OWNER, CHARACTER, FALLBACK);
			assertEquals("https://textures.minecraft.net/texture/character", texture.url());
			assertTrue(texture.slim());
			client.verify(() -> ProvinceSystemClient.fetchWornSkin(OWNER.toString(), CHARACTER));
			client.verifyNoMoreInteractions();
		}
	}

	@Test void unavailableOrMismatchedPortraitsUseOnlyTheCapturedAccountFallback() {
		try (var client = mockStatic(ProvinceSystemClient.class)) {
			var request = client.when(() -> ProvinceSystemClient.fetchWornSkin(OWNER.toString(), CHARACTER));
			for (SimpleResult result : new SimpleResult[] {
					SimpleResult.fail("offline"), SimpleResult.success("{}"), SimpleResult.success("[]"),
					SimpleResult.success("invalid"), SimpleResult.success(null),
					SimpleResult.success(response("another-character", "https://textures.minecraft.net/texture/other")),
					SimpleResult.success(response(CHARACTER, "https://untrusted.example/skin.png")),
					SimpleResult.success("{\"character_id\":\"" + CHARACTER + "\",\"value\":null}")
			}) {
				request.thenReturn(result);
				assertSame(FALLBACK, SelectPortraits.texture(OWNER, CHARACTER, FALLBACK));
				assertNull(SelectPortraits.texture(OWNER, CHARACTER, null));
			}
			request.thenThrow(new IllegalStateException("gateway unavailable"));
			assertSame(FALLBACK, SelectPortraits.texture(OWNER, CHARACTER, FALLBACK));
		}
	}

	@Test void loadingFetchesAndBuildsThePortraitOffTheCallingPath() {
		try (var client = mockStatic(ProvinceSystemClient.class); var portraits = mockStatic(SkinPortrait.class)) {
			client.when(() -> ProvinceSystemClient.fetchWornSkin(OWNER.toString(), CHARACTER)).thenReturn(SimpleResult.fail("offline"));
			BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
			BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
			CompletableFuture<BufferedImage> download = new CompletableFuture<>();
			portraits.when(() -> SkinPortrait.fetch(FALLBACK)).thenReturn(download);
			portraits.when(() -> SkinPortrait.front(skin, false)).thenReturn(front);
			var pending = new java.util.ArrayList<Runnable>();
			var loaded = SelectPortraits.load(OWNER, CHARACTER, FALLBACK, pending::add);
			assertFalse(loaded.isDone());
			client.verifyNoInteractions();
			pending.getFirst().run();
			assertFalse(loaded.isDone());
			download.complete(skin);
			assertSame(front, loaded.join());
		}
	}

	@Test void failedDownloadsAndShutdownReturnNoPortrait() {
		try (var client = mockStatic(ProvinceSystemClient.class); var portraits = mockStatic(SkinPortrait.class)) {
			client.when(() -> ProvinceSystemClient.fetchWornSkin(OWNER.toString(), CHARACTER)).thenReturn(SimpleResult.fail("offline"));
			portraits.when(() -> SkinPortrait.fetch(FALLBACK)).thenReturn(CompletableFuture.completedFuture(null));
			assertNull(SelectPortraits.load(OWNER, CHARACTER, FALLBACK, Runnable::run).join());
			portraits.when(() -> SkinPortrait.fetch(FALLBACK)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
			assertNull(SelectPortraits.load(OWNER, CHARACTER, FALLBACK, Runnable::run).join());
			assertNull(SelectPortraits.load(OWNER, CHARACTER, FALLBACK, task -> { throw new RejectedExecutionException(); }).join());
		}
	}

	@Test void productionEntryUsesThePluginsAsyncScheduler() {
		try (var bukkit = mockStatic(Bukkit.class); var client = mockStatic(ProvinceSystemClient.class);
				var portraits = mockStatic(SkinPortrait.class)) {
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
			doAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; })
					.when(scheduler).runTaskAsynchronously(nullable(Plugin.class), any(Runnable.class));
			client.when(() -> ProvinceSystemClient.fetchWornSkin(OWNER.toString(), CHARACTER)).thenReturn(SimpleResult.fail("offline"));
			portraits.when(() -> SkinPortrait.fetch(FALLBACK)).thenReturn(CompletableFuture.completedFuture(null));
			assertNull(SelectPortraits.load(OWNER, CHARACTER, FALLBACK).join());
			verify(scheduler).runTaskAsynchronously(nullable(Plugin.class), any(Runnable.class));
		}
	}
}
