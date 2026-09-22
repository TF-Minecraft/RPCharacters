package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.tfminecraft.simplefactions.events.PlayerEnterRegionEvent;
import net.tfminecraft.simplefactions.events.PlayerLeaveRegionEvent;

/**
 * Loaded only when SimpleFactions is enabled so this class is never defined
 * without SF on the classpath at runtime.
 */
public final class SimpleFactionsPermadeathListener implements Listener {

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEnterRegion(PlayerEnterRegionEvent event) {
		PermadeathZoneListener.syncFromLocation(event.getPlayer(), event.getPlayer().getLocation());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onLeaveRegion(PlayerLeaveRegionEvent event) {
		PermadeathZoneListener.syncFromLocation(event.getPlayer(), event.getPlayer().getLocation());
	}
}
