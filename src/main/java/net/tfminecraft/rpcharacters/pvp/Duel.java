package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;
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
	private final Map<UUID, Map<Wear, Integer>> wearTicks = new HashMap<>();
	private final Map<UUID, Swing> swings = new HashMap<>();
	private final Set<UUID> burningFromOpponent = new HashSet<>();
	private final Map<UUID, Set<PotionEffectType>> effectsFromOpponent = new HashMap<>();
	private final List<BukkitTask> tasks = new ArrayList<>();
	private DuelBanner banner;
	private boolean fighting;
	private long fightStartedMs;

	Duel(UUID first, UUID second) {
		this.first = first;
		this.second = second;
	}

	UUID first() {
		return first;
	}

	void banner(DuelBanner banner) {
		this.banner = banner;
	}

	void removeBanner() {
		if (banner != null) {
			banner.remove();
			banner = null;
		}
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

	/** The gear a duel blow wears: the struck player's armour or raised shield, the shooter's bow. */
	enum Wear {
		ARMOUR, SHOT
	}

	private record Swing(ItemStack weapon, int tick) {
	}

	/** A duel blow on this server tick wears this kind of the player's gear. */
	void wears(UUID id, Wear wear, int tick) {
		wearTicks.computeIfAbsent(id, key -> new EnumMap<>(Wear.class)).put(wear, tick);
	}

	boolean wearsAt(UUID id, Wear wear, int tick) {
		Integer at = wearTicks.getOrDefault(id, Map.of()).get(wear);
		return at != null && at == tick;
	}

	/** The player struck their opponent with this held weapon on this server tick. */
	void swung(UUID id, ItemStack weapon, int tick) {
		swings.put(id, new Swing(weapon, tick));
	}

	/** True once, for the swung weapon's wear on the tick of the swing; that uses the swing up. */
	boolean takeSwing(UUID id, ItemStack item, int tick) {
		Swing swing = swings.get(id);
		if (swing == null || swing.tick() != tick || !item.isSimilar(swing.weapon())) {
			return false;
		}
		swings.remove(id);
		return true;
	}

	/** A swing whose wear never came (Unbreaking) must not cover the next thing the weapon does. */
	void forgetSwing(UUID id) {
		swings.remove(id);
	}

	void clearWear(UUID id, Wear wear) {
		Map<Wear, Integer> ticks = wearTicks.get(id);
		if (ticks != null) {
			ticks.remove(wear);
		}
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
