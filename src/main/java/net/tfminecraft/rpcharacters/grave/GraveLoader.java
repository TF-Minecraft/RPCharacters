package net.tfminecraft.rpcharacters.grave;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.tlibs.TLibs;

public final class GraveLoader implements LoaderInterface {

	private static boolean enabled = true;
	private static boolean protectByDefault = true;
	private static boolean hologramShowKiller = true;
	private static long snapshotIntervalTicks = 20L;
	private static int expireSeconds = 0;
	private static Material material = Material.CHEST;
	private static double hologramOffsetY = 1.2;
	private static double hologramRadius = 32.0;
	private static String hologramTimerFormat = "&7Despawns in &e{time}";
	private static String hologramTimerExpiring = "&7Despawning...";
	private static Set<Integer> excludedSlots = new HashSet<>();
	private static List<String> excludedItems = new ArrayList<>();
	private static Map<Integer, List<String>> excludedSlotItems = new HashMap<>();

	private static String insuranceItemPath = "m.miscellanea.grave_insurance";
	private static boolean insuranceConsume = true;

	private static String messageLocked = "&cThis grave is locked.";
	private static String messageRecovered = "&aYou recovered your belongings.";
	private static String messageLooted = "&aYou looted this grave.";
	private static String messageInventoryFull = "&eSome items did not fit and were dropped.";
	private static String messageEmpty = "&7There is nothing left here.";
	private static String messagePlaced = "&eYour grave was placed at &f{x}, {y}, {z} &ein {world}.";
	private static String messageUnlockHint = "&eRun &f/grave unlock &eto let others loot this grave.";
	private static String messageInsuranceNone = "&eYou have no grave to recover.";
	private static String messageUnlockedByStrike = "&cYou were in an evil RP session, so your grave is unlocked. Anyone can loot it.";
	private static String messageUnlockNone = "&eYou have no grave to unlock.";
	private static String messageUnlockAlready = "&eYour grave is already unlocked.";
	private static String messageUnlockSuccess = "&aYour grave is now unlocked. Anyone can loot it.";
	private static String messageRobHint = "&aRight click to rob";

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		enabled = config.getBoolean("enabled", true);
		protectByDefault = config.getBoolean("protect-by-default", true);
		hologramShowKiller = config.getBoolean("hologram-show-killer", true);
		snapshotIntervalTicks = Math.max(1L, config.getLong("snapshot-interval-ticks", 20L));
		expireSeconds = Math.max(0, config.getInt("expire-seconds", 0));
		hologramOffsetY = config.getDouble("hologram-offset-y", 1.2);
		hologramRadius = config.getDouble("hologram-radius", 32.0);
		hologramTimerFormat = config.getString("hologram-timer-format", hologramTimerFormat);
		hologramTimerExpiring = config.getString("hologram-timer-expiring", hologramTimerExpiring);

		Material parsed = Material.matchMaterial(config.getString("material", "CHEST"));
		material = parsed != null ? parsed : Material.CHEST;

		excludedSlots = parseExcludedSlots(config.getIntegerList("excluded-slots"));
		excludedItems = parseExcludedItems(config.getStringList("excluded-items"));
		excludedSlotItems = parseExcludedSlotItems(config.getConfigurationSection("excluded-slot-items"));

		String configuredInsurance = config.getString("insurance.item", insuranceItemPath);
		insuranceItemPath = configuredInsurance != null ? configuredInsurance.trim() : "";
		insuranceConsume = config.getBoolean("insurance.consume", true);

