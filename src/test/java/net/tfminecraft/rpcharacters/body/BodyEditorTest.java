package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.rpcharacters.body.BodySnapshot.Container;

class BodyEditorTest {
    @TempDir
    Path temp;

    private PlayerMock player;
    private BodyStore store;
    private String blocked;
    private final List<UUID> asked = new ArrayList<>();
    private BodyEditor editor;
    /** Slots 9-12 are accessory slots; a placeholder is any non-diamond item there. */
    private final BodyEditor.Accessories accessories = new BodyEditor.Accessories() {
        @Override
        public boolean slot(int inventorySlot) {
            return inventorySlot >= 9 && inventorySlot <= 12;
        }

        @Override
        public boolean placeholder(byte[] item) {
            return ItemStack.deserializeBytes(item).getType() != Material.DIAMOND;
        }
    };
    private final UUID owner = UUID.randomUUID();
    private final UUID character = UUID.randomUUID();

    @BeforeEach
    void setup() throws IOException {
        player = MockBukkit.mock().addPlayer();
        store = new BodyStore(temp.resolve("bodies"));
        editor = new BodyEditor(store, (who, which) -> {
            asked.add(which);
            return blocked;
        }, accessories);
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(1, new ItemStack(Material.STICK));
        player.getInventory().setItem(39, new ItemStack(Material.IRON_HELMET));
        player.getEnderChest().setItem(5, new ItemStack(Material.EMERALD, 2));
        BodySnapshot body = BodySnapshot.capture(player, Set.of()).withLocation(new Location(player.getWorld(), 1, 64, 1));
        store.write(owner, character, 0, "quit", body);
    }

    @AfterEach
    void cleanup() {
        MockBukkit.unmock();
    }

    @Test
    void slotsReadAndChangeOnlyTheNamedSlot() throws Exception {
        BodySnapshot body = editor.read(owner, character).body();
        int slots = player.getInventory().getContents().length;
        assertEquals(slots, body.slotCount(Container.INVENTORY));
        assertEquals(27, body.slotCount(Container.ENDER_CHEST));
        assertEquals(Material.DIAMOND, item(body.slot(Container.INVENTORY, 0)).getType());
        assertEquals(0, body.slot(Container.INVENTORY, 2).length);

        BodySnapshot changed = body.withSlot(Container.INVENTORY, 1, new byte[0]);

        assertEquals(0, changed.slot(Container.INVENTORY, 1).length);
        assertArrayEquals(body.slot(Container.INVENTORY, 0), changed.slot(Container.INVENTORY, 0));
        assertArrayEquals(body.slot(Container.INVENTORY, 39), changed.slot(Container.INVENTORY, 39));
        assertArrayEquals(body.slot(Container.ENDER_CHEST, 5), changed.slot(Container.ENDER_CHEST, 5));
        assertEquals(body.location(), changed.location());
        assertEquals(changed, BodySnapshot.deserialize(changed.serialize()));
        assertThrows(IOException.class, () -> body.slot(Container.INVENTORY, slots));
        assertThrows(IOException.class, () -> body.withSlot(Container.ENDER_CHEST, -1, new byte[0]));
    }

    @Test
    void putFillsTheFirstEmptyStorageSlotAndKeepsThePreviousRevision() throws Exception {
        byte[] gold = new ItemStack(Material.GOLD_INGOT, 4).serializeAsBytes();

        BodyEditor.Change change = editor.put(owner, character, Container.INVENTORY, -1, gold, 1, "staff-put:Fran");

        assertEquals(2, change.slot());
        assertEquals(2, change.record().rev());
        assertEquals("staff-put:Fran", change.record().reason());
        BodyRecord stored = store.read(owner, character).orElseThrow();
        assertEquals(new ItemStack(Material.GOLD_INGOT, 4), item(stored.body().slot(Container.INVENTORY, 2)));
        assertEquals(1, store.previous(owner, character).orElseThrow().rev());
        assertEquals(List.of(character), asked);
    }

    @Test
    void putIntoAChosenSlotOfTheEnderChest() throws Exception {
        byte[] gold = new ItemStack(Material.GOLD_INGOT).serializeAsBytes();

        BodyEditor.Change change = editor.put(owner, character, Container.ENDER_CHEST, 6, gold, 1, "staff-put:Fran");

        assertEquals(6, change.slot());
        assertEquals(Material.GOLD_INGOT, item(store.read(owner, character).orElseThrow().body().slot(Container.ENDER_CHEST, 6)).getType());
    }

    @Test
    void autoNeverPicksArmourOrTheOffhand() throws Exception {
        BodySnapshot body = editor.read(owner, character).body();
        for (int slot = 0; slot < 36; slot++) {
            if (slot < 9 || slot > 12) body = body.withSlot(Container.INVENTORY, slot, new ItemStack(Material.DIRT).serializeAsBytes());
        }
        store.write(owner, character, 1, "full", body);

        assertRefused(() -> editor.put(owner, character, Container.INVENTORY, -1,
                new ItemStack(Material.GOLD_INGOT).serializeAsBytes(), 2, "staff-put:Fran"));
        assertEquals(0, store.read(owner, character).orElseThrow().body().slot(Container.INVENTORY, 40).length);
    }

