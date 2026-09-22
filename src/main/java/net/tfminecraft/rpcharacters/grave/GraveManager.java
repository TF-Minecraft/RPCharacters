package net.tfminecraft.rpcharacters.grave;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class GraveManager {

	private static final GraveManager INSTANCE = new GraveManager();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int SEARCH_RADIUS = 2;

	private final Map<UUID, Grave> byId = new ConcurrentHashMap<>();
	private final Map<String, Grave> byBlock = new ConcurrentHashMap<>();

	private GraveManager() {}

	public static GraveManager get() {
		return INSTANCE;
	}

	public Grave getAt(Block block) {
		if (block == null) {
			return null;
		}
		if (block.getState() instanceof TileState tile) {
			String raw = tile.getPersistentDataContainer().get(GraveKeys.graveId(), GraveKeys.GRAVE_ID_TYPE);
			if (raw != null && !raw.isBlank()) {
				try {
					Grave tagged = byId.get(UUID.fromString(raw));
					if (tagged != null) {
						return tagged;
					}
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
		return byBlock.get(blockKey(block));
	}

	public boolean isGrave(Block block) {
		return getAt(block) != null;
	}

	public Grave findNewestByOwner(UUID ownerId) {
		if (ownerId == null) {
			return null;
		}
		Grave newest = null;
		for (Grave grave : byId.values()) {
			if (!grave.isOwner(ownerId)) {
				continue;
			}
			if (newest == null || grave.getCreated() > newest.getCreated()) {
				newest = grave;
			}
		}
		return newest;
	}

	public List<Grave> getGravesNear(Location center, double radius) {
		List<Grave> result = new ArrayList<>();
		if (center == null || center.getWorld() == null || radius <= 0.0) {
			return result;
		}
		double radiusSq = radius * radius;
		World world = center.getWorld();
		for (Grave grave : byId.values()) {
			Location loc = grave.getBlockLocation();
			if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
				continue;
			}
			if (!loc.getChunk().isLoaded()) {
				continue;
			}
			if (center.distanceSquared(loc) > radiusSq) {
				continue;
			}
			if (!isGraveBlockPresent(grave)) {
				continue;
			}
			result.add(grave);
		}
		return result;
	}

	public boolean isGraveBlockPresent(Grave grave) {
		if (grave == null) {
			return false;
		}
		Location loc = grave.getBlockLocation();
		if (loc == null || loc.getWorld() == null) {
			return false;
		}
		return loc.getBlock().getType() == GraveLoader.getMaterial();
	}

	public void register(Grave grave) {
		if (grave == null || grave.getId() == null) {
			return;
		}
		Grave previous = byId.put(grave.getId(), grave);
		if (previous != null && previous.getBlockLocation() != null) {
			byBlock.remove(blockKey(previous.getBlockLocation()));
		}
		if (grave.getBlockLocation() != null) {
			byBlock.put(blockKey(grave.getBlockLocation()), grave);
		}
	}

	public void unregister(Grave grave) {
		if (grave == null) {
			return;
		}
		byId.remove(grave.getId());
		if (grave.getBlockLocation() != null) {
			byBlock.remove(blockKey(grave.getBlockLocation()));
		}
	}

	public void applyPdc(Block block, Grave grave) {
		if (block == null || grave == null || !(block.getState() instanceof TileState tile)) {
			return;
		}
		PersistentDataContainer pdc = tile.getPersistentDataContainer();
		pdc.set(GraveKeys.graveId(), GraveKeys.GRAVE_ID_TYPE, grave.getId().toString());
		tile.update();
	}

	public void save(Grave grave) {
		if (grave == null) {
			return;
		}
		register(grave);
		Location loc = grave.getBlockLocation();
		if (loc != null && loc.getWorld() != null) {
			applyPdc(loc.getBlock(), grave);
		}
		File file = fileFor(grave.getId());
		file.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(toRecord(grave), writer);
		} catch (IOException e) {
			RPCharacters.plugin.getLogger().warning("Could not save grave " + grave.getId() + ": " + e.getMessage());
		}
	}

	public void saveAll() {
		for (Grave grave : new ArrayList<>(byId.values())) {
			save(grave);
		}
	}

	public void loadAll() {
		byId.clear();
		byBlock.clear();
		File folder = gravesFolder();
		if (!folder.isDirectory()) {
			return;
		}
		File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null) {
			return;
		}
		for (File file : files) {
			try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
				GraveRecord record = GSON.fromJson(reader, GraveRecord.class);
				Grave grave = fromRecord(record);
				if (grave == null) {
					continue;
				}
				register(grave);
				GraveVisualManager.cleanupLegacyHologram(grave);
			} catch (Exception e) {
				RPCharacters.plugin.getLogger().warning("Could not load grave file " + file.getName() + ": " + e.getMessage());
			}
		}
	}

	public Grave spawn(Player victim, Block chestBlock, UUID killer, String killerDisplay, int experience,
			boolean protect, ItemStack[] storage, ItemStack[] armor, ItemStack offhand, List<ItemStack> extras) {
		if (victim == null || chestBlock == null) {
			return null;
		}
		chestBlock.getChunk().load();
		Material material = GraveLoader.getMaterial();
		chestBlock.setType(material, false);
		if (chestBlock.getType() != material) {
			return null;
		}

		Grave grave = new Grave(UUID.randomUUID(), victim.getUniqueId(), chestBlock.getLocation());
		grave.setKiller(killer);
		grave.setKillerDisplay(killerDisplay);
		grave.setProtected(protect);
		grave.setLocked(true);
		grave.setExperience(experience);
		copyInto(grave, storage, armor, offhand, extras);

		applyPdc(chestBlock, grave);
		save(grave);
		return grave;
	}

	public Block findChestBlock(Player player, Location deathLocation) {
		if (deathLocation != null && deathLocation.getWorld() != null) {
			Block atDeath = deathLocation.getBlock();
			if (isWaterLike(atDeath)) {
				Block placed = pickPlaceable(atDeath);
				if (placed != null) {
					return placed;
				}
			} else {
				Block support = findColumnSupport(deathLocation);
				if (support != null) {
					if (isWaterLike(support)) {
						Block placed = pickPlaceable(support);
						if (placed != null) {
							return placed;
						}
					} else {
						Block placed = pickOnSolid(support);
						if (placed != null) {
							return placed;
						}
					}
				}
			}
		}
		Location cached = LastSolidTracker.get().getLastSolid(player);
		if (cached != null) {
			Block placed = pickOnSolid(cached.getBlock());
			if (placed != null) {
				return placed;
			}
		}
		return findLastResort(deathLocation);
	}

	private Block findLastResort(Location deathLocation) {
		if (deathLocation == null || deathLocation.getWorld() == null) {
			return null;
		}
		World world = deathLocation.getWorld();
		Block deathBlock = deathLocation.getBlock();
		int x = deathBlock.getX();
		int y = deathBlock.getY();
		int z = deathBlock.getZ();

		Block air = findAirAbove(world, x, z, y);
		if (air != null) {
			air.getChunk().load();
			return air;
		}

		Block inChunk = findAirInChunk(world, x, z, y);
		if (inChunk != null) {
			inChunk.getChunk().load();
			return inChunk;
		}

		if (!isGrave(deathBlock)) {
			deathBlock.getChunk().load();
			return deathBlock;
		}
		return null;
	}

	private Block findAirAbove(World world, int x, int z, int startY) {
		int maxY = world.getMaxHeight() - 1;
		for (int y = startY; y <= maxY; y++) {
			Block block = world.getBlockAt(x, y, z);
			if (isAirGraveSpot(block)) {
				return block;
			}
		}
		return null;
	}

	private Block findAirInChunk(World world, int originX, int originZ, int startY) {
		int minX = (originX >> 4) << 4;
		int minZ = (originZ >> 4) << 4;
		int maxY = world.getMaxHeight() - 1;
		for (int x = minX; x < minX + 16; x++) {
			for (int z = minZ; z < minZ + 16; z++) {
				if (x == originX && z == originZ) {
					continue;
				}
				for (int y = startY; y <= maxY; y++) {
					Block block = world.getBlockAt(x, y, z);
					if (isAirGraveSpot(block)) {
						return block;
					}
				}
			}
		}
		return null;
	}

	private boolean isAirGraveSpot(Block block) {
		return block != null && !isGrave(block) && block.getType().isAir();
	}

	private static void copyInto(Grave grave, ItemStack[] storage, ItemStack[] armor, ItemStack offhand,
			List<ItemStack> extras) {
		if (storage != null) {
			for (int i = 0; i < Grave.STORAGE_SLOTS && i < storage.length; i++) {
				grave.setItem(i, storage[i] != null ? storage[i].clone() : null);
			}
		}
		if (armor != null) {
			for (int i = 0; i < Grave.ARMOR_SLOTS && i < armor.length; i++) {
				grave.setItem(Grave.STORAGE_SLOTS + i, armor[i] != null ? armor[i].clone() : null);
			}
		}
		grave.setItem(Grave.STORAGE_SLOTS + Grave.ARMOR_SLOTS, offhand != null ? offhand.clone() : null);
		if (extras != null) {
			for (ItemStack extra : extras) {
				grave.addExtra(extra);
			}
		}
	}

	private static Block findColumnSupport(Location deathLocation) {
		if (deathLocation == null || deathLocation.getWorld() == null) {
			return null;
		}
		World world = deathLocation.getWorld();
		int minY = world.getMinHeight();
		Block current = deathLocation.getBlock();
		for (int y = current.getY(); y >= minY; y--) {
			Block block = world.getBlockAt(current.getX(), y, current.getZ());
			if (isWaterLike(block) || block.getType().isSolid()) {
				return block;
			}
		}
		return null;
	}

	private Block pickOnSolid(Block ground) {
		if (ground == null) {
			return null;
		}
		Block above = ground.getRelative(0, 1, 0);
		if (canPlace(above)) {
			above.getChunk().load();
			return above;
		}
		if (canPlace(ground)) {
			ground.getChunk().load();
			return ground;
		}
		return pickPlaceable(ground);
	}

	private Block pickPlaceable(Block preferred) {
		if (preferred == null) {
			return null;
		}
		if (canPlace(preferred)) {
			preferred.getChunk().load();
			return preferred;
		}
		Block nearby = searchNearby(preferred);
		if (nearby != null) {
			nearby.getChunk().load();
		}
		return nearby;
	}

	private static boolean isWaterLike(Block block) {
		if (block == null) {
			return false;
		}
		Material type = block.getType();
		return type == Material.WATER || type == Material.BUBBLE_COLUMN;
	}

	private Block searchNearby(Block origin) {
		World world = origin.getWorld();
		int ox = origin.getX();
		int oy = origin.getY();
		int oz = origin.getZ();
		for (int dy = 0; dy <= 1; dy++) {
			for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					Block candidate = world.getBlockAt(ox + dx, oy + dy, oz + dz);
					if (canPlace(candidate)) {
						return candidate;
					}
				}
			}
		}
		return null;
	}

	private boolean canPlace(Block block) {
		if (block == null) {
			return false;
		}
		if (isGrave(block)) {
			return false;
		}
		Material type = block.getType();
		if (type == Material.LAVA) {
			return false;
		}
		if (type == GraveLoader.getMaterial()) {
			return true;
		}
		if (type.isAir()) {
			return true;
		}
		return !type.isSolid();
	}

	public void despawn(Grave grave) {
		if (grave == null) {
			return;
		}
		GraveVisualManager.get().removeGrave(grave.getId());
		GraveVisualManager.cleanupLegacyHologram(grave);
		Location loc = grave.getBlockLocation();
		if (loc != null && loc.getWorld() != null) {
			Block block = loc.getBlock();
			if (block.getType() == GraveLoader.getMaterial()) {
				block.setType(Material.AIR, false);
			}
		}
		unregister(grave);
		File file = fileFor(grave.getId());
		if (file.exists() && !file.delete()) {
			RPCharacters.plugin.getLogger().warning("Could not delete grave file " + file.getName());
		}
	}

	public boolean removeIfEmpty(Grave grave) {
		if (grave == null || !grave.isEmpty()) {
			return false;
		}
		despawn(grave);
		return true;
	}

	public boolean isExpired(Grave grave) {
		return GraveExpiryLogic.isExpired(
				grave != null ? grave.getCreated() : 0L,
				System.currentTimeMillis(),
				GraveLoader.getExpireSeconds());
	}

	public void expireGrave(Grave grave) {
		if (grave == null) {
			return;
		}
		Location loc = grave.getBlockLocation();
		if (loc == null || loc.getWorld() == null) {
			despawn(grave);
			return;
		}
		GraveChunkForceLoad.withForcedChunk(loc, () -> {
			if (!grave.isEmpty()) {
				Location dropAt = GraveRecover.graveOverflowLocation(grave);
				if (dropAt != null) {
					GraveLootDrop.dropAllToWorld(grave, dropAt);
				}
			}
			despawn(grave);
		});
	}

	public void expireOverdue() {
		if (GraveLoader.getExpireSeconds() <= 0) {
			return;
		}
		for (Grave grave : new ArrayList<>(byId.values())) {
			if (isExpired(grave)) {
				expireGrave(grave);
			}
		}
	}

	public void tickExpiry() {
		expireOverdue();
	}

	public static String blockKey(Block block) {
		return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
	}

	public static String blockKey(Location location) {
		if (location == null || location.getWorld() == null) {
			return "";
		}
		return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY()
				+ ":" + location.getBlockZ();
	}

	private static File gravesFolder() {
		return new File(RPCharacters.plugin.getDataFolder(), "graves");
	}

	private static File fileFor(UUID id) {
		return new File(gravesFolder(), id.toString() + ".json");
	}

	private static GraveRecord toRecord(Grave grave) {
		GraveRecord record = new GraveRecord();
		record.id = grave.getId().toString();
		record.owner = grave.getOwner() != null ? grave.getOwner().toString() : null;
		record.killer = grave.getKiller() != null ? grave.getKiller().toString() : null;
		record.killerDisplay = grave.getKillerDisplay();
		record.protect = grave.isProtected();
		record.locked = grave.isLocked();
		record.created = grave.getCreated();
		record.experience = grave.getExperience();
		record.hologramUuid = null;
		Location loc = grave.getBlockLocation();
		if (loc != null && loc.getWorld() != null) {
			record.world = loc.getWorld().getUID().toString();
			record.x = loc.getBlockX();
			record.y = loc.getBlockY();
			record.z = loc.getBlockZ();
		}
		record.storage = encodeArray(grave.getStorage());
		record.armor = encodeArray(grave.getArmor());
		record.offhand = serializeItem(grave.getOffhand());
		record.extras = encodeArray(grave.getExtras().toArray(new ItemStack[0]));
		return record;
	}

	private static Grave fromRecord(GraveRecord record) {
		if (record == null || record.id == null || record.owner == null || record.world == null) {
			return null;
		}
		World world = Bukkit.getWorld(UUID.fromString(record.world));
		if (world == null) {
			return null;
		}
		Location block = new Location(world, record.x, record.y, record.z);
		return new Grave(
				UUID.fromString(record.id),
				UUID.fromString(record.owner),
				parseUuid(record.killer),
				record.killerDisplay,
				record.protect,
				record.locked == null || record.locked,
				record.created,
				record.experience,
				decodeArray(record.storage, Grave.STORAGE_SLOTS),
				decodeArray(record.armor, Grave.ARMOR_SLOTS),
				deserializeItem(record.offhand),
				decodeList(record.extras),
				parseUuid(record.hologramUuid),
				block);
	}

	private static UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static List<String> encodeArray(ItemStack[] items) {
		List<String> encoded = new ArrayList<>();
		if (items == null) {
			return encoded;
		}
		for (ItemStack item : items) {
			encoded.add(serializeItem(item));
		}
		return encoded;
	}

	private static ItemStack[] decodeArray(List<String> encoded, int size) {
		ItemStack[] items = new ItemStack[size];
		if (encoded == null) {
			return items;
		}
		for (int i = 0; i < size && i < encoded.size(); i++) {
			items[i] = deserializeItem(encoded.get(i));
		}
		return items;
	}

	private static List<ItemStack> decodeList(List<String> encoded) {
		List<ItemStack> items = new ArrayList<>();
		if (encoded == null) {
			return items;
		}
		for (String raw : encoded) {
			ItemStack item = deserializeItem(raw);
			if (!Grave.isBlank(item)) {
				items.add(item);
			}
		}
		return items;
	}

	private static String serializeItem(ItemStack item) {
		if (Grave.isBlank(item)) {
			return null;
		}
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
			out.writeObject(item);
			out.flush();
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (IOException e) {
			return null;
		}
	}

	private static ItemStack deserializeItem(String data) {
		if (data == null || data.isEmpty()) {
			return null;
		}
		try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(data));
				BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
			Object value = in.readObject();
			return value instanceof ItemStack stack ? stack : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static final class GraveRecord {
		String id;
		String owner;
		String killer;
		String killerDisplay;
		boolean protect;
		Boolean locked;
		long created;
		int experience;
		String hologramUuid;
		String world;
		int x;
		int y;
		int z;
		List<String> storage;
		List<String> armor;
		String offhand;
		List<String> extras;
	}
}
