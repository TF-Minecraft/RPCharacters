package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseSaveTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void roundTripsBooleanDefaults(boolean value) throws Exception {
		HashMap<String, Object> defaults = new HashMap<>();
		defaults.put("flag", value);
		assertEquals(value, saveAndRead(new JSONObject(), defaults).get("flag"));
	}

	@ParameterizedTest
	@ValueSource(longs = {0L, 1_790_000_000_123L, Long.MAX_VALUE, Long.MIN_VALUE})
	void roundTripsLongTimestampsWithoutLosingPrecision(long value) throws Exception {
		HashMap<String, Object> defaults = new HashMap<>();
		defaults.put("last-character-switch-ms", value);
		defaults.put("investigation-regen-ms", value);
		JSONObject saved = saveAndRead(new JSONObject(), defaults);
		assertEquals(value, saved.get("last-character-switch-ms"));
		assertEquals(value, saved.get("investigation-regen-ms"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void existingValuesStillTakePrecedenceOverDefaults() throws Exception {
		JSONObject existing = new JSONObject();
		existing.put("flag", false);
		existing.put("timestamp", 1_790_000_000_123L);
		HashMap<String, Object> defaults = new HashMap<>();
		defaults.put("flag", true);
		defaults.put("timestamp", 0L);
		JSONObject saved = saveAndRead(existing, defaults);
		assertEquals(false, saved.get("flag"));
		assertEquals(1_790_000_000_123L, saved.get("timestamp"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void preservesLegacyStringsAndSupportedValues() throws Exception {
		JSONObject nested = new JSONObject();
		nested.put("flag", true);
		nested.put("timestamp", Long.MAX_VALUE);
		JSONArray array = new JSONArray();
		array.add(false);
		HashMap<String, Object> defaults = new HashMap<>();
		defaults.put("active", "true");
		defaults.put("eighteen", "false");
		defaults.put("points", 7);
		defaults.put("food-value", 2.5);
		defaults.put("nested", nested);
		defaults.put("array", array);
		JSONObject saved = saveAndRead(new JSONObject(), defaults);
		assertEquals("true", saved.get("active"));
		assertEquals("false", saved.get("eighteen"));
		assertEquals(7, ((Number) saved.get("points")).intValue());
		assertEquals(2.5, saved.get("food-value"));
		assertEquals(nested, saved.get("nested"));
		assertEquals(array, saved.get("array"));
	}

	private JSONObject saveAndRead(JSONObject existing, HashMap<String, Object> defaults) throws Exception {
		Database database = new Database();
		Field json = Database.class.getDeclaredField("json");
		json.setAccessible(true);
		json.set(database, existing);
		Path file = directory.resolve("data.json");
		assertTrue(database.save(file.toFile(), defaults));
		return (JSONObject) new JSONParser().parse(Files.readString(file));
	}
}
