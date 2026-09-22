package net.tfminecraft.rpcharacters.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Characters plugin routes via TFMCWeb {@link GatewayClient}.
 */
public final class ProvinceSystemClient {

	private static final Pattern INT_FIELD = Pattern.compile(
		"\"(\\w+)\"\\s*:\\s*(-?\\d+)"
	);
	private static final Pattern STRING_FIELD = Pattern.compile(
		"\"(\\w+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
	);

	private ProvinceSystemClient() {}

	public static final class CatalogPushResult {
		public final boolean ok;
		public final int stages;
		public final int races;
		public final int traits;
		public final int classes;
		public final String updatedAt;
		public final String error;

		private CatalogPushResult(
			boolean ok,
			int stages,
			int races,
			int traits,
			int classes,
			String updatedAt,
			String error
		) {
			this.ok = ok;
			this.stages = stages;
			this.races = races;
			this.traits = traits;
			this.classes = classes;
			this.updatedAt = updatedAt;
			this.error = error;
		}

		public static CatalogPushResult success(
			int stages,
			int races,
			int traits,
			int classes,
			String updatedAt
		) {
			return new CatalogPushResult(
				true, stages, races, traits, classes, updatedAt, null
			);
		}

		public static CatalogPushResult fail(String error) {
			return new CatalogPushResult(false, 0, 0, 0, 0, null, error);
		}
	}

	public static final class RealmWipeResult {
		public final boolean ok;
		public final String realmId;
		public final int total;
		public final int pngsDeleted;
		public final String error;

		private RealmWipeResult(
			boolean ok,
			String realmId,
			int total,
			int pngsDeleted,
			String error
		) {
			this.ok = ok;
			this.realmId = realmId;
			this.total = total;
			this.pngsDeleted = pngsDeleted;
			this.error = error;
		}

		public static RealmWipeResult success(String realmId, int total, int pngsDeleted) {
			return new RealmWipeResult(true, realmId, total, pngsDeleted, null);
		}

		public static RealmWipeResult fail(String error) {
			return new RealmWipeResult(false, null, 0, 0, error);
		}
	}

	public static final class SimpleResult {
		public final boolean ok;
		public final String error;
		public final String body;

		private SimpleResult(boolean ok, String error, String body) {
			this.ok = ok;
			this.error = error;
			this.body = body;
		}

		public static SimpleResult success(String body) {
			return new SimpleResult(true, null, body);
		}

		public static SimpleResult fail(String error) {
			return new SimpleResult(false, error, null);
		}
	}

	/** PUT /characters/plugin/creation-catalog */
	public static CatalogPushResult pushCreationCatalog(String jsonBody) {
		SimpleResult raw = request("PUT", "/characters/plugin/creation-catalog", jsonBody);
		if (!raw.ok) {
			return CatalogPushResult.fail(raw.error);
		}
		String response = raw.body;
		return CatalogPushResult.success(
			jsonInt(response, "stages"),
			jsonInt(response, "races"),
			jsonInt(response, "traits"),
			jsonInt(response, "classes"),
			jsonString(response, "updated_at")
		);
	}

	/** GET /characters/plugin/pending — realm injected by TFMCWeb gateway. */
	public static SimpleResult fetchPendingCreates() {
		return request("GET", "/characters/plugin/pending", null);
	}

	/** POST /characters/plugin/applied */
	public static SimpleResult ackCreates(String jsonBody) {
		return request("POST", "/characters/plugin/applied", jsonBody);
	}

	/** GET /characters/plugin/lore-items/pending */
	public static SimpleResult fetchPendingLoreItems() {
		return request("GET", "/characters/plugin/lore-items/pending", null);
	}

