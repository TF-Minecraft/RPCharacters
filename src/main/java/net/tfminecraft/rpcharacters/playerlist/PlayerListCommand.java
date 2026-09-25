package net.tfminecraft.rpcharacters.playerlist;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** {@code /players} and the Quick Actions menu button both open the player list. */
public final class PlayerListCommand implements CommandExecutor, Listener {

	public static final String COMMAND = "players";

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, RPTexts.ERROR + "Only players can open the player list.");
			return true;
		}
		open(player);
		return true;
	}

	@EventHandler
	public void onCustomClick(PlayerCustomClickEvent event) {
		boolean playtime = PlaytimeDialogs.OPEN_ACTION.equals(event.getIdentifier());
		if ((!PlayerListDialogs.OPEN_ACTION.equals(event.getIdentifier()) && !playtime)
				|| !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
			return;
		}
		Player player = connection.getPlayer();
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			if (player.isOnline()) {
				if (playtime) {
					PlaytimeDialogs.open(player);
				} else {
					open(player);
				}
			}
		});
	}

	private static void open(Player player) {
		if (!player.hasPermission(Cache.playerList.permission())) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to view the player list.");
			return;
		}
		PlayerListDialogs.openList(player);
	}
}
