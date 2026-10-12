package net.tfminecraft.rpcharacters.progress;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.player.DefaultPlayerData;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.professions.ProfessionPointService;

/**
 * Keeps each character's level, experience, MMOCore progress and point pools apart. RPCharacters
 * stores them; MMOCore's own file only caches whichever character played last. While a service exists,
 * the legacy account fields stay as they were at cutover and are never written. Main thread only.
 */
public final class CharacterProgressService {
	private static volatile CharacterProgressService active;

	private final ProgressStore store;
	/** The character whose MMOCore copy is applied for each loaded account; reloaded accounts start empty. */
	private final Map<PlayerData, Applied> applied = Collections.synchronizedMap(new WeakHashMap<>());
	private final Set<Player> pendingCheckpoints = new HashSet<>();
	private final Map<PlayerData, RPCharacter> legacyActive = new WeakHashMap<>();
	private record Applied(RPCharacter character) {}

	/** Registers this service, so progression pools are per character from now on. */
	public CharacterProgressService(Path root) {
		this.store = new ProgressStore(root);
		active = this;
	}

	public static CharacterProgressService active() {
		return active;
	}

	public void close() {
		applied.clear();
		pendingCheckpoints.clear();
		legacyActive.clear();
		if (active == this) {
			active = null;
		}
	}

	/** Remember the original legacy character before the selector clears active flags. Null means none. */
	public void recordLegacyActive(PlayerData account, RPCharacter character) throws IOException {
		requireMainThread();
		if (character != null) {
			requireOwned(account, character);
		}
		legacyActive.put(account, character);
	}

	/**
	 * Run once per join, after MMOCore has loaded the player and before any character plays. Writes the
	 * baseline the first time, gives each cutover character missing a file a copy of it, and loads every
	 * readable file. Never rewrites an existing file. A character whose file is unreadable stays unloaded,
	 * so {@link #activate} refuses it; characters created after cutover wait for {@link #ensureFresh}.
	 */
	public void prepare(PlayerData account) throws IOException {
		requireMainThread();
		UUID owner = account.getUniqueId();
		ProgressBaseline baseline = store.readBaseline(owner).orElse(null);
		if (baseline == null) {
			baseline = captureBaseline(account);
			store.createBaseline(baseline);
		}
		legacyActive.remove(account);
		for (RPCharacter character : new ArrayList<>(account.getCharacters())) {
			if (character.getProgress() != null) {
				continue;
			}
			Optional<CharacterProgress> saved;
			try {
				saved = store.read(owner, character.getId());
			} catch (IOException unreadable) {
				RPCharacters.plugin.getLogger().log(Level.SEVERE, "Progress for character " + character.getId()
						+ " of " + owner + " cannot be read; it cannot be played until staff repair it", unreadable);
				continue;
			}
			if (saved.isPresent()) {
				character.setProgress(saved.get());
			} else if (baseline.includes(character.getId())) {
				CharacterProgress copy = baseline.cloneFor(character.getId());
				store.create(copy);
				character.setProgress(copy);
			}
		}
	}

	/**
	 * Gives a new character its progress: MMOCore defaults and empty pools, so nothing carries over from
	 * other characters. Loads an existing file instead, and gives a cutover character its baseline copy.
	 */
	public void ensureFresh(PlayerData account, RPCharacter character) throws IOException {
		requireMainThread();
		requireOwned(account, character);
		if (character.getProgress() != null) {
			return;
		}
		UUID owner = account.getUniqueId();
		Optional<CharacterProgress> saved = store.read(owner, character.getId());
		if (saved.isPresent()) {
			character.setProgress(saved.get());
			return;
		}
		Optional<ProgressBaseline> baseline = store.readBaseline(owner);
		CharacterProgress created = baseline.isPresent() && baseline.get().includes(character.getId())
				? baseline.get().cloneFor(character.getId())
				: fresh(owner, character.getId());
		store.create(created);
		character.setProgress(created);
	}

