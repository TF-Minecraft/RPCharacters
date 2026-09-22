package net.tfminecraft.rpcharacters.chat;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.managers.ClueInputManager;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.chat.smart.SmartMessageService;
import net.tfminecraft.rpcharacters.chat.smart.SmartMessageSettings;
import net.tfminecraft.rpcharacters.loaders.SmartMessageLoader;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleDebug;

public final class ChatManager implements Listener {

	// Retain Bukkit chat-event ordering and String message semantics for existing integrations.
	@SuppressWarnings("deprecation")
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlainChat(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		if (shouldSkipIngest(player)) {
			return;
		}

		String message = event.getMessage();
		if (message == null || message.isBlank()) {
			return;
		}
		if (message.stripLeading().startsWith("/")) {
			return;
		}

		event.setCancelled(true);

		ChatChannel channel = ChatChannelPreferenceManager.get().getActiveChannel(player);
		if (channel == null) {
			RPCharacters.plugin.getLogger().warning("Default chat channel not loaded — check chat.yml");
			return;
		}

		PlayerData data = PlayerManager.get(player);
		RPCharacter character = data != null ? data.getActiveCharacter() : null;
		if (channel.requiresActiveCharacter() && character == null) {
			Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> RPTexts.send(player, Cache.chatNoCharacterMessage));
			return;
		}

		Bukkit.getScheduler().runTask(RPCharacters.plugin,
				() -> dispatch(player, channel, message, false));
	}

	private static boolean shouldSkipIngest(Player player) {
		if (player == null) {
			return true;
		}
		if (CreationManager.activeCreators.containsKey(player)) {
			return true;
		}
		return ClueInputManager.isPending(player);
	}

	public static void dispatch(Player player, ChatChannel channel, String rawMessage, boolean wasCommand) {
		if (player == null || channel == null || rawMessage == null || rawMessage.isBlank()) {
			if (SpeechBubbleDebug.isEnabled()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "null player/channel/message");
			}
			return;
		}

		if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
			SpeechBubbleDebug.log("chat-dispatch",
					"player=" + player.getName()
							+ ", channel=" + channel.getId()
							+ ", bubble=true"
							+ ", wasCommand=" + wasCommand
							+ ", raw=" + rawMessage);
		}

		if (!ChatChannelPreferenceManager.get().isChannelVisible(player, channel.getId())) {
			if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "channel toggled off for sender");
			}
			RPTexts.send(player, Cache.chatChannelCantUseWhenToggledOffMessage);
			return;
		}

		if (!channel.getUsePermission().isBlank() && !player.hasPermission(channel.getUsePermission())) {
			if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "missing use permission " + channel.getUsePermission());
			}
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to use this chat channel.");
			return;
		}

		PlayerData data = PlayerManager.get(player);
		RPCharacter character = data != null ? data.getActiveCharacter() : null;
		if (channel.requiresActiveCharacter() && character == null) {
			if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "no active character");
			}
			RPTexts.send(player, Cache.chatNoCharacterMessage);
			return;
		}

		String message = sanitizeMessage(player, channel, rawMessage);
		if (message.isEmpty()) {
			if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "message empty after sanitize");
			}
			return;
		}

		if (!player.hasPermission(Cache.chatBypassCooldownPermission)
				&& ChatCooldownManager.get().isOnCooldown(player, channel.getId(), channel.getCooldownSeconds())) {
			if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "on cooldown");
			}
			int remaining = ChatCooldownManager.get().getRemainingSeconds(player, channel.getId());
			RPTexts.send(player, RPTexts.ERROR + "Wait " + RPTexts.WARN + remaining + RPTexts.ERROR + "s.");
			return;
		}

		Set<Player> recipients = buildRecipients(player, channel);

		boolean wearingMask = channel.isMasked() && MaskService.isMasked(player);
		String displayName = wearingMask
				? MaskService.getMaskedLabel()
				: DisplayIdentityService.resolveDisplayUnmasked(player);

		if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
			SpeechBubbleDebug.log("chat-dispatch", "firing CharacterChatEvent, recipients=" + recipients.size());
		}

		CharacterChatEvent chatEvent = new CharacterChatEvent(
				player,
				character,
				channel.getId(),
				message,
				displayName,
				recipients,
				wearingMask,
				wasCommand);
		Bukkit.getPluginManager().callEvent(chatEvent);
		if (chatEvent.isCancelled()) {
			if (SpeechBubbleDebug.isEnabled() && channel.hasSpeechBubble()) {
				SpeechBubbleDebug.logSkip("chat-dispatch", "CharacterChatEvent cancelled by another plugin");
			}
			return;
		}

		RPCharacters.plugin.getLogger().info(
				player.getName() + " in " + channel.getId() + ": " + chatEvent.getMessage());

		SmartMessageSettings smartSettings = SmartMessageLoader.getSettings();
		if (smartSettings.isEnabled() && channel.isSmartMessages() && !channel.isGlobal()) {
			SmartMessageService.deliver(chatEvent, channel, player);
		} else {
			if (chatEvent.getRecipients().isEmpty()) {
				RPTexts.send(player, RPTexts.ERROR + "No one can hear you in this channel.");
				return;
			}

			String formatted = ChatFormatter.format(channel, player, chatEvent.getDisplayName(), chatEvent.getMessage());
			if (formatted.isEmpty()) {
				return;
			}

			for (Player recipient : chatEvent.getRecipients()) {
				if (recipient != null && recipient.isOnline()
						&& ChatChannelPreferenceManager.get().isChannelVisible(recipient, channel.getId())) {
					RPTexts.send(recipient, formatted);
				}
			}
		}

		if (!player.hasPermission(Cache.chatBypassCooldownPermission)) {
			ChatCooldownManager.get().applyCooldown(player, channel.getId(), channel.getCooldownSeconds());
		}
	}

	private static String sanitizeMessage(Player player, ChatChannel channel, String rawMessage) {
		String trimmed = rawMessage.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		String colorPerm = channel.getColorCodePermission();
		if (colorPerm != null && !colorPerm.isBlank() && player.hasPermission(colorPerm)) {
			return trimmed;
		}
		return ClueFormatter.stripColor(trimmed);
	}

	private static Set<Player> buildRecipients(Player sender, ChatChannel channel) {
		if (channel.usesRecipientResolver()) {
			return ChatRecipientResolverRegistry.resolve(
					channel.getRecipientResolverId(), sender, channel);
		}

		Set<Player> recipients = new HashSet<>();
		String channelId = channel.getId();
		if (ChatRecipientFilters.canReceive(sender, channel)) {
			recipients.add(sender);
		}
		if (channel.isGlobal()) {
			for (Player target : Bukkit.getOnlinePlayers()) {
				if (!target.equals(sender) && ChatRecipientFilters.canReceive(target, channel)) {
					recipients.add(target);
				}
			}
			return recipients;
		}

		Location origin = sender.getLocation();
		double rangeSq = (double) channel.getRange() * channel.getRange();
		for (Player target : sender.getWorld().getPlayers()) {
			if (target.equals(sender) || !ChatRecipientFilters.canReceive(target, channel)) {
				continue;
			}
			if (target.getLocation().distanceSquared(origin) <= rangeSq) {
				recipients.add(target);
			}
		}
		return recipients;
	}
}
