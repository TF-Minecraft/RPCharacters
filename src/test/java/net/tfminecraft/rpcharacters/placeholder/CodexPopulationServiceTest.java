package net.tfminecraft.rpcharacters.placeholder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class CodexPopulationServiceTest {
	private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	JavaPlugin plugin;
	Logger logger;
	Level previousLevel;
	Handler handler;
	List<String> messages;
	BukkitScheduler scheduler;
	PluginManager plugins;
	MockedStatic<Bukkit> bukkit;
	List<Runnable> async = new ArrayList<>();
	List<Runnable> sync = new ArrayList<>();
	Runnable timer;
	List<BukkitTask> timers = new ArrayList<>();
	boolean throwAsync;
	boolean throwSync;
	boolean throwTimer;

	@BeforeEach void setup() {
		MockBukkit.mock();
		plugin = MockBukkit.createMockPlugin("RPCharacters");
		messages = new ArrayList<>();
		logger = plugin.getLogger();
		previousLevel = logger.getLevel();
		logger.setLevel(Level.ALL);
		handler = new Handler() {
			@Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
			@Override public void flush() {}
			@Override public void close() {}
		};
		logger.addHandler(handler);
		scheduler = mock(BukkitScheduler.class);
		plugins = mock(PluginManager.class);
		bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
			if (throwAsync) {
				throw new IllegalStateException("async off");
			}
			async.add(invocation.getArgument(1));
			return mock(BukkitTask.class);
		});
		when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
			if (throwSync) {
				throw new IllegalStateException("sync off");
			}
			sync.add(invocation.getArgument(1));
			return mock(BukkitTask.class);
		});
		when(scheduler.runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
				.thenAnswer(invocation -> {
					if (throwTimer) {
						throw new IllegalStateException("timer off");
					}
					timer = invocation.getArgument(1);
					BukkitTask task = mock(BukkitTask.class);
					timers.add(task);
					return task;
				});
	}

	@AfterEach void cleanup() {
		CodexPopulationService.stop();
		logger.removeHandler(handler);
		logger.setLevel(previousLevel);
		bukkit.close();
		MockBukkit.unmock();
	}

	@Test void refreshPublishesDiskCountsAndLivePlayersReplaceSavedFiles() throws Exception {
		assertNull(CodexPopulationService.placeholder("name"));
		assertEquals("", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		assertEquals(20L * 60L, CodexPopulationService.REFRESH_PERIOD_TICKS);
		writeSample();
		CodexPopulationService.start(plugin);
		verify(scheduler).runTaskTimerAsynchronously(
				eq(plugin), any(Runnable.class), eq(CodexPopulationService.REFRESH_PERIOD_TICKS), eq(CodexPopulationService.REFRESH_PERIOD_TICKS));
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		assertEquals("0", CodexPopulationService.placeholder("codex_holders_research:bronze"));
		assertEquals(0, count("Skipped"));
		timer.run();
		drain();
		assertEquals(0, count("Skipped"));

		Map<UUID, Map<String, Set<String>>> live = Map.of(
				ALICE, Map.of("research", Set.of("bronze")),
				CARA, Map.of("research", Set.of("alchemy")));
		CodexPopulationService.start(plugin, null, () -> live);
		verify(timers.get(0)).cancel();
		drain();
		assertEquals("67", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		assertEquals("2", CodexPopulationService.placeholder("codex_holders_research:alchemy"));
		assertEquals("33", CodexPopulationService.placeholder("codex_percent_research:bronze"));
		assertEquals("1", CodexPopulationService.placeholder("codex_holders_research:bronze"));
		assertEquals("", CodexPopulationService.placeholder("codex_percent_research:missing"));
	}

	@Test void failedScanKeepsThePreviousSnapshotAndCanRecover() throws Exception {
		writeSample();
		CodexPopulationService.start(plugin, null, Map::of);
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		CodexPopulationService.running().scanner = path -> {
			throw new IllegalStateException("boom");
		};
		timer.run();
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		assertEquals(1, count("Codex population scan failed"));
		CodexPopulationService.running().scanner = CodexPopulation::read;
		timer.run();
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
	}

	@Test void liveReadFailureFallsBackToSavedFilesAndLogsOnceUntilItRecovers() throws Exception {
		writeSample();
		AtomicBoolean fail = new AtomicBoolean(true);
		Map<UUID, Map<String, Set<String>>> live = Map.of(
				ALICE, Map.of("research", Set.of("bronze")),
				CARA, Map.of("research", Set.of("alchemy")));
		CodexPopulationService.start(plugin, null, () -> {
			if (fail.get()) {
				throw new IllegalStateException("down");
			}
			return live;
		});
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		assertEquals(1, count("using saved files"));
		timer.run();
		drain();
		assertEquals(1, count("using saved files"));
		fail.set(false);
		timer.run();
		drain();
		assertEquals("67", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		fail.set(true);
		timer.run();
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		assertEquals(2, count("using saved files"));
	}

	@Test void unreadableFilesAreReportedOnce() throws Exception {
		writeSample();
		Files.createDirectory(codex().resolve("players").resolve(UUID.randomUUID() + ".yml"));
		CodexPopulationService.start(plugin, null, Map::of);
		drain();
		assertEquals(1, count("Skipped"));
		assertTrue(messages.stream().anyMatch(message -> message.contains("Skipped 1 unreadable")));
		timer.run();
		drain();
		assertEquals(1, count("Skipped"));
	}

	@Test void schedulingFailuresStayOnThePreviousSnapshot() throws Exception {
		writeSample();
		throwAsync = true;
		CodexPopulationService.start(plugin, null, Map::of);
		assertEquals(1, count("could not be scheduled"));
		throwAsync = false;
		timer.run();
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));

		throwSync = true;
		timer.run();
		drain();
		assertEquals(1, count("could not be published"));
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
		throwSync = false;
		timer.run();
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));

		throwTimer = true;
		assertDoesNotThrow(() -> CodexPopulationService.start(plugin, null, Map::of));
		assertEquals(1, count("will not refresh on a timer"));
		throwTimer = false;
		drain();
		assertEquals("100", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
	}

	@Test void aStoppedScanDoesNotPublishAndAnOverlappingScanIsIgnored() throws Exception {
		writeSample();
		CodexPopulationService.start(plugin, null, Map::of);
		assertEquals(1, async.size());
		timer.run();
		assertEquals(1, async.size());
		CodexPopulationService.stop();
		timer.run();
		drain();
		assertTrue(sync.isEmpty());
		assertNull(CodexPopulationService.running());

		CodexPopulationService.start(plugin, path -> {
			CodexPopulationService.stop();
			return CodexPopulation.read(path);
		}, Map::of);
		drain();
		assertTrue(sync.isEmpty());
		assertEquals("", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
	}

	@Test void publishAfterStopKeepsTheSnapshotAlreadyInMemory() throws Exception {
		writeSample();
		CodexPopulationService.start(plugin, null, Map::of);
		async.remove(0).run();
		CodexPopulation.Snapshot sentinel = new CodexPopulation.Snapshot(
				Set.of("research:alchemy"), Map.of("research:alchemy", 1), 4);
		CodexPopulationService.running().snapshot = sentinel;
		CodexPopulationService.running().stopped = true;
		sync.remove(0).run();
		assertEquals("25", CodexPopulationService.placeholder("codex_percent_research:alchemy"));
	}

	@Test void missingCodexFolderAndDataFolderStayBlank() {
		CodexPopulationService.stop();
		CodexPopulationService.start(plugin);
		drain();
		assertEquals(1, count("Codex is not installed"));
		assertEquals("", CodexPopulationService.placeholder("codex_holders_research:alchemy"));

		JavaPlugin bare = mock(JavaPlugin.class);
		when(bare.getLogger()).thenReturn(logger);
		when(bare.getDataFolder()).thenReturn(null);
		CodexPopulationService.start(bare);
		drain();
		when(bare.getDataFolder()).thenReturn(new File("RPCharacters"));
		CodexPopulationService.start(bare);
		drain();
		assertTrue(count("Codex is not installed") >= 2);
	}

	private void drain() {
		for (int guard = 0; guard < 8 && (!async.isEmpty() || !sync.isEmpty()); guard++) {
			List<Runnable> asyncNow = List.copyOf(async);
			async.clear();
			asyncNow.forEach(Runnable::run);
			List<Runnable> syncNow = List.copyOf(sync);
			sync.clear();
			syncNow.forEach(Runnable::run);
		}
	}

	private int count(String fragment) {
		return (int) messages.stream().filter(message -> message.contains(fragment)).count();
	}

	private Path codex() {
		return plugin.getDataFolder().getParentFile().toPath().resolve("Codex");
	}

	private void writeSample() throws IOException {
		Path codex = codex();
		Files.createDirectories(codex.resolve("categories"));
		Files.createDirectories(codex.resolve("players"));
		write(codex.resolve("categories/research.yml"),
				"discoveries:",
				"  alchemy:",
				"    name: Alchemy",
				"  bronze:",
				"    name: Bronze");
		write(codex.resolve("players/" + ALICE + ".yml"),
				"categories:",
				"  research:",
				"    discoveries:",
				"    - alchemy;01/01/2026;0");
		write(codex.resolve("players/" + BOB + ".yml"),
				"categories:",
				"  research:",
				"    discoveries:",
				"    - alchemy;01/01/2026;0");
	}

	private static void write(Path path, String... lines) throws IOException {
		Files.writeString(path, String.join("\n", lines) + "\n");
	}
}
