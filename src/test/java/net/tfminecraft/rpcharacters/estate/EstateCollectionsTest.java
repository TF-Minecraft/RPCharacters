package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Body;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.tlibs.character.CharacterRef;

class EstateCollectionsTest {
    @TempDir Path root;
    private ServerMock server;
    private FlakyFiles files;
    private EstateTestKit kit;
    private EstateCollections collections;
    private PlayerMock player;
    private final CharacterRef kira = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private final List<Integer> saves = new ArrayList<>();

    /** Stacks are one byte naming a material, so tests can see exactly what was handed over. */
    private static final Material[] KINDS = {Material.DIAMOND, Material.GOLD_INGOT, Material.ENDER_PEARL, Material.BREAD};

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        files = new FlakyFiles();
        kit = new EstateTestKit(root, files);
        collections = new EstateCollections(kit.book, bytes -> {
            if (bytes[0] < 0) throw new IllegalArgumentException("unreadable item");
            return new ItemStack(KINDS[bytes[0]]);
        }, kit.pushes::add, kit.logger);
        player = new PlayerMock(server, "Kira", kira.ownerId()) {
            @Override
            public void saveData() {
                saves.add(getInventory().firstEmpty());
            }
        };
        server.addPlayer(player);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private EstateRecord collection(Named to, int... kinds) throws Exception {
        EstateRecord record = new EstateRecord(UUID.randomUUID(), new CharacterRef(UUID.randomUUID(), UUID.randomUUID()));
        record.name = "Aria";
        record.createdAt = kit.clock.instant();
        record.dueAt = kit.clock.instant();
        record.body = new Body();
        record.body.state = "COLLECTABLE";
        for (int kind : kinds) record.body.stacks.add(Base64.getEncoder().encodeToString(new byte[] {(byte) kind}));
        record.body.addressedTo = to;
        kit.book.save(record);
        kit.clock.advance(java.time.Duration.ofSeconds(1));
        return record;
    }

    @Test
    void stacksGoIntoFreeSlotsOneBatchAtATimeAndTheRestWaitsDurably() throws Exception {
        EstateRecord record = collection(new Named(kira, "Kira"), 0, 1, 2, 3);
        assertEquals(List.of(record.deathId), collections.waitingFor(kira.characterId()).stream().map(r -> r.deathId).toList());
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.DIRT));
        assertEquals("Make room in your inventory, then collect again.", collections.collect(player, kira.characterId()));
        player.getInventory().setItem(4, null);
        player.getInventory().setItem(20, null);
        String first = collections.collect(player, kira.characterId());
        assertTrue(first.contains("You collected 2 stacks from the estate of Aria; 2 more wait"), first);
        assertEquals(Material.DIAMOND, player.getInventory().getItem(4).getType());
        assertEquals(Material.GOLD_INGOT, player.getInventory().getItem(20).getType());
        EstateRecord left = kit.book.get(record.deathId).orElseThrow();
        assertEquals(2, left.body.stacks.size());
        assertEquals(2, left.body.delivered);
        assertEquals(1, saves.size());
        String log = Files.readString(root.resolve("estates/collections.log"));
        assertTrue(log.contains(record.deathId + " recipient " + kira.characterId()), log);
        assertEquals(List.of(record.testator.ownerId()), kit.pushes);
        player.getInventory().clear();
        assertEquals("You collected 2 stacks from the estate of Aria.", collections.collect(player, kira.characterId()));
        assertTrue(collections.waitingFor(kira.characterId()).isEmpty());
        assertEquals("You have no inheritance to collect.", collections.collect(player, kira.characterId()));
    }

    @Test
    void aFailedSaveHandsNothingOverSoNothingCanBeDuplicated() throws Exception {
        EstateRecord record = collection(new Named(kira, "Kira"), 0);
        files.failWrites = true;
        assertTrue(collections.collect(player, kira.characterId()).contains("nothing was handed over"));
        assertTrue(player.getInventory().isEmpty());
        assertEquals(1, kit.book.get(record.deathId).orElseThrow().body.stacks.size());
        assertTrue(saves.isEmpty());
        files.failWrites = false;
        assertEquals("You collected 1 stack from the estate of Aria.", collections.collect(player, kira.characterId()));
    }

    @Test
    void anUnreadableStackStopsTheBatchBeforeAnythingIsWritten() throws Exception {
        EstateRecord record = collection(new Named(kira, "Kira"), 0, -1);
        assertTrue(collections.collect(player, kira.characterId()).contains("needs staff attention"));
        assertEquals(2, kit.book.get(record.deathId).orElseThrow().body.stacks.size());
        assertTrue(player.getInventory().isEmpty());
    }

    @Test
    void staffCollectOnlyCollectionsNoCharacterCanCollect() throws Exception {
        EstateRecord addressed = collection(new Named(kira, "Kira"), 0);
        assertEquals("That collection belongs to Kira; they collect it themselves.", collections.collectAsStaff(player, addressed));
        EstateRecord parked = collection(null, 1);
        assertEquals("You collected 1 stack from the estate of Aria.", collections.collectAsStaff(player, parked));
        assertTrue(Files.readString(root.resolve("estates/collections.log")).contains("staff Kira"));
        assertEquals("That estate has nothing to collect.", collections.collectAsStaff(player, kit.book.get(parked.deathId).orElseThrow()));
        EstateRecord bare = new EstateRecord(UUID.randomUUID(), kira);
        assertEquals("That estate has nothing to collect.", collections.collectAsStaff(player, bare));
        bare.body = new Body();
        bare.body.state = "STRIPPING";
        assertEquals("That estate has nothing to collect.", collections.collectAsStaff(player, bare));
        assertTrue(collections.waitingFor(UUID.randomUUID()).isEmpty());
    }
}
