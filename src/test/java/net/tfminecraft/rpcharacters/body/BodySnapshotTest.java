package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

class BodySnapshotTest {
    /**
     * MockBukkit cannot write persistent data as bytes. This stand-in round-trips the mock container's values
     * the way Paper round-trips the NBT, so the body code above it runs unchanged.
     */
    static final BodyPdcCodec MOCK_PDC = new BodyPdcCodec() {
        @Override
        public byte[] write(PersistentDataContainer container) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(new HashMap<>(((PersistentDataContainerMock) container).serialize()));
            }
            return bytes.toByteArray();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void read(PersistentDataContainer target, byte[] bytes) throws IOException {
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                PersistentDataContainerMock.deserialize((Map<String, Object>) in.readObject()).copyTo(target, true);
            } catch (ClassNotFoundException error) {
                throw new IOException(error);
            }
        }
    };

    /** Paper's getRespawnLocation(false) returns the stored point; MockBukkit's always returns null. */
    static final class BodyPlayer extends PlayerMock {
        boolean forced;
        BodyPlayer(ServerMock server) {
            super(server, "Body", UUID.randomUUID());
        }

        @Override
        public void setRespawnLocation(Location location, boolean force) {
            forced = force;
            super.setRespawnLocation(location, force);
        }

        @Override
        public Location getRespawnLocation(boolean loadLocationAndValidate) {
            return getRespawnLocation();
        }
    }

    private org.mockito.MockedStatic<BodyRespawn> respawns;
    private ServerMock server;
    private BodyPlayer player;
    private World world;
    private final NamespacedKey bodyKey = new NamespacedKey("fixture", "injury");
    private final NamespacedKey accountKey = new NamespacedKey("fixture", "kit_claim");

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        respawns = mockStatic(BodyRespawn.class);
        player = new BodyPlayer(server);
        server.addPlayer(player);
        world = player.getWorld();
    }

    @AfterEach
    void cleanup() {
        respawns.close();
        MockBukkit.unmock();
    }

    @Test
    void roundTripsTheWholeBodyWithoutTouchingAccountData() throws Exception {
        Location location = new Location(world, 23.5, 75, -16.25, 42, -12);
        player.teleport(location);
        ItemStack ring = new ItemStack(Material.DIAMOND_HOE);
        ring.editMeta(meta -> {
            meta.setDisplayName("Ring Slot");
            meta.setCustomModelData(8);
            meta.getPersistentDataContainer().set(new NamespacedKey("mmoinventory", "guiitemid"),
                    PersistentDataType.STRING, "ring");
        });
        player.getInventory().setItem(9, ring);
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.getEnderChest().setItem(3, new ItemStack(Material.DIAMOND, 7));
        Location bed = new Location(world, 4, 70, 9);
        world.getBlockAt(bed).setType(Material.RED_BED);
        player.setRespawnLocation(bed, true);
        player.setLastDeathLocation(new Location(world, -40, 12, 300));
        player.setHealth(13);
        player.setAbsorptionAmount(0);
        player.setFoodLevel(8);
        player.setSaturation(2);
        player.setExhaustion(3);
        player.setLevel(9);
        player.setExp(.5f);
        player.setTotalExperience(120);
        player.getInventory().setHeldItemSlot(4);
        player.setFireTicks(17);
        player.setFreezeTicks(11);
        player.setRemainingAir(123);
        player.setFallDistance(6.5f);
        PotionEffect strength = new PotionEffect(PotionEffectType.STRENGTH, 400, 2, true, false, true);
        player.addPotionEffect(strength);
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0));
        player.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, "left_arm");
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.LONG, 25L);
        String saved = BodySnapshot.capture(player, Set.of(bodyKey), MOCK_PDC).serialize();

        Location room = new Location(world, 500, 100, 500);
        player.teleport(room);
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setRespawnLocation(null, true);
        player.setLastDeathLocation(null);
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setLevel(0);
        player.setVelocity(new Vector(1, 2, 3));
        player.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, "other_body");
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.LONG, 30L);
        player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 2));
        BodySnapshot body = BodySnapshot.deserialize(saved);
        body.apply(player, Set.of(bodyKey), MOCK_PDC);

        assertEquals(room, player.getLocation(), "applying a body never teleports");
        assertEquals(location, body.location());
        assertEquals(world.getName(), body.worldName());
        assertEquals(bed, player.getRespawnLocation());
        assertEquals(new Location(world, -40, 12, 300), player.getLastDeathLocation());
        assertEquals(ring, player.getInventory().getItem(9));
        assertEquals(Material.IRON_HELMET, player.getInventory().getHelmet().getType());
        assertEquals(Material.IRON_CHESTPLATE, player.getInventory().getChestplate().getType());
        assertEquals(Material.IRON_LEGGINGS, player.getInventory().getLeggings().getType());
        assertEquals(Material.IRON_BOOTS, player.getInventory().getBoots().getType());
        assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
        assertEquals(7, player.getEnderChest().getItem(3).getAmount());
        assertEquals(13, player.getHealth());
        assertEquals(8, player.getFoodLevel());
        assertEquals(2, player.getSaturation());
        assertEquals(3, player.getExhaustion());
        assertEquals(9, player.getLevel());
        assertEquals(.5f, player.getExp());
        assertEquals(120, player.getTotalExperience());
        assertEquals(4, player.getInventory().getHeldItemSlot());
        assertEquals(17, player.getFireTicks());
        assertEquals(11, player.getFreezeTicks());
        assertEquals(123, player.getRemainingAir());
        assertEquals(6.5f, player.getFallDistance());
        assertEquals(new Vector(), player.getVelocity());
        assertEquals(strength, player.getPotionEffect(PotionEffectType.STRENGTH));
        assertEquals(strength.getHiddenPotionEffect(), player.getPotionEffect(PotionEffectType.STRENGTH).getHiddenPotionEffect());
        assertTrue(player.getPotionEffect(PotionEffectType.NIGHT_VISION).isInfinite());
        assertFalse(player.hasPotionEffect(PotionEffectType.POISON));
        assertEquals("left_arm", player.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING));
        assertEquals(30L, player.getPersistentDataContainer().get(accountKey, PersistentDataType.LONG));
        assertEquals(saved, body.serialize(), "a stored body re-serialises byte for byte");
        assertEquals(List.of("fixture:injury"), BodyJson.parse(saved).getAsJsonArray("persistentKeys").asList().stream()
                .map(JsonElement::getAsString).toList());
    }

    @Test
    void freshBodyStartsEmptyAndFull() throws Exception {
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(30);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getEnderChest().setItem(0, new ItemStack(Material.EMERALD, 64));
        player.setLevel(50);
        player.setRemainingAir(10);
        player.setHealth(4);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
        player.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, "old");
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.LONG, 42L);
        Location at = new Location(world, 10, 64, 10, 90, 0);
        BodySnapshot fresh = BodySnapshot.fresh(player, at);

        BodySnapshot.deserialize(fresh.serialize()).apply(player, Set.of(bodyKey));

        assertTrue(player.getInventory().isEmpty());
        assertTrue(player.getEnderChest().isEmpty());
        assertEquals(0, player.getLevel());
        assertEquals(30, player.getHealth());
        assertEquals(20, player.getFoodLevel());
        assertEquals(player.getMaximumAir(), player.getRemainingAir());
        assertTrue(player.getActivePotionEffects().isEmpty());
        assertNull(player.getRespawnLocation());
        assertFalse(player.getPersistentDataContainer().has(bodyKey));
        assertEquals(42L, player.getPersistentDataContainer().get(accountKey, PersistentDataType.LONG));
        assertEquals(at, fresh.location());
        assertEquals(player.getInventory().getContents().length, slots(fresh, "inventory"));
        assertEquals(player.getEnderChest().getContents().length, slots(fresh, "enderChest"));
    }

    @Test
    void movesABodyWithoutChangingTheRest() throws Exception {
        player.getInventory().setItem(2, new ItemStack(Material.APPLE, 3));
        BodySnapshot original = BodySnapshot.capture(player, Set.of());
        Location elsewhere = new Location(world, -300, 80, 41, 10, 5);

        BodySnapshot moved = original.withLocation(elsewhere);

        assertEquals(elsewhere, moved.location());
        assertEquals(player.getLocation(), original.location());
        JsonObject left = BodyJson.parse(original.serialize());
        JsonObject right = BodyJson.parse(moved.serialize());
        left.remove("location");
        right.remove("location");
        assertEquals(left, right);
    }

    @Test
    void genuineRespawnRefillsTheFinalMaximumWithoutChangingAnyOtherBodyData() throws Exception {
        player.setHealth(4);
        player.getInventory().setItem(2, new ItemStack(Material.APPLE, 3));
        player.setLevel(7);
        BodySnapshot wounded = BodySnapshot.capture(player, Set.of());
        BodySnapshot respawned = wounded.withFullHealth();
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(38);
        BodySnapshot.deserialize(respawned.serialize()).apply(player, Set.of());
        assertEquals(38, player.getHealth());
        wounded.applyHealth(player);
        assertEquals(4, player.getHealth());
        JsonObject before = wounded.toJson(), after = respawned.toJson();
        before.remove("health"); after.remove("health"); assertEquals(before, after);
    }

    @Test
    void forcedSpawnPointAndOrdinaryBedKeepTheirDistinctRespawnRules() throws Exception {
        Location at = new Location(world, 20, 65, 30);
        player.setRespawnLocation(at, true);
        respawns.when(() -> BodyRespawn.forced(player)).thenReturn(true);
        BodySnapshot forced = BodySnapshot.capture(player, Set.of());
        player.setRespawnLocation(null, false);
        BodySnapshot.deserialize(forced.serialize()).apply(player, Set.of());
        assertEquals(at, player.getRespawnLocation(false)); assertTrue(player.forced);
        respawns.when(() -> BodyRespawn.forced(player)).thenReturn(false);
        BodySnapshot bed = BodySnapshot.capture(player, Set.of());
        bed.apply(player, Set.of()); assertFalse(player.forced);
        BodySnapshot.fresh(player, at).apply(player, Set.of()); assertNull(player.getRespawnLocation(false)); assertFalse(player.forced);
    }

    @Test
    void refusesPlacesABodyCannotHave() {
        World unloaded = mock(World.class);
        when(unloaded.getUID()).thenReturn(UUID.randomUUID());
        Location gone = new Location(unloaded, 0, 64, 0);
        for (Location bad : new Location[] { null, new Location(null, 0, 64, 0), gone,
                new Location(world, Double.NaN, 64, 0), new Location(world, 0, Double.POSITIVE_INFINITY, 0),
                new Location(world, 0, 64, 4.0E7), new Location(world, 0, 64, 0, Float.NaN, 0),
                new Location(world, 0, 64, 0, 0, Float.POSITIVE_INFINITY) }) {
            assertThrows(IllegalArgumentException.class, () -> BodySnapshot.fresh(player, bad), String.valueOf(bad));
        }
        BodySnapshot body = BodySnapshot.fresh(player, player.getLocation());
        assertThrows(IllegalArgumentException.class, () -> body.withLocation(new Location(world, Double.NaN, 0, 0)));
    }

    @Test
    void bodiesWithoutBodyDataNeedNoPersistentBytes() throws Exception {
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.LONG, 5L);
        BodySnapshot body = BodySnapshot.capture(player, Set.of(bodyKey));
        assertTrue(BodyJson.parse(body.serialize()).get("persistentData").isJsonNull());
        player.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, "scar");

        body.apply(player, Set.of(bodyKey));

        assertFalse(player.getPersistentDataContainer().has(bodyKey));
        assertEquals(5L, player.getPersistentDataContainer().get(accountKey, PersistentDataType.LONG));
    }

    @Test
    void storedBodyStaysReadableWhileItsWorldIsUnloaded() throws Exception {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        JsonObject json = full();
        json.getAsJsonObject("location").addProperty("world", "dungeon_7");
        BodySnapshot body = BodySnapshot.deserialize(BodyJson.GSON.toJson(json));

        assertEquals("dungeon_7", body.worldName());
        assertThrows(IOException.class, body::location);
        assertThrows(IOException.class, () -> body.apply(player, Set.of(bodyKey), MOCK_PDC));
        assertEquals(3, player.getInventory().getItem(0).getAmount());

        World dungeon = server.addSimpleWorld("dungeon_7");
        assertEquals(dungeon, body.location().getWorld());
    }

    @Test
    void pointsInMissingWorldsFallBackLikeVanilla() throws Exception {
        JsonObject json = full();
        json.getAsJsonObject("respawn").addProperty("world", "gone");
        json.getAsJsonObject("lastDeath").addProperty("world", "gone");
        player.setRespawnLocation(new Location(world, 1, 2, 3), true);
        player.setLastDeathLocation(new Location(world, 1, 2, 3));

        BodySnapshot.deserialize(BodyJson.GSON.toJson(json)).apply(player, Set.of(bodyKey), MOCK_PDC);

        assertNull(player.getRespawnLocation());
        assertNull(player.getLastDeathLocation());
    }

    @Test
    void rejectsEveryMalformedField() throws Exception {
        Map<String, Consumer<JsonObject>> breaks = new java.util.LinkedHashMap<>();
        breaks.put("format 2", json -> json.addProperty("format", 2));
        breaks.put("format text", json -> json.addProperty("format", "1"));
        breaks.put("format fraction", json -> json.addProperty("format", 1.5));
        breaks.put("extra field", json -> json.addProperty("bonus", 1));
        breaks.put("missing field", json -> json.remove("food"));
        breaks.put("inventory base64", json -> json.addProperty("inventory", "%%%"));
        breaks.put("inventory short", json -> json.addProperty("inventory", encode(new byte[3])));
        breaks.put("inventory empty", json -> json.addProperty("inventory", encode(ByteBuffer.allocate(5).put((byte) 1).putInt(0).array())));
        breaks.put("inventory huge", json -> json.addProperty("inventory", encode(ByteBuffer.allocate(5).put((byte) 1).putInt(257).array())));
        breaks.put("inventory truncated", json -> json.addProperty("inventory", encode(ByteBuffer.allocate(9).put((byte) 1).putInt(2).putInt(0).array())));
        breaks.put("inventory slot past end", json -> json.addProperty("inventory", encode(ByteBuffer.allocate(9).put((byte) 1).putInt(1).putInt(9).array())));
        breaks.put("inventory negative slot", json -> json.addProperty("inventory", encode(ByteBuffer.allocate(9).put((byte) 1).putInt(1).putInt(-1).array())));
        breaks.put("inventory trailing", json -> json.addProperty("inventory", encode(ByteBuffer.allocate(10).put((byte) 1).putInt(1).putInt(0).array())));
        breaks.put("ender chest", json -> json.add("enderChest", JsonNull.INSTANCE));
        breaks.put("held slot high", json -> json.addProperty("heldSlot", 9));
        breaks.put("held slot low", json -> json.addProperty("heldSlot", -1));
        breaks.put("location type", json -> json.addProperty("location", "spawn"));
        breaks.put("location field", json -> json.getAsJsonObject("location").remove("pitch"));
        breaks.put("location world blank", json -> json.getAsJsonObject("location").addProperty("world", " "));
        breaks.put("location world number", json -> json.getAsJsonObject("location").addProperty("world", 4));
        breaks.put("location far", json -> json.getAsJsonObject("location").addProperty("x", 4.0E7));
        breaks.put("location yaw", json -> json.getAsJsonObject("location").addProperty("yaw", "north"));
        breaks.put("location y array", json -> json.getAsJsonObject("location").add("y", new JsonArray()));
        breaks.put("respawn", json -> json.getAsJsonObject("respawn").addProperty("z", true));
        breaks.put("last death", json -> json.add("lastDeath", new JsonArray()));
        breaks.put("respawn flag", json -> json.addProperty("respawnForced", "true"));
        breaks.put("health negative", json -> json.addProperty("health", -1));
        breaks.put("health huge", json -> json.addProperty("health", 2000));
        breaks.put("health text", json -> json.addProperty("health", "full"));
        breaks.put("health overflow", json -> json.add("health", new JsonPrimitive(new java.math.BigDecimal("1e400"))));
        breaks.put("absorption", json -> json.addProperty("absorption", -1));
        breaks.put("food high", json -> json.addProperty("food", 21));
        breaks.put("food fraction", json -> json.addProperty("food", 1.5));
        breaks.put("saturation", json -> json.addProperty("saturation", 21));
        breaks.put("exhaustion", json -> json.addProperty("exhaustion", 41));
        breaks.put("level", json -> json.addProperty("level", -1));
        breaks.put("exp", json -> json.addProperty("exp", 1.5));
        breaks.put("total exp", json -> json.addProperty("totalExp", -1));
        breaks.put("fire ticks", json -> json.addProperty("fireTicks", 1.0E12));
        breaks.put("freeze ticks", json -> json.addProperty("freezeTicks", -1));
        breaks.put("air", json -> json.addProperty("remainingAir", "lots"));
        breaks.put("fall", json -> json.addProperty("fallDistance", -1));
        breaks.put("effects type", json -> json.addProperty("effects", 1));
        breaks.put("effect not object", json -> json.getAsJsonArray("effects").add(1));
        breaks.put("effect field", json -> effect(json).remove("icon"));
        breaks.put("effect key", json -> effect(json).addProperty("type", "Not A Key"));
        breaks.put("effect key empty", json -> effect(json).addProperty("type", ""));
        breaks.put("effect duration", json -> effect(json).addProperty("duration", -2));
        breaks.put("effect amplifier", json -> effect(json).addProperty("amplifier", 256));
        breaks.put("effect ambient", json -> effect(json).addProperty("ambient", "yes"));
        breaks.put("effect particles", json -> effect(json).addProperty("particles", 1));
        breaks.put("effect icon", json -> effect(json).add("icon", new JsonObject()));
        breaks.put("hidden effect", json -> effect(json).getAsJsonObject("hidden").addProperty("amplifier", -1));
        breaks.put("duplicate effect", json -> json.getAsJsonArray("effects").add(effect(json).deepCopy()));
        breaks.put("deep effects", json -> {
            JsonObject innermost = effect(json);
            for (int depth = 0; depth < 9; depth++) {
                JsonObject hidden = innermost.deepCopy();
                hidden.add("hidden", JsonNull.INSTANCE);
                innermost.add("hidden", hidden);
                innermost = hidden;
            }
        });
        breaks.put("persistent bytes", json -> json.addProperty("persistentData", "%%%"));
        breaks.put("persistent number", json -> json.addProperty("persistentData", 5));
        breaks.put("persistent keys type", json -> json.add("persistentKeys", JsonNull.INSTANCE));
        breaks.put("persistent key number", json -> json.getAsJsonArray("persistentKeys").add(3));
        breaks.put("persistent key invalid", json -> json.getAsJsonArray("persistentKeys").add("no spaces:allowed"));
        breaks.put("persistent key too long once namespaced", json -> json.getAsJsonArray("persistentKeys").add("a".repeat(32767)));
        breaks.put("persistent key not canonical", json -> json.getAsJsonArray("persistentKeys").add("zebra"));
        breaks.put("persistent key unsorted", json -> json.getAsJsonArray("persistentKeys").add("aaa:first"));
        breaks.put("persistent key repeated", json -> json.getAsJsonArray("persistentKeys").add("fixture:injury"));
        breaks.put("persistent key tag", json -> {
            json.getAsJsonArray("persistentKeys").remove(0);
            json.getAsJsonArray("persistentKeys").add(BodyTag.KEY.toString());
        });
        for (Map.Entry<String, Consumer<JsonObject>> broken : breaks.entrySet()) {
            JsonObject json = full();
            broken.getValue().accept(json);
            assertThrows(IOException.class, () -> BodySnapshot.deserialize(BodyJson.GSON.toJson(json)), broken.getKey());
        }
        for (String text : List.of("bad: [", "{} {}", "[]", "", "{\"format\":NaN}", "{'format':1}")) {
            assertThrows(IOException.class, () -> BodySnapshot.deserialize(text), text);
        }
    }

    @Test
    void applyChecksEverythingBeforeChangingThePlayer() throws Exception {
        Map<String, Consumer<JsonObject>> breaks = new java.util.LinkedHashMap<>();
        breaks.put("world", json -> json.getAsJsonObject("location").addProperty("world", "elsewhere"));
        breaks.put("inventory size", json -> json.addProperty("inventory", encode(emptyItems(40))));
        breaks.put("ender chest size", json -> json.addProperty("enderChest", encode(emptyItems(26))));
        breaks.put("item bytes", json -> json.addProperty("inventory", encode(garbageItems(player.getInventory().getContents().length))));
        breaks.put("effect type", json -> effect(json).addProperty("type", "fixture:not_an_effect"));
        breaks.put("hidden effect type", json -> effect(json).getAsJsonObject("hidden").addProperty("type", "fixture:nope"));
        breaks.put("persistent bytes", json -> json.addProperty("persistentData", encode(new byte[] { 1, 2, 3 })));
        breaks.put("unapproved persistent key", json -> {
            PersistentDataContainerMock other = new PersistentDataContainerMock();
            other.set(accountKey, PersistentDataType.LONG, 99L);
            try {
                json.addProperty("persistentData", encode(MOCK_PDC.write(other)));
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
        Location before = player.getLocation();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getEnderChest().setItem(1, new ItemStack(Material.EMERALD, 2));
        player.setLevel(12);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
        player.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, "kept");
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.LONG, 7L);
        for (Map.Entry<String, Consumer<JsonObject>> broken : breaks.entrySet()) {
            JsonObject json = full();
            broken.getValue().accept(json);
            BodySnapshot body = BodySnapshot.deserialize(BodyJson.GSON.toJson(json));
            assertThrows(IOException.class, () -> body.apply(player, Set.of(bodyKey), MOCK_PDC), broken.getKey());
            assertUnchanged(before);
        }
        BodySnapshot good = BodySnapshot.deserialize(BodyJson.GSON.toJson(full()));
        player.setItemOnCursor(new ItemStack(Material.STICK));
        assertThrows(IOException.class, () -> good.apply(player, Set.of(bodyKey), MOCK_PDC));
        player.setItemOnCursor(null);
        assertThrows(IllegalArgumentException.class, () -> good.apply(player, Set.of(bodyKey, BodyTag.KEY), MOCK_PDC));
        assertUnchanged(before);
    }

    @Test
    void captureNeedsTheInventoryClosed() throws Exception {
        player.setItemOnCursor(new ItemStack(Material.STICK));
        assertThrows(IOException.class, () -> BodySnapshot.capture(player, Set.of()));
        player.closeInventory();

        player.openInventory(server.createInventory(null, 9));
        assertThrows(IOException.class, () -> BodySnapshot.capture(player, Set.of()));
        player.closeInventory();

        Inventory grid = server.createInventory(null, 9);
        grid.setItem(1, new ItemStack(Material.OAK_PLANKS));
        player.openInventory(new SimpleInventoryViewMock(player, grid, player.getInventory(), InventoryType.CRAFTING));
        assertThrows(IOException.class, () -> BodySnapshot.capture(player, Set.of()));
        grid.clear();
        assertNotNull(BodySnapshot.capture(player, Set.of()));

        assertThrows(IllegalArgumentException.class, () -> BodySnapshot.capture(player, Set.of(BodyTag.KEY)));
    }

    @Test
    void captureClampsOddValuesFromOtherPlugins() throws Exception {
        player.setFoodLevel(25);
        player.setSaturation(30);
        player.setExhaustion(50);

        player.setFreezeTicks(0);
        player.setFallDistance(-2);

        JsonObject json = BodyJson.parse(BodySnapshot.capture(player, Set.of()).serialize());

        assertEquals(20, json.get("food").getAsInt());
        assertEquals(20, json.get("saturation").getAsFloat());
        assertEquals(40, json.get("exhaustion").getAsFloat());
        assertEquals(0, json.get("level").getAsInt());
        assertEquals(0, json.get("fallDistance").getAsFloat());
        BodySnapshot.deserialize(BodyJson.GSON.toJson(json));
    }

    @Test
    void captureFailsClosedWhenTheLivePlayerCannotBeSaved() throws Exception {
        Player broken = mockPlayer(new Location(world, 0, 64, 0), new ItemStack[] { unsavableItem() });
        assertThrows(IOException.class, () -> BodySnapshot.capture(broken, Set.of()));

        Player nowhere = mockPlayer(new Location(world, Double.NaN, 64, 0), new ItemStack[41]);
        assertThrows(IOException.class, () -> BodySnapshot.capture(nowhere, Set.of()));
    }

    @Test
    void healthIsCappedAtTheCurrentMaximum() throws Exception {
        JsonObject json = full();
        json.addProperty("health", 13);
        BodySnapshot hurt = BodySnapshot.deserialize(BodyJson.GSON.toJson(json));
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(10);
        hurt.applyHealth(player);
        assertEquals(10, player.getHealth());
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20);
        hurt.applyHealth(player);
        assertEquals(13, player.getHealth());

        json.addProperty("health", 0);
        BodySnapshot.deserialize(BodyJson.GSON.toJson(json)).applyHealth(player);
        assertEquals(0.01, player.getHealth(), 1e-9);
    }

    @Test
    void paperBytesPassThroughUnchanged() throws Exception {
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        byte[] bytes = { 10, 0, 0, 0 };
        when(container.serializeToBytes()).thenReturn(bytes);
        assertSame(bytes, BodyPdcCodec.PAPER.write(container));
        BodyPdcCodec.PAPER.read(container, bytes);
        verify(container).readFromBytes(bytes, true);

        doThrow(new IllegalStateException("bad nbt")).when(container).serializeToBytes();
        doThrow(new IllegalArgumentException("bad nbt")).when(container).readFromBytes(bytes, true);
        assertThrows(IOException.class, () -> BodyPdcCodec.PAPER.write(container));
        assertThrows(IOException.class, () -> BodyPdcCodec.PAPER.read(container, bytes));
    }

    @Test
    void equalBodiesSerialiseIdentically() throws Exception {
        BodySnapshot body = BodySnapshot.deserialize(BodyJson.GSON.toJson(full()));
        BodySnapshot same = BodySnapshot.deserialize(body.serialize());
        BodySnapshot other = body.withLocation(new Location(world, 1, 2, 3));
        assertEquals(body, same);
        assertEquals(body.hashCode(), same.hashCode());
        assertNotEquals(body, other);
        assertNotEquals(body, body.serialize());
        assertEquals(BodyJson.parse(body.serialize()), body.toJson());
    }


    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hiddenPotionEffectsSurviveAndExcessiveDepthFailsClosed() throws Exception {
        // MockBukkit strips hidden effects from its accessor; exercise Paper's values through a delegate.
        PotionEffect hidden = new PotionEffect(PotionEffectType.STRENGTH, 1200, 0);
        PotionEffect active = new PotionEffect(PotionEffectType.STRENGTH, 400, 2, true, false, true, hidden);
        Player source = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(List.of(active)).when(source).getActivePotionEffects();
        BodySnapshot saved = BodySnapshot.capture(source, Set.of());
        Player target = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        saved.apply(target, Set.of());
        org.mockito.ArgumentCaptor<java.util.Collection<PotionEffect>> effects =
                org.mockito.ArgumentCaptor.forClass((Class) java.util.Collection.class);
        verify(target).addPotionEffects(effects.capture());
        PotionEffect restored = effects.getValue().iterator().next();
        assertEquals(active, restored);
        assertEquals(hidden, restored.getHiddenPotionEffect());
        assertNull(restored.getHiddenPotionEffect().getHiddenPotionEffect());

        PotionEffect deep = hidden;
        for (int i = 0; i < 9; i++) {
            deep = new PotionEffect(PotionEffectType.STRENGTH, 400, i, true, true, true, deep);
        }
        doReturn(List.of(deep)).when(source).getActivePotionEffects();
        assertThrows(IOException.class, () -> BodySnapshot.capture(source, Set.of()),
                "A capture must not silently discard potion state");
    }

    @Test
    void persistentDataKeepsTypesAndNeverMovesTheAccountBodyTag() throws Exception {
        NamespacedKey bytes = new NamespacedKey("fixture", "bytes");
        NamespacedKey ints = new NamespacedKey("fixture", "ints");
        NamespacedKey longs = new NamespacedKey("fixture", "longs");
        Set<NamespacedKey> keys = Set.of(bodyKey, bytes, ints, longs);
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(bodyKey, PersistentDataType.DOUBLE, 12.125);
        data.set(bytes, PersistentDataType.BYTE_ARRAY, new byte[] {0, 1, -1});
        data.set(ints, PersistentDataType.INTEGER_ARRAY, new int[] {3, Integer.MAX_VALUE});
        data.set(longs, PersistentDataType.LONG_ARRAY, new long[] {Long.MIN_VALUE, 4});
        UUID character = UUID.randomUUID();
        BodyTag.live(player, character, 5);
        BodySnapshot saved = BodySnapshot.capture(player, keys, MOCK_PDC);
        for (NamespacedKey key : keys) data.remove(key);
        BodyTag.room(player, character);
        saved.apply(player, keys, MOCK_PDC);
        assertEquals(new BodyTag.Room(character), BodyTag.read(player));
        assertEquals(12.125, data.get(bodyKey, PersistentDataType.DOUBLE));
        assertArrayEquals(new byte[] {0, 1, -1}, data.get(bytes, PersistentDataType.BYTE_ARRAY));
        assertArrayEquals(new int[] {3, Integer.MAX_VALUE}, data.get(ints, PersistentDataType.INTEGER_ARRAY));
        assertArrayEquals(new long[] {Long.MIN_VALUE, 4}, data.get(longs, PersistentDataType.LONG_ARRAY));
    }

    /** A body with every optional field present, captured from the mock player. */
    private JsonObject full() throws IOException {
        PlayerMock source = new BodyPlayer(server);
        server.addPlayer(source);
        Location bed = new Location(world, 4, 70, 9);
        world.getBlockAt(bed).setType(Material.RED_BED);
        source.setRespawnLocation(bed, true);
        source.setLastDeathLocation(new Location(world, 8, 9, 10));
        source.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 400, 2, true, false, true,
                new PotionEffect(PotionEffectType.STRENGTH, 1200, 0)));
        source.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, "scar");
        source.getInventory().setItem(5, new ItemStack(Material.BREAD, 4));
        JsonObject captured = BodyJson.parse(BodySnapshot.capture(source, Set.of(bodyKey), MOCK_PDC).serialize());
        // MockBukkit drops hidden effects when returning active effects.
        JsonObject hidden = effect(captured).deepCopy();
        hidden.addProperty("duration", 1200);
        hidden.addProperty("amplifier", 0);
        hidden.add("hidden", JsonNull.INSTANCE);
        effect(captured).add("hidden", hidden);
        return captured;
    }

    private void assertUnchanged(Location before) {
        assertEquals(before, player.getLocation());
        assertEquals(3, player.getInventory().getItem(0).getAmount());
        assertEquals(2, player.getEnderChest().getItem(1).getAmount());
        assertEquals(12, player.getLevel());
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals("kept", player.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING));
        assertEquals(7L, player.getPersistentDataContainer().get(accountKey, PersistentDataType.LONG));
    }

    private Player mockPlayer(Location location, ItemStack[] contents) {
        Player mock = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(mock.getItemOnCursor()).thenReturn(ItemStack.empty());
        when(mock.getOpenInventory()).thenReturn(view);
        when(view.getType()).thenReturn(InventoryType.CRAFTING);
        when(mock.getLocation()).thenReturn(location);
        when(mock.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(contents);
        return mock;
    }

    private static ItemStack unsavableItem() {
        ItemStack item = mock(ItemStack.class);
        when(item.isEmpty()).thenReturn(false);
        when(item.serializeAsBytes()).thenThrow(new IllegalStateException("unsavable"));
        return item;
    }

    private static JsonObject effect(JsonObject json) {
        return json.getAsJsonArray("effects").get(0).getAsJsonObject();
    }

    private static int slots(BodySnapshot body, String key) throws IOException {
        return BodySnapshot.slots(BodyJson.bytes(BodyJson.parse(body.serialize()), key));
    }

    private static byte[] emptyItems(int slots) {
        return ByteBuffer.allocate(5 + 4 * slots).put((byte) 1).putInt(slots).array();
    }

    private static byte[] garbageItems(int slots) {
        ByteBuffer buffer = ByteBuffer.allocate(5 + 4 * slots + 3).put((byte) 1).putInt(slots).putInt(3).put(new byte[] { 1, 2, 3 });
        return buffer.array();
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
