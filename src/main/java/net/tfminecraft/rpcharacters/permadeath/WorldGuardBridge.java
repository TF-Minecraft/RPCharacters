package net.tfminecraft.rpcharacters.permadeath;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class WorldGuardBridge {

	private static boolean available;

	private WorldGuardBridge() {
	}

	public static void init() {
		available = false;

		if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
			RPCharacters.plugin.getLogger().warning(
					"WorldGuard not found — cuboid permadeath zones are unavailable.");
			return;
		}

		try {
			WorldGuard.getInstance().getPlatform().getRegionContainer();
			available = true;
			RPCharacters.plugin.getLogger().info("WorldGuard bridge enabled for permadeath zones.");
		} catch (Exception ex) {
			RPCharacters.plugin.getLogger().warning(
					"Failed to initialize WorldGuard bridge — cuboid permadeath zones are unavailable: "
							+ ex.getMessage());
			ex.printStackTrace();
		}
	}

	public static boolean isAvailable() {
		return available;
	}

	public static PermadeathZoneDefinition getPermadeathZoneAt(Location location) {
		if (!available || location == null || location.getWorld() == null) {
			return null;
		}

		for (String regionId : getRegionIdsAt(location)) {
			PermadeathZoneDefinition zone = PermadeathZoneLoader.getZone(regionId);
			if (zone != null) {
				return zone;
			}
		}
		return null;
	}

	public static boolean isInPermadeathZone(Location location) {
		return getPermadeathZoneAt(location) != null;
	}

	public static List<String> getRegionIdsAt(Location location) {
		List<String> ids = new ArrayList<>();
		if (!available || location == null || location.getWorld() == null) {
			return ids;
		}

		try {
			RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
			RegionQuery query = container.createQuery();
			ApplicableRegionSet applicable = query.getApplicableRegions(BukkitAdapter.adapt(location));
			for (ProtectedRegion region : applicable) {
				ids.add(region.getId());
			}
		} catch (Exception ex) {
			RPCharacters.plugin.getLogger().warning("WorldGuard region lookup failed: " + ex.getMessage());
		}

		return ids;
	}
}
