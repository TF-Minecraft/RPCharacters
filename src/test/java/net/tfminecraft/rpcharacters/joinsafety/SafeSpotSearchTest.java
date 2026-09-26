package net.tfminecraft.rpcharacters.joinsafety;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SafeSpotSearchTest {

	private static SafeSpotSearch.Probe spots(int[]... positions) {
		Set<String> open = new HashSet<>();
		for (int[] p : positions) {
			open.add(p[0] + ":" + p[1] + ":" + p[2]);
		}
		return (x, y, z) -> open.contains(x + ":" + y + ":" + z);
	}

	@Test
	void keepsOriginWhenItIsStandable() {
		int[] found = SafeSpotSearch.find(4079, 196, 1663, spots(new int[] { 4079, 196, 1663 }));
		assertArrayEquals(new int[] { 4079, 196, 1663 }, found);
	}

	@Test
	void picksNearestSpot() {
		int[] found = SafeSpotSearch.find(4079, 196, 1663, spots(
				new int[] { 4077, 196, 1661 },
				new int[] { 4079, 196, 1662 },
				new int[] { 4083, 196, 1663 }));
		assertArrayEquals(new int[] { 4079, 196, 1662 }, found);
	}

	@Test
	void prefersUpOverDownAtEqualDistance() {
		int[] found = SafeSpotSearch.find(0, 64, 0, spots(new int[] { 0, 63, 0 }, new int[] { 0, 65, 0 }));
		assertArrayEquals(new int[] { 0, 65, 0 }, found);
	}

	@Test
	void prefersSidewaysOverVerticalAtEqualDistance() {
		int[] found = SafeSpotSearch.find(0, 64, 0, spots(new int[] { 0, 65, 0 }, new int[] { 1, 64, 0 }));
		assertArrayEquals(new int[] { 1, 64, 0 }, found);
	}

	@Test
	void returnsNullWhenNothingInRange() {
		int far = SafeSpotSearch.HORIZONTAL_RADIUS + 1;
		assertNull(SafeSpotSearch.find(0, 64, 0, spots(new int[] { far, 64, 0 })));
	}

	@Test
	void offsetsCoverTheWholeBoxOnce() {
		List<int[]> offsets = SafeSpotSearch.buildOffsets(2, 1);
		assertEquals(5 * 3 * 5, offsets.size());
		assertArrayEquals(new int[] { 0, 0, 0 }, offsets.get(0));
	}
}
