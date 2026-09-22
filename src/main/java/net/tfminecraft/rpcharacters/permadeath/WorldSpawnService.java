package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;

public final class WorldSpawnService {

	private WorldSpawnService() {
	}

	public static Location getSpawn() {
		Location configured = PermadeathZoneLoader.getWorldSpawn();
		if (configured != null) {
			return configured.clone();
		}
		if (!Bukkit.getWorlds().isEmpty()) {
			return Bukkit.getWorlds().get(0).getSpawnLocation().clone();
		}
		return null;
	}
}
