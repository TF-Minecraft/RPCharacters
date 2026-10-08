package net.tfminecraft.rpcharacters.placeholder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code %rpcharacters_codex_percent_<category>:<discovery>%} is the nearest whole
 * percent of Codex accounts that have that entry.
 * {@code %rpcharacters_codex_holders_<category>:<discovery>%} is the count.
 * An unknown category or entry is blank.
 * <p>
 * Lookups read an immutable snapshot. The snapshot is rebuilt off the main thread
 * about once a minute from the Codex files, then the online players' live data
 * replaces their saved files before it is published.
 */
public final class CodexPopulationService {
	static final long REFRESH_PERIOD_TICKS = 20L * 60L;

	private static volatile CodexPopulationService active;

	private final JavaPlugin plugin;
	private final Path codexFolder;
	volatile CodexPopulation.Snapshot snapshot = CodexPopulation.Snapshot.EMPTY;
	volatile boolean stopped;
	private final AtomicBoolean scanning = new AtomicBoolean();
	private BukkitTask task;
	private int reportedUnreadable = -1;
	private boolean liveFailureLogged;

	Scanner scanner = CodexPopulation::read;
	LiveReader liveReader = CodexLiveReader::current;

	@FunctionalInterface
	interface Scanner {
		CodexPopulation.Census scan(Path folder);
	}

	@FunctionalInterface
	interface LiveReader {
		Map<UUID, Map<String, Set<String>>> read() throws Exception;
	}

	private CodexPopulationService(JavaPlugin plugin, Path codexFolder) {
		this.plugin = plugin;
		this.codexFolder = codexFolder;
	}

	public static void start(JavaPlugin plugin) {
		start(plugin, null, null);
	}

	static void start(JavaPlugin plugin, Scanner scanner, LiveReader liveReader) {
		stop();
		CodexPopulationService service = new CodexPopulationService(plugin, codexFolder(plugin));
		if (scanner != null) {
			service.scanner = scanner;
		}
		if (liveReader != null) {
			service.liveReader = liveReader;
		}
		active = service;
		service.arm();
	}

	public static void stop() {
		CodexPopulationService service = active;
		active = null;
		if (service != null) {
			service.cancel();
		}
	}

	static void replaceSnapshot(CodexPopulation.Snapshot snapshot) {
		stop();
		CodexPopulationService service = new CodexPopulationService(null, null);
		service.snapshot = snapshot;
		service.stopped = true;
		active = service;
	}

	static CodexPopulationService running() {
		return active;
	}

	static String placeholder(String params) {
		CodexPopulationService service = active;
		CodexPopulation.Snapshot snapshot = service == null ? CodexPopulation.Snapshot.EMPTY : service.snapshot;
		return CodexPopulation.answer(snapshot, params);
	}

	private static Path codexFolder(JavaPlugin plugin) {
		File data = plugin.getDataFolder();
		if (data == null) {
			return null;
		}
		File parent = data.getParentFile();
		if (parent == null) {
			return null;
		}
		return parent.toPath().resolve("Codex");
	}

	private void arm() {
		if (codexFolder == null || !Files.isDirectory(codexFolder)) {
			plugin.getLogger().info("Codex is not installed; discovery population placeholders are blank.");
		}
		submitRefresh();
		try {
			task = Bukkit.getScheduler().runTaskTimerAsynchronously(
					plugin, this::submitRefresh, REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
		} catch (RuntimeException ex) {
			plugin.getLogger().log(Level.WARNING, "Codex population placeholders will not refresh on a timer", ex);
		}
	}

	private void submitRefresh() {
		if (stopped) {
			return;
		}
		if (!scanning.compareAndSet(false, true)) {
			return;
		}
		try {
			Bukkit.getScheduler().runTaskAsynchronously(plugin, this::readDisk);
		} catch (RuntimeException ex) {
			scanning.set(false);
			plugin.getLogger().log(Level.WARNING, "Codex population scan could not be scheduled", ex);
		}
	}

	private void readDisk() {
		if (stopped) {
			scanning.set(false);
			return;
		}
		final CodexPopulation.Census census;
		try {
			census = scanner.scan(codexFolder);
		} catch (RuntimeException ex) {
			scanning.set(false);
			plugin.getLogger().log(Level.WARNING, "Codex population scan failed", ex);
			return;
		}
		if (stopped) {
			scanning.set(false);
			return;
		}
		try {
			Bukkit.getScheduler().runTask(plugin, () -> publish(census));
		} catch (RuntimeException ex) {
			scanning.set(false);
			plugin.getLogger().log(Level.WARNING, "Codex population scan could not be published", ex);
		}
	}

	private void publish(CodexPopulation.Census census) {
		try {
			if (!stopped) {
				snapshot = CodexPopulation.combine(census, safeLive());
				noteUnreadable(census.unreadable());
			}
		} finally {
			scanning.set(false);
		}
	}

	private Map<UUID, Map<String, Set<String>>> safeLive() {
		try {
			Map<UUID, Map<String, Set<String>>> live = liveReader.read();
			liveFailureLogged = false;
			return live;
		} catch (Exception ex) {
			if (liveFailureLogged) {
				return Map.of();
			}
			liveFailureLogged = true;
			plugin.getLogger().log(Level.WARNING,
					"Could not read online Codex progress; population placeholders are using saved files", ex);
			return Map.of();
		}
	}

	private void noteUnreadable(int count) {
		if (count == reportedUnreadable) {
			return;
		}
		reportedUnreadable = count;
		if (count <= 0) {
			return;
		}
		plugin.getLogger().warning("Skipped " + count + " unreadable Codex file(s) while counting discoveries");
	}

	private void cancel() {
		stopped = true;
		BukkitTask current = task;
		task = null;
		if (current != null) {
			current.cancel();
		}
	}
}
