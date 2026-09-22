package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import io.lumine.mythic.lib.api.event.SynchronizedDataLoadEvent;
import io.lumine.mythic.lib.data.SynchronizedDataHolder;
import net.Indyuce.mmocore.api.player.PlayerData;

class MmoCorePlayerReadyTest {

	@Test
	void onlyReadyMmoCoreLoadFlushesPendingCallbackOnce() {
		UUID id = UUID.randomUUID();
		Player player = mock(Player.class);
		PlayerData data = mock(PlayerData.class);
		SynchronizedDataHolder otherData = mock(SynchronizedDataHolder.class);
		SynchronizedDataLoadEvent event = mock(SynchronizedDataLoadEvent.class);
		AtomicInteger calls = new AtomicInteger();
		when(player.getUniqueId()).thenReturn(id);
		when(player.isOnline()).thenReturn(true);
		when(data.getUniqueId()).thenReturn(id);
		when(otherData.getUniqueId()).thenReturn(id);
		when(event.getHolder()).thenReturn(data);

		try (var bukkit = mockStatic(Bukkit.class); var players = mockStatic(PlayerData.class)) {
			bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
			bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
			players.when(() -> PlayerData.has(player)).thenReturn(true);
			players.when(() -> PlayerData.get(player)).thenReturn(data);

			MmoCorePlayerReady.runWhenLoaded(player, calls::incrementAndGet);
			MmoCorePlayerReady listener = new MmoCorePlayerReady();
			listener.onMmoLoaded(event);
			assertEquals(0, calls.get(), "Unsynchronized MMOCore data must remain pending");

			when(data.isSynchronized()).thenReturn(true);
			when(event.getHolder()).thenReturn(otherData);
			listener.onMmoLoaded(event);
			assertEquals(0, calls.get(), "Another plugin's load event must not flush MMOCore work");

			when(event.getHolder()).thenReturn(data);
			listener.onMmoLoaded(event);
			listener.onMmoLoaded(event);
			assertEquals(1, calls.get());
		} finally {
			MmoCorePlayerReady.cancel(id);
		}
	}
}
