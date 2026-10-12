package net.tfminecraft.rpcharacters.identity;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;

/** Main-thread directory of immutable identities; lookups never read files or load offline players. */
public final class CharacterDirectory {

	private final Map<UUID, Map<UUID, CharacterInfo>> accounts = new HashMap<>();
	private final Map<UUID, UUID> owners = new HashMap<>();
	private final Set<UUID> unavailable = new HashSet<>();
	/** Characters saved with the legacy {@code active} flag, which predates session timestamps. */
	private final Set<CharacterRef> flaggedActive = new HashSet<>();
	private final Consumer<String> warn;

	private CharacterDirectory(Consumer<String> warn) {
		this.warn = Objects.requireNonNull(warn, "warn");
	}

	/** Loads the data directory containing playerdata and characterdata before service registration. */
	public static CharacterDirectory load(Path dataRoot, Consumer<String> warn) throws IOException {
		CharacterDirectory directory = new CharacterDirectory(warn);
		if (Files.notExists(dataRoot, LinkOption.NOFOLLOW_LINKS)) return directory;
		if (!Files.isDirectory(dataRoot)) throw new IOException("Cannot read character data root " + dataRoot);
		directory.loadAccounts(dataRoot.resolve("playerdata"));
		directory.loadCharacters(dataRoot.resolve("characterdata"));
		return directory;
	}

	/** Empty optional means unreadable or ambiguous; an empty list means no characters. */
	public Optional<List<CharacterInfo>> characters(UUID ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		if (unavailable.contains(ownerId)) return Optional.empty();
		return Optional.of(List.copyOf(accounts.getOrDefault(ownerId, Map.of()).values()));
	}

	public Optional<CharacterInfo> info(UUID characterId) {
		UUID ownerId = owners.get(Objects.requireNonNull(characterId, "characterId"));
		if (ownerId == null || unavailable.contains(ownerId)) return Optional.empty();
		return Optional.ofNullable(accounts.getOrDefault(ownerId, Map.of()).get(characterId));
	}

