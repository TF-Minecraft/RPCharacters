package net.tfminecraft.rpcharacters.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.database.SpawnedClueDatabase;
import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.ClueHologram;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryService;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager;
import net.tfminecraft.rpcharacters.clues.discovery.CluePotencyService;
import net.tfminecraft.rpcharacters.clues.discovery.InvestigationPointService;

public class SpawnedClueManager implements Listener {

	private static SpawnedClueManager instance;

	private final Map<UUID, SpawnedClue> byId = new HashMap<>();
	private final Map<ChunkKey, List<UUID>> byChunk = new HashMap<>();
	private boolean dirty = false;

	public static SpawnedClueManager get() {
		if (instance == null) {
			instance = new SpawnedClueManager();
		}
		return instance;
	}

	public void loadAllFromDisk() {
		byId.clear();
		byChunk.clear();
		for (SpawnedClue clue : SpawnedClueDatabase.loadAll()) {
			registerInternal(clue);
		}
		for (SpawnedClue clue : new ArrayList<>(byId.values())) {
			removeIfGone(clue);
		}
		ClueDiscoveryVisualManager.get().cleanupLegacyOrphans(byId.values());
		startAutosave();
	}

	public void register(SpawnedClue clue) {
		registerInternal(clue);
		markDirty();
	}

	private void registerInternal(SpawnedClue clue) {
		byId.put(clue.getId(), clue);
		ChunkKey key = chunkKey(clue);
		byChunk.computeIfAbsent(key, k -> new ArrayList<>());
		List<UUID> ids = byChunk.get(key);
		if (!ids.contains(clue.getId())) {
			ids.add(clue.getId());
		}
	}

	public Collection<SpawnedClue> getAllClues() {
		return byId.values();
	}

	public SpawnedClue get(UUID id) {
		return byId.get(id);
	}

