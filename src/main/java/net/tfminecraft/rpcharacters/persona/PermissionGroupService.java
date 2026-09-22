package net.tfminecraft.rpcharacters.persona;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.identity.NameColour;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PermissionGroupService {

	private static final int LEGACY_SWITCH_COOLDOWN_DAYS = 14;

	private PermissionGroupService() {}

	public static int getNameColourStops(Player player) {
		return resolvePerk(player, PermissionGroupDefinition.KEY_NAME_COLOUR_STOPS, Aggregation.MAX);
	}

	public static int getCharacterSwitchCooldownDays(Player player) {
		return resolvePerk(player, PermissionGroupDefinition.KEY_CHARACTER_SWITCH_COOLDOWN_DAYS, Aggregation.MIN);
	}

	public static int getMaxAliveCharacters(Player player) {
		return resolvePerk(player, PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, Aggregation.MAX);
	}

	/** Swappable wardrobe skins (base + extras). Masked is separate and always available. */
	public static int getWardrobeSkinSlots(Player player) {
		return resolvePerk(player, PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS, Aggregation.MAX);
	}

	public static boolean canUseNameColour(Player player) {
		return getNameColourStops(player) > 0;
	}

	public static boolean hasCharacterSwitchCooldown(Player player, PlayerData data) {
		if (player == null || data == null) {
			return false;
		}
		Long lastSwitchMs = data.getLastCharacterSwitchAtMs();
		if (lastSwitchMs == null) {
			return false;
		}
		int cooldownDays = getCharacterSwitchCooldownDays(player);
		if (cooldownDays <= 0) {
			return false;
		}
		long cooldownEndMs = lastSwitchMs + Duration.ofDays(cooldownDays).toMillis();
		return System.currentTimeMillis() < cooldownEndMs;
	}

	public static int getRemainingCooldownMinutes(Player player, PlayerData data) {
		if (player == null || data == null) {
			return 0;
		}
		Long lastSwitchMs = data.getLastCharacterSwitchAtMs();
		if (lastSwitchMs == null) {
			return 0;
		}
		int cooldownDays = getCharacterSwitchCooldownDays(player);
		if (cooldownDays <= 0) {
			return 0;
		}
		long cooldownEndMs = lastSwitchMs + Duration.ofDays(cooldownDays).toMillis();
		long remainingMs = cooldownEndMs - System.currentTimeMillis();
		if (remainingMs <= 0) {
			return 0;
		}
		return (int) Math.ceil(remainingMs / 60_000.0);
	}

	public static Optional<String> validateNameColourHexes(Player player, List<String> hexArgs, boolean staffOverride) {
		if (hexArgs == null || hexArgs.isEmpty()) {
			return Optional.of(RPTexts.formatDisplay(RPTexts.ERROR + "Provide at least one hex colour."));
		}
		if (!staffOverride) {
			int maxStops = getNameColourStops(player);
			if (maxStops <= 0) {
				return Optional.of(RPTexts.formatDisplay(RPTexts.ERROR + "You do not have permission to change your name colour."));
			}
			if (hexArgs.size() > maxStops) {
				return Optional.of(RPTexts.formatDisplay(RPTexts.ERROR + "You can use up to " + RPTexts.WARN + maxStops
						+ " " + RPTexts.ERROR + "colour(s) with your rank."));
			}
		}
		return Optional.empty();
	}

	public static void enforceNameColourOnLogin(Player player, PlayerData data) {
		if (player == null || data == null) {
			return;
		}
		int maxStops = getNameColourStops(player);
		boolean changed = false;
		for (RPCharacter character : data.getCharacters()) {
			if (character == null || character.getNameColour() == null) {
				continue;
			}
			if (character.isNameColourStaffOverride()) {
				continue;
			}
			if (maxStops <= 0) {
				character.setNameColour(null);
				changed = true;
				continue;
			}
			List<String> codes = character.getNameColour().getHexCodes();
			if (codes.size() > maxStops) {
				character.setNameColour(NameColour.of(new ArrayList<>(codes.subList(0, maxStops))));
				changed = true;
			}
		}
		if (changed) {
			net.tfminecraft.rpcharacters.RPCharacters.getPlayerManager().savePlayer(player);
		}
	}

	public static Long migrateLegacyCooldownMinutes(int remainingMinutes) {
		if (remainingMinutes <= 0) {
			return null;
		}
		long totalMinutes = (long) LEGACY_SWITCH_COOLDOWN_DAYS * 24L * 60L;
		long elapsedMinutes = Math.max(0, totalMinutes - remainingMinutes);
		return System.currentTimeMillis() - Duration.ofMinutes(elapsedMinutes).toMillis();
	}

	private static int resolvePerk(Player player, String perkKey, Aggregation aggregation) {
		int value = Cache.permissionGroupDefaults.getOrDefault(perkKey, 0);
		if (player == null) {
			return value;
		}
		for (PermissionGroupDefinition group : Cache.permissionGroups) {
			String permission = group.getPermission();
			if (permission == null || permission.isBlank()) {
				continue;
			}
			if (!player.hasPermission(permission)) {
				continue;
			}
			int groupValue = group.getPerk(perkKey, value);
			value = aggregation.apply(value, groupValue);
		}
		return value;
	}

	private enum Aggregation {
		MAX {
			@Override
			int apply(int current, int candidate) {
				return Math.max(current, candidate);
			}
		},
		MIN {
			@Override
			int apply(int current, int candidate) {
				return Math.min(current, candidate);
			}
		};

		abstract int apply(int current, int candidate);
	}
}
