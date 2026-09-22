package net.tfminecraft.rpcharacters.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.calendar.AgeCalculator;
import net.tfminecraft.rpcharacters.calendar.FantasyCalendar;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.NameColour;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;

/**
 * Pull pending web creates from ProvinceSystem and persist into RPCharacters data.
 */
public final class CharacterIngestService {

	private static final Database DB = new Database();
	/** Minimum gap between non-forced pending pulls (join / reload / timer). */
	private static final long PULL_COOLDOWN_MS = 15_000L;
	private static final long PERIODIC_PULL_TICKS = 20L * 15L;

	private static final AtomicLong lastPullAtMs = new AtomicLong(0L);
	private static volatile BukkitTask periodicTask;

	private CharacterIngestService() {}

	/**
	 * Pull if the 15s cooldown has elapsed. Used by join, reload, and the timer.
	 */
	public static void tryPullAsync(JavaPlugin plugin) {
		if (plugin == null || Cache.devCharacters) {
			return;
		}
		long now = System.currentTimeMillis();
		long prev = lastPullAtMs.get();
		if (now - prev < PULL_COOLDOWN_MS) {
			return;
		}
		if (!lastPullAtMs.compareAndSet(prev, now)) {
			return;
		}
		pullAsync(plugin);
	}

	/**
	 * Always pull (admin {@code /rpcharacter pending sync}). Updates cooldown stamp.
	 */
	public static void forcePullAsync(JavaPlugin plugin) {
		if (plugin == null) {
			return;
		}
		if (Cache.devCharacters) {
			plugin.getLogger().warning(
				"[character-ingest] skipped: dev-characters is on, website characters are not synced"
			);
			return;
		}
		lastPullAtMs.set(System.currentTimeMillis());
		pullAsync(plugin);
	}

	/**
	 * Per-player pull respecting the same global cooldown; always pushes roster after
	 * (or immediately if the pull is skipped).
	 */
	public static void tryPullForPlayerAsync(JavaPlugin plugin, UUID playerUuid) {
		if (plugin == null || playerUuid == null || Cache.devCharacters) {
			return;
		}
		long now = System.currentTimeMillis();
		long prev = lastPullAtMs.get();
		if (now - prev < PULL_COOLDOWN_MS) {
			RosterSyncService.pushRosterAsync(playerUuid);
			return;
		}
		if (!lastPullAtMs.compareAndSet(prev, now)) {
			RosterSyncService.pushRosterAsync(playerUuid);
			return;
		}
		pullForPlayerAsync(plugin, playerUuid);
	}

