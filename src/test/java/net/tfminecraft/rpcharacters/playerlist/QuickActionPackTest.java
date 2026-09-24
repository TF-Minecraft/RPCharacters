package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.text.Component;

class QuickActionPackTest {

	@TempDir
	Path datapacks;

	@Test
	void packPutsOneDialogOnTheQuickActionsKey() {
		Map<String, String> files = QuickActionPack.files(Component.text("TFMC"), "Who's online");
		JsonObject tag = JsonParser.parseString(files.get("data/minecraft/tags/dialog/quick_actions.json"))
				.getAsJsonObject();
		assertEquals("rpcharacters:player_list", tag.getAsJsonArray("values").get(0).getAsString());

		JsonObject dialog = JsonParser.parseString(files.get("data/rpcharacters/dialog/player_list.json"))
				.getAsJsonObject();
		JsonObject button = dialog.getAsJsonArray("actions").get(0).getAsJsonObject();
		assertEquals("Who's online", button.get("label").getAsString());
		assertEquals("minecraft:custom", button.getAsJsonObject("action").get("type").getAsString());
		assertEquals(PlayerListDialogs.OPEN_ACTION.asString(),
				button.getAsJsonObject("action").get("id").getAsString());

		JsonObject meta = JsonParser.parseString(files.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
		assertEquals(QuickActionPack.PACK_FORMAT, meta.get("min_format").getAsInt());
	}

	@Test
	void syncWritesOnceThenReportsNoChange() throws IOException {
		assertTrue(QuickActionPack.sync(datapacks, true, Component.text("TFMC"), "Who's online"));
		assertTrue(Files.isRegularFile(datapacks.resolve(QuickActionPack.FOLDER).resolve("pack.mcmeta")));
		assertFalse(QuickActionPack.sync(datapacks, true, Component.text("TFMC"), "Who's online"));
		assertTrue(QuickActionPack.sync(datapacks, true, Component.text("TFMC"), "Online players"));
	}

	@Test
	void disablingRemovesOnlyThePack() throws IOException {
		Path other = Files.createDirectories(datapacks.resolve("other-pack"));
		QuickActionPack.sync(datapacks, true, Component.text("TFMC"), "Who's online");
		assertTrue(QuickActionPack.sync(datapacks, false, Component.text("TFMC"), "Who's online"));
		assertFalse(Files.exists(datapacks.resolve(QuickActionPack.FOLDER)));
		assertTrue(Files.isDirectory(other));
		assertFalse(QuickActionPack.sync(datapacks, false, Component.text("TFMC"), "Who's online"));
	}
}
