package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
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
		int spent = 0;
		for (AttributeInstance instance : mmoPd.getAttributes().getInstances()) {
			if (IgnoredAttributes.isIgnored(instance.getId())) {
				continue;
			}
			int creationBase = character.getCreationBaseAmount(instance.getId());
			int extra = Math.max(0, instance.getBase() - creationBase);
			if (extra > 0) {
				allocation.put(instance.getId().toLowerCase(), extra);
				spent += extra;
			}
		}
		character.setExtraAttributeAllocation(allocation);
		return spent;
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
			instance.setBase(instance.getBase() + entry.getValue());
		}
	}

	public static void applyFreeAttributePoints(Player player, RPCharacter character) {
		if (player == null || character == null) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int total = pd.getAccountAttributePointsTotal();
		int spent = character.getSpentExtraAttributePoints();
		mmoPd.setAttributePoints(Math.max(0, total - spent));
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
		if (player == null || amount <= 0) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		pd.addAccountAttributePoints(amount);
		RPCharacter active = pd.getActiveCharacter();
		if (active != null) {
			clampExcessAttributePool(player, active);
			applyFreeAttributePoints(player, active);
		}
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void migrateAttributePointsIfNeeded(Player player, PlayerData pd) {
		if (player == null || pd == null || !pd.needsAttributePointsMigration()) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int unspent = mmoPd.getAttributePoints();
		int spent = 0;
		RPCharacter active = pd.getActiveCharacter();
		if (active != null) {
			spent = captureAllocationFromMmo(player, active);
		}
		pd.setAccountAttributePointsTotal(unspent + spent);
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
		if (active == null) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		int spent = captureAllocationFromMmo(player, active);
		int mmoPool = mmoPd.getAttributePoints() + spent;
		int beforeTotal = pd.getAccountAttributePointsTotal();
		if (mmoPool != beforeTotal) {
			pd.setAccountAttributePointsTotal(mmoPool);
			RPCharacters.getPlayerManager().savePlayer(player);
		}
		clampExcessAttributePool(player, active);
		applyFreeAttributePoints(player, active);
		if (sender != null && Cache.attributePointsAdminDebugMessages) {
			int afterTotal = pd.getAccountAttributePointsTotal();
			int unspent = mmoPd.getAttributePoints();
			spent = active.getSpentExtraAttributePoints();
			RPTexts.sendPrefixed(sender, RPTexts.WARN + player.getName()
					+ " attribute points: account total " + RPTexts.SUCCESS + beforeTotal + " → " + afterTotal
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
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (player.isOnline()) {
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
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || character == null) {
			return;
		}
		int lifetime = pd.getAccountAttributePointsTotal();
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
		while (mmoPd.getAttributePoints() + character.getSpentExtraAttributePoints() > lifetime) {
			if (mmoPd.getAttributePoints() > 0) {
				mmoPd.setAttributePoints(mmoPd.getAttributePoints() - 1);
				continue;
			}
			break;
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
