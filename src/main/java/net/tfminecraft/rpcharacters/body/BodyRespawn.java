package net.tfminecraft.rpcharacters.body;

import java.io.IOException;

import org.bukkit.entity.Player;

/** Paper exposes the spawn point publicly but keeps its vanilla forced flag on the player handle. */
final class BodyRespawn {
    private BodyRespawn() {
    }

    static boolean forced(Player player) throws IOException {
        if (player.getRespawnLocation(false) == null) return false;
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object config = handle.getClass().getMethod("getRespawnConfig").invoke(handle);
            if (config == null) return false;
            Object forced = config.getClass().getMethod("forced").invoke(config);
            if (!(forced instanceof Boolean value)) throw new IOException("Invalid Paper forced-respawn flag");
            return value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            throw new IOException("Paper forced-respawn flag could not be read", error);
        }
    }
}
