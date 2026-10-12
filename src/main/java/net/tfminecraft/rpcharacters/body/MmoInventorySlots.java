package net.tfminecraft.rpcharacters.body;

import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Restores empty vanilla accessory slots using MMOInventory's own configured placeholders. */
final class MmoInventorySlots {
    private MmoInventorySlots() {
    }

    static void refresh(Player player) throws IOException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOInventory");
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            Object manager = plugin.getClass().getMethod("getInventoryManager").invoke(plugin);
            Object inventory = manager.getClass().getMethod("getVanilla").invoke(manager);
            if (inventory != null) {
                // The pinned API fills air slots only; real rings and other accessories remain intact.
                inventory.getClass().getMethod("replacePlaceholderItems", Player.class).invoke(inventory, player);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            throw new IOException("MMOInventory accessory slots could not be restored", error);
        }
    }
}
