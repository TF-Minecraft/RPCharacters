package net.tfminecraft.rpcharacters.injuries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.loaders.InjuryProgressionLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;
import net.tfminecraft.rpcharacters.enums.Status;

public final class RpInjureService {

	private static final Map<UUID, RpInjureSession> byAttacker = new ConcurrentHashMap<>();
	private static final Map<UUID, RpInjureSession> byTarget = new ConcurrentHashMap<>();

	private RpInjureService() {
	}

	public static boolean begin(Player attacker, Player target) {
		if (attacker == null || target == null) {
			return false;
		}
		String fail = validatePair(attacker, target, false);
		if (fail != null) {
			RPTexts.send(attacker, RPTexts.ERROR + fail);
			return false;
		}
		if (byAttacker.containsKey(attacker.getUniqueId()) || byTarget.containsKey(attacker.getUniqueId())) {
			RPTexts.send(attacker, RPTexts.ERROR + "You already have an injury request in progress.");
			return false;
		}
		if (byAttacker.containsKey(target.getUniqueId()) || byTarget.containsKey(target.getUniqueId())) {
			RPTexts.send(attacker, RPTexts.ERROR + target.getName() + " already has an injury request in progress.");
			return false;
		}

		RPCharacter character = PlayerManager.get(target).getActiveCharacter();
		List<Trait> available = listAvailableInjuries(character);
		if (available.isEmpty()) {
			RPTexts.send(attacker, RPTexts.ERROR + character.getName() + " has no injuries left to receive.");
			return false;
		}

		RpInjureSession session = new RpInjureSession(attacker.getUniqueId(), target.getUniqueId());
		byAttacker.put(session.attackerId, session);
		byTarget.put(session.targetId, session);
		RpInjureGui.openPicker(attacker, target, character);
		return true;
	}

	public static RpInjureSession getByAttacker(UUID attackerId) {
		return attackerId == null ? null : byAttacker.get(attackerId);
	}

	public static RpInjureSession getByTarget(UUID targetId) {
		return targetId == null ? null : byTarget.get(targetId);
	}

	public static RpInjureSession getInvolving(UUID playerId) {
		RpInjureSession session = getByAttacker(playerId);
		return session != null ? session : getByTarget(playerId);
	}

	public static void chooseInjury(Player attacker, String traitId) {
		RpInjureSession session = getByAttacker(attacker.getUniqueId());
		if (session == null || session.phase != RpInjureSession.Phase.PICKING) {
			return;
		}
		Player target = Bukkit.getPlayer(session.targetId);
		if (target == null || !target.isOnline()) {
			cancel(session, attacker, "That player is no longer online.");
			return;
		}
		String fail = validatePair(attacker, target, false);
		if (fail != null) {
			cancel(session, attacker, fail);
			return;
		}
		RPCharacter character = PlayerManager.get(target).getActiveCharacter();
		Trait trait = TraitLoader.getByString(traitId);
		if (trait == null || !isAvailable(character, trait)) {
			RPTexts.send(attacker, RPTexts.ERROR + "You cannot apply that injury.");
			return;
		}

		session.traitId = trait.getId();
		session.phase = RpInjureSession.Phase.AWAITING_ACCEPT;
		session.ignoreClose = true;
		attacker.closeInventory();
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> session.ignoreClose = false);

