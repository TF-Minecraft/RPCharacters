package net.tfminecraft.rpcharacters.playtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class CharacterPlaytimeDirectoryTest {
	@TempDir Path root;
	private final UUID owner = UUID.randomUUID();

	@AfterEach
	void clearDirectory() {
		CharacterPlaytimeDirectory.loadFromDisk(root.resolve("absent"), ignored -> {});
	}

	private Path save(String file, String json) throws Exception {
		Path folder = Files.createDirectories(root.resolve(owner.toString()));
		return Files.writeString(folder.resolve(file + ".json"), json);
	}

	@Test
	void loadsAllSavedCharactersWithoutRewritingFilesOrUsingCharacterAgeAsPlaytime() throws Exception {
		Path alive = save("a", """
				{"id":"a","name":"Real name","alias":"Sir Rowan","status":"ALIVE","active":"false","online-playtime-seconds":7200}
				""");
		String before = Files.readString(alive);
		save("b", """
				{"id":"b","name":"Lady Wren","status":"DEAD","online-playtime-seconds":14400}
				""");
		save("old", """
				{"id":"old","name":"Old character","status":"MISSING","playtime-seconds":1700000000,"created-at":1700000000}
				""");
		save("hidden", """
				{"id":"hidden","name":"Secret","status":"ALIVE","hidden":"true","online-playtime-seconds":50000}
				""");
		List<String> warnings = new ArrayList<>();
		CharacterPlaytimeDirectory.loadFromDisk(root, warnings::add);
		try (var manager = mockStatic(PlayerManager.class)) {
			var entries = CharacterPlaytimeDirectory.getAll();
			assertEquals(3, entries.size());
			assertTrue(entries.stream().allMatch(e -> e.ownerId().equals(owner)));
			assertTrue(entries.stream().anyMatch(e -> e.name().equals("Sir Rowan") && e.seconds() == 7200));
			assertTrue(entries.stream().anyMatch(e -> e.status() == Status.DEAD && e.seconds() == 14400));
			assertTrue(entries.stream().anyMatch(e -> e.characterId().equals("old") && e.seconds() == 0));
		}
		assertTrue(warnings.isEmpty());
		assertEquals(before, Files.readString(alive));
	}

	@Test
	void malformedRecordDoesNotDiscardOtherCharacters() throws Exception {
		save("valid", """
				{"id":"valid","name":"Valid","status":"ALIVE","online-playtime-seconds":60}
				""");
		save("broken", "{broken");
		List<String> warnings = new ArrayList<>();
		CharacterPlaytimeDirectory.loadFromDisk(root, warnings::add);
		try (var manager = mockStatic(PlayerManager.class)) {
			assertEquals(1, CharacterPlaytimeDirectory.getAll().size());
		}
		assertEquals(1, warnings.size());
	}

	@Test
	void liveTimeAndIdentityReplaceSavedValuesAndHiddenStateCannotLeak() throws Exception {
		save("a", """
				{"id":"a","name":"Old name","status":"ALIVE","online-playtime-seconds":60}
				""");
		CharacterPlaytimeDirectory.loadFromDisk(root, ignored -> {});
		RPCharacter live = mock(RPCharacter.class);
		when(live.getId()).thenReturn("a");
		when(live.getEffectiveDisplayPlain()).thenReturn("New alias");
		when(live.getOnlinePlaytimeSeconds()).thenReturn(120);
		when(live.getStatus()).thenReturn(Status.DEAD);
		PlayerData data = mock(PlayerData.class);
		when(data.getUniqueId()).thenReturn(owner);
		when(data.getCharacters()).thenReturn(List.of(live));
		try (var manager = mockStatic(PlayerManager.class)) {
			manager.when(PlayerManager::getOnlineData).thenReturn(List.of(data));
			var entries = CharacterPlaytimeDirectory.getAll();
			assertEquals(1, entries.size());
			assertEquals("New alias", entries.getFirst().name());
			assertEquals(120, entries.getFirst().seconds());
			assertEquals(Status.DEAD, entries.getFirst().status());
			when(live.isHidden()).thenReturn(true);
			assertTrue(CharacterPlaytimeDirectory.getAll().isEmpty());
			when(live.isHidden()).thenReturn(false);
			CharacterPlaytimeDirectory.upsert(owner, live);
			manager.when(PlayerManager::getOnlineData).thenReturn(List.of());
			assertEquals(120, CharacterPlaytimeDirectory.getAll().getFirst().seconds());
			CharacterPlaytimeDirectory.remove(owner, "a");
			assertTrue(CharacterPlaytimeDirectory.getAll().isEmpty());
		}
	}
}
