package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class GraveManagerTest {
    @TempDir Path directory;
    RuntimeTestState state;
    ServerMock server;
    World world;
    PlayerMock player;
    RPCharacters plugin;
    Logger logger;
    GraveManager manager;
    Map<UUID, Grave> byId, oldIds;
    Map<String, Grave> byBlock, oldBlocks;
    MockedStatic<GraveLoader> settings;
    MockedStatic<GraveVisualManager> visuals;
    MockedStatic<LastSolidTracker> trackers;
    GraveVisualManager visual;
    LastSolidTracker tracker;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class);
        world = server.addSimpleWorld("graves"); player = server.addPlayer("Alex");
        plugin = mock(RPCharacters.class); logger = mock(Logger.class);
        when(plugin.getName()).thenReturn("RPCharacters"); when(plugin.namespace()).thenReturn("rpcharacters"); when(plugin.getDataFolder()).thenReturn(directory.toFile()); when(plugin.getLogger()).thenReturn(logger);
        RPCharacters.plugin = plugin; manager = GraveManager.get();
        Field ids = GraveManager.class.getDeclaredField("byId"), blocks = GraveManager.class.getDeclaredField("byBlock"); ids.setAccessible(true); blocks.setAccessible(true);
        byId = (Map<UUID, Grave>) ids.get(manager); byBlock = (Map<String, Grave>) blocks.get(manager);
        oldIds = new HashMap<>(byId); oldBlocks = new HashMap<>(byBlock); byId.clear(); byBlock.clear();
        settings = mockStatic(GraveLoader.class); settings.when(GraveLoader::getMaterial).thenReturn(Material.CHEST);
        visual = mock(GraveVisualManager.class); visuals = mockStatic(GraveVisualManager.class); visuals.when(GraveVisualManager::get).thenReturn(visual);
        tracker = mock(LastSolidTracker.class); trackers = mockStatic(LastSolidTracker.class); trackers.when(LastSolidTracker::get).thenReturn(tracker);
    }
    @AfterEach void restore() {
        trackers.close(); visuals.close(); settings.close();
        byId.clear(); byId.putAll(oldIds); byBlock.clear(); byBlock.putAll(oldBlocks);
        state.close(); MockBukkit.unmock();
    }
    Grave grave(int x, int y, int z) { return new Grave(UUID.randomUUID(), player.getUniqueId(), new Location(world, x, y, z)); }
    Grave dated(UUID owner, long created, Location location) {
        return new Grave(UUID.randomUUID(), owner, null, null, false, true, created, 0, null, null, null, null, null, location);
    }
    Path file(Grave grave) { return directory.resolve("graves").resolve(grave.getId() + ".json"); }
    JsonObject read(Grave grave) throws Exception { return JsonParser.parseString(Files.readString(file(grave))).getAsJsonObject(); }
    void write(String name, String json) throws Exception { Files.writeString(Files.createDirectories(directory.resolve("graves")).resolve(name), json); }
    JsonObject record(UUID id) {
        JsonObject json = new JsonObject(); json.addProperty("id", id.toString()); json.addProperty("owner", player.getUniqueId().toString());
        json.addProperty("world", world.getUID().toString()); json.addProperty("x", 4); json.addProperty("y", 64); json.addProperty("z", 8); return json;
    }
    void tag(Block block, String value) {
        TileState tile = (TileState) block.getState(); tile.getPersistentDataContainer().set(GraveKeys.graveId(), GraveKeys.GRAVE_ID_TYPE, value); tile.update();
    }
    ItemStack namedItem() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD, 1); var meta = item.getItemMeta();
        meta.setDisplayName("§aKeepsake é"); meta.setLore(List.of("One", "Two")); meta.setCustomModelData(77);
        meta.getPersistentDataContainer().set(new NamespacedKey("test", "marker"), PersistentDataType.STRING, "kept"); item.setItemMeta(meta); return item;
    }

    @Test void registrationAndTaggedBlockLookupTrackReplacementAndRemoval() {
        assertSame(manager, GraveManager.get()); assertNull(manager.getAt(null)); assertNull(manager.getById(null));
        manager.register(null); manager.register(mock(Grave.class)); manager.unregister(null); manager.save(null); manager.despawn(null);
        Grave first = grave(1, 64, 1); Block block = first.getBlockLocation().getBlock(); block.setType(Material.CHEST);
        manager.register(first); assertSame(first, manager.getAt(block)); assertTrue(manager.isGrave(block)); assertSame(first, manager.getById(first.getId()));
        manager.applyPdc(block, first); assertEquals(first.getId().toString(), ((TileState) block.getState()).getPersistentDataContainer().get(GraveKeys.graveId(), GraveKeys.GRAVE_ID_TYPE));
        assertSame(first, manager.getAt(block)); tag(block, " "); assertSame(first, manager.getAt(block));
        tag(block, "not-a-uuid"); assertSame(first, manager.getAt(block)); tag(block, UUID.randomUUID().toString()); assertSame(first, manager.getAt(block));
        Grave replacement = new Grave(first.getId(), first.getOwner(), new Location(world, 2, 64, 2)); manager.register(replacement);
        assertNull(manager.getAt(block)); assertSame(replacement, manager.getAt(replacement.getBlockLocation().getBlock()));
        manager.unregister(replacement); assertNull(manager.getById(first.getId())); assertNull(manager.getAt(replacement.getBlockLocation().getBlock()));
        manager.applyPdc(null, first); manager.applyPdc(block, null); manager.applyPdc(world.getBlockAt(8, 64, 8), first);
        Grave orphan = new Grave(UUID.randomUUID(), null, null); manager.register(orphan); manager.unregister(orphan); assertNull(manager.getById(orphan.getId()));
    }

    @Test void movingARegisteredGraveDoesNotLeaveAnOldBlockIndex() {
        Grave grave = grave(1, 64, 1); Block previous = grave.getBlockLocation().getBlock(); manager.register(grave);
        grave.setBlockLocation(new Location(world, 9, 64, 9)); manager.register(grave);
        assertNull(manager.getAt(previous), "Moving a grave must release its old block");
        assertSame(grave, manager.getAt(grave.getBlockLocation().getBlock()));
    }

    @Test void staleBlockTagsCannotExposeLootFromAMovedGrave() {
        Grave grave = grave(1, 64, 1); Block previous = grave.getBlockLocation().getBlock(); previous.setType(Material.CHEST);
        manager.save(grave); grave.setBlockLocation(new Location(world, 9, 64, 9)); manager.register(grave);
        assertNull(manager.getAt(previous), "An old tagged chest must not expose a grave at another location");
        assertSame(grave, manager.getAt(grave.getBlockLocation().getBlock()));
    }

    @Test void newestOwnerLookupIgnoresOtherPlayersAndMissingOwners() {
        assertNull(manager.findNewestByOwner(null)); assertNull(manager.findNewestByOwner(player.getUniqueId()));
        Grave newest = dated(player.getUniqueId(), 20, new Location(world, 2, 64, 2));
        manager.register(dated(player.getUniqueId(), 10, new Location(world, 1, 64, 1)));
        manager.register(dated(UUID.randomUUID(), 30, new Location(world, 3, 64, 3))); manager.register(newest);
        assertSame(newest, manager.findNewestByOwner(player.getUniqueId()));
    }

    @Test void nearbyLookupRequiresLoadedMatchingWorldPresentBlocksAndRadius() {
        Location center = new Location(world, 0, 64, 0); Grave near = grave(1, 64, 0), far = grave(20, 64, 0), absent = grave(2, 64, 0);
        for (Grave grave : List.of(near, far)) { grave.getBlockLocation().getBlock().setType(Material.CHEST); grave.getBlockLocation().getChunk().load(); manager.register(grave); }
        manager.register(absent); manager.register(new Grave(UUID.randomUUID(), player.getUniqueId(), null));
        manager.register(new Grave(UUID.randomUUID(), player.getUniqueId(), new Location(null, 0, 0, 0)));
        World other = server.addSimpleWorld("other"); manager.register(new Grave(UUID.randomUUID(), player.getUniqueId(), new Location(other, 0, 64, 0)));
        Grave unloaded = mock(Grave.class); Location location = mock(Location.class); Chunk chunk = mock(Chunk.class);
        when(unloaded.getId()).thenReturn(UUID.randomUUID()); when(unloaded.getBlockLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world); when(location.getChunk()).thenReturn(chunk); manager.register(unloaded);
        assertEquals(List.of(), manager.getGravesNear(null, 5)); assertEquals(List.of(), manager.getGravesNear(new Location(null, 0, 0, 0), 5));
        assertEquals(List.of(), manager.getGravesNear(center, 0)); assertEquals(List.of(near), manager.getGravesNear(center, 5));
        assertFalse(manager.isGraveBlockPresent(null)); assertFalse(manager.isGraveBlockPresent(new Grave(null, null, null)));
        assertFalse(manager.isGraveBlockPresent(new Grave(null, null, new Location(null, 0, 0, 0))));
        assertTrue(manager.isGraveBlockPresent(near)); assertFalse(manager.isGraveBlockPresent(absent));
        assertEquals(GraveManager.blockKey(near.getBlockLocation()), GraveManager.blockKey(near.getBlockLocation().getBlock()));
        assertEquals("", GraveManager.blockKey((Location) null)); assertEquals("", GraveManager.blockKey(new Location(null, 0, 0, 0)));
    }

    @Test void nonfiniteRadiusCannotExposeAllNearbyGraves() {
        Grave grave = grave(1, 64, 0); grave.getBlockLocation().getBlock().setType(Material.CHEST); grave.getBlockLocation().getChunk().load(); manager.register(grave);
        assertTrue(manager.getGravesNear(new Location(world, 0, 64, 0), Double.NaN).isEmpty());
        assertTrue(manager.getGravesNear(new Location(world, 0, 64, 0), Double.POSITIVE_INFINITY).isEmpty());
    }

    @Test void saveAndLoadPreserveItemMetadataOwnershipFlagsAndExperience() throws Exception {
        Grave grave = grave(4, 64, 8); grave.getBlockLocation().getBlock().setType(Material.CHEST);
        UUID killer = UUID.randomUUID(); grave.setKiller(killer); grave.setKillerDisplay("Kïller\n\\n"); grave.setProtected(true); grave.setLocked(false);
        grave.setExperience(31); grave.setHologramId(UUID.randomUUID()); ItemStack sword = namedItem();
        grave.setItem(0, sword); grave.setItem(36, new ItemStack(Material.DIAMOND_BOOTS)); grave.setItem(40, new ItemStack(Material.SHIELD)); grave.addExtra(new ItemStack(Material.EMERALD, 3));
        manager.save(grave); JsonObject json = read(grave); assertEquals(killer.toString(), json.get("killer").getAsString());
        assertFalse(json.has("hologramUuid")); assertEquals(36, json.getAsJsonArray("storage").size()); assertEquals(4, json.getAsJsonArray("armor").size());
        manager.loadAll(); Grave loaded = manager.getById(grave.getId()); assertNotNull(loaded); assertNotSame(grave, loaded);
        assertEquals(player.getUniqueId(), loaded.getOwner()); assertEquals(killer, loaded.getKiller()); assertEquals("Kïller\n\\n", loaded.getKillerDisplay());
        assertTrue(loaded.isProtected()); assertFalse(loaded.isLocked()); assertEquals(31, loaded.getExperience()); assertEquals(grave.getCreated(), loaded.getCreated());
        assertEquals(grave.getBlockLocation(), loaded.getBlockLocation()); assertEquals(sword, loaded.getItem(0));
        assertEquals("kept", loaded.getItem(0).getItemMeta().getPersistentDataContainer().get(new NamespacedKey("test", "marker"), PersistentDataType.STRING));
        assertEquals(Material.DIAMOND_BOOTS, loaded.getItem(36).getType()); assertEquals(Material.SHIELD, loaded.getItem(40).getType()); assertEquals(3, loaded.getExtras().getFirst().getAmount());
        visuals.verify(() -> GraveVisualManager.cleanupLegacyHologram(loaded)); manager.saveAll(); assertTrue(Files.isRegularFile(file(loaded)));
    }

    @Test void failedItemEncodingMustPreserveThePreviousSaveAndReportFailure() throws Exception {
        Grave grave = grave(4, 64, 8); grave.setItem(0, new ItemStack(Material.DIAMOND, 2)); manager.save(grave);
        String before = Files.readString(file(grave)); ItemStack unencodable = mock(ItemStack.class);
        when(unencodable.getType()).thenReturn(Material.DIAMOND); when(unencodable.serialize()).thenReturn(Map.of("invalid", new Object())); grave.getStorage()[0] = unencodable;
        assertDoesNotThrow(() -> manager.save(grave)); assertEquals(before, Files.readString(file(grave)), "A failed item encoding cannot overwrite saved loot");
        verify(logger).warning(contains("save grave")); assertSame(unencodable, grave.getItem(0));
    }

    @Test void saveFailuresAreLoggedAndNullOwnerOrLocationRecordsRemainRepresentable() throws Exception {
        Files.writeString(directory.resolve("graves"), "blocked parent"); Grave grave = grave(1, 64, 1);
        assertDoesNotThrow(() -> manager.save(grave)); verify(logger).warning(contains("Could not save grave"));
        Files.delete(directory.resolve("graves")); Grave orphan = new Grave(UUID.randomUUID(), null, null); manager.save(orphan);
        JsonObject json = read(orphan); assertFalse(json.has("owner")); assertFalse(json.has("world")); assertFalse(json.has("killer"));
        Grave sparse = mock(Grave.class); when(sparse.getId()).thenReturn(UUID.randomUUID()); when(sparse.getStorage()).thenReturn(null); when(sparse.getArmor()).thenReturn(null);
        manager.save(sparse); JsonObject sparseJson = read(sparse); assertEquals(0, sparseJson.getAsJsonArray("storage").size()); assertEquals(0, sparseJson.getAsJsonArray("armor").size());
    }

    @Test void diskWriteFailurePreservesPreviouslySavedLoot() throws Exception {
        Grave grave = grave(4, 64, 8); grave.setItem(0, new ItemStack(Material.DIAMOND, 2)); manager.save(grave);
        String before = Files.readString(file(grave)); Path folder = file(grave).getParent(); Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(folder);
        try {
            Files.setPosixFilePermissions(folder, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(file(grave), Set.of(PosixFilePermission.OWNER_READ));
            grave.setItem(0, new ItemStack(Material.EMERALD, 5)); assertDoesNotThrow(() -> manager.save(grave));
            assertEquals(before, Files.readString(file(grave))); verify(logger).warning(contains("Could not save grave"));
        } finally {
            Files.setPosixFilePermissions(folder, permissions);
            Files.setPosixFilePermissions(file(grave), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test void partialStagingWriteCannotReplaceSavedLootAndRemovesItsTemporaryFile() throws Exception {
        Grave grave = grave(4, 64, 8); grave.setItem(0, new ItemStack(Material.DIAMOND, 2)); manager.save(grave);
        String before = Files.readString(file(grave)); grave.setItem(0, new ItemStack(Material.EMERALD, 5));
        AtomicReference<Path> stage = new AtomicReference<>();
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("writeString")) {
                Path path = call.getArgument(0); stage.set(path); Files.write(path, "partial".getBytes(StandardCharsets.UTF_8));
                throw new IOException("disk full during staged write");
            }
            return call.callRealMethod();
        })) { assertDoesNotThrow(() -> manager.save(grave)); }
        assertNotNull(stage.get()); assertNotEquals(file(grave), stage.get()); assertEquals(before, Files.readString(file(grave)));
        assertFalse(Files.exists(stage.get())); verify(logger).warning(contains("disk full during staged write"));
        try (var children = Files.list(file(grave).getParent())) { assertEquals(List.of(file(grave)), children.toList()); }
    }

    @Test void unsupportedAtomicReplacementPreservesSavedLootAndRemovesStagingFile() throws Exception {
        Grave grave = grave(4, 64, 8); grave.setItem(0, new ItemStack(Material.DIAMOND, 2)); manager.save(grave);
        String before = Files.readString(file(grave)); grave.setItem(0, new ItemStack(Material.EMERALD, 5));
        AtomicReference<Path> stage = new AtomicReference<>();
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move")) {
                CopyOption[] options = (CopyOption[]) call.getRawArguments()[2];
                assertTrue(Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE));
                Path path = call.getArgument(0); stage.set(path);
                throw new AtomicMoveNotSupportedException(path.toString(), file(grave).toString(), "unsupported atomic move");
            }
            return call.callRealMethod();
        })) { assertDoesNotThrow(() -> manager.save(grave)); }
        assertNotNull(stage.get()); assertEquals(before, Files.readString(file(grave))); assertFalse(Files.exists(stage.get()));
        verify(logger).warning(contains("unsupported atomic move"));
    }

    @Test void failedStagingCleanupKeepsTheOriginalSaveAndBothIoFailureDetails() throws Exception {
        Grave grave = grave(4, 64, 8); grave.setItem(0, new ItemStack(Material.DIAMOND, 2)); manager.save(grave);
        String before = Files.readString(file(grave)); AtomicReference<Path> stage = new AtomicReference<>();
        IOException writeFailure = new IOException("disk full"), cleanupFailure = new IOException("cleanup denied");
        try {
            try (var files = mockStatic(Files.class, call -> {
                if (call.getMethod().getName().equals("writeString")) {
                    Path path = call.getArgument(0); stage.set(path); Files.write(path, "partial".getBytes(StandardCharsets.UTF_8)); throw writeFailure;
                }
                if (call.getMethod().getName().equals("deleteIfExists") && Objects.equals(stage.get(), call.getArgument(0))) throw cleanupFailure;
                return call.callRealMethod();
            })) { assertDoesNotThrow(() -> manager.save(grave)); }
            assertNotNull(stage.get()); assertEquals(before, Files.readString(file(grave))); assertEquals("partial", Files.readString(stage.get()));
            assertArrayEquals(new Throwable[]{cleanupFailure}, writeFailure.getSuppressed()); verify(logger).warning(contains("disk full"));
        } finally { if (stage.get() != null) Files.deleteIfExists(stage.get()); }
    }

    @Test void loadingSkipsMalformedMissingWorldAndInvalidIdentityRecords() throws Exception {
        manager.register(grave(1, 64, 1)); manager.loadAll(); assertTrue(byId.isEmpty());
        write("ignore.txt", "not JSON"); write("null.json", "null"); write("empty.json", "{}"); write("malformed.json", "{");
        JsonObject unknownWorld = record(UUID.randomUUID()); unknownWorld.addProperty("world", UUID.randomUUID().toString()); write("world.json", unknownWorld.toString());
        JsonObject malformedId = record(UUID.randomUUID()); malformedId.addProperty("id", "bad"); write("bad-id.json", malformedId.toString());
        JsonObject valid = record(UUID.randomUUID()); write("good.json", valid.toString()); manager.loadAll();
        assertEquals(1, byId.size()); Grave loaded = manager.getById(UUID.fromString(valid.get("id").getAsString())); assertNotNull(loaded); assertTrue(loaded.isLocked());
        assertEquals(0, loaded.getExtras().size()); assertNull(loaded.getItem(0)); verify(logger, times(2)).warning(startsWith("Could not load grave file"));
    }

    @Test void inaccessibleGravesDirectoryDoesNotThrow() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("graves")); Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(folder);
        try { Files.setPosixFilePermissions(folder, Set.of()); assertDoesNotThrow(manager::loadAll); assertTrue(byId.isEmpty()); }
        finally { Files.setPosixFilePermissions(folder, permissions); }
    }

    @Test void legacyItemRecordsTolerateAbsentCorruptAndNonItemEntries() throws Exception {
        JsonObject json = record(UUID.randomUUID()); UUID hologram = UUID.randomUUID(); json.addProperty("hologramUuid", hologram.toString()); json.addProperty("killer", "bad-uuid");
        JsonArray storage = new JsonArray(); storage.add(""); storage.add("not-base64"); storage.add(encoded("not an item")); json.add("storage", storage);
        JsonArray extras = new JsonArray(); extras.add(JsonNull.INSTANCE); extras.add(""); extras.add("bad"); extras.add(encoded(new ItemStack(Material.AIR))); extras.add(encoded(new ItemStack(Material.GOLD_INGOT, 2))); json.add("extras", extras);
        write("legacy.json", json.toString()); manager.loadAll(); Grave grave = manager.getById(UUID.fromString(json.get("id").getAsString()));
        assertNull(grave.getKiller()); assertEquals(hologram, grave.getHologramId()); assertTrue(Arrays.stream(grave.getStorage()).allMatch(Objects::isNull));
        assertEquals(1, grave.getExtras().size()); assertEquals(Material.GOLD_INGOT, grave.getExtras().getFirst().getType());
        json.addProperty("killer", " "); json.addProperty("hologramUuid", "bad"); write("legacy.json", json.toString()); manager.loadAll();
        assertNull(manager.getById(grave.getId()).getHologramId());
    }

    String encoded(Object value) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); BukkitObjectOutputStream stream = new BukkitObjectOutputStream(bytes)) {
            stream.writeObject(value); stream.flush(); return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    @Test void spawningGravesCopiesEveryInventoryRegionAndValidatesPlacement() {
        Block block = world.getBlockAt(1, 64, 1); assertNull(manager.spawn(null, block, null, null, 0, false, null, null, null, null));
        assertNull(manager.spawn(player, null, null, null, 0, false, null, null, null, null));
        Block denied = mock(Block.class); Chunk chunk = mock(Chunk.class); when(denied.getChunk()).thenReturn(chunk); when(denied.getType()).thenReturn(Material.STONE);
        assertNull(manager.spawn(player, denied, null, null, 0, false, null, null, null, null));
        ItemStack diamond = new ItemStack(Material.DIAMOND, 3), boots = new ItemStack(Material.IRON_BOOTS), shield = new ItemStack(Material.SHIELD), emerald = new ItemStack(Material.EMERALD, 2);
        UUID killer = UUID.randomUUID(); Grave grave = manager.spawn(player, block, killer, "Killer", 9, true,
            new ItemStack[]{diamond, null}, new ItemStack[]{boots, null}, shield, Arrays.asList(emerald, null));
        assertNotNull(grave); assertEquals(killer, grave.getKiller()); assertEquals("Killer", grave.getKillerDisplay()); assertTrue(grave.isLocked()); assertTrue(grave.isProtected()); assertEquals(9, grave.getExperience());
        assertSame(grave, manager.getAt(block)); assertNotSame(diamond, grave.getItem(0)); assertNotSame(boots, grave.getItem(36)); assertNotSame(shield, grave.getOffhand()); assertNotSame(emerald, grave.getExtras().getFirst());
        diamond.setAmount(1); emerald.setAmount(1); assertEquals(3, grave.getItem(0).getAmount()); assertEquals(2, grave.getExtras().getFirst().getAmount());
        assertNotNull(manager.spawn(player, world.getBlockAt(8, 64, 8), null, null, 0, false, null, null, null, null));
    }

    @Test void despawningRemovesOnlyGraveBlocksAndCleansIndexesVisualsAndFiles() throws Exception {
        Grave grave = grave(1, 64, 1); grave.getBlockLocation().getBlock().setType(Material.CHEST); manager.save(grave); manager.despawn(grave);
        assertEquals(Material.AIR, grave.getBlockLocation().getBlock().getType()); assertNull(manager.getById(grave.getId())); assertFalse(Files.exists(file(grave)));
        verify(visual).removeGrave(grave.getId()); visuals.verify(() -> GraveVisualManager.cleanupLegacyHologram(grave));
        Grave replaced = grave(2, 64, 2); replaced.getBlockLocation().getBlock().setType(Material.STONE); manager.register(replaced); manager.despawn(replaced);
        assertEquals(Material.STONE, replaced.getBlockLocation().getBlock().getType());
        Grave orphan = new Grave(UUID.randomUUID(), null, null); manager.register(orphan); manager.despawn(orphan); assertNull(manager.getById(orphan.getId()));
        Grave undeletable = grave(3, 64, 3); manager.register(undeletable); Files.createDirectories(file(undeletable)); Files.writeString(file(undeletable).resolve("child"), "preserve");
        manager.despawn(undeletable); verify(logger).warning(contains("Could not delete grave file")); assertTrue(Files.isDirectory(file(undeletable)));
    }

    @Test void emptyRemovalAndExpiryDropLootBeforeDespawningAndIgnoreDisabledTimers() {
        assertFalse(manager.removeIfEmpty(null)); Grave full = grave(1, 64, 1); full.setExperience(1); assertFalse(manager.removeIfEmpty(full));
        Grave empty = grave(2, 64, 2); manager.register(empty); assertTrue(manager.removeIfEmpty(empty)); assertNull(manager.getById(empty.getId()));
        assertFalse(manager.isExpired(null)); manager.expireGrave(null);
        Grave orphan = dated(player.getUniqueId(), 1, null); manager.register(orphan); manager.expireGrave(orphan); assertNull(manager.getById(orphan.getId()));
        try (var chunks = mockStatic(GraveChunkForceLoad.class); var loot = mockStatic(GraveLootDrop.class)) {
            chunks.when(() -> GraveChunkForceLoad.withForcedChunk(any(Location.class), any(Runnable.class))).thenAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; });
            manager.register(full); manager.expireGrave(full); loot.verify(() -> GraveLootDrop.dropAllToWorld(full, full.getBlockLocation().add(.5, .5, .5)));
            assertNull(manager.getById(full.getId())); manager.register(empty); manager.expireGrave(empty); assertNull(manager.getById(empty.getId()));
            Grave old = dated(player.getUniqueId(), 1, new Location(world, 3, 64, 3)); Grave fresh = grave(4, 64, 4); manager.register(old); manager.register(fresh);
            manager.expireOverdue(); assertSame(old, manager.getById(old.getId()));
            settings.when(GraveLoader::getExpireSeconds).thenReturn(60); assertTrue(manager.isExpired(old)); assertFalse(manager.isExpired(fresh)); manager.tickExpiry();
            assertNull(manager.getById(old.getId())); assertSame(fresh, manager.getById(fresh.getId()));
        }
    }

    @Test void placementUsesWaterColumnSupportsAndCachedSolidGround() {
        Block water = world.getBlockAt(1, 64, 1); water.setType(Material.WATER); assertEquals(water, manager.findChestBlock(player, water.getLocation()));
        water.setType(Material.BUBBLE_COLUMN); assertEquals(water, manager.findChestBlock(player, water.getLocation()));
        Block support = world.getBlockAt(4, 60, 4); support.setType(Material.STONE);
        assertEquals(support.getRelative(0, 1, 0), manager.findChestBlock(player, new Location(world, 4, 64, 4)));
        Block deepWater = world.getBlockAt(6, 60, 6); deepWater.setType(Material.WATER);
        assertEquals(deepWater, manager.findChestBlock(player, new Location(world, 6, 64, 6)));
        Block cached = world.getBlockAt(9, 60, 9); cached.setType(Material.STONE); when(tracker.getLastSolid(player)).thenReturn(cached.getLocation());
        assertEquals(cached.getRelative(0, 1, 0), manager.findChestBlock(player, null));
        cached.setType(Material.CHEST); cached.getRelative(0, 1, 0).setType(Material.STONE); assertEquals(cached, manager.findChestBlock(player, null));
    }

    @Test void occupiedPreferredLocationsUseNearbyFreeSpaceWithoutReplacingOtherGraves() {
        Block water = world.getBlockAt(1, 64, 1); water.setType(Material.WATER); manager.register(grave(1, 64, 1));
        Block chosen = manager.findChestBlock(player, water.getLocation()); assertNotEquals(water, chosen); assertFalse(manager.isGrave(chosen));
        assertEquals(1, chosen.getLocation().distanceSquared(water.getLocation()));
        Block support = world.getBlockAt(8, 64, 8); support.setType(Material.STONE); support.getRelative(0, 1, 0).setType(Material.STONE);
        Block adjacent = manager.findChestBlock(player, support.getLocation()); assertNotEquals(support, adjacent); assertTrue(adjacent.getType().isAir());
    }

    @Test void enclosedWaterGravesRemainProtectedWhenNearbySpaceIsUnavailable() {
        Grid enclosed = new Grid(pos -> pos.equals("1:0:1") ? Material.WATER : Material.STONE);
        Grave existing = new Grave(UUID.randomUUID(), player.getUniqueId(), enclosed.location()); manager.register(existing);
        assertNull(manager.findChestBlock(player, enclosed.location()));
        assertSame(existing, manager.getAt(enclosed.at(1, 0, 1)));
    }

    @Test void blockedWaterBelowDeathFallsBackToLastKnownSolidGround() {
        Grid enclosed = new Grid(pos -> pos.equals("1:0:1") ? Material.WATER : pos.equals("1:1:1") ? Material.LAVA : Material.STONE);
        Grave existing = new Grave(UUID.randomUUID(), player.getUniqueId(), enclosed.location()); manager.register(existing);
        Block cached = world.getBlockAt(9, 60, 9); cached.setType(Material.STONE); when(tracker.getLastSolid(player)).thenReturn(cached.getLocation());
        assertEquals(cached.getRelative(0, 1, 0), manager.findChestBlock(player, new Location(enclosed.world, 1, 1, 1)));
        assertSame(existing, manager.getAt(enclosed.at(1, 0, 1)));
    }

    @Test void lastResortPlacementSearchesColumnThenChunkAndProtectsExistingGraves() {
        assertNull(manager.findChestBlock(null, null)); assertNull(manager.findChestBlock(player, new Location(null, 0, 0, 0)));
        Grid column = new Grid(pos -> pos.equals("1:0:1") ? Material.LAVA : pos.equals("1:1:1") ? Material.AIR : Material.STONE);
        assertEquals(column.at(1, 1, 1), manager.findChestBlock(player, column.location()));
        Grid chunk = new Grid(pos -> pos.equals("1:0:1") ? Material.LAVA : pos.equals("0:0:0") ? Material.AIR : Material.STONE);
        assertEquals(chunk.at(0, 0, 0), manager.findChestBlock(player, chunk.location()));
        Grid full = new Grid(pos -> Material.STONE); assertEquals(full.at(1, 0, 1), manager.findChestBlock(player, full.location()));
        manager.register(new Grave(UUID.randomUUID(), player.getUniqueId(), full.location())); assertNull(manager.findChestBlock(player, full.location()));
    }

    @Test void placementAtOrBeyondWorldHeightAlwaysReturnsAValidBlock() {
        int highest = world.getMaxHeight() - 1;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) world.getBlockAt(x, highest, z).setType(Material.STONE);
        for (int deathY : List.of(highest, world.getMaxHeight() + 5, world.getMinHeight() - 5)) {
            Block placed = assertDoesNotThrow(() -> manager.findChestBlock(player, new Location(world, 0, deathY, 0)));
            assertNotNull(placed); assertTrue(placed.getY() >= world.getMinHeight() && placed.getY() < world.getMaxHeight());
        }
    }

    /** A two-block-high world bounds exhaustive fallback searches. */
    static final class Grid {
        final World world = mock(World.class);
        final Chunk chunk = mock(Chunk.class);
        final Map<String, Block> blocks = new HashMap<>();
        final Function<String, Material> material;
        Grid(Function<String, Material> material) {
            this.material = material; when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getMinHeight()).thenReturn(0); when(world.getMaxHeight()).thenReturn(2);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> at(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            when(world.getBlockAt(any(Location.class))).thenAnswer(call -> { Location at = call.getArgument(0); return at(at.getBlockX(), at.getBlockY(), at.getBlockZ()); });
            when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk); when(chunk.isLoaded()).thenReturn(true);
        }
        Location location() { return new Location(world, 1, 0, 1); }
        Block at(int x, int y, int z) {
            String key = x + ":" + y + ":" + z; Block known = blocks.get(key); if (known != null) return known;
            Block block = mock(Block.class); blocks.put(key, block);
            when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(y); when(block.getZ()).thenReturn(z);
            when(block.getType()).thenReturn(material.apply(key)); when(block.getChunk()).thenReturn(chunk); when(block.getLocation()).thenReturn(new Location(world, x, y, z));
            when(block.getRelative(anyInt(), anyInt(), anyInt())).thenAnswer(call -> at(x + (int) call.getArgument(0), y + (int) call.getArgument(1), z + (int) call.getArgument(2)));
            return block;
        }
    }
}
