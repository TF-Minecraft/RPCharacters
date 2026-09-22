package net.tfminecraft.rpcharacters.grave;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;

public final class GraveLootRules {

	private static final String ADMIN_PERMISSION = "rpchar.grave.admin";

	private GraveLootRules() {}

	public static boolean canRecover(Player player, Grave grave) {
		if (player == null || grave == null) {
			return false;
		}
		return grave.isOwner(player.getUniqueId()) || player.hasPermission(ADMIN_PERMISSION);
	}

	public static boolean canSteal(Player player, Grave grave) {
		if (player == null || grave == null || canRecover(player, grave)) {
			return false;
		}
		if (!hasCanLootGravesTrait(player)) {
			return false;
		}
		if (!grave.isLocked()) {
			return true;
		}
		return grave.getKiller() != null && grave.getKiller().equals(player.getUniqueId());
	}

	public static boolean hasCanLootGravesTrait(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data == null || !data.hasActiveCharacter()) {
			return false;
		}
		RPCharacter character = data.getActiveCharacter();
		if (character == null) {
			return false;
		}
		for (Trait trait : character.getTraits()) {
			if (trait == null) {
				continue;
			}
			TraitData traitData = trait.getTraitData();
			if (traitData != null && traitData.canLootGraves()) {
				return true;
			}
		}
		return false;
	}
}
