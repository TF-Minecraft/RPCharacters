package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PermadeathBattleExemptionTest {

	@AfterEach
	void resetExemption() {
		PermadeathBattleExemption.set(null);
	}

	@Test
	void defaultIsNotInStartedBattle() {
		assertFalse(PermadeathBattleExemption.isInStartedBattle(stubPlayer()));
		assertFalse(PermadeathBattleExemption.isInStartedBattle(null));
	}

	@Test
	void installedPredicateCanExempt() {
		PermadeathBattleExemption.set(player -> true);
		assertTrue(PermadeathBattleExemption.isInStartedBattle(stubPlayer()));
		assertFalse(PermadeathBattleExemption.isInStartedBattle(null));
	}

	@Test
	void nullPredicateResetsToFalse() {
		PermadeathBattleExemption.set(player -> true);
		PermadeathBattleExemption.set(null);
		assertFalse(PermadeathBattleExemption.isInStartedBattle(stubPlayer()));
	}

	private static Player stubPlayer() {
		return (Player) Proxy.newProxyInstance(
				Player.class.getClassLoader(),
				new Class<?>[] { Player.class },
				(proxy, method, args) -> {
					Class<?> type = method.getReturnType();
					if (type == boolean.class) {
						return false;
					}
					if (type == int.class) {
						return 0;
					}
					if (type == long.class) {
						return 0L;
					}
					if (type == float.class) {
						return 0f;
					}
					if (type == double.class) {
						return 0d;
					}
					if (type == byte.class) {
						return (byte) 0;
					}
					if (type == short.class) {
						return (short) 0;
					}
					if (type == char.class) {
						return '\0';
					}
					if (method.getName().equals("equals")) {
						return proxy == args[0];
					}
					if (method.getName().equals("hashCode")) {
						return System.identityHashCode(proxy);
					}
					if (method.getName().equals("toString")) {
						return "stub-player";
					}
					return null;
				});
	}
}
