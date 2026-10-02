package net.tfminecraft.rpcharacters.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * One small file holding every known player's active-character online playtime.
 *
 * <p>Other plugins need this figure for offline players too, and the per-character
 * files are only read on login. Keeping a flat index means the lookup costs nothing
 * at read time. Online playtime does not advance while a player is offline, so a
 * cached value for an offline player is exact rather than stale.
 */
public class PlaytimeIndexDatabase {
	private PlaytimeIndexDatabase() {}


	/** One player: the uuid is the identity, the name is the lookup key other plugins have. */
	public static final class Entry {
		private final UUID uuid;
		private final String name;
		private final int seconds;

		public Entry(UUID uuid, String name, int seconds) {
			this.uuid = uuid;
			this.name = name;
			this.seconds = Math.max(0, seconds);
		}

		public UUID getUuid() {
			return uuid;
		}

		public String getName() {
			return name;
		}

		public int getSeconds() {
			return seconds;
		}
	}

	private static File getFile() {
		return new File(RPCharacters.plugin.getDataFolder(), "data/playtime-index.json");
	}

	public static List<Entry> loadAll() {
		List<Entry> entries = new ArrayList<>();
		File file = getFile();
		if (!file.exists()) return entries;

		try (var reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
			JSONArray array = (JSONArray) new JSONParser().parse(reader);
			for (Object raw : array) {
				if (!(raw instanceof JSONObject obj)) continue;
				Object id = obj.get("uuid");
				Object name = obj.get("name");
				if (id == null || name == null) continue;
				try {
					int seconds = obj.containsKey("seconds") ? (int) Math.min(Integer.MAX_VALUE, Math.max(0L, ((Number) obj.get("seconds")).longValue())) : 0;
					entries.add(new Entry(UUID.fromString(id.toString()), name.toString(), seconds));
				} catch (RuntimeException ignored) {
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return entries;
	}

	@SuppressWarnings("unchecked")
	public static void saveAll(Collection<Entry> entries) {
		trySaveAll(entries);
	}

	@SuppressWarnings("unchecked")
	public static boolean trySaveAll(Collection<Entry> entries) {
		Path staged = null;
		try {
			File file = getFile();
			file.getParentFile().mkdirs();

			JSONArray array = new JSONArray();
			for (Entry entry : entries) {
				if (entry == null || entry.getUuid() == null || entry.getName() == null) continue;
				JSONObject obj = new JSONObject();
				obj.put("uuid", entry.getUuid().toString());
				obj.put("name", entry.getName());
				obj.put("seconds", entry.getSeconds());
				array.add(obj);
			}

			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			String serialized = gson.toJson(array);
			staged = Files.createTempFile(file.toPath().getParent(), ".playtime-", ".tmp");
			Files.writeString(staged, serialized, StandardCharsets.UTF_8);
			Files.move(staged, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (Exception ex) {
			if (staged != null) {
				try {
					Files.deleteIfExists(staged);
				} catch (IOException cleanup) {
					ex.addSuppressed(cleanup);
				}
			}
			ex.printStackTrace();
			return false;
		}
	}
}
