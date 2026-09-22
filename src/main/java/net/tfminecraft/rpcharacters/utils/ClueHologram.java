package net.tfminecraft.rpcharacters.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;

public final class ClueHologram {

	private static final DustOptions RAY_DUST = new DustOptions(Color.fromRGB(0xC8C8C8), 0.8f);
	private static final double ORPHAN_SCAN_RADIUS = 2.5;

	private static final NamespacedKey CLUE_ID_KEY = TextDisplayHelper.key("spawned_clue_id");
	private static final NamespacedKey CLUE_LINE_KEY = TextDisplayHelper.key("spawned_clue_line");

	private ClueHologram() {}

	public static void spawn(SpawnedClue clue) {
		refresh(clue);
	}

	public static void refresh(SpawnedClue clue) {
		Location visualBase = clue.getVisualBase();
		if (visualBase == null || visualBase.getWorld() == null) return;

		World world = visualBase.getWorld();
		List<String> lines = ClueFormatter.wrapLore(clue.getClueText(), Cache.spawnedClueLineLength);
		var transformation = TextDisplayHelper.createScaleTransformation(Cache.spawnedClueScale);

		removeOrphanDisplays(clue, visualBase);

		List<UUID> ids = clue.getDisplayEntityIds();
		while (ids.size() > lines.size()) {
			TextDisplayHelper.removeDisplay(ids.remove(ids.size() - 1));
		}

		boolean idsChanged = false;
		for (int i = 0; i < lines.size(); i++) {
			int stackIndex = lines.size() - 1 - i;
			double y = Cache.spawnedClueFirstLineOffset + (stackIndex * Cache.spawnedClueLineSpacing);
			Location lineLoc = visualBase.clone().add(0, y, 0);
			String text = lines.get(i);

			UUID existingId = i < ids.size() ? ids.get(i) : null;
			TextDisplay display = getOrCreateDisplay(clue, i, existingId, world, lineLoc, text, transformation);
			if (display == null) continue;

			UUID displayId = display.getUniqueId();
			if (i < ids.size()) {
				if (!displayId.equals(ids.get(i))) {
					ids.set(i, displayId);
					idsChanged = true;
				}
			} else {
				ids.add(displayId);
				idsChanged = true;
			}
		}

		clue.setVisualsSpawned(true);
		if (idsChanged) {
			SpawnedClueManager.get().markDisplayDirty();
		}
	}

	public static void remove(SpawnedClue clue) {
		Location visualBase = clue.getVisualBase();
		if (visualBase != null && clue.isChunkLoaded()) {
			removeOrphanDisplays(clue, visualBase);
		}
		for (UUID entityId : new ArrayList<>(clue.getDisplayEntityIds())) {
			TextDisplayHelper.removeDisplay(entityId);
		}
		clue.clearDisplayEntityIds();
		clue.setVisualsSpawned(false);
	}

	private static TextDisplay getOrCreateDisplay(SpawnedClue clue, int lineIndex, UUID entityId,
			World world, Location lineLoc, String text, org.bukkit.util.Transformation transformation) {
		TextDisplay display = TextDisplayHelper.getOrCreateDisplay(entityId, world, lineLoc, td -> {
			TextDisplayHelper.applyDisplay(td, text, transformation, true);
			tagDisplay(td, clue.getId(), lineIndex);
		});
		if (display == null) {
			return null;
		}
		if (entityId != null && entityId.equals(display.getUniqueId())) {
			TextDisplayHelper.applyDisplay(display, text, transformation, true);
			tagDisplay(display, clue.getId(), lineIndex);
		}
		return display;
	}

	private static void tagDisplay(TextDisplay display, UUID clueId, int lineIndex) {
		TextDisplayHelper.setTag(display, CLUE_ID_KEY, clueId.toString());
		TextDisplayHelper.setTag(display, CLUE_LINE_KEY, lineIndex);
	}

	private static void removeOrphanDisplays(SpawnedClue clue, Location visualBase) {
		World world = visualBase.getWorld();
		if (world == null) return;

		Set<UUID> tracked = new HashSet<>(clue.getDisplayEntityIds());
		String clueId = clue.getId().toString();

		for (Entity entity : world.getNearbyEntities(visualBase, ORPHAN_SCAN_RADIUS, ORPHAN_SCAN_RADIUS, ORPHAN_SCAN_RADIUS)) {
			if (!(entity instanceof TextDisplay display)) continue;
			if (display.isDead()) continue;
			if (!clueId.equals(display.getPersistentDataContainer().get(CLUE_ID_KEY, PersistentDataType.STRING))) {
				continue;
			}
			if (!tracked.contains(display.getUniqueId())) {
				display.remove();
			}
		}
	}

	public static void spawnParticle(Location visualBase) {
		if (visualBase == null || visualBase.getWorld() == null) return;
		visualBase.getWorld().spawnParticle(
				Particle.FIREWORK,
				visualBase,
				3,
				0.05, 0.05, 0.05,
				0.01);
	}

	public static void spawnParticleRay(Location from, Location to) {
		if (from == null || to == null || from.getWorld() == null) return;
		if (!from.getWorld().equals(to.getWorld())) return;

		double dx = to.getX() - from.getX();
		double dy = to.getY() - from.getY();
		double dz = to.getZ() - from.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance < 0.05) {
			spawnParticle(from);
			return;
		}

		int steps = Math.max(2, (int) Math.ceil(distance * 4));
		World world = from.getWorld();
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			world.spawnParticle(
					Particle.DUST,
					from.getX() + dx * t,
					from.getY() + dy * t,
					from.getZ() + dz * t,
					1,
					0, 0, 0,
					0,
					RAY_DUST);
		}
	}

	public static void spawnClueParticles(SpawnedClue clue) {
		Location visualBase = clue.getVisualBase();
		if (visualBase == null) return;

		spawnParticle(visualBase);
		if (clue.hasTargetBlock()) {
			Location targetCenter = clue.getTargetCenter(visualBase.getWorld());
			if (targetCenter != null) {
				spawnParticleRay(visualBase, targetCenter);
			}
		}
	}
}
