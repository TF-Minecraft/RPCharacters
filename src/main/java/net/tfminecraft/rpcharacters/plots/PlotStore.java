package net.tfminecraft.rpcharacters.plots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Plots, each character's intrigue settings, protections, limits and messages waiting for a character. */
public final class PlotStore {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public List<Plot> plots = new ArrayList<>();
    public Map<String, IntrigueSettings> characters = new LinkedHashMap<>();
    /** "master account|target character" to the epoch millis the account may target them again. */
    public Map<String, Long> cooldowns = new LinkedHashMap<>();
    public Map<String, List<String>> notices = new LinkedHashMap<>();
    /** "participant character|target character" to the plot the target survived: never again. */
    public Map<String, String> bans = new LinkedHashMap<>();
    /** A Spymaster character to the faction members they protect. */
    public Map<String, List<PlotCharacter>> wards = new LinkedHashMap<>();
    private transient Path file;

    public static PlotStore load(Path file) throws IOException {
        PlotStore store = Files.exists(file) ? GSON.fromJson(Files.readString(file), PlotStore.class) : new PlotStore();
        if (store == null) throw new IOException("Empty plot file: " + file);
        if (store.plots == null) store.plots = new ArrayList<>();
        if (store.characters == null) store.characters = new LinkedHashMap<>();
        if (store.cooldowns == null) store.cooldowns = new LinkedHashMap<>();
        if (store.notices == null) store.notices = new LinkedHashMap<>();
        if (store.bans == null) store.bans = new LinkedHashMap<>();
        if (store.wards == null) store.wards = new LinkedHashMap<>();
        store.plots.removeIf(plot -> plot == null || plot.code == null || plot.master == null || plot.phase == null);
        store.characters.values().removeIf(Objects::isNull);
        store.characters.values().forEach(settings -> {
            if (settings.investigators == null) settings.investigators = new ArrayList<>();
        });
        store.wards.values().removeIf(Objects::isNull);
        store.file = file;
        return store;
    }

    public void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(pending, GSON.toJson(this));
        try { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public IntrigueSettings settings(String characterId) {
        return characters.computeIfAbsent(characterId, id -> new IntrigueSettings());
    }

    /** Settings without recording a character that never changed them. */
    public IntrigueSettings peek(String characterId) {
        IntrigueSettings settings = characters.get(characterId);
        return settings == null ? new IntrigueSettings() : settings;
    }

    public Plot plot(String code) {
        for (Plot plot : plots) if (plot.code.equalsIgnoreCase(code)) return plot;
        return null;
    }

    /** The active plot this character leads or has joined; informed characters are not part of one. */
    public Plot plotOf(String characterId) {
        for (Plot plot : plots) if (plot.phase.active() && plot.involves(characterId)) return plot;
        return null;
    }

    /** The active plot any character of this account leads or has joined. */
    public Plot plotOfAccount(UUID account) {
        for (Plot plot : plots) if (plot.phase.active() && plot.involvesAccount(account)) return plot;
        return null;
    }

    public List<Plot> against(String characterId) {
        return plots.stream().filter(plot -> plot.phase.active() && plot.isTarget(characterId)).toList();
    }

    /** The character this one serves as confidant, if any. */
    public String confidantFor(String confidantId) {
        for (var entry : characters.entrySet())
            if (entry.getValue().confidant != null && entry.getValue().confidant.is(confidantId)) return entry.getKey();
        return null;
    }

    public List<PlotCharacter> wardsOf(String spymasterId) {
        return wards.computeIfAbsent(spymasterId, id -> new ArrayList<>());
    }

    /** The Spymaster character whose list holds this character, if any. */
    public String wardedBy(String characterId) {
        for (var entry : wards.entrySet())
            for (PlotCharacter ward : entry.getValue()) if (ward.is(characterId)) return entry.getKey();
        return null;
    }
}