	/** GET /characters/plugin/lore-items/claim-status?player_uuid=&character_id=&kit_id= */
	public static SimpleResult fetchLoreItemClaimStatus(
			String playerUuid,
			String characterId,
			String kitId
	) {
		if (playerUuid == null || playerUuid.isBlank() || characterId == null || characterId.isBlank()) {
			return SimpleResult.fail("player_uuid and character_id are required");
		}
		try {
			String kit = (kitId == null || kitId.isBlank()) ? "starter" : kitId.trim();
			String q = "?player_uuid=" + java.net.URLEncoder.encode(playerUuid.trim(), StandardCharsets.UTF_8)
					+ "&character_id=" + java.net.URLEncoder.encode(characterId.trim(), StandardCharsets.UTF_8)
					+ "&kit_id=" + java.net.URLEncoder.encode(kit, StandardCharsets.UTF_8);
			return request("GET", "/characters/plugin/lore-items/claim-status" + q, null);
		} catch (Exception e) {
			return SimpleResult.fail(e.getMessage() != null ? e.getMessage() : "encode failed");
		}
	}

	/**
	 * DELETE /characters/plugin/lore-items/customisations?player_uuid=&character_id=&kit_id=
	 */
	public static SimpleResult clearLoreItemCustomisations(
			String playerUuid,
			String characterId,
			String kitId
	) {
		if (playerUuid == null || playerUuid.isBlank()
				|| characterId == null || characterId.isBlank()
				|| kitId == null || kitId.isBlank()) {
			return SimpleResult.fail("player_uuid, character_id, and kit_id are required");
		}
		try {
			String q = "?player_uuid=" + java.net.URLEncoder.encode(playerUuid.trim(), StandardCharsets.UTF_8)
					+ "&character_id=" + java.net.URLEncoder.encode(characterId.trim(), StandardCharsets.UTF_8)
					+ "&kit_id=" + java.net.URLEncoder.encode(kitId.trim(), StandardCharsets.UTF_8);
			return request("DELETE", "/characters/plugin/lore-items/customisations" + q, null);
		} catch (Exception e) {
			return SimpleResult.fail(e.getMessage() != null ? e.getMessage() : "encode failed");
		}
	}

	/** @deprecated use {@link #fetchLoreItemClaimStatus(String, String, String)} */
	@Deprecated
	public static SimpleResult fetchLoreItemClaimStatus(String playerUuid, String characterId) {
		return fetchLoreItemClaimStatus(playerUuid, characterId, "starter");
	}

	public static boolean claimStatusPendingSkin(String body) {
		return claimStatusFlagTrue(body, "pending_skin");
	}

	public static boolean claimStatusPendingPack(String body) {
		return claimStatusFlagTrue(body, "pending_pack");
	}

	/** Cheap JSON flag parse: {@code "flag": true}. */
	static boolean claimStatusFlagTrue(String body, String flag) {
		if (body == null || body.isBlank() || flag == null || flag.isBlank()) {
			return false;
		}
		String lower = body.toLowerCase();
		String key = "\"" + flag.toLowerCase() + "\"";
		int idx = lower.indexOf(key);
		if (idx < 0) {
			return false;
		}
		int colon = lower.indexOf(':', idx);
		if (colon < 0) {
			return false;
		}
		String rest = lower.substring(colon + 1).trim();
		return rest.startsWith("true");
	}

	/** POST /characters/plugin/lore-items/applied */
	public static SimpleResult ackLoreItems(String jsonBody) {
		return request("POST", "/characters/plugin/lore-items/applied", jsonBody);
	}

	/** PUT /characters/plugin/roster */
	public static SimpleResult pushRoster(String jsonBody) {
		return request("PUT", "/characters/plugin/roster", jsonBody);
	}

	/**
	 * DELETE /characters/plugin/realm-data?realm_id=
	 * Wipes every website character row for one realm. Player meta stays.
	 */
	public static RealmWipeResult wipeRealmCharacterData(String realm) {
		if (realm == null || realm.isBlank()) {
			return RealmWipeResult.fail("realm_id is required");
		}
		SimpleResult raw;
		try {
			String q = "?realm_id=" + java.net.URLEncoder.encode(realm.trim(), StandardCharsets.UTF_8);
			raw = request("DELETE", "/characters/plugin/realm-data" + q, null);
		} catch (Exception e) {
			return RealmWipeResult.fail(e.getMessage() != null ? e.getMessage() : "encode failed");
		}
		if (!raw.ok) {
			return RealmWipeResult.fail(raw.error);
		}
		String realmId = jsonString(raw.body, "realm_id");
		return RealmWipeResult.success(
			realmId == null ? realm.trim() : realmId,
			jsonInt(raw.body, "total"),
			jsonInt(raw.body, "pngs_deleted")
		);
	}

