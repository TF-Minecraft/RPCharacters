package net.tfminecraft.rpcharacters.permadeath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;

import net.tfminecraft.rpcharacters.loaders.InjuryPoolLoader;
import net.tfminecraft.rpcharacters.loaders.InjuryProgressionLoader;
import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;
import net.tfminecraft.rpcharacters.clues.discovery.ClueAdminModeService;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.enums.Status;

public final class PermadeathService {

	private static final String INJURY_KEY = "injury";
	private static final ConcurrentHashMap<UUID, Boolean> pendingPermadeathRespawn = new ConcurrentHashMap<>();
	private static final Set<UUID> pendingPermakillSounds = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> ignoreNextZoneDeath = ConcurrentHashMap.newKeySet();

	private PermadeathService() {
	}

	public static void handleDeath(Player player, Location deathLocation) {
		if (ignoreNextZoneDeath.remove(player.getUniqueId())) {
			return;
		}
		// A strike replaces the permadeath-zone roll, so the two never stack.
		if (EvilRpService.handleDeath(player)) {
			return;
		}
		if (ClueAdminModeService.isEnabled(player)) {
			return;
		}
		if (PermadeathBattleExemption.isInStartedBattle(player)) {
			return;
		}
		if (PermadeathAreaLookup.getPermadeathZoneAt(player, deathLocation) == null) {
			return;
		}

		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return;
		}

		RPCharacter character = pd.getActiveCharacter();

		if (rollPermadeath(computeRisk(character).getChancePercent())) {
			killCharacter(player, character, PermakillCause.PERMADEATH_ZONE);
			return;
		}

		List<Trait> healing = listHealingInjuryTraits(character);
		Trait toUpgrade = selectHealingInjuryToUpgrade(healing);
		if (toUpgrade != null) {
			convertTrait(player, character, toUpgrade, InjuryProgressionLoader.getPermanentId(toUpgrade.getId()));
			return;
		}

		Trait picked = InjuryPoolLoader.pickRandom(collectOwnedTraitIds(character));
		if (picked == null) {
			RPCharacters.plugin.getLogger().warning(
					"Injury pool empty or misconfigured for " + player.getName()
							+ " - skipping permadeath consequence.");
			return;
		}

