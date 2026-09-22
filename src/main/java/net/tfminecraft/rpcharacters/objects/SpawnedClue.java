package net.tfminecraft.rpcharacters.objects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;

public class SpawnedClue {

	private final UUID id;
	private final String world;
	private final double x;
	private final double y;
	private final double z;
	private final String clueText;
	private final long expiresAtMs;
	private final UUID ownerUuid;
	private final Integer targetBlockX;
	private final Integer targetBlockY;
	private final Integer targetBlockZ;

	private final List<UUID> displayEntityIds = new ArrayList<>();
	private boolean visualsSpawned;

	private double potency;
	private long spawnedAtMs;
	private final Map<UUID, Long> discoveredByCharacter = new HashMap<>();
	private int footTrafficEventsThisHour;
	private long footTrafficWindowStartMs;

	public SpawnedClue(UUID id, String world, double x, double y, double z, String clueText,
			long expiresAtMs, UUID ownerUuid) {
		this(id, world, x, y, z, clueText, expiresAtMs, ownerUuid, null, null, null);
	}

	public SpawnedClue(UUID id, String world, double x, double y, double z, String clueText,
			long expiresAtMs, UUID ownerUuid, Integer targetBlockX, Integer targetBlockY, Integer targetBlockZ) {
		this(id, world, x, y, z, clueText, expiresAtMs, ownerUuid, targetBlockX, targetBlockY, targetBlockZ, null,
				1.0, System.currentTimeMillis(), null, 0, 0L);
	}

	public SpawnedClue(UUID id, String world, double x, double y, double z, String clueText,
			long expiresAtMs, UUID ownerUuid, Integer targetBlockX, Integer targetBlockY, Integer targetBlockZ,
			List<UUID> displayEntityIds) {
		this(id, world, x, y, z, clueText, expiresAtMs, ownerUuid, targetBlockX, targetBlockY, targetBlockZ,
				displayEntityIds, 1.0, System.currentTimeMillis(), null, 0, 0L);
	}

	public SpawnedClue(UUID id, String world, double x, double y, double z, String clueText,
			long expiresAtMs, UUID ownerUuid, Integer targetBlockX, Integer targetBlockY, Integer targetBlockZ,
			List<UUID> displayEntityIds, double potency, long spawnedAtMs, Map<UUID, Long> discoveredByCharacter,
			int footTrafficEventsThisHour, long footTrafficWindowStartMs) {
		this.id = id;
		this.world = world;
		this.x = x;
		this.y = y;
		this.z = z;
		this.clueText = clueText;
		this.expiresAtMs = expiresAtMs;
		this.ownerUuid = ownerUuid;
		this.targetBlockX = targetBlockX;
		this.targetBlockY = targetBlockY;
		this.targetBlockZ = targetBlockZ;
		if (displayEntityIds != null) {
			this.displayEntityIds.addAll(displayEntityIds);
		}
		this.potency = potency;
		this.spawnedAtMs = spawnedAtMs > 0 ? spawnedAtMs : System.currentTimeMillis();
		if (discoveredByCharacter != null) {
			this.discoveredByCharacter.putAll(discoveredByCharacter);
		}
		this.footTrafficEventsThisHour = footTrafficEventsThisHour;
		this.footTrafficWindowStartMs = footTrafficWindowStartMs;
	}

	public UUID getId() {
		return id;
	}

	public String getWorldName() {
		return world;
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}

	public double getZ() {
		return z;
	}

	public String getClueText() {
		return clueText;
	}

	public long getExpiresAtMs() {
		return expiresAtMs;
	}

	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public boolean hasTargetBlock() {
		return targetBlockX != null && targetBlockY != null && targetBlockZ != null;
	}

	public int getTargetBlockX() {
		return targetBlockX;
	}

	public int getTargetBlockY() {
		return targetBlockY;
	}

	public int getTargetBlockZ() {
		return targetBlockZ;
	}

