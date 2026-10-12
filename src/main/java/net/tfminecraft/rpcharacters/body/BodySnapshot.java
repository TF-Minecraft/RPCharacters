package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One character's vanilla body. Item bytes keep Paper's complete item components and custom data.
 * Stored bodies are checked strictly on read; worlds, effects and items are resolved only when used.
 */
public final class BodySnapshot {
    static final int FORMAT = 1;
    static final int MAX_SLOTS = 256;
    private static final int MAX_EFFECT_DEPTH = 8;
    private static final double MAX_COORDINATE = 3.2E7;
    private static final double MAX_HEALTH = 1024;
    private static final double MAX_ABSORPTION = 2048;
    private static final double MIN_APPLIED_HEALTH = 0.01;
    private static final Set<String> KEYS = Set.of("format", "inventory", "enderChest", "heldSlot", "location",
            "respawn", "respawnForced", "lastDeath", "health", "absorption", "food", "saturation", "exhaustion", "level", "exp",
            "totalExp", "fireTicks", "freezeTicks", "remainingAir", "fallDistance", "effects", "persistentData",
            "persistentKeys");
    private static final Set<String> LOCATION_KEYS = Set.of("world", "x", "y", "z", "yaw", "pitch");
    private static final Set<String> EFFECT_KEYS = Set.of("type", "duration", "amplifier", "ambient", "particles",
            "icon", "hidden");

    private final JsonObject data;
    private final String json;

    private BodySnapshot(JsonObject data) {
        this.data = data;
        this.json = BodyJson.GSON.toJson(data);
    }

    /** The caller closes the inventory first, so cursor and crafting-grid items are back in the body. */
    public static BodySnapshot capture(Player player, Set<NamespacedKey> bodyKeys) throws IOException {
        return capture(player, bodyKeys, BodyPdcCodec.PAPER);
    }

    static BodySnapshot capture(Player player, Set<NamespacedKey> bodyKeys, BodyPdcCodec codec) throws IOException {
        requireBodyKeys(bodyKeys);
        requireClosedInventory(player);
        Location location = player.getLocation();
        if (!placeable(location)) throw new IOException("Character body location is not a valid place");
        JsonObject data = new JsonObject();
        data.addProperty("format", FORMAT);
        data.addProperty("inventory", items(player.getInventory().getContents()));
        data.addProperty("enderChest", items(player.getEnderChest().getContents()));
        data.addProperty("heldSlot", player.getInventory().getHeldItemSlot());
        data.add("location", encode(location));
        // The raw point keeps bed semantics and avoids loading the bed's chunk.
        data.add("respawn", optional(player.getRespawnLocation(false)));
        data.addProperty("respawnForced", BodyRespawn.forced(player));
        data.add("lastDeath", optional(player.getLastDeathLocation()));
        data.addProperty("health", clamp(player.getHealth(), 0, MAX_HEALTH));
        data.addProperty("absorption", clamp(player.getAbsorptionAmount(), 0, MAX_ABSORPTION));
        data.addProperty("food", (int) clamp(player.getFoodLevel(), 0, 20));
        data.addProperty("saturation", (float) clamp(player.getSaturation(), 0, 20));
        data.addProperty("exhaustion", (float) clamp(player.getExhaustion(), 0, 40));
        data.addProperty("level", Math.max(player.getLevel(), 0));
        data.addProperty("exp", (float) clamp(player.getExp(), 0, 1));
        data.addProperty("totalExp", Math.max(player.getTotalExperience(), 0));
        data.addProperty("fireTicks", player.getFireTicks());
        data.addProperty("freezeTicks", Math.max(player.getFreezeTicks(), 0));
        data.addProperty("remainingAir", player.getRemainingAir());
        data.addProperty("fallDistance", (float) clamp(player.getFallDistance(), 0, MAX_COORDINATE));
        List<PotionEffect> active = new ArrayList<>(player.getActivePotionEffects());
        active.sort(Comparator.comparing(effect -> effect.getType().getKey().toString()));
        JsonArray effects = new JsonArray();
        for (PotionEffect effect : active) effects.add(encode(effect, 0));
        data.add("effects", effects);
        data.add("persistentData", persistentData(player, bodyKeys, codec));
        data.add("persistentKeys", keyList(bodyKeys));
        return new BodySnapshot(data);
    }

