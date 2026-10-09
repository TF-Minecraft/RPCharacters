package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

import net.Indyuce.mmocore.api.player.PlayerData;

/**
 * What a duellist had when the duel started. Ending the duel puts back what it used up
 * without ever leaving the player better off than if they had not duelled.
 */
final class DuelSnapshot {

	private final long takenAtMs;
	private final int food;
	private final float saturation;
	private final float exhaustion;
	private final int fireTicks;
	private final Map<PotionEffectType, PotionEffect> effects = new HashMap<>();
	private final boolean resources;
	private final double mana;
	private final double stamina;

	private DuelSnapshot(Player player, long nowMs) {
		takenAtMs = nowMs;
		food = player.getFoodLevel();
		saturation = player.getSaturation();
		exhaustion = player.getExhaustion();
		fireTicks = player.getFireTicks();
		for (PotionEffect effect : player.getActivePotionEffects()) {
			effects.put(effect.getType(), effect);
		}
		resources = PlayerData.has(player.getUniqueId());
		PlayerData data = resources ? PlayerData.get(player.getUniqueId()) : null;
		mana = data == null ? 0 : data.getMana();
		stamina = data == null ? 0 : data.getStamina();
	}

	static DuelSnapshot take(Player player, long nowMs) {
		return new DuelSnapshot(player, nowMs);
	}

	/**
	 * Hunger, mana and stamina are topped back up to where they started. Debuffs from the
	 * duel come off, but ones the player already had stay with the time they had left, so a
	 * duel can't cleanse anything. Buffs the duel took away come back for their remaining time.
	 */
	void restore(Player player, long nowMs) {
		if (player.getFoodLevel() < food) {
			player.setFoodLevel(food);
		}
		player.setSaturation(Math.min(Math.max(player.getSaturation(), saturation), player.getFoodLevel()));
		player.setExhaustion(Math.min(player.getExhaustion(), exhaustion));
		int elapsedTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, nowMs - takenAtMs) / 50L);
		player.setFireTicks(Math.min(player.getFireTicks(), Math.max(0, fireTicks - elapsedTicks)));
		restoreEffects(player, elapsedTicks);
		if (resources && PlayerData.has(player.getUniqueId())) {
			PlayerData data = PlayerData.get(player.getUniqueId());
			if (data.getMana() < mana) {
				data.setMana(mana);
			}
			if (data.getStamina() < stamina) {
				data.setStamina(stamina);
			}
		}
	}

	private void restoreEffects(Player player, int elapsedTicks) {
		for (PotionEffect current : new ArrayList<>(player.getActivePotionEffects())) {
			if (harmful(current.getType())) {
				player.removePotionEffect(current.getType());
			}
		}
		List<PotionEffect> back = new ArrayList<>();
		for (PotionEffect before : effects.values()) {
			if (!harmful(before.getType()) && player.hasPotionEffect(before.getType())) {
				continue;
			}
			int left = before.isInfinite() ? PotionEffect.INFINITE_DURATION : before.getDuration() - elapsedTicks;
			if (left == PotionEffect.INFINITE_DURATION || left > 0) {
				back.add(new PotionEffect(before.getType(), left, before.getAmplifier(),
						before.isAmbient(), before.hasParticles(), before.hasIcon()));
			}
		}
		player.addPotionEffects(back);
	}

	private static boolean harmful(PotionEffectType type) {
		return type.getCategory() == PotionEffectTypeCategory.HARMFUL;
	}
}
