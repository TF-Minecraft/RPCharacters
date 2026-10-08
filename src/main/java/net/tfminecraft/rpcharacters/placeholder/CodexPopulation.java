package net.tfminecraft.rpcharacters.placeholder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Counts how many Codex accounts have each discovery.
 * Codex 2.9 stores that in {@code plugins/Codex}: category ids are the category
 * file names, and each player file lists discoveries as {@code id;date;millis}.
 */
final class CodexPopulation {
	static final int MAX_FILE_BYTES = 1024 * 1024;

	private static final String PERCENT = "codex_percent_";
	private static final String HOLDERS = "codex_holders_";

	private CodexPopulation() {}

	record Census(Set<String> defined, Map<UUID, Map<String, Set<String>>> players, int unreadable) {
		static final Census EMPTY = new Census(Set.of(), Map.of(), 0);
	}

	record Snapshot(Set<String> defined, Map<String, Integer> holders, int players) {
		static final Snapshot EMPTY = new Snapshot(Set.of(), Map.of(), 0);

		String countText(boolean percent, String category, String discovery) {
			String key = category + ":" + discovery;
			if (!defined.contains(key)) {
				return "";
			}
			int count = holders.getOrDefault(key, 0);
			if (!percent) {
				return Integer.toString(count);
			}
			if (players <= 0) {
				return "0";
			}
			return Long.toString(Math.round(count * 100.0 / players));
		}
	}

	@FunctionalInterface
	interface LineReader {
		List<String> read(Path path) throws IOException;
	}

	@FunctionalInterface
	interface DirectoryLister {
		List<Path> list(Path directory) throws IOException;
	}

	static String answer(Snapshot snapshot, String params) {
		if (params == null) {
			return null;
		}
		Snapshot current = snapshot == null ? Snapshot.EMPTY : snapshot;
		String lower = params.toLowerCase(Locale.ROOT);
		boolean percent;
		String rest;
		if (lower.startsWith(PERCENT)) {
			percent = true;
			rest = params.substring(PERCENT.length());
		} else if (lower.startsWith(HOLDERS)) {
			percent = false;
			rest = params.substring(HOLDERS.length());
		} else {
			return null;
		}
		int colon = rest.indexOf(':');
		if (colon <= 0 || colon >= rest.length() - 1) {
			return "";
		}
		String category = rest.substring(0, colon).trim().toLowerCase(Locale.ROOT);
		String discovery = rest.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
		if (category.isEmpty() || discovery.isEmpty()) {
			return "";
		}
		return current.countText(percent, category, discovery);
	}

	static Census read(Path codexFolder) {
		return read(codexFolder, CodexPopulation::defaultLines, CodexPopulation::defaultList);
	}

	static Census read(Path codexFolder, LineReader reader, DirectoryLister lister) {
		if (codexFolder == null || !Files.isDirectory(codexFolder)) {
			return Census.EMPTY;
		}
		int[] skipped = {0};
		Set<String> defined = new HashSet<>();
		eachYaml(codexFolder.resolve("categories"), lister, skipped, path -> {
			List<String> lines = readLines(path, skipped, reader);
			if (lines == null) {
				return;
			}
			String name = fileStem(path);
			if (name.isEmpty()) {
				return;
			}
			defined.addAll(categoryDiscoveries(name, stripBom(lines)));
		});
		Map<UUID, Map<String, Set<String>>> players = new HashMap<>();
		eachYaml(codexFolder.resolve("players"), lister, skipped, path -> {
			UUID uuid = playerId(path);
			if (uuid == null) {
				return;
			}
			List<String> lines = readLines(path, skipped, reader);
			if (lines == null) {
				return;
			}
			players.put(uuid, parsePlayer(stripBom(lines)));
		});
		return new Census(Set.copyOf(defined), copyPlayers(players), skipped[0]);
	}

	static Snapshot combine(Census census, Map<UUID, Map<String, Set<String>>> live) {
		Census source = census == null ? Census.EMPTY : census;
		Map<UUID, Map<String, Set<String>>> merged = new HashMap<>();
		for (var entry : source.players().entrySet()) {
			if (entry.getKey() != null) {
				merged.put(entry.getKey(), entry.getValue());
			}
		}
		if (live != null) {
			for (var entry : live.entrySet()) {
				if (entry.getKey() == null) {
					continue;
				}
				merged.put(entry.getKey(), entry.getValue() == null ? Map.of() : entry.getValue());
			}
		}
		Map<String, Integer> counts = new HashMap<>();
		for (var unlocks : merged.values()) {
			if (unlocks == null) {
				continue;
			}
			for (var category : unlocks.entrySet()) {
				if (category.getKey() == null || category.getValue() == null) {
					continue;
				}
				String categoryName = category.getKey().trim().toLowerCase(Locale.ROOT);
				for (String id : category.getValue()) {
					if (id == null || id.isBlank()) {
						continue;
					}
					String key = categoryName + ":" + id.trim().toLowerCase(Locale.ROOT);
					if (!source.defined().contains(key)) {
						continue;
					}
					counts.merge(key, 1, Integer::sum);
				}
			}
		}
		return new Snapshot(Set.copyOf(source.defined()), Map.copyOf(counts), merged.size());
	}

