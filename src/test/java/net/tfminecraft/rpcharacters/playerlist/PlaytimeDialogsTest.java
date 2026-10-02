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
		OfflinePlayer owner = mock(OfflinePlayer.class);
		when(owner.getName()).thenReturn("PlayerOne");
		Entry entry = entry("dead", "Sir Rowan", 3661, Status.DEAD);
		try (var bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getOfflinePlayer(OWNER)).thenReturn(owner);
			assertEquals("Sir Rowan\nPlayer: PlayerOne\nCharacter playtime: 0d 1h 1m 1s\nCharacter status: Deceased",
					PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(entry)));
		}
	}

	@Test
	void onlyCurrentlyPlayedVisibleCharacterGetsDot() {
		Player viewer = mock(Player.class);
		Player target = mock(Player.class);
		when(target.getName()).thenReturn("PlayerOne");
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
			assertFalse(PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(entry)).contains("Ping:"));
			when(viewer.canSee(target)).thenReturn(true);
			assertEquals("§a●", PlaytimeDialogs.onlineMarker(viewer, entry));
			assertEquals("Lady Wren\nPlayer: PlayerOne\nCharacter playtime: 0d 0h 1m 0s\nCharacter status: Alive",
					PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(entry)));
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

	@org.junit.jupiter.api.Nested
	class RuntimeCoverage {
		net.tfminecraft.rpcharacters.RuntimeTestState state;
		@org.junit.jupiter.api.BeforeEach void setup() { org.mockbukkit.mockbukkit.MockBukkit.mock(); state = new net.tfminecraft.rpcharacters.RuntimeTestState(); net.tfminecraft.rpcharacters.Cache.playerList = PlayerListSettings.defaults(); }
		@org.junit.jupiter.api.AfterEach void teardown() { state.close(); org.mockbukkit.mockbukkit.MockBukkit.unmock(); }
		Player viewer() { Player p = mock(Player.class); when(p.hasPermission(net.tfminecraft.rpcharacters.Cache.playerList.permission())).thenReturn(true); return p; }
		@Test void permissionDenialStopsDirectoryReadsAndDialogCreation() {
			Player viewer = mock(Player.class);
			try (var directory = mockStatic(net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.class); var ui = new PlayerListDialogsTest.DialogCapture()) {
				PlaytimeDialogs.open(viewer); directory.verifyNoInteractions(); assertTrue(ui.opened.isEmpty()); verify(viewer).sendMessage(contains("do not have permission"));
			}
		}
		@Test void emptyLeaderboardShowsHelpfulMessageAndOnlineButtonRechecksPermission() {
			Player viewer = viewer();
			try (var directory = mockStatic(net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.class); var ui = new PlayerListDialogsTest.DialogCapture(); var lists = mockStatic(PlayerListDialogs.class)) {
				directory.when(net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory::getAll).thenReturn(List.of()); PlaytimeDialogs.open(viewer);
				assertEquals("Playtime leaderboard", PlainTextComponentSerializer.plainText().serialize(ui.last().base().title())); assertTrue(ui.last().bodyText().contains("0 characters • Page 1/1")); assertTrue(ui.last().bodyText().contains("No characters recorded yet.")); assertEquals(1, ui.last().buttons().size()); verify(viewer).showDialog(ui.last().dialog());
				var button = ui.last().buttons().getFirst(); ui.click(button, net.kyori.adventure.audience.Audience.empty()); lists.verifyNoInteractions();
				when(viewer.hasPermission(net.tfminecraft.rpcharacters.Cache.playerList.permission())).thenReturn(false); ui.click(button, viewer); lists.verifyNoInteractions();
				when(viewer.hasPermission(net.tfminecraft.rpcharacters.Cache.playerList.permission())).thenReturn(true); ui.click(button, viewer); lists.verify(() -> PlayerListDialogs.openList(viewer));
			}
		}
		@Test void rankedRowsNavigateEveryPageWithTooltipsMedalsAndPermissionChecks() {
			Player viewer = viewer(); OfflinePlayer owner = mock(OfflinePlayer.class); when(owner.getName()).thenReturn("Account");
			List<Entry> entries = IntStream.range(0, 25).mapToObj(i -> entry("id" + i, "Character" + i, (25 - i) * 60, i == 1 ? Status.DEAD : i == 2 ? Status.MISSING : Status.ALIVE)).toList();
			try (var directory = mockStatic(net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.class); var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); var ui = new PlayerListDialogsTest.DialogCapture()) {
				bukkit.when(() -> Bukkit.getOfflinePlayer(OWNER)).thenReturn(owner); bukkit.when(() -> Bukkit.getPlayer(OWNER)).thenReturn(null); directory.when(net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory::getAll).thenReturn(entries);
				PlaytimeDialogs.open(viewer); assertTrue(ui.last().bodyText().contains("25 characters • Page 1/3")); assertTrue(ui.last().bodyText().contains("Character11")); assertFalse(ui.last().bodyText().contains("Character12"));
				var rows = ((io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody) ui.last().base().body().get(1)).contents();
				List<net.kyori.adventure.text.Component> hoverRows = new java.util.ArrayList<>(); collectHoverRows(rows, hoverRows); assertEquals(12, hoverRows.size());
				assertEquals(net.kyori.adventure.text.format.NamedTextColor.GOLD, hoverRows.get(0).children().get(0).color()); assertEquals(0xC0C0C0, hoverRows.get(1).children().get(0).color().value()); assertEquals(0xCD7F32, hoverRows.get(2).children().get(0).color().value()); assertEquals(net.kyori.adventure.text.format.NamedTextColor.GRAY, hoverRows.get(3).children().get(0).color());
				assertTrue(hoverRows.get(1).hoverEvent().value().toString().contains("Deceased"));
				var next = ui.last().buttons().getFirst(); ui.click(next, net.kyori.adventure.audience.Audience.empty()); assertEquals(1, ui.opened.size()); ui.click(next, viewer); assertTrue(ui.last().bodyText().contains("Page 2/3")); assertTrue(ui.last().bodyText().contains("Character12")); assertTrue(ui.last().bodyText().contains("Character23"));
				var previous = ui.last().buttons().getFirst(); next = ui.last().buttons().get(1); ui.click(next, viewer); assertTrue(ui.last().bodyText().contains("Page 3/3")); assertTrue(ui.last().bodyText().contains("Character24")); assertEquals(2, ui.last().buttons().size()); ui.click(previous, viewer); assertTrue(ui.last().bodyText().contains("Page 1/3"));
				int count = ui.opened.size(); when(viewer.hasPermission(net.tfminecraft.rpcharacters.Cache.playerList.permission())).thenReturn(false); ui.click(ui.last().buttons().getFirst(), viewer); assertEquals(count, ui.opened.size()); verify(viewer).sendMessage(contains("do not have permission"));
			}
		}
		@Test void unknownAccountUsesUuidAndMissingStatusAndHiddenRowsNeverGetOnlineDot() {
			Player viewer = viewer(); OfflinePlayer owner = mock(OfflinePlayer.class); Entry missing = entry("missing", "Missing Hero", 90, Status.MISSING);
			try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); var managers = mockStatic(PlayerManager.class)) {
				bukkit.when(() -> Bukkit.getOfflinePlayer(OWNER)).thenReturn(owner); assertTrue(PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(missing)).contains("Player: " + OWNER)); assertTrue(PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(missing)).endsWith("Missing")); when(owner.getName()).thenReturn("  "); assertTrue(PlainTextComponentSerializer.plainText().serialize(PlaytimeDialogs.tooltip(missing)).contains(OWNER.toString()));
				assertEquals("", PlaytimeDialogs.onlineMarker(viewer, new Entry(OWNER, "id", "Hidden", 0, Status.ALIVE, true))); bukkit.when(() -> Bukkit.getPlayer(OWNER)).thenReturn(viewer); assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry("id", "Name", 1, Status.ALIVE)));
				var data = new PlayerData(OWNER); managers.when(() -> PlayerManager.get(viewer)).thenReturn(data); assertEquals("", PlaytimeDialogs.onlineMarker(viewer, entry("id", "Name", 1, Status.ALIVE)));
			}
		}
		@Test void formattingAndPaddingHonorWidthsColoursUnicodeAndNullInputs() {
			assertEquals(List.of(""), GuiText.wrap(null, 100)); assertEquals(List.of(""), GuiText.wrap("", 100)); assertEquals(0, GuiText.width(null)); assertEquals("", GuiText.plain(null)); assertEquals("a§", GuiText.plain("§aa§")); assertEquals("", PlainTextComponentSerializer.plainText().serialize(GuiText.component(null)));
			assertEquals(2 + 3 + 4 + 5 + 7 + 6, GuiText.width("iltf@A")); assertEquals(14, GuiText.width("§lAA")); assertEquals(13, GuiText.width("§lA§rA")); assertEquals(13, GuiText.width("§lA§aA")); assertEquals(13, GuiText.width("§lA§x§1§2§3§4§5§6A"));
			assertEquals(14, GuiText.paddedWidth("A", 14)); assertEquals(15, GuiText.paddedWidth("A", 15)); assertEquals(6, GuiText.paddedWidth("A", 1)); assertEquals("A  ", PlainTextComponentSerializer.plainText().serialize(GuiText.padded("A", 15)));
			String longName = "😀".repeat(40); String shortened = PlaytimeDialogs.shortName(longName); assertTrue(shortened.endsWith("...")); assertFalse(Character.isHighSurrogate(shortened.charAt(shortened.length() - 4))); assertTrue(GuiText.width(shortened) <= 146);
		}
		private void collectHoverRows(net.kyori.adventure.text.Component component, List<net.kyori.adventure.text.Component> result) { if (component.hoverEvent() != null) result.add(component); for (var child : component.children()) collectHoverRows(child, result); }
	}

}
