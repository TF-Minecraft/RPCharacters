package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Body;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Decision;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Recipient;

/** Reads and writes estate records. Times are epoch milliseconds; a bad file throws, never half-loads. */
final class EstateJson {
    static final int FORMAT = 1;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private EstateJson() {}

    static String write(EstateRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("deathId", r.deathId.toString());
        o.add("testator", ref(r.testator));
        o.addProperty("name", r.name);
        o.add("diedAt", time(r.diedAt));
        o.add("createdAt", time(r.createdAt));
        o.add("dueAt", time(r.dueAt));
        o.addProperty("state", r.state.name());
        o.addProperty("heldLeftMs", r.heldLeftMs);
        JsonObject will = new JsonObject();
        will.addProperty("heir", text(r.willHeir));
        will.addProperty("revision", r.willRevision);
        o.add("will", will);
        o.addProperty("wasActive", r.wasActive);
        o.addProperty("cause", r.cause);
        o.addProperty("killerAccount", text(r.killerAccount));
        o.add("killer", r.killer == null ? JsonNull.INSTANCE : ref(r.killer));
        o.add("postponedSince", time(r.postponedSince));
        o.addProperty("postponeReason", r.postponeReason);
        o.addProperty("noGuild", r.noGuild);
        o.addProperty("guildlessHeld", r.guildlessHeld);
        o.add("decision", r.decision == null ? JsonNull.INSTANCE : decision(r.decision));
        JsonObject participants = new JsonObject();
        r.participants.forEach((id, report) -> participants.add(id, report(report)));
        o.add("participants", participants);
        o.add("body", r.body == null ? JsonNull.INSTANCE : body(r.body));
        o.add("revivedAt", time(r.revivedAt));
        o.addProperty("revivedNote", r.revivedNote);
        o.add("cancelledAt", time(r.cancelledAt));
        o.add("revertedAt", time(r.revertedAt));
        o.add("updatedAt", time(r.updatedAt));
        return PRETTY.toJson(o) + "\n";
    }

