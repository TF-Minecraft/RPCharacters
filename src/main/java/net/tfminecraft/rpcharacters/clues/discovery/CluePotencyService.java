package net.tfminecraft.rpcharacters.clues.discovery;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;

public final class CluePotencyService {

	private static final long HOUR_MS = 60L * 60L * 1000L;

	private CluePotencyService() {}

	public static void tickAgeDecay(Collection<SpawnedClue> clues) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (settings.getPotencyDecayPerHour() <= 0) return;

		long now = System.currentTimeMillis();
		for (SpawnedClue clue : clues) {
			if (clue.shouldRemove()) continue;
			long ageMs = now - clue.getSpawnedAtMs();
			double hours = ageMs / (double) HOUR_MS;
			double decayed = settings.getPotencyInitial() - (settings.getPotencyDecayPerHour() * hours);
			if (decayed < clue.getPotency()) {
				clue.setPotency(decayed);
			}
			if (settings.isPotencyExpireWhenZero() && clue.getPotency() <= 0) {
				SpawnedClueManager.get().removeIfGone(clue);
			}
		}
	}

	public static void applyTargetInteractDisturbance(SpawnedClue clue) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (!settings.isTargetInteractEnabled() || clue == null) return;
		if (ThreadLocalRandom.current().nextDouble() < settings.getTargetInteractZeroLossChance()) {
			return;
		}
		applyRandomLoss(clue, settings.getTargetInteractLossMin(), settings.getTargetInteractLossMax());
	}

	public static void tickFootTraffic(Collection<SpawnedClue> clues, Collection<? extends Player> players) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (!settings.isFootTrafficEnabled()) return;

		long now = System.currentTimeMillis();
		double radiusSq = settings.getFootTrafficRadius() * settings.getFootTrafficRadius();

		for (SpawnedClue clue : clues) {
			if (clue.shouldRemove() || !clue.hasTargetBlock()) continue;
			if (clue.getPotency() <= settings.getPotencyMinForDiscovery()) continue;

			resetFootTrafficWindowIfNeeded(clue, now);

			if (clue.getFootTrafficEventsThisHour() >= settings.getFootTrafficMaxEventsPerHour()) {
				continue;
			}

			Location target = clue.getTargetCenter();
			if (target == null || target.getWorld() == null) continue;

			for (Player player : players) {
				if (player == null || !player.isOnline()) continue;
				if (!player.getWorld().equals(target.getWorld())) continue;

				PlayerData pd = PlayerManager.get(player);
				if (pd == null || !pd.hasActiveCharacter()) continue;
				RPCharacter character = pd.getActiveCharacter();
				if (character == null) continue;

				if (settings.isFootTrafficOnlyUndiscovered()) {
					try {
						if (clue.isDiscoveredBy(java.util.UUID.fromString(character.getId()))) {
							continue;
						}
					} catch (IllegalArgumentException ignored) {
					}
				}

				double dx = player.getLocation().getX() - target.getX();
				double dy = player.getLocation().getY() - target.getY();
				double dz = player.getLocation().getZ() - target.getZ();
				if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSq) continue;

				if (ThreadLocalRandom.current().nextDouble() >= settings.getFootTrafficChancePerCheck()) {
					continue;
				}

				applyRandomLoss(clue, settings.getFootTrafficLossMin(), settings.getFootTrafficLossMax());
				clue.setFootTrafficEventsThisHour(clue.getFootTrafficEventsThisHour() + 1);
				SpawnedClueManager.get().markDirty();
				break;
			}
		}
	}

	private static void resetFootTrafficWindowIfNeeded(SpawnedClue clue, long now) {
		if (clue.getFootTrafficWindowStartMs() <= 0 || now - clue.getFootTrafficWindowStartMs() >= HOUR_MS) {
			clue.setFootTrafficWindowStartMs(now);
			clue.setFootTrafficEventsThisHour(0);
		}
	}

	private static void applyRandomLoss(SpawnedClue clue, double min, double max) {
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		double lossMin = Math.min(min, max);
		double lossMax = Math.max(min, max);
		double loss = lossMin;
		if (lossMax > lossMin) {
			loss = lossMin + (ThreadLocalRandom.current().nextDouble() * (lossMax - lossMin));
		}
		clue.setPotency(clue.getPotency() - loss);
		SpawnedClueManager.get().markDirty();
		if (settings.isPotencyExpireWhenZero() && clue.getPotency() <= 0) {
			SpawnedClueManager.get().removeIfGone(clue);
		}
	}
}
