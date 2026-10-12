package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class CharacterSelectionWorldTest {
    @TempDir Path directory;
    final NamespacedKey marker = new NamespacedKey("rpcharacters", "selection_room");
    final AtomicReference<ChunkGenerator> generator = new AtomicReference<>();
    World world;
    Block block;
    PersistentDataContainerMock data;
    MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        MockBukkit.mock(); world = mock(World.class); block = mock(Block.class); data = new PersistentDataContainerMock();
        when(world.getPersistentDataContainer()).thenReturn(data); when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getWorldContainer).thenReturn(directory.toFile());
    }

    @AfterEach void cleanup() { bukkit.close(); MockBukkit.unmock(); }

    MockedConstruction<WorldCreator> creators(World created) {
        return mockConstruction(WorldCreator.class, (creator, context) -> {
            assertEquals("character_selection", context.arguments().getFirst());
            when(creator.generator(any(ChunkGenerator.class))).thenAnswer(call -> { generator.set(call.getArgument(0)); return creator; });
            when(creator.generateStructures(false)).thenReturn(creator); when(creator.createWorld()).thenReturn(created);
        });
    }

    @Test void createsAnOwnedVoidRoomInsideADarkBox() throws Exception {
        try (var creators = creators(world)) {
            Location spawn = CharacterSelectionWorld.create("character_selection");
            assertEquals(new Location(world, .5, 64, .5), spawn);
            assertEquals((byte) 1, data.get(marker, PersistentDataType.BYTE));
            verify(world).setGameRule(GameRule.DO_MOB_SPAWNING, false);
            verify(world).setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            verify(world).setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            verify(world).setGameRule(GameRule.MAX_ENTITY_CRAMMING, 0);
            verify(world).setTime(6000); verify(world).setSpawnLocation(0, 64, 0); verify(world).save();
            verify(block, times(11 * 11 * 5 - 9 * 9 * 3)).setType(Material.BLACK_CONCRETE, false);
            verify(block, times(9 * 9 * 3)).setType(Material.AIR, false);
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) { verify(world).getBlockAt(x, 63, z); verify(world).getBlockAt(x, 67, z); }
            for (int y = 63; y <= 67; y++) { verify(world).getBlockAt(5, y, 0); verify(world).getBlockAt(-5, y, 0); verify(world).getBlockAt(0, y, 5); verify(world).getBlockAt(0, y, -5); }
            assertTrue(Files.isRegularFile(directory.resolve("character_selection/.rpcharacters-selection-room")));
            ChunkGenerator.ChunkData empty = generator.get().generateChunkData(world, new Random(1), 5, -10, null);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) assertEquals(Material.AIR, empty.getType(x, 64, z));
            assertEquals(1, creators.constructed().size());
        }
    }

    @Test void onlyAnOwnedLoadedWorldCanBeReused() throws Exception {
        bukkit.when(() -> Bukkit.getWorld("character_selection")).thenReturn(world);
        try (var creators = creators(world)) {
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create("character_selection"));
            verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            data.set(marker, PersistentDataType.BYTE, (byte) 0);
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create("character_selection"));
            verify(world, never()).setGameRule(any(), any());
            data.set(marker, PersistentDataType.BYTE, (byte) 1);
            assertEquals(world, CharacterSelectionWorld.create("character_selection").getWorld());
            assertTrue(creators.constructed().isEmpty());
        }
    }

    @Test void anUnloadedDiskWorldMustBeRecognizedBeforeLoadingOrGeneratingAnyChunks() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("character_selection"));
        try (var creators = creators(world)) {
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create("character_selection"));
            Files.writeString(folder.resolve(".rpcharacters-selection-room"), "some other world");
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create("character_selection"));
            assertTrue(creators.constructed().isEmpty()); verifyNoInteractions(world);
        }
    }

    @Test void aSavedRoomReloadsOnRestartAndStillRequiresItsWorldMarker() throws Exception {
        try (var creators = creators(world)) { CharacterSelectionWorld.create("character_selection"); }
        clearInvocations(world, block);
        try (var creators = creators(world)) {
            assertEquals(world, CharacterSelectionWorld.create("character_selection").getWorld());
            assertEquals(1, creators.constructed().size());
        }
        data.remove(marker); clearInvocations(world, block);
        try (var creators = creators(world)) {
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create("character_selection"));
            verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test void invalidNamesAndWorldCreationFailuresCannotProduceARoom() {
        for (String name : new String[]{null, "", "../world", "Room", "world/other", "world name"}) {
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create(name));
        }
        try (var creators = creators(null)) {
            assertThrows(IOException.class, () -> CharacterSelectionWorld.create("character_selection"));
        }
        assertFalse(Files.exists(directory.resolve("character_selection")));
    }
}