	/**
	 * POST /characters/plugin/characters/delete
	 * Drops site rows for characters deleted in-game. Pending creates are kept.
	 */
	public static SimpleResult deleteCharacters(String realm, List<String> characterIds) {
		if (realm == null || realm.isBlank()) {
			return SimpleResult.fail("realm_id is required");
		}
		JSONArray ids = new JSONArray();
		if (characterIds != null) {
			for (String id : characterIds) {
				if (id != null && !id.isBlank()) {
					ids.add(id.trim());
				}
			}
		}
		if (ids.isEmpty()) {
			return SimpleResult.fail("character_ids is required");
		}
		JSONObject root = new JSONObject();
		root.put("realm_id", realm.trim());
		root.put("character_ids", ids);
		return request("POST", "/characters/plugin/characters/delete", root.toJSONString());
	}

	/** GET /characters/plugin/wardrobe/{playerUuid}/{characterId} */
	public static SimpleResult fetchWardrobe(String playerUuid, String characterId) {
		String uuid = sanitizePathSegment(playerUuid);
		String cid = sanitizePathSegment(characterId);
		if (uuid == null || cid == null) {
			return SimpleResult.fail("invalid player or character id");
		}
		return request(
			"GET",
			"/characters/plugin/wardrobe/" + uuid + "/" + cid,
			null
		);
	}

	/**
	 * POST /characters/plugin/wardrobe/{playerUuid}/{characterId}/active
	 * Same as PATCH — POST used because Java HttpURLConnection often rejects PATCH.
	 */
	public static SimpleResult setWardrobeActive(
		String playerUuid,
		String characterId,
		String slot
	) {
		String uuid = sanitizePathSegment(playerUuid);
		String cid = sanitizePathSegment(characterId);
		if (uuid == null || cid == null) {
			return SimpleResult.fail("invalid player or character id");
		}
		String body;
		if (slot == null || slot.isBlank()) {
			body = "{\"slot\":null}";
		} else {
			String safe = slot.trim().toLowerCase();
			body = "{\"slot\":\"" + jsonEscape(safe) + "\"}";
		}
		return request(
			"POST",
			"/characters/plugin/wardrobe/" + uuid + "/" + cid + "/active",
			body
		);
	}

	/**
	 * POST /characters/plugin/wardrobe/{playerUuid}/{characterId}/ack
	 * Clears apply_pending for slots after pull + apply.
	 */
	public static SimpleResult ackWardrobe(
		String playerUuid,
		String characterId,
		java.util.List<String> slots
	) {
		String uuid = sanitizePathSegment(playerUuid);
		String cid = sanitizePathSegment(characterId);
		if (uuid == null || cid == null) {
			return SimpleResult.fail("invalid player or character id");
		}
		StringBuilder sb = new StringBuilder("{\"slots\":[");
		boolean first = true;
		if (slots != null) {
			for (String slot : slots) {
				if (slot == null || slot.isBlank()) {
					continue;
				}
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append('"').append(jsonEscape(slot.trim().toLowerCase())).append('"');
			}
		}
		sb.append("]}");
		return request(
			"POST",
			"/characters/plugin/wardrobe/" + uuid + "/" + cid + "/ack",
			sb.toString()
		);
	}

	private static String sanitizePathSegment(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim();
		if (s.isEmpty() || s.contains("/") || s.contains("\\") || s.contains("..")) {
			return null;
		}
		return s;
	}

	private static String jsonEscape(String raw) {
		if (raw == null) {
			return "";
		}
		return raw
			.replace("\\", "\\\\")
			.replace("\"", "\\\"");
	}

	/**
	 * PUT /characters/plugin/kit-skins/{name} — raw PNG bytes for default kit preview.
	 * {@code name} is the skin_png stem (no path, no .png suffix).
	 */
	public static SimpleResult putKitSkin(String name, byte[] pngBytes) {
		String stem = sanitizeKitSkinStem(name);
		if (stem == null) {
			return SimpleResult.fail("invalid kit skin name");
		}
		if (pngBytes == null || pngBytes.length == 0) {
			return SimpleResult.fail("empty kit skin body");
		}
		return requestBytes(
			"PUT",
			"/characters/plugin/kit-skins/" + stem,
			pngBytes,
			"image/png"
		);
	}

