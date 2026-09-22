package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.PermadeathZoneDefinition;

class PermadeathAreaLookupTest {

	@Test
	void worldGuardWinsWhenBothMatch() {
		PermadeathZoneDefinition wg = new PermadeathZoneDefinition("mini_vard", "Mount Vard");
		PermadeathZoneDefinition sf = new PermadeathZoneDefinition("REGION_1", "Highlands");
		assertSame(wg, PermadeathAreaLookup.preferWorldGuard(wg, sf));
	}

	@Test
	void simpleFactionsUsedWhenWorldGuardMisses() {
		PermadeathZoneDefinition sf = new PermadeathZoneDefinition("REGION_1", "Highlands");
		assertSame(sf, PermadeathAreaLookup.preferWorldGuard(null, sf));
	}

	@Test
	void neitherSourceIsNotAZone() {
		assertNull(PermadeathAreaLookup.preferWorldGuard(null, null));
	}

	@Test
	void worldGuardAlone() {
		PermadeathZoneDefinition wg = new PermadeathZoneDefinition("mini_vard", "Mount Vard");
		assertEquals("mini_vard", PermadeathAreaLookup.preferWorldGuard(wg, null).getRegionId());
	}
}