    @Test
    void accessorySlotsTakeNoOrdinaryItemsAndGiveNoPlaceholders() throws Exception {
        BodySnapshot body = editor.read(owner, character).body()
                .withSlot(Container.INVENTORY, 2, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 3, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 4, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 5, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 6, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 7, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 8, new ItemStack(Material.DIRT).serializeAsBytes())
                .withSlot(Container.INVENTORY, 9, new ItemStack(Material.DIAMOND_HOE).serializeAsBytes())
                .withSlot(Container.INVENTORY, 11, new ItemStack(Material.DIAMOND).serializeAsBytes());
        store.write(owner, character, 1, "accessories", body);
        byte[] gold = new ItemStack(Material.GOLD_INGOT).serializeAsBytes();

        assertEquals(13, editor.put(owner, character, Container.INVENTORY, -1, gold, 2, "staff-put:Fran").slot());
        assertRefused(() -> editor.put(owner, character, Container.INVENTORY, 10, gold, 3, "staff-put:Fran"));
        assertRefused(() -> editor.take(owner, character, Container.INVENTORY, 9, bytes -> true, 3, "staff-take:Fran"));
        assertTrue(editor.accessorySlot(Container.INVENTORY, 10));
        assertFalse(editor.accessorySlot(Container.ENDER_CHEST, 10));

        BodyEditor.Change ring = editor.take(owner, character, Container.INVENTORY, 11, bytes -> true, 3, "staff-take:Fran");

        assertEquals(Material.DIAMOND, item(ring.item()).getType());
    }

    @Test
    void aWriteThatLandedBeforeADiskErrorStillCounts() throws Exception {
        BodyStoreTest.FaultyFiles files = new BodyStoreTest.FaultyFiles();
        BodyStore faulty = new BodyStore(temp.resolve("bodies"), files, java.time.Clock.systemUTC(), () -> 1);
        BodyEditor failing = new BodyEditor(faulty, (who, which) -> null, BodyEditor.Accessories.NONE);
        files.failOn("sync", 2);

        BodyEditor.Change change = failing.take(owner, character, Container.INVENTORY, 1, bytes -> true, 1, "staff-take:Fran");

        assertEquals(2, change.record().rev());
        assertEquals(Material.STICK, item(change.item()).getType());
        assertEquals(0, store.read(owner, character).orElseThrow().body().slot(Container.INVENTORY, 1).length);

        files.failOn("write", 1);
        assertThrows(IOException.class, () -> failing.take(owner, character, Container.INVENTORY, 0, bytes -> true, 2, "staff-take:Fran"));
        assertEquals(2, store.read(owner, character).orElseThrow().rev());
    }

    @Test
    void takeEmptiesTheSlotAndReturnsItsItem() throws Exception {
        BodyEditor.Change change = editor.take(owner, character, Container.INVENTORY, 1,
                bytes -> item(bytes).getType() == Material.STICK, 1, "staff-take:Fran");

        assertEquals(new ItemStack(Material.STICK), item(change.item()));
        BodySnapshot stored = store.read(owner, character).orElseThrow().body();
        assertEquals(0, stored.slot(Container.INVENTORY, 1).length);
        assertEquals(Material.DIAMOND, item(stored.slot(Container.INVENTORY, 0)).getType());
    }

    @Test
    void refusalsWriteNothing() throws Exception {
        byte[] before = Files.readAllBytes(bodyFile());
        byte[] gold = new ItemStack(Material.GOLD_INGOT).serializeAsBytes();

        assertRefused(() -> editor.put(owner, character, Container.INVENTORY, 0, gold, 1, "staff-put:Fran"));
        assertRefused(() -> editor.put(owner, character, Container.INVENTORY, -1, gold, 2, "staff-put:Fran"));
        assertRefused(() -> editor.take(owner, character, Container.INVENTORY, 2, bytes -> true, 1, "staff-take:Fran"));
        assertRefused(() -> editor.take(owner, character, Container.INVENTORY, 1,
                bytes -> item(bytes).getType() == Material.DIAMOND, 1, "staff-take:Fran"));
        assertRefused(() -> editor.take(owner, character, Container.INVENTORY, 1, bytes -> true, 0, "staff-take:Fran"));
        assertRefused(() -> editor.read(owner, UUID.randomUUID()));

        blocked = "The player is playing this character right now.";
        BodyEditor.Refused refused = assertRefused(() -> editor.take(owner, character, Container.INVENTORY, 1,
                bytes -> true, 1, "staff-take:Fran"));
        assertEquals(blocked, refused.getMessage());

        assertArrayEquals(before, Files.readAllBytes(bodyFile()));
        assertFalse(Files.exists(bodyFile().resolveSibling(character + ".body.prev")));
    }

    @Test
    void aBodyThatMovedOnAfterTheListingIsRefused() throws Exception {
        store.write(owner, character, 1, "quit", editor.read(owner, character).body());

        BodyEditor.Refused refused = assertRefused(() -> editor.take(owner, character, Container.INVENTORY, 1,
                bytes -> true, 1, "staff-take:Fran"));

        assertTrue(refused.getMessage().contains("version 2"), refused.getMessage());
    }

    private Path bodyFile() {
        return temp.resolve("bodies").resolve(owner.toString()).resolve(character + ".body");
    }

    private static ItemStack item(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }

    private interface Action {
        void run() throws IOException;
    }

    private static BodyEditor.Refused assertRefused(Action action) {
        return assertThrows(BodyEditor.Refused.class, action::run);
    }
}
