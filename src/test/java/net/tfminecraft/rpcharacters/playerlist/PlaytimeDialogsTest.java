package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.Entry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class PlaytimeDialogsTest {
	private static final UUID OWNER = UUID.randomUUID();

	private static Entry entry(String id, String name, int seconds, Status status) {
		return new Entry(OWNER, id, name, seconds, status, false);
	}

	@Test
	void tooltipShowsCharacterAndOwningAccountIncludingDeceasedCharacters() {
		Player viewer = mock(Player.class);
		OfflinePlayer owner = mock(OfflinePlayer.class);
		when(owner.getName()).thenReturn("PlayerOne");
		Entry entry = entry("dead", "Sir Rowan", 3661, Status.DEAD);
		try (var bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getOfflinePlayer(OWNER)).thenReturn(owner);
			assertEquals("Sir Rowan\nPlayer: PlayerOne\nLeaderboard: #2\nCharacter playtime: 0d 1h 1m 1s\nCharacter status: Deceased\nNot currently played",
					PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(viewer, entry, 2)));
		}
	}

	@Test
	void onlyCurrentlyPlayedVisibleCharacterGetsDotAndPing() {
		Player viewer = mock(Player.class);
		Player target = mock(Player.class);
		when(target.getName()).thenReturn("PlayerOne");
		when(target.getPing()).thenReturn(42);
		PlayerData data = mock(PlayerData.class);
		RPCharacter active = mock(RPCharacter.class);
		when(active.getId()).thenReturn("active");
		when(data.getActiveCharacter()).thenReturn(active);
		Entry entry = entry("active", "Lady Wren", 60, Status.ALIVE);
		try (var bukkit = mockStatic(Bukkit.class); var manager = mockStatic(PlayerManager.class)) {
			bukkit.when(() -> Bukkit.getOfflinePlayer(OWNER)).thenReturn(target);
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry));
			bukkit.when(() -> Bukkit.getPlayer(OWNER)).thenReturn(target);
			manager.when(() -> PlayerManager.get(target)).thenReturn(data);
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry));
			assertFalse(PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(viewer, entry, 1)).contains("Ping:"));
			when(viewer.canSee(target)).thenReturn(true);
			assertEquals("§a●", PlaytimeDialogs.onlineMarker(viewer, entry));
			assertEquals("Lady Wren\nPlayer: PlayerOne\nLeaderboard: #1\nCharacter playtime: 0d 0h 1m 0s\nCharacter status: Alive\n● Currently playing\nPing: 42ms",
					PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(viewer, entry, 1)));
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry("inactive", "Other", 90, Status.ALIVE)));
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry("dead", "Former", 90, Status.DEAD)));
			when(active.isHidden()).thenReturn(true);
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry));
		}
	}

	@Test
	void formatsCharacterSecondsRatherThanMinecraftTicks() {
		assertEquals("0d 0h 0m", PlaytimeDialogs.formatSeconds(0));
		assertEquals("0d 0h 0m", PlaytimeDialogs.formatSeconds(-1));
		assertEquals("0d 0h 0m", PlaytimeDialogs.formatSeconds(59));
		assertEquals("0d 0h 1m", PlaytimeDialogs.formatSeconds(60));
		assertEquals("2d 3h 4m", PlaytimeDialogs.formatSeconds(2 * 86400 + 3 * 3600 + 4 * 60));
		assertEquals("24855d 3h 14m", PlaytimeDialogs.formatSeconds(Integer.MAX_VALUE));
	}

	@Test
	void ranksMultipleCharactersFromSameOwnerIndependentlyIncludingDeceasedAndZeroTime() {
		Entry zoe = entry("zoe", "Zoe", 10000, Status.DEAD);
		Entry amy = entry("amy", "amy", 0, Status.ALIVE);
		Entry bob = entry("bob", "Bob", 60, Status.ALIVE);
		Entry aaron = entry("aaron", "aaron", 60, Status.MISSING);
		assertEquals(List.of(zoe, aaron, bob, amy), PlaytimeDialogs.sortedPlayers(List.of(amy, bob, zoe, aaron)));
	}

	@Test
	void paginationIncludesEveryCharacterOnceAndHandlesTheFinalPartialPage() {
		List<Entry> entries = IntStream.range(0, 25)
				.mapToObj(i -> entry("id" + i, "Character" + i, i * 60, Status.ALIVE)).toList();
		assertEquals(entries.subList(0, 12), PlaytimeDialogs.pagePlayers(entries, 0));
		assertEquals(entries.subList(12, 24), PlaytimeDialogs.pagePlayers(entries, 1));
		assertEquals(entries.subList(24, 25), PlaytimeDialogs.pagePlayers(entries, 2));
		assertTrue(PlaytimeDialogs.pagePlayers(entries, 3).isEmpty());
		assertTrue(PlaytimeDialogs.pagePlayers(List.of(), 0).isEmpty());
	}

	@Test
	void longCharacterNamesCannotPushPlaytimeOutOfItsColumn() {
		String name = "Sir Maximilian of the Very Long House Name";
		String label = PlaytimeDialogs.shortName(name);
		assertTrue(label.endsWith("..."));
		assertTrue(GuiText.width(label) <= 146);
		assertEquals("Lady Wren", PlaytimeDialogs.shortName("Lady Wren"));
	}
}
