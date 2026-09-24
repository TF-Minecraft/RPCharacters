package net.tfminecraft.rpcharacters.focus;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.PlayerData;

final class FocusAttributes {

    private FocusAttributes() {}

    static double getTotal(Player player, String mmocoreId) {
        if (player == null || mmocoreId == null || mmocoreId.isBlank()) {
            return 0;
        }
        if (player.getServer().getPluginManager().getPlugin("MMOCore") == null) {
            return 0;
        }
        try {
            return PlayerData.get(player).getAttributes().getInstance(mmocoreId).getTotal();
        } catch (Exception ex) {
            return 0;
        }
    }
}
