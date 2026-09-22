package net.tfminecraft.rpcharacters.chat.smart;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Wall;
import org.bukkit.util.BoundingBox;

/**
 * Decides which blocks attenuate speech and how strongly, without listing every material.
 */
final class SoundOcclusionRules {

	private SoundOcclusionRules() {}

	static boolean blocksSound(Block block, SmartMessageSettings settings) {
		if (block == null || block.isEmpty()) {
			return false;
		}
		if (block.isPassable()) {
			return false;
		}

		Material material = block.getType();
		if (isStructural(material, block)) {
			return true;
		}
		if (isPermeable(material, block.getBlockData())) {
			return false;
		}

		return collisionFillRatio(block) >= settings.getCollisionFillThreshold();
	}

	static boolean isOpenAnchor(Block block, SmartMessageSettings settings) {
		if (block == null || block.isEmpty()) {
			return true;
		}
		return !blocksSound(block, settings);
	}

	static double attenuation(Block block, SmartMessageSettings settings) {
		if (block == null) {
			return settings.getDefaultBlockAttenuation();
		}
		Material material = block.getType();
		Double override = settings.getBlockAttenuationOverride(material);
		if (override != null) {
			return override;
		}
		return attenuationFromBlastResistance(material, settings.getDefaultBlockAttenuation());
	}

	static double attenuationFromBlastResistance(Material material, double fallback) {
		if (material == null || material.isAir()) {
			return 0.0;
		}

		float resistance = material.getBlastResistance();
		if (resistance < 0.0f) {
			return fallback;
		}
		if (resistance < 0.3f) {
			return 0.06;
		}
		if (resistance < 0.8f) {
			return 0.10;
		}
		if (resistance < 1.5f) {
			return 0.18;
		}
		if (resistance < 3.0f) {
			return 0.35;
		}
		if (resistance < 6.0f) {
			return 0.45;
		}
		if (resistance < 12.0f) {
			return 0.55;
		}
		return 0.62;
	}

	private static boolean isStructural(Material material, Block block) {
		if (Tag.STAIRS.isTagged(material)) {
			return true;
		}
		if (Tag.DOORS.isTagged(material) && isClosed(block)) {
			return true;
		}
		return Tag.TRAPDOORS.isTagged(material) && isClosed(block);
	}

	private static boolean isClosed(Block block) {
		BlockData data = block.getBlockData();
		if (data instanceof Openable openable) {
			return !openable.isOpen();
		}
		return !block.isPassable();
	}

	private static boolean isPermeable(Material material, BlockData data) {
		if (data instanceof Fence || data instanceof Wall) {
			return true;
		}
		if (isPermeableByTag(material)) {
			return true;
		}
		return isPermeableByName(material);
	}

	private static boolean isPermeableByTag(Material material) {
		return Tag.FENCES.isTagged(material)
				|| Tag.LEAVES.isTagged(material)
				|| Tag.WALLS.isTagged(material)
				|| Tag.FENCE_GATES.isTagged(material)
				|| Tag.FLOWER_POTS.isTagged(material)
				|| Tag.SIGNS.isTagged(material)
				|| Tag.BANNERS.isTagged(material)
				|| Tag.CANDLES.isTagged(material)
				|| Tag.CROPS.isTagged(material)
				|| Tag.SAPLINGS.isTagged(material)
				|| Tag.SMALL_FLOWERS.isTagged(material)
				|| Tag.CORAL_PLANTS.isTagged(material)
				|| Tag.CLIMBABLE.isTagged(material)
				|| Tag.WOOL_CARPETS.isTagged(material)
				|| Tag.PRESSURE_PLATES.isTagged(material)
				|| Tag.BUTTONS.isTagged(material)
				|| Tag.RAILS.isTagged(material);
	}

	private static boolean isPermeableByName(Material material) {
		String name = material.name();
		return name.contains("BARS")
				|| name.contains("CHAIN")
				|| name.contains("FLOWER_POT")
				|| name.contains("SIGN")
				|| name.contains("BANNER")
				|| name.contains("CANDLE")
				|| name.contains("TORCH")
				|| name.contains("LANTERN")
				|| name.contains("ROD")
				|| name.contains("PRESSURE_PLATE")
				|| name.contains("BUTTON")
				|| name.contains("LEVER")
				|| name.contains("TRIPWIRE")
				|| name.contains("CARPET")
				|| name.contains("SAPLING")
				|| name.contains("FLOWER")
				|| name.contains("MUSHROOM")
				|| name.contains("GRASS")
				|| name.contains("FERN")
				|| name.contains("VINE")
				|| name.contains("LILY_PAD")
				|| name.contains("SCAFFOLDING");
	}

	private static double collisionFillRatio(Block block) {
		double total = 0.0;
		for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
			total += box.getWidthX() * box.getHeight() * box.getWidthZ();
		}
		return Math.min(1.0, total);
	}
}
