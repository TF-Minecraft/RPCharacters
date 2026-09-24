package net.tfminecraft.rpcharacters.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscordGatePolicyTest {

	@Test
	void bypassPermissionMatchesTheTfmcWebNode() {
		assertEquals("tfmcweb.discord.bypass", DiscordGatePolicy.BYPASS_PERMISSION);
	}

	@Test
	void staffBypassSkipsTheDiscordFreeze() {
		assertTrue(DiscordGatePolicy.freezes(true, false));
		assertFalse(DiscordGatePolicy.freezes(true, true));
		assertFalse(DiscordGatePolicy.freezes(false, false));
		assertFalse(DiscordGatePolicy.freezes(false, true));
	}
}
