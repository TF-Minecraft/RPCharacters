package net.tfminecraft.rpcharacters.plots;

import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Every character's Intrigue: one roll for life with the Spymaster formula. Rolls a character already
 * made as Spymaster in SimpleFactions are imported and kept.
 */
public final class Intrigue {
    static final Map<String, Double> WEIGHTS = Map.of(
            "intelligence", 3.0, "wisdom", 2.5, "charisma", 1.5, "dexterity", 1.0, "constitution", -2.0, "strength", -2.0);
    static Supplier<RandomGenerator> random = ThreadLocalRandom::current;
    private static final Map<String, Integer> values = new LinkedHashMap<>();
    private static Path file;

    private Intrigue() {}

    /** Loads this plugin's rolls, then adds any Spymaster roll SimpleFactions has that this file lacks. */
    public static void load(Path own, Path simpleFactions) throws IOException {
        values.clear();
        file = own;
        values.putAll(read(own));
        boolean imported = false;
        if (simpleFactions != null && Files.exists(simpleFactions))
            for (var entry : read(simpleFactions).entrySet()) imported |= values.putIfAbsent(entry.getKey(), entry.getValue()) == null;
        if (imported) save();
    }

    private static Map<String, Integer> read(Path path) throws IOException {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!Files.exists(path)) return result;
        Map<String, Integer> loaded = PlotStore.GSON.fromJson(Files.readString(path), new TypeToken<Map<String, Integer>>() {}.getType());
        if (loaded == null) throw new IOException("Empty Intrigue file: " + path);
        loaded.forEach((id, value) -> {
            if (id != null && value != null) result.put(id, PlotMath.clamp(value));
        });
        return result;
    }

    static void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(pending, PlotStore.GSON.toJson(values));
        try { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The character's Intrigue, or null until they have rolled. */
    public static Integer known(String characterId) {
        return characterId == null ? null : values.get(characterId);
    }

    /** The player's active character's Intrigue, rolled now if this is the first time. */
    public static int of(Player player, String characterId) throws IOException {
        Integer known = values.get(characterId);
        if (known != null) return known;
        int rolled = PlotMath.clamp(roll(PlotCharacters.attributes(player)));
        values.put(characterId, rolled);
        try {
            save();
        } catch (IOException error) {
            values.remove(characterId);
            throw error;
        }
        return rolled;
    }

    /** SimpleFactions' configured formula when it is installed, otherwise the same formula with its defaults. */
    static int roll(Map<String, Integer> attributes) {
        Integer configured = PlotFactions.intrigueRoll(attributes, random.get());
        return configured != null ? configured : defaultRoll(attributes, random.get());
    }

    static int defaultRoll(Map<String, Integer> attributes, RandomGenerator random) {
        double score = 50;
        for (var weight : WEIGHTS.entrySet())
            score += (Math.max(0, Math.min(16, attributes.getOrDefault(weight.getKey(), 0))) - 6) * weight.getValue();
        return PlotMath.clamp((int) Math.round(score) + random.nextInt(-20, 21));
    }
}
