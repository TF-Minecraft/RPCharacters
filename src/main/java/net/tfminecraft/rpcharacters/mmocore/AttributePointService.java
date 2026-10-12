package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.progress.CharacterProgressService;
import net.tfminecraft.rpcharacters.progress.ProgressPools;
import net.tfminecraft.rpcharacters.progress.ProgressRouting;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class AttributePointService {

	private AttributePointService() {}

	public static int captureAllocationFromMmo(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return 0;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		Map<String, Integer> allocation = new HashMap<>();
		long spent = 0;
		for (AttributeInstance instance : mmoPd.getAttributes().getInstances()) {
			if (IgnoredAttributes.isIgnored(instance.getId())) {
				continue;
			}
			int creationBase = character.getCreationBaseAmount(instance.getId());
			int extra = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) instance.getBase() - creationBase));
			if (extra > 0) {
				allocation.put(instance.getId().toLowerCase(java.util.Locale.ROOT), extra);
				spent += extra;
			}
		}
		character.setExtraAttributeAllocation(allocation);
		return (int) Math.min(Integer.MAX_VALUE, spent);
	}

	public static void applyAllocationToMmo(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		for (Map.Entry<String, Integer> entry : character.getExtraAttributeAllocation().entrySet()) {
			if (IgnoredAttributes.isIgnored(entry.getKey())) {
				continue;
			}
			AttributeInstance instance = mmoPd.getAttributes().getInstance(entry.getKey());
			if (instance == null || entry.getValue() == null || entry.getValue() <= 0) {
				continue;
			}
			instance.setBase((int) Math.min(Integer.MAX_VALUE, (long) instance.getBase() + entry.getValue()));
		}
	}

	public static void applyFreeAttributePoints(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		ProgressPools pools = ProgressRouting.pools(PlayerManager.get(player), character);
		if (pools == null) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int total = pools.getAttributePointsTotal();
		int spent = character.getSpentExtraAttributePoints();
		mmoPd.setAttributePoints(Math.max(0, total - spent));
	}

	/** Lifetime attribute points of the account, or of the live character when progress is per character. */
	public static int lifetimePoints(Player player) {
		ProgressPools pools = player == null ? null : ProgressRouting.pools(PlayerManager.get(player));
		return pools == null ? 0 : pools.getAttributePointsTotal();
	}

	/** Unspent attribute points: the lifetime points less what the active character has allocated. */
	public static int freePoints(Player player) {
		if (player == null) {
			return 0;
		}
		PlayerData pd = PlayerManager.get(player);
		ProgressPools pools = ProgressRouting.pools(pd);
		if (pools == null) {
			return 0;
		}
		RPCharacter active = pd.getActiveCharacter();
		int spent = active == null ? 0 : active.getSpentExtraAttributePoints();
		return Math.max(0, pools.getAttributePointsTotal() - spent);
	}

	public static void applyCharacterAttributes(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		// MMOCore persists attribute bases across sessions. Login/reload must not stack
		// creation modifiers on top of values left from the previous session.
		clearMmoAttributeBases(player);
		Integrator integrator = new Integrator();
		integrator.integrate(player, character);
		applyAllocationToMmo(player, character);
		applyFreeAttributePoints(player, character);
	}

	/** Clears MMO attribute bases so the next apply starts from a known state. */
	public static void clearMmoAttributeBases(Player player) {
		if (player == null) {
			return;
		}
		zeroAllBases(player);
		net.Indyuce.mmocore.api.player.PlayerData.get(player).setAttributePoints(0);
	}

	public static void syncOnDeactivate(RPCharacter character) {
		if (character == null || character.getOwner() == null) {
			return;
		}
		Player player = character.getOwner();
		captureAllocationFromMmo(player, character);
		Integrator integrator = new Integrator();
		integrator.stripCreationLayer(player, character);
		zeroAllBases(player);
		net.Indyuce.mmocore.api.player.PlayerData.get(player).setAttributePoints(0);
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void syncOnActivate(RPCharacter character) {
		if (character == null || character.getOwner() == null) {
			return;
		}
		Player player = character.getOwner();
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		clampExcessAttributePool(player, character);
		applyCharacterAttributes(player, character);
	}

	public static void grantAttributePoints(Player player, int amount) {
		tryGrantAttributePoints(player, amount);
	}

	/** @return false when there were no pools to credit, such as in the character room */
	public static boolean tryGrantAttributePoints(Player player, int amount) {
		if (player == null || amount <= 0) {
			return false;
		}
		PlayerData pd = PlayerManager.get(player);
		ProgressPools pools = ProgressRouting.pools(pd);
		if (pools == null) {
			return false;
		}
		pools.addAttributePoints(amount);
		RPCharacter active = pd.getActiveCharacter();
		if (active != null) {
			clampExcessAttributePool(player, active);
			applyFreeAttributePoints(player, active);
		}
		RPCharacters.getPlayerManager().savePlayer(player);
		CharacterProgressService.checkpoint(player);
		return true;
	}

	public static void migrateAttributePointsIfNeeded(Player player, PlayerData pd) {
		// Per-character totals start from the progress baseline instead.
		if (player == null || pd == null || ProgressRouting.enabled() || !pd.needsAttributePointsMigration()) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int unspent = mmoPd.getAttributePoints();
		int spent = 0;
		RPCharacter active = pd.getActiveCharacter();
		if (active != null) {
			spent = captureAllocationFromMmo(player, active);
		}
		pd.setAccountAttributePointsTotal((int) Math.min(Integer.MAX_VALUE, (long) unspent + spent));
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void syncAttributePoints(Player player) {
		syncAttributePoints(player, null);
	}

	public static void syncAttributePoints(Player player, CommandSender sender) {
		if (player == null) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		RPCharacter active = pd.getActiveCharacter();
		ProgressPools pools = ProgressRouting.pools(pd, active);
		if (active == null || pools == null) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int spent = captureAllocationFromMmo(player, active);
		int mmoPool = (int) Math.min(Integer.MAX_VALUE, (long) mmoPd.getAttributePoints() + spent);
		int beforeTotal = pools.getAttributePointsTotal();
		if (mmoPool != beforeTotal) {
			pools.setAttributePointsTotal(mmoPool);
			RPCharacters.getPlayerManager().savePlayer(player);
			CharacterProgressService.checkpoint(player);
		}
		clampExcessAttributePool(player, active);
		applyFreeAttributePoints(player, active);
		if (sender != null && Cache.attributePointsAdminDebugMessages) {
			int afterTotal = pools.getAttributePointsTotal();
			int unspent = mmoPd.getAttributePoints();
			spent = active.getSpentExtraAttributePoints();
			RPTexts.sendPrefixed(sender, RPTexts.WARN + player.getName()
					+ " attribute points: " + pools.holder() + " total " + RPTexts.SUCCESS + beforeTotal + " → " + afterTotal
					+ RPTexts.WARN + ", unspent now " + RPTexts.SUCCESS + unspent
					+ RPTexts.WARN + " (spent on character: " + RPTexts.SUCCESS + spent
					+ RPTexts.WARN + ", mmo pool: " + RPTexts.SUCCESS + mmoPool + RPTexts.WARN + ")");
			RPTexts.sendPrefixed(sender, RPTexts.WARN + "Allocation: " + RPTexts.MUTED
					+ formatAllocationBreakdown(active));
		}
	}

	public static void scheduleSyncAttributePoints(Player player) {
		scheduleSyncAttributePoints(player, null);
	}

	public static void scheduleSyncAttributePoints(Player player, CommandSender sender) {
		if (player == null) {
			return;
		}
		BooleanSupplier current = ProgressRouting.sessionGuard(player);
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (current.getAsBoolean()) {
				syncAttributePoints(player, sender);
			}
		}, 1L);
	}

	public static void refreshAfterCreationLayerChange(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		captureAllocationFromMmo(player, character);
		clampExcessAttributePool(player, character);
		applyFreeAttributePoints(player, character);
	}

	public static void clampExcessAttributePool(Player player, RPCharacter character) {
		ProgressPools pools = character == null ? null : ProgressRouting.pools(PlayerManager.get(player), character);
		if (pools == null) {
			return;
		}
		int lifetime = pools.getAttributePointsTotal();
		while (character.getSpentExtraAttributePoints() > lifetime) {
			Map<String, Integer> mutable = new HashMap<>(character.getExtraAttributeAllocation());
			String target = null;
			int highest = 0;
			for (Map.Entry<String, Integer> entry : mutable.entrySet()) {
				if (IgnoredAttributes.isIgnored(entry.getKey())) {
					continue;
				}
				if (entry.getValue() != null && entry.getValue() > highest) {
					highest = entry.getValue();
					target = entry.getKey();
				}
			}
			if (target == null) {
				break;
			}
			int next = mutable.get(target) - 1;
			if (next <= 0) {
				mutable.remove(target);
			} else {
				mutable.put(target, next);
			}
			character.setExtraAttributeAllocation(mutable);
			AttributeInstance instance = net.Indyuce.mmocore.api.player.PlayerData.get(player).getAttributes().getInstance(target);
			if (instance != null && instance.getBase() > character.getCreationBaseAmount(target)) {
				instance.setBase(instance.getBase() - 1);
			}
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int allowedUnspent = Math.max(0, lifetime - character.getSpentExtraAttributePoints());
		if (mmoPd.getAttributePoints() > allowedUnspent) {
			mmoPd.setAttributePoints(allowedUnspent);
		}
	}

	private static void zeroAllBases(Player player) {
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		for (AttributeInstance instance : mmoPd.getAttributes().getInstances()) {
			if (IgnoredAttributes.isIgnored(instance.getId())) {
				continue;
			}
			instance.setBase(0);
		}
	}

	private static String formatAllocationBreakdown(RPCharacter character) {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : character.getExtraAttributeAllocation().entrySet()) {
			if (entry.getValue() != null && entry.getValue() > 0) {
				parts.add(entry.getKey() + " +" + entry.getValue());
			}
		}
		if (parts.isEmpty()) {
			return "none";
		}
		return String.join(", ", parts);
	}
}
