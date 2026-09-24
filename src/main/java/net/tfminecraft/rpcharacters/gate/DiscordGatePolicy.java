package net.tfminecraft.rpcharacters.gate;

/**
 * Survival Discord freeze exemption. The node is registered by TFMCWeb.
 */
public final class DiscordGatePolicy {

	public static final String BYPASS_PERMISSION = "tfmcweb.discord.bypass";

	private DiscordGatePolicy() {}

	public static boolean freezes(boolean gateRequired, boolean hasBypass) {
		return gateRequired && !hasBypass;
	}
}
