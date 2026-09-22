package net.tfminecraft.rpcharacters.speechbubble;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.loaders.SmartMessageLoader;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.chat.smart.SmartMessageSettings;
import net.tfminecraft.rpcharacters.speechbubble.fake.FakeBubbleManager;
import net.tfminecraft.rpcharacters.speechbubble.fake.ProtocolLibBridge;

public final class SpeechBubbleListener implements Listener {

	@EventHandler(priority = EventPriority.LOWEST)
	public void onCharacterChatDebug(CharacterChatEvent event) {
		if (!SpeechBubbleDebug.isEnabled()) {
			return;
		}
		if (event.isCancelled()) {
			SpeechBubbleDebug.logSkip("event-cancelled",
					"CharacterChatEvent was cancelled before MONITOR — bubble listener will not run"
							+ ", player=" + (event.getSender() != null ? event.getSender().getName() : "null")
							+ ", channel=" + event.getChannel());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onCharacterChat(CharacterChatEvent event) {
		String channelId = event.getChannel();
		ChatChannel channel = channelId != null ? ChatLoader.getChannel(channelId) : null;
		SmartMessageSettings smartSettings = SmartMessageLoader.getSettings();
		if (smartSettings.isEnabled() && channel != null && channel.isSmartMessages() && ProtocolLibBridge.isReady()) {
			return;
		}
		SpeechBubbleManager.get().onChat(event);
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID playerId = event.getPlayer().getUniqueId();
		SpeechBubbleManager.get().removePlayer(playerId);
		FakeBubbleManager.get().removeViewer(playerId);
		FakeBubbleManager.get().removeSpeaker(playerId);
	}

	@EventHandler
	public void onChunkUnload(ChunkUnloadEvent event) {
		Chunk chunk = event.getChunk();
		String worldName = event.getWorld().getName();
		int chunkX = chunk.getX();
		int chunkZ = chunk.getZ();

		for (Player player : Bukkit.getOnlinePlayers()) {
			if (!player.getWorld().getName().equals(worldName)) {
				continue;
			}
			if ((player.getLocation().getBlockX() >> 4) == chunkX
					&& (player.getLocation().getBlockZ() >> 4) == chunkZ) {
				UUID playerId = player.getUniqueId();
				SpeechBubbleManager.get().removePlayer(playerId);
				FakeBubbleManager.get().removeSpeaker(playerId);
			}
		}
		FakeBubbleManager.get().removeSpeakerInChunk(worldName, chunkX, chunkZ);
	}
}
