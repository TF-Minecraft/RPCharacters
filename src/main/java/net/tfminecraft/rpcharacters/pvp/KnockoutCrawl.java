package net.tfminecraft.rpcharacters.pvp;

import java.util.function.Predicate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.api.event.PrePlayerStopCrawlEvent;
import dev.geco.gsit.model.Crawl;
import dev.geco.gsit.model.StopReason;

/** Loaded only when GSit is enabled. Uses the crawl API shared by GSit 3.2 and 3.3. */
final class KnockoutCrawl implements Listener {

	private final Predicate<Player> knockedOut;
	private final Map<UUID, Crawl> ownedCrawls = new HashMap<>();

	KnockoutCrawl(Predicate<Player> knockedOut) {
		this.knockedOut = knockedOut;
	}

	void enforce(Player player) {
		// The command checks permissions/ground state and toggles an existing crawl off.
		// The API starts the pose directly and also handles the player's client-side crawl.
		if (!GSitAPI.isPlayerCrawling(player)) {
			Crawl crawl = GSitAPI.startCrawl(player);
			if (crawl != null) {
				ownedCrawls.put(player.getUniqueId(), crawl);
			}
		}
	}

	void release(Player player) {
		Crawl crawl = ownedCrawls.remove(player.getUniqueId());
		if (crawl != null && GSitAPI.getCrawlByPlayer(player) == crawl) {
			GSitAPI.stopCrawl(crawl, StopReason.PLUGIN);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onStopCrawl(PrePlayerStopCrawlEvent event) {
		if (event.getReason().isCancellable() && knockedOut.test(event.getPlayer())) {
			event.setCancelled(true);
		}
	}
}
