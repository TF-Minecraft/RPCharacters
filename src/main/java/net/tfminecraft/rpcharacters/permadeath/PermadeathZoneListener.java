package net.tfminecraft.rpcharacters.permadeath;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PermadeathZoneListener implements Listener {

	private static final ConcurrentHashMap<UUID, String> currentZoneId = new ConcurrentHashMap<>();

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event) {
		if (event.getFrom().getBlockX() == event.getTo().getBlockX()
				&& event.getFrom().getBlockY() == event.getTo().getBlockY()
				&& event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
			return;
		}

		syncFromLocation(event.getPlayer(), event.getTo());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerTeleport(PlayerTeleportEvent event) {
		syncFromLocation(event.getPlayer(), event.getTo());
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		syncFromLocation(event.getPlayer(), event.getPlayer().getLocation());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerDeath(PlayerDeathEvent event) {
		PermadeathService.handleDeath(event.getEntity(), event.getEntity().getLocation());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		PermadeathService.handlePlayerRespawn(event.getPlayer(), event);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		currentZoneId.remove(event.getPlayer().getUniqueId());
		PermadeathService.clearPendingPermadeathRespawn(event.getPlayer());
	}

	public static void clearZoneTracking(Player player) {
		currentZoneId.remove(player.getUniqueId());
	}

	public static void silentZoneSync(Player player, Location location) {
		if (location == null) {
			currentZoneId.remove(player.getUniqueId());
			return;
		}
		PermadeathZoneDefinition zone = PermadeathAreaLookup.getPermadeathZoneAt(player, location);
		if (zone != null) {
			currentZoneId.put(player.getUniqueId(), zone.getRegionId().toLowerCase());
		} else {
			currentZoneId.remove(player.getUniqueId());
		}
	}

	public static void syncFromLocation(Player player, Location to) {
		if (player == null || to == null) {
			return;
		}

		UUID uuid = player.getUniqueId();
		String previousId = currentZoneId.get(uuid);
		PermadeathZoneDefinition toZone = PermadeathAreaLookup.getPermadeathZoneAt(player, to);
		String toId = toZone != null ? toZone.getRegionId().toLowerCase() : null;

		if (Objects.equals(previousId, toId)) {
			return;
		}

		if (previousId != null) {
			PermadeathZoneDefinition fromZone = PermadeathZoneLoader.getZone(previousId);
			if (fromZone != null) {
				onZoneLeave(player, fromZone);
			}
		}

		if (toId != null) {
			onZoneEnter(player, toZone);
			currentZoneId.put(uuid, toId);
		} else {
			currentZoneId.remove(uuid);
		}
	}

	public static void onZoneEnter(Player player, PermadeathZoneDefinition zone) {
		RPTexts.title(player, RPTexts.enterTitle(zone.getName()), RPTexts.ERROR + "Permadeath Zone");

		PlayerData pd = PlayerManager.get(player);
		if (pd == null || pd.hasDismissedPermadeathTutorial()) {
			return;
		}
		PermadeathTutorialMessages.send(player);
	}

	public static void onZoneLeave(Player player, PermadeathZoneDefinition zone) {
		RPTexts.title(player, RPTexts.leaveTitle(zone.getName()), " ");
	}
}
