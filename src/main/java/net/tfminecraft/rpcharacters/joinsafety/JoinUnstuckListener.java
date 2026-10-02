package net.tfminecraft.rpcharacters.joinsafety;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.util.BoundingBox;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Moves players out of blocks when they log in. Someone may have built where
 * they logged out, and they would suffocate while the resource pack loads.
 */
public final class JoinUnstuckListener implements Listener {

	private static final String MOVED_MESSAGE =
			"\u00A7eYou logged in inside a block, so you were moved to a safe spot nearby.";

	private static final Set<Material> HAZARDS = EnumSet.of(
			Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
			Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS, Material.SWEET_BERRY_BUSH,
			Material.POWDER_SNOW, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE);

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		// A teleport inside the join event is lost when the client's first position syncs.
		// Scheduled tasks run before players tick, so this still lands before suffocation damage.
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> unstick(player));
	}

	private static void unstick(Player player) {
		if (!player.isOnline()) {
			return;
		}
		GameMode mode = player.getGameMode();
		if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.isInsideVehicle()) {
			return;
		}
		// Vanilla drops a player who still fits crawling into the swimming pose, so leave them be.
		if (!isInWall(player) || !crawlSpaceBlocked(player)) {
			return;
		}
		Location from = player.getLocation();
		Location target = findSafeSpot(from);
		if (target == null) {
			return;
		}
		if (!player.teleport(target)) {
			return;
		}
		// PlayerManager froze no-character players in the wall on join; re-capture it here.
		RPCharacters.getPlayerManager().releaseFreeze(player);
		RPCharacters.getPlayerManager().reevaluateFreeze(player);
		player.sendMessage(MOVED_MESSAGE);
		RPCharacters.plugin.getLogger().info("Moved " + player.getName() + " out of blocks on join: "
				+ describe(from) + " -> " + describe(target));
	}

	/** Same test vanilla uses for suffocation damage: a thin box at eye level. */
	static boolean isInWall(Player player) {
		Location eye = player.getEyeLocation();
		double half = player.getWidth() * 0.4;
		BoundingBox box = new BoundingBox(
				eye.getX() - half, eye.getY() - 1.0E-6, eye.getZ() - half,
				eye.getX() + half, eye.getY() + 1.0E-6, eye.getZ() + half);
		return collides(player.getWorld(), box, true);
	}

	/** True when blocks fill the space a crawling (swimming pose) player would need. */
	static boolean crawlSpaceBlocked(Player player) {
		Location feet = player.getLocation();
		double half = player.getWidth() / 2.0 - 1.0E-6;
		BoundingBox box = new BoundingBox(
				feet.getX() - half, feet.getY() + 1.0E-6, feet.getZ() - half,
				feet.getX() + half, feet.getY() + 0.6, feet.getZ() + half);
		return collides(player.getWorld(), box, false);
	}

	private static boolean collides(World world, BoundingBox box, boolean suffocatingOnly) {
		for (int x = floor(box.getMinX()); x <= floor(box.getMaxX()); x++) {
			for (int y = floor(box.getMinY()); y <= floor(box.getMaxY()); y++) {
				for (int z = floor(box.getMinZ()); z <= floor(box.getMaxZ()); z++) {
					Block block = world.getBlockAt(x, y, z);
					if (suffocatingOnly && !block.isSuffocating()) {
						continue;
					}
					if (block.getCollisionShape().overlaps(box.clone().shift(-x, -y, -z))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static Location findSafeSpot(Location from) {
		World world = from.getWorld();
		int[] spot = SafeSpotSearch.find(from.getBlockX(), from.getBlockY(), from.getBlockZ(),
				(x, y, z) -> canStand(world, x, y, z));
		if (spot == null) {
			Block top = world.getHighestBlockAt(from.getBlockX(), from.getBlockZ());
			if (!canStand(world, top.getX(), top.getY() + 1, top.getZ())) {
				return null;
			}
			spot = new int[] { top.getX(), top.getY() + 1, top.getZ() };
		}
		Location target = new Location(world, spot[0] + 0.5, spot[1], spot[2] + 0.5);
		target.setYaw(from.getYaw());
		target.setPitch(from.getPitch());
		return target;
	}

	private static boolean canStand(World world, int x, int y, int z) {
		if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
			return false;
		}
		Block floor = world.getBlockAt(x, y - 1, z);
		if (!floor.getType().isSolid() || HAZARDS.contains(floor.getType())) {
			return false;
		}
		return isOpen(world.getBlockAt(x, y, z)) && isOpen(world.getBlockAt(x, y + 1, z));
	}

	private static boolean isOpen(Block block) {
		return block.isPassable() && !block.isLiquid() && !HAZARDS.contains(block.getType());
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}

	private static String describe(Location loc) {
		return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
	}
}