    /** Empty possessions sized like the template's inventories, and full resources. */
    public static BodySnapshot fresh(Player template, Location at) {
        JsonObject data = new JsonObject();
        data.addProperty("format", FORMAT);
        data.addProperty("inventory", BodyJson.encode(emptyItems(template.getInventory().getContents().length)));
        data.addProperty("enderChest", BodyJson.encode(emptyItems(template.getEnderChest().getContents().length)));
        data.addProperty("heldSlot", 0);
        data.add("location", place(at));
        data.add("respawn", JsonNull.INSTANCE);
        data.addProperty("respawnForced", false);
        data.add("lastDeath", JsonNull.INSTANCE);
        data.add("health", JsonNull.INSTANCE);
        data.addProperty("absorption", 0.0);
        data.addProperty("food", 20);
        data.addProperty("saturation", 5.0f);
        data.addProperty("exhaustion", 0.0f);
        data.addProperty("level", 0);
        data.addProperty("exp", 0.0f);
        data.addProperty("totalExp", 0);
        data.addProperty("fireTicks", 0);
        data.addProperty("freezeTicks", 0);
        data.addProperty("remainingAir", template.getMaximumAir());
        data.addProperty("fallDistance", 0.0f);
        data.add("effects", new JsonArray());
        data.add("persistentData", JsonNull.INSTANCE);
        data.add("persistentKeys", new JsonArray());
        return new BodySnapshot(data);
    }

    public BodySnapshot withLocation(Location at) {
        JsonObject copy = data.deepCopy();
        copy.add("location", place(at));
        return new BodySnapshot(copy);
    }

    /** A genuine vanilla respawn refills health once the selected character's item and class stats exist. */
    public BodySnapshot withFullHealth() {
        JsonObject copy = data.deepCopy();
        copy.add("health", JsonNull.INSTANCE);
        return new BodySnapshot(copy);
    }

    public String serialize() {
        return json;
    }

    public static BodySnapshot deserialize(String encoded) throws IOException {
        return fromJson(BodyJson.parse(encoded));
    }

    static BodySnapshot fromJson(JsonObject data) throws IOException {
        validate(data);
        return new BodySnapshot(data.deepCopy());
    }

    JsonObject toJson() {
        return data.deepCopy();
    }

    public String worldName() {
        return data.getAsJsonObject("location").get("world").getAsString();
    }

    public Location location() throws IOException {
        World world = Bukkit.getWorld(worldName());
        if (world == null) throw new IOException("Character body world is not loaded: " + worldName());
        return at(world, data.getAsJsonObject("location"));
    }

    /** Validates everything before the first change, so a bad body leaves the player untouched. Never teleports. */
    public void apply(Player player, Set<NamespacedKey> bodyKeys) throws IOException {
        apply(player, bodyKeys, BodyPdcCodec.PAPER);
    }

