package net.tfminecraft.rpcharacters.mmocore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.lumine.mythic.lib.api.event.SynchronizedDataLoadEvent;
import io.lumine.mythic.lib.data.SynchronizedDataHolder;
import io.lumine.mythic.lib.gui.PluginInventory;
import net.Indyuce.mmocore.api.event.PlayerChangeClassEvent;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.tfminecraft.rpcharacters.RPCharacters;

class SlotBuffScopeLifecycleTest {

	private final UUID id = UUID.randomUUID();
	private final Player player = mock(Player.class);
	private final PlayerData data = mock(PlayerData.class);
	private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
	private final BukkitTask task = mock(BukkitTask.class);
	private final Logger logger = mock(Logger.class);
	private final ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
	private final ArgumentCaptor<Runnable> nextTick = ArgumentCaptor.forClass(Runnable.class);
	private RPCharacters oldPlugin;
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<PlayerData> players;

	@BeforeEach
	void setUp() {
		oldPlugin = RPCharacters.plugin;
		RPCharacters.plugin = mock(RPCharacters.class);
		when(RPCharacters.plugin.getLogger()).thenReturn(logger);
		when(player.getUniqueId()).thenReturn(id);
		when(data.getUniqueId()).thenReturn(id);
		when(data.getBoundSkills()).thenReturn(Map.of());
		when(scheduler.runTaskTimer(any(Plugin.class), timer.capture(), anyLong(), anyLong())).thenReturn(task);
		when(scheduler.runTask(any(Plugin.class), nextTick.capture())).thenReturn(task);
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
		bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.of(player));
		players = mockStatic(PlayerData.class);
		players.when(() -> PlayerData.has(player)).thenReturn(true);
		players.when(() -> PlayerData.get(player)).thenReturn(data);
	}

	@AfterEach
	void tearDown() {
		SlotBuffScope.shutdown();
		players.close();
		bukkit.close();
		RPCharacters.plugin = oldPlugin;
	}

	@Test
	void doesNothingUntilStarted() {
		SlotBuffScope listener = new SlotBuffScope();
		SlotBuffScope.scopeNow(data);
		listener.onInventoryClick(click(mock(PluginInventory.class)));
		listener.onDataLoad(load(data));

		verify(data, never()).getBoundSkills();
		verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
	}

	@Test
	void timerScopesEveryOnlinePlayerWithMmoData() {
		Player noData = mock(Player.class);
		bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.of(player, noData));
		SlotBuffScope.start();
		verify(scheduler).runTaskTimer(eq(RPCharacters.plugin), any(Runnable.class), eq(20L), eq(20L));

		timer.getValue().run();

		verify(data).getBoundSkills();
		players.verify(() -> PlayerData.get(noData), never());
		SlotBuffScope.scopeNow((PlayerData) null);
	}

	@Test
	void skillMenuClicksDataLoadsAndClassChangesScopeOnTheNextTickOnce() {
		SlotBuffScope.start();
		SlotBuffScope listener = new SlotBuffScope();

		listener.onInventoryClick(click(mock(InventoryHolder.class)));
		verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));

		listener.onInventoryClick(click(mock(PluginInventory.class)));
		listener.onDataLoad(load(data));
		PlayerChangeClassEvent change = mock(PlayerChangeClassEvent.class);
		when(change.getData()).thenReturn(data);
		listener.onClassChange(change);
		verify(scheduler, times(1)).runTask(any(Plugin.class), any(Runnable.class));

		nextTick.getValue().run();
		verify(data).getBoundSkills();

		listener.onDataLoad(load(mock(SynchronizedDataHolder.class)));
		verify(scheduler, times(1)).runTask(any(Plugin.class), any(Runnable.class));

		bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
		listener.onClassChange(change);
		nextTick.getValue().run();
		verify(data, times(1)).getBoundSkills();
	}

	@Test
	void runtimeFailuresWarnAtMostOncePerMinute() {
		when(data.getBoundSkills()).thenThrow(new IllegalStateException("broken"));
		SlotBuffScope.start();

		SlotBuffScope.scopeNow(data);
		SlotBuffScope.scopeNow(data);

		verify(logger, times(1)).log(eq(Level.WARNING), anyString(), any(IllegalStateException.class));
	}

	@Test
	void apiMismatchStopsScopingWithOneSevereLine() {
		when(data.getBoundSkills()).thenThrow(new NoSuchMethodError("getBuffs"));
		Player second = mock(Player.class);
		bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.of(player, second));
		SlotBuffScope.start();

		timer.getValue().run();

		verify(task).cancel();
		verify(logger, times(1)).log(eq(Level.SEVERE), anyString(), any(NoSuchMethodError.class));
		players.verify(() -> PlayerData.has(second), never());
	}

	@Test
	void apiMismatchInTheListenersStopsScoping() {
		SlotBuffScope listener = new SlotBuffScope();
		InventoryClickEvent click = mock(InventoryClickEvent.class);
		when(click.getView()).thenThrow(new NoClassDefFoundError("PluginInventory"));
		SlotBuffScope.start();
		listener.onInventoryClick(click);
		verify(task, times(1)).cancel();

		SynchronizedDataLoadEvent load = mock(SynchronizedDataLoadEvent.class);
		when(load.getHolder()).thenThrow(new NoSuchMethodError("getHolder"));
		SlotBuffScope.start();
		listener.onDataLoad(load);
		verify(task, times(2)).cancel();

		players.when(() -> PlayerData.has(player)).thenThrow(new NoClassDefFoundError("PlayerData"));
		SlotBuffScope.start();
		timer.getValue().run();
		verify(task, times(3)).cancel();
	}

	private InventoryClickEvent click(InventoryHolder holder) {
		Inventory top = mock(Inventory.class);
		when(top.getHolder(false)).thenReturn(holder);
		InventoryView view = mock(InventoryView.class);
		when(view.getTopInventory()).thenReturn(top);
		InventoryClickEvent event = mock(InventoryClickEvent.class);
		when(event.getView()).thenReturn(view);
		when(event.getWhoClicked()).thenReturn(player);
		return event;
	}

	private SynchronizedDataLoadEvent load(SynchronizedDataHolder holder) {
		SynchronizedDataLoadEvent event = mock(SynchronizedDataLoadEvent.class);
		when(event.getHolder()).thenReturn(holder);
		return event;
	}
}
