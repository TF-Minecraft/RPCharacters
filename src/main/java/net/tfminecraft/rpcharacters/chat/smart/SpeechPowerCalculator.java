package net.tfminecraft.rpcharacters.chat.smart;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.chat.ChatChannel;

public final class SpeechPowerCalculator {

	private static final int[][] CARDINAL_OFFSETS = {
			{ -1, 0 },
			{ 1, 0 },
			{ 0, -1 },
			{ 0, 1 },
	};
	private static final int[][] DIAGONAL_OFFSETS = {
			{ -1, -1 },
			{ -1, 1 },
			{ 1, -1 },
			{ 1, 1 },
	};

	private SpeechPowerCalculator() {}

	public static double compute(Player speaker, Player listener, ChatChannel channel, SmartMessageSettings settings) {
		return evaluate(speaker, listener, channel, settings).getIntelligibility();
	}

	public static SpeechPowerSnapshot evaluate(Player speaker, Player listener, ChatChannel channel,
			SmartMessageSettings settings) {
		if (speaker == null || listener == null || channel == null || settings == null) {
			return SpeechPowerSnapshot.skipped("invalid-input");
		}
		if (!speaker.getWorld().equals(listener.getWorld())) {
			return SpeechPowerSnapshot.skipped("different-world");
		}

		if (listener.equals(speaker) && settings.isSenderHearsSelf()) {
			return SpeechPowerSnapshot.resolved(1.0, "sender-hears-self", 0.0, channel.getRange(), 1.0, 1.0, 0.0, 0, 0,
					null);
		}

		int range = channel.getRange();
		if (range <= 0) {
			return SpeechPowerSnapshot.resolved(1.0, "global-channel", 0.0, range, 1.0, 1.0, 0.0, 0, 0, null);
		}

		Location speakerEye = eyeLocation(speaker);
		Location listenerEye = eyeLocation(listener);
		double distance = speakerEye.distance(listenerEye);
		if (distance > range) {
			return SpeechPowerSnapshot.resolved(0.0, "out-of-range", distance, range, 0.0, 0.0, 0.0, 0, 0, null);
		}

		double distanceFactor = distanceFactor(distance, range, settings.getFadeStartPercent());
		ListenerOcclusionResult occlusion = listenerOcclusion(speakerEye, listener, settings);
		double intelligibility = clamp(distanceFactor * occlusion.result().factor());
		return SpeechPowerSnapshot.resolved(intelligibility, null, distance, range, distanceFactor,
				occlusion.result().factor(), occlusion.result().weight(), occlusion.result().blockingBlocks(),
				occlusion.anchorRays(), occlusion.anchorOffset());
	}

	private static Location eyeLocation(Player player) {
		Location loc = player.getLocation().clone();
		loc.setY(loc.getY() + player.getEyeHeight());
		return loc;
	}

	private static double distanceFactor(double distance, int range, double fadeStartPercent) {
		if (range <= 0) {
			return 1.0;
		}
		double fadeStart = range * clamp(fadeStartPercent);
		if (distance <= fadeStart) {
			return 1.0;
		}
		double fadeSpan = Math.max(0.001, range - fadeStart);
		double t = (distance - fadeStart) / fadeSpan;
		return 1.0 - smoothstep(t);
	}