	public static void startPeriodicPull(JavaPlugin plugin) {
		if (plugin == null) {
			return;
		}
		stopPeriodicPull();
		periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
			plugin,
			() -> tryPullAsync(plugin),
			PERIODIC_PULL_TICKS,
			PERIODIC_PULL_TICKS
		);
	}

	public static void stopPeriodicPull() {
		BukkitTask task = periodicTask;
		periodicTask = null;
		if (task != null) {
			task.cancel();
		}
	}

	public static void pullAsync(JavaPlugin plugin) {
		if (plugin == null || Cache.devCharacters) {
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			ProvinceSystemClient.SimpleResult pending = ProvinceSystemClient.fetchPendingCreates();
			if (!pending.ok) {
				plugin.getLogger().warning("[character-ingest] pending fetch failed: " + pending.error);
			} else {
				List<JSONObject> creates = ProvinceSystemClient.parsePendingCreates(pending.body);
				if (!creates.isEmpty()) {
					List<JSONObject> results = applyAllOnMain(plugin, creates);
					if (!results.isEmpty()) {
						ProvinceSystemClient.SimpleResult ack = ProvinceSystemClient.ackCreates(buildAckJson(results));
						if (!ack.ok) {
							plugin.getLogger().warning("[character-ingest] ack failed: " + ack.error);
						} else {
							plugin.getLogger().info("[character-ingest] processed " + results.size() + " pending create(s)");
						}
					}
				}
			}
			KitCustomiseIngestService.pullNow(plugin);
		});
	}

	public static void pullForPlayerAsync(JavaPlugin plugin, UUID playerUuid) {
		if (plugin == null || playerUuid == null || Cache.devCharacters) {
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			ProvinceSystemClient.SimpleResult pending = ProvinceSystemClient.fetchPendingCreates();
			if (!pending.ok) {
				plugin.getLogger().warning("[character-ingest] pending fetch failed: " + pending.error);
				RosterSyncService.pushRosterNow(playerUuid);
				return;
			}
			List<JSONObject> mine = new ArrayList<>();
			for (JSONObject row : ProvinceSystemClient.parsePendingCreates(pending.body)) {
				String uuid = stringOf(row.get("player_uuid"));
				if (playerUuid.toString().equalsIgnoreCase(uuid)) {
					mine.add(row);
				}
			}
			List<JSONObject> results = applyAllOnMain(plugin, mine);
			if (!results.isEmpty()) {
				ProvinceSystemClient.ackCreates(buildAckJson(results));
			}
			KitCustomiseIngestService.pullNow(plugin);
			RosterSyncService.pushRosterNow(playerUuid);
		});
	}

	private static List<JSONObject> applyAllOnMain(JavaPlugin plugin, List<JSONObject> creates) {
		if (creates == null || creates.isEmpty()) {
			return List.of();
		}
		AtomicReference<List<JSONObject>> ref = new AtomicReference<>(List.of());
		CountDownLatch latch = new CountDownLatch(1);
		Bukkit.getScheduler().runTask(plugin, () -> {
			try {
				List<JSONObject> results = new ArrayList<>();
				for (JSONObject row : creates) {
					results.add(applyOne(row));
				}
				ref.set(results);
			} finally {
				latch.countDown();
			}
		});
		try {
			latch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return ref.get();
	}

	@SuppressWarnings("unchecked")
	private static JSONObject applyOne(JSONObject row) {
		JSONObject result = new JSONObject();
		String createId = stringOf(row.get("id"));
		result.put("id", createId);
		try {
			String playerUuidStr = stringOf(row.get("player_uuid"));
			UUID playerUuid = UUID.fromString(playerUuidStr);
			Object payloadObj = row.get("payload");
			if (!(payloadObj instanceof JSONObject)) {
				result.put("ok", false);
				result.put("error", "missing payload");
				return result;
			}
			JSONObject payload = (JSONObject) payloadObj;
			ApplyOutcome outcome = applyPayload(playerUuid, createId, payload);
			if (outcome.ok) {
				result.put("ok", true);
				result.put("character_id", outcome.characterId);
				RosterSyncService.pushRosterAsync(playerUuid);
			} else {
				result.put("ok", false);
				result.put("error", outcome.error);
			}
		} catch (Exception e) {
			result.put("ok", false);
			result.put("error", e.getMessage() != null ? e.getMessage() : "apply failed");
		}
		return result;
	}

	private static ApplyOutcome applyPayload(UUID playerUuid, String createId, JSONObject payload) {
		Player online = Bukkit.getPlayer(playerUuid);
		PlayerData pd;
		boolean inManager = false;

		if (online != null && PlayerManager.exists(online)) {
			pd = PlayerManager.get(online);
			inManager = true;
		} else if (online != null) {
			pd = DB.loadPlayer(online);
			if (pd == null) {
				pd = new PlayerData(online);
			}
		} else {
			pd = DB.loadPlayerData(playerUuid);
		}

		if (pd == null) {
			return ApplyOutcome.fail("could not load player data");
		}

		if (online != null) {
			if (!CharacterSlotService.hasFreeSlot(online, pd)) {
				return ApplyOutcome.fail("no free character slot");
			}
		} else {
			int alive = pd.getCharacters(Status.ALIVE).size();
			int cap = CharacterSlotService.getHardSlotCap();
			if (alive >= cap) {
				return ApplyOutcome.fail("no free character slot");
			}
		}

		for (RPCharacter existing : pd.getCharacters()) {
			if (existing.getId() != null && existing.getId().equalsIgnoreCase(createId)) {
				return ApplyOutcome.ok(createId);
			}
		}

		String name = stringOf(payload.get("name"));
		String raceId = stringOf(payload.get("race_id"));
		String classId = stringOf(payload.get("class_id"));
		String gender = stringOf(payload.get("gender"));
		String description = stringOf(payload.get("description"));
		int age;
		try {
			age = ((Number) payload.get("age")).intValue();
		} catch (Exception e) {
			return ApplyOutcome.fail("invalid age");
		}

		Race race = RaceLoader.getByString(raceId);
		if (race == null) {
			return ApplyOutcome.fail("unknown race: " + raceId);
		}

		List<Trait> traits = new ArrayList<>();
		JSONArray allTraits = (JSONArray) payload.get("all_traits");
		if (allTraits == null) {
			allTraits = (JSONArray) payload.get("traits");
		}
		if (allTraits != null) {
			for (Object o : allTraits) {
				Trait t = TraitLoader.getByString(String.valueOf(o));
				if (t != null) {
					traits.add(t);
				}
			}
		}

		List<String> clues = new ArrayList<>();
		JSONArray clueArr = (JSONArray) payload.get("clues");
		if (clueArr != null) {
			for (Object o : clueArr) {
				clues.add(String.valueOf(o));
			}
		}

		RPCharacter character = new RPCharacter(
			online,
			createId,
			name,
			false,
			Status.ALIVE,
			race,
			traits,
			classId,
			clues
		);
		character.setGender(gender);
		character.setPersonaDescription(description);
		List<String> nameColours = new ArrayList<>();
		Object coloursRaw = payload.get("name_colours");
		if (coloursRaw instanceof JSONArray colourArr) {
			for (Object entry : colourArr) {
				if (entry != null) {
					String code = entry.toString().trim();
					if (!code.isEmpty()) {
						nameColours.add(code);
					}
				}
			}
		}
		if (!nameColours.isEmpty()) {
			character.setNameColour(NameColour.of(nameColours));
		}
		String birthday = stringOf(payload.get("birthday"));
		if (birthday.isBlank() || FantasyCalendar.fromIso(birthday) == null) {
			String salt = stringOf(payload.get("client_request_id"));
			if (salt.isBlank()) {
				salt = createId;
			}
			birthday = AgeCalculator.birthdayFromAge(
				age, FantasyCalendar.getCurrentDate(), salt
			);
		}
		character.setBirthday(birthday);
		character.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond());
		ProstheticTraitRules.stripReplacedInjuries(character);
		character.ensureTraitStateDefaults();
		character.update();

		Object eighteenRaw = payload.get("eighteen");
		if (eighteenRaw instanceof Boolean) {
			pd.setEighteen((Boolean) eighteenRaw);
		}

		pd.addCharacter(character);

		net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle.fireCreated(online, pd.getUniqueId(), character);

		if (online != null && !pd.hasActiveCharacter()) {
			pd.setActiveCharacter(character);
			net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(online);
		}

		net.tfminecraft.rpcharacters.kit.KitService.onCharacterCreated(online, pd, character);

		if (inManager && online != null) {
			RPCharacters.getPlayerManager().savePlayer(online);
			RPCharacters.getPlayerManager().reevaluateFreeze(online);
		} else {
			DB.savePlayer(pd);
		}

		return ApplyOutcome.ok(createId);
	}

	@SuppressWarnings("unchecked")
	private static String buildAckJson(List<JSONObject> results) {
		JSONObject root = new JSONObject();
		JSONArray arr = new JSONArray();
		arr.addAll(results);
		root.put("results", arr);
		return root.toJSONString();
	}

	private static String stringOf(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private static final class ApplyOutcome {
		final boolean ok;
		final String characterId;
		final String error;

		private ApplyOutcome(boolean ok, String characterId, String error) {
			this.ok = ok;
			this.characterId = characterId;
			this.error = error;
		}

		static ApplyOutcome ok(String characterId) {
			return new ApplyOutcome(true, characterId, null);
		}

		static ApplyOutcome fail(String error) {
			return new ApplyOutcome(false, null, error);
		}
	}
}