    static EstateRecord read(String json) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.get("format").getAsInt() != FORMAT) throw new IOException("Unknown estate format");
            EstateRecord r = new EstateRecord(UUID.fromString(o.get("deathId").getAsString()), ref(o.get("testator")));
            r.name = o.get("name").getAsString();
            r.diedAt = time(o, "diedAt");
            r.createdAt = time(o, "createdAt");
            r.dueAt = time(o, "dueAt");
            r.state = EstateState.valueOf(o.get("state").getAsString());
            r.heldLeftMs = o.get("heldLeftMs").isJsonNull() ? null : o.get("heldLeftMs").getAsLong();
            JsonObject will = o.getAsJsonObject("will");
            r.willHeir = uuid(will.get("heir"));
            r.willRevision = will.get("revision").getAsLong();
            r.wasActive = o.get("wasActive").isJsonNull() ? null : o.get("wasActive").getAsBoolean();
            r.cause = string(o.get("cause"));
            r.killerAccount = uuid(o.get("killerAccount"));
            r.killer = o.get("killer").isJsonNull() ? null : ref(o.get("killer"));
            r.postponedSince = time(o, "postponedSince");
            r.postponeReason = string(o.get("postponeReason"));
            r.noGuild = o.get("noGuild").getAsBoolean();
            r.guildlessHeld = o.has("guildlessHeld") && o.get("guildlessHeld").getAsBoolean();
            r.decision = o.get("decision").isJsonNull() ? null : decision(o.getAsJsonObject("decision"));
            for (Map.Entry<String, JsonElement> entry : o.getAsJsonObject("participants").entrySet()) {
                r.participants.put(entry.getKey(), report(entry.getValue().getAsJsonObject()));
            }
            r.body = o.get("body").isJsonNull() ? null : body(o.getAsJsonObject("body"));
            r.revivedAt = time(o, "revivedAt");
            r.revivedNote = string(o.get("revivedNote"));
            r.cancelledAt = time(o, "cancelledAt");
            r.revertedAt = time(o, "revertedAt");
            r.updatedAt = time(o, "updatedAt");
            if ((r.state == EstateState.DECIDED || r.state == EstateState.REVERTED) && r.decision == null) {
                throw new IOException("Decided estate has no decision");
            }
            if (r.decision != null) r.toDecision();
            return r;
        } catch (RuntimeException error) {
            throw new IOException("Unreadable estate record: " + error.getMessage(), error);
        }
    }

    private static JsonObject decision(Decision d) {
        JsonObject o = new JsonObject();
        o.add("decidedAt", time(d.decidedAt()));
        o.addProperty("revision", d.revision());
        o.add("heir", d.heir() == null ? JsonNull.INSTANCE : named(d.heir()));
        o.addProperty("deathGuild", text(d.deathGuild()));
        o.addProperty("deathRealm", text(d.deathRealm()));
        o.add("money", part(d.money()));
        o.add("property", part(d.property()));
        o.add("guildLocks", part(d.guildLocks()));
        o.add("letters", part(d.letters()));
        return o;
    }

    private static Decision decision(JsonObject o) {
        return new Decision(time(o, "decidedAt"), o.get("revision").getAsLong(),
                o.get("heir").isJsonNull() ? null : named(o.getAsJsonObject("heir")), uuid(o.get("deathGuild")),
                uuid(o.get("deathRealm")), part(o.getAsJsonObject("money")), part(o.getAsJsonObject("property")),
                part(o.getAsJsonObject("guildLocks")), part(o.getAsJsonObject("letters")));
    }

    private static JsonObject part(Part p) {
        JsonObject o = new JsonObject();
        Recipient r = p.recipient();
        o.addProperty("kind", r.kind().name());
        o.addProperty("account", r.account().orElse(null));
        o.add("character", r.character().map(EstateJson::ref).map(JsonElement.class::cast).orElse(JsonNull.INSTANCE));
        o.addProperty("shown", p.shown());
        o.addProperty("label", p.label());
        return o;
    }

    private static Part part(JsonObject o) {
        Recipient r = switch (Recipient.Kind.valueOf(o.get("kind").getAsString())) {
            case ACCOUNT -> Recipient.account(o.get("account").getAsString());
            case CHARACTER -> Recipient.character(ref(o.get("character")));
            case WAITING -> Recipient.WAITING;
            case FROZEN -> Recipient.FROZEN;
            case SENDER -> Recipient.SENDER;
        };
        return new Part(r, o.get("shown").getAsString(), string(o.get("label")));
    }

    private static JsonObject report(Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("outcome", r.outcome());
        o.addProperty("revision", r.revision());
        o.addProperty("reason", r.reason());
        JsonObject counts = new JsonObject();
        r.counts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> counts.addProperty(entry.getKey(), entry.getValue()));
        o.add("counts", counts);
        JsonArray moved = new JsonArray();
        r.movedIds().forEach(moved::add);
        o.add("movedIds", moved);
        o.add("at", time(r.at()));
        return o;
    }

    private static Report report(JsonObject o) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : o.getAsJsonObject("counts").entrySet()) {
            counts.put(entry.getKey(), entry.getValue().getAsInt());
        }
        List<String> moved = new ArrayList<>();
        for (JsonElement id : o.getAsJsonArray("movedIds")) moved.add(id.getAsString());
        return new Report(o.get("outcome").getAsString(), o.get("revision").getAsLong(), string(o.get("reason")),
                counts, moved, time(o, "at"));
    }

    private static JsonObject body(Body b) {
        JsonObject o = new JsonObject();
        o.addProperty("state", b.state);
        o.addProperty("expectedRev", b.expectedRev);
        JsonArray stacks = new JsonArray();
        b.stacks.forEach(stacks::add);
        o.add("stacks", stacks);
        o.add("addressedTo", b.addressedTo == null ? JsonNull.INSTANCE : named(b.addressedTo));
        o.addProperty("readdressVia", text(b.readdressVia));
        o.addProperty("delivered", b.delivered);
        return o;
    }

    private static Body body(JsonObject o) {
        Body b = new Body();
        b.state = o.get("state").getAsString();
        b.expectedRev = o.get("expectedRev").getAsLong();
        for (JsonElement stack : o.getAsJsonArray("stacks")) b.stacks.add(stack.getAsString());
        b.addressedTo = o.get("addressedTo").isJsonNull() ? null : named(o.getAsJsonObject("addressedTo"));
        b.readdressVia = uuid(o.get("readdressVia"));
        b.delivered = o.get("delivered").getAsInt();
        return b;
    }

    private static JsonObject named(Named n) {
        JsonObject o = ref(n.ref());
        o.addProperty("name", n.name());
        return o;
    }

    private static Named named(JsonObject o) {
        return new Named(ref(o), o.get("name").getAsString());
    }

    private static JsonObject ref(CharacterRef ref) {
        JsonObject o = new JsonObject();
        o.addProperty("owner", ref.ownerId().toString());
        o.addProperty("character", ref.characterId().toString());
        return o;
    }

    private static CharacterRef ref(JsonElement e) {
        JsonObject o = e.getAsJsonObject();
        return new CharacterRef(UUID.fromString(o.get("owner").getAsString()), UUID.fromString(o.get("character").getAsString()));
    }

    private static JsonElement time(Instant at) {
        return at == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(at.toEpochMilli());
    }

    private static Instant time(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e.isJsonNull() ? null : Instant.ofEpochMilli(e.getAsLong());
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static UUID uuid(JsonElement e) {
        return e.isJsonNull() ? null : UUID.fromString(e.getAsString());
    }

    private static String string(JsonElement e) {
        return e.isJsonNull() ? null : e.getAsString();
    }
}