    void apply(Player player, Set<NamespacedKey> bodyKeys, BodyPdcCodec codec) throws IOException {
        requireBodyKeys(bodyKeys);
        requireClosedInventory(player);
        location();
        ItemStack[] inventory = items("inventory", player.getInventory().getContents().length);
        ItemStack[] enderChest = items("enderChest", player.getEnderChest().getContents().length);
        List<PotionEffect> effects = new ArrayList<>();
        for (JsonElement effect : data.getAsJsonArray("effects")) effects.add(effect(effect.getAsJsonObject()));
        PersistentDataContainer target = player.getPersistentDataContainer();
        PersistentDataContainer selected = target.getAdapterContext().newPersistentDataContainer();
        if (!data.get("persistentData").isJsonNull()) codec.read(selected, BodyJson.bytes(data, "persistentData"));
        for (NamespacedKey key : selected.getKeys()) {
            if (!bodyKeys.contains(key)) throw new IOException("Character body holds unapproved persistent data: " + key);
        }
        // Like vanilla, a point in a missing world falls back to the world spawn.
        Location respawn = loaded(data.get("respawn"));
        Location lastDeath = loaded(data.get("lastDeath"));

        PlayerInventory items = player.getInventory();
        items.setContents(inventory);
        items.setHeldItemSlot(integer("heldSlot"));
        player.getEnderChest().setContents(enderChest);
        for (PotionEffect active : player.getActivePotionEffects()) player.removePotionEffect(active.getType());
        player.addPotionEffects(effects);
        for (NamespacedKey key : bodyKeys) target.remove(key);
        selected.copyTo(target, true);
        player.setRespawnLocation(respawn, data.get("respawnForced").getAsBoolean());
        player.setLastDeathLocation(lastDeath);
        player.setFoodLevel(integer("food"));
        player.setSaturation(real("saturation"));
        player.setExhaustion(real("exhaustion"));
        player.setLevel(integer("level"));
        player.setExp(real("exp"));
        player.setTotalExperience(integer("totalExp"));
        player.setFireTicks(integer("fireTicks"));
        player.setFreezeTicks(integer("freezeTicks"));
        player.setRemainingAir(integer("remainingAir"));
        player.setFallDistance(real("fallDistance"));
        player.setVelocity(new Vector());
        player.setAbsorptionAmount(data.get("absorption").getAsDouble());
        applyHealth(player);
    }

