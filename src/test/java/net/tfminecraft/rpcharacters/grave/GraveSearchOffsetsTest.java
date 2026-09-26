package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GraveSearchOffsetsTest {

	private static int distanceSq(int[] o) {
		return o[0] * o[0] + o[1] * o[1] + o[2] * o[2];
	}

	@Test
	void coversRadiusTwoOnSameLevelAndOneAboveWithoutOrigin() {
		List<int[]> offsets = GraveManager.searchOffsets(2);
		assertEquals(5 * 5 * 2 - 1, offsets.size());
		for (int[] o : offsets) {
			assertTrue(o[0] != 0 || o[1] != 0 || o[2] != 0);
		}
	}

	@Test
	void checksNearestBlocksFirst() {
		List<int[]> offsets = GraveManager.searchOffsets(2);
		assertEquals(1, distanceSq(offsets.get(0)));
		assertEquals(0, offsets.get(0)[1], "same level is tried before the block above");
		for (int i = 1; i < offsets.size(); i++) {
			assertTrue(distanceSq(offsets.get(i - 1)) <= distanceSq(offsets.get(i)));
		}
	}
}
