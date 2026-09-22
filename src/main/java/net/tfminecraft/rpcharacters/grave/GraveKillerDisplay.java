package net.tfminecraft.rpcharacters.grave;

import java.util.Locale;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;

public final class GraveKillerDisplay {

	private static final String PREFIX = "Killed by ";

	private GraveKillerDisplay() {
	}

	public static String build(Player victim, Player killerPlayer) {
		return formatDisplay(resolveLabel(victim, killerPlayer));
	}

	static String resolveLabel(Player victim, Player killerPlayer) {
		if (victim == null) {
			return null;
		}
		if (killerPlayer != null && !killerPlayer.getUniqueId().equals(victim.getUniqueId())) {
			return playerLabel(killerPlayer);
		}
		EntityDamageEvent last = victim.getLastDamageCause();
		if (last instanceof EntityDamageByEntityEvent byEntity) {
			String entityLabel = labelFromDamager(byEntity.getDamager(), victim);
			if (entityLabel != null && !entityLabel.isBlank()) {
				return entityLabel;
			}
		}
		return formatDamageCause(last);
	}

	private static String labelFromDamager(Entity damager, Player victim) {
		if (damager instanceof Player player && !player.getUniqueId().equals(victim.getUniqueId())) {
			return playerLabel(player);
		}
		if (damager instanceof LivingEntity living) {
			return entityLabel(living);
		}
		if (damager instanceof Projectile projectile) {
			if (projectile.getShooter() instanceof Player player
					&& !player.getUniqueId().equals(victim.getUniqueId())) {
				return playerLabel(player);
			}
			if (projectile.getShooter() instanceof LivingEntity living) {
				return entityLabel(living);
			}
		}
		return null;
	}

	private static String playerLabel(Player player) {
		if (player == null) {
			return null;
		}
		String display = DisplayIdentityService.resolveDisplay(player);
		if (display != null && !display.isBlank()) {
			return ClueFormatter.stripColor(display);
		}
		return player.getName() != null ? player.getName() : "Unknown";
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private static String entityLabel(LivingEntity entity) {
		if (entity == null) {
			return null;
		}
		String custom = entity.getCustomName();
		if (custom != null && !ClueFormatter.stripColor(custom).isBlank()) {
			return ClueFormatter.stripColor(custom);
		}
		return formatEntityType(entity.getType().name());
	}

	public static String formatDisplay(String label) {
		if (label == null || label.isBlank()) {
			return null;
		}
		String plain = ClueFormatter.stripColor(label).trim();
		if (plain.isEmpty()) {
			return null;
		}
		return PREFIX + plain;
	}

	public static String formatDamageCause(EntityDamageEvent event) {
		if (event == null) {
			return null;
		}
		return formatDamageCause(event.getCause());
	}

	public static String formatDamageCause(EntityDamageEvent.DamageCause cause) {
		if (cause == null) {
			return null;
		}
		String raw = cause.name().toLowerCase(Locale.ROOT).replace('_', ' ');
		if (raw.isEmpty()) {
			return null;
		}
		return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
	}

	public static String formatEntityType(String entityTypeName) {
		if (entityTypeName == null || entityTypeName.isBlank()) {
			return null;
		}
		String raw = entityTypeName.toLowerCase(Locale.ROOT).replace('_', ' ');
		if (raw.isEmpty()) {
			return null;
		}
		return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
	}
}
