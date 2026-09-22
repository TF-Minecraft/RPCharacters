package net.tfminecraft.rpcharacters.wardrobe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * In-game wardrobe picker: green = filled, yellow = empty, red = locked.
 */
public final class WardrobeGui {

	public static final String TITLE_RAW = RPTexts.MUTED + "Wardrobe";

	private static final int[] BUTTON_SLOTS = { 11, 13, 15 };
	private static final String[] SLOT_IDS = {
		WardrobeSnapshot.SLOT_BASE,
		WardrobeSnapshot.SLOT_EXTRA_1,
		WardrobeSnapshot.SLOT_EXTRA_2
	};

	private WardrobeGui() {}

	static NamespacedKey slotKey() {
		return new NamespacedKey(RPCharacters.plugin, "wardrobe_slot");
	}
	/** Open GUI if cache ready; otherwise refresh then open. */
	public static void open(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null) {
			RPTexts.send(
				player,
				RPTexts.WARN + "Loading wardrobe… Opening when ready."
			);
			WardrobeService.refreshActiveAsync(player, () -> {
				if (player.isOnline() && WardrobeCache.get(player) != null) {
					openNow(player);
				} else if (player.isOnline()) {
					RPTexts.send(
						player,
						RPTexts.ERROR + "Could not load wardrobe. Try again."
					);
				}
			});
			return;
		}
		openNow(player);
	}

	// Existing GUI text uses the same legacy formatter. Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	public static void openNow(Player player) {
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (player == null || !player.isOnline() || snapshot == null) {
			return;
		}
		WardrobeGuiHolder holder = new WardrobeGuiHolder(player);
		Inventory inv = Bukkit.createInventory(
			holder,
			27,
			RPTexts.formatGui(TITLE_RAW)
		);
		holder.setInventory(inv);
		for (int i = 0; i < SLOT_IDS.length; i++) {
			inv.setItem(BUTTON_SLOTS[i], buildButton(snapshot, SLOT_IDS[i]));
		}
		player.openInventory(inv);
	}

	public static void refreshOpen(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}
		if (!(player.getOpenInventory().getTopInventory().getHolder()
			instanceof WardrobeGuiHolder)) {
			return;
		}
		openNow(player);
	}

	/** Close wardrobe GUI if open (required before skin apply to avoid client desync). */
	public static boolean closeIfOpen(Player player) {
		if (player == null || !player.isOnline()) {
			return false;
		}
		if (!(player.getOpenInventory().getTopInventory().getHolder()
			instanceof WardrobeGuiHolder)) {
			return false;
		}
		player.closeInventory();
		return true;
	}

	public static String readSlotId(ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return null;
		}
		String raw = item.getItemMeta().getPersistentDataContainer()
			.get(slotKey(), PersistentDataType.STRING);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim().toLowerCase();
	}

	// Existing GUI text uses the same legacy formatter. Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private static ItemStack buildButton(WardrobeSnapshot snapshot, String slotId) {
		WardrobeSlotData data = snapshot.getSlot(slotId);
		boolean unlocked = isUnlocked(snapshot, slotId, data);
		boolean filled = data != null && data.isFilled() && data.canApply();
		boolean active = snapshot.getActiveSlot() != null
			&& snapshot.getActiveSlot().equalsIgnoreCase(slotId);

		Material mat;
		if (!unlocked) {
			mat = Material.RED_CONCRETE;
		} else if (filled) {
			mat = Material.GREEN_CONCRETE;
		} else {
			mat = Material.YELLOW_CONCRETE;
		}

		ItemStack item = new ItemStack(mat);
		ItemMeta meta = item.getItemMeta();
		String label = WardrobeService.labelForSlot(snapshot, slotId);
		List<String> lore = new ArrayList<>();

		if (!unlocked) {
			meta.setDisplayName(RPTexts.formatGui(RPTexts.ERROR + "LOCKED"));
			lore.add(RPTexts.formatGui(RPTexts.MUTED + label));
			lore.add(getUnlockRequirementLore(minSlotsFor(slotId)));
		} else if (filled) {
			meta.setDisplayName(RPTexts.formatGui(RPTexts.SUCCESS + label));
			if (active) {
				lore.add(RPTexts.formatGui(RPTexts.SUCCESS + "Active"));
			} else {
				lore.add(RPTexts.formatGui(RPTexts.MUTED + "Click to equip"));
			}
		} else {
			meta.setDisplayName(RPTexts.formatGui(RPTexts.WARN + label));
			lore.add(RPTexts.formatGui(RPTexts.MUTED + "Empty"));
			lore.add(RPTexts.formatGui(
				RPTexts.MUTED + "Upload on the website (character → Wardrobe)"
			));
		}

		meta.setLore(lore);
		meta.getPersistentDataContainer().set(
			slotKey(),
			PersistentDataType.STRING,
			slotId
		);
		item.setItemMeta(meta);
		return item;
	}

	static boolean isUnlocked(
		WardrobeSnapshot snapshot,
		String slotId,
		WardrobeSlotData data
	) {
		if (data != null) {
			return data.isUnlocked();
		}
		int swappable = snapshot.getSwappableSlots();
		if (WardrobeSnapshot.SLOT_BASE.equals(slotId)) {
			return true;
		}
		if (WardrobeSnapshot.SLOT_EXTRA_1.equals(slotId)) {
			return swappable >= 2;
		}
		if (WardrobeSnapshot.SLOT_EXTRA_2.equals(slotId)) {
			return swappable >= 3;
		}
		return false;
	}

	static int minSlotsFor(String slotId) {
		if (WardrobeSnapshot.SLOT_EXTRA_2.equals(slotId)) {
			return 3;
		}
		if (WardrobeSnapshot.SLOT_EXTRA_1.equals(slotId)) {
			return 2;
		}
		return 1;
	}

	/**
	 * Lowest-tier visible group that grants at least {@code minSlots} wardrobe skins.
	 */
	static Optional<PermissionGroupDefinition> getMinimumVisibleUnlockGroup(
		int minSlots
	) {
		int defaultSlots = Cache.permissionGroupDefaults.getOrDefault(
			PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS,
			1
		);
		return Cache.permissionGroups.stream()
			.filter(PermissionGroupDefinition::isVisible)
			.filter(group -> group.getPerk(
				PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS,
				defaultSlots
			) >= minSlots)
			.min(Comparator.comparingInt(PermissionGroupDefinition::getTier));
	}

	static String getUnlockRequirementLore(int minSlots) {
		Optional<PermissionGroupDefinition> group =
			getMinimumVisibleUnlockGroup(minSlots);
		if (group.isEmpty()) {
			return RPTexts.formatGui(RPTexts.MUTED + "You need a higher rank+");
		}
		String rawName = group.get().getDisplayName();
		if (rawName == null || rawName.isBlank()) {
			rawName = group.get().getId();
		}
		return RPTexts.formatGui(
			RPTexts.MUTED + "You need " + formatGroupDisplayName(rawName) + "+"
		);
	}

	static String formatGroupDisplayName(String raw) {
		if (raw == null || raw.isBlank()) {
			return raw;
		}
		return StringFormatter.formatHex(raw.replace('&', '\u00A7'));
	}

	static String unlockChatMessage(int minSlots) {
		Optional<PermissionGroupDefinition> group =
			getMinimumVisibleUnlockGroup(minSlots);
		if (group.isEmpty()) {
			return RPTexts.ERROR + "You need a higher rank+";
		}
		String rawName = group.get().getDisplayName();
		if (rawName == null || rawName.isBlank()) {
			rawName = group.get().getId();
		}
		return RPTexts.ERROR + "You need "
			+ formatGroupDisplayName(rawName) + "+";
	}
}
