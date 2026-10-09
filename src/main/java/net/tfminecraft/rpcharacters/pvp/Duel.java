package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** Two players in one duel, from the accepted challenge until it ends. */
final class Duel {

	private final UUID first;
	private final UUID second;
	private final Map<UUID, DuelSnapshot> snapshots = new HashMap<>();
	private final Map<UUID, Double> damageTaken = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, Map<Touch, Integer>> opponentTouchTicks = new HashMap<>();
	private final Set<UUID> burningFromOpponent = new HashSet<>();
	private final Map<UUID, Set<PotionEffectType>> effectsFromOpponent = new HashMap<>();
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

	void name(UUID id, String name) {
		names.put(id, name);
	}

	String nameOf(UUID id) {
		return names.getOrDefault(id, "");
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

	/** How the opponent last reached a player, to match against how an effect was applied. */
	enum Touch {
		HIT, PROJECTILE, POTION
	}

	/** The opponent's hit, projectile or thrown potion reached this player on this server tick. */
	void touchedByOpponent(UUID id, Touch touch, int tick) {
		opponentTouchTicks.computeIfAbsent(id, key -> new EnumMap<>(Touch.class)).put(touch, tick);
	}

	boolean touchedByOpponentAt(UUID id, Set<Touch> touches, int tick) {
		Map<Touch, Integer> ticks = opponentTouchTicks.getOrDefault(id, Map.of());
		for (Touch touch : touches) {
			Integer at = ticks.get(touch);
			if (at != null && at == tick) {
				return true;
			}
		}
		return false;
	}

	void setBurningFromOpponent(UUID id, boolean fromOpponent) {
		if (fromOpponent) {
			burningFromOpponent.add(id);
		} else {
			burningFromOpponent.remove(id);
		}
	}

	boolean isBurningFromOpponent(UUID id) {
		return burningFromOpponent.contains(id);
	}

	void setEffectFromOpponent(UUID id, PotionEffectType type, boolean fromOpponent) {
		Set<PotionEffectType> types = effectsFromOpponent.computeIfAbsent(id, key -> new HashSet<>());
		if (fromOpponent) {
			types.add(type);
		} else {
			types.remove(type);
		}
	}

	boolean hasEffectFromOpponent(UUID id, PotionEffectType type) {
		return effectsFromOpponent.getOrDefault(id, Set.of()).contains(type);
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
