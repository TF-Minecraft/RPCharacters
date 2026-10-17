package net.tfminecraft.rpcharacters.grave;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The grave listener runs before the strike, so it marks a grave killer-only from the
 * strike it expects to kill. The strike then settles it: if the kill did not happen (a
 * cancelled permakill), the grave goes back to an ordinary locked grave its owner can open.
 */
public final class KillerOnlyGraves {

	private static final Map<UUID, UUID> awaitingStrike = new ConcurrentHashMap<>();

	private KillerOnlyGraves() {
	}

	static void remember(UUID victimId, UUID graveId) {
		awaitingStrike.put(victimId, graveId);
	}

	/** Called once the strike for this death has landed. */
	public static void settle(UUID victimId, boolean characterDied) {
		UUID graveId = awaitingStrike.remove(victimId);
		if (graveId == null || characterDied) {
			return;
		}
		Grave grave = GraveManager.get().getById(graveId);
		if (grave == null) {
			return;
		}
		grave.setKillerOnly(false);
		grave.flush();
	}

	/** A respawn ends the death; a strike that never came leaves the grave as it is. */
	static void forget(UUID victimId) {
		awaitingStrike.remove(victimId);
	}
}