	private static ListenerOcclusionResult listenerOcclusion(Location speakerEye, Player listener,
			SmartMessageSettings settings) {
		Location listenerEye = eyeLocation(listener);
		if (!settings.isListenerAnchorSearch()) {
			OcclusionResult direct = rayOcclusion(speakerEye, listenerEye, settings);
			return new ListenerOcclusionResult(direct, 1, "0,0");
		}

		World world = listenerEye.getWorld();
		int headBlockX = listenerEye.getBlockX();
		int headBlockY = listenerEye.getBlockY();
		int headBlockZ = listenerEye.getBlockZ();
		double anchorY = listenerEye.getY();

		Location directAnchor = anchorLocation(world, headBlockX, anchorY, headBlockZ);
		OcclusionResult best = rayOcclusion(speakerEye, directAnchor, settings);
		String bestOffset = "0,0";
		int raysCast = 1;

		if (settings.isListenerAnchorEarlyExitClearLos() && best.blockingBlocks() == 0) {
			return new ListenerOcclusionResult(best, raysCast, bestOffset);
		}

		int[][] offsets = settings.isListenerAnchorDiagonals()
				? concatOffsets(CARDINAL_OFFSETS, DIAGONAL_OFFSETS)
				: CARDINAL_OFFSETS;

		for (int[] offset : offsets) {
			int blockX = headBlockX + offset[0];
			int blockZ = headBlockZ + offset[1];
			Block neighbor = world.getBlockAt(blockX, headBlockY, blockZ);
			if (!SoundOcclusionRules.isOpenAnchor(neighbor, settings)) {
				continue;
			}

			Location anchor = anchorLocation(world, blockX, anchorY, blockZ);
			OcclusionResult candidate = rayOcclusion(speakerEye, anchor, settings);
			raysCast++;
			if (candidate.factor() > best.factor()) {
				best = candidate;
				bestOffset = offset[0] + "," + offset[1];
			}
		}

		return new ListenerOcclusionResult(best, raysCast, bestOffset);
	}

	private static int[][] concatOffsets(int[][] first, int[][] second) {
		int[][] combined = new int[first.length + second.length][2];
		System.arraycopy(first, 0, combined, 0, first.length);
		System.arraycopy(second, 0, combined, first.length, second.length);
		return combined;
	}

	private static Location anchorLocation(World world, int blockX, double y, int blockZ) {
		return new Location(world, blockX + 0.5, y, blockZ + 0.5);
	}

	private static OcclusionResult rayOcclusion(Location from, Location to, SmartMessageSettings settings) {
		World world = from.getWorld();
		if (world == null || !world.equals(to.getWorld())) {
			return new OcclusionResult(0.0, 0.0, 0);
		}

		double dx = to.getX() - from.getX();
		double dy = to.getY() - from.getY();
		double dz = to.getZ() - from.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance < 0.001) {
			return new OcclusionResult(1.0, 0.0, 0);
		}

		double step = settings.getRayStep();
		int steps = (int) Math.ceil(distance / step);
		double invDist = 1.0 / distance;
		double ndx = dx * invDist;
		double ndy = dy * invDist;
		double ndz = dz * invDist;

		double totalWeight = 0.0;
		Set<Long> visitedBlocks = new HashSet<>();
		int blockingBlocks = 0;
		for (int i = 1; i < steps; i++) {
			double travelled = i * step;
			if (travelled >= distance) {
				break;
			}
			double x = from.getX() + ndx * travelled;
			double y = from.getY() + ndy * travelled;
			double z = from.getZ() + ndz * travelled;
			int blockX = (int) Math.floor(x);
			int blockY = (int) Math.floor(y);
			int blockZ = (int) Math.floor(z);
			long blockKey = packBlockKey(blockX, blockY, blockZ);
			if (!visitedBlocks.add(blockKey)) {
				continue;
			}
			Block block = world.getBlockAt(blockX, blockY, blockZ);
			if (SoundOcclusionRules.blocksSound(block, settings)) {
				totalWeight += SoundOcclusionRules.attenuation(block, settings);
				blockingBlocks++;
			}
		}

		double cappedWeight = Math.min(totalWeight, settings.getOcclusionMaxWeight());
		double factor = Math.exp(-cappedWeight * settings.getOcclusionCurve());
		return new OcclusionResult(clamp(factor), cappedWeight, blockingBlocks);
	}

	private static long packBlockKey(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
	}

	private static double smoothstep(double t) {
		double clamped = clamp(t);
		return clamped * clamped * (3.0 - 2.0 * clamped);
	}

	private static double clamp(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private record OcclusionResult(double factor, double weight, int blockingBlocks) {}

	private record ListenerOcclusionResult(OcclusionResult result, int anchorRays, String anchorOffset) {}
}
