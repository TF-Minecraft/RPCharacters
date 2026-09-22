package net.tfminecraft.rpcharacters.speechbubble.fake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.loaders.SpeechBubbleLoader;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;
import net.tfminecraft.rpcharacters.display.TextWrapUtil;
import net.tfminecraft.rpcharacters.speechbubble.BubbleLayoutUtil;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleSettings;

public final class FakeBubbleManager {

	private static final FakeBubbleManager INSTANCE = new FakeBubbleManager();
	private static final double POSITION_EPSILON_SQ = 0.001 * 0.001;

	private final Map<UUID, ViewerBubbleState> viewerStates = new HashMap<>();
	private long tickCounter;
	private BukkitRunnable tickTask;

	private FakeBubbleManager() {}

	public static FakeBubbleManager get() {
		return INSTANCE;
	}

	public void startTicks() {
		if (tickTask != null || !ProtocolLibBridge.isReady()) {
			return;
		}
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
		for (UUID viewerId : new ArrayList<>(viewerStates.keySet())) {
			clearViewer(viewerId);
		}
		viewerStates.clear();
	}

	public void show(Player viewer, Player speaker, UUID utteranceId, String heardMessage, ChatChannel channel,
			long expiresAt) {
		if (!ProtocolLibBridge.isReady() || viewer == null || speaker == null || channel == null) {
			return;
		}
		if (heardMessage == null || heardMessage.isBlank()) {
			return;
		}

		SpeechBubbleSettings settings = SpeechBubbleLoader.getSettings();
		if (!settings.isEnabled()) {
			return;
		}

		String colorPrefix = StringFormatter.formatHex(channel.getMessageColorPrefix().replace('&', '\u00A7'));
		List<String> lines = TextWrapUtil.wrapLines(heardMessage, settings.getMaxCharactersPerLine(), colorPrefix);
		if (lines.isEmpty()) {
			return;
		}

		ViewerBubbleState viewerState = viewerStates.computeIfAbsent(viewer.getUniqueId(), ViewerBubbleState::new);
		SpeakerViewerStack stack = viewerState.getOrCreateStack(speaker.getUniqueId());

		ViewerUtterance utterance = new ViewerUtterance(utteranceId, lines, expiresAt);
		stack.getUtterances().addLast(utterance);

		while (stack.getUtterances().size() > settings.getMaxStackedUtterances()) {
			ViewerUtterance removed = stack.getUtterances().removeFirst();
			destroyUtteranceEntities(viewer, removed);
		}

		refreshSpeakerStack(viewer, speaker, stack, settings);
	}

	public void removeViewer(UUID viewerId) {
		clearViewer(viewerId);
		viewerStates.remove(viewerId);
	}

	public void removeSpeaker(UUID speakerId) {
		for (ViewerBubbleState viewerState : viewerStates.values()) {
			SpeakerViewerStack stack = viewerState.removeStack(speakerId);
			if (stack != null) {
				Player viewer = Bukkit.getPlayer(viewerState.getViewerId());
				if (viewer != null && viewer.isOnline()) {
					destroyStackEntities(viewer, stack);
				}
			}
		}
	}

