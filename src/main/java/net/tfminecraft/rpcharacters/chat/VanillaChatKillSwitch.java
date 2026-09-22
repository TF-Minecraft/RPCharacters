package net.tfminecraft.rpcharacters.chat;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Last-moment vanilla broadcast kill. Other plugins and RP capture see the
 * event first; this only stops {@code <Name> message} from going global.
 */
public final class VanillaChatKillSwitch implements Listener {

	// Retain Bukkit chat-event ordering and String message semantics for existing integrations.
	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onLegacyChat(AsyncPlayerChatEvent event) {
		event.setCancelled(true);
		event.getRecipients().clear();
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onPaperChat(AsyncChatEvent event) {
		event.setCancelled(true);
		event.viewers().clear();
	}
}
