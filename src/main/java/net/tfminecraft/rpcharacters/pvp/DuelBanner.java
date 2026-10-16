package net.tfminecraft.rpcharacters.pvp;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.tfminecraft.rpcharacters.RPCharacters;

/** A temporary display, so the duel's flag never changes blocks or catches a hit. */
final class DuelBanner {

	static final String TAG = "rpcharacters_duel_banner";
	private final Location anchor;
	private final ItemDisplay display;
	private final BukkitTask planting;
	private boolean removed;

	private DuelBanner(Location anchor, ItemDisplay display, BukkitTask planting) {
		this.anchor = anchor;
		this.display = display;
		this.planting = planting;
	}

	static DuelBanner plant(Player first, Player second) {
		Location a = first.getLocation();
		Location b = second.getLocation();
		Location at = new Location(a.getWorld(), (a.getX() + b.getX()) / 2,
				Math.min(a.getY(), b.getY()), (a.getZ() + b.getZ()) / 2);
		Vector facing = a.toVector().subtract(at.toVector()).setY(0);
		if (facing.lengthSquared() > 0) {
			at.setDirection(facing);
		}
		var ground = at.getWorld().rayTraceBlocks(at.clone().add(0, 2, 0), new Vector(0, -1, 0),
				6, FluidCollisionMode.NEVER, true);
		if (ground != null) {
			at.setY(ground.getHitPosition().getY());
		}
		ItemStack item = new ItemStack(Material.BLUE_BANNER);
		BannerMeta meta = (BannerMeta) item.getItemMeta();
		meta.addPattern(new Pattern(DyeColor.YELLOW, PatternType.CROSS));
		meta.addPattern(new Pattern(DyeColor.YELLOW, PatternType.BORDER));
		item.setItemMeta(meta);
		ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, entity -> {
			entity.setItemStack(item);
			entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
			entity.setPersistent(false);
			entity.setInvulnerable(true);
			entity.setGravity(false);
			entity.setSilent(true);
			entity.setDisplayWidth(2);
			entity.setDisplayHeight(3);
			entity.setViewRange(1);
			entity.addScoreboardTag(TAG);
			// The banner item model extends half a block below its display origin.
			entity.setTransformation(transform(1.15f, .8f));
		});
		BukkitTask planting = Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (display.isValid()) {
				display.setInterpolationDuration(8);
				display.setInterpolationDelay(0);
				display.setTransformation(transform(.5f, 1));
			}
		}, 1L);
		at.getWorld().playSound(at, Sound.BLOCK_WOOL_PLACE, SoundCategory.PLAYERS, .7f, .8f);
		return new DuelBanner(at, display, planting);
	}

	void remove() {
		if (removed) {
			return;
		}
		removed = true;
		planting.cancel();
		display.remove();
		anchor.getWorld().spawnParticle(Particle.DUST, anchor.clone().add(0, 1, 0), 16, .35, .6, .35,
				0, new Particle.DustOptions(Color.fromRGB(222, 190, 102), 1));
	}

	private static Transformation transform(float height, float scale) {
		return new Transformation(new Vector3f(0, height, 0), new Quaternionf(), new Vector3f(scale), new Quaternionf());
	}
}
