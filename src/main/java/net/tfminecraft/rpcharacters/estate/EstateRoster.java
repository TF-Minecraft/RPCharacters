package net.tfminecraft.rpcharacters.estate;

import java.time.Instant;
import java.util.Map;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;

/** The {@code estate} object of a roster row, in the shape ProvinceSystem's contract fixes. */
final class EstateRoster {
    private EstateRoster() {}

    @SuppressWarnings("unchecked")
    static JSONObject estate(EstateRecord r) {
        JSONObject estate = new JSONObject();
        estate.put("death_id", r.deathId.toString());
        estate.put("status", r.state.name());
        estate.put("due_at", millis(r.dueAt));
        estate.put("updated_at", millis(r.updatedAt));
        estate.put("revision", r.decision == null ? 0L : r.decision.revision());
        if (r.decision == null) return estate;
        estate.put("decided_at", millis(r.decision.decidedAt()));
        estate.put("heir_name", r.decision.heir() == null ? null : r.decision.heir().name());
        JSONObject recipients = new JSONObject();
        recipients.put("money", part(r.decision.money()));
        recipients.put("property", part(r.decision.property()));
        recipients.put("guild_locks", part(r.decision.guildLocks()));
        recipients.put("letters", part(r.decision.letters()));
        estate.put("recipients", recipients);
        Report money = r.participants.get("denareconomy");
        if (money != null && money.counts().containsKey("cents")) estate.put("money_moved_cents", (long) money.counts().get("cents"));
        JSONObject participants = new JSONObject();
        for (Map.Entry<String, Report> entry : r.participants.entrySet()) {
            JSONObject one = new JSONObject();
            one.put("outcome", entry.getValue().outcome());
            one.put("revision", entry.getValue().revision());
            JSONObject counts = new JSONObject();
            // Money goes only in money_moved_cents; the site shows other counts as "297 locks".
            entry.getValue().counts().forEach((label, count) -> {
                if (!label.equals("cents")) counts.put(label, (long) count);
            });
            one.put("counts", counts);
            participants.put(entry.getKey(), one);
        }
        estate.put("participants", participants);
        return estate;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject part(Part part) {
        JSONObject json = new JSONObject();
        json.put("kind", part.shown());
        json.put("name", part.label());
        return json;
    }

    private static Long millis(Instant at) {
        return at == null ? null : at.toEpochMilli();
    }
}
