package net.tfminecraft.rpcharacters.permadeath;

import java.util.function.Predicate;

import org.bukkit.entity.Player;

/**
 * SF battle roster check is installed from {@link SimpleFactionsRegionBridge}
 * so this class never names SimpleFactions types.
 */
public final class PermadeathBattleExemption {

	private static volatile Predicate<Player> inStartedBattle = player -> false;

	private PermadeathBattleExemption() {
	}

	public static void set(Predicate<Player> check) {
		inStartedBattle = check != null ? check : player -> false;
	}

	public static boolean isInStartedBattle(Player player) {
		return player != null && inStartedBattle.test(player);
	}
}
