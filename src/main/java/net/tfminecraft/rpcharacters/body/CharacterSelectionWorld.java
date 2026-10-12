package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;

final class CharacterSelectionWorld {
    private static final String OWNER_FILE = ".rpcharacters-selection-room";
    private static final String OWNER_MARKER = "RPCharacters character selection room v1\n";
    private CharacterSelectionWorld() {}

    static Location create(String name) throws IOException {
        if (name == null || !name.matches("[a-z0-9_-]+")) throw new IOException("Invalid character selection world name");
        World world = Bukkit.getWorld(name);
        NamespacedKey marker = new NamespacedKey("rpcharacters", "selection_room");
        Path folder = Bukkit.getWorldContainer().toPath().resolve(name);
        Path ownerFile = folder.resolve(OWNER_FILE);
        boolean existing = world != null || Files.exists(folder);
        if (world == null && existing && (!Files.isRegularFile(ownerFile)
                || !OWNER_MARKER.equals(Files.readString(ownerFile)))) {
            throw new IOException("Existing world is not a character selection room: " + name);
        }
        if (world == null) world = new WorldCreator(name).generator(new EmptyGenerator()).generateStructures(false).createWorld();
        if (world == null) throw new IOException("Character selection world could not be created");
        if (existing && !Byte.valueOf((byte) 1).equals(world.getPersistentDataContainer().get(marker, PersistentDataType.BYTE))) {
            throw new IOException("Existing world is not a character selection room: " + name);
        }
        world.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(6000);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) world.getBlockAt(x, 63, z).setType(Material.SMOOTH_QUARTZ, false);
        }
        world.setSpawnLocation(0, 64, 0);
        world.save();
        Files.createDirectories(folder);
        Files.writeString(ownerFile, OWNER_MARKER);
        return new Location(world, .5, 64, .5);
    }

    private static final class EmptyGenerator extends ChunkGenerator {
        @Override public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}
