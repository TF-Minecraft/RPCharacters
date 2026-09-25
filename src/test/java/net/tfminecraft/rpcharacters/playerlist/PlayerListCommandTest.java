package net.tfminecraft.rpcharacters.playerlist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;

class PlayerListCommandTest {

	@Test
	void quickActionRoutesPlaytimeOnTheServerThread() {
		Player player = mock(Player.class);
		when(player.isOnline()).thenReturn(true);
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		doAnswer(call -> {
			call.getArgument(1, Runnable.class).run();
			return null;
		}).when(scheduler).runTask(nullable(Plugin.class), any(Runnable.class));
		try (var bukkit = mockStatic(Bukkit.class); var dialogs = mockStatic(PlaytimeDialogs.class)) {
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
			new PlayerListCommand().onCustomClick(event(player, PlaytimeDialogs.OPEN_ACTION));
			dialogs.verify(() -> PlaytimeDialogs.open(player));
		}
	}

	@Test
	void ignoresUnrelatedActionsAndPlayersWhoHaveDisconnected() {
		Player player = mock(Player.class);
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		doAnswer(call -> {
			call.getArgument(1, Runnable.class).run();
			return null;
		}).when(scheduler).runTask(nullable(Plugin.class), any(Runnable.class));
		try (var bukkit = mockStatic(Bukkit.class); var dialogs = mockStatic(PlaytimeDialogs.class)) {
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
			new PlayerListCommand().onCustomClick(event(player, Key.key("other", "action")));
			bukkit.verifyNoInteractions();
			new PlayerListCommand().onCustomClick(event(player, PlaytimeDialogs.OPEN_ACTION));
			dialogs.verifyNoInteractions();
		}
	}

	private static PlayerCustomClickEvent event(Player player, Key key) {
		PlayerGameConnection connection = mock(PlayerGameConnection.class);
		when(connection.getPlayer()).thenReturn(player);
		PlayerCustomClickEvent event = mock(PlayerCustomClickEvent.class);
		when(event.getIdentifier()).thenReturn(key);
		when(event.getCommonConnection()).thenReturn(connection);
		return event;
	}
}
