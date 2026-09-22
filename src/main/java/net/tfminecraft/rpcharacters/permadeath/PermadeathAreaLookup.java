package net.tfminecraft.rpcharacters.permadeath;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class PermadeathAreaLookup {

	private static final String SF_BRIDGE =
			"net.tfminecraft.rpcharacters.permadeath.SimpleFactionsRegionBridge";

	private static volatile Method simpleFactionsZoneAt;

	private PermadeathAreaLookup() {
	}

	/**
	 * WorldGuard cuboids win when both match. Either source must also be a
	 * {@code zones.yml} permadeath-zones key.
	 */
	public static PermadeathZoneDefinition preferWorldGuard(
			PermadeathZoneDefinition worldGuard,
			PermadeathZoneDefinition simpleFactions) {
		if (worldGuard != null) {
			return worldGuard;
		}
		return simpleFactions;
	}

	public static PermadeathZoneDefinition getPermadeathZoneAt(Player player, Location location) {
		PermadeathZoneDefinition worldGuard = null;
		if (WorldGuardBridge.isAvailable()) {
			worldGuard = WorldGuardBridge.getPermadeathZoneAt(location);
		}
		if (worldGuard != null) {
			return worldGuard;
		}
		return simpleFactionsZone(player, location);
	}

	public static PermadeathZoneDefinition getPermadeathZoneAt(Location location) {
		return getPermadeathZoneAt(null, location);
	}

	public static boolean isInPermadeathZone(Player player, Location location) {
		return getPermadeathZoneAt(player, location) != null;
	}

	private static boolean simpleFactionsPluginEnabled() {
		try {
			return Bukkit.getPluginManager() != null
					&& Bukkit.getPluginManager().isPluginEnabled("SimpleFactions");
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static PermadeathZoneDefinition simpleFactionsZone(Player player, Location location) {
		if (!simpleFactionsPluginEnabled()) {
			return null;
		}
		try {
			Method method = simpleFactionsZoneAt;
			if (method == null) {
				Class<?> bridge = Class.forName(SF_BRIDGE);
				method = bridge.getMethod("getPermadeathZoneAt", Player.class, Location.class);
				simpleFactionsZoneAt = method;
			}
			Object zone = method.invoke(null, player, location);
			return (PermadeathZoneDefinition) zone;
		} catch (ClassNotFoundException | NoClassDefFoundError ignored) {
			return null;
		} catch (Exception ex) {
			if (RPCharacters.plugin != null) {
				RPCharacters.plugin.getLogger().warning(
						"SimpleFactions permadeath lookup failed: " + ex.getMessage());
			}
			return null;
		}
	}
}
