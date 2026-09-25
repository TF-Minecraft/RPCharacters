package net.tfminecraft.rpcharacters.playtime;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;

/** Read-only view of saved character playtime, overlaid with current online data. */
public final class CharacterPlaytimeDirectory {

	private record Id(UUID ownerId, String characterId) {}

	public record Entry(UUID ownerId, String characterId, String name, int seconds, Status status, boolean hidden) {
		private Id id() { return new Id(ownerId, characterId); }
	}

	private static final Map<Id, Entry> SAVED = new ConcurrentHashMap<>();

	private CharacterPlaytimeDirectory() {}

	/** Load lightweight records once at startup; never instantiate offline characters or rewrite their files. */
	public static void loadFromDisk(Path root, Consumer<String> warn) {
		SAVED.clear();
		if (!Files.isDirectory(root)) return;
		try (var owners = Files.list(root)) {
			for (Path owner : owners.filter(Files::isDirectory).toList()) {
				UUID ownerId;
				try {
					ownerId = UUID.fromString(owner.getFileName().toString());
				} catch (IllegalArgumentException ignored) {
					continue;
				}
				try (var files = Files.list(owner)) {
					for (Path file : files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).toList()) {
						try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
							JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
							String id = json.get("id").getAsString();
							String name = json.has("alias") && !json.get("alias").isJsonNull()
									? json.get("alias").getAsString() : "";
							if (name.isBlank()) name = json.get("name").getAsString();
							name = ClueFormatter.stripColor(name);
							if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Missing character identity");
							Status status = Status.valueOf(json.get("status").getAsString().toUpperCase(Locale.ROOT));
							int seconds = json.has("online-playtime-seconds")
									? (int) Math.min(Integer.MAX_VALUE, Math.max(0L, json.get("online-playtime-seconds").getAsLong())) : 0;
							boolean hidden = json.has("hidden") && json.get("hidden").getAsBoolean();
							Entry entry = new Entry(ownerId, id, name, seconds, status, hidden);
							SAVED.put(entry.id(), entry);
						} catch (IOException | RuntimeException e) {
							warn.accept("Skipped playtime record " + file + ": " + e.getMessage());
						}
					}
				} catch (IOException e) {
					warn.accept("Could not read character playtime directory " + owner + ": " + e.getMessage());
				}
			}
		} catch (IOException e) {
			warn.accept("Could not read character playtime directory " + root + ": " + e.getMessage());
		}
	}

	public static void upsert(UUID ownerId, RPCharacter character) {
		Entry entry = fromLive(ownerId, character);
		if (entry != null) SAVED.put(entry.id(), entry);
	}

	public static void remove(UUID ownerId, String characterId) {
		SAVED.remove(new Id(ownerId, characterId));
	}

	/** Includes inactive and deceased characters; hidden identities are never public. Main thread only. */
	public static List<Entry> getAll() {
		Map<Id, Entry> entries = new HashMap<>(SAVED);
		for (PlayerData owner : PlayerManager.getOnlineData()) {
			for (RPCharacter character : owner.getCharacters()) {
				Entry entry = fromLive(owner.getUniqueId(), character);
				if (entry != null) entries.put(entry.id(), entry);
			}
		}
		List<Entry> result = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (!entry.hidden()) result.add(entry);
		}
		return result;
	}

	private static Entry fromLive(UUID ownerId, RPCharacter character) {
		if (ownerId == null || character == null || character.getId() == null) return null;
		return new Entry(ownerId, character.getId(), character.getEffectiveDisplayPlain(),
				character.getOnlinePlaytimeSeconds(), character.getStatus(), character.isHidden());
	}
}
