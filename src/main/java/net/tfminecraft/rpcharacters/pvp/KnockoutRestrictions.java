package net.tfminecraft.rpcharacters.pvp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import io.lumine.mythic.lib.api.event.skill.PlayerCastSkillEvent;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * A knocked out player can talk and roleplay, but can't fight back: no hits, shots, skills
 * or spells, and no eating, drinking, item use, dropping items or building. Everything runs
 * first so other plugins never act on the attempt.
 */
public final class KnockoutRestrictions implements Listener {

	private static final long NOTICE_INTERVAL_MS = 1_000L;

	private final Predicate<Player> knockedOut;
	private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

	public KnockoutRestrictions(Predicate<Player> knockedOut) {
		this.knockedOut = knockedOut;
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onAttack(EntityDamageEvent event) {
		Player attacker = PvpKnockoutManager.attackingPlayer(event);
		if (attacker != null && !attacker.equals(event.getEntity())) {
			block(attacker, event);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onShootBow(EntityShootBowEvent event) {
		if (event.getEntity() instanceof Player player) {
			block(player, event);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onLaunch(ProjectileLaunchEvent event) {
		if (event.getEntity().getShooter() instanceof Player player) {
			block(player, event);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCastSkill(PlayerCastSkillEvent event) {
		block(event.getPlayer(), event);
	}

	// Not ignoreCancelled: Bukkit fires air clicks already cancelled.
	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getAction() == Action.PHYSICAL || !knockedOut.test(event.getPlayer())) {
			return;
		}
		event.setUseItemInHand(Event.Result.DENY);
		event.setUseInteractedBlock(Event.Result.DENY);
		notice(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onInteractEntity(PlayerInteractEntityEvent event) {
		block(event.getPlayer(), event);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onConsume(PlayerItemConsumeEvent event) {
		block(event.getPlayer(), event);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onSwapHands(PlayerSwapHandItemsEvent event) {
		block(event.getPlayer(), event);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent event) {
		block(event.getPlayer(), event);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		block(event.getPlayer(), event);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		block(event.getPlayer(), event);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastNotice.remove(event.getPlayer().getUniqueId());
	}

	private void block(Player player, Cancellable event) {
		if (knockedOut.test(player)) {
			event.setCancelled(true);
			notice(player);
		}
	}

	private void notice(Player player) {
		long now = System.currentTimeMillis();
		Long last = lastNotice.get(player.getUniqueId());
		if (last != null && now - last < NOTICE_INTERVAL_MS) {
			return;
		}
		lastNotice.put(player.getUniqueId(), now);
		RPTexts.send(player, PvpLoader.getDownedBlocked());
	}
}
