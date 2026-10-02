package net.tfminecraft.rpcharacters.grave;

import org.bukkit.Chunk;
import org.bukkit.Location;

import net.tfminecraft.rpcharacters.RPCharacters;

final class GraveChunkForceLoad {

	private GraveChunkForceLoad() {
	}

	static void withForcedChunk(Location location, Runnable action) {
		if (action == null) {
			return;
		}
		if (location == null || location.getWorld() == null) {
			action.run();
			return;
		}
		Chunk chunk = location.getChunk();
		boolean ticketAdded = addTicket(chunk);
		try {
			chunk.load(true);
			action.run();
		} finally {
			if (ticketAdded) {
				removeTicket(chunk);
			}
		}
	}

	private static boolean addTicket(Chunk chunk) {
		try {
			return chunk.addPluginChunkTicket(RPCharacters.plugin);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static void removeTicket(Chunk chunk) {
		try {
			chunk.removePluginChunkTicket(RPCharacters.plugin);
		} catch (Throwable ignored) {
		}
	}
}
