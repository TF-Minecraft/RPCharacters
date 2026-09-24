package net.tfminecraft.rpcharacters.focus;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class FocusStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final File folder;

    public FocusStore(File folder) {
        this.folder = folder;
    }

    /** Null means absent, never an unreadable or malformed existing character record. */
    public FocusData load(String characterId) {
        if (characterId == null || characterId.isBlank()) return null;
        File file = fileFor(characterId);
        if (Files.notExists(file.toPath())) return null;
        try (Reader reader = new FileReader(file)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            exactNonnegativeNumber(object, "points").intValueExact();
            exactNonnegativeNumber(object, "lastRegenMs").longValueExact();
            FocusData data = GSON.fromJson(object, FocusData.class);
            if (data.getCharacterId() == null || data.getCharacterId().isBlank()) {
                data.setCharacterId(characterId);
            } else if (!characterId.equals(data.getCharacterId())) {
                throw new IllegalStateException("Character ID does not match filename");
            }
            return data;
        } catch (IOException | RuntimeException ex) {
            throw failure("load", file, ex);
        }
    }

    public void save(FocusData data) {
        if (data == null || data.getCharacterId() == null || data.getCharacterId().isBlank()) return;
        File target = fileFor(data.getCharacterId());
        java.nio.file.Path temp = null;
        try {
            Files.createDirectories(folder.toPath());
            temp = Files.createTempFile(folder.toPath(), ".focus-", ".tmp");
            try (Writer writer = new FileWriter(temp.toFile())) {
                GSON.toJson(data, writer);
            }
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            throw failure("save", target, ex);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* Reported primary failure. */ }
            }
        }
    }

    private static java.math.BigDecimal exactNonnegativeNumber(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Missing or nonnumeric " + field);
        }
        var number = value.getAsBigDecimal();
        if (number.signum() < 0) throw new IllegalStateException("Negative " + field);
        return number;
    }

    private static IllegalStateException failure(String operation, File file, Exception cause) {
        return new IllegalStateException("Cannot " + operation + " focus file " + file
                + "; existing state will not be replaced: " + cause.getMessage(), cause);
    }

    private File fileFor(String characterId) {
        return new File(folder, characterId + ".json");
    }
}
