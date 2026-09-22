package net.tfminecraft.rpcharacters.grave;

import java.lang.reflect.Method;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.RPCharacters;

final class GraveChunkForceLoad {

	private static final Method ADD_TICKET = resolveTicketMethod("addPluginChunkTicket", Plugin.class);
	private static final Method REMOVE_TICKET = resolveTicketMethod("removePluginChunkTicket", Plugin.class);

	private GraveChunkForceLoad() {
	}

	static void withForcedChunk(Location location, Runnable action) {
		if (location == null || location.getWorld() == null || action == null) {
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
		if (chunk == null || ADD_TICKET == null) {
			return false;
		}
		try {
			Object result = ADD_TICKET.invoke(chunk, RPCharacters.plugin);
			return result instanceof Boolean bool && bool;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static void removeTicket(Chunk chunk) {
		if (chunk == null || REMOVE_TICKET == null) {
			return;
		}
		try {
			REMOVE_TICKET.invoke(chunk, RPCharacters.plugin);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static Method resolveTicketMethod(String name, Class<?>... parameterTypes) {
		try {
			return Chunk.class.getMethod(name, parameterTypes);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}
}