	/**
	 * Replaces the player's MMOCore progress with the character's. Call after making the character active
	 * and before {@link RPCharacter#tryActivate()}. Nothing is changed when a check fails.
	 */
	public void activate(PlayerData account, RPCharacter character) throws IOException {
		requireMainThread();
		Player player = requireLive(account, character);
		if (!MmoCorePlayerReady.isReady(player)) {
			throw new IOException("MMOCore has not finished loading " + player.getName());
		}
		CharacterProgress progress = requireProgress(account, character);
		applied.remove(account);
		try {
			ClassService.runApplying(player,
					() -> MmoProgressCodec.apply(net.Indyuce.mmocore.api.player.PlayerData.get(player), progress.mmo()));
		} catch (RuntimeException failure) {
			throw new IOException("Could not apply the progress of character " + character.getId(), failure);
		}
		applied.put(account, new Applied(character));
	}

	/** Read before activation: class application may checkpoint temporarily clamped resources. */
	public MmoProgressCodec.Resources resources(PlayerData account, RPCharacter character) throws IOException {
		requireMainThread();
		requireOwned(account, character);
		return MmoProgressCodec.resources(requireProgress(account, character).mmo());
	}

	/** The caller must also fence delayed restores with the activation's character session. */
	public void restoreResources(PlayerData account, RPCharacter character, MmoProgressCodec.Resources resources)
			throws IOException {
		requireMainThread();
		Player player = requireLive(account, character);
		if (!holds(account, character)) {
			throw new IOException("MMOCore does not hold the progress of character " + character.getId());
		}
		resources.apply(net.Indyuce.mmocore.api.player.PlayerData.get(player));
	}

	private static CharacterProgress requireProgress(PlayerData account, RPCharacter character) throws IOException {
		CharacterProgress progress = character.getProgress();
		if (progress == null) {
			throw new IOException("Character " + character.getId() + " has no readable progress");
		}
		if (!account.getUniqueId().equals(progress.getOwnerId())
				|| !character.getId().equals(progress.getCharacterId())) {
			throw new IOException("Character progress belongs to another owner or character");
		}
		return progress;
	}

	/**
	 * Saves the live character's MMOCore progress and pools as the next revision. Call before the
	 * character is deactivated or MMOCore attribute bases are cleared, and for checkpoints.
	 */
	public void save(PlayerData account, RPCharacter character) throws IOException {
		requireMainThread();
		Player player = requireLive(account, character);
		if (!holds(account, character)) {
			throw new IOException("MMOCore does not hold the progress of character " + character.getId());
		}
		net.Indyuce.mmocore.api.player.PlayerData mmo;
		try {
			mmo = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		} catch (RuntimeException unloaded) {
			throw new IOException("MMOCore no longer holds " + player.getName(), unloaded);
		}
		CharacterProgress progress = character.getProgress();
		ClassService.trackCharacterProgress(mmo, progress);
		progress.setMmo(MmoProgressCodec.capture(mmo));
		store.replace(progress);
	}

	/** Best-effort save of the player's live character; failures are logged and retried by the next save. */
	public static void checkpoint(Player player) {
		CharacterProgressService service = active;
		if (service == null || player == null) {
			return;
		}
		PlayerData account = PlayerManager.get(player);
		RPCharacter character = account == null ? null : account.getActiveCharacter();
		if (character == null || !service.holds(account, character) || ClassService.isApplying(player.getUniqueId())) {
			return;
		}
		try {
			service.save(account, character);
		} catch (IOException | RuntimeException failure) {
			RPCharacters.plugin.getLogger().log(Level.WARNING,
					"Could not checkpoint the progress of character " + character.getId(), failure);
		}
	}

