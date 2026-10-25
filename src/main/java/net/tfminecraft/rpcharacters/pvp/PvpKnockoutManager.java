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
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
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
		if (player.getHealth() - event.getFinalDamage() > 0) {
			return;
		}
		// A duel's own blow only ends the duel.
		if (Duels.settleLethalBlow(event, player)) {
			return;
		}
		if (shouldSkipKnockout(player)) {
			return;
		}
		if (isKnockedOut(player) && downedPlayerSurvives(event)) {
			event.setCancelled(true);
			return;
		}
		if (!usesNonlethalMode(event, player)) {
			return;
		}

		event.setCancelled(true);
		Player attacker = attackingPlayer(event);
		CoreProtectKnockouts.log(attacker, player);
		boolean deciding = PvpStrikeService.handleKnockout(player, attacker);
		applyKnockout(player, deciding);
	}

	/**
	 * Nothing hurts a downed player, so no one can finish them off while the winner decides.
	 * Runs first so other plugins never see the hit. SimpleFactions battles stay lethal.
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onDownedDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player && isKnockedOut(player) && downedPlayerSurvives(event)
				&& !PermadeathBattleExemption.isInStartedBattle(player)) {
			event.setCancelled(true);
		}
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
		return isNonlethal(attacker);
	}

	/** Whether this player's active character is in nonlethal PvP. Players without one are lethal. */
	static boolean isNonlethal(Player attacker) {
		PlayerData pd = PlayerManager.get(attacker);
		if (pd == null || !pd.hasActiveCharacter()) {
			return false;
		}
		return !pd.getActiveCharacter().isPvpLethal();
	}

	/** A downed player can't be hurt, except by /kill, the void and the world border. */
	static boolean downedPlayerSurvives(EntityDamageEvent event) {
		return switch (event.getCause()) {
			case KILL, VOID, WORLD_BORDER -> false;
			default -> true;
		};
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
		Knockout knockout = knockouts.remove(player.getUniqueId());
		if (knockout == null) {
			return;
		}
		// Each freeze teleport keeps the fall distance, so it would kill them on landing.
		player.setFallDistance(0f);
		// A spare or strike can come before the blindness wears off.
		if (System.currentTimeMillis() < knockout.blindUntilMs) {
			player.removePotionEffect(PotionEffectType.BLINDNESS);
		}
		if (crawl != null) {
			crawl.release(player);
		}
	}

	public boolean isDown(Player player) {
		return isKnockedOut(player);
	}

	/**
	 * Moves a knocked-out player and keeps them down at the new spot, for plugins that drag or
	 * carry the body. Returns false when the player is not knocked out.
	 */
	public boolean moveDowned(Player player, Location to) {
		if (to == null || !isKnockedOut(player)) {
			return false;
		}
		Knockout knockout = knockouts.get(player.getUniqueId());
		knockout.location = to.clone();
		enforceFreeze(player, knockout.location);
		return true;
	}

	private boolean isKnockedOut(Player player) {
		Knockout knockout = knockouts.get(player.getUniqueId());
		return knockout != null && !knockout.isOver(player.getUniqueId(), System.currentTimeMillis())
				&& !shouldSkipTick(player);
	}

	/**
	 * Knocks the player out. While the winner decides ({@code deciding}) they stay down until
	 * the choice is made or the decision time runs out; otherwise for knockout-seconds.
	 */
	private void applyKnockout(Player player, boolean deciding) {
		double maxHealth = 20.0;
		var maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
		if (maxAttr != null) {
			maxHealth = maxAttr.getValue();
		}
		player.setHealth(Math.max(0.1, Math.min(1.0, maxHealth)));

		long seconds = deciding ? PvpLoader.getDecisionSeconds() : PvpLoader.getKnockoutSeconds();
		long blindSeconds = Math.min(seconds, PvpLoader.getKnockoutBlindnessSeconds());
		if (blindSeconds > 0) {
			player.addPotionEffect(new PotionEffect(
					PotionEffectType.BLINDNESS,
					(int) Math.min(Integer.MAX_VALUE, blindSeconds * 20L),
					PvpLoader.getBlindnessAmplifier(),
					false,
					false,
					true));
		}

		long now = System.currentTimeMillis();
		knockouts.put(player.getUniqueId(), new Knockout(
				player.getLocation().clone(),
				now + seconds * 1000L,
				deciding,
				now + blindSeconds * 1000L));
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
			if (!player.isOnline() || entry.getValue().isOver(player.getUniqueId(), now) || shouldSkipTick(player)) {
				releaseKnockout(player);
				continue;
			}
			enforceFreeze(player, entry.getValue().location);
			player.setFallDistance(0f);
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
		// SimpleFactions counts battle lives and routes respawns from the death, so battles stay lethal.
		if (PermadeathBattleExemption.isInStartedBattle(player)) {
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
		private Location location;
		private final long untilMs;
		/** Held down by a winner's decision rather than for a fixed time. */
		private final boolean deciding;
		private final long blindUntilMs;

		private Knockout(Location location, long untilMs, boolean deciding, long blindUntilMs) {
			this.location = location;
			this.untilMs = untilMs;
			this.deciding = deciding;
			this.blindUntilMs = blindUntilMs;
		}

		private boolean isOver(UUID playerId, long nowMs) {
			return nowMs >= untilMs || deciding && !PvpStrikeService.isAwaitingDecision(playerId);
		}
	}
}
