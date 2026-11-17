package net.tfminecraft.rpcharacters.blind;

import java.util.Set;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;

/** Reads a world's blocks as {@link Surroundings.Kind}s. Unloaded chunks read as solid, so nothing is loaded. */
public final class WorldProbe implements Surroundings.Probe {

	private static final Set<Material> HAZARDS = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
			Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
			Material.WITHER_ROSE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

	private final World world;

	public WorldProbe(World world) {
		this.world = world;
	}

	@Override
	public Surroundings.Kind kind(int x, int y, int z) {
		if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
			return Surroundings.Kind.SOLID;
		}
		return kindOf(world.getBlockAt(x, y, z));
	}

	static Surroundings.Kind kindOf(Block block) {
		Material type = block.getType();
		if (HAZARDS.contains(type)) {
			BlockData data = block.getBlockData();
			if (!(data instanceof Lightable lit) || lit.isLit()) return Surroundings.Kind.HAZARD;
		}
		if (Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type)) {
			return Surroundings.Kind.DOOR;
		}
		return block.isPassable() ? Surroundings.Kind.OPEN : Surroundings.Kind.SOLID;
	}
}
