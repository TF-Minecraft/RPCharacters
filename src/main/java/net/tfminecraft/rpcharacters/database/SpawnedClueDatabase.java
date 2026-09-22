package net.tfminecraft.rpcharacters.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoverySettings;

public class SpawnedClueDatabase {

	private static final JSONParser PARSER = new JSONParser();

	private static File getFile() {
		return new File(RPCharacters.plugin.getDataFolder(), "data/spawned-clues.json");
	}

	@SuppressWarnings("unchecked")
	public static List<SpawnedClue> loadAll() {
		List<SpawnedClue> clues = new ArrayList<>();
		File file = getFile();
		if (!file.exists()) return clues;

		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		long now = System.currentTimeMillis();

		try {
			JSONArray array = (JSONArray) PARSER.parse(new InputStreamReader(new FileInputStream(file), "UTF-8"));
			for (Object entry : array) {
				JSONObject obj = (JSONObject) entry;
				long expiresAt = ((Number) obj.get("expiresAt")).longValue();
				if (expiresAt <= now) continue;

				UUID id = UUID.fromString((String) obj.get("id"));
				String world = (String) obj.get("world");
				double x = ((Number) obj.get("x")).doubleValue();
				double y = ((Number) obj.get("y")).doubleValue();
				double z = ((Number) obj.get("z")).doubleValue();
				String clueText = (String) obj.get("clueText");
				UUID ownerUuid = UUID.fromString((String) obj.get("ownerUuid"));

				Integer targetBlockX = null;
				Integer targetBlockY = null;
				Integer targetBlockZ = null;
				if (obj.containsKey("targetBlockX") && obj.containsKey("targetBlockY") && obj.containsKey("targetBlockZ")) {
					targetBlockX = ((Number) obj.get("targetBlockX")).intValue();
					targetBlockY = ((Number) obj.get("targetBlockY")).intValue();
					targetBlockZ = ((Number) obj.get("targetBlockZ")).intValue();
				}

				List<UUID> displayEntityIds = new ArrayList<>();
				if (obj.containsKey("displayEntityIds")) {
					JSONArray displayIds = (JSONArray) obj.get("displayEntityIds");
					for (Object displayId : displayIds) {
						try {
							displayEntityIds.add(UUID.fromString((String) displayId));
						} catch (IllegalArgumentException ignored) {
						}
					}
				}

				double potency = settings.getPotencyInitial();
				if (obj.containsKey("potency")) {
					potency = ((Number) obj.get("potency")).doubleValue();
				}

				long spawnedAtMs = now;
				if (obj.containsKey("spawnedAtMs")) {
					spawnedAtMs = ((Number) obj.get("spawnedAtMs")).longValue();
				} else {
					spawnedAtMs = expiresAt - (net.tfminecraft.rpcharacters.Cache.spawnedClueTimerHours * 60L * 60L * 1000L);
				}

				Map<UUID, Long> discovered = new HashMap<>();
				if (obj.containsKey("discoveredBy")) {
					JSONObject discoveredJson = (JSONObject) obj.get("discoveredBy");
					for (Object key : discoveredJson.keySet()) {
						try {
							UUID charId = UUID.fromString(key.toString());
							long at = ((Number) discoveredJson.get(key)).longValue();
							discovered.put(charId, at);
						} catch (IllegalArgumentException ignored) {
						}
					}
				}

				int footTrafficEvents = 0;
				long footTrafficWindowStart = 0L;
				if (obj.containsKey("footTrafficEventsThisHour")) {
					footTrafficEvents = ((Number) obj.get("footTrafficEventsThisHour")).intValue();
				}
				if (obj.containsKey("footTrafficWindowStartMs")) {
					footTrafficWindowStart = ((Number) obj.get("footTrafficWindowStartMs")).longValue();
				}

				if (!obj.containsKey("potency")) {
					long totalLifetime = Math.max(1L, expiresAt - spawnedAtMs);
					long age = now - spawnedAtMs;
					double ageFraction = Math.min(1.0, age / (double) totalLifetime);
					potency = Math.max(0, settings.getPotencyInitial() - (settings.getPotencyDecayPerHour() * (age / 3600000.0)));
					potency = Math.max(0, potency * (1.0 - ageFraction * 0.5));
				}

				clues.add(new SpawnedClue(id, world, x, y, z, clueText, expiresAt, ownerUuid,
						targetBlockX, targetBlockY, targetBlockZ, displayEntityIds, potency, spawnedAtMs,
						discovered, footTrafficEvents, footTrafficWindowStart));
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return clues;
	}

	@SuppressWarnings("unchecked")
	public static void saveAll(Collection<SpawnedClue> clues) {
		try {
			File file = getFile();
			file.getParentFile().mkdirs();

			JSONArray array = new JSONArray();
			for (SpawnedClue clue : clues) {
				if (clue.isExpired()) continue;
				JSONObject obj = new JSONObject();
				obj.put("id", clue.getId().toString());
				obj.put("world", clue.getWorldName());
				obj.put("x", clue.getX());
				obj.put("y", clue.getY());
				obj.put("z", clue.getZ());
				obj.put("clueText", clue.getClueText());
				obj.put("expiresAt", clue.getExpiresAtMs());
				obj.put("ownerUuid", clue.getOwnerUuid().toString());
				obj.put("potency", clue.getPotency());
				obj.put("spawnedAtMs", clue.getSpawnedAtMs());
				if (clue.hasTargetBlock()) {
					obj.put("targetBlockX", clue.getTargetBlockX());
					obj.put("targetBlockY", clue.getTargetBlockY());
					obj.put("targetBlockZ", clue.getTargetBlockZ());
				}
				if (!clue.getDiscoveredByCharacter().isEmpty()) {
					JSONObject discoveredJson = new JSONObject();
					for (Map.Entry<UUID, Long> entry : clue.getDiscoveredByCharacter().entrySet()) {
						discoveredJson.put(entry.getKey().toString(), entry.getValue());
					}
					obj.put("discoveredBy", discoveredJson);
				}
				if (clue.getFootTrafficEventsThisHour() > 0) {
					obj.put("footTrafficEventsThisHour", clue.getFootTrafficEventsThisHour());
					obj.put("footTrafficWindowStartMs", clue.getFootTrafficWindowStartMs());
				}
				array.add(obj);
			}

			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			try (FileWriter writer = new FileWriter(file, false)) {
				writer.write(gson.toJson(array));
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
}
