package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;
import net.tfminecraft.rpcharacters.speechbubble.fake.FakeTextDisplayPackets;
import net.tfminecraft.rpcharacters.speechbubble.fake.ProtocolLibBridge;

public final class GraveVisualManager {

	private static final GraveVisualManager INSTANCE = new GraveVisualManager();
	private static final double LINE_SPACING = 0.25;
	private static final float SCALE = 1.0f;
	private static final int LINE_WIDTH = 200;

	private final Map<UUID, ViewerGraveState> viewerStates = new HashMap<>();
	private boolean warnedProtocolMissing;

	private GraveVisualManager() {}

	public static GraveVisualManager get() {
		return INSTANCE;
	}

	public void startTicks() {
		new BukkitRunnable() {
			@Override
			public void run() {
				if (!ProtocolLibBridge.isReady()) {
					if (!warnedProtocolMissing) {
						warnedProtocolMissing = true;
						RPCharacters.plugin.getLogger().warning(
								"Grave holograms require ProtocolLib - per-viewer grave text disabled.");
					}
					return;
				}
				warnedProtocolMissing = false;
				for (Player player : Bukkit.getOnlinePlayers()) {
					refreshViewer(player);
				}
			}
		}.runTaskTimer(RPCharacters.plugin, 20L, 20L);
	}

	public void shutdown() {
		for (UUID viewerId : new ArrayList<>(viewerStates.keySet())) {
			clearViewer(viewerId);
		}
		viewerStates.clear();
	}

	public void refreshViewer(Player viewer) {
		if (viewer == null || !viewer.isOnline()) {
			return;
		}
		if (!ProtocolLibBridge.isReady()) {
			return;
		}
		FakeTextDisplayPackets packets = ProtocolLibBridge.getPackets();
		if (packets == null) {
			return;
		}

		ViewerGraveState state = viewerStates.computeIfAbsent(viewer.getUniqueId(), k -> new ViewerGraveState());
		List<Grave> nearby = GraveManager.get().getGravesNear(viewer.getLocation(), GraveLoader.getHologramRadius());

		Map<UUID, GraveVisualStack> active = new HashMap<>();
		for (Grave grave : nearby) {
			GraveVisualStack stack = state.getOrCreateStack(grave.getId());
			refreshGraveStack(viewer, grave, stack, packets);
			active.put(grave.getId(), stack);
		}

		for (UUID graveId : new ArrayList<>(state.getStacks().keySet())) {
			if (!active.containsKey(graveId)) {
				destroyStack(viewer, state.getStacks().get(graveId));
				state.removeStack(graveId);
			}
		}
	}

	public void clearViewer(UUID viewerId) {
		Player viewer = Bukkit.getPlayer(viewerId);
		ViewerGraveState state = viewerStates.remove(viewerId);
		if (state == null || viewer == null || !viewer.isOnline()) {
			return;
		}
		for (GraveVisualStack stack : state.getStacks().values()) {
			destroyStack(viewer, stack);
		}
	}

	public void removeGrave(UUID graveId) {
		for (Map.Entry<UUID, ViewerGraveState> entry : viewerStates.entrySet()) {
			Player viewer = Bukkit.getPlayer(entry.getKey());
			GraveVisualStack stack = entry.getValue().removeStack(graveId);
			if (viewer != null && viewer.isOnline() && stack != null) {
				destroyStack(viewer, stack);
			}
		}
	}

	public static void cleanupLegacyHologram(Grave grave) {
		if (grave == null || grave.getHologramId() == null) {
			return;
		}
		TextDisplayHelper.removeDisplay(grave.getHologramId());
		grave.setHologramId(null);
	}

	private void refreshGraveStack(Player viewer, Grave grave, GraveVisualStack stack,
			FakeTextDisplayPackets packets) {
		destroyStack(viewer, stack);

		Location blockLoc = grave.getBlockLocation();
		if (blockLoc == null || blockLoc.getWorld() == null) {
			return;
		}
		if (!GraveManager.get().isGraveBlockPresent(grave)) {
			return;
		}

		List<String> lines = GraveHologramTexts.linesForViewer(viewer, grave);
		if (lines.isEmpty()) {
			return;
		}

		Location visualBase = blockLoc.clone().add(0.5, GraveLoader.getHologramOffsetY(), 0.5);
		AtomicInteger idSource = viewerStates.get(viewer.getUniqueId()).getEntityIdSource();

		for (int i = 0; i < lines.size(); i++) {
			int stackIndex = lines.size() - 1 - i;
			double y = stackIndex * LINE_SPACING;
			Location lineLoc = visualBase.clone().add(0, y, 0);
			int entityId = idSource.getAndDecrement();
			UUID entityUuid = UUID.randomUUID();
			packets.spawn(viewer, entityId, entityUuid, lineLoc, lines.get(i), SCALE, LINE_WIDTH);
			stack.getLines().add(new GraveVisualLine(entityId, entityUuid));
		}
	}

	private void destroyStack(Player viewer, GraveVisualStack stack) {
		if (stack == null || stack.getLines().isEmpty()) {
			return;
		}
		FakeTextDisplayPackets packets = ProtocolLibBridge.getPackets();
		if (packets == null) {
			return;
		}
		List<Integer> ids = new ArrayList<>();
		for (GraveVisualLine line : stack.getLines()) {
			ids.add(line.entityId());
		}
		packets.destroy(viewer, ids);
		stack.getLines().clear();
	}

	private static final class ViewerGraveState {
		private final AtomicInteger entityIdSource = new AtomicInteger(-60_000);
		private final Map<UUID, GraveVisualStack> stacks = new HashMap<>();

		AtomicInteger getEntityIdSource() {
			return entityIdSource;
		}

		GraveVisualStack getOrCreateStack(UUID graveId) {
			return stacks.computeIfAbsent(graveId, id -> new GraveVisualStack());
		}

		Map<UUID, GraveVisualStack> getStacks() {
			return stacks;
		}

		GraveVisualStack removeStack(UUID graveId) {
			return stacks.remove(graveId);
		}
	}

	private static final class GraveVisualStack {
		private final List<GraveVisualLine> lines = new ArrayList<>();

		List<GraveVisualLine> getLines() {
			return lines;
		}
	}

	private record GraveVisualLine(int entityId, UUID entityUuid) {}
}
