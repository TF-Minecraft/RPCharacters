package net.tfminecraft.rpcharacters.body;

import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Restores empty vanilla accessory slots using MMOInventory's own configured placeholders. */
final class MmoInventorySlots {
    private MmoInventorySlots() {
    }

    /** MMOInventory's vanilla-inventory accessory slots, for staff body edits; none when it is not running. */
    static final BodyEditor.Accessories ACCESSORIES = new BodyEditor.Accessories() {
        @Override
        public boolean slot(int inventorySlot) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("MMOInventory");
            if (plugin == null || !plugin.isEnabled()) return false;
            try {
                Object manager = plugin.getClass().getMethod("getInventoryManager").invoke(plugin);
                Object inventory = manager.getClass().getMethod("getVanilla").invoke(manager);
                return inventory != null && inventory.getClass().getMethod("getSlot", int.class).invoke(inventory, inventorySlot) != null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                // Unknown layout: treat every slot as an accessory slot, so edits are refused rather than misplaced.
                return true;
            }
        }

        /** Placeholders are plain items; every real accessory is an MMOItems item. */
        @Override
        public boolean placeholder(byte[] item) {
            return BodyAdminCommand.mmoId(ItemStack.deserializeBytes(item)) == null;
        }
    };

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