	/**
	 * PUT /characters/plugin/wardrobe-templates/masked — body template for auto-masked.
	 */
	public static SimpleResult putWardrobeMaskedTemplate(byte[] pngBytes) {
		if (pngBytes == null || pngBytes.length == 0) {
			return SimpleResult.fail("empty masked template body");
		}
		return requestBytes(
			"PUT",
			"/characters/plugin/wardrobe-templates/masked",
			pngBytes,
			"image/png"
		);
	}

	private static String sanitizeKitSkinStem(String name) {
		if (name == null) {
			return null;
		}
		String raw = name.trim();
		if (raw.toLowerCase().endsWith(".png")) {
			raw = raw.substring(0, raw.length() - 4).trim();
		}
		if (raw.isEmpty() || raw.contains("/") || raw.contains("\\") || raw.contains("..")) {
			return null;
		}
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
				continue;
			}
			return null;
		}
		if (raw.startsWith(".") || raw.endsWith(".")) {
			return null;
		}
		return raw;
	}

	@SuppressWarnings("unchecked")
	public static List<JSONObject> parsePendingCreates(String body) {
		List<JSONObject> out = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return out;
		}
		try {
			JSONParser parser = new JSONParser();
			Object parsed = parser.parse(body);
			if (!(parsed instanceof JSONObject)) {
				return out;
			}
			Object creates = ((JSONObject) parsed).get("creates");
			if (!(creates instanceof JSONArray)) {
				return out;
			}
			JSONArray arr = (JSONArray) creates;
			for (Object row : arr) {
				if (row instanceof JSONObject) {
					out.add((JSONObject) row);
				}
			}
		} catch (Exception ignored) {
			// caller treats empty as no pending
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	public static List<JSONObject> parsePendingLoreItems(String body) {
		List<JSONObject> out = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return out;
		}
		try {
			JSONParser parser = new JSONParser();
			Object parsed = parser.parse(body);
			if (!(parsed instanceof JSONObject)) {
				return out;
			}
			Object items = ((JSONObject) parsed).get("items");
			if (!(items instanceof JSONArray)) {
				return out;
			}
			JSONArray arr = (JSONArray) items;
			for (Object row : arr) {
				if (row instanceof JSONObject) {
					out.add((JSONObject) row);
				}
			}
		} catch (Exception ignored) {
			// caller treats empty as no pending
		}
		return out;
	}

	private static SimpleResult request(String method, String path, String jsonBody) {
		GatewayClient.Result raw = GatewayClient.request(method, path, jsonBody);
		if (raw.ok) {
			return SimpleResult.success(raw.body);
		}
		return SimpleResult.fail(raw.error);
	}

	private static SimpleResult requestBytes(
		String method,
		String path,
		byte[] body,
		String contentType
	) {
		GatewayClient.Result raw = GatewayClient.requestBytes(
			method,
			path,
			body,
			contentType
		);
		if (raw.ok) {
			return SimpleResult.success(raw.body);
		}
		return SimpleResult.fail(raw.error);
	}

	private static int jsonInt(String json, String key) {
		if (json == null || key == null) {
			return 0;
		}
		Matcher m = INT_FIELD.matcher(json);
		while (m.find()) {
			if (key.equals(m.group(1))) {
				try {
					return Integer.parseInt(m.group(2));
				} catch (NumberFormatException ignored) {
					return 0;
				}
			}
		}
		return 0;
	}

	private static String jsonString(String json, String key) {
		if (json == null || key == null) {
			return null;
		}
		Matcher m = STRING_FIELD.matcher(json);
		while (m.find()) {
			if (key.equals(m.group(1))) {
				return unescape(m.group(2));
			}
		}
		return null;
	}

	private static String unescape(String raw) {
		if (raw == null) {
			return null;
		}
		return raw
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\t", "\t");
	}
}