    /** Saved health capped at the current maximum; a fresh body is full. Repeat once item stats settle. */
    public void applyHealth(Player player) {
        double max = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        JsonElement saved = data.get("health");
        double health = saved.isJsonNull() ? max : saved.getAsDouble();
        player.setHealth(Math.min(Math.max(health, MIN_APPLIED_HEALTH), max));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BodySnapshot body && json.equals(body.json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    private int integer(String key) {
        return data.get(key).getAsInt();
    }

    private float real(String key) {
        return data.get(key).getAsFloat();
    }

    private ItemStack[] items(String key, int expected) throws IOException {
        byte[] bytes = BodyJson.bytes(data, key);
        int slots = slots(bytes);
        if (slots != expected) {
            throw new IOException("Character body " + key + " has " + slots + " slots; the player has " + expected);
        }
        try {
            return ItemStack.deserializeItemsFromBytes(bytes);
        } catch (RuntimeException error) {
            throw new IOException("Character body " + key + " could not be read", error);
        }
    }

    private static String items(ItemStack[] contents) throws IOException {
        try {
            return BodyJson.encode(ItemStack.serializeItemsAsBytes(contents));
        } catch (RuntimeException error) {
            throw new IOException("Character body items could not be saved", error);
        }
    }

    /** Paper's item-array layout: format byte 1, a count, then a length and the bytes for each slot (0 for empty). */
    private static byte[] emptyItems(int slots) {
        return ByteBuffer.allocate(5 + 4 * slots).put((byte) 1).putInt(slots).array();
    }

    static int slots(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (!buffer.hasRemaining() || buffer.get() != 1) throw new IOException("Unsupported character body item format");
        int count = buffer.remaining() < 4 ? -1 : buffer.getInt();
        if (count < 1 || count > MAX_SLOTS) throw new IOException("Invalid character body slot count");
        for (int slot = 0; slot < count; slot++) {
            int length = buffer.remaining() < 4 ? -1 : buffer.getInt();
            if (length < 0 || length > buffer.remaining()) throw new IOException("Truncated character body items");
            buffer.position(buffer.position() + length);
        }
        if (buffer.hasRemaining()) throw new IOException("Trailing character body item data");
        return count;
    }

    private static void requireBodyKeys(Set<NamespacedKey> bodyKeys) {
        if (bodyKeys.contains(BodyTag.KEY)) throw new IllegalArgumentException("The body tag cannot be part of a body");
    }

    private static void requireClosedInventory(Player player) throws IOException {
        if (!player.getItemOnCursor().isEmpty()) throw new IOException("Close the inventory first: the cursor holds an item");
        InventoryView view = player.getOpenInventory();
        // Paper reports the default player inventory as CREATIVE in creative mode.
        if (view.getType() != InventoryType.CRAFTING && view.getType() != InventoryType.CREATIVE) {
            throw new IOException("Close the inventory first: " + view.getType() + " is open");
        }
        Inventory grid = view.getTopInventory();
        if (grid != null && !grid.isEmpty()) throw new IOException("Close the inventory first: the crafting grid holds items");
    }

    private static JsonElement persistentData(Player player, Set<NamespacedKey> bodyKeys, BodyPdcCodec codec)
            throws IOException {
        PersistentDataContainer source = player.getPersistentDataContainer();
        PersistentDataContainer selected = source.getAdapterContext().newPersistentDataContainer();
        source.copyTo(selected, true);
        for (NamespacedKey key : Set.copyOf(selected.getKeys())) {
            if (!bodyKeys.contains(key)) selected.remove(key);
        }
        return selected.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(BodyJson.encode(codec.write(selected)));
    }

    private static JsonArray keyList(Set<NamespacedKey> keys) {
        JsonArray list = new JsonArray();
        keys.stream().map(NamespacedKey::toString).sorted().forEach(list::add);
        return list;
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    private static boolean placeable(Location at) {
        return at != null && at.isWorldLoaded() && coordinate(at.getX()) && coordinate(at.getY())
                && coordinate(at.getZ()) && Float.isFinite(at.getYaw()) && Float.isFinite(at.getPitch());
    }

    private static boolean coordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    private static JsonObject place(Location at) {
        if (!placeable(at)) throw new IllegalArgumentException("A character body needs a finite place in a loaded world");
        return encode(at);
    }

    private static JsonElement optional(Location at) {
        return placeable(at) ? encode(at) : JsonNull.INSTANCE;
    }

    private static JsonObject encode(Location at) {
        JsonObject json = new JsonObject();
        json.addProperty("world", at.getWorld().getName());
        json.addProperty("x", at.getX());
        json.addProperty("y", at.getY());
        json.addProperty("z", at.getZ());
        json.addProperty("yaw", at.getYaw());
        json.addProperty("pitch", at.getPitch());
        return json;
    }

    private static Location loaded(JsonElement element) {
        if (element.isJsonNull()) return null;
        JsonObject at = element.getAsJsonObject();
        World world = Bukkit.getWorld(at.get("world").getAsString());
        return world == null ? null : at(world, at);
    }

    private static Location at(World world, JsonObject at) {
        return new Location(world, at.get("x").getAsDouble(), at.get("y").getAsDouble(), at.get("z").getAsDouble(),
                at.get("yaw").getAsFloat(), at.get("pitch").getAsFloat());
    }

    private static JsonObject encode(PotionEffect effect, int depth) throws IOException {
        if (depth > MAX_EFFECT_DEPTH) throw new IOException("Character body hidden potion effects are too deep");
        JsonObject json = new JsonObject();
        json.addProperty("type", effect.getType().getKey().toString());
        json.addProperty("duration", Math.max(effect.getDuration(), PotionEffect.INFINITE_DURATION));
        json.addProperty("amplifier", Math.max(0, Math.min(255, effect.getAmplifier())));
        json.addProperty("ambient", effect.isAmbient());
        json.addProperty("particles", effect.hasParticles());
        json.addProperty("icon", effect.hasIcon());
        PotionEffect hidden = effect.getHiddenPotionEffect();
        json.add("hidden", hidden == null ? JsonNull.INSTANCE : encode(hidden, depth + 1));
        return json;
    }

    private static PotionEffect effect(JsonObject json) throws IOException {
        String type = json.get("type").getAsString();
        PotionEffectType kind = Registry.MOB_EFFECT.get(NamespacedKey.fromString(type));
        if (kind == null) throw new IOException("Unknown character body effect: " + type);
        PotionEffect hidden = json.get("hidden").isJsonNull() ? null : effect(json.getAsJsonObject("hidden"));
        return new PotionEffect(kind, json.get("duration").getAsInt(), json.get("amplifier").getAsInt(),
                json.get("ambient").getAsBoolean(), json.get("particles").getAsBoolean(),
                json.get("icon").getAsBoolean(), hidden);
    }

    private static void validate(JsonObject data) throws IOException {
        BodyJson.keys(data, "character body", KEYS);
        if (BodyJson.integer(data, "format", 0, Integer.MAX_VALUE) != FORMAT) {
            throw new IOException("Unsupported character body format");
        }
        slots(BodyJson.bytes(data, "inventory"));
        slots(BodyJson.bytes(data, "enderChest"));
        BodyJson.integer(data, "heldSlot", 0, 8);
        validateLocation(BodyJson.object(data, "location"));
        if (!BodyJson.isNull(data, "respawn")) validateLocation(BodyJson.object(data, "respawn"));
        BodyJson.bool(data, "respawnForced");
        if (!BodyJson.isNull(data, "lastDeath")) validateLocation(BodyJson.object(data, "lastDeath"));
        if (!BodyJson.isNull(data, "health")) BodyJson.real(data, "health", 0, MAX_HEALTH);
        BodyJson.real(data, "absorption", 0, MAX_ABSORPTION);
        BodyJson.integer(data, "food", 0, 20);
        BodyJson.real(data, "saturation", 0, 20);
        BodyJson.real(data, "exhaustion", 0, 40);
        BodyJson.integer(data, "level", 0, Integer.MAX_VALUE);
        BodyJson.real(data, "exp", 0, 1);
        BodyJson.integer(data, "totalExp", 0, Integer.MAX_VALUE);
        BodyJson.integer(data, "fireTicks", Integer.MIN_VALUE, Integer.MAX_VALUE);
        BodyJson.integer(data, "freezeTicks", 0, Integer.MAX_VALUE);
        BodyJson.integer(data, "remainingAir", Integer.MIN_VALUE, Integer.MAX_VALUE);
        BodyJson.real(data, "fallDistance", 0, MAX_COORDINATE);
        Set<String> types = new HashSet<>();
        for (JsonElement effect : BodyJson.array(data, "effects")) {
            if (!types.add(validateEffect(effect, 0))) throw BodyJson.invalid("effects");
        }
        if (!BodyJson.isNull(data, "persistentData")) BodyJson.bytes(data, "persistentData");
        String previous = "";
        for (JsonElement element : BodyJson.array(data, "persistentKeys")) {
            String text = element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : "";
            NamespacedKey key = key(text);
            if (key == null || !key.toString().equals(text) || key.equals(BodyTag.KEY) || previous.compareTo(text) >= 0) {
                throw BodyJson.invalid("persistentKeys");
            }
            previous = text;
        }
    }

    private static void validateLocation(JsonObject at) throws IOException {
        BodyJson.keys(at, "body location", LOCATION_KEYS);
        if (BodyJson.string(at, "world").isBlank()) throw BodyJson.invalid("world");
        BodyJson.real(at, "x", -MAX_COORDINATE, MAX_COORDINATE);
        BodyJson.real(at, "y", -MAX_COORDINATE, MAX_COORDINATE);
        BodyJson.real(at, "z", -MAX_COORDINATE, MAX_COORDINATE);
        BodyJson.real(at, "yaw", -Float.MAX_VALUE, Float.MAX_VALUE);
        BodyJson.real(at, "pitch", -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    private static String validateEffect(JsonElement element, int depth) throws IOException {
        if (!element.isJsonObject() || depth > MAX_EFFECT_DEPTH) throw BodyJson.invalid("effects");
        JsonObject effect = element.getAsJsonObject();
        BodyJson.keys(effect, "body effect", EFFECT_KEYS);
        String type = BodyJson.string(effect, "type");
        if (key(type) == null) throw BodyJson.invalid("effects");
        BodyJson.integer(effect, "duration", PotionEffect.INFINITE_DURATION, Integer.MAX_VALUE);
        BodyJson.integer(effect, "amplifier", 0, 255);
        BodyJson.bool(effect, "ambient");
        BodyJson.bool(effect, "particles");
        BodyJson.bool(effect, "icon");
        if (!BodyJson.isNull(effect, "hidden")) validateEffect(effect.get("hidden"), depth + 1);
        return type;
    }

    private static NamespacedKey key(String text) {
        try {
            return NamespacedKey.fromString(text);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
