package net.tfminecraft.rpcharacters.grave;

import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class GraveExpiryService {

	private static final GraveExpiryService INSTANCE = new GraveExpiryService();

	private BukkitTask task;

	private GraveExpiryService() {
	}

	public static GraveExpiryService get() {
		return INSTANCE;
	}

	public void start() {
		shutdown();
		if (!GraveLoader.isEnabled() || GraveLoader.getExpireSeconds() <= 0) {
			return;
		}
		GraveManager.get().expireOverdue();
		task = new BukkitRunnable() {
			@Override
			public void run() {
				GraveManager.get().tickExpiry();
			}
		}.runTaskTimer(RPCharacters.plugin, GraveLoader.getSnapshotIntervalTicks(),
				GraveLoader.getSnapshotIntervalTicks());
	}

	public void shutdown() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}
}
