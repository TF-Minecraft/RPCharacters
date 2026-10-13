package net.tfminecraft.rpcharacters.will;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import net.tfminecraft.rpcharacters.api.GatewayClient;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Will changes made on the website (design W5): pulled every 15 seconds for this server's realm, applied on
 * the main thread, then acknowledged with the realm in the body. A failed pull or ack backs off; a request
 * whose character cannot be read yet stays un-acknowledged until it can.
 */
public final class WillQueue {
    static final long PERIOD_TICKS = 20L * 15L;
    static final long MAX_BACKOFF_MS = 10L * 60_000L;

    static LongSupplier clock = System::currentTimeMillis;
    static java.util.function.BiConsumer<Plugin, Runnable> async =
            (plugin, task) -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    private static BukkitTask task;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile long retryAtMs;
    private static volatile long backoffMs;

    private WillQueue() {}

    public static synchronized void start(Plugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> pull(plugin), PERIOD_TICKS, PERIOD_TICKS);
    }

    public static synchronized void stop() {
        if (task != null) task.cancel();
        task = null;
        running.set(false);
        retryAtMs = 0;
        backoffMs = 0;
    }

    /** One pull, off the main thread. Skipped while backing off, while another runs, or without a realm. */
    static void pull(Plugin plugin) {
        if (clock.getAsLong() < retryAtMs || !running.compareAndSet(false, true)) return;
        String realm = GatewayClient.realmId();
        if (realm == null) {
            running.set(false);
            return;
        }
        ProvinceSystemClient.SimpleResult pending = ProvinceSystemClient.fetchPendingWills(realm);
        if (!pending.ok) {
            failed(plugin.getLogger(), "pull", pending.error);
            return;
        }
        List<JSONObject> requests = parse(pending.body);
        if (requests.isEmpty()) {
            succeeded();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<JSONObject> results = applyAll(requests, realm);
            async.accept(plugin, () -> ack(plugin.getLogger(), realm, results));
        });
    }

    @SuppressWarnings("unchecked")
    static void ack(Logger logger, String realm, List<JSONObject> results) {
        if (results.isEmpty()) {
            succeeded();
            return;
        }
        JSONObject body = new JSONObject();
        body.put("realm_id", realm);
        JSONArray array = new JSONArray();
        array.addAll(results);
        body.put("results", array);
        ProvinceSystemClient.SimpleResult ack = ProvinceSystemClient.ackWills(body.toJSONString());
        if (!ack.ok) {
            failed(logger, "acknowledgement", ack.error);
            return;
        }
        logger.info("[wills] applied " + results.size() + " website request(s)");
        succeeded();
    }

    private static void succeeded() {
        backoffMs = 0;
        retryAtMs = 0;
        running.set(false);
    }

    private static void failed(Logger logger, String step, String error) {
        backoffMs = backoffMs == 0 ? PERIOD_TICKS * 50L : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
        retryAtMs = clock.getAsLong() + backoffMs;
        logger.warning("[wills] " + step + " failed (" + error + "); next try in " + backoffMs / 1000 + " s");
        running.set(false);
    }

    static List<JSONObject> parse(String body) {
        List<JSONObject> out = new ArrayList<>();
        try {
            if (new JSONParser().parse(body) instanceof JSONObject root && root.get("requests") instanceof JSONArray rows) {
                for (Object row : rows) if (row instanceof JSONObject request) out.add(request);
            }
        } catch (Exception malformed) {
            // Nothing to apply; the next pull asks again.
        }
        return out;
    }

    /** Main thread. One ack row per request that has an answer now. */
    static List<JSONObject> applyAll(List<JSONObject> requests, String realm) {
        List<JSONObject> results = new ArrayList<>();
        for (JSONObject request : requests) {
            JSONObject result = apply(request, realm);
            if (result != null) results.add(result);
        }
        return results;
    }

    /** The ack row for one request, or null to leave it pending (unreadable account, failed save). */
    static JSONObject apply(JSONObject request, String realm) {
        String id = String.valueOf(request.get("id"));
        if (!realm.equals(request.get("realm_id"))) return null;
        UUID characterId;
        UUID account;
        UUID heir;
        try {
            characterId = UUID.fromString(String.valueOf(request.get("character_id")));
            account = UUID.fromString(String.valueOf(request.get("account_uuid")));
            heir = request.get("heir_character_id") == null ? null : UUID.fromString(String.valueOf(request.get("heir_character_id")));
        } catch (IllegalArgumentException invalid) {
            return result(id, "invalid", null);
        }
        WillRules.Lookup lookup = WillService.lookup();
        if (lookup.unreadable(characterId)) return null;
        var info = lookup.info(characterId);
        if (info.isEmpty() || !info.get().ref().ownerId().equals(account)) return result(id, "invalid", null);
        PlayerData data = account(account);
        RPCharacter character = data == null ? null : data.getCharacterById(characterId.toString());
        if (character == null) return null;
        if (id.equals(character.getWill().lastRequest())) return result(id, "applied", character.getWill());
        if (character.getStatus() != Status.ALIVE) return result(id, "character dead", null);
        if (!(request.get("base_revision") instanceof Number base) || base.longValue() != character.getWill().revision()) {
            return result(id, "stale", null);
        }
        WillService.Change change = WillService.change(data, character, account, heir, id);
        return switch (change.verdict()) {
            case OK -> change.saved() ? result(id, "applied", change.will()) : null;
            case HEIR_UNREADABLE -> null;
            case TESTATOR_DEAD -> result(id, "character dead", null);
            default -> result(id, "invalid heir", null);
        };
    }

    /** A loaded account, or the saved one when the owner is offline; null while it is still joining. */
    private static PlayerData account(UUID account) {
        Player online = Bukkit.getPlayer(account);
        if (online == null) return new Database().loadPlayerData(account);
        return PlayerManager.exists(online) ? PlayerManager.get(online) : null;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject result(String id, String outcome, CharacterWill will) {
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("result", outcome);
        if (will != null) {
            result.put("will_revision", will.revision());
            result.put("heir_name", WillService.heirName(will));
        }
        return result;
    }
}
