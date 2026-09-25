package net.tfminecraft.rpcharacters.professions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.Indyuce.mmocore.experience.Profession;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionPointService {
	private ProfessionPointService() {}

	public static int getLifetimePoints(Player player, String professionId) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return 0;
		}
		return pd.getAccountProfessionPoints(professionId);
	}

	public static int getFreePoints(Player player, String professionId) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return 0;
		}
		RPCharacter character = pd.getActiveCharacter();
		if (character == null) {
			return 0;
		}
		int lifetime = pd.getAccountProfessionPoints(professionId);
		int spent = character.getSpentPointsOnProfession(professionId);
		return Math.max(0, lifetime - spent);
	}

	public static void grantPoints(Player player, String professionId, int amount) {
		if (player == null || professionId == null || amount <= 0) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		pd.addAccountProfessionPoints(professionId, amount);
		RPCharacters.getPlayerManager().savePlayer(player);
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
		grantPoints(player, definition.getId(), gained);
		if (gained < 2) {
			RPTexts.send(player, RPTexts.MUTED + "Gained " + RPTexts.WARN + "1 " + RPTexts.SUCCESS
					+ definition.getName() + RPTexts.MUTED + " point!");
		} else {
			RPTexts.send(player, RPTexts.MUTED + "Gained " + RPTexts.WARN + gained + " " + RPTexts.SUCCESS
					+ definition.getName() + RPTexts.MUTED + " points!");
		}
	}

	public static void bootstrapLifetimeFromMmoCore(Player player) {
		if (player == null) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || pd.isProfessionPointsInitialized()) {
			return;
		}
		net.Indyuce.mmocore.api.player.PlayerData mmoPd = net.Indyuce.mmocore.api.player.PlayerData.get(player);
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
}
