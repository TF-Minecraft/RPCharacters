package net.tfminecraft.rpcharacters.body;

import java.io.IOException;

import org.bukkit.entity.Player;

/** Runs after Paper restores the saved vehicle, leaving the whole vehicle tree in its original world. */
final class SavedMount {
    private SavedMount() {
    }

    static void detach(Player player) throws IOException {
        if (player.getVehicle() == null) return;
        if (!player.leaveVehicle() || player.getVehicle() != null) {
            throw new IOException("Saved vehicle could not release its player");
        }
    }
}