	/** Coalesces XP events after MMOCore applies them; a delayed save never crosses an activation. */
	public static void scheduleCheckpoint(Player player) {
		CharacterProgressService service = active;
		if (service == null || player == null || ProgressRouting.activeProgress(player) == null
				|| !service.pendingCheckpoints.add(player)) {
			return;
		}
		BooleanSupplier current = ProgressRouting.sessionGuard(player);
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			service.pendingCheckpoints.remove(player);
			if (current.getAsBoolean()) {
				checkpoint(player);
			}
		}, 20L);
	}

	boolean holds(PlayerData account, RPCharacter character) {
		Applied value = applied.get(account);
		return active == this && value != null && value.character() == character
				&& Boolean.TRUE.equals(character.isActive()) && account.getActiveCharacter() == character;
	}

	Object token(PlayerData account) {
		return applied.get(account);
	}

	private CharacterProgress fresh(UUID owner, String characterId) {
		DefaultPlayerData defaults = MMOCore.plugin.playerDataManager.getDefaultData();
		CharacterProgress progress = new CharacterProgress(owner, characterId, CharacterProgress.FRESH,
				MmoProgressCodec.fresh());
		progress.setSkillPointsTotal(defaults.getSkillPoints());
		progress.setAttributePointsTotal(defaults.getAttributePoints());
		progress.setClassProgress(defaults.getLevel(), 0.0);
		return progress;
	}

	/** The shared progress as the legacy code would have used it, for every character the account has now. */
	private ProgressBaseline captureBaseline(PlayerData account) throws IOException {
		Player player = account.getPlayer();
		if (player == null || !player.isOnline() || !MmoCorePlayerReady.isReady(player)) {
			throw new IOException("The progress baseline for " + account.getUniqueId() + " needs MMOCore loaded");
		}
		net.Indyuce.mmocore.api.player.PlayerData mmo = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		CharacterProgress template = new CharacterProgress(account.getUniqueId(), null, CharacterProgress.BASELINE,
				MmoProgressCodec.capture(mmo));
		template.setSkillPointsTotal(account.needsSkillPointsMigration()
				? (int) Math.min(Integer.MAX_VALUE, (long) mmo.getSkillPoints() + mmo.countSkillPointsSpent())
				: account.getAccountSkillPointsTotal());
		template.setRewardSkillPoints(account.getAccountRewardSkillPoints());
		if (account.needsAttributePointsMigration()) {
			if (!legacyActive.containsKey(account)) {
				throw new IOException("Legacy active character was not recorded before attribute migration");
			}
			RPCharacter original = legacyActive.get(account);
			int spent = original == null ? 0 : AttributePointService.captureAllocationFromMmo(player, original);
			template.setAttributePointsTotal((int) Math.min(Integer.MAX_VALUE, (long) mmo.getAttributePoints() + spent));
		} else {
			template.setAttributePointsTotal(account.getAccountAttributePointsTotal());
		}
		ClassService.captureLegacyClassProgress(player, account, template);
		ProfessionPointService.captureLegacyLifetimePoints(mmo, account, template);
		List<String> ids = new ArrayList<>();
		for (RPCharacter character : account.getCharacters()) {
			ids.add(character.getId());
		}
		return new ProgressBaseline(account.getUniqueId(), ids, template);
	}

	private static Player requireLive(PlayerData account, RPCharacter character) throws IOException {
		requireOwned(account, character);
		Player player = account.getPlayer();
		if (player == null || !player.isOnline()) {
			throw new IOException("Account " + account.getUniqueId() + " is not online");
		}
		if (!Boolean.TRUE.equals(character.isActive()) || account.getActiveCharacter() != character) {
			throw new IOException("Character " + character.getId() + " is not the active character");
		}
		return player;
	}

	private static void requireOwned(PlayerData account, RPCharacter character) throws IOException {
		if (account == null || character == null || !account.getCharacters().contains(character)) {
			throw new IOException("Character is not in the account roster");
		}
	}

	private static void requireMainThread() {
		if (!Bukkit.isPrimaryThread()) {
			throw new IllegalStateException("Character progress requires the main thread");
		}
	}
}
