package net.tfminecraft.rpcharacters.playerlist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/**
 * Keeps a small data pack in the main world that puts player lists on the
 * vanilla Quick Actions key (G). The key opens a fixed dialog from the pack;
 * its buttons send custom actions back to the server for online players or playtime.
 * Data packs load at startup, so changes apply after the next restart.
 */
public final class QuickActionPack {

	public static final String FOLDER = "rpcharacters-player-list";
	/** Data pack format of Minecraft 1.21.10. */
	static final int PACK_FORMAT = 88;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private QuickActionPack() {}

	/** File path (relative to the pack folder) to file content. */
	static Map<String, String> files(Component title, String buttonLabel) {
		JsonObject pack = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("description", "RPCharacters: player list on the Quick Actions key");
		meta.addProperty("min_format", PACK_FORMAT);
		meta.addProperty("max_format", PACK_FORMAT);
		pack.add("pack", meta);

		JsonObject tag = new JsonObject();
		JsonArray values = new JsonArray();
		values.add("rpcharacters:player_list");
		tag.add("values", values);

		JsonObject action = new JsonObject();
		action.addProperty("type", "minecraft:custom");
		action.addProperty("id", PlayerListDialogs.OPEN_ACTION.asString());
		JsonObject button = new JsonObject();
		button.addProperty("label", buttonLabel);
		button.addProperty("width", 160);
		button.add("action", action);
		JsonArray actions = new JsonArray();
		actions.add(button);
		JsonObject playtimeAction = new JsonObject();
		playtimeAction.addProperty("type", "minecraft:custom");
		playtimeAction.addProperty("id", PlaytimeDialogs.OPEN_ACTION.asString());
		JsonObject playtimeButton = new JsonObject();
		playtimeButton.addProperty("label", "Playtime leaderboard");
		playtimeButton.addProperty("width", 160);
		playtimeButton.add("action", playtimeAction);
		actions.add(playtimeButton);
		JsonObject exit = new JsonObject();
		exit.addProperty("label", "Close");
		exit.addProperty("width", 160);

		JsonObject dialog = new JsonObject();
		dialog.addProperty("type", "minecraft:multi_action");
		dialog.add("title", component(title));
		dialog.addProperty("external_title", buttonLabel);
		dialog.addProperty("can_close_with_escape", true);
		dialog.addProperty("columns", 1);
		dialog.add("actions", actions);
		dialog.add("exit_action", exit);

		Map<String, String> files = new LinkedHashMap<>();
		files.put("pack.mcmeta", GSON.toJson(pack) + "\n");
		files.put("data/minecraft/tags/dialog/quick_actions.json", GSON.toJson(tag) + "\n");
		files.put("data/rpcharacters/dialog/player_list.json", GSON.toJson(dialog) + "\n");
		return files;
	}

	private static JsonElement component(Component component) {
		return GsonComponentSerializer.gson().serializeToTree(component);
	}

	/**
	 * Writes or removes the pack under {@code datapacks}. Returns true when the
	 * files on disk changed, meaning a restart is needed.
	 */
	public static boolean sync(Path datapacks, boolean enabled, Component title, String buttonLabel)
			throws IOException {
		Path folder = datapacks.resolve(FOLDER);
		if (!enabled) {
			if (!Files.exists(folder)) {
				return false;
			}
			try (Stream<Path> paths = Files.walk(folder)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(path);
				}
			}
			return true;
		}
		boolean changed = false;
		for (Map.Entry<String, String> file : files(title, buttonLabel).entrySet()) {
			Path path = folder.resolve(file.getKey());
			byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
			if (Files.exists(path) && Arrays.equals(Files.readAllBytes(path), content)) {
				continue;
			}
			Files.createDirectories(path.getParent());
			Files.write(path, content);
			changed = true;
		}
		return changed;
	}

	/** Syncs the pack for the main world and logs when a restart is needed. */
	public static void syncMainWorld(Path worldFolder, PlayerListSettings settings, Logger logger) {
		try {
			boolean changed = sync(worldFolder.resolve("datapacks"), settings.quickAction(),
					GuiText.component(StringFormatter.formatHex(settings.title())),
					settings.quickActionLabel());
			if (changed) {
				logger.info(settings.quickAction()
						? "Player list added to the Quick Actions key (G); restart the server to apply."
						: "Player list removed from the Quick Actions key (G); restart the server to apply.");
			}
		} catch (IOException e) {
			logger.warning("Could not update the Quick Actions data pack: " + e.getMessage());
		}
	}
}
