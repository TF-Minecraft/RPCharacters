package net.tfminecraft.rpcharacters.speechbubble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.loaders.SpeechBubbleLoader;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;
import net.tfminecraft.rpcharacters.display.TextWrapUtil;
import net.tfminecraft.rpcharacters.speechbubble.BubbleLayoutUtil;

public final class SpeechBubbleManager {

	private static final SpeechBubbleManager INSTANCE = new SpeechBubbleManager();
	private static final double POSITION_EPSILON_SQ = 0.001 * 0.001;

	private static final NamespacedKey OWNER_KEY = TextDisplayHelper.key("speech_bubble_owner");
	private static final NamespacedKey UTTERANCE_KEY = TextDisplayHelper.key("speech_bubble_utterance");
	private static final NamespacedKey LINE_KEY = TextDisplayHelper.key("speech_bubble_line");

	private final Map<UUID, SpeechBubbleStack> stacks = new HashMap<>();
	private long tickCounter;
	private BukkitRunnable tickTask;

	private SpeechBubbleManager() {}

	public static SpeechBubbleManager get() {
		return INSTANCE;
	}

	public void startTicks() {
		if (tickTask != null) {
			return;
		}
		SpeechBubbleDebug.log("startup", "tick task started");
		tickTask = new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		};
		tickTask.runTaskTimer(RPCharacters.plugin, 0L, 1L);
	}

	public void shutdown() {
		if (tickTask != null) {
			tickTask.cancel();
			tickTask = null;
		}
		removeAll();
	}

	public void onChat(CharacterChatEvent event) {
		SpeechBubbleSettings settings = SpeechBubbleLoader.getSettings();
		Player player = event.getSender();
		String channelId = event.getChannel();

		SpeechBubbleDebug.log("event-received",
				"player=" + (player != null ? player.getName() : "null")
						+ ", channel=" + channelId
						+ ", cancelled=" + event.isCancelled()
						+ ", message=" + preview(event.getMessage()));

		if (!settings.isEnabled()) {
			SpeechBubbleDebug.logSkip("onChat", "speech bubbles disabled in speechbubbles.yml");
			return;
		}

		if (player == null || !player.isOnline()) {
			SpeechBubbleDebug.logSkip("onChat", "sender null or offline");
			return;
		}

		if (channelId == null) {
			SpeechBubbleDebug.logSkip("onChat", "channel id null");
			return;
		}

		ChatChannel channel = ChatLoader.getChannel(channelId);
		if (channel == null) {
			SpeechBubbleDebug.logSkip("onChat", "unknown channel '" + channelId + "' in chat.yml");
			return;
		}
		if (!channel.hasSpeechBubble()) {
			SpeechBubbleDebug.logSkip("onChat",
					"channel '" + channelId + "' has bubble=false (or missing) in chat.yml");
			return;
		}

		String message = event.getMessage();
		if (message == null || message.isBlank()) {
			SpeechBubbleDebug.logSkip("onChat", "empty message after sanitization");
			return;
		}

		String colorPrefix = StringFormatter.formatHex(channel.getMessageColorPrefix().replace('&', '\u00A7'));
		List<String> lines = TextWrapUtil.wrapLines(message, settings.getMaxCharactersPerLine(), colorPrefix);
		if (lines.isEmpty()) {
			SpeechBubbleDebug.logSkip("onChat", "wrap produced no lines");
			return;
		}

		SpeechBubbleDebug.log("onChat",
				"creating utterance lines=" + lines.size()
						+ ", colorPrefix=" + channel.getMessageColorPrefix()
						+ ", wrapped=" + lines);

		long expiresAt = System.currentTimeMillis()
				+ (settings.getUtteranceTimeoutSeconds() * 1000L);
		SpeechBubbleUtterance utterance = new SpeechBubbleUtterance(lines, expiresAt);

		SpeechBubbleStack stack = stacks.computeIfAbsent(player.getUniqueId(), SpeechBubbleStack::new);
		stack.getUtterances().addLast(utterance);

		while (stack.getUtterances().size() > settings.getMaxStackedUtterances()) {
			SpeechBubbleUtterance removed = stack.getUtterances().removeFirst();
			removeDisplaysForUtterance(stack, removed.getId());
			SpeechBubbleDebug.log("onChat", "dropped oldest utterance due to stack cap");
		}

		refreshVisuals(player, stack, settings);
		SpeechBubbleDebug.log("onChat", "refreshVisuals complete, displayCount=" + stack.getDisplayEntityIds().size());
	}

	public void removePlayer(UUID playerId) {
		SpeechBubbleDebug.log("cleanup", "removePlayer playerId=" + playerId);
		SpeechBubbleStack stack = stacks.remove(playerId);
		if (stack != null) {
			removeAllDisplays(stack);
			stack.clear();
		}
	}

	public void removeAll() {
		for (SpeechBubbleStack stack : new ArrayList<>(stacks.values())) {
			removeAllDisplays(stack);
			stack.clear();
		}
		stacks.clear();
	}

	private void tick() {
		tickCounter++;
		SpeechBubbleSettings settings = SpeechBubbleLoader.getSettings();
		if (!settings.isEnabled()) {
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, SpeechBubbleStack>> iterator = stacks.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<UUID, SpeechBubbleStack> entry = iterator.next();
			UUID playerId = entry.getKey();
			SpeechBubbleStack stack = entry.getValue();
			Player player = Bukkit.getPlayer(playerId);

			if (player == null || !player.isOnline()) {
				removeAllDisplays(stack);
				stack.clear();
				iterator.remove();
				continue;
			}

			boolean changed = false;

			Iterator<SpeechBubbleUtterance> utteranceIterator = stack.getUtterances().iterator();
			while (utteranceIterator.hasNext()) {
				SpeechBubbleUtterance utterance = utteranceIterator.next();
				if (utterance.isExpired(now)) {
					removeDisplaysForUtterance(stack, utterance.getId());
					utteranceIterator.remove();
					changed = true;
				}
			}

			if (stack.getUtterances().isEmpty()) {
				removeAllDisplays(stack);
				stack.clear();
				iterator.remove();
				continue;
			}

			if (changed) {
				refreshVisuals(player, stack, settings);
			} else {
				updatePositions(player, stack, settings);
			}
		}
	}

	private void refreshVisuals(Player player, SpeechBubbleStack stack, SpeechBubbleSettings settings) {
		List<SpeechBubbleStack.LayoutLine> layoutLines = stack.buildLayoutLines();
		List<UUID> ids = stack.getDisplayEntityIds();
		float scale = settings.getScale();
		World world = player.getWorld();

		while (ids.size() > layoutLines.size()) {
			TextDisplayHelper.removeDisplay(ids.remove(ids.size() - 1));
		}

		UUID ownerId = player.getUniqueId();
		for (int i = 0; i < layoutLines.size(); i++) {
			SpeechBubbleStack.LayoutLine layoutLine = layoutLines.get(i);
			int stackIndex = layoutLines.size() - 1 - i;
			Location spawnLoc = desiredLineLocation(player, stackIndex, settings);

			UUID existingId = i < ids.size() ? ids.get(i) : null;
			TextDisplay display = TextDisplayHelper.getOrCreateDisplay(existingId, world, spawnLoc, td -> {
				TextDisplayHelper.applyDisplay(td, layoutLine.getText(),
						TextDisplayHelper.createScaleTransformation(scale), false);
				tagDisplay(td, ownerId, layoutLine.getUtteranceId(), layoutLine.getLineIndex());
			});
			if (display == null) {
				SpeechBubbleDebug.logSkip("refreshVisuals",
						"failed to spawn line " + i + " at " + formatLoc(spawnLoc));
				continue;
			}

			TextDisplayHelper.applyDisplay(display, layoutLine.getText(),
					TextDisplayHelper.createScaleTransformation(scale), false);
			tagDisplay(display, ownerId, layoutLine.getUtteranceId(), layoutLine.getLineIndex());
			display.teleport(spawnLoc);

			UUID displayId = display.getUniqueId();
			if (i < ids.size()) {
				ids.set(i, displayId);
			} else {
				ids.add(displayId);
			}

			SpeechBubbleDebug.log("refreshVisuals",
					"line " + i + " entity=" + displayId
							+ " loc=" + formatLoc(spawnLoc)
							+ " text=" + preview(layoutLine.getText()));
		}
	}

	private void updatePositions(Player player, SpeechBubbleStack stack, SpeechBubbleSettings settings) {
		List<SpeechBubbleStack.LayoutLine> layoutLines = stack.buildLayoutLines();
		List<UUID> ids = stack.getDisplayEntityIds();
		double lerpFactor = settings.getFollowLerpFactor();
		boolean needRefresh = false;

		for (int i = 0; i < layoutLines.size(); i++) {
			if (i >= ids.size()) {
				needRefresh = true;
				break;
			}
			int stackIndex = layoutLines.size() - 1 - i;
			Location target = desiredLineLocation(player, stackIndex, settings);
			TextDisplay display = TextDisplayHelper.findDisplay(ids.get(i));
			if (display == null || display.isDead()) {
				needRefresh = true;
				break;
			}

			Location current = display.getLocation();
			Location lerped = TextDisplayHelper.lerpLocation(current, target, lerpFactor);
			if (lerped == null) {
				needRefresh = true;
				break;
			}
			if (current.distanceSquared(lerped) > POSITION_EPSILON_SQ) {
				display.teleport(lerped);
			}
		}

		if (needRefresh) {
			refreshVisuals(player, stack, settings);
		}
	}

	private Location desiredLineLocation(Player player, int stackIndex, SpeechBubbleSettings settings) {
		return BubbleLayoutUtil.desiredLineLocation(player, stackIndex, settings, tickCounter);
	}

	private void tagDisplay(TextDisplay display, UUID ownerId, UUID utteranceId, int lineIndex) {
		TextDisplayHelper.setTag(display, OWNER_KEY, ownerId.toString());
		TextDisplayHelper.setTag(display, UTTERANCE_KEY, utteranceId.toString());
		TextDisplayHelper.setTag(display, LINE_KEY, lineIndex);
	}

	private void removeDisplaysForUtterance(SpeechBubbleStack stack, UUID utteranceId) {
		String utteranceKey = utteranceId.toString();
		List<UUID> ids = stack.getDisplayEntityIds();
		for (int i = ids.size() - 1; i >= 0; i--) {
			UUID entityId = ids.get(i);
			TextDisplay display = TextDisplayHelper.findDisplay(entityId);
			if (display == null) {
				ids.remove(i);
				continue;
			}
			String taggedUtterance = display.getPersistentDataContainer().get(UTTERANCE_KEY,
					org.bukkit.persistence.PersistentDataType.STRING);
			if (utteranceKey.equals(taggedUtterance)) {
				TextDisplayHelper.removeDisplay(entityId);
				ids.remove(i);
			}
		}
	}

	private void removeAllDisplays(SpeechBubbleStack stack) {
		for (UUID entityId : new ArrayList<>(stack.getDisplayEntityIds())) {
			TextDisplayHelper.removeDisplay(entityId);
		}
		stack.getDisplayEntityIds().clear();
	}

	private static String preview(String text) {
		if (text == null) {
			return "null";
		}
		String plain = text.replace('§', '&');
		if (plain.length() <= 48) {
			return "\"" + plain + "\"";
		}
		return "\"" + plain.substring(0, 45) + "...\"";
	}

	private static String formatLoc(Location location) {
		if (location == null) {
			return "null";
		}
		return String.format(Locale.ROOT, "%.2f,%.2f,%.2f",
				location.getX(), location.getY(), location.getZ());
	}
}
