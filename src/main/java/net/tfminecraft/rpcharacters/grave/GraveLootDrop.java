package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class GraveLootDrop {

	private GraveLootDrop() {
	}

	static final class TransferResult {
		private final boolean transferred;
		private final boolean overflowDropped;

		TransferResult(boolean transferred, boolean overflowDropped) {
			this.transferred = transferred;
			this.overflowDropped = overflowDropped;
		}

		boolean transferred() {
			return transferred;
		}

		boolean overflowDropped() {
			return overflowDropped;
		}
	}

	static void dropAllToWorld(Grave grave, Location dropAt) {
		if (grave == null || dropAt == null || dropAt.getWorld() == null) {
			return;
		}
		World world = dropAt.getWorld();
		for (int slot = 0; slot < Grave.TOTAL_LOGICAL_SLOTS; slot++) {
			ItemStack item = grave.getItem(slot);
			if (Grave.isBlank(item)) {
				continue;
			}
			world.dropItemNaturally(dropAt, item.clone());
			grave.setItem(slot, null);
		}
		for (ItemStack extra : grave.getExtras()) {
			if (Grave.isBlank(extra)) {
				continue;
			}
			world.dropItemNaturally(dropAt, extra.clone());
		}
		grave.clearExtras();
		int experience = grave.getExperience();
		if (experience > 0) {
			ExperienceOrb orb = (ExperienceOrb) world.spawnEntity(dropAt, EntityType.EXPERIENCE_ORB);
			orb.setExperience(experience);
			grave.setExperience(0);
		}
	}

	static TransferResult transferToPlayer(Player player, Grave grave, Location overflowAt) {
		if (player == null || grave == null) {
			return new TransferResult(false, false);
		}
		Location dropAt = overflowAt != null ? overflowAt.clone() : player.getLocation();
		World world = dropAt.getWorld() != null ? dropAt.getWorld() : player.getWorld();
		boolean transferred = false;
		boolean overflowDropped = false;
		for (int slot = 0; slot < Grave.TOTAL_LOGICAL_SLOTS; slot++) {
			ItemStack item = grave.getItem(slot);
			if (Grave.isBlank(item)) {
				continue;
			}
			Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
			for (ItemStack left : leftover.values()) {
				if (!Grave.isBlank(left) && world != null) {
					world.dropItemNaturally(dropAt, left);
					overflowDropped = true;
				}
			}
			grave.setItem(slot, null);
			transferred = true;
		}
		for (ItemStack extra : new ArrayList<>(grave.getExtras())) {
			if (Grave.isBlank(extra)) {
				continue;
			}
			Map<Integer, ItemStack> leftover = player.getInventory().addItem(extra.clone());
			for (ItemStack left : leftover.values()) {
				if (!Grave.isBlank(left) && world != null) {
					world.dropItemNaturally(dropAt, left);
					overflowDropped = true;
				}
			}
			transferred = true;
		}
		grave.clearExtras();
		int experience = grave.getExperience();
		if (experience > 0) {
			player.giveExp(experience);
			grave.setExperience(0);
			transferred = true;
		}
		return new TransferResult(transferred, overflowDropped);
	}
}
