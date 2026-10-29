package net.tfminecraft.rpcharacters.permadeath;

import java.util.Objects;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
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
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PermadeathZoneListener implements Listener {

	private static final ConcurrentHashMap<UUID, String> currentZoneId = new ConcurrentHashMap<>();
	/** Zones a player just stepped out of whose "Now leaving" title has not shown yet. */
	private static final ConcurrentHashMap<UUID, PendingLeave> pendingLeaves = new ConcurrentHashMap<>();

	private record PendingLeave(String zoneId, BukkitTask task) {
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event) {
		if (event.getTo() == null) return;
		if (Objects.equals(event.getFrom().getWorld(), event.getTo().getWorld())
				&& event.getFrom().getBlockX() == event.getTo().getBlockX()
				&& event.getFrom().getBlockY() == event.getTo().getBlockY()
				&& event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
			return;
		}

		syncFromLocation(event.getPlayer(), event.getTo());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerTeleport(PlayerTeleportEvent event) {
		// A teleport is a deliberate jump (a character switch, a warp), not edge walking: settle any
		// waiting leave and tell the player where they land at once.
		showPendingLeaveNow(event.getPlayer());
		syncFromLocation(event.getPlayer(), event.getTo(), false);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		syncFromLocation(event.getPlayer(), event.getPlayer().getLocation());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerDeath(PlayerDeathEvent event) {
		showPendingLeaveNow(event.getEntity());
		PermadeathService.handleDeath(event.getEntity(), event.getEntity().getLocation());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		PermadeathService.handlePlayerRespawn(event.getPlayer(), event);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		clearZoneTracking(event.getPlayer());
		PermadeathService.clearPendingPermadeathRespawn(event.getPlayer());
	}

	public static void clearZoneTracking(Player player) {
		currentZoneId.remove(player.getUniqueId());
		cancelPendingLeave(player.getUniqueId());
	}

	public static void silentZoneSync(Player player, Location location) {
		cancelPendingLeave(player.getUniqueId());
		if (location == null) {
			currentZoneId.remove(player.getUniqueId());
			return;
		}
		PermadeathZoneDefinition zone = PermadeathAreaLookup.getPermadeathZoneAt(player, location);
		if (zone != null) {
			currentZoneId.put(player.getUniqueId(), zone.getRegionId().toLowerCase(Locale.ROOT));
		} else {
			currentZoneId.remove(player.getUniqueId());
		}
	}

	public static void syncFromLocation(Player player, Location to) {
		syncFromLocation(player, to, true);
	}

	private static void syncFromLocation(Player player, Location to, boolean delayLeave) {
		if (player == null || to == null) {
			return;
		}

		UUID uuid = player.getUniqueId();
		String previousId = currentZoneId.get(uuid);
		PermadeathZoneDefinition toZone = PermadeathAreaLookup.getPermadeathZoneAt(player, to);
		String toId = toZone != null ? toZone.getRegionId().toLowerCase(Locale.ROOT) : null;

		if (Objects.equals(previousId, toId)) {
			return;
		}

		if (previousId != null) {
			PermadeathZoneDefinition fromZone = PermadeathZoneLoader.getZone(previousId);
			if (fromZone != null) {
				if (toId == null && delayLeave) {
					scheduleLeave(player, previousId, fromZone);
				} else {
					onZoneLeave(player, fromZone);
				}
			}
		}

		if (toId != null) {
			PendingLeave pending = cancelPendingLeave(uuid);
			// Back in the zone before its leave title showed: the player never saw it end.
			if (pending == null || !pending.zoneId().equals(toId)) {
				onZoneEnter(player, toZone);
			}
			currentZoneId.put(uuid, toId);
		} else {
			currentZoneId.remove(uuid);
		}
	}

	/**
	 * Holds the leave title for {@code leave-title-delay-ms}, so a player walking along a zone's
	 * edge doesn't get a title every few steps. Entering any zone always warns at once, unless the
	 * player returns to the zone whose leave title is still waiting.
	 */
	private static void scheduleLeave(Player player, String zoneId, PermadeathZoneDefinition zone) {
		UUID uuid = player.getUniqueId();
		cancelPendingLeave(uuid);
		long delayTicks = Math.ceilDiv(PermadeathZoneLoader.getLeaveTitleDelayMs(), 50);
		if (delayTicks <= 0 || !RPCharacters.plugin.isEnabled()) {
			onZoneLeave(player, zone);
			return;
		}
		// Whatever replaces or clears this entry cancels the task first, so a run is always current.
		BukkitTask task = Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			pendingLeaves.remove(uuid);
			showLeaveIfZoneExists(player, zoneId);
		}, delayTicks);
		pendingLeaves.put(uuid, new PendingLeave(zoneId, task));
	}

	/** Death or a teleport just after leaving shows the waiting title at once, e.g. so a death is clearly outside. */
	private static void showPendingLeaveNow(Player player) {
		PendingLeave pending = cancelPendingLeave(player.getUniqueId());
		if (pending != null) {
			showLeaveIfZoneExists(player, pending.zoneId());
		}
	}

	private static void showLeaveIfZoneExists(Player player, String zoneId) {
		// A zones.yml reload may have removed the zone meanwhile.
		PermadeathZoneDefinition zone = PermadeathZoneLoader.getZone(zoneId);
		if (zone != null && player.isOnline()) {
			onZoneLeave(player, zone);
		}
	}

	private static PendingLeave cancelPendingLeave(UUID uuid) {
		PendingLeave pending = pendingLeaves.remove(uuid);
		if (pending != null) {
			pending.task().cancel();
		}
		return pending;
	}

	public static void onZoneEnter(Player player, PermadeathZoneDefinition zone) {
		RPTexts.title(player, RPTexts.enterTitle(zone.getName()), RPTexts.ERROR + "Permadeath Zone");

		TutorialService.show(player, TutorialService.PERMADEATH_ZONE);
	}

	public static void onZoneLeave(Player player, PermadeathZoneDefinition zone) {
		RPTexts.title(player, RPTexts.leaveTitle(zone.getName()), " ");
	}
}
