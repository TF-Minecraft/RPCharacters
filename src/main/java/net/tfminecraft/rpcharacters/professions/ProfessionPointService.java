package net.tfminecraft.rpcharacters.professions;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.Indyuce.mmocore.experience.Profession;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.progress.CharacterProgress;
import net.tfminecraft.rpcharacters.progress.CharacterProgressService;
import net.tfminecraft.rpcharacters.progress.ProgressPools;
import net.tfminecraft.rpcharacters.progress.ProgressRouting;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionPointService {
	private ProfessionPointService() {}

	public static int getLifetimePoints(Player player, String professionId) {
		ProgressPools pools = ProgressRouting.pools(PlayerManager.get(player));
		return pools == null ? 0 : pools.getProfessionPoints(professionId);
	}

	public static int getFreePoints(Player player, String professionId) {
		PlayerData pd = PlayerManager.get(player);
		RPCharacter character = pd == null ? null : pd.getActiveCharacter();
		ProgressPools pools = ProgressRouting.pools(pd, character);
		if (character == null || pools == null) {
			return 0;
		}
		int lifetime = pools.getProfessionPoints(professionId);
		int spent = character.getSpentPointsOnProfession(professionId);
		return Math.max(0, lifetime - spent);
	}

	public static void grantPoints(Player player, String professionId, int amount) {
		tryGrantPoints(player, professionId, amount);
	}

	/** @return false when there were no pools to credit, such as in the character room */
	public static boolean tryGrantPoints(Player player, String professionId, int amount) {
		if (player == null || professionId == null || amount <= 0) {
			return false;
		}
		ProgressPools pools = ProgressRouting.pools(PlayerManager.get(player));
		if (pools == null) {
			return false;
		}
		pools.addProfessionPoints(professionId, amount);
		RPCharacters.getPlayerManager().savePlayer(player);
		CharacterProgressService.checkpoint(player);
		return true;
	}

	public static void onProfessionLevelUp(PlayerLevelChangeEvent event) {
		if (event.getReason() != PlayerLevelChangeEvent.Reason.LEVEL_UP) {
			return;
		}
		Profession profession = event.getProfession();
		if (profession == null) {
			return;
		}
		ProfessionDefinition definition = ProfessionRegistry.getProfession(profession.getId());
		if (definition == null) {
			return;
		}
		Player player = event.getPlayer();
		int gained = event.getNewLevel() - event.getOldLevel();
		if (gained <= 0) {
			return;
		}
		if (!tryGrantPoints(player, definition.getId(), gained)) {
			return;
		}
		if (gained < 2) {
			RPTexts.send(player, RPTexts.MUTED + "Gained " + RPTexts.WARN + "1 " + RPTexts.SUCCESS
					+ definition.getName() + RPTexts.MUTED + " point!");
		} else {
			RPTexts.send(player, RPTexts.MUTED + "Gained " + RPTexts.WARN + gained + " " + RPTexts.SUCCESS
					+ definition.getName() + RPTexts.MUTED + " points!");
		}
	}

	public static void bootstrapLifetimeFromMmoCore(Player player) {
		// Per-character pools start from the progress baseline instead.
		if (player == null || ProgressRouting.enabled()) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || pd.isProfessionPointsInitialized()) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
		if (mmoPd == null) {
			return;
		}
		for (ProfessionDefinition profession : ProfessionRegistry.getProfessions()) {
			Profession mmoProf = MMOCore.plugin.professionManager.get(profession.getId());
			if (mmoProf == null) {
				continue;
			}
			int fromLevel = Math.max(0, mmoPd.getCollectionSkills().getLevel(mmoProf) - 1);
			int current = pd.getAccountProfessionPoints(profession.getId());
			if (fromLevel > current) {
				pd.setAccountProfessionPoints(profession.getId(), fromLevel);
			}
		}
		pd.setProfessionPointsInitialized(true);
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	/**
	 * The lifetime points the legacy code knew for the account: the saved totals, else one point per
	 * profession level above the first in MMOCore, the way {@link #bootstrapLifetimeFromMmoCore} counts them.
	 */
	public static void captureLegacyLifetimePoints(net.Indyuce.mmocore.api.player.PlayerData mmoPd, PlayerData account,
			CharacterProgress target) {
		account.getAccountProfessionPointsMap().forEach(target::setProfessionPoints);
		if (!account.isProfessionPointsInitialized()) {
			countFromMmoCore(mmoPd, target);
		}
	}

	/**
	 * Recounts the live pools' profession points from MMOCore levels, for a refund that also clears the
	 * character's upgrades. While progress is shared this resets the account's totals, as before.
	 */
	public static void resetLifetimePoints(Player player) {
		if (player == null) {
			return;
		}
		if (!ProgressRouting.enabled()) {
			PlayerData pd = PlayerManager.get(player);
			if (pd != null) {
				pd.clearAccountProfessionPoints();
				pd.setProfessionPointsInitialized(false);
				bootstrapLifetimeFromMmoCore(player);
			}
			return;
		}
		CharacterProgress progress = ProgressRouting.activeProgress(player);
		if (progress == null) {
			return;
		}
		progress.clearProfessionPoints();
		countFromMmoCore(net.Indyuce.mmocore.api.player.PlayerData.get(player), progress);
		RPCharacters.getPlayerManager().savePlayer(player);
		CharacterProgressService.checkpoint(player);
	}

	private static void countFromMmoCore(net.Indyuce.mmocore.api.player.PlayerData mmoPd, CharacterProgress target) {
		for (ProfessionDefinition profession : ProfessionRegistry.getProfessions()) {
			Profession mmoProf = MMOCore.plugin.professionManager.get(profession.getId());
			if (mmoProf == null) {
				continue;
			}
			int fromLevel = Math.max(0, mmoPd.getCollectionSkills().getLevel(mmoProf) - 1);
			if (fromLevel > target.getProfessionPoints(profession.getId())) {
				target.setProfessionPoints(profession.getId(), fromLevel);
			}
		}
	}
}
