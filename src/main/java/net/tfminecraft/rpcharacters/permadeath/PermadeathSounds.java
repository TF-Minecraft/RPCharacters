package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

public final class PermadeathSounds {

	private static final String PERMAKILL_MOOD = "ambient.soul_sand_valley.mood";
	private static final String END_PORTAL_OPEN = "block.end_portal.spawn";

	private static final float MASTER_VOLUME = 10.0f;

	private PermadeathSounds() {
	}

	/** Entity-attached MASTER sounds (same pattern as SimpleFactions war horn). */
	public static void playPermakill(Player player) {
		player.playSound(player, PERMAKILL_MOOD, SoundCategory.MASTER, MASTER_VOLUME, 1.0f);
		player.playSound(player, END_PORTAL_OPEN, SoundCategory.MASTER, MASTER_VOLUME, 1.0f);
	}
}
