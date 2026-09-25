package net.tfminecraft.rpcharacters.database;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory;

class CharacterPlaytimeSaveTest {
	@Test
	void publishesSavedPlaytimeOnlyAfterSuccessfulSave() throws Exception {
		UUID owner = UUID.randomUUID();
		PlayerData data = new PlayerData(owner);
		RPCharacter character = new RPCharacter(null);
		character.setName("Test Character");
		Race race = mock(Race.class);
		when(race.getId()).thenReturn("test");
		character.setRace(race);
		Path folder = Path.of("plugins/RPCharacters/data/characterdata", owner.toString());
		Database db = spy(new Database());
		doReturn(false, true).when(db).save(any(File.class), any());
		try (var directory = mockStatic(CharacterPlaytimeDirectory.class);
				var mail = mockStatic(MailRecipientDirectory.class)) {
			db.saveCharacter(data, character);
			verify(db).save(any(File.class), any());
			directory.verifyNoInteractions();
			db.saveCharacter(data, character);
			verify(db, times(2)).save(any(File.class), any());
			directory.verify(() -> CharacterPlaytimeDirectory.upsert(owner, character));
		} finally {
			Files.deleteIfExists(folder.resolve(character.getId() + ".json"));
			Files.deleteIfExists(folder);
		}
	}
}