		TraitChangeService.addTrait(player, character, picked);
		TraitChangeService.sendGainedMessage(player, picked);
		PermadeathTitles.showInjury(player, picked);
	}

	public static void handlePlayerRespawn(Player player, PlayerRespawnEvent event) {
		boolean permadeathRespawn = consumePendingPermadeathRespawn(player);
		if (permadeathRespawn) {
			Location spawn = WorldSpawnService.getSpawn();
			if (spawn != null) {
				event.setRespawnLocation(spawn);
			}
			PermadeathZoneListener.silentZoneSync(player, event.getRespawnLocation());
		}

		if (consumePendingPermakillSounds(player)) {
			PermadeathSounds.playPermakill(player);
		}

		if (permadeathRespawn) {
			RPCharacters.getPlayerManager().releaseFreeze(player);
			RPCharacters.getPlayerManager().reevaluateFreeze(player);
		}
	}

	public static boolean isAwaitingPermakillRespawn(Player player) {
		UUID id = player.getUniqueId();
		return pendingPermadeathRespawn.containsKey(id) || pendingPermakillSounds.contains(id);
	}

	public static PermadeathRisk computeRisk(RPCharacter character) {
		int injuryCount = countInjuryTraits(character);
		int chancePerInjury = PermadeathZoneLoader.getChancePerInjury();
		int chancePercent = injuryCount * chancePerInjury;
		return new PermadeathRisk(injuryCount, chancePercent, chancePerInjury);
	}

	public static boolean applyRandomInjury(Player player, RPCharacter character) {
		return giveRandomInjury(player, character) != null;
	}

	/** Adds a random healing injury from the pool. Returns it, or null when none are left. */
	public static Trait giveRandomInjury(Player player, RPCharacter character) {
		Trait picked = InjuryPoolLoader.pickRandom(collectOwnedTraitIds(character));
		if (picked == null) {
			return null;
		}
		TraitChangeService.addTrait(player, character, picked);
		TraitChangeService.sendGainedMessage(player, picked);
		return picked;
	}

	/**
	 * Turns one healing injury permanent when it has a progression target, otherwise adds
	 * a random permanent injury. Returns the permanent injury, or null when none are left.
	 */
	public static Trait givePermanentInjury(Player player, RPCharacter character) {
		Set<String> owned = collectOwnedTraitIds(character);
		for (Trait healing : listHealingInjuryTraits(character)) {
			String permanentId = InjuryProgressionLoader.getPermanentId(healing.getId());
			if (permanentId == null || permanentId.isBlank() || ownsTraitId(owned, permanentId)) {
				continue;
			}
			Trait permanent = TraitLoader.getByString(permanentId);
			if (permanent == null) {
				continue;
			}
			TraitChangeService.removeTrait(player, character, healing);
			TraitChangeService.addTrait(player, character, permanent);
			TraitChangeService.sendGainedMessage(player, permanent);
			return permanent;
		}
		return giveRandomPermanentInjury(player, character);
	}

	public static boolean applyRandomPermanentInjury(Player player, RPCharacter character) {
		return giveRandomPermanentInjury(player, character) != null;
	}

	private static Trait giveRandomPermanentInjury(Player player, RPCharacter character) {
		Set<String> owned = collectOwnedTraitIds(character);
		Set<String> targets = new LinkedHashSet<>(InjuryProgressionLoader.getProgressionMap().values());
		List<Trait> eligible = new ArrayList<>();
		for (String traitId : targets) {
			if (ownsTraitId(owned, traitId)) {
				continue;
			}
			Trait trait = TraitLoader.getByString(traitId);
			if (trait != null) {
				eligible.add(trait);
			}
		}
		if (eligible.isEmpty()) {
			return null;
		}
		Trait picked = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
		TraitChangeService.addTrait(player, character, picked);
		TraitChangeService.sendGainedMessage(player, picked);
		return picked;
	}

	public static boolean killCharacter(Player player, RPCharacter character) {
		return killCharacter(player, character, PermakillCause.OTHER);
	}

	public static boolean killCharacter(Player player, RPCharacter character, PermakillCause cause) {
		return killCharacter(player, character, cause, null);
	}

	public static boolean killCharacter(Player player, RPCharacter character, PermakillCause cause, Player killer) {
		if (!character.getStatus().equals(Status.ALIVE)) {
			return false;
		}

		CharacterPermakillEvent event = new CharacterPermakillEvent(player, character, cause, killer);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			return false;
		}

		// Kills applied inside the death event respawn at world spawn once the player clicks respawn.
		boolean duringDeath = cause == PermakillCause.PERMADEATH_ZONE
				|| (cause == PermakillCause.EVIL_RP_STRIKES && player.isDead());
		boolean wasActive = character.isActive();
		String killedName = character.getName();
		character.setStatus(Status.DEAD);
		String replacementName = null;
		if (wasActive) {
			character.deactivate();
			PlayerData pd = PlayerManager.get(player);
			if (pd != null && pd.getCharacters(Status.ALIVE).size() > 0) {
				RPCharacter replacement = pd.getCharacters(Status.ALIVE).get(0);
				pd.setActiveCharacter(replacement);
				replacementName = replacement.getName();
				net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(player);
			}
		}

		RPCharacters.getPlayerManager().savePlayer(player);

		net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushRosterForPlayer(player);

		if (!player.isDead() && !duringDeath) {
			RPCharacters.getPlayerManager().reevaluateFreeze(player);
		}

		if (duringDeath) {
			markPendingPermadeathRespawn(player);
			PermadeathZoneListener.clearZoneTracking(player);
			RPCharacters.getPlayerManager().releaseFreeze(player);
		}
		if (wasActive) {
			markPendingPermakillSounds(player);
			PermadeathTitles.showPermakill(player, killedName, replacementName, cause);
			if (!player.isDead()) {
				scheduleEntityDeath(player);
			}
		}
		return true;
	}

	public static void clearPendingPermadeathRespawn(Player player) {
		UUID uuid = player.getUniqueId();
		pendingPermadeathRespawn.remove(uuid);
		pendingPermakillSounds.remove(uuid);
		ignoreNextZoneDeath.remove(uuid);
	}

	public static void markPendingPermakillSounds(Player player) {
		pendingPermakillSounds.add(player.getUniqueId());
	}

	public static boolean consumePendingPermakillSounds(Player player) {
		return pendingPermakillSounds.remove(player.getUniqueId());
	}

	private static void scheduleEntityDeath(Player player) {
		if (!RPCharacters.plugin.isEnabled()) {
			// Shutting down: the character is already saved as dead and the player is about to leave.
			return;
		}
		ignoreNextZoneDeath.add(player.getUniqueId());
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			if (!player.isOnline() || player.isDead()) {
				ignoreNextZoneDeath.remove(player.getUniqueId());
				return;
			}
			player.setHealth(0);
		});
	}

	public static void markPendingPermadeathRespawn(Player player) {
		pendingPermadeathRespawn.put(player.getUniqueId(), Boolean.TRUE);
	}

	public static boolean consumePendingPermadeathRespawn(Player player) {
		return pendingPermadeathRespawn.remove(player.getUniqueId()) != null;
	}

	private static boolean rollPermadeath(int chancePercent) {
		if (chancePercent <= 0) {
			return false;
		}
		if (chancePercent >= 100) {
			return true;
		}
		return ThreadLocalRandom.current().nextInt(100) < chancePercent;
	}

	/**
	 * One zone death changes a single injury: upgrade one existing healing injury,
	 * or leave the caller to roll a new one when this returns null.
	 */
	static Trait selectHealingInjuryToUpgrade(List<Trait> healingInjuries) {
		if (healingInjuries == null || healingInjuries.isEmpty()) {
			return null;
		}
		return healingInjuries.get(0);
	}

	private static List<Trait> listHealingInjuryTraits(RPCharacter character) {
		List<Trait> healing = new ArrayList<>();
		for (Trait trait : character.getTraits()) {
			if (trait.getTraitData().getKey() != null
					&& trait.getTraitData().getKey().equalsIgnoreCase(INJURY_KEY)
					&& trait.hasDuration()) {
				healing.add(trait);
			}
		}
		return healing;
	}

	private static void convertTrait(Player player, RPCharacter character, Trait healing, String permanentId) {
		TraitChangeService.removeTrait(player, character, healing);

		if (permanentId == null || permanentId.isBlank()) {
			RPCharacters.plugin.getLogger().warning(
					"No permanent progression target for healing trait '" + healing.getId()
							+ "' on character " + character.getName() + ".");
			return;
		}

		if (ownsTraitId(character, permanentId)) {
			return;
		}

		Trait permanent = TraitLoader.getByString(permanentId);
		if (permanent == null) {
			RPCharacters.plugin.getLogger().warning(
					"Permanent trait '" + permanentId + "' not found while converting '"
							+ healing.getId() + "' on character " + character.getName() + ".");
			return;
		}

		TraitChangeService.addTrait(player, character, permanent);
		TraitChangeService.sendGainedMessage(player, permanent);
	}

	private static boolean ownsTraitId(RPCharacter character, String traitId) {
		return ownsTraitId(collectOwnedTraitIds(character), traitId);
	}

	private static boolean ownsTraitId(Set<String> ownedTraitIds, String traitId) {
		if (traitId == null) {
			return false;
		}
		for (String owned : ownedTraitIds) {
			if (owned.equalsIgnoreCase(traitId)) {
				return true;
			}
		}
		return false;
	}

	private static int countInjuryTraits(RPCharacter character) {
		int count = 0;
		for (Trait trait : character.getTraits()) {
			if (trait.getTraitData().getKey() != null
					&& trait.getTraitData().getKey().equalsIgnoreCase(INJURY_KEY)) {
				count++;
			}
		}
		return count;
	}

	private static Set<String> collectOwnedTraitIds(RPCharacter character) {
		Set<String> ids = new HashSet<>();
		for (Trait trait : character.getTraits()) {
			ids.add(trait.getId().toLowerCase());
		}
		return ids;
	}
}
