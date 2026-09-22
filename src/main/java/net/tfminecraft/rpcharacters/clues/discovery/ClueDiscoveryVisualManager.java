package net.tfminecraft.rpcharacters.clues.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.ClueHologram;
import net.tfminecraft.rpcharacters.speechbubble.fake.FakeTextDisplayPackets;
import net.tfminecraft.rpcharacters.speechbubble.fake.ProtocolLibBridge;

public final class ClueDiscoveryVisualManager {

	private static final ClueDiscoveryVisualManager INSTANCE = new ClueDiscoveryVisualManager();

	private final Map<UUID, ViewerClueState> viewerStates = new HashMap<>();

	private ClueDiscoveryVisualManager() {}

	public static ClueDiscoveryVisualManager get() {
		return INSTANCE;
	}

	public void shutdown() {
		for (UUID viewerId : new ArrayList<>(viewerStates.keySet())) {
			clearViewer(viewerId);
		}
		viewerStates.clear();
	}

	public void cleanupLegacyOrphans(Iterable<SpawnedClue> clues) {
		for (SpawnedClue clue : clues) {
			if (!clue.getDisplayEntityIds().isEmpty()) {
				ClueHologram.remove(clue);
			}
		}
	}

	public void refreshViewer(Player viewer) {
		if (viewer == null || !viewer.isOnline()) return;
		if (!ProtocolLibBridge.isReady()) return;

		boolean adminMode = ClueAdminModeService.isEnabled(viewer);
		PlayerData pd = PlayerManager.get(viewer);
		if (!adminMode && (pd == null || !pd.hasActiveCharacter())) {
			clearViewer(viewer.getUniqueId());
			return;
		}

		RPCharacter character = pd != null ? pd.getActiveCharacter() : null;
		UUID charUuid = character != null ? parseUuid(character.getId()) : viewer.getUniqueId();

		ViewerClueState state = viewerStates.computeIfAbsent(viewer.getUniqueId(), k -> new ViewerClueState());
		FakeTextDisplayPackets packets = ProtocolLibBridge.getPackets();
		if (packets == null) return;

		List<SpawnedClue> nearby = net.tfminecraft.rpcharacters.managers.SpawnedClueManager.get()
				.getCluesNear(viewer.getLocation(), Math.max(Cache.clueSpawnRadius, 32));

		Map<UUID, ClueVisualStack> active = new HashMap<>();
		for (SpawnedClue clue : nearby) {
			if (!clue.isChunkLoaded()) continue;
			if (!adminMode && !clue.isDiscoveredBy(charUuid)) continue;
			ClueVisualStack stack = state.getOrCreateStack(clue.getId());
			refreshClueStack(viewer, clue, charUuid, stack, packets, adminMode);
			active.put(clue.getId(), stack);
		}

		for (UUID clueId : new ArrayList<>(state.getStacks().keySet())) {
			if (!active.containsKey(clueId)) {
				destroyStack(viewer, state.getStacks().get(clueId));
				state.removeStack(clueId);
			}
		}
	}

	public void tickParticles(Player viewer) {
		if (viewer == null || !viewer.isOnline()) return;
		boolean adminMode = ClueAdminModeService.isEnabled(viewer);
		PlayerData pd = PlayerManager.get(viewer);
		if (!adminMode && (pd == null || !pd.hasActiveCharacter())) return;
		RPCharacter character = pd != null ? pd.getActiveCharacter() : null;
		UUID charUuid = character != null ? parseUuid(character.getId()) : viewer.getUniqueId();
		if (!adminMode && charUuid == null) return;

		List<SpawnedClue> nearby = net.tfminecraft.rpcharacters.managers.SpawnedClueManager.get()
				.getCluesNear(viewer.getLocation(), Math.max(Cache.clueSpawnRadius, 32));
		for (SpawnedClue clue : nearby) {
			if (!adminMode && !clue.isDiscoveredBy(charUuid)) continue;
			if (!clue.isChunkLoaded()) continue;
			ClueHologram.spawnClueParticles(clue);
		}
	}

	public void clearViewer(UUID viewerId) {
		Player viewer = Bukkit.getPlayer(viewerId);
		ViewerClueState state = viewerStates.remove(viewerId);
		if (state == null || viewer == null || !viewer.isOnline()) return;
		for (ClueVisualStack stack : state.getStacks().values()) {
			destroyStack(viewer, stack);
		}
	}

	public void removeClue(UUID clueId) {
		for (Map.Entry<UUID, ViewerClueState> entry : viewerStates.entrySet()) {
			Player viewer = Bukkit.getPlayer(entry.getKey());
			ClueVisualStack stack = entry.getValue().removeStack(clueId);
			if (viewer != null && viewer.isOnline() && stack != null) {
				destroyStack(viewer, stack);
			}
		}
	}

	private void refreshClueStack(Player viewer, SpawnedClue clue, UUID charUuid, ClueVisualStack stack,
			FakeTextDisplayPackets packets, boolean adminMode) {
		destroyStack(viewer, stack);

		Location visualBase = clue.getVisualBase(viewer.getWorld());
		if (visualBase == null) return;

		String resolved = ClueReadabilityResolver.resolve(clue, charUuid, adminMode);
		List<String> lines = ClueFormatter.wrapLore(resolved, Cache.spawnedClueLineLength);
		if (lines.isEmpty()) return;

		AtomicInteger idSource = viewerStates.get(viewer.getUniqueId()).getEntityIdSource();
		float scale = (float) Cache.spawnedClueScale;
		int lineWidth = 200;

		for (int i = 0; i < lines.size(); i++) {
			int stackIndex = lines.size() - 1 - i;
			double y = Cache.spawnedClueFirstLineOffset + (stackIndex * Cache.spawnedClueLineSpacing);
			Location lineLoc = visualBase.clone().add(0, y, 0);
			int entityId = idSource.getAndDecrement();
			UUID entityUuid = UUID.randomUUID();
			packets.spawn(viewer, entityId, entityUuid, lineLoc, lines.get(i), scale, lineWidth);
			stack.getLines().add(new ClueVisualLine(entityId, entityUuid));
		}
	}

	private void destroyStack(Player viewer, ClueVisualStack stack) {
		if (stack == null || stack.getLines().isEmpty()) return;
		FakeTextDisplayPackets packets = ProtocolLibBridge.getPackets();
		if (packets == null) return;
		List<Integer> ids = new ArrayList<>();
		for (ClueVisualLine line : stack.getLines()) {
			ids.add(line.entityId());
		}
		packets.destroy(viewer, ids);
		stack.getLines().clear();
	}

	private static UUID parseUuid(String id) {
		if (id == null) return null;
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static final class ViewerClueState {
		private final AtomicInteger entityIdSource = new AtomicInteger(-50_000);
		private final Map<UUID, ClueVisualStack> stacks = new HashMap<>();

		AtomicInteger getEntityIdSource() {
			return entityIdSource;
		}

		ClueVisualStack getOrCreateStack(UUID clueId) {
			return stacks.computeIfAbsent(clueId, ClueVisualStack::new);
		}

		Map<UUID, ClueVisualStack> getStacks() {
			return stacks;
		}

		ClueVisualStack removeStack(UUID clueId) {
			return stacks.remove(clueId);
		}
	}

	private static final class ClueVisualStack {
		private final UUID clueId;
		private final List<ClueVisualLine> lines = new ArrayList<>();

		ClueVisualStack(UUID clueId) {
			this.clueId = clueId;
		}

		List<ClueVisualLine> getLines() {
			return lines;
		}
	}

	private record ClueVisualLine(int entityId, UUID entityUuid) {}
}