	public void removeSpeakerInChunk(String worldName, int chunkX, int chunkZ) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (!player.getWorld().getName().equals(worldName)) {
				continue;
			}
			if ((player.getLocation().getBlockX() >> 4) == chunkX
					&& (player.getLocation().getBlockZ() >> 4) == chunkZ) {
				removeSpeaker(player.getUniqueId());
			}
		}
	}

	private void tick() {
		tickCounter++;
		SpeechBubbleSettings settings = SpeechBubbleLoader.getSettings();
		if (!settings.isEnabled()) {
			return;
		}

		FakeTextDisplayPackets packets = ProtocolLibBridge.getPackets();
		if (packets == null) {
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, ViewerBubbleState>> iterator = viewerStates.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<UUID, ViewerBubbleState> entry = iterator.next();
			Player viewer = Bukkit.getPlayer(entry.getKey());
			if (viewer == null || !viewer.isOnline()) {
				clearViewer(entry.getKey());
				iterator.remove();
				continue;
			}

			ViewerBubbleState viewerState = entry.getValue();
			Iterator<Map.Entry<UUID, SpeakerViewerStack>> stackIterator = viewerState.getStacks().entrySet().iterator();

			while (stackIterator.hasNext()) {
				Map.Entry<UUID, SpeakerViewerStack> stackEntry = stackIterator.next();
				Player speaker = Bukkit.getPlayer(stackEntry.getKey());
				SpeakerViewerStack stack = stackEntry.getValue();
				boolean changed = false;

				Iterator<ViewerUtterance> utteranceIterator = stack.getUtterances().iterator();
				while (utteranceIterator.hasNext()) {
					ViewerUtterance utterance = utteranceIterator.next();
					if (utterance.isExpired(now)) {
						destroyUtteranceEntities(viewer, utterance);
						utteranceIterator.remove();
						changed = true;
					}
				}

				if (stack.getUtterances().isEmpty() || speaker == null || !speaker.isOnline()) {
					destroyStackEntities(viewer, stack);
					stackIterator.remove();
					continue;
				}

				if (changed) {
					refreshSpeakerStack(viewer, speaker, stack, settings);
				} else {
					updatePositions(viewer, speaker, stack, settings, packets);
				}
			}

			if (viewerState.getStacks().isEmpty()) {
				iterator.remove();
			}
		}
	}

	private void refreshSpeakerStack(Player viewer, Player speaker, SpeakerViewerStack stack,
			SpeechBubbleSettings settings) {
		destroyStackEntities(viewer, stack);

		FakeTextDisplayPackets packets = ProtocolLibBridge.getPackets();
		if (packets == null) {
			return;
		}

		List<LayoutLine> layoutLines = stack.buildLayoutLines();
		AtomicInteger idSource = viewerStates.get(viewer.getUniqueId()).getEntityIdSource();
		int lineWidth = Math.max(50, settings.getMaxCharactersPerLine() * 6);
		float scale = settings.getScale();

		for (int i = 0; i < layoutLines.size(); i++) {
			LayoutLine layoutLine = layoutLines.get(i);
			int stackIndex = layoutLines.size() - 1 - i;
			Location spawnLoc = BubbleLayoutUtil.desiredLineLocation(speaker, stackIndex, settings, tickCounter);

			int entityId = idSource.getAndDecrement();
			UUID entityUuid = UUID.randomUUID();
			try {
				packets.spawn(viewer, entityId, entityUuid, spawnLoc, layoutLine.text(), scale, lineWidth);
			} catch (Exception ex) {
				destroyStackEntities(viewer, stack);
				if (RPCharacters.plugin != null) {
					RPCharacters.plugin.getLogger().warning(
							"Failed to refresh fake speech bubbles for " + viewer.getName() + ": " + ex.getMessage());
				}
				return;
			}

			FakeBubbleLine line = new FakeBubbleLine(entityId, entityUuid, layoutLine.utteranceId(), layoutLine.lineIndex(),
					spawnLoc.clone());
			stack.getActiveLines().add(line);
			layoutLine.utterance().trackEntityId(layoutLine.lineIndex(), entityId);
		}
	}

	private void updatePositions(Player viewer, Player speaker, SpeakerViewerStack stack,
			SpeechBubbleSettings settings, FakeTextDisplayPackets packets) {
		List<LayoutLine> layoutLines = stack.buildLayoutLines();
		double lerpFactor = settings.getFollowLerpFactor();
		boolean needRefresh = layoutLines.size() != stack.getActiveLines().size();

		for (int i = 0; !needRefresh && i < layoutLines.size(); i++) {
			int stackIndex = layoutLines.size() - 1 - i;
			Location target = BubbleLayoutUtil.desiredLineLocation(speaker, stackIndex, settings, tickCounter);
			if (i >= stack.getActiveLines().size()) {
				needRefresh = true;
				break;
			}
			FakeBubbleLine line = stack.getActiveLines().get(i);
			Location current = line.getCurrentLocation();
			Location lerped = TextDisplayHelper.lerpLocation(current, target, lerpFactor);
			if (lerped == null) {
				needRefresh = true;
				break;
			}
			if (current.distanceSquared(lerped) > POSITION_EPSILON_SQ) {
				line.setCurrentLocation(lerped);
				packets.teleport(viewer, line.getEntityId(), lerped);
			}
		}

		if (needRefresh) {
			refreshSpeakerStack(viewer, speaker, stack, settings);
		}
	}

	private void clearViewer(UUID viewerId) {
		ViewerBubbleState state = viewerStates.get(viewerId);
		if (state == null) {
			return;
		}
		Player viewer = Bukkit.getPlayer(viewerId);
		if (viewer != null && viewer.isOnline()) {
			for (SpeakerViewerStack stack : state.getStacks().values()) {
				destroyStackEntities(viewer, stack);
			}
		}
	}

	private void destroyStackEntities(Player viewer, SpeakerViewerStack stack) {
		List<Integer> entityIds = new ArrayList<>();
		for (FakeBubbleLine line : stack.getActiveLines()) {
			entityIds.add(line.getEntityId());
		}
		if (!entityIds.isEmpty()) {
			ProtocolLibBridge.getPackets().destroy(viewer, entityIds);
		}
		stack.getActiveLines().clear();
		for (ViewerUtterance utterance : stack.getUtterances()) {
			utterance.clearEntityIds();
		}
	}

	private void destroyUtteranceEntities(Player viewer, ViewerUtterance utterance) {
		List<Integer> entityIds = utterance.getEntityIds();
		if (!entityIds.isEmpty()) {
			ProtocolLibBridge.getPackets().destroy(viewer, entityIds);
			utterance.clearEntityIds();
		}
	}

	static final class ViewerBubbleState {
		private final UUID viewerId;
		private final Map<UUID, SpeakerViewerStack> stacks = new HashMap<>();
		private final AtomicInteger entityIdSource = new AtomicInteger(-1);

		ViewerBubbleState(UUID viewerId) {
			this.viewerId = viewerId;
		}

		UUID getViewerId() {
			return viewerId;
		}

		Map<UUID, SpeakerViewerStack> getStacks() {
			return stacks;
		}

		SpeakerViewerStack getOrCreateStack(UUID speakerId) {
			return stacks.computeIfAbsent(speakerId, SpeakerViewerStack::new);
		}

		SpeakerViewerStack removeStack(UUID speakerId) {
			return stacks.remove(speakerId);
		}

		AtomicInteger getEntityIdSource() {
			return entityIdSource;
		}
	}

	static final class SpeakerViewerStack {
		private final UUID speakerId;
		private final Deque<ViewerUtterance> utterances = new ArrayDeque<>();
		private final List<FakeBubbleLine> activeLines = new ArrayList<>();

		SpeakerViewerStack(UUID speakerId) {
			this.speakerId = speakerId;
		}

		Deque<ViewerUtterance> getUtterances() {
			return utterances;
		}

		List<FakeBubbleLine> getActiveLines() {
			return activeLines;
		}

		List<LayoutLine> buildLayoutLines() {
			List<LayoutLine> layout = new ArrayList<>();
			for (ViewerUtterance utterance : utterances) {
				List<String> lines = utterance.getLines();
				for (int i = 0; i < lines.size(); i++) {
					layout.add(new LayoutLine(utterance, lines.get(i), i));
				}
			}
			return layout;
		}
	}

	static final class ViewerUtterance {
		private final UUID id;
		private final List<String> lines;
		private final long expiresAt;
		private final List<Integer> entityIds = new ArrayList<>();

		ViewerUtterance(UUID id, List<String> lines, long expiresAt) {
			this.id = id;
			this.lines = List.copyOf(lines);
			this.expiresAt = expiresAt;
		}

		UUID getId() {
			return id;
		}

		List<String> getLines() {
			return lines;
		}

		boolean isExpired(long now) {
			return now >= expiresAt;
		}

		List<Integer> getEntityIds() {
			return entityIds;
		}

		void trackEntityId(int lineIndex, int entityId) {
			while (entityIds.size() <= lineIndex) {
				entityIds.add(0);
			}
			entityIds.set(lineIndex, entityId);
		}

		void clearEntityIds() {
			entityIds.clear();
		}
	}

	static final class FakeBubbleLine {
		private final int entityId;
		private final UUID entityUuid;
		private final UUID utteranceId;
		private final int lineIndex;
		private Location currentLocation;

		FakeBubbleLine(int entityId, UUID entityUuid, UUID utteranceId, int lineIndex, Location currentLocation) {
			this.entityId = entityId;
			this.entityUuid = entityUuid;
			this.utteranceId = utteranceId;
			this.lineIndex = lineIndex;
			this.currentLocation = currentLocation;
		}

		int getEntityId() {
			return entityId;
		}

		Location getCurrentLocation() {
			return currentLocation;
		}

		void setCurrentLocation(Location currentLocation) {
			this.currentLocation = currentLocation;
		}
	}

	record LayoutLine(ViewerUtterance utterance, String text, int lineIndex) {
		UUID utteranceId() {
			return utterance.getId();
		}
	}
}
