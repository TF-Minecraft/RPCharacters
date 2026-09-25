package net.tfminecraft.rpcharacters.pvp;

/**
 * After {@code /pvp start}, a killing blow from someone who cannot loot the grave
 * must not put the victim's items where the rules say they cannot go back for them.
 * An unlocked grave is the exception: anyone can loot it, so the items go in.
 */
public final class PvpStartDeathPolicy {

	private PvpStartDeathPolicy() {
	}

	public static boolean keepInventory(boolean inPvpStart, boolean killedByOtherPlayer, boolean killerCanLootGrave,
			boolean unlockedGrave) {
		return inPvpStart && killedByOtherPlayer && !killerCanLootGrave && !unlockedGrave;
	}
}
