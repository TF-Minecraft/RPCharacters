package net.tfminecraft.rpcharacters.persona;

import java.util.Comparator;
import java.util.Optional;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class CharacterSlotService {

	private CharacterSlotService() {}

	public static int getMaxAliveCharacters(Player player) {
		int fromGroups = PermissionGroupService.getMaxAliveCharacters(player);
		int cap = getHardSlotCap();
		return Math.min(fromGroups, cap);
	}

	public static int getHardSlotCap() {
		if (Cache.maxCharacterSlots > 0) {
			return Cache.maxCharacterSlots;
		}
		return 10;
	}

	public static int getDisplaySlotCount() {
		if (Cache.characterSlots == null) {
			return 0;
		}
		return Math.min(Cache.characterSlots.size(), getHardSlotCap());
	}

	public static int getBaseRowSlotCount() {
		if (Cache.baseCharacterSlotCount > 0) {
			return Cache.baseCharacterSlotCount;
		}
		return Math.min(5, getDisplaySlotCount());
	}

	public static boolean shouldShowLockedSlot(int slotIndex, int maxAllowed) {
		return slotIndex >= maxAllowed && slotIndex < getBaseRowSlotCount();
	}

	public static boolean hasFreeSlot(Player player, PlayerData data) {
		if (player == null || data == null) {
			return false;
		}
		return data.getCharacters(Status.ALIVE).size() < getMaxAliveCharacters(player);
	}

	public static boolean isSlotUnlocked(Player player, int slotIndex) {
		return slotIndex < getMaxAliveCharacters(player);
	}

	public static boolean isOverSlotLimit(Player player, PlayerData data) {
		if (player == null || data == null) {
			return false;
		}
		return data.getCharacters(Status.ALIVE).size() > getMaxAliveCharacters(player);
	}

	/**
	 * Lowest-tier visible group that unlocks this slot (for locked-slot lore).
	 */
	public static Optional<PermissionGroupDefinition> getMinimumVisibleUnlockGroup(int slotIndex) {
		int defaultMax = Cache.permissionGroupDefaults.getOrDefault(
				PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, 3);
		return Cache.permissionGroups.stream()
				.filter(PermissionGroupDefinition::isVisible)
				.filter(group -> group.getPerk(PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, defaultMax) > slotIndex)
				.min(Comparator.comparingInt(PermissionGroupDefinition::getTier));
	}

	public static String getUnlockRequirementLore(int slotIndex) {
		Optional<PermissionGroupDefinition> group = getMinimumVisibleUnlockGroup(slotIndex);
		if (group.isEmpty()) {
			return RPTexts.formatGui(RPTexts.MUTED + "Requires a higher rank");
		}
		String rawName = group.get().getDisplayName();
		if (rawName == null || rawName.isBlank()) {
			rawName = group.get().getId();
		}
		return RPTexts.formatGui(RPTexts.MUTED + "Requires at least " + formatGroupDisplayName(rawName));
	}

	static String formatGroupDisplayName(String raw) {
		if (raw == null || raw.isBlank()) {
			return raw;
		}
		return StringFormatter.formatHex(raw.replace('&', '\u00A7'));
	}
}
