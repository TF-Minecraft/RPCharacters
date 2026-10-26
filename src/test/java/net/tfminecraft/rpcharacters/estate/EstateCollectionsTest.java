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
        assertEquals("That collection belongs to Kira; they collect it themselves.", collections.collectAsStaff(player, addressed, false));
        EstateRecord parked = collection(null, 1);
        assertEquals("You collected 1 stack from the estate of Aria.", collections.collectAsStaff(player, parked, false));
        assertTrue(Files.readString(root.resolve("estates/collections.log")).contains("staff Kira"));
        assertEquals("That estate has nothing to collect.", collections.collectAsStaff(player, kit.book.get(parked.deathId).orElseThrow(), false));
        EstateRecord bare = new EstateRecord(UUID.randomUUID(), kira);
        assertEquals("That estate has nothing to collect.", collections.collectAsStaff(player, bare, false));
        bare.body = new Body();
        bare.body.state = "STRIPPING";
        assertEquals("That estate has nothing to collect.", collections.collectAsStaff(player, bare, false));
        assertTrue(collections.waitingFor(UUID.randomUUID()).isEmpty());
        player.getInventory().clear();
        assertEquals("You collected 1 stack from the estate of Aria.", collections.collectAsStaff(player, addressed, true));
    }

    private EstateRecord estate(CharacterRef testator, net.tfminecraft.tlibs.character.estate.EstateState state) throws Exception {
        EstateRecord record = new EstateRecord(UUID.randomUUID(), testator);
        record.name = "Lyza";
        record.createdAt = kit.clock.instant();
        record.diedAt = kit.clock.instant();
        record.dueAt = kit.clock.instant().plus(java.time.Duration.ofHours(24));
        record.state = state;
        record.willHeir = kira.characterId();
        kit.book.save(record);
        return record;
    }

    private String collect() {
        return collections.collect(player, kira.characterId());
    }

    @Test
    void anHeirOnTheTestatorsAccountIsToldWhenTheEstatePasses() throws Exception {
        var open = net.tfminecraft.tlibs.character.estate.EstateState.OPEN;
        EstateRecord own = estate(new CharacterRef(kira.ownerId(), UUID.randomUUID()), open);
        kit.clock.advance(java.time.Duration.ofHours(7).plusMinutes(10));
        assertEquals("The estate of Lyza passes to you in about 17 hours. Collect it then.", collect());
        kit.clock.advance(java.time.Duration.ofHours(16).plusMinutes(10));
        assertEquals("The estate of Lyza passes to you in 40 minutes. Collect it then.", collect());
        kit.clock.advance(java.time.Duration.ofMinutes(41));
        assertEquals("The estate of Lyza is due and passes to you once it can be decided. Try again later.", collect());
        EstateRecord held = EstateBook.edit(own);
        held.state = net.tfminecraft.tlibs.character.estate.EstateState.HELD;
        kit.book.save(held);
        assertEquals("Staff are holding the estate of Lyza. It passes to you once they release it.", collect());
        EstateRecord revived = EstateBook.edit(held);
        revived.revivedAt = kit.clock.instant();
        kit.book.save(revived);
        assertEquals("You have no inheritance to collect.", collect());
    }

    @Test
    void aWillStaysSecretFromOtherAccountsAndOtherCharacters() throws Exception {
        var open = net.tfminecraft.tlibs.character.estate.EstateState.OPEN;
        estate(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()), open);
        assertEquals("You have no inheritance to collect.", collect());
        EstateRecord other = estate(new CharacterRef(kira.ownerId(), UUID.randomUUID()), open);
        EstateRecord renamed = EstateBook.edit(other);
        renamed.willHeir = UUID.randomUUID();
        kit.book.save(renamed);
        assertEquals("You have no inheritance to collect.", collect());
        EstateRecord cancelled = EstateBook.edit(renamed);
        cancelled.willHeir = kira.characterId();
        cancelled.state = net.tfminecraft.tlibs.character.estate.EstateState.CANCELLED;
        kit.book.save(cancelled);
        assertEquals("You have no inheritance to collect.", collect());
    }

    @Test
    void aDecidedEstateSaysTheBodyIsOnItsWayUntilItIsCollectable() throws Exception {
        EstateRecord record = estate(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()),
                net.tfminecraft.tlibs.character.estate.EstateState.OPEN);
        EstateRecord.Part to = EstateRecord.Part.character(new Named(kira, "Kira"));
        EstateRecord next = EstateBook.edit(record);
        next.state = net.tfminecraft.tlibs.character.estate.EstateState.DECIDED;
        next.decision = new EstateRecord.Decision(kit.clock.instant(), 2, new Named(kira, "Kira"), null, null,
                EstateRecord.Part.bank(new Named(kira, "Kira")), EstateRecord.Part.FROZEN, to, EstateRecord.Part.SENDER);
        kit.book.save(next);
        assertEquals("You have no inheritance to collect.", collect());
        next = EstateBook.edit(next);
        next.decision = new EstateRecord.Decision(kit.clock.instant(), 2, new Named(kira, "Kira"), null, null,
                EstateRecord.Part.bank(new Named(kira, "Kira")), to, to, EstateRecord.Part.SENDER);
        kit.book.save(next);
        // No answer from the body participant yet, as where character bodies are off.
        assertEquals("You have no inheritance to collect.", collect());
        next.participants.put(BodyEstate.ID, report("RETRY", 2));
        kit.book.save(next);
        assertEquals("The estate of Lyza is being handed over to you. Try again in a few minutes.", collect());
        next.participants.put(BodyEstate.ID, report("BLOCKED", 2));
        kit.book.save(next);
        assertEquals("The estate of Lyza needs staff before it can be handed over. Contact staff.", collect());
        next.participants.put(BodyEstate.ID, report("DONE", 2));
        kit.book.save(next);
        assertEquals("You have no inheritance to collect.", collect());
        next.participants.put(BodyEstate.ID, report("RETRY", 1));
        next.revivedAt = kit.clock.instant();
        kit.book.save(next);
        assertEquals("You have no inheritance to collect.", collect());
    }

    private EstateRecord.Report report(String outcome, long revision) {
        return new EstateRecord.Report(outcome, revision, null, java.util.Map.of(), List.of(), kit.clock.instant());
    }

    @Test
    void waitsReadInMinutesHoursOrDays() {
        assertEquals("in 1 minute", EstateCollections.in(java.time.Duration.ofSeconds(1)));
        assertEquals("in 1 minute", EstateCollections.in(java.time.Duration.ZERO));
        assertEquals("in 89 minutes", EstateCollections.in(java.time.Duration.ofMinutes(89)));
        assertEquals("in about 2 hours", EstateCollections.in(java.time.Duration.ofMinutes(90)));
        assertEquals("in about 47 hours", EstateCollections.in(java.time.Duration.ofHours(47)));
        assertEquals("in about 3 days", EstateCollections.in(java.time.Duration.ofHours(70)));
    }
}
