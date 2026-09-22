package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public final class Grave {

	public static final int STORAGE_SLOTS = 36;
	public static final int ARMOR_SLOTS = 4;
	public static final int TOTAL_LOGICAL_SLOTS = STORAGE_SLOTS + ARMOR_SLOTS + 1;

	private final UUID id;
	private final UUID owner;
	private UUID killer;
	private String killerDisplay;
	private boolean protect;
	private boolean locked;
	private final long created;
	private int experience;
	private final ItemStack[] storage;
	private final ItemStack[] armor;
	private ItemStack offhand;
	private final List<ItemStack> extras;
	private UUID hologramId;
	private Location block;

	public Grave(UUID id, UUID owner, Location block) {
		this.id = id != null ? id : UUID.randomUUID();
		this.owner = owner;
		this.block = block != null ? block.clone() : null;
		this.created = System.currentTimeMillis();
		this.locked = true;
		this.storage = new ItemStack[STORAGE_SLOTS];
		this.armor = new ItemStack[ARMOR_SLOTS];
		this.extras = new ArrayList<>();
	}

	Grave(UUID id, UUID owner, UUID killer, String killerDisplay, boolean protect, boolean locked, long created,
			int experience, ItemStack[] storage, ItemStack[] armor, ItemStack offhand, List<ItemStack> extras,
			UUID hologramId, Location block) {
		this.id = id;
		this.owner = owner;
		this.killer = killer;
		this.killerDisplay = killerDisplay;
		this.protect = protect;
		this.locked = locked;
		this.created = created;
		this.experience = Math.max(0, experience);
		this.storage = copy(storage, STORAGE_SLOTS);
		this.armor = copy(armor, ARMOR_SLOTS);
		this.offhand = offhand;
		this.extras = copyExtras(extras);
		this.hologramId = hologramId;
		this.block = block != null ? block.clone() : null;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwner() {
		return owner;
	}

	public boolean isOwner(UUID playerId) {
		return owner != null && owner.equals(playerId);
	}

	public UUID getKiller() {
		return killer;
	}

	public void setKiller(UUID killer) {
		this.killer = killer;
	}

	public String getKillerDisplay() {
		return killerDisplay;
	}

	public void setKillerDisplay(String killerDisplay) {
		this.killerDisplay = killerDisplay;
	}

	public boolean isProtected() {
		return protect;
	}

	public void setProtected(boolean protect) {
		this.protect = protect;
	}

	public boolean isLocked() {
		return locked;
	}

	public void setLocked(boolean locked) {
		this.locked = locked;
	}

	public long getCreated() {
		return created;
	}

	public int getExperience() {
		return experience;
	}

	public void setExperience(int experience) {
		this.experience = Math.max(0, experience);
	}

	public UUID getHologramId() {
		return hologramId;
	}

	public void setHologramId(UUID hologramId) {
		this.hologramId = hologramId;
	}

	public Location getBlockLocation() {
		return block != null ? block.clone() : null;
	}

	public void setBlockLocation(Location block) {
		this.block = block != null ? block.clone() : null;
	}

	public ItemStack[] getStorage() {
		return storage;
	}

	public ItemStack[] getArmor() {
		return armor;
	}

	public ItemStack getOffhand() {
		return offhand;
	}

	public List<ItemStack> getExtras() {
		return Collections.unmodifiableList(extras);
	}

	public void addExtra(ItemStack item) {
		if (Grave.isBlank(item) || GraveLoader.isExcludedItem(item)) {
			return;
		}
		extras.add(item.clone());
	}

	public void clearExtras() {
		extras.clear();
	}

	public ItemStack getItem(int logicalSlot) {
		if (logicalSlot < 0 || logicalSlot >= TOTAL_LOGICAL_SLOTS) {
			return null;
		}
		if (logicalSlot < STORAGE_SLOTS) {
			return storage[logicalSlot];
		}
		if (logicalSlot < STORAGE_SLOTS + ARMOR_SLOTS) {
			return armor[logicalSlot - STORAGE_SLOTS];
		}
		return offhand;
	}

	public void setItem(int logicalSlot, ItemStack item) {
		if (logicalSlot < 0 || logicalSlot >= TOTAL_LOGICAL_SLOTS) {
			return;
		}
		if (logicalSlot < STORAGE_SLOTS) {
			if (GraveLoader.keepOutOfGrave(logicalSlot, item)) {
				storage[logicalSlot] = null;
				return;
			}
			storage[logicalSlot] = item;
			return;
		}
		if (logicalSlot < STORAGE_SLOTS + ARMOR_SLOTS) {
			int armorSlot = logicalSlot;
			if (GraveLoader.keepOutOfGrave(armorSlot, item)) {
				armor[logicalSlot - STORAGE_SLOTS] = null;
				return;
			}
			armor[logicalSlot - STORAGE_SLOTS] = item;
			return;
		}
		if (GraveLoader.keepOutOfGrave(logicalSlot, item)) {
			offhand = null;
			return;
		}
		offhand = item;
	}

	public void flush() {
		GraveManager.get().save(this);
	}

	public boolean isEmpty() {
		if (experience > 0) {
			return false;
		}
		for (ItemStack item : storage) {
			if (!isBlank(item)) {
				return false;
			}
		}
		for (ItemStack item : armor) {
			if (!isBlank(item)) {
				return false;
			}
		}
		for (ItemStack item : extras) {
			if (!isBlank(item)) {
				return false;
			}
		}
		return isBlank(offhand);
	}

	static boolean isBlank(ItemStack item) {
		return item == null || item.getType().isAir();
	}

	private static List<ItemStack> copyExtras(List<ItemStack> source) {
		List<ItemStack> copy = new ArrayList<>();
		if (source == null) {
			return copy;
		}
		for (ItemStack item : source) {
			if (!isBlank(item)) {
				copy.add(item);
			}
		}
		return copy;
	}

	private static ItemStack[] copy(ItemStack[] source, int size) {
		ItemStack[] copy = new ItemStack[size];
		if (source == null) {
			return copy;
		}
		System.arraycopy(source, 0, copy, 0, Math.min(source.length, size));
		return copy;
	}
}
