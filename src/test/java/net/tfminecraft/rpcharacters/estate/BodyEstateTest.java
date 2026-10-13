package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.tfminecraft.rpcharacters.body.BodyConflictException;
import net.tfminecraft.rpcharacters.body.BodyRecord;
import net.tfminecraft.rpcharacters.body.BodySnapshot;
import net.tfminecraft.rpcharacters.body.BodyStore;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Body;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.Outcome;

class BodyEstateTest {
    @TempDir Path root;
    private EstateTestKit kit;
    private BodyStore store;
    private boolean leased;
    private boolean guard = true;
    private BodySnapshot.Kept kept = BodySnapshot.Kept.NONE;
    private BodyEstate participant;
    private CharacterRef aria;
    private CharacterRef kira;

    static byte[] layout(int slots, List<byte[]> items) {
        int size = 5 + 4 * slots + items.stream().mapToInt(i -> i.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(size).put((byte) 1).putInt(slots);
        for (int slot = 0; slot < slots; slot++) {
            byte[] item = slot < items.size() ? items.get(slot) : new byte[0];
            buffer.putInt(item.length).put(item);
        }
        return buffer.array();
    }

    static BodySnapshot body(List<byte[]> inventory, List<byte[]> ender) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("format", 1);
        data.addProperty("inventory", Base64.getEncoder().encodeToString(layout(41, inventory)));
        data.addProperty("enderChest", Base64.getEncoder().encodeToString(layout(27, ender)));
        data.addProperty("heldSlot", 0);
        JsonObject at = new JsonObject();
        at.addProperty("world", "world");
        for (String key : List.of("x", "y", "z", "yaw", "pitch")) at.addProperty(key, 0.0);
        data.add("location", at);
        data.add("respawn", JsonNull.INSTANCE);
        data.addProperty("respawnForced", false);
        data.add("lastDeath", JsonNull.INSTANCE);
        data.add("health", JsonNull.INSTANCE);
        data.addProperty("absorption", 0.0);
        data.addProperty("food", 20);
        data.addProperty("saturation", 5.0);
        data.addProperty("exhaustion", 0.0);
        data.addProperty("level", 0);
        data.addProperty("exp", 0.0);
        data.addProperty("totalExp", 0);
        data.addProperty("fireTicks", 0);
        data.addProperty("freezeTicks", 0);
        data.addProperty("remainingAir", 300);
        data.addProperty("fallDistance", 0.0);
        data.add("effects", new JsonArray());
        data.add("persistentData", JsonNull.INSTANCE);
        data.add("persistentKeys", new JsonArray());
        return BodySnapshot.deserialize(data.toString());
    }

