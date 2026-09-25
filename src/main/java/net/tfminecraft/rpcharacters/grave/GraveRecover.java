package net.tfminecraft.rpcharacters.grave;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

final class GraveRecover {

	private GraveRecover() {}

	/**
	 * Transfers all grave loot and XP to the player. Overflow drops at {@code overflowAt}.
	 *
	 * @return {@code true} when items or XP were transferred (insurance may consume a charge)
	 */
	static boolean recover(Player player, Grave grave, Location overflowAt) {
		if (grave.isEmpty()) {
			send(player, GraveLoader.getMessageEmpty());
			GraveManager.get().despawn(grave);
			return false;
		}
		Location dropAt = overflowAt != null ? overflowAt.clone() : graveOverflowLocation(grave, player);
		GraveLootDrop.TransferResult result = GraveLootDrop.transferToPlayer(player, grave, dropAt);
		send(player, grave.isOwner(player.getUniqueId())
				? GraveLoader.getMessageRecovered()
				: GraveLoader.getMessageLooted());
		if (result.overflowDropped()) {
			send(player, GraveLoader.getMessageInventoryFull());
		}
		GraveManager.get().despawn(grave);
		return result.transferred();
	}

	static Location graveOverflowLocation(Grave grave) {
		Location dropAt = grave.getBlockLocation();
		if (dropAt == null) {
			return null;
		}
		return dropAt.clone().add(0.5, 0.5, 0.5);
	}

	static Location graveOverflowLocation(Grave grave, Player player) {
		Location dropAt = graveOverflowLocation(grave);
		if (dropAt != null) {
			return dropAt;
		}
		return player != null ? player.getLocation() : null;
	}

	static void sendMessage(Player player, String message) {
		send(player, message);
	}

	private static void send(Player player, String message) {
		if (player == null || message == null || message.isBlank()) {
			return;
		}
		player.sendMessage(StringFormatter.formatHex(message.replace('&', '\u00A7')));
	}
}
