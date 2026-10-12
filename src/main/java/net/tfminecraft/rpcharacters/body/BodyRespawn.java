package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.lang.reflect.Method;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;

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

    /** MMOCore may heal a dead entity before recovery; bypass Spigot's health guard, never kill it again. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void respawn(Player player) throws IOException {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object players = player.getServer().getClass().getMethod("getHandle").invoke(player.getServer());
            for (Method method : players.getClass().getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (method.getName().equals("respawn") && types.length == 4 && types[0].isInstance(handle)
                        && types[1] == boolean.class && types[2].isEnum() && types[3] == RespawnReason.class) {
                    method.invoke(players, handle, false, Enum.valueOf((Class) types[2], "KILLED"), RespawnReason.PLUGIN);
                    return;
                }
            }
            throw new IOException("Paper player respawn method is unavailable");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            throw new IOException("Paper player respawn could not complete", error);
        }
    }
}
