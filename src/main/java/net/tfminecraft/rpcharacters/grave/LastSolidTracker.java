package net.tfminecraft.rpcharacters.grave;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class LastSolidTracker {

	private static final LastSolidTracker INSTANCE = new LastSolidTracker();

	private final ConcurrentHashMap<UUID, Location> lastSolid = new ConcurrentHashMap<>();
	private BukkitTask task;

	private LastSolidTracker() {}

	public static LastSolidTracker get() {
		return INSTANCE;
	}

	public void start() {
		shutdown();
		task = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(RPCharacters.plugin, 0L, GraveLoader.getSnapshotIntervalTicks());
	}

	public void shutdown() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	public Location getLastSolid(Player player) {
		if (player == null) {
			return null;
		}
		return getLastSolid(player.getUniqueId());
	}

	public Location getLastSolid(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		Location stored = lastSolid.get(playerId);
		return stored != null ? stored.clone() : null;
	}

	private void tick() {
		if (!GraveLoader.isEnabled()) {
			return;
		}
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.getGameMode() == GameMode.SPECTATOR) {
				continue;
			}
			Block solid = findSolid(player);
			if (solid == null) {
				continue;
			}
			lastSolid.put(player.getUniqueId(), solid.getLocation());
		}
	}

	private static Block findSolid(Player player) {
		Location loc = player.getLocation();
		if (loc.getWorld() == null) {
			return null;
		}
		Block feet = loc.getBlock();
		if (feet.getType().isSolid()) {
			return feet;
		}
		Block below = feet.getRelative(BlockFace.DOWN);
		if (below.getType().isSolid()) {
			return below;
		}
		return null;
	}
}
