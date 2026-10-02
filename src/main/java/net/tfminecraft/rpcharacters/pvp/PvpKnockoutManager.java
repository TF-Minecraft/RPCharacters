package net.tfminecraft.rpcharacters.pvp;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;

public final class PvpKnockoutManager implements Listener {

	private final Map<UUID, Knockout> knockouts = new ConcurrentHashMap<>();
	private BukkitTask tickTask;
	private KnockoutCrawl crawl;

	public void start() {
		if (crawl == null && Bukkit.getPluginManager().isPluginEnabled("GSit")) {
			crawl = new KnockoutCrawl(this::isKnockedOut);
			Bukkit.getPluginManager().registerEvents(crawl, RPCharacters.plugin);
		} else if (crawl == null) {
			RPCharacters.plugin.getLogger().warning("GSit is not enabled; knockout freeze and blindness will work, but the downed crawl pose is unavailable.");
		}
		if (tickTask != null) {
			tickTask.cancel();
		}
		tickTask = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(RPCharacters.plugin, 0L, PvpLoader.getFreezePeriodTicks());
	}

	public void shutdown() {
		if (tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
		for (UUID id : knockouts.keySet()) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				releaseKnockout(player);
			}
		}
		knockouts.clear();
		if (crawl != null) {
			HandlerList.unregisterAll(crawl);
			crawl = null;
		}
	}

	/**
	 * Spigot {@code PlayerDeathEvent} is not cancellable on this API. Cancel the
	 * killing blow at HIGHEST instead so death (and permadeath MONITOR) never runs.
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onLethalDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player player)) {
			return;
		}
		if (shouldSkipKnockout(player)) {
			return;
		}
		if (player.getHealth() - event.getFinalDamage() > 0) {
			return;
		}
		if (!usesNonlethalMode(event, player)) {
			return;
		}

		event.setCancelled(true);
		applyKnockout(player);
		PvpStrikeService.handleKnockout(player, attackingPlayer(event));
	}

	/**
	 * Nonlethal applies only when another player lands the killing blow.
	 * The victim's mode never matters, and lava, mobs, and other non-player
	 * damage always stay lethal.
	 */
	static boolean usesNonlethalMode(EntityDamageEvent event, Player player) {
		Player attacker = attackingPlayer(event);
		if (attacker == null || attacker.getUniqueId().equals(player.getUniqueId())) {
			return false;
		}
		PlayerData pd = PlayerManager.get(attacker);
		if (pd == null || !pd.hasActiveCharacter()) {
			return false;
		}
		return !pd.getActiveCharacter().isPvpLethal();
	}

	static Player attackingPlayer(EntityDamageEvent event) {
		if (event.getDamageSource() != null
				&& event.getDamageSource().getCausingEntity() instanceof Player player) {
			return player;
		}
		if (event instanceof EntityDamageByEntityEvent byEntity) {
			if (byEntity.getDamager() instanceof Player player) {
				return player;
			}
			if (byEntity.getDamager() instanceof Projectile projectile
					&& projectile.getShooter() instanceof Player player) {
				return player;
			}
		}
		return null;
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		releaseKnockout(event.getPlayer());
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		releaseKnockout(event.getEntity());
	}

	private void releaseKnockout(Player player) {
		if (knockouts.remove(player.getUniqueId()) != null && crawl != null) {
			crawl.release(player);
		}
	}

	private boolean isKnockedOut(Player player) {
		Knockout knockout = knockouts.get(player.getUniqueId());
		return knockout != null && System.currentTimeMillis() < knockout.untilMs && !shouldSkipTick(player);
	}

	private void applyKnockout(Player player) {
		double maxHealth = 20.0;
		var maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
		if (maxAttr != null) {
			maxHealth = maxAttr.getValue();
		}
		player.setHealth(Math.max(0.1, Math.min(1.0, maxHealth)));

		int durationTicks = (int) Math.min(Integer.MAX_VALUE, PvpLoader.getKnockoutSeconds() * 20L);
		player.addPotionEffect(new PotionEffect(
				PotionEffectType.BLINDNESS,
				durationTicks,
				PvpLoader.getBlindnessAmplifier(),
				false,
				false,
				true));

		knockouts.put(player.getUniqueId(), new Knockout(
				player.getLocation().clone(),
				System.currentTimeMillis() + PvpLoader.getKnockoutSeconds() * 1000L));
		if (crawl != null) {
			crawl.enforce(player);
		}
	}

	private void tick() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, Knockout>> it = knockouts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Knockout> entry = it.next();
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			if (!player.isOnline() || now >= entry.getValue().untilMs || shouldSkipTick(player)) {
				releaseKnockout(player);
				continue;
			}
			enforceFreeze(player, entry.getValue().location);
			if (crawl != null) {
				crawl.enforce(player);
			}
		}
	}

	private boolean shouldSkipKnockout(Player player) {
		if (PermadeathService.isAwaitingPermakillRespawn(player)) {
			return true;
		}
		if (CreationManager.activeCreators.containsKey(player)) {
			return true;
		}
		return false;
	}

	private boolean shouldSkipTick(Player player) {
		if (player.isDead() || PermadeathService.isAwaitingPermakillRespawn(player)) {
			return true;
		}
		if (CreationManager.activeCreators.containsKey(player)) {
			return true;
		}
		if (!player.getGameMode().equals(GameMode.SURVIVAL)) {
			return true;
		}
		return false;
	}

	private void enforceFreeze(Player player, Location freezeAt) {
		Location here = player.getLocation();
		if (here.getX() == freezeAt.getX()
				&& here.getY() == freezeAt.getY()
				&& here.getZ() == freezeAt.getZ()) {
			return;
		}
		Location dest = freezeAt.clone();
		dest.setYaw(here.getYaw());
		dest.setPitch(here.getPitch());
		player.teleport(dest);
	}

	private static final class Knockout {
		private final Location location;
		private final long untilMs;

		private Knockout(Location location, long untilMs) {
			this.location = location;
			this.untilMs = untilMs;
		}
	}
}