	public Location getTargetCenter(World world) {
		if (!hasTargetBlock() || world == null) return null;
		return new Location(world, targetBlockX + 0.5, targetBlockY + 0.5, targetBlockZ + 0.5);
	}

	public Location getTargetCenter() {
		return getTargetCenter(resolveWorld());
	}

	public List<UUID> getDisplayEntityIds() {
		return displayEntityIds;
	}

	public boolean isVisualsSpawned() {
		return visualsSpawned;
	}

	public void setVisualsSpawned(boolean visualsSpawned) {
		this.visualsSpawned = visualsSpawned;
	}

	public void clearDisplayEntityIds() {
		displayEntityIds.clear();
	}

	public double getPotency() {
		return potency;
	}

	public void setPotency(double potency) {
		this.potency = Math.max(0, Math.min(1, potency));
	}

	public long getSpawnedAtMs() {
		return spawnedAtMs;
	}

	public void setSpawnedAtMs(long spawnedAtMs) {
		this.spawnedAtMs = spawnedAtMs;
	}

	public Map<UUID, Long> getDiscoveredByCharacter() {
		return discoveredByCharacter;
	}

	public boolean isDiscoveredBy(String characterId) {
		if (characterId == null) return false;
		try {
			return discoveredByCharacter.containsKey(UUID.fromString(characterId));
		} catch (IllegalArgumentException ex) {
			for (UUID key : discoveredByCharacter.keySet()) {
				if (key.toString().equalsIgnoreCase(characterId)) {
					return true;
				}
			}
			return false;
		}
	}

	public boolean isDiscoveredBy(UUID characterUuid) {
		return characterUuid != null && discoveredByCharacter.containsKey(characterUuid);
	}

	public void markDiscovered(UUID characterUuid) {
		if (characterUuid == null) return;
		discoveredByCharacter.putIfAbsent(characterUuid, System.currentTimeMillis());
	}

	public int getFootTrafficEventsThisHour() {
		return footTrafficEventsThisHour;
	}

	public void setFootTrafficEventsThisHour(int footTrafficEventsThisHour) {
		this.footTrafficEventsThisHour = Math.max(0, footTrafficEventsThisHour);
	}

	public long getFootTrafficWindowStartMs() {
		return footTrafficWindowStartMs;
	}

	public void setFootTrafficWindowStartMs(long footTrafficWindowStartMs) {
		this.footTrafficWindowStartMs = footTrafficWindowStartMs;
	}

	public boolean isExpired() {
		return System.currentTimeMillis() >= expiresAtMs;
	}

	/** Whether this clue should be removed (time expiry or potency depleted). */
	public boolean shouldRemove() {
		if (isExpired()) {
			return true;
		}
		return ClueDiscoveryLoader.getSettings().isPotencyExpireWhenZero() && potency <= 0;
	}

	public int getChunkX() {
		return ((int) Math.floor(x)) >> 4;
	}

	public int getChunkZ() {
		return ((int) Math.floor(z)) >> 4;
	}

	public World resolveWorld() {
		return Bukkit.getWorld(world);
	}

	public Location getAnchor(World world) {
		if (world == null) return null;
		return new Location(world, x, y, z);
	}

	public Location getAnchor() {
		return getAnchor(resolveWorld());
	}

	public Location getVisualBase(World world) {
		Location anchor = getAnchor(world);
		if (anchor == null) return null;
		return anchor.clone().add(0, Cache.spawnedClueVisualYOffset, 0);
	}

	public Location getVisualBase() {
		return getVisualBase(resolveWorld());
	}

	public boolean isChunkLoaded() {
		World w = resolveWorld();
		if (w == null) return false;
		return w.isChunkLoaded(getChunkX(), getChunkZ());
	}

	public double distanceSquaredTo(Location location) {
		if (location == null || location.getWorld() == null) return Double.MAX_VALUE;
		if (!world.equals(location.getWorld().getName())) return Double.MAX_VALUE;
		double dx = x - location.getX();
		double dy = y - location.getY();
		double dz = z - location.getZ();
		return (dx * dx) + (dy * dy) + (dz * dz);
	}
}
