package net.tfminecraft.rpcharacters.progress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Progress files under {@code <root>/<owner>/}: {@code baseline.yml}, written once, and
 * {@code characters/<id>.yml}. Writes go to a temporary file that is synced, renamed over the target
 * and followed by a directory sync. A file that cannot be read is an error, never an empty record.
 * A replacement whose content matches the revision on disk is not written again.
 *
 * <p>Checkpoints are queued ({@link #queueReplace}): the caller's copy moves to the next revision at
 * once and one writer thread checks and writes the files in order, the newest text of a file that is
 * still waiting replacing the older. {@link #replace} and {@link #read} first wait for the file's
 * queued writes, so a transition or a reload always works on the latest revision. A queued write that
 * fails leaves its file marked: the next write of that copy may then find the disk anywhere from the
 * revision the failed writes replaced up to the one they claimed, and is made even if nothing changed.
 */
final class ProgressStore {
	static final int FORMAT = 1;

	private final Path root;
	private final Executor writer;
	// The newest queued write of each character file, until it is on disk or has failed. Guarded by this.
	private final Map<Path, Queued> queued = new HashMap<>();
	// Files whose last queued write failed. Guarded by this.
	private final Map<Path, Lost> lost = new HashMap<>();
	private boolean closed;
	// Runs on the writer thread as each queued write starts; tests hold the writer here.
	volatile Runnable beforeWrite = () -> {};

	/** A queued revision; {@code expected} is the revision it replaces on disk. Mutable fields are guarded by the store. */
	private static final class Queued {
		final UUID owner;
		final String characterId;
		final Path target;
		final long expected;
		long revision;
		String text;
		boolean running;
		final CompletableFuture<Void> done = new CompletableFuture<>();

		Queued(UUID owner, String characterId, Path target, long expected) {
			this.owner = owner;
			this.characterId = characterId;
			this.target = target;
			this.expected = expected;
		}
	}

	/** Failed queued writes left the disk at {@code oldest} up to {@code revision}, which the live copy holds. */
	private record Lost(long oldest, long revision) {}

	ProgressStore(Path root) {
		this(root, new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "RPCharacters progress writer");
			thread.setDaemon(true);
			return thread;
		}));
	}

	/** {@code writer} must run tasks one at a time, in order. */
	ProgressStore(Path root, Executor writer) {
		this.root = root.toAbsolutePath().normalize();
		this.writer = writer;
	}

	Path baselinePath(UUID owner) {
		return root.resolve(owner.toString()).resolve("baseline.yml");
	}

	Path characterPath(UUID owner, String characterId) throws IOException {
		try {
			if (!UUID.fromString(characterId).toString().equals(characterId)) {
				throw new IllegalArgumentException("Noncanonical character id");
			}
		} catch (IllegalArgumentException | NullPointerException invalid) {
			throw new IOException("Character id must be a canonical UUID", invalid);
		}
		Path directory = root.resolve(owner.toString()).resolve("characters");
		return directory.resolve(characterId + ".yml");
	}

	Optional<ProgressBaseline> readBaseline(UUID owner) throws IOException {
		Path file = baselinePath(owner);
		if (!Files.exists(file)) {
			if (Files.exists(file.getParent().resolve("characters"))) {
				throw new IOException("Progress baseline is missing while character data exists");
			}
			return Optional.empty();
		}
		YamlConfiguration yaml = load(file, owner);
		if (!(yaml.get("characters") instanceof List<?> ids)) {
			throw new IOException("Progress baseline " + file + " has no character list");
		}
		List<String> characters = new ArrayList<>();
		for (Object id : ids) {
			String character = String.valueOf(id);
			characterPath(owner, character);
			if (characters.contains(character)) {
				throw new IOException("Progress baseline has duplicate characters");
			}
			characters.add(character);
		}
		CharacterProgress template = CharacterProgress.readFrom(yaml, owner, null, CharacterProgress.BASELINE);
		return Optional.of(new ProgressBaseline(owner, characters, template));
	}

	void createBaseline(ProgressBaseline baseline) throws IOException {
		YamlConfiguration yaml = header(baseline.ownerId());
		yaml.set("characters", baseline.characterIds());
		baseline.template().writeTo(yaml);
		write(baselinePath(baseline.ownerId()), yaml.saveToString(), true, true);
	}

	/** Waits for the file's queued writes first, so it reads the latest revision. */
	Optional<CharacterProgress> read(UUID owner, String characterId) throws IOException {
		Path file = characterPath(owner, characterId);
		awaitWrites(file);
		return readDisk(owner, characterId, file);
	}

	private static Optional<CharacterProgress> readDisk(UUID owner, String characterId, Path file) throws IOException {
		if (!Files.exists(file)) {
			if (Files.exists(previous(file))) {
				throw new IOException("Progress is missing but its previous revision exists: " + file);
			}
			return Optional.empty();
		}
		YamlConfiguration yaml = load(file, owner);
		if (!characterId.equals(yaml.getString("character"))) {
			throw new IOException("Progress " + file + " belongs to another character");
		}
		String source = yaml.getString("source");
		if (!CharacterProgress.BASELINE.equals(source) && !CharacterProgress.FRESH.equals(source)) {
			throw new IOException("Progress " + file + " has no known source");
		}
		Object rawRevision = yaml.get("revision");
		if (!(rawRevision instanceof Integer || rawRevision instanceof Long)
				|| ((Number) rawRevision).longValue() < 1) {
			throw new IOException("Progress " + file + " has no valid revision");
		}
		long revision = ((Number) rawRevision).longValue();
		CharacterProgress progress = CharacterProgress.readFrom(yaml, owner, characterId, source);
		progress.setRevision(revision);
		progress.setSavedContent(content(progress));
		return Optional.of(progress);
	}

	/** Writes revision 1 of a character that has no file yet. */
	void create(CharacterProgress progress) throws IOException {
		Path target = characterPath(progress.getOwnerId(), progress.getCharacterId());
		String first = text(progress, 1L);
		// Keep evidence of allocation even if the primary disappears before its first checkpoint.
		write(previous(target), first, true, true);
		write(target, first, true, true);
		progress.setRevision(1L);
		progress.setSavedContent(content(progress));
	}

	/**
	 * Writes the next revision on the calling thread, refusing if the file is not the revision this copy
	 * was read as. Content equal to that revision is left in place: the revision stays and nothing is
	 * written. Waits for the file's queued writes first; a failure leaves this copy as it was.
	 */
	void replace(CharacterProgress progress) throws IOException {
		Path target = characterPath(progress.getOwnerId(), progress.getCharacterId());
		awaitWrites(target);
		long expected = progress.getRevision();
		Lost miss = check(progress.getOwnerId(), progress.getCharacterId(), target, expected);
		if (expected == Long.MAX_VALUE) {
			throw new IOException("Progress revision is exhausted");
		}
		String content = content(progress);
		if (content.equals(progress.savedContent()) && miss == null) {
			return;
		}
		long next = expected + 1;
		publish(target, text(progress, next));
		synchronized (this) {
			lost.remove(target);
		}
		progress.setRevision(next);
		progress.setSavedContent(content);
	}

	/**
	 * Queues the next revision for the writer thread and moves this copy to it now; a copy that is not
	 * the file's newest queued revision is refused at once. The future fails, on the writer thread, when
	 * the write does, with the errors {@link #replace} would throw. Unchanged content queues nothing,
	 * unless an earlier queued write of the file failed. After {@link #close} this is {@link #replace}.
	 */
	CompletableFuture<Void> queueReplace(CharacterProgress progress) throws IOException {
		Path target = characterPath(progress.getOwnerId(), progress.getCharacterId());
		synchronized (this) {
			if (!closed) {
				return queue(progress, target);
			}
		}
		replace(progress);
		return CompletableFuture.completedFuture(null);
	}

	private CompletableFuture<Void> queue(CharacterProgress progress, Path target) throws IOException {
		String content = content(progress);
		if (content.equals(progress.savedContent()) && !lost.containsKey(target)) {
			return CompletableFuture.completedFuture(null);
		}
		long expected = progress.getRevision();
		Queued current = queued.get(target);
		if (current != null && current.revision != expected) {
			throw new IOException("Progress for " + progress.getCharacterId() + " is at revision " + current.revision
					+ ", not " + expected);
		}
		if (expected == Long.MAX_VALUE) {
			throw new IOException("Progress revision is exhausted");
		}
		long next = expected + 1;
		String text = text(progress, next);
		// A write still waiting takes the newer text; one already being written is followed by another.
		boolean waiting = current != null && !current.running;
		Queued task = waiting ? current
				: new Queued(progress.getOwnerId(), progress.getCharacterId(), target, expected);
		task.revision = next;
		task.text = text;
		progress.setRevision(next);
		progress.setSavedContent(content);
		if (!waiting) {
			queued.put(target, task);
			writer.execute(() -> run(task));
		}
		return task.done;
	}

	// On the writer thread: the same check and files as replace, then the file's mark follows the outcome.
	private void run(Queued task) {
		long revision;
		String text;
		synchronized (this) {
			task.running = true;
			revision = task.revision;
			text = task.text;
		}
		Throwable failure = null;
		try {
			beforeWrite.run();
			check(task.owner, task.characterId, task.target, task.expected);
			publish(task.target, text);
		} catch (IOException | RuntimeException | Error error) {
			// Even an Error settles the write, as a transition may be waiting for it on the main thread.
			failure = error;
		}
		synchronized (this) {
			if (queued.get(task.target) == task) {
				queued.remove(task.target);
			}
			if (failure == null) {
				lost.remove(task.target);
			} else {
				Lost miss = lost.get(task.target);
				lost.put(task.target, new Lost(miss == null ? task.expected : Math.min(miss.oldest(), task.expected),
						revision));
			}
		}
		if (failure == null) {
			task.done.complete(null);
		} else {
			task.done.completeExceptionally(failure);
		}
	}

	/**
	 * The revision on disk must be the one this copy holds, or lie within the failed queued writes that
	 * moved this copy on. Returns the file's mark, if any.
	 */
	private Lost check(UUID owner, String characterId, Path target, long expected) throws IOException {
		long onDisk = readDisk(owner, characterId, target)
				.orElseThrow(() -> new IOException("Progress for " + characterId + " is missing"))
				.getRevision();
		Lost miss;
		synchronized (this) {
			miss = lost.get(target);
		}
		boolean resumes = miss != null && miss.revision() == expected && onDisk >= miss.oldest() && onDisk <= expected;
		if (onDisk != expected && !resumes) {
			throw new IOException("Progress for " + characterId + " is at revision " + onDisk + ", not " + expected);
		}
		return miss;
	}

	private static void publish(Path target, String text) throws IOException {
		// Both files share a directory, so the sync after the primary also publishes the .prev copy.
		// The .prev file is only kept as evidence; nothing restores from it.
		write(previous(target), Files.readString(target, StandardCharsets.UTF_8), false, false);
		write(target, text, false, true);
	}

	// Called on the main thread only, so no new write of the file can be queued while it waits.
	private void awaitWrites(Path file) {
		CompletableFuture<Void> done;
		synchronized (this) {
			Queued task = queued.get(file);
			done = task == null ? null : task.done;
		}
		if (done != null) {
			done.handle((ignored, failure) -> null).join();
		}
	}

	/** Waits for every queued write; later writes run on the calling thread. */
	void close() {
		List<CompletableFuture<Void>> pending;
		synchronized (this) {
			closed = true;
			pending = queued.values().stream().map(task -> task.done).toList();
		}
		for (CompletableFuture<Void> done : pending) {
			done.handle((ignored, failure) -> null).join();
		}
		if (writer instanceof ExecutorService service) {
			service.shutdown();
		}
	}

	/** What a revision records, without the fields that change on every write. */
	private static String content(CharacterProgress progress) {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set("character", progress.getCharacterId());
		yaml.set("source", progress.getSource());
		progress.writeTo(yaml);
		return yaml.saveToString();
	}

	private static String text(CharacterProgress progress, long revision) {
		YamlConfiguration yaml = header(progress.getOwnerId());
		yaml.set("character", progress.getCharacterId());
		yaml.set("revision", revision);
		yaml.set("source", progress.getSource());
		progress.writeTo(yaml);
		return yaml.saveToString();
	}

	private static Path previous(Path target) {
		return target.resolveSibling(target.getFileName() + ".prev");
	}

	private static YamlConfiguration header(UUID owner) {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set("format", FORMAT);
		yaml.set("owner", owner.toString());
		yaml.set("saved-at", System.currentTimeMillis());
		return yaml;
	}

	private static YamlConfiguration load(Path file, UUID owner) throws IOException {
		YamlConfiguration yaml = new YamlConfiguration();
		try {
			yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
		} catch (InvalidConfigurationException malformed) {
			throw new IOException("Progress " + file + " is not valid YAML", malformed);
		}
		if (!Integer.valueOf(FORMAT).equals(yaml.get("format")) || !owner.toString().equals(yaml.getString("owner"))) {
			throw new IOException("Progress " + file + " has another format or owner");
		}
		return yaml;
	}

	private static void write(Path target, String text, boolean createOnly, boolean syncDirectory)
			throws IOException {
		Path directory = target.getParent();
		ensureDirectory(directory);
		if (createOnly && Files.exists(target)) {
			throw new FileAlreadyExistsException(target.toString());
		}
		Path staged = Files.createTempFile(directory, ".progress-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
				ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
				while (bytes.hasRemaining()) {
					channel.write(bytes);
				}
				channel.force(true);
			}
			if (createOnly) {
				// Atomic create-only publication: ATOMIC_MOVE may replace an existing target on Unix.
				Files.createLink(target, staged);
				Files.delete(staged);
			} else {
				Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException failure) {
			Files.deleteIfExists(staged);
			throw failure;
		}
		if (syncDirectory) {
			syncDirectory(directory);
		}
	}

	private static void ensureDirectory(Path directory) throws IOException {
		if (Files.isDirectory(directory)) {
			return;
		}
		ensureDirectory(directory.getParent());
		Files.createDirectory(directory);
		syncDirectory(directory.getParent());
	}

	private static void syncDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		}
	}
}