		messageLocked = config.getString("messages.locked", messageLocked);
		messageRecovered = config.getString("messages.recovered", messageRecovered);
		messageLooted = config.getString("messages.looted", messageLooted);
		messageInventoryFull = config.getString("messages.inventory-full", messageInventoryFull);
		messageEmpty = config.getString("messages.empty", messageEmpty);
		messagePlaced = config.getString("messages.placed", messagePlaced);
		messageUnlockHint = config.getString("messages.unlock-hint", messageUnlockHint);
		messageInsuranceNone = config.getString("messages.insurance-none", messageInsuranceNone);
		messageUnlockedByStrike = config.getString("messages.unlocked-evil-rp", messageUnlockedByStrike);
		messageUnlockNone = config.getString("messages.unlock-none", messageUnlockNone);
		messageUnlockAlready = config.getString("messages.unlock-already", messageUnlockAlready);
		messageUnlockSuccess = config.getString("messages.unlock-success", messageUnlockSuccess);
		messageRobHint = config.getString("messages.rob-hint", messageRobHint);
	}

	private static Set<Integer> parseExcludedSlots(List<Integer> configured) {
		Set<Integer> slots = new HashSet<>();
		if (configured == null) {
			return slots;
		}
		for (Integer slot : configured) {
			if (slot != null && slot >= 0 && slot <= 40) {
				slots.add(slot);
			}
		}
		return slots;
	}

	private static List<String> parseExcludedItems(List<String> configured) {
		List<String> paths = new ArrayList<>();
		if (configured == null) {
			return paths;
		}
		for (String path : configured) {
			if (path != null && !path.isBlank()) {
				paths.add(path.trim());
			}
		}
		return paths;
	}

	private static Map<Integer, List<String>> parseExcludedSlotItems(ConfigurationSection section) {
		Map<Integer, List<String>> mapped = new HashMap<>();
		if (section == null) {
			return mapped;
		}
		for (String key : section.getKeys(false)) {
			int slot;
			try {
				slot = Integer.parseInt(key);
			} catch (NumberFormatException e) {
				continue;
			}
			if (slot < 0 || slot > 40) {
				continue;
			}
			List<String> paths = parseExcludedItems(section.getStringList(key));
			if (!paths.isEmpty()) {
				mapped.put(slot, paths);
			}
		}
		return mapped;
	}

	public static boolean keepOutOfGrave(int slot, ItemStack item) {
		if (Grave.isBlank(item)) {
			return false;
		}
		if (isExcludedSlot(slot) || isExcludedItem(item)) {
			return true;
		}
		List<String> paths = excludedSlotItems.get(slot);
		if (paths == null || paths.isEmpty()) {
			return false;
		}
		for (String path : paths) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean isProtectByDefault() {
		return protectByDefault;
	}

	public static boolean isHologramShowKiller() {
		return hologramShowKiller;
	}

	public static long getSnapshotIntervalTicks() {
		return snapshotIntervalTicks;
	}

	public static int getExpireSeconds() {
		return expireSeconds;
	}

	public static Material getMaterial() {
		return material;
	}

	public static double getHologramOffsetY() {
		return hologramOffsetY;
	}

	public static double getHologramRadius() {
		return hologramRadius;
	}

	public static String getHologramTimerFormat() {
		return hologramTimerFormat;
	}

	public static String getHologramTimerExpiring() {
		return hologramTimerExpiring;
	}

	public static Set<Integer> getExcludedSlots() {
		return Collections.unmodifiableSet(excludedSlots);
	}

	public static boolean isExcludedSlot(int slot) {
		return excludedSlots.contains(slot);
	}

	public static boolean isExcludedItem(ItemStack item) {
		if (Grave.isBlank(item) || excludedItems.isEmpty()) {
			return false;
		}
		for (String path : excludedItems) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
				return true;
			}
		}
		return false;
	}

	public static String getMessageLocked() {
		return messageLocked;
	}

	public static String getMessageRecovered() {
		return messageRecovered;
	}

	public static String getMessageLooted() {
		return messageLooted;
	}

	public static String getMessageInventoryFull() {
		return messageInventoryFull;
	}

	public static String getMessageEmpty() {
		return messageEmpty;
	}

	public static String getMessagePlaced() {
		return messagePlaced;
	}

	public static String getMessageUnlockedByStrike() {
		return messageUnlockedByStrike;
	}

	public static String getMessageUnlockHint() {
		return messageUnlockHint;
	}

	public static boolean isInsuranceEnabled() {
		return insuranceItemPath != null && !insuranceItemPath.isBlank();
	}

	public static boolean isInsuranceConsume() {
		return insuranceConsume;
	}

	public static boolean isInsuranceItem(ItemStack item) {
		if (!isInsuranceEnabled() || Grave.isBlank(item)) {
			return false;
		}
		return TLibs.getItemAPI().getChecker().checkItemWithPath(item, insuranceItemPath);
	}

	public static String getMessageInsuranceNone() {
		return messageInsuranceNone;
	}

	public static String getMessageUnlockNone() {
		return messageUnlockNone;
	}

	public static String getMessageUnlockAlready() {
		return messageUnlockAlready;
	}

	public static String getMessageUnlockSuccess() {
		return messageUnlockSuccess;
	}

	public static String getMessageRobHint() {
		return messageRobHint;
	}
}
