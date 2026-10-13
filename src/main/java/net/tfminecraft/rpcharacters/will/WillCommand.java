package net.tfminecraft.rpcharacters.will;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /will}: opens the will menu for the character being played. */
public final class WillCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) WillMenu.open(player);
        else sender.sendMessage("Only a player can make a will.");
        return true;
    }
}