		RpInjureGui.openAccept(target, attacker, trait);
		RPTexts.send(attacker, RPTexts.MUTED + "Waiting for " + RPTexts.WARN + target.getName()
				+ RPTexts.MUTED + " to accept or decline.");
		RPTexts.send(target, RPTexts.WARN + attacker.getName() + RPTexts.MUTED
				+ " wants to injure you. Accept or decline in the menu.");
		scheduleTimeout(session);
	}

	public static void accept(Player target) {
		RpInjureSession session = getByTarget(target.getUniqueId());
		if (session == null || session.phase != RpInjureSession.Phase.AWAITING_ACCEPT) {
			return;
		}
		Player attacker = Bukkit.getPlayer(session.attackerId);
		if (attacker == null || !attacker.isOnline()) {
			finish(session, true);
			RPTexts.send(target, RPTexts.ERROR + "That player is no longer online.");
			return;
		}
		String fail = validatePair(attacker, target, true);
		if (fail != null) {
			notifyBoth(session, RPTexts.ERROR + fail);
			finish(session, true);
			return;
		}

		RPCharacter character = PlayerManager.get(target).getActiveCharacter();
		Trait trait = TraitLoader.getByString(session.traitId);
		if (trait == null || !isAvailable(character, trait)) {
			notifyBoth(session, RPTexts.ERROR + "That injury can no longer be applied.");
			finish(session, true);
			return;
		}

		TraitChangeService.addTrait(target, character, trait);
		TraitChangeService.sendGainedMessage(target, trait);
		RPTexts.send(attacker, RPTexts.SUCCESS + "Applied " + RPTexts.WARN + ChatColor.stripColor(trait.getName())
				+ RPTexts.SUCCESS + " to " + RPTexts.WARN + character.getName()
				+ RPTexts.SUCCESS + " (" + RPTexts.WARN + target.getName() + RPTexts.SUCCESS + ").");
		Bukkit.getPluginManager().callEvent(new CharacterInjuredEvent(target, attacker, character, trait.getId()));
		finish(session, true);
	}

	public static void decline(Player target) {
		RpInjureSession session = getByTarget(target.getUniqueId());
		if (session == null) {
			return;
		}
		Player attacker = Bukkit.getPlayer(session.attackerId);
		if (attacker != null && attacker.isOnline()) {
			RPTexts.send(attacker, RPTexts.ERROR + target.getName() + " declined the injury.");
		}
		RPTexts.send(target, RPTexts.MUTED + "You declined the injury.");
		finish(session, true);
	}

	public static void cancelFromClose(Player player) {
		RpInjureSession session = getInvolving(player.getUniqueId());
		if (session == null || session.ignoreClose) {
			return;
		}
		if (session.phase == RpInjureSession.Phase.PICKING
				&& player.getUniqueId().equals(session.attackerId)) {
			cancel(session, player, null);
			return;
		}
		if (session.phase == RpInjureSession.Phase.AWAITING_ACCEPT
				&& player.getUniqueId().equals(session.targetId)) {
			decline(player);
		}
	}

	public static void cancelInvolving(Player player, String reason) {
		RpInjureSession session = getInvolving(player.getUniqueId());
		if (session != null) {
			cancel(session, player, reason);
		}
	}

	public static List<Trait> listPickerInjuries(RPCharacter character) {
		List<Trait> out = new ArrayList<>();
		Set<String> owned = ownedInjuryIds(character);
		for (Trait trait : TraitLoader.get()) {
			if (trait == null || trait.getTraitData() == null || !trait.getTraitData().isInjuryKey()) {
				continue;
			}
			if (shouldHideHealing(trait, owned)) {
				continue;
			}
			out.add(trait);
		}
		return out;
	}

	public static boolean isOwned(RPCharacter character, Trait trait) {
		return trait != null && ownedInjuryIds(character).contains(trait.getId().toLowerCase(Locale.ROOT));
	}

	public static boolean isAvailable(RPCharacter character, Trait trait) {
		if (character == null || trait == null || trait.getTraitData() == null
				|| !trait.getTraitData().isInjuryKey()) {
			return false;
		}
		Set<String> owned = ownedInjuryIds(character);
		if (owned.contains(trait.getId().toLowerCase(Locale.ROOT))) {
			return false;
		}
		return !shouldHideHealing(trait, owned);
	}

	private static List<Trait> listAvailableInjuries(RPCharacter character) {
		List<Trait> out = new ArrayList<>();
		for (Trait trait : listPickerInjuries(character)) {
			if (!isOwned(character, trait)) {
				out.add(trait);
			}
		}
		return out;
	}

	private static boolean shouldHideHealing(Trait trait, Set<String> owned) {
		if (!trait.hasDuration()) {
			return false;
		}
		String permanentId = InjuryProgressionLoader.getPermanentId(trait.getId());
		return permanentId != null && owned.contains(permanentId.toLowerCase(Locale.ROOT));
	}

	private static Set<String> ownedInjuryIds(RPCharacter character) {
		Set<String> owned = new HashSet<>();
		if (character == null || character.getTraits() == null) {
			return owned;
		}
		for (Trait trait : character.getTraits()) {
			if (trait != null && trait.getId() != null) {
				owned.add(trait.getId().toLowerCase(Locale.ROOT));
			}
		}
		return owned;
	}

	private static String validatePair(Player attacker, Player target, boolean forAccept) {
		if (attacker.getUniqueId().equals(target.getUniqueId())) {
			return "You cannot injure yourself.";
		}
		if (!attacker.hasPermission("rpchar.injure")) {
			return "You do not have access to this command.";
		}
		PlayerData attackerData = PlayerManager.get(attacker);
		if (attackerData == null || !attackerData.hasActiveCharacter()
				|| !attackerData.getActiveCharacter().getStatus().equals(Status.ALIVE)) {
			return "You need an active character to injure someone.";
		}
		PlayerData targetData = PlayerManager.get(target);
		if (targetData == null || !targetData.hasActiveCharacter()
				|| !targetData.getActiveCharacter().getStatus().equals(Status.ALIVE)) {
			return target.getName() + " has no active character.";
		}
		if (attacker.getWorld() == null || target.getWorld() == null
				|| !attacker.getWorld().equals(target.getWorld())) {
			return "You must be in the same world.";
		}
		double range = Cache.rpInjureRange;
		if (attacker.getLocation().distance(target.getLocation()) > range) {
			return forAccept
					? "You are too far apart now."
					: "You must be within " + formatRange(range) + " blocks.";
		}
		return null;
	}

	private static String formatRange(double range) {
		if (Math.abs(range - Math.rint(range)) < 0.001) {
			return String.valueOf((int) Math.rint(range));
		}
		return String.valueOf(range);
	}

	private static void scheduleTimeout(RpInjureSession session) {
		cancelTimeout(session);
		int seconds = Cache.rpInjureTimeoutSeconds;
		session.timeoutTask = Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (getByAttacker(session.attackerId) != session) {
				return;
			}
			Player attacker = Bukkit.getPlayer(session.attackerId);
			Player target = Bukkit.getPlayer(session.targetId);
			if (attacker != null && attacker.isOnline()) {
				RPTexts.send(attacker, RPTexts.ERROR + "The injury request timed out.");
			}
			if (target != null && target.isOnline()) {
				RPTexts.send(target, RPTexts.MUTED + "The injury request timed out.");
			}
			finish(session, true);
		}, seconds * 20L);
	}

	private static void cancelTimeout(RpInjureSession session) {
		if (session.timeoutTask != null) {
			session.timeoutTask.cancel();
			session.timeoutTask = null;
		}
	}

	private static void cancel(RpInjureSession session, Player actor, String reason) {
		if (reason != null && actor != null) {
			RPTexts.send(actor, RPTexts.ERROR + reason);
		}
		Player other = otherPlayer(session, actor);
		if (other != null && other.isOnline() && session.phase == RpInjureSession.Phase.AWAITING_ACCEPT) {
			RPTexts.send(other, RPTexts.MUTED + "The injury request was cancelled.");
		}
		finish(session, true);
	}

	private static Player otherPlayer(RpInjureSession session, Player actor) {
		if (actor == null) {
			return null;
		}
		UUID otherId = actor.getUniqueId().equals(session.attackerId) ? session.targetId : session.attackerId;
		return Bukkit.getPlayer(otherId);
	}

	private static void notifyBoth(RpInjureSession session, String message) {
		Player attacker = Bukkit.getPlayer(session.attackerId);
		Player target = Bukkit.getPlayer(session.targetId);
		if (attacker != null && attacker.isOnline()) {
			RPTexts.send(attacker, message);
		}
		if (target != null && target.isOnline()) {
			RPTexts.send(target, message);
		}
	}

	private static void finish(RpInjureSession session, boolean closeInventories) {
		cancelTimeout(session);
		byAttacker.remove(session.attackerId, session);
		byTarget.remove(session.targetId, session);
		if (!closeInventories) {
			return;
		}
		session.ignoreClose = true;
		closeIfOurs(Bukkit.getPlayer(session.attackerId));
		closeIfOurs(Bukkit.getPlayer(session.targetId));
	}

	private static void closeIfOurs(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}
		if (player.getOpenInventory().getTopInventory().getHolder() instanceof RpInjureGui.Holder) {
			player.closeInventory();
		}
	}

	static final class RpInjureSession {
		enum Phase {
			PICKING,
			AWAITING_ACCEPT
		}

		final UUID attackerId;
		final UUID targetId;
		Phase phase = Phase.PICKING;
		String traitId;
		boolean ignoreClose;
		BukkitTask timeoutTask;

		RpInjureSession(UUID attackerId, UUID targetId) {
			this.attackerId = attackerId;
			this.targetId = targetId;
		}
	}
}
