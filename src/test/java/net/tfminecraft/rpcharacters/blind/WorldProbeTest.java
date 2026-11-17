package net.tfminecraft.rpcharacters.blind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.tfminecraft.rpcharacters.blind.Surroundings.Kind;

class WorldProbeTest {
    World world;

    @BeforeEach void setup() {
        MockBukkit.mock();
        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    Block block(Material type, boolean passable, BlockData data) {
        Block b = mock(Block.class);
        when(b.getType()).thenReturn(type); when(b.isPassable()).thenReturn(passable); when(b.getBlockData()).thenReturn(data);
        return b;
    }

    @Test void outsideTheWorldAndUnloadedChunksReadSolid() {
        WorldProbe probe = new WorldProbe(world);
        assertEquals(Kind.SOLID, probe.kind(0, -65, 0));
        assertEquals(Kind.SOLID, probe.kind(0, 320, 0));
        when(world.isChunkLoaded(2, -1)).thenReturn(false);
        assertEquals(Kind.SOLID, probe.kind(40, 64, -3));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        Block air = block(Material.AIR, true, mock(BlockData.class));
        when(world.getBlockAt(1, 64, 1)).thenReturn(air);
        assertEquals(Kind.OPEN, probe.kind(1, 64, 1));
    }

    @Test void readsHazardsDoorsAndEverythingElse() {
        assertEquals(Kind.HAZARD, WorldProbe.kindOf(block(Material.LAVA, true, mock(BlockData.class))));
        Lightable lit = mock(Lightable.class); when(lit.isLit()).thenReturn(true);
        assertEquals(Kind.HAZARD, WorldProbe.kindOf(block(Material.CAMPFIRE, false, lit)));
        Lightable out = mock(Lightable.class);
        assertEquals(Kind.SOLID, WorldProbe.kindOf(block(Material.CAMPFIRE, false, out)), "an unlit campfire is just a block");
        assertEquals(Kind.DOOR, WorldProbe.kindOf(block(Material.OAK_DOOR, false, mock(BlockData.class))));
        assertEquals(Kind.DOOR, WorldProbe.kindOf(block(Material.IRON_TRAPDOOR, false, mock(BlockData.class))));
        assertEquals(Kind.DOOR, WorldProbe.kindOf(block(Material.OAK_FENCE_GATE, false, mock(BlockData.class))));
        assertEquals(Kind.SOLID, WorldProbe.kindOf(block(Material.STONE, false, mock(BlockData.class))));
        assertEquals(Kind.OPEN, WorldProbe.kindOf(block(Material.SHORT_GRASS, true, mock(BlockData.class))));
    }
}