	public List<SpawnedClue> getCluesNear(Location center, double radius) {
		List<SpawnedClue> result = new ArrayList<>();
		if (center == null || center.getWorld() == null || radius <= 0) return result;

		double radiusSq = radius * radius;
		Set<UUID> seen = new HashSet<>();
		int centerChunkX = center.getBlockX() >> 4;
		int centerChunkZ = center.getBlockZ() >> 4;
		int chunkRadius = (int) Math.ceil(radius / 16.0) + 1;
		String worldName = center.getWorld().getName();

		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				ChunkKey key = new ChunkKey(worldName, centerChunkX + dx, centerChunkZ + dz);
				List<UUID> ids = byChunk.get(key);
				if (ids == null) continue;
				for (UUID id : ids) {
					if (!seen.add(id)) continue;
					SpawnedClue clue = byId.get(id);
					if (clue == null || clue.shouldRemove()) continue;
					if (clue.distanceSquaredTo(center) <= radiusSq) {
						result.add(clue);
					}
				}
			}
		}
		return result;
	}

	public List<SpawnedClue> getCluesLinkedToBlock(Location block) {
		List<SpawnedClue> result = new ArrayList<>();
		if (block == null || block.getWorld() == null) return result;

		String worldName = block.getWorld().getName();
		int blockX = block.getBlockX();
		int blockY = block.getBlockY();
		int blockZ = block.getBlockZ();

		for (SpawnedClue clue : byId.values()) {
			if (!clue.hasTargetBlock()) continue;
			if (!worldName.equals(clue.getWorldName())) continue;
			if (blockX == clue.getTargetBlockX()
					&& blockY == clue.getTargetBlockY()
					&& blockZ == clue.getTargetBlockZ()) {
				result.add(clue);
			}
		}
		return result;
	}

	public boolean hasClueAt(Location location) {
		if (location == null || location.getWorld() == null) return false;
		String worldName = location.getWorld().getName();
		int blockX = location.getBlockX();
		int blockY = location.getBlockY();
		int blockZ = location.getBlockZ();
		for (SpawnedClue clue : byId.values()) {
			if (clue.shouldRemove()) continue;
			if (!worldName.equals(clue.getWorldName())) continue;
			if (blockX == (int) Math.floor(clue.getX())
					&& blockY == (int) Math.floor(clue.getY())
					&& blockZ == (int) Math.floor(clue.getZ())) {
				return true;
			}
		}
		return false;
	}

	public void remove(SpawnedClue clue) {
		if (clue == null) return;
		removeVisuals(clue);
		ClueDiscoveryVisualManager.get().removeClue(clue.getId());
		byId.remove(clue.getId());
		ChunkKey key = chunkKey(clue);
		List<UUID> ids = byChunk.get(key);
		if (ids != null) {
			ids.remove(clue.getId());
			if (ids.isEmpty()) byChunk.remove(key);
		}
		markDirty();
	}

	/**
	 * Removes a clue only when its chunk is loaded and it should be gone
	 * (time expired or potency depleted). Defers cleanup until the chunk loads.
	 */
	public void removeIfGone(SpawnedClue clue) {
		if (clue == null || !clue.shouldRemove() || !clue.isChunkLoaded()) {
			return;
		}
		remove(clue);
	}

	public void processCluesInChunk(String worldName, int chunkX, int chunkZ) {
		ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
		List<UUID> ids = byChunk.get(key);
		if (ids == null) {
			return;
		}
		for (UUID id : new ArrayList<>(ids)) {
			SpawnedClue clue = byId.get(id);
			if (clue != null) {
				removeIfGone(clue);
			}
		}
	}

	public int clearInRadius(Location center, double radius) {
		if (center == null || center.getWorld() == null || radius <= 0) return 0;

		List<SpawnedClue> toRemove = new ArrayList<>(getCluesNear(center, radius));
		for (SpawnedClue clue : toRemove) {
			remove(clue);
		}
		return toRemove.size();
	}

	public int clearLinkedToBlock(Location block) {
		List<SpawnedClue> toRemove = new ArrayList<>(getCluesLinkedToBlock(block));
		for (SpawnedClue clue : toRemove) {
			remove(clue);
		}
		return toRemove.size();
	}

	public void spawnVisuals(SpawnedClue clue) {
		// Per-viewer fake displays are refreshed on the discovery tick.
	}

	public void removeVisuals(SpawnedClue clue) {
		if (clue == null) return;
		runSync(() -> {
			ClueHologram.remove(clue);
			ClueDiscoveryVisualManager.get().removeClue(clue.getId());
			markDirty();
		});
	}

	public void removeAllVisuals() {
		runSync(() -> {
			for (SpawnedClue clue : new ArrayList<>(byId.values())) {
				ClueHologram.remove(clue);
			}
			ClueDiscoveryVisualManager.get().shutdown();
		});
	}

	public void startTicks() {
		int intervalSeconds = Math.max(1, ClueDiscoveryLoader.getSettings().getPassiveIntervalSeconds());
		long intervalTicks = intervalSeconds * 20L;

		new BukkitRunnable() {
			@Override
			public void run() {
				tickDiscovery();
			}
		}.runTaskTimer(RPCharacters.plugin, intervalTicks, intervalTicks);

		int particleInterval = Math.max(1, Cache.spawnedClueParticleInterval);
		new BukkitRunnable() {
			@Override
			public void run() {
				tickParticles();
			}
		}.runTaskTimer(RPCharacters.plugin, 0L, particleInterval);

		new BukkitRunnable() {
			@Override
			public void run() {
				purgeExpired();
			}
		}.runTaskTimer(RPCharacters.plugin, 1200L, 1200L);
	}

	private void tickDiscovery() {
		Collection<SpawnedClue> clues = new ArrayList<>(byId.values());
		CluePotencyService.tickAgeDecay(clues);

		List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
		CluePotencyService.tickFootTraffic(clues, online);

		double passiveRadius = ClueDiscoveryLoader.getSettings().getPassiveRadius();
		for (Player player : online) {
			InvestigationPointService.regen(player);
			PlayerData pd = PlayerManager.get(player);
			if (pd == null || !pd.hasActiveCharacter()) continue;
			RPCharacter character = pd.getActiveCharacter();
			if (character == null) continue;

			List<SpawnedClue> nearby = getCluesNear(player.getLocation(), passiveRadius);
			for (SpawnedClue clue : nearby) {
				ClueDiscoveryService.tryPassiveDiscovery(player, character, clue);
			}

			ClueDiscoveryVisualManager.get().refreshViewer(player);
		}
	}

	private void tickParticles() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			ClueDiscoveryVisualManager.get().tickParticles(player);
		}
	}

	private void purgeExpired() {
		for (SpawnedClue clue : new ArrayList<>(byId.values())) {
			removeIfGone(clue);
		}
	}

	private void startAutosave() {
		new BukkitRunnable() {
			@Override
			public void run() {
				if (!dirty) return;
				dirty = false;
				saveAllNow();
			}
		}.runTaskTimer(RPCharacters.plugin, 200L, 200L);
	}

	public void saveAllNow() {
		SpawnedClueDatabase.saveAll(byId.values());
	}

	public void shutdown() {
		removeAllVisuals();
		for (SpawnedClue clue : byId.values()) {
			clue.clearDisplayEntityIds();
			clue.setVisualsSpawned(false);
		}
		saveAllNow();
	}

	public void markDisplayDirty() {
		markDirty();
	}

	public void markDirty() {
		dirty = true;
	}

	@EventHandler
	public void onChunkLoad(ChunkLoadEvent event) {
		Chunk chunk = event.getChunk();
		processCluesInChunk(event.getWorld().getName(), chunk.getX(), chunk.getZ());
		World world = event.getWorld();
		int cx = chunk.getX();
		int cz = chunk.getZ();
		for (Player player : world.getPlayers()) {
			Location loc = player.getLocation();
			if ((loc.getBlockX() >> 4) == cx && (loc.getBlockZ() >> 4) == cz) {
				ClueDiscoveryVisualManager.get().refreshViewer(player);
			}
		}
	}

	@EventHandler
	public void onChunkUnload(ChunkUnloadEvent event) {
		Chunk chunk = event.getChunk();
		ChunkKey key = new ChunkKey(event.getWorld().getName(), chunk.getX(), chunk.getZ());
		List<UUID> ids = byChunk.get(key);
		if (ids == null) return;
		for (UUID id : new ArrayList<>(ids)) {
			ClueDiscoveryVisualManager.get().removeClue(id);
		}
	}

	private ChunkKey chunkKey(SpawnedClue clue) {
		return new ChunkKey(clue.getWorldName(), clue.getChunkX(), clue.getChunkZ());
	}

	private void runSync(Runnable runnable) {
		if (Bukkit.isPrimaryThread()) {
			runnable.run();
		} else {
			Bukkit.getScheduler().runTask(RPCharacters.plugin, runnable);
		}
	}

	private static final class ChunkKey {
		private final String world;
		private final int chunkX;
		private final int chunkZ;

		private ChunkKey(String world, int chunkX, int chunkZ) {
			this.world = world;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof ChunkKey other)) return false;
			return chunkX == other.chunkX && chunkZ == other.chunkZ && Objects.equals(world, other.world);
		}

		@Override
		public int hashCode() {
			return Objects.hash(world, chunkX, chunkZ);
		}
	}
}
