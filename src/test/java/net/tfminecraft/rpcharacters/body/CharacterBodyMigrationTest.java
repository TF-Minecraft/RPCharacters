package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CharacterBodyMigrationTest {
    @TempDir Path directory;
    ServerMock server;
    PlayerMock player;
    World world;
    PlayerData account;
    UUID owner;
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    final List<RPCharacter> roster = new ArrayList<>();
    Path root, manifest, bodyRoot;
    BodyStore store;
    BodySnapshot original;
    Location spawn;

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); world = server.addSimpleWorld("migration"); player = server.addPlayer("Alex");
        owner = player.getUniqueId(); player.teleport(new Location(world, 3, 70, 5));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 11)); player.setLevel(9);
        original = BodySnapshot.capture(player, Set.of(), BodySnapshotTest.MOCK_PDC);
        root = directory.resolve("plans"); bodyRoot = directory.resolve("bodies"); manifest = directory.resolve("heirs.yml");
        store = new BodyStore(bodyRoot, BodyFiles.DISK, Clock.systemUTC(), () -> 4445);
        spawn = new Location(world, 20, 65, 20);
        account = mock(PlayerData.class);
        when(account.getUniqueId()).thenReturn(owner); when(account.getPlayer()).thenReturn(player);
        when(account.getCharacters()).thenReturn(roster);
        when(account.getCharacterById(anyString())).thenAnswer(call -> roster.stream()
                .filter(character -> character.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        roster.add(character(alice, new Location(world, 40, 70, 40)));
        roster.add(character(bob, new Location(world, 80, 70, 80)));
        manifest(alice);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    RPCharacter character(UUID id, Location location) {
        RPCharacter result = mock(RPCharacter.class);
        when(result.getId()).thenReturn(id.toString()); when(result.getLastLocation()).thenReturn(location);
        return result;
    }

    void manifest(UUID heir) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("version", 1); yaml.createSection("heirs");
        if (heir != null) yaml.set("heirs." + owner, heir.toString());
        Files.writeString(manifest, yaml.saveToString());
    }

    String hash() throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifest))); }
    CharacterBodyMigration migration() throws Exception { return new CharacterBodyMigration(root, manifest, hash(), store, spawn); }
    Path planFile() { return root.resolve(owner + ".yml"); }
    Path bodyFile(UUID id) { return bodyRoot.resolve(owner.toString()).resolve(id + ".body"); }
    YamlConfiguration plan() throws Exception { YamlConfiguration yaml = new YamlConfiguration(); yaml.load(planFile().toFile()); return yaml; }

    @Test void manifestMustBeReviewedWellFormedAndCanonicalBeforeAnyPlanExists() throws Exception {
        assertThrows(IOException.class, () -> new CharacterBodyMigration(root, manifest, "wrong", store, spawn));
        assertFalse(Files.exists(root));
        for (String text : List.of("version: 2\nheirs: {}\n", "version: 1.5\nheirs: {}\n", "version: '1'\nheirs: {}\n",
                "version: 1\n", "version: 1\nheirs: []\n", "bad: [", "version: 1\nheirs:\n  bad: " + alice + "\n",
                "version: 1\nheirs:\n  " + owner + ": bad\n",
                "version: 1\nheirs:\n  " + owner + ": 1-1-1-1-1\n",
                "version: 1\nheirs:\n  " + owner + ": {}\n")) {
            Files.writeString(manifest, text);
            assertThrows(IOException.class, this::migration, text);
        }
        Files.delete(manifest); assertThrows(IOException.class, this::migration);
    }

    @Test void unavailableChecksumAlgorithmCannotAdmitAnUnreviewedManifest() throws Exception {
        String expected = hash();
        try (var digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256")).thenThrow(new NoSuchAlgorithmException("unavailable"));
            assertThrows(IllegalStateException.class, () -> new CharacterBodyMigration(root, manifest, expected, store, spawn));
        }
        assertFalse(Files.exists(root));
    }

    @Test void migrationGivesOnlyTheReviewedHeirTheRetainedInventoryAndOtherBodiesStartFresh() throws Exception {
        CharacterBodyMigration migration = migration(); assertFalse(migration.migrated(owner));
        assertEquals(alice, migration.heir(owner)); assertNull(migration.heir(UUID.randomUUID()));
        assertEquals(alice, migration.initialize(account, original));
        BodyRecord heir = store.read(owner, alice).orElseThrow(), other = store.read(owner, bob).orElseThrow();
        assertEquals(original, heir.body()); assertEquals(1, heir.rev());
        assertEquals(BodySnapshot.fresh(player, roster.get(1).getLastLocation()), other.body());
        assertNotEquals(original, other.body()); assertTrue(migration.migrated(owner));
        YamlConfiguration plan = plan(); assertEquals(original.serialize(), plan.getString("legacy-body"));
        assertEquals(Set.of(alice.toString(), bob.toString()), plan.getConfigurationSection("characters").getKeys(false));
        byte[] before = Files.readAllBytes(bodyFile(alice));
        assertEquals(alice, migration.initialize(account, original));
        assertArrayEquals(before, Files.readAllBytes(bodyFile(alice)), "Retry must not issue a new item revision");
        assertEquals(alice, migration.heir(owner));
    }

    @Test void missingOrStaleHeirsAndInvalidRostersFailBeforeTheImmutablePlanIsWritten() throws Exception {
        manifest(null); assertThrows(IOException.class, () -> migration().initialize(account, original));
        manifest(UUID.randomUUID()); assertThrows(IOException.class, () -> migration().initialize(account, original));
        roster.clear(); assertThrows(IOException.class, () -> migration().initialize(account, original));
        roster.add(character(alice, spawn)); manifest(alice);
        RPCharacter invalid = character(bob, spawn); when(invalid.getId()).thenReturn("invalid"); roster.add(invalid);
        assertThrows(IOException.class, () -> migration().initialize(account, original));
        when(invalid.getId()).thenReturn(null); assertThrows(IOException.class, () -> migration().initialize(account, original));
        when(invalid.getId()).thenReturn(alice.toString()); assertThrows(IOException.class, () -> migration().initialize(account, original));
        assertFalse(Files.exists(planFile())); assertFalse(Files.exists(bodyRoot));
    }

    @Test void newAccountsRetainTheirUnassignedLegacyBodyOutsidePlayableStorage() throws Exception {
        roster.clear(); manifest(null); CharacterBodyMigration migration = migration();
        assertNull(migration.initialize(account, original)); assertTrue(migration.migrated(owner));
        assertNull(migration.heir(owner)); assertTrue(plan().getConfigurationSection("characters").getKeys(false).isEmpty());
        Path quarantine;
        try (var files = Files.list(bodyRoot.resolve(owner.toString()).resolve("quarantine"))) { quarantine = files.findFirst().orElseThrow(); }
        assertEquals(original, store.readQuarantine(quarantine).body());
        assertTrue(store.read(owner, alice).isEmpty());
    }

    @Test void interruptionBetweenBodyWritesKeepsTheInitialRosterLocationsAndLegacyBodyForRetry() throws Exception {
        store = spy(store); AtomicInteger bobWrites = new AtomicInteger();
        doAnswer(call -> {
            YamlConfiguration staged = plan(); assertFalse(staged.getBoolean("complete"));
            assertEquals(original.serialize(), staged.getString("legacy-body"));
            if (bob.equals(call.getArgument(1)) && bobWrites.getAndIncrement() == 0) throw new IOException("interrupted");
            return call.callRealMethod();
        }).when(store).write(eq(owner), any(UUID.class), eq(0L), eq("migration"), any(BodySnapshot.class));
        CharacterBodyMigration migration = migration();
        assertThrows(IOException.class, () -> migration.initialize(account, original));
        assertFalse(migration.migrated(owner)); assertTrue(store.read(owner, alice).isPresent());
        Location initialBobLocation = roster.get(1).getLastLocation().clone();
        player.setMaximumAir(600);
        when(roster.get(1).getLastLocation()).thenReturn(spawn);
        UUID laterId = UUID.randomUUID(); RPCharacter later = character(laterId, new Location(world, 12, 70, 12)); roster.add(later);
        BodySnapshot differentInput = original.withLocation(spawn);
        assertEquals(alice, migration.initialize(account, differentInput));
        assertEquals(original, store.read(owner, alice).orElseThrow().body());
        assertEquals(initialBobLocation, store.read(owner, bob).orElseThrow().body().location());
        assertTrue(store.read(owner, laterId).isEmpty());
        migration.ensureBody(account, later);
        assertEquals(BodySnapshot.fresh(player, later.getLastLocation()), store.read(owner, laterId).orElseThrow().body());
        assertFalse(plan().contains("characters." + laterId));
    }

    @Test void failedCompletionWriteLeavesRetryablePlanAndDoesNotDuplicateBodyRevisions() throws Exception {
        CharacterBodyMigration migration = migration(); AtomicInteger replacements = new AtomicInteger();
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move") && planFile().equals(call.getArgument(1))
                    && replacements.incrementAndGet() == 2) {
                throw new AtomicMoveNotSupportedException("temporary", "plan", "simulated commit failure");
            }
            return call.callRealMethod();
        })) { assertThrows(IOException.class, () -> migration.initialize(account, original)); }
        assertFalse(plan().getBoolean("complete")); assertEquals(1, store.read(owner, alice).orElseThrow().rev());
        assertEquals(1, store.read(owner, bob).orElseThrow().rev());
        try (var files = Files.list(root)) { assertEquals(List.of(planFile()), files.toList()); }
        migration.initialize(account, original); assertTrue(migration.migrated(owner));
        assertEquals(1, store.read(owner, alice).orElseThrow().rev());
    }

    @Test void initialPlanWriteFailureCannotWriteAnyBodyOrLeaveATemporaryPlan() throws Exception {
        store = spy(store); CharacterBodyMigration migration = migration();
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move") && planFile().equals(call.getArgument(1))) throw new IOException("disk full");
            return call.callRealMethod();
        })) { assertThrows(IOException.class, () -> migration.initialize(account, original)); }
        verify(store, never()).write(any(), any(), anyLong(), anyString(), any());
        verify(store, never()).quarantine(any(), anyString(), any()); assertFalse(Files.exists(planFile()));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void completedPlanCannotResetMissingBodiesOrConsumeChangedUntaggedInventory() throws Exception {
        CharacterBodyMigration migration = migration(); migration.initialize(account, original);
        assertThrows(IOException.class, () -> migration.initialize(account, original.withLocation(spawn)));
        Files.delete(bodyFile(bob));
        assertThrows(IOException.class, () -> migration.initialize(account, original));
        assertThrows(IOException.class, () -> migration.ensureBody(account, roster.get(1)));
        assertFalse(Files.exists(bodyFile(bob)));
        store.write(owner, alice, 1, "logout", original.withLocation(spawn));
        assertThrows(IOException.class, () -> migration.initialize(account, original));
        YamlConfiguration incomplete = plan(); incomplete.set("complete", false); Files.writeString(planFile(), incomplete.saveToString());
        assertThrows(IOException.class, () -> migration.initialize(account, original));
        assertEquals(2, store.read(owner, alice).orElseThrow().rev());
    }

    @Test void existingBodiesWithoutTheirPlanCannotBeTreatedAsANewCutover() throws Exception {
        store.write(owner, alice, 0, "unexpected", original.withLocation(spawn));
        assertThrows(IOException.class, () -> migration().initialize(account, original));
        assertEquals(spawn, store.read(owner, alice).orElseThrow().body().location());
        assertFalse(Files.exists(planFile()));
    }

    @Test void aConflictingFirstBodyCannotBeAdoptedByAnIncompleteOrCompletedPlan() throws Exception {
        CharacterBodyMigration migration = migration(); migration.initialize(account, original);
        Files.delete(bodyFile(bob)); store.write(owner, bob, 0, "unexpected", original);
        assertThrows(IOException.class, () -> migration.initialize(account, original));
        YamlConfiguration incomplete = plan(); incomplete.set("complete", false); Files.writeString(planFile(), incomplete.saveToString());
        assertThrows(IOException.class, () -> migration.initialize(account, original));
        assertEquals(original, store.read(owner, bob).orElseThrow().body());
    }

    @Test void freshBodiesUseSavedLocationsOrTheConfiguredSpawnAndNeverReplaceExistingBodies() throws Exception {
        when(roster.get(1).getLastLocation()).thenReturn(null);
        CharacterBodyMigration migration = migration(); migration.initialize(account, original);
        assertEquals(spawn, store.read(owner, bob).orElseThrow().body().location());
        byte[] before = Files.readAllBytes(bodyFile(alice)); migration.ensureBody(account, roster.getFirst());
        assertArrayEquals(before, Files.readAllBytes(bodyFile(alice)));
        for (Location at : new Location[]{null, new Location(null, 10, 70, 10)}) {
            RPCharacter later = character(UUID.randomUUID(), at); migration.ensureBody(account, later);
            assertEquals(spawn, store.read(owner, UUID.fromString(later.getId())).orElseThrow().body().location());
        }
        RPCharacter invalid = character(UUID.randomUUID(), spawn); when(invalid.getId()).thenReturn("bad");
        assertThrows(IOException.class, () -> migration.ensureBody(account, invalid));
        YamlConfiguration incomplete = plan(); incomplete.set("complete", false); Files.writeString(planFile(), incomplete.saveToString());
        assertThrows(IOException.class, () -> migration.ensureBody(account, character(UUID.randomUUID(), spawn)));
    }

    @Test void corruptPlanSchemaCannotAuthorizeMigrationOrLegacyGraveOwnership() throws Exception {
        CharacterBodyMigration migration = migration(); migration.initialize(account, original); String valid = Files.readString(planFile());
        List<Consumer<YamlConfiguration>> corruptions = List.of(
                p -> p.set("version", 2), p -> p.set("version", 1.5), p -> p.set("owner", UUID.randomUUID().toString()),
                p -> p.set("complete", "true"), p -> p.set("heir", "invalid"), p -> p.set("legacy-body", "broken"), p -> p.set("fresh-body", "broken"),
                p -> p.set("characters", null), p -> p.set("characters", List.of("broken")),
                p -> p.set("characters.bad", spawn), p -> p.set("characters." + alice, "unavailable"),
                p -> p.set("characters." + alice, null));
        for (Consumer<YamlConfiguration> mutation : corruptions) {
            YamlConfiguration bad = new YamlConfiguration(); bad.loadFromString(valid); mutation.accept(bad);
            Files.writeString(planFile(), bad.saveToString());
            assertThrows(IOException.class, () -> migration.migrated(owner));
            assertNull(migration.heir(owner));
        }
        Files.writeString(planFile(), "bad: ["); assertThrows(IOException.class, () -> migration.initialize(account, original));
        Files.writeString(planFile(), valid); manifest(bob); CharacterBodyMigration changedHeir = migration();
        assertThrows(IOException.class, () -> changedHeir.migrated(owner)); assertNull(changedHeir.heir(owner));
        manifest(null); YamlConfiguration withoutHeir = plan(); withoutHeir.set("heir", null);
        Files.writeString(planFile(), withoutHeir.saveToString());
        assertThrows(IOException.class, () -> migration().migrated(owner));
    }
}
