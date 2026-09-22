package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import net.Indyuce.mmocore.api.event.PlayerDataLoadEvent;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Runs work after MMOCore has finished loading the player. Attribute maps are
 * still being filled during {@code PlayerJoinEvent}, which races
 * {@code HashMap.computeIfAbsent}.
 */
public final class MmoCorePlayerReady implements Listener {

	private static final int MAX_WAIT_TICKS = 100;
	private static final Map<UUID, List<Runnable>> PENDING = new ConcurrentHashMap<>();
	private static final java.util.Set<UUID> POLLING = ConcurrentHashMap.newKeySet();

	public static boolean isReady(Player player) {
		if (player == null) {
			return false;
		}
		try {
			if (!PlayerData.has(player)) {
				return false;
			}
			return PlayerData.get(player).isFullyLoaded();
		} catch (Throwable t) {
			return false;
		}
	}

	public static void runWhenLoaded(Player player, Runnable task) {
		if (player == null || task == null) {
			return;
		}
		if (isReady(player)) {
			task.run();
			return;
		}
		UUID id = player.getUniqueId();
		PENDING.compute(id, (ignored, list) -> {
			List<Runnable> next = list == null ? new ArrayList<>() : list;
			next.add(task);
			return next;
		});
		startPoll(id);
	}

	public static void cancel(UUID id) {
		if (id == null) {
			return;
		}
		PENDING.remove(id);
		POLLING.remove(id);
	}

	@EventHandler
	public void onMmoLoaded(PlayerDataLoadEvent event) {
		flush(event.getPlayer().getUniqueId());
	}

	private static void startPoll(UUID id) {
		if (!POLLING.add(id)) {
			return;
		}
		poll(id, 0);
	}

	private static void poll(UUID id, int tick) {
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (flush(id)) {
				POLLING.remove(id);
				return;
			}
			if (tick >= MAX_WAIT_TICKS) {
				POLLING.remove(id);
				PENDING.remove(id);
				RPCharacters.plugin.getLogger().warning(
						"MMOCore player data did not finish loading in time for " + id);
				return;
			}
			poll(id, tick + 1);
		}, 1L);
	}

	private static boolean flush(UUID id) {
		Player player = Bukkit.getPlayer(id);
		if (player == null || !player.isOnline()) {
			PENDING.remove(id);
			return true;
		}
		if (!isReady(player)) {
			return false;
		}
		List<Runnable> tasks = PENDING.remove(id);
		if (tasks == null) {
			return true;
		}
		for (Runnable task : tasks) {
			try {
				task.run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		return true;
	}
}
