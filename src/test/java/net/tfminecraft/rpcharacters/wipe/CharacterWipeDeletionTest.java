package net.tfminecraft.rpcharacters.wipe;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CharacterWipeDeletionTest {
	private final UUID owner = UUID.randomUUID();
	private Path folder;
	private Path file;

	@BeforeEach
	void prepare() throws Exception {
		folder = Files.createDirectories(Path.of("plugins/RPCharacters/data/characterdata", owner.toString()));
		file = folder.resolve("test.json");
	}

	@AfterEach
	void cleanup() throws Exception {
		if (Files.isDirectory(file)) Files.deleteIfExists(file.resolve("blocking-child"));
		Files.deleteIfExists(file);
		Files.deleteIfExists(folder);
	}

	private Object delete() throws Exception {
		Method method = CharacterWipeService.class.getDeclaredMethod("deleteCharacterFile", UUID.class, String.class);
		method.setAccessible(true);
		return method.invoke(null, owner, "test");
	}

	@Test
	void missingFileCountsAsAlreadyDeleted() throws Exception {
		assertEquals(Boolean.TRUE, delete());
	}

	@Test
	void existingFileReportsSuccessfulDeletion() throws Exception {
		Files.writeString(file, "{}");
		assertEquals(Boolean.TRUE, delete());
		assertFalse(Files.exists(file));
	}

	@Test
	void failedDeletionReportsFailureAndKeepsTheFile() throws Exception {
		Files.createDirectory(file);
		Files.writeString(file.resolve("blocking-child"), "preserve");
		assertEquals(Boolean.FALSE, delete());
		assertTrue(Files.exists(file.resolve("blocking-child")));
	}
}
