package net.tfminecraft.rpcharacters.chat;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ChatChannelCommandInterceptor implements Listener {

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
		Player player = event.getPlayer();
		if (player == null) {
			return;
		}

		ParsedChannelCommand parsed = ChatChannelCommandParser.parse(event.getMessage());
		if (parsed == null) {
			return;
		}

		event.setCancelled(true);

		if (!parsed.hasMessage()) {
			RPTexts.send(player, RPTexts.ERROR + "Usage: /" + parsed.label() + " <message>");
			return;
		}

		if (CreationManager.activeCreators.containsKey(player)
				&& !CreationManager.isChatInputStage(player)) {
			CreationManager.sendChatBlockedDuringCreationHint(player);
			return;
		}

		ChatManager.dispatch(player, parsed.channel(), parsed.message(), true);
	}
}
