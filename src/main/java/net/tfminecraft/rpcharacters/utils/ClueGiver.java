package net.tfminecraft.rpcharacters.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * API for other plugins (e.g. Thievery) to obtain leave-behind clues from a character.
 * Player-authored clues and automatic clues (race) form the clue pool.
 */
public final class ClueGiver {

	private static final String CLUE_ITEM_PDC_KEY = "clue_item";

	private ClueGiver() {}

	public static NamespacedKey clueItemKey() {
		return new NamespacedKey(RPCharacters.plugin, CLUE_ITEM_PDC_KEY);
	}

	/**
	 * Whether the player's active character has enough player-authored clues.
	 * Returns false if the player has no loaded data or no active character.
	 */
	public static boolean hasEnoughClues(Player player) {
		if (player == null) return false;
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) return false;
		return pd.getActiveCharacter().hasEnoughClues();
	}

	public static List<String> getAutomaticClues(RPCharacter character) {
		if (character == null || character.getRace() == null) {
			return Collections.emptyList();
		}
		Race race = character.getRace();
		String raceName = ClueFormatter.stripColor(race.getName());
		String article = startsWithVowel(raceName) ? "an" : "a";
		String clue = Cache.raceClueTemplate
				.replace("{a/an}", article)
				.replace("{race}", raceName);
		return Collections.singletonList(ClueFormatter.format(clue));
	}

	public static List<String> getCluePool(RPCharacter character) {
		if (character == null) return Collections.emptyList();
		List<String> pool = new ArrayList<>(character.getPlayerClues());
		pool.addAll(getAutomaticClues(character));
		return pool;
	}

	/**
	 * Returns a random clue from the character's full pool, or null if empty.
	 */
	public static String getRandomClue(RPCharacter character) {
		return getRandomClueExcluding(character, Collections.emptyList());
	}

	/**
	 * Returns a random clue not present in {@code excluded}, or null if none available.
	 */
	public static String getRandomClueExcluding(RPCharacter character, Collection<String> excluded) {
		List<String> pool = getCluePool(character);
		if (pool.isEmpty()) return null;

		List<String> available = new ArrayList<>();
		for (String clue : pool) {
			if (!excluded.contains(clue)) {
				available.add(clue);
			}
		}
		if (available.isEmpty()) return null;
		return available.get(ThreadLocalRandom.current().nextInt(available.size()));
	}

	private static boolean startsWithVowel(String word) {
		if (word == null || word.isEmpty()) return false;
		char c = Character.toLowerCase(word.charAt(0));
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
	}

	public static ItemStack getClueItem(Player p, Collection<String> excluded) {
		return new ItemStack(Material.AIR);
	}

	public static ItemStack getClueItem(RPCharacter character, Collection<String> excluded) {
		return new ItemStack(Material.AIR);
	}

	public static boolean isClueItem(ItemStack item) {
		if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
		ItemMeta meta = item.getItemMeta();
		return meta.getPersistentDataContainer().has(clueItemKey(), PersistentDataType.STRING);
	}

	public static String getClueText(ItemStack item) {
		if (!isClueItem(item)) return null;
		return item.getItemMeta().getPersistentDataContainer().get(clueItemKey(), PersistentDataType.STRING);
	}

	/**
	 * Spawns a world clue marker at the exact anchor location (visuals use anchor + Y offset only).
	 * Returns null if anchor/owner invalid or no clue available. Must be called on the main thread.
	 */
	public static SpawnedClue spawnClue(Location anchor, Player owner, Collection<String> exclude) {
		return spawnClue(anchor, null, owner, exclude, false);
	}

	public static SpawnedClue spawnClue(Location anchor, Player owner, Collection<String> exclude,
			boolean fallbackLocation) {
		return spawnClue(anchor, null, owner, exclude, fallbackLocation);
	}

	/**
	 * Spawns a world clue marker at anchor, with an optional target block for a particle ray to its center.
	 * Pass the block location of the affected chest/door (block coords); the ray ends at block center (+0.5).
	 */
	public static SpawnedClue spawnClue(Location anchor, Location targetBlock, Player owner, Collection<String> exclude) {
		return spawnClue(anchor, targetBlock, owner, exclude, false);
	}

	/**
	 * Spawns a world clue marker at anchor, with an optional target block for a particle ray to its center.
	 * When {@code fallbackLocation} is true and the anchor is not valid, searches for a random valid spot
	 * within {@code clue-spawn-radius} blocks of the owner.
	 */
	public static SpawnedClue spawnClue(Location anchor, Location targetBlock, Player owner,
			Collection<String> exclude, boolean fallbackLocation) {
		if (anchor == null || owner == null || anchor.getWorld() == null) return null;

		PlayerData pd = PlayerManager.get(owner);
		if (pd == null || !pd.hasActiveCharacter()) return null;

		Location spawnLocation = resolveSpawnLocation(anchor, owner, fallbackLocation);
		if (spawnLocation == null) return null;

		RPCharacter character = pd.getActiveCharacter();
		String clue = getRandomClueExcluding(character, exclude);
		if (clue == null) return null;
		return spawnClueWithText(anchor, targetBlock, owner, clue, fallbackLocation);
	}

	/**
	 * Spawns a world clue marker with a pre-selected clue text (no second random roll).
	 * Must be called on the main thread.
	 */
	public static SpawnedClue spawnClueWithText(Location anchor, Location targetBlock, Player owner,
			String clueText, boolean fallbackLocation) {
		if (anchor == null || owner == null || anchor.getWorld() == null) return null;
		if (clueText == null || clueText.isEmpty()) return null;

		PlayerData pd = PlayerManager.get(owner);
		if (pd == null || !pd.hasActiveCharacter()) return null;

		Location spawnLocation = resolveSpawnLocation(anchor, owner, fallbackLocation);
		if (spawnLocation == null) return null;

		return registerSpawnedClue(spawnLocation, targetBlock, owner, clueText);
	}

	private static SpawnedClue registerSpawnedClue(Location spawnLocation, Location targetBlock, Player owner,
			String clue) {
		Integer targetBlockX = null;
		Integer targetBlockY = null;
		Integer targetBlockZ = null;
		if (targetBlock != null && targetBlock.getWorld() != null
				&& targetBlock.getWorld().equals(spawnLocation.getWorld())) {
			targetBlockX = targetBlock.getBlockX();
			targetBlockY = targetBlock.getBlockY();
			targetBlockZ = targetBlock.getBlockZ();
		}

		long expiresAt = System.currentTimeMillis()
				+ (Cache.spawnedClueTimerHours * 60L * 60L * 1000L);
		long spawnedAt = System.currentTimeMillis();
		double potency = net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader.getSettings().getPotencyInitial();
		SpawnedClue spawned = new SpawnedClue(
				java.util.UUID.randomUUID(),
				spawnLocation.getWorld().getName(),
				spawnLocation.getX(),
				spawnLocation.getY(),
				spawnLocation.getZ(),
				clue,
				expiresAt,
				owner.getUniqueId(),
				targetBlockX,
				targetBlockY,
				targetBlockZ,
				null,
				potency,
				spawnedAt,
				null,
				0,
				0L);

		SpawnedClueManager.get().register(spawned);
		String logMessage = "Spawned clue at "
				+ spawnLocation.getWorld().getName() + " "
				+ spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ();
		if (targetBlockX != null) {
			logMessage += " -> target " + targetBlockX + "," + targetBlockY + "," + targetBlockZ;
		}
		logMessage += ": " + ClueFormatter.stripColor(clue);
		Database.log(owner, logMessage);
		return spawned;
	}

	/**
	 * Returns a random valid clue anchor within {@code clue-spawn-radius} blocks of the player,
	 * or null if none found after several attempts.
	 */
	public static Location getRandomValidClueLocation(Player player) {
		if (player == null) return null;
		return getRandomValidClueLocation(player.getLocation());
	}

	/**
	 * Returns a random valid clue anchor within {@code clue-spawn-radius} blocks of center,
	 * or null if none found after several attempts.
	 */
	public static Location getRandomValidClueLocation(Location center) {
		if (center == null || center.getWorld() == null) return null;

		int radius = Math.max(1, Cache.clueSpawnRadius);
		int attempts = radius * radius * 8;
		World world = center.getWorld();
		int centerX = center.getBlockX();
		int centerY = center.getBlockY();
		int centerZ = center.getBlockZ();

		for (int i = 0; i < attempts; i++) {
			int dx = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
			int dy = ThreadLocalRandom.current().nextInt(-2, 3);
			int dz = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
			if ((dx * dx) + (dz * dz) > radius * radius) continue;

			Location candidate = new Location(world, centerX + dx + 0.5, centerY + dy, centerZ + dz + 0.5);
			if (isValidClueAnchor(candidate) && !SpawnedClueManager.get().hasClueAt(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	public static boolean isValidClueAnchor(Location location) {
		if (location == null || location.getWorld() == null) return false;
		World world = location.getWorld();
		int x = location.getBlockX();
		int y = location.getBlockY();
		int z = location.getBlockZ();
		Block feet = world.getBlockAt(x, y, z);
		Block above = world.getBlockAt(x, y + 1, z);
		return feet.getType().isAir() && above.getType().isAir();
	}

	private static Location resolveSpawnLocation(Location anchor, Player owner, boolean fallbackLocation) {
		if (isValidClueAnchor(anchor) && !SpawnedClueManager.get().hasClueAt(anchor)) {
			return anchor.clone();
		}
		if (!fallbackLocation) return null;

		Location fallback = getRandomValidClueLocation(owner);
		if (fallback == null) {
			RPCharacters.plugin.getLogger().info(
					"[RPCharacters] Could not find fallback clue location for "
							+ owner.getName() + " near "
							+ anchor.getWorld().getName() + " "
							+ anchor.getBlockX() + "," + anchor.getBlockY() + "," + anchor.getBlockZ());
		}
		return fallback;
	}
}
