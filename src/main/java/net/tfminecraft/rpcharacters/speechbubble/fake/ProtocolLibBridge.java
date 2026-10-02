package net.tfminecraft.rpcharacters.speechbubble.fake;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class ProtocolLibBridge {

	private static boolean ready;
	private static FakeTextDisplayPackets packets;

	private ProtocolLibBridge() {}

	public static void init(Plugin plugin) {
		ready = false;
		packets = null;

		Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
		if (protocolLib == null || !protocolLib.isEnabled()) {
			plugin.getLogger().warning(
					"ProtocolLib not found — smart-message speech bubbles disabled (chat muffling still works).");
			return;
		}

		try {
			ProtocolManager manager = ProtocolLibrary.getProtocolManager();
			if (manager == null) throw new IllegalStateException("ProtocolLib manager is unavailable");
			packets = new FakeTextDisplayPackets(manager);
			ready = true;
			plugin.getLogger().info("ProtocolLib detected — per-viewer fake speech bubbles enabled.");
		} catch (Exception ex) {
			plugin.getLogger().warning(
					"Failed to initialize ProtocolLib bridge: " + ex.getMessage()
							+ " — smart-message speech bubbles disabled.");
		}
	}

	public static boolean isReady() {
		return ready && packets != null;
	}

	public static FakeTextDisplayPackets getPackets() {
		return packets;
	}

	public static void shutdown() {
		if (ready) {
			FakeBubbleManager.get().shutdown();
		}
		ready = false;
		packets = null;
	}
}
