package net.tfminecraft.rpcharacters.joinsafety;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the nearest block position a player can stand at, checking closer offsets first.
 */
public final class SafeSpotSearch {

	public static final int HORIZONTAL_RADIUS = 8;
	public static final int VERTICAL_RADIUS = 8;

	private static final List<int[]> OFFSETS = buildOffsets(HORIZONTAL_RADIUS, VERTICAL_RADIUS);

	@FunctionalInterface
	public interface Probe {
		/** True when feet at (x, y, z) are safe: open feet and head blocks on a solid floor. */
		boolean canStand(int x, int y, int z);
	}

	private SafeSpotSearch() {
	}

	/**
	 * @return {x, y, z} of the nearest standable position, or {@code null} if none is in range
	 */
	public static int[] find(int x, int y, int z, Probe probe) {
		if (probe == null) {
			return null;
		}
		for (int[] offset : OFFSETS) {
			int cx = x + offset[0];
			int cy = y + offset[1];
			int cz = z + offset[2];
			if (probe.canStand(cx, cy, cz)) {
				return new int[] { cx, cy, cz };
			}
		}
		return null;
	}

	/** Offsets ordered by distance; ties prefer less vertical movement, then moving up. */
	static List<int[]> buildOffsets(int horizontal, int vertical) {
		List<int[]> offsets = new ArrayList<>();
		for (int dx = -horizontal; dx <= horizontal; dx++) {
			for (int dy = -vertical; dy <= vertical; dy++) {
				for (int dz = -horizontal; dz <= horizontal; dz++) {
					offsets.add(new int[] { dx, dy, dz });
				}
			}
		}
		offsets.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
				.thenComparingInt(o -> Math.abs(o[1]))
				.thenComparingInt(o -> -o[1])
				.thenComparingInt(o -> o[0])
				.thenComparingInt(o -> o[2]));
		return offsets;
	}
}
