package net.tfminecraft.rpcharacters.pvp;

/**
 * After {@code /pvp start}, a killing blow from someone who cannot loot the grave
 * must not put the victim's items where the rules say they cannot go back for them.
 */
public final class PvpStartDeathPolicy {

	private PvpStartDeathPolicy() {
	}

	public static boolean keepInventory(boolean inPvpStart, boolean killedByOtherPlayer, boolean killerCanLootGrave) {
		return inPvpStart && killedByOtherPlayer && !killerCanLootGrave;
	}
}