	/**
	 * The latest session start wins. Records saved before session timestamps fall back to the
	 * character still flagged active; any timestamp beats a flag alone.
	 */
	public Optional<CharacterInfo> lastPlayed(UUID ownerId) {
		return characters(ownerId).flatMap(characters -> characters.stream()
				.filter(character -> character.lastPlayedAt() != null || flaggedActive.contains(character.ref()))
				.max(Comparator.comparing(CharacterInfo::lastPlayedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(character -> character.ref().characterId())));
	}

	/** Updates a saved/live snapshot; only a fresh successful load can clear quarantine. */
	public void upsert(UUID ownerId, RPCharacter character) {
		Objects.requireNonNull(ownerId, "ownerId");
		CharacterInfo info;
		try {
			info = snapshot(ownerId, character);
		} catch (RuntimeException error) {
			quarantine(ownerId, "Invalid live character: " + error.getMessage());
			return;
		}
		upsert(ownerId, info, Boolean.TRUE.equals(character.isActive()));
	}

	/** Updates from a snapshot taken earlier, such as when a save finished off the main thread. */
	public void upsert(UUID ownerId, CharacterInfo info, boolean active) {
		Objects.requireNonNull(ownerId, "ownerId");
		UUID id = info.ref().characterId();
		claim(ownerId, id);
		accounts.computeIfAbsent(ownerId, ignored -> new LinkedHashMap<>()).put(id, info);
		flag(info.ref(), active);
	}

	public void remove(UUID ownerId, String characterId) {
		Objects.requireNonNull(ownerId, "ownerId");
		try {
			UUID id = uuid(characterId);
			Map<UUID, CharacterInfo> characters = accounts.get(ownerId);
			if (characters != null) characters.remove(id);
			flaggedActive.remove(new CharacterRef(ownerId, id));
			if (!unavailable.contains(ownerId)) owners.remove(id, ownerId);
		} catch (RuntimeException error) {
			quarantine(ownerId, "Invalid removed character ID: " + error.getMessage());
		}
	}

	/** Converts canonical live data for service overlays; callers must preserve directory quarantine. */
	public static CharacterInfo snapshot(UUID ownerId, RPCharacter character) {
		Set<String> traits = new LinkedHashSet<>();
		for (Trait trait : character.getTraits()) traits.add(string(trait.getId()));
		return new CharacterInfo(new CharacterRef(ownerId, uuid(character.getId())),
				plainName(character.getName()), CharacterStatus.valueOf(character.getStatus().name()),
				character.getRace() == null ? null : string(character.getRace().getId()), traits,
				character.getOnlinePlaytimeSeconds(), character.getLastPlayedAt());
	}

	private void loadAccounts(Path root) throws IOException {
		Set<UUID> seen = new HashSet<>();
		for (Path file : entries(root, true)) {
			if (staging(file)) continue;
			UUID ownerId = ownerId(file, true);
			if (ownerId == null) continue;
			if (!seen.add(ownerId)) quarantine(ownerId, "Duplicate account file " + file);
			try {
				readObject(file);
			} catch (IOException | RuntimeException error) {
				quarantine(ownerId, "Cannot read account " + file + ": " + error.getMessage());
			}
		}
	}

	private void loadCharacters(Path root) throws IOException {
		Set<UUID> seen = new HashSet<>();
		for (Path folder : entries(root, true)) {
			UUID ownerId = ownerId(folder, false);
			if (ownerId == null) continue;
			if (!seen.add(ownerId)) quarantine(ownerId, "Duplicate owner directory " + folder);
			Map<UUID, CharacterInfo> characters = accounts.computeIfAbsent(ownerId, ignored -> new LinkedHashMap<>());
			try {
				Set<UUID> fileIds = new HashSet<>();
				for (Path file : entries(folder, false)) {
					if (!staging(file)) readCharacter(ownerId, characters, fileIds, file);
				}
			} catch (IOException | RuntimeException error) {
				quarantine(ownerId, "Cannot list characters " + folder + ": " + error.getMessage());
			}
		}
	}

	private void readCharacter(UUID ownerId, Map<UUID, CharacterInfo> characters, Set<UUID> fileIds, Path file) {
		UUID fileId = null;
		try {
			fileId = uuid(stem(file));
			claim(ownerId, fileId);
			if (!fileIds.add(fileId)) quarantine(ownerId, "Duplicate character file " + file);
		} catch (RuntimeException error) {
			quarantine(ownerId, "Invalid character filename " + file + ": " + error.getMessage());
		}
		try {
			JSONObject json = readObject(file);
			UUID id = uuid(string(json.get("id")));
			claim(ownerId, id);
			if (!id.equals(fileId)) throw new IllegalArgumentException("Filename and character ID differ");
			Set<String> traits = new LinkedHashSet<>();
			if (!(json.get("traits") instanceof JSONArray savedTraits)) {
				throw new IllegalArgumentException("Invalid traits");
			}
			for (Object trait : savedTraits) traits.add(string(trait));
			String race = json.get("race") == null ? null : string(json.get("race"));
			long seconds = json.containsKey("online-playtime-seconds") ? number(json.get("online-playtime-seconds")) : 0;
			Instant lastPlayed = json.get("last-played-at-ms") == null ? null : Instant.ofEpochMilli(number(json.get("last-played-at-ms")));
			CharacterInfo info = new CharacterInfo(new CharacterRef(ownerId, id), plainName(string(json.get("name"))),
					CharacterStatus.valueOf(string(json.get("status")).toUpperCase(Locale.ROOT)), race, traits, seconds, lastPlayed);
			characters.put(id, info);
			flag(info.ref(), Boolean.parseBoolean(String.valueOf(json.get("active"))));
		} catch (IOException | RuntimeException error) {
			quarantine(ownerId, "Cannot read character " + file + ": " + error.getMessage());
		}
	}

	private void claim(UUID ownerId, UUID characterId) {
		UUID previous = owners.putIfAbsent(characterId, ownerId);
		if (previous != null && !previous.equals(ownerId)) {
			quarantine(previous, "Duplicate character UUID " + characterId + " also claimed by " + ownerId);
			quarantine(ownerId, "Duplicate character UUID " + characterId + " also claimed by " + previous);
		}
	}

	private void flag(CharacterRef ref, boolean active) {
		if (active) flaggedActive.add(ref);
		else flaggedActive.remove(ref);
	}

	private void quarantine(UUID ownerId, String reason) {
		unavailable.add(ownerId);
		warn.accept("Character directory unavailable for " + ownerId + ": " + reason);
	}

	/** Null for stray entries such as backups or {@code .DS_Store}, which belong to no account. */
	private UUID ownerId(Path path, boolean accountFile) {
		try {
			return uuid(accountFile ? stem(path) : path.getFileName().toString());
		} catch (IllegalArgumentException error) {
			warn.accept("Skipped " + path + ": the name is not an account UUID");
			return null;
		}
	}

	private static List<Path> entries(Path root, boolean optional) throws IOException {
		if (optional && Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		try (var entries = Files.list(root)) {
			return entries.sorted().toList();
		}
	}

	private static JSONObject readObject(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			if (new JSONParser().parse(reader) instanceof JSONObject object) return object;
			throw new IllegalArgumentException("Expected a JSON object");
		} catch (ParseException error) {
			throw new IllegalArgumentException("Invalid JSON", error);
		}
	}

	private static String stem(Path path) {
		String name = path.getFileName().toString();
		return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
	}

	private static boolean staging(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith(".rpcharacters-") && name.endsWith(".tmp");
	}

	private static UUID uuid(String value) {
		UUID id = UUID.fromString(value);
		if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Noncanonical UUID " + value);
		return id;
	}

	private static String string(Object value) {
		if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Expected a nonblank string");
		return text;
	}

	private static String plainName(String name) {
		return string(ClueFormatter.stripColor(string(name)).trim());
	}

	private static long number(Object value) {
		if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected a number");
		return new BigDecimal(number.toString()).longValueExact();
	}
}
