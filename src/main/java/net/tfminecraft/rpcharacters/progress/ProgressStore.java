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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Progress files under {@code <root>/<owner>/}: {@code baseline.yml}, written once, and
 * {@code characters/<id>.yml}. Writes go to a temporary file that is synced, renamed over the target
 * and followed by a directory sync. A file that cannot be read is an error, never an empty record.
 */
final class ProgressStore {
	static final int FORMAT = 1;

	private final Path root;

	ProgressStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
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
		write(baselinePath(baseline.ownerId()), yaml.saveToString(), true);
	}

	Optional<CharacterProgress> read(UUID owner, String characterId) throws IOException {
		Path file = characterPath(owner, characterId);
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
		return Optional.of(progress);
	}

	/** Writes revision 1 of a character that has no file yet. */
	void create(CharacterProgress progress) throws IOException {
		Path target = characterPath(progress.getOwnerId(), progress.getCharacterId());
		String first = text(progress, 1L);
		// Keep evidence of allocation even if the primary disappears before its first checkpoint.
		write(previous(target), first, true);
		write(target, first, true);
		progress.setRevision(1L);
	}

	/** Writes the next revision, refusing if the file is not the revision this copy was read as. */
	void replace(CharacterProgress progress) throws IOException {
		long onDisk = read(progress.getOwnerId(), progress.getCharacterId())
				.orElseThrow(() -> new IOException("Progress for " + progress.getCharacterId() + " is missing"))
				.getRevision();
		if (onDisk != progress.getRevision()) {
			throw new IOException("Progress for " + progress.getCharacterId() + " is at revision " + onDisk
					+ ", not " + progress.getRevision());
		}
		if (onDisk == Long.MAX_VALUE) {
			throw new IOException("Progress revision is exhausted");
		}
		long next = onDisk + 1;
		Path target = characterPath(progress.getOwnerId(), progress.getCharacterId());
		write(previous(target), Files.readString(target, StandardCharsets.UTF_8), false);
		write(target, text(progress, next), false);
		progress.setRevision(next);
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

	private static void write(Path target, String text, boolean createOnly) throws IOException {
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
		syncDirectory(directory);
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