    static final byte[] SWORD = {1, 2, 3};
    static final byte[] GOLD = {4, 5};
    static final byte[] PEARL = {6};

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        kit = new EstateTestKit(root);
        store = new BodyStore(root.resolve("bodies"));
        participant = participant(store);
        kit.world.participants.add(participant);
        kit.coordinator.bodies(participant);
        aria = kit.world.alive("Aria");
        kira = kit.world.alive("Kira");
        kit.world.kill(aria);
    }

    private BodyEstate participant(BodyStore bodies) {
        return new BodyEstate(kit.book, new BodyEstate.Bodies() {
            @Override
            public BodyStore store() {
                return bodies;
            }

            @Override
            public boolean hasLease(UUID owner, UUID character) {
                return leased;
            }
        }, decision -> guard, kept, kit.logger);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BodyRecord stow(CharacterRef ref, BodySnapshot snapshot) throws IOException {
        long rev = store.read(ref.ownerId(), ref.characterId()).map(BodyRecord::rev).orElse(0L);
        return store.write(ref.ownerId(), ref.characterId(), rev, "permadeath", snapshot);
    }

    private Outcome settle(UUID death) {
        return participant.settle(kit.book.get(death).orElseThrow().toDecision());
    }

    private EstateRecord record(UUID death) {
        return kit.book.get(death).orElseThrow();
    }

    @Test
    void theWholeStowedBodyIsStrippedIntoACollectionForTheHeir() throws IOException {
        stow(aria, body(List.of(SWORD, new byte[0], GOLD), List.of(PEARL)));
        leased = true;
        EstateRecord estate = kit.decided(aria, kira.characterId());
        assertEquals("RETRY", estate.participants.get(BodyEstate.ID).outcome());
        assertNull(estate.body);
        leased = false;
        Outcome done = settle(estate.deathId);
        assertEquals(Outcome.done(Map.of("stacks", 3), List.of("body:" + aria.ownerId() + "/" + aria.characterId())), done);
        EstateRecord stripped = record(estate.deathId);
        assertEquals("COLLECTABLE", stripped.body.state);
        assertEquals(new Named(kira, "Kira"), stripped.body.addressedTo);
        assertEquals(List.of(SWORD, GOLD, PEARL).stream().map(b -> Base64.getEncoder().encodeToString(b)).toList(),
                stripped.body.stacks);
        BodyRecord after = store.read(aria.ownerId(), aria.characterId()).orElseThrow();
        assertEquals(2, after.rev());
        assertEquals("estate:" + estate.deathId, after.reason());
        assertTrue(after.body().possessions(BodySnapshot.Kept.NONE).isEmpty());
        assertEquals(done, settle(estate.deathId));
        assertEquals(2, store.read(aria.ownerId(), aria.characterId()).orElseThrow().rev());
    }

    static BodySnapshot bodyOf(Map<Integer, org.bukkit.inventory.ItemStack> inventory, List<org.bukkit.inventory.ItemStack> ender)
            throws IOException {
        org.bukkit.inventory.ItemStack[] slots = new org.bukkit.inventory.ItemStack[41];
        inventory.forEach((slot, item) -> slots[slot] = item);
        BodySnapshot base = body(List.of(), List.of());
        com.google.gson.JsonObject data = com.google.gson.JsonParser.parseString(base.serialize()).getAsJsonObject();
        data.addProperty("inventory", Base64.getEncoder().encodeToString(org.bukkit.inventory.ItemStack.serializeItemsAsBytes(slots)));
        data.addProperty("enderChest", Base64.getEncoder().encodeToString(org.bukkit.inventory.ItemStack.serializeItemsAsBytes(
                ender.toArray(new org.bukkit.inventory.ItemStack[27]))));
        return BodySnapshot.deserialize(data.toString());
    }

    @Test
    void mmoInventorySlotPlaceholdersStayInTheBodyAsGravesLeaveThem() throws Exception {
        try (var tlibs = mockStatic(net.tfminecraft.tlibs.TLibs.class); var state = new net.tfminecraft.rpcharacters.RuntimeTestState(
                net.tfminecraft.rpcharacters.grave.GraveLoader.class)) {
            var items = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class, RETURNS_DEEP_STUBS);
            tlibs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(items);
            when(items.getChecker().checkItemWithPath(any(org.bukkit.inventory.ItemStack.class), anyString())).thenAnswer(call ->
                    ("v." + ((org.bukkit.inventory.ItemStack) call.getArgument(0)).getType().name().toLowerCase(java.util.Locale.ROOT))
                            .equals(call.getArgument(1)));
            new net.tfminecraft.rpcharacters.grave.GraveLoader().load(Path.of("src/main/resources/graves.yml").toFile());
            kept = net.tfminecraft.rpcharacters.grave.GraveLoader::keepOutOfGrave;
            participant = participant(store);
            var hoe = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_HOE);
            var ring = new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_NUGGET);
            var pearl = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ENDER_PEARL, 3);
            stow(aria, bodyOf(Map.of(0, hoe, 9, hoe, 10, ring, 11, hoe, 12, hoe), List.of(pearl)));
            kit.world.participants.clear();
            kit.world.participants.add(participant);
            EstateRecord estate = kit.decided(aria, kira.characterId());
            List<org.bukkit.inventory.ItemStack> collected = record(estate.deathId).body.stacks.stream()
                    .map(stack -> org.bukkit.inventory.ItemStack.deserializeBytes(Base64.getDecoder().decode(stack))).toList();
            assertEquals(List.of(hoe, ring, pearl), collected);
            BodyRecord stripped = store.read(aria.ownerId(), aria.characterId()).orElseThrow();
            var left = org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(
                    com.google.gson.JsonParser.parseString(stripped.body().serialize()).getAsJsonObject().get("inventory").getAsString()));
            assertEquals(hoe, left[9]);
            assertEquals(hoe, left[11]);
            assertEquals(hoe, left[12]);
            assertTrue(left[0] == null || left[0].isEmpty());
            assertTrue(left[10] == null || left[10].isEmpty());
            assertTrue(stripped.body().possessions(kept).isEmpty());
            BodyRecord previous = store.previous(aria.ownerId(), aria.characterId()).orElseThrow();
            assertEquals(3, previous.body().possessions(kept).size());
            assertEquals(6, previous.body().possessions(BodySnapshot.Kept.NONE).size());
            BodySnapshot.Kept broken = (slot, item) -> { throw new IllegalStateException("checker"); };
            assertThrows(IOException.class, () -> previous.body().possessions(broken));
        }
    }

    @Test
    void retriesWithoutABodyOrGuardAndLeavesFrozenPropertyAlone() throws IOException {
        EstateRecord estate = kit.decided(aria, kira.characterId());
        assertEquals(Outcome.retry("no stored body yet"), settle(estate.deathId));
        guard = false;
        assertEquals(Outcome.retry("estate guard does not hold"), settle(estate.deathId));
        guard = true;
        EstateDecision unknown = new EstateDecision(UUID.randomUUID(), aria, Optional.empty(), EstateTestKit.T0,
                Optional.empty(), Optional.empty(), Optional.empty(), net.tfminecraft.tlibs.character.estate.Recipient.FROZEN,
                net.tfminecraft.tlibs.character.estate.Recipient.FROZEN, net.tfminecraft.tlibs.character.estate.Recipient.FROZEN,
                net.tfminecraft.tlibs.character.estate.Recipient.SENDER, 0);
        assertEquals(Outcome.retry("no estate record"), participant.settle(unknown));
        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        stow(bran, body(List.of(SWORD), List.of()));
        EstateRecord frozen = kit.decided(bran, null);
        assertEquals(Outcome.done(), settle(frozen.deathId));
        assertEquals(1, store.read(bran.ownerId(), bran.characterId()).orElseThrow().rev());
        assertNull(record(frozen.deathId).body);
    }

    @Test
    void anEmptyBodyIsCollectableWithoutAWrite() throws IOException {
        stow(aria, body(List.of(), List.of()));
        EstateRecord estate = kit.decided(aria, kira.characterId());
        assertEquals("COLLECTABLE", record(estate.deathId).body.state);
        assertTrue(record(estate.deathId).body.stacks.isEmpty());
        assertEquals(1, store.read(aria.ownerId(), aria.characterId()).orElseThrow().rev());
        assertEquals(Outcome.done(Map.of("stacks", 0), List.of()), settle(estate.deathId));
    }

    private EstateRecord stripping(UUID death, long expectedRev) throws IOException {
        EstateRecord next = EstateBook.edit(record(death));
        next.body = new Body();
        next.body.state = "STRIPPING";
        next.body.expectedRev = expectedRev;
        next.body.stacks = new ArrayList<>(List.of(Base64.getEncoder().encodeToString(SWORD)));
        next.body.addressedTo = new Named(kira, "Kira");
        kit.book.save(next);
        return next;
    }

    @Test
    void aStripInterruptedBeforeTheBodyWriteIsRedoneAndOneAfterItIsOnlyRecorded() throws IOException {
        guard = false;
        EstateRecord estate = kit.decided(aria, kira.characterId());
        guard = true;
        BodyRecord before = stow(aria, body(List.of(SWORD), List.of()));
        stripping(estate.deathId, before.rev());
        settle(estate.deathId);
        assertEquals("COLLECTABLE", record(estate.deathId).body.state);
        assertEquals(before.rev() + 1, store.read(aria.ownerId(), aria.characterId()).orElseThrow().rev());

        stripping(estate.deathId, before.rev());
        assertEquals(Outcome.done(Map.of("stacks", 1), List.of("body:" + aria.ownerId() + "/" + aria.characterId())),
                settle(estate.deathId));
        assertEquals("COLLECTABLE", record(estate.deathId).body.state);
        assertEquals(before.rev() + 1, store.read(aria.ownerId(), aria.characterId()).orElseThrow().rev());

        stripping(estate.deathId, before.rev() - 1);
        assertEquals(Outcome.blocked("stored body moved on while it was being stripped"), settle(estate.deathId));
    }

    @Test
    void aStrippedBodyWithNoCollectionIsReDerivedFromItsPreviousRevisionOrBlocked() throws IOException {
        guard = false;
        EstateRecord estate = kit.decided(aria, kira.characterId());
        guard = true;
        BodyRecord before = stow(aria, body(List.of(GOLD), List.of(PEARL)));
        store.write(aria.ownerId(), aria.characterId(), before.rev(), "estate:" + estate.deathId, before.body().stripped(BodySnapshot.Kept.NONE));
        settle(estate.deathId);
        EstateRecord derived = record(estate.deathId);
        assertEquals("COLLECTABLE", derived.body.state);
        assertEquals(2, derived.body.stacks.size());
        assertEquals(before.rev(), derived.body.expectedRev);

        CharacterRef bran = kit.world.alive("Bran");
        kit.world.kill(bran);
        guard = false;
        EstateRecord lone = kit.decided(bran, kira.characterId());
        guard = true;
        BodyRecord first = store.write(bran.ownerId(), bran.characterId(), 0, "estate:" + lone.deathId, body(List.of(), List.of()));
        assertEquals(1, first.rev());
        assertEquals(Outcome.blocked("stripped body has no matching previous revision"), settle(lone.deathId));
    }

    @Test
    void aCorruptBodyIsBlockedAndStorageErrorsRetry() throws IOException {
        guard = false;
        EstateRecord estate = kit.decided(aria, kira.characterId());
        guard = true;
        stow(aria, body(List.of(SWORD), List.of()));
        Path file = root.resolve("bodies").resolve(aria.ownerId().toString()).resolve(aria.characterId() + ".body");
        Files.writeString(file, "broken");
        assertEquals(Outcome.Kind.BLOCKED, settle(estate.deathId).kind());

        BodyStore failing = mock(BodyStore.class);
        BodyEstate flaky = participant(failing);
        when(failing.read(any(), any())).thenThrow(new IOException("disk"));
        assertEquals(Outcome.retry("storage error: disk"), flaky.settle(record(estate.deathId).toDecision()));
        BodyRecord readable = new BodyRecord(aria.ownerId(), aria.characterId(), 1, 1, EstateTestKit.T0, "quit",
                body(List.of(SWORD), List.of()));
        reset(failing);
        when(failing.read(any(), any())).thenReturn(Optional.of(readable));
        when(failing.write(any(), any(), anyLong(), any(), any())).thenThrow(mock(BodyConflictException.class));
        EstateRecord cleared = EstateBook.edit(record(estate.deathId));
        cleared.body = null;
        kit.book.save(cleared);
        assertEquals(Outcome.retry("stored body changed during the strip"), flaky.settle(cleared.toDecision()));
    }

    @Test
    void aForcedReviveFollowsTheObservedBody() throws IOException {
        EstateRecord none = new EstateRecord(UUID.randomUUID(), aria);
        participant.forcedRevive(none);
        assertNull(none.body);
        guard = false;
        EstateRecord estate = kit.decided(aria, kira.characterId());
        guard = true;
        BodyRecord before = stow(aria, body(List.of(SWORD), List.of()));
        EstateRecord uncommitted = stripping(estate.deathId, before.rev());
        participant.forcedRevive(uncommitted);
        assertEquals("DISCARDED", uncommitted.body.state);
        assertTrue(uncommitted.body.stacks.isEmpty());
        participant.forcedRevive(uncommitted);
        assertEquals("DISCARDED", uncommitted.body.state);

        store.write(aria.ownerId(), aria.characterId(), before.rev(), "estate:" + estate.deathId, before.body().stripped(BodySnapshot.Kept.NONE));
        EstateRecord committed = stripping(estate.deathId, before.rev());
        participant.forcedRevive(committed);
        assertEquals("COLLECTABLE", committed.body.state);
        assertEquals(1, committed.body.stacks.size());

        EstateRecord elsewhere = stripping(estate.deathId, before.rev() - 1);
        participant.forcedRevive(elsewhere);
        assertEquals("STRIPPING", elsewhere.body.state);

        CharacterRef bran = kit.world.alive("Bran");
        EstateRecord bodiless = new EstateRecord(UUID.randomUUID(), bran);
        bodiless.body = new Body();
        bodiless.body.state = "STRIPPING";
        participant.forcedRevive(bodiless);
        assertEquals("STRIPPING", bodiless.body.state);
        BodyStore failing = mock(BodyStore.class);
        when(failing.read(any(), any())).thenThrow(new IOException("disk"));
        participant(failing).forcedRevive(bodiless);
        assertEquals("STRIPPING", bodiless.body.state);
    }

    @Test
    void anUncollectedInheritanceFollowsItsRecipientsOwnEstate() throws IOException {
        stow(aria, body(List.of(SWORD), List.of()));
        EstateRecord first = kit.decided(aria, kira.characterId());
        assertEquals(kira, record(first.deathId).body.addressedTo.ref());
        kit.world.kill(kira);
        CharacterRef lio = kit.world.alive("Lio");
        EstateRecord second = kit.decided(kira, null);
        assertTrue(second.decision.property().recipient().isFrozen());
        assertNull(record(first.deathId).body.addressedTo);
        assertEquals(second.deathId, record(first.deathId).body.readdressVia);
        settle(second.deathId);
        assertEquals(second.deathId, record(first.deathId).body.readdressVia);

        EstateRecord assigned = EstateBook.edit(record(second.deathId));
        Part toLio = Part.character(new Named(lio, "Lio"));
        kit.coordinator.revise(assigned, assigned.decision.revise(assigned.decision.money(), toLio, assigned.decision.guildLocks()),
                "test");
        assertEquals(new Named(lio, "Lio"), record(first.deathId).body.addressedTo);
        assertNull(record(first.deathId).body.readdressVia);
        settle(second.deathId);
        assertEquals(new Named(lio, "Lio"), record(first.deathId).body.addressedTo);
        assertTrue(participant.describe(aria.characterId()).labels().getFirst().contains("Ender chest"));
    }
}
