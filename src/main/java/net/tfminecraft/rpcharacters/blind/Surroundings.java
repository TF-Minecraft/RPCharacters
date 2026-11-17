package net.tfminecraft.rpcharacters.blind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads the blocks around a blind character: what a cane tap outlines and what the
 * warnings call out. Works on {@link Probe} so it runs without a world.
 */
public final class Surroundings {

	/** What a block is, for a blind character's purposes. */
	public enum Kind {
		/** Something to stand on or walk into. */
		SOLID,
		/** Air, plants, open space. */
		OPEN,
		/** Doors, trapdoors and gates. */
		DOOR,
		/** Lava, fire, cactus and the like. */
		HAZARD
	}

	/** What a tap marker shows, most important first. */
	public enum Marker {
		HAZARD, DROP, DOOR, WALL
	}

	public record Spot(Marker marker, int x, int y, int z) {}

	@FunctionalInterface
	public interface Probe {
		Kind kind(int x, int y, int z);
	}

	private Surroundings() {}

	/**
	 * The markers a cane tap shows within {@code radius} of the feet block, nearest and most
	 * important first, at most {@code max}. Everything is only marked where it is on or next to
	 * floor the character could walk on, so open fields and the middle of a lava lake stay quiet,
	 * and hazards take at most half of the markers.
	 */
	public static List<Spot> tap(Probe probe, int fx, int fy, int fz, int radius, int max) {
		List<Spot> spots = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius || (dx == 0 && dz == 0)) continue;
				classify(probe, fx + dx, fy, fz + dz).ifPresent(spots::add);
			}
		}
		spots.sort(Comparator.comparing(Spot::marker)
				.thenComparingInt(s -> (s.x() - fx) * (s.x() - fx) + (s.z() - fz) * (s.z() - fz)));
		int hazardRoom = Math.max(1, max / 2);
		List<Spot> chosen = new ArrayList<>();
		for (Spot spot : spots) {
			if (chosen.size() == max) break;
			if (spot.marker() == Marker.HAZARD && hazardRoom-- <= 0) continue;
			chosen.add(spot);
		}
		return List.copyOf(chosen);
	}

	private static Optional<Spot> classify(Probe probe, int x, int fy, int z) {
		boolean onFloor = floor(probe, x, fy, z).isPresent();
		if (!onFloor && !bordersFloor(probe, x, fy, z)) return Optional.empty();
		for (int y = fy - 1; y <= fy + 1; y++) {
			if (probe.kind(x, y, z) == Kind.HAZARD) return Optional.of(new Spot(Marker.HAZARD, x, y, z));
		}
		for (int y = fy; y <= fy + 1; y++) {
			if (probe.kind(x, y, z) == Kind.DOOR) return Optional.of(new Spot(Marker.DOOR, x, y, z));
		}
		if (onFloor) return Optional.empty();
		if (probe.kind(x, fy, z) == Kind.SOLID && probe.kind(x, fy + 1, z) == Kind.SOLID) {
			return Optional.of(new Spot(Marker.WALL, x, fy, z));
		}
		if (depth(probe, x, fy, z, 2) >= 2) return Optional.of(new Spot(Marker.DROP, x, fy, z));
		return Optional.empty();
	}

	private static boolean bordersFloor(Probe probe, int x, int fy, int z) {
		return floor(probe, x + 1, fy, z).isPresent() || floor(probe, x - 1, fy, z).isPresent()
				|| floor(probe, x, fy, z + 1).isPresent() || floor(probe, x, fy, z - 1).isPresent();
	}

	/** The y a character could stand at in this column, within one step of {@code fy}. */
	static Optional<Integer> floor(Probe probe, int x, int fy, int z) {
		for (int y = fy + 1; y >= fy - 1; y--) {
			if (probe.kind(x, y - 1, z) == Kind.SOLID && probe.kind(x, y, z) == Kind.OPEN
					&& probe.kind(x, y + 1, z) == Kind.OPEN) {
				return Optional.of(y);
			}
		}
		return Optional.empty();
	}

	/** How many open blocks lie below the feet level in this column, counting up to {@code limit}. */
	static int depth(Probe probe, int x, int fy, int z, int limit) {
		int open = 0;
		while (open < limit && probe.kind(x, fy - 1 - open, z) == Kind.OPEN) open++;
		return open;
	}

	/**
	 * What lies ahead in the walking direction (unit steps {@code dx}, {@code dz}), up to
	 * {@code lookAhead} blocks: a hazard, or a drop at least {@code dropDepth} deep. The look
	 * follows the ground one step up or down at a time, so stairs and slopes are not drops.
	 * The first thing found wins; a wall stops the look. Every column the line crosses is looked
	 * at, in order (grid traversal); at an exact corner the x-side column comes first.
	 */
	public static Optional<Marker> ahead(Probe probe, double x, int fy, double z, double dx, double dz,
			int lookAhead, int dropDepth) {
		int level = fy;
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double nextX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? bx + 1 - x : x - bx) / Math.abs(dx);
		double nextZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? bz + 1 - z : z - bz) / Math.abs(dz);
		while (true) {
			double reached;
			if (nextX <= nextZ) {
				reached = nextX;
				bx += stepX;
				nextX += 1 / Math.abs(dx);
			} else {
				reached = nextZ;
				bz += stepZ;
				nextZ += 1 / Math.abs(dz);
			}
			if (reached > lookAhead) break;
			for (int y = level - 1; y <= level + 1; y++) {
				if (probe.kind(bx, y, bz) == Kind.HAZARD) return Optional.of(Marker.HAZARD);
			}
			if (probe.kind(bx, level, bz) == Kind.SOLID && probe.kind(bx, level + 1, bz) == Kind.SOLID) return Optional.empty();
			Optional<Integer> ground = floor(probe, bx, level, bz);
			if (ground.isPresent()) {
				level = ground.get();
			} else if (depth(probe, bx, level, bz, dropDepth) >= dropDepth) {
				return Optional.of(Marker.DROP);
			}
		}
		return Optional.empty();
	}
}
