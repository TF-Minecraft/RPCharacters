package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.database.PlaytimeIndexDatabase.Entry;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class PlaytimeDialogsTest {

	@Test
	void tooltipIncludesExactPlaytimeAndOnlyVisibleOnlineDetails() {
		Player viewer = mock(Player.class);
		Player target = mock(Player.class);
		Entry entry = new Entry(UUID.randomUUID(), "PlayerOne", 3661);
		try (var bukkit = mockStatic(Bukkit.class); var identity = mockStatic(DisplayIdentityService.class)) {
			String offline = "PlayerOne\nLeaderboard: #2\nCurrent character playtime: 0d 1h 1m 1s\nOffline";
			assertEquals(offline, PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(viewer, entry, 2)));
			bukkit.when(() -> Bukkit.getPlayer(entry.getUuid())).thenReturn(target);
			assertEquals(offline, PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(viewer, entry, 2)));
			identity.verifyNoInteractions();
			when(viewer.canSee(target)).thenReturn(true);
			when(target.getPing()).thenReturn(42);
			identity.when(() -> DisplayIdentityService.resolveDisplaySafe(target)).thenReturn("§aSafe Alias");
			assertEquals("PlayerOne\nLeaderboard: #2\nCurrent character playtime: 0d 1h 1m 1s\n● Online\nCharacter: Safe Alias\nPing: 42ms",
					PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(viewer, entry, 2)));
		}
	}

	@Test
	void greenDotShowsVisibleOnlinePlayersButDoesNotRevealHiddenPlayers() {
		Player viewer = mock(Player.class);
		Player target = mock(Player.class);
		UUID subject = UUID.randomUUID();
		try (var bukkit = mockStatic(Bukkit.class)) {
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, subject));
			bukkit.when(() -> Bukkit.getPlayer(subject)).thenReturn(target);
			assertEquals("", PlaytimeDialogs.onlineMarker(viewer, subject));
			when(viewer.canSee(target)).thenReturn(true);
			assertEquals("§a●", PlaytimeDialogs.onlineMarker(viewer, subject));
			bukkit.when(() -> Bukkit.getPlayer(subject)).thenReturn(viewer);
			assertEquals("§a●", PlaytimeDialogs.onlineMarker(viewer, subject));
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
	void ranksEveryIndexedPlayerByPlaytimeWithNamesBreakingTies() {
		Entry zoe = new Entry(UUID.randomUUID(), "Zoe", 10000);
		Entry amy = new Entry(UUID.randomUUID(), "amy", 0);
		Entry bob = new Entry(UUID.randomUUID(), "Bob", 60);
		Entry aaron = new Entry(UUID.randomUUID(), "aaron", 60);
		assertEquals(List.of(zoe, aaron, bob, amy),
				PlaytimeDialogs.sortedPlayers(List.of(amy, bob, zoe, aaron)));
	}

	@Test
	void paginationIncludesEveryPlayerOnceAndHandlesTheFinalPartialPage() {
		List<Entry> entries = IntStream.range(0, 25)
				.mapToObj(i -> new Entry(new UUID(0, i), "Player" + i, i * 60)).toList();
		assertEquals(entries.subList(0, 12), PlaytimeDialogs.pagePlayers(entries, 0));
		assertEquals(entries.subList(12, 24), PlaytimeDialogs.pagePlayers(entries, 1));
		assertEquals(entries.subList(24, 25), PlaytimeDialogs.pagePlayers(entries, 2));
		assertTrue(PlaytimeDialogs.pagePlayers(entries, 3).isEmpty());
		assertTrue(PlaytimeDialogs.pagePlayers(List.of(), 0).isEmpty());
	}
}
