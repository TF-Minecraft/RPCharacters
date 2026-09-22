package net.tfminecraft.rpcharacters.playtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.database.PlaytimeIndexDatabase;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Counts the real time a character spends online, as opposed to
 * {@link RPCharacter#getAgeSeconds()} which is wall-clock time since creation and
 * keeps climbing while the player is away.
 *
 * <p>Playtime accrues to the active character only. A player between characters
 * (freshly permakilled, or mid-creation) reads as zero, so a dead character stops
 * earning the moment it dies.
 *
 * <p>The index is the read surface for other plugins: {@link #getSeconds(String)} is a
 * map lookup with no disk access, which is what callers on a hot path need. It is
 * persisted so offline players still have a figure after a restart.
 */
public final class PlaytimeService {

	/** Credited per tick. Partial minutes at logout are dropped. */
	private static final int TICK_SECONDS = 60;
	private static final long TICK_INTERVAL_TICKS = TICK_SECONDS * 20L;
	private static final long AUTOSAVE_INTERVAL_TICKS = 200L;

	private static final Map<UUID, PlaytimeIndexDatabase.Entry> byUuid = new ConcurrentHashMap<>();
	private static final Map<String, Integer> byName = new ConcurrentHashMap<>();

	private static volatile boolean dirty = false;

	private PlaytimeService() {
	}

	public static void loadAllFromDisk() {
		byUuid.clear();
		byName.clear();
		for (PlaytimeIndexDatabase.Entry entry : PlaytimeIndexDatabase.loadAll()) {
			byUuid.put(entry.getUuid(), entry);
			byName.put(key(entry.getName()), entry.getSeconds());
		}
		dirty = false;
		Bukkit.getLogger().info("[RPCharacters] Loaded " + byUuid.size() + " playtime index entries");
	}

	public static void startTicks() {
		Bukkit.getLogger().info("[RPCharacters] Starting Playtime Service");
		new BukkitRunnable() {
			@Override
			public void run() {
				for (Player p : Bukkit.getOnlinePlayers()) {
					accrue(p);
				}
			}
		}.runTaskTimer(RPCharacters.plugin, TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);

		new BukkitRunnable() {
			@Override
			public void run() {
				if (!dirty) return;
				dirty = false;
				saveAllNow();
			}
		}.runTaskTimer(RPCharacters.plugin, AUTOSAVE_INTERVAL_TICKS, AUTOSAVE_INTERVAL_TICKS);
	}

	public static void shutdown() {
		saveAllNow();
	}

	public static void saveAllNow() {
		PlaytimeIndexDatabase.saveAll(new ArrayList<>(byUuid.values()));
	}

	/** Credits one tick to the active character, then republishes the player's figure. */
	private static void accrue(Player p) {
		RPCharacter active = activeCharacter(p);
		if (active != null) {
			active.addOnlinePlaytimeSeconds(TICK_SECONDS);
		}
		publish(p, active);
	}

	/**
	 * Republishes a player's figure without crediting time. Call after a join or a
	 * character switch so the index reflects the new active character immediately
	 * instead of trailing by up to a minute.
	 */
	public static void refresh(Player p) {
		publish(p, activeCharacter(p));
	}

	private static RPCharacter activeCharacter(Player p) {
		if (p == null) return null;
		PlayerData pd = PlayerManager.get(p);
		if (pd == null || !pd.hasActiveCharacter()) return null;
		return pd.getActiveCharacter();
	}

	private static void publish(Player p, RPCharacter active) {
		if (p == null || p.getUniqueId() == null || p.getName() == null) return;
		int seconds = active == null ? 0 : active.getOnlinePlaytimeSeconds();

		PlaytimeIndexDatabase.Entry previous = byUuid.get(p.getUniqueId());
		if (previous != null && !previous.getName().equalsIgnoreCase(p.getName())) {
			// Renamed since we last saw them; drop the key nobody will ask for again.
			byName.remove(key(previous.getName()));
		}
		if (previous != null && previous.getSeconds() == seconds
				&& previous.getName().equals(p.getName())) {
			return;
		}

		byUuid.put(p.getUniqueId(), new PlaytimeIndexDatabase.Entry(p.getUniqueId(), p.getName(), seconds));
		byName.put(key(p.getName()), seconds);
		dirty = true;
	}

	/**
	 * Online seconds on this player's active character, or null when the player is
	 * unknown to the index. Callers should treat null as "no answer" rather than zero:
	 * a player who has never been seen is different from one with a fresh character.
	 */
	public static Integer getSeconds(String playerName) {
		if (playerName == null || playerName.isBlank()) return null;
		return byName.get(key(playerName));
	}

	public static Integer getSeconds(UUID uuid) {
		if (uuid == null) return null;
		PlaytimeIndexDatabase.Entry entry = byUuid.get(uuid);
		return entry == null ? null : entry.getSeconds();
	}

	/** Every known player, for admin readouts. */
	public static List<PlaytimeIndexDatabase.Entry> getAll() {
		return new ArrayList<>(byUuid.values());
	}

	private static String key(String playerName) {
		return playerName.toLowerCase(Locale.ROOT);
	}
}
