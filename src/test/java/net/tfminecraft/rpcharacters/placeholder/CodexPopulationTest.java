package net.tfminecraft.rpcharacters.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexPopulationTest {
	@TempDir Path temp;

	@Test void savedFilesMatchTheCodexLayoutAndIgnoreUnknownOrClosedLists() throws Exception {
		Path codex = temp.resolve("Codex");
		Files.createDirectories(codex.resolve("categories"));
		Files.createDirectories(codex.resolve("players"));
		write(codex.resolve("categories/achievements.yml"),
				"\uFEFFdiscoveries:",
				"  die:",
				"    name: Die",
				"",
				"# comment",
				"  poi_100:",
				"    name: Places");
		write(codex.resolve("categories/Research.yml"),
				"discoveries:",
				"  alchemy: # science",
				"    name: Alchemy",
				"  \"bronze\":",
				"    name: Bronze",
				"  stray:",
				"    name: Stray",
				"rewards:",
				"  secret:",
				"    name: Hidden");
		write(codex.resolve("categories/special.yml"),
				"name: Special",
				"  not a discovery",
				"discoveries:",
				"  the_lost_past:",
				"    name: Lost");
		UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");
		write(codex.resolve("players/" + first + ".yml"),
				"name: Example",
				"categories:",
				"  my category:",
				"  note without colon",
				"  achievements:",
				"    completed: false",
				"    discoveries:",
				"    - die;20/09/2026;0",
				"    - die;21/09/2026;0",
				"    - \";01/01/2026;0\"",
				"  Research:",
				"    completed: false",
				"    discoveries:",
				"    - \"Alchemy;02/02/2026;0\"",
				"    completed: false",
				"    - stray;03/03/2026;0",
				"  poi:",
				"    completed: false",
				"    discoveries: []");
		Files.writeString(codex.resolve("players/" + second.toString().toUpperCase() + ".yml"),
				"name: B\r\ncategories:\r\n  achievements:\r\n    discoveries:\r\n    - die;01/01/2026;0\r\n");
		write(codex.resolve("players/notes.yml"), "name: not a player");

		CodexPopulation.Census census = CodexPopulation.read(codex);
		assertEquals(0, census.unreadable());
		assertEquals(2, census.players().size());
		assertTrue(census.defined().contains("research:alchemy"));
		assertTrue(census.defined().contains("research:bronze"));
		assertFalse(census.defined().contains("research:secret"));
		CodexPopulation.Snapshot snapshot = CodexPopulation.combine(census, Map.of());
		assertEquals("2", CodexPopulation.answer(snapshot, "codex_holders_achievements:die"));
		assertEquals("100", CodexPopulation.answer(snapshot, "codex_percent_achievements:die"));
		assertEquals("1", CodexPopulation.answer(snapshot, "codex_holders_research:alchemy"));
		assertEquals("50", CodexPopulation.answer(snapshot, "codex_percent_research:alchemy"));
		assertEquals("0", CodexPopulation.answer(snapshot, "codex_holders_research:bronze"));
		assertEquals("0", CodexPopulation.answer(snapshot, "codex_percent_research:stray"));
		assertEquals("0", CodexPopulation.answer(snapshot, "codex_holders_achievements:poi_100"));
		assertEquals("0", CodexPopulation.answer(snapshot, "codex_percent_special:the_lost_past"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_percent_research:secret"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_holders_research:nope"));
		assertEquals(census.defined(), snapshot.defined());
		assertEquals(2, snapshot.players());
	}

	@Test void onlinePlayersReplaceTheirSavedFiles() {
		UUID saved = UUID.randomUUID();
		UUID online = UUID.randomUUID();
		UUID extra = UUID.randomUUID();
		Set<String> defined = Set.of("research:alchemy", "research:bronze");
		Map<UUID, Map<String, Set<String>>> disk = new HashMap<>();
		disk.put(saved, Map.of("research", Set.of("alchemy")));
		disk.put(online, Map.of("Research", Set.of(" Alchemy ")));
		disk.put(null, Map.of("research", Set.of("alchemy")));
		Map<UUID, Map<String, Set<String>>> live = new HashMap<>();
		live.put(online, Map.of("research", Set.of("bronze")));
		live.put(extra, null);
		live.put(null, Map.of("research", Set.of("alchemy")));
		CodexPopulation.Snapshot snapshot = CodexPopulation.combine(new CodexPopulation.Census(defined, disk, 0), live);
		assertEquals(3, snapshot.players());
		assertEquals("1", CodexPopulation.answer(snapshot, "codex_holders_research:alchemy"));
		assertEquals("1", CodexPopulation.answer(snapshot, "codex_holders_research:bronze"));
		assertEquals("33", CodexPopulation.answer(snapshot, "codex_percent_research:alchemy"));
	}

	@Test void blankAndUnknownDiscoveriesDoNotCount() {
		UUID player = UUID.randomUUID();
		Map<String, Set<String>> unlocks = new HashMap<>();
		Set<String> ids = new HashSet<>();
		ids.add(null);
		ids.add("  ");
		ids.add("nope");
		ids.add("alchemy");
		unlocks.put(null, Set.of("alchemy"));
		unlocks.put("research", null);
		unlocks.put("Research", ids);
		Map<UUID, Map<String, Set<String>>> disk = new HashMap<>();
		disk.put(player, null);
		disk.put(UUID.randomUUID(), unlocks);
		CodexPopulation.Snapshot snapshot = CodexPopulation.combine(
				new CodexPopulation.Census(Set.of("research:alchemy"), disk, 0), null);
		assertEquals(2, snapshot.players());
		assertEquals("1", CodexPopulation.answer(snapshot, "codex_holders_research:alchemy"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_percent_research:nope"));
	}

	@Test void percentRoundsToTheNearestWholeNumberAndZeroPopulationIsZero() {
		assertEquals("1", percent(1, 199));
		assertEquals("0", percent(1, 200));
		assertEquals("33", percent(1, 2));
		assertEquals("67", percent(2, 1));
		assertEquals("50", percent(1, 1));
		assertEquals("0", percent(0, 2));
		CodexPopulation.Snapshot empty = CodexPopulation.combine(
				new CodexPopulation.Census(Set.of("research:alchemy"), Map.of(), 0), Map.of());
		assertEquals("0", CodexPopulation.answer(empty, "codex_percent_research:alchemy"));
		assertEquals("0", CodexPopulation.answer(empty, "codex_holders_research:alchemy"));
		assertEquals(0, empty.holders().size());
	}

	@Test void placeholderTextRejectsMalformedNamesAndUnknownSnapshots() {
		CodexPopulation.Snapshot snapshot = new CodexPopulation.Snapshot(
				Set.of("research:alchemy"), Map.of("research:alchemy", 1), 2);
		assertNull(CodexPopulation.answer(snapshot, null));
		assertNull(CodexPopulation.answer(snapshot, "name"));
		assertNull(CodexPopulation.answer(snapshot, "codex_percent"));
		assertNull(CodexPopulation.answer(snapshot, "codex_holders"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_percent_research"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_percent_:alchemy"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_holders_research:"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_percent_   :alchemy"));
		assertEquals("", CodexPopulation.answer(snapshot, "codex_holders_research:   "));
		assertEquals("", CodexPopulation.answer(null, "codex_percent_research:alchemy"));
		assertEquals("", CodexPopulation.answer(CodexPopulation.Snapshot.EMPTY, "codex_percent_research:alchemy"));
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("50", CodexPopulation.answer(snapshot, "CODEX_PERCENT_RESEARCH:IGNITIUM".replace("IGNITIUM", "ALCHEMY")));
			assertEquals("1", CodexPopulation.answer(snapshot, "CODEX_HOLDERS_RESEARCH:ALCHEMY"));
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test void missingCorruptAndOversizedFilesAreSkipped() throws Exception {
		assertEquals(0, CodexPopulation.read(null).players().size());
		Path file = temp.resolve("not-a-folder");
		Files.writeString(file, "x");
		assertEquals(0, CodexPopulation.read(file).defined().size());
		Path empty = temp.resolve("empty-codex");
		Files.createDirectories(empty);
		assertEquals(0, CodexPopulation.read(empty).unreadable());

		Path codex = temp.resolve("partial");
		Files.createDirectories(codex.resolve("categories"));
		write(codex.resolve("categories/research.yml"), "discoveries:", "  alchemy:", "    name: Alchemy");
		CodexPopulation.Census categoriesOnly = CodexPopulation.read(codex);
		assertEquals(1, categoriesOnly.defined().size());
		assertEquals(0, categoriesOnly.players().size());
		assertEquals("0", CodexPopulation.answer(
				CodexPopulation.combine(categoriesOnly, Map.of()), "codex_percent_research:alchemy"));
		Path blank = temp.resolve("blank-player");
		Files.createDirectories(blank.resolve("players"));
		Files.writeString(blank.resolve("players/" + UUID.randomUUID() + ".yml"), "");
		CodexPopulation.Census blankCensus = CodexPopulation.read(blank);
		assertEquals(1, blankCensus.players().size());
		assertTrue(blankCensus.players().values().iterator().next().isEmpty());

		Path broken = temp.resolve("broken");
		Files.createDirectories(broken.resolve("categories"));
		Files.createDirectories(broken.resolve("players"));
		UUID valid = UUID.randomUUID();
		write(broken.resolve("players/" + valid + ".yml"),
				"categories:", "  research:", "    discoveries:", "    - alchemy;01/01/2026;0");
		write(broken.resolve("categories/research.yml"), "discoveries:", "  alchemy:", "    name: Alchemy");
		Files.write(broken.resolve("players/" + UUID.randomUUID() + ".yml"), new byte[CodexPopulation.MAX_FILE_BYTES + 1]);
		Files.createDirectory(broken.resolve("players/" + UUID.randomUUID() + ".yml"));
		write(broken.resolve("players/notes.yml"), "nope");
		CodexPopulation.Census skipped = CodexPopulation.read(broken);
		assertEquals(2, skipped.unreadable());
		assertEquals(1, skipped.players().size());
		assertTrue(skipped.defined().contains("research:alchemy"));

		Path dot = broken.resolve("categories/.yml");
		Files.writeString(dot, "discoveries:\n  ignored:\n");
		CodexPopulation.Census named = CodexPopulation.read(broken, CodexPopulation::defaultLines, dir -> {
			if ("categories".equals(dir.getFileName().toString())) {
				return List.of(dot);
			}
			return List.of();
		});
		assertTrue(named.defined().isEmpty());
		assertFalse(named.defined().contains("ignored"));
	}

	@Test void unreadableDirectoriesAndFilesDoNotFailTheScan() throws Exception {
		Path codex = temp.resolve("io");
		Files.createDirectories(codex.resolve("categories"));
		Files.createDirectories(codex.resolve("players"));
		write(codex.resolve("categories/research.yml"), "discoveries:", "  alchemy:", "    name: Alchemy");
		write(codex.resolve("players/" + UUID.randomUUID() + ".yml"),
				"categories:", "  research:", "    discoveries:", "    - alchemy;01/01/2026;0");
		CodexPopulation.Census lines = CodexPopulation.read(codex, path -> {
			throw new IOException("disk");
		}, CodexPopulation::defaultList);
		assertEquals(2, lines.unreadable());
		assertTrue(lines.defined().isEmpty());
		assertTrue(lines.players().isEmpty());

		CodexPopulation.Census listed = CodexPopulation.read(codex, CodexPopulation::defaultLines, dir -> {
			throw new IOException("list");
		});
		assertEquals(2, listed.unreadable());
		assertTrue(listed.defined().isEmpty());
	}

	@Test void utilityConstructorCanBeInvoked() throws Exception {
		var ctor = CodexPopulation.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		ctor.newInstance();
	}

	private static String percent(int holders, int emptyPlayers) {
		Map<UUID, Map<String, Set<String>>> players = new HashMap<>();
		for (int i = 0; i < holders; i++) {
			players.put(UUID.randomUUID(), Map.of("research", Set.of("alchemy")));
		}
		for (int i = 0; i < emptyPlayers; i++) {
			players.put(UUID.randomUUID(), Map.of());
		}
		CodexPopulation.Snapshot snapshot = CodexPopulation.combine(
				new CodexPopulation.Census(Set.of("research:alchemy"), players, 0), Map.of());
		return CodexPopulation.answer(snapshot, "codex_percent_research:alchemy");
	}

	private static void write(Path path, String... lines) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, String.join("\n", lines) + "\n");
	}
}
