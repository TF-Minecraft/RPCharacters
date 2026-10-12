package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Strict JSON reading shared by bodies and body files: exact keys, exact types, no lenient parsing. */
final class BodyJson {
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();

    private BodyJson() {
    }

    static JsonObject parse(String text) throws IOException {
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing character body data");
            if (!element.isJsonObject()) throw new IOException("Character body data is not an object");
            return element.getAsJsonObject();
        } catch (JsonParseException error) {
            throw new IOException("Invalid character body JSON", error);
        }
    }

    static void keys(JsonObject object, String what, Set<String> keys) throws IOException {
        if (!object.keySet().equals(keys)) throw new IOException("Unexpected " + what + " fields: " + object.keySet());
    }

    static boolean isNull(JsonObject object, String key) {
        return object.get(key).isJsonNull();
    }

    static JsonObject object(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (!value.isJsonObject()) throw invalid(key);
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (!value.isJsonArray()) throw invalid(key);
        return value.getAsJsonArray();
    }

    static JsonPrimitive primitive(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive()) throw invalid(key);
        return value.getAsJsonPrimitive();
    }

    static String string(JsonObject object, String key) throws IOException {
        JsonPrimitive value = primitive(object, key);
        if (!value.isString()) throw invalid(key);
        return value.getAsString();
    }

    static boolean bool(JsonObject object, String key) throws IOException {
        JsonPrimitive value = primitive(object, key);
        if (!value.isBoolean()) throw invalid(key);
        return value.getAsBoolean();
    }

    static long integer(JsonObject object, String key, long min, long max) throws IOException {
        JsonPrimitive value = primitive(object, key);
        if (!value.isNumber()) throw invalid(key);
        long number;
        try {
            number = new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid(key);
        }
        if (number < min || number > max) throw invalid(key);
        return number;
    }

    static double real(JsonObject object, String key, double min, double max) throws IOException {
        JsonPrimitive value = primitive(object, key);
        if (!value.isNumber()) throw invalid(key);
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max) throw invalid(key);
        return number;
    }

    static byte[] bytes(JsonObject object, String key) throws IOException {
        try {
            return Base64.getDecoder().decode(string(object, key));
        } catch (IllegalArgumentException error) {
            throw invalid(key);
        }
    }

    static UUID uuid(JsonObject object, String key) throws IOException {
        UUID id = uuid(string(object, key));
        if (id == null) throw invalid(key);
        return id;
    }

    /** Only the canonical lower-case form; {@link UUID#fromString} accepts shortened groups. */
    static UUID uuid(String text) {
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equals(text) ? id : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static IOException invalid(String key) {
        return new IOException("Invalid character body field: " + key);
    }
}