	static List<String> defaultLines(Path path) throws IOException {
		return Files.readAllLines(path, StandardCharsets.UTF_8);
	}

	static List<Path> defaultList(Path directory) throws IOException {
		List<Path> paths = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
			for (Path path : stream) {
				paths.add(path);
			}
		}
		return paths;
	}

	private static void eachYaml(Path directory, DirectoryLister lister, int[] skipped, Consumer<Path> consumer) {
		if (!Files.isDirectory(directory)) {
			return;
		}
		List<Path> paths;
		try {
			paths = lister.list(directory);
		} catch (IOException ex) {
			skipped[0]++;
			return;
		}
		for (Path path : paths) {
			consumer.accept(path);
		}
	}

	private static List<String> readLines(Path path, int[] skipped, LineReader reader) {
		try {
			if (!Files.isRegularFile(path)) {
				skipped[0]++;
				return null;
			}
			if (Files.size(path) > MAX_FILE_BYTES) {
				skipped[0]++;
				return null;
			}
			return reader.read(path);
		} catch (IOException ex) {
			skipped[0]++;
			return null;
		}
	}

	private static List<String> stripBom(List<String> lines) {
		if (lines.isEmpty()) {
			return lines;
		}
		String first = lines.get(0);
		if (!first.startsWith("\uFEFF")) {
			return lines;
		}
		List<String> copy = new ArrayList<>(lines);
		copy.set(0, first.substring(1));
		return copy;
	}

	private static String fileStem(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		if (dot <= 0) {
			return "";
		}
		return name.substring(0, dot).toLowerCase(Locale.ROOT);
	}

	private static UUID playerId(Path path) {
		try {
			return UUID.fromString(fileStem(path));
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static Set<String> categoryDiscoveries(String category, List<String> lines) {
		Set<String> found = new HashSet<>();
		boolean inDiscoveries = false;
		for (String line : lines) {
			if (!inDiscoveries) {
				if (isDiscoveriesHeader(line)) {
					inDiscoveries = true;
				}
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
				break;
			}
			String key = indentedKey(line);
			if (key != null) {
				found.add(category + ":" + key);
			}
		}
		return found;
	}

	private static boolean isDiscoveriesHeader(String line) {
		if (line.startsWith(" ") || line.startsWith("\t")) {
			return false;
		}
		int hash = line.indexOf('#');
		String body = (hash >= 0 ? line.substring(0, hash) : line).trim();
		return body.equals("discoveries:");
	}

	private static Map<String, Set<String>> parsePlayer(List<String> lines) {
		Map<String, Set<String>> byCategory = new HashMap<>();
		String category = null;
		boolean inDiscoveries = false;
		for (String line : lines) {
			String key = indentedKey(line);
			if (key != null) {
				category = key;
				inDiscoveries = false;
				continue;
			}
			if (category != null && line.trim().equals("discoveries:")) {
				inDiscoveries = true;
				continue;
			}
			if (!inDiscoveries) {
				continue;
			}
			String item = listItem(line);
			if (item == null) {
				if (!line.trim().startsWith("-")) {
					inDiscoveries = false;
				}
				continue;
			}
			String id = discoveryId(item);
			if (id.isEmpty()) {
				continue;
			}
			byCategory.computeIfAbsent(category, ignored -> new HashSet<>()).add(id);
		}
		return byCategory;
	}

	private static String indentedKey(String line) {
		if (!line.startsWith("  ") || line.startsWith("   ")) {
			return null;
		}
		String body = stripComment(line.substring(2)).trim();
		if (body.startsWith("-") || !body.endsWith(":")) {
			return null;
		}
		String key = stripQuotes(body.substring(0, body.length() - 1).trim());
		if (key.isEmpty() || key.indexOf(' ') >= 0 || key.indexOf(':') >= 0) {
			return null;
		}
		return key.toLowerCase(Locale.ROOT);
	}

	private static String listItem(String line) {
		String trimmed = line.trim();
		if (!trimmed.startsWith("- ")) {
			return null;
		}
		return stripQuotes(stripComment(trimmed.substring(2)).trim());
	}

	private static String discoveryId(String item) {
		int semi = item.indexOf(';');
		String id = semi < 0 ? item : item.substring(0, semi);
		return stripQuotes(id.trim()).trim().toLowerCase(Locale.ROOT);
	}

	private static String stripComment(String value) {
		int hash = value.indexOf('#');
		if (hash >= 0) {
			return value.substring(0, hash);
		}
		return value;
	}

	private static String stripQuotes(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1).trim();
			}
		}
		return value;
	}

	private static Map<UUID, Map<String, Set<String>>> copyPlayers(Map<UUID, Map<String, Set<String>>> players) {
		Map<UUID, Map<String, Set<String>>> copy = new HashMap<>();
		for (var entry : players.entrySet()) {
			Map<String, Set<String>> categories = new HashMap<>();
			for (var category : entry.getValue().entrySet()) {
				categories.put(category.getKey(), Set.copyOf(category.getValue()));
			}
			copy.put(entry.getKey(), Map.copyOf(categories));
		}
		return Map.copyOf(copy);
	}
}
