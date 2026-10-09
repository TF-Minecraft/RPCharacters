package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.scheduler.BukkitTask;

/** Two players in one duel, from the accepted challenge until it ends. */
final class Duel {

	private final UUID first;
	private final UUID second;
	private final Map<UUID, DuelSnapshot> snapshots = new HashMap<>();
	private final Map<UUID, Double> damageTaken = new HashMap<>();
	private final List<BukkitTask> tasks = new ArrayList<>();
	private boolean fighting;
	private long fightStartedMs;

	Duel(UUID first, UUID second) {
		this.first = first;
		this.second = second;
	}

	UUID first() {
		return first;
	}

	UUID second() {
		return second;
	}

	boolean includes(UUID id) {
		return first.equals(id) || second.equals(id);
	}

	UUID opponentOf(UUID id) {
		return first.equals(id) ? second : first;
	}

	void snapshot(UUID id, DuelSnapshot snapshot) {
		snapshots.put(id, snapshot);
	}

	DuelSnapshot snapshotOf(UUID id) {
		return snapshots.get(id);
	}

	/** Health this duel has cost the player so far, given back when it ends. */
	double damageTaken(UUID id) {
		return damageTaken.getOrDefault(id, 0.0);
	}

	void addDamage(UUID id, double amount) {
		damageTaken.merge(id, amount, Double::sum);
	}

	boolean isFighting() {
		return fighting;
	}

	long fightStartedMs() {
		return fightStartedMs;
	}

	void startFight(long nowMs) {
		fighting = true;
		fightStartedMs = nowMs;
	}

	void addTask(BukkitTask task) {
		tasks.add(task);
	}

	void cancelTasks() {
		for (BukkitTask task : tasks) {
			task.cancel();
		}
		tasks.clear();
	}
}
