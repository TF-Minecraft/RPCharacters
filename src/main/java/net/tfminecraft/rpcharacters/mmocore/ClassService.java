package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.player.profess.SavedClassInformation;
import net.Indyuce.mmocore.skill.ClassSkill;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class ClassService {

	private static final Set<UUID> APPLYING = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Progression> ACCOUNT_PROGRESS = new ConcurrentHashMap<>();

	private ClassService() {}

	public static void trackFromPlayer(Player player) {
		if (player == null) {
			return;
		}
		PlayerData pd = PlayerData.get(player);
		ACCOUNT_PROGRESS.put(player.getUniqueId(), new Progression(pd.getLevel(), pd.getExperience()));
	}

	public static void migrateSkillPointsIfNeeded(Player player) {
		if (player == null) {
			return;
		}
		net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.needsSkillPointsMigration()) {
			return;
		}
		PlayerData mmoPd = PlayerData.get(player);
		int pool = mmoPd.getSkillPoints() + mmoPd.countSkillPointsSpent();
		pd.setAccountSkillPointsTotal(pool);
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void grantSkillPoints(Player player, int amount) {
		if (player == null || amount <= 0) {
			return;
		}
		net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		pd.addAccountSkillPoints(amount);
		applyFreeSkillPoints(player);
		RPCharacters.getPlayerManager().savePlayer(player);
	}

	public static void applyFreeSkillPoints(Player player) {
		if (player == null) {
			return;
		}
		net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		PlayerData mmoPd = PlayerData.get(player);
		int total = pd.getAccountSkillPointsTotal();
		int spent = mmoPd.countSkillPointsSpent();
		mmoPd.setSkillPoints(Math.max(0, total - spent));
	}

	public static void syncSkillPoints(Player player) {
		syncSkillPoints(player, null);
	}

	public static void syncSkillPoints(Player player, CommandSender sender) {
		if (player == null) {
			return;
		}
		net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		PlayerData mmoPd = PlayerData.get(player);
		List<String> stripped = sanitizeForeignSkillLevels(mmoPd);
		int spent = mmoPd.countSkillPointsSpent();
		int mmoPool = mmoPd.getSkillPoints() + spent;
		int beforeTotal = pd.getAccountSkillPointsTotal();
		if (mmoPool != beforeTotal) {
			pd.setAccountSkillPointsTotal(mmoPool);
			RPCharacters.getPlayerManager().savePlayer(player);
		}
		applyFreeSkillPoints(player);
		if (sender != null && Cache.skillPointsAdminDebugMessages) {
			int afterTotal = pd.getAccountSkillPointsTotal();
			int unspent = mmoPd.getSkillPoints();
			spent = mmoPd.countSkillPointsSpent();
			RPTexts.sendPrefixed(sender, RPTexts.WARN + player.getName()
					+ " skill points: account total " + RPTexts.SUCCESS + beforeTotal + " → " + afterTotal
					+ RPTexts.WARN + ", unspent now " + RPTexts.SUCCESS + unspent
					+ RPTexts.WARN + " (spent on class: " + RPTexts.SUCCESS + spent
					+ RPTexts.WARN + ", mmo pool: " + RPTexts.SUCCESS + mmoPool + RPTexts.WARN + ")");
			RPTexts.sendPrefixed(sender, RPTexts.WARN + "Class " + mmoPd.getProfess().getId()
					+ RPTexts.WARN + " skill spend: " + RPTexts.MUTED + formatSkillPointsSpentBreakdown(mmoPd));
			if (!stripped.isEmpty()) {
				RPTexts.sendPrefixed(sender, RPTexts.WARN + "Stripped foreign skills: " + RPTexts.MUTED
						+ String.join(", ", stripped));
			}
		}
	}

	public static void scheduleSyncSkillPoints(Player player) {
		scheduleSyncSkillPoints(player, null);
	}

	public static void scheduleSyncSkillPoints(Player player, CommandSender sender) {
		if (player == null) {
			return;
		}
		Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
			if (player.isOnline()) {
				syncSkillPoints(player, sender);
			}
		}, 1L);
	}

	public static void applyClass(Player player, String classId) {
		if (player == null || classId == null || classId.isBlank()) {
			return;
		}
		PlayerData mmoPd = PlayerData.get(player);
		PlayerClass target = MMOCore.plugin.classManager.get(classId);
		if (target == null) {
			return;
		}
		if (isOnClass(player, classId)) {
			if (mmoPd.hasSavedClass(target)) {
				sanitizeForeignSkillLevels(target, mmoPd.getClassInfo(target));
			}
			sanitizeForeignSkillLevels(mmoPd);
			clampExcessSkillPool(player);
			applyFreeSkillPoints(player);
			applyFreeAttributePointsIfActive(player);
			return;
		}

		UUID uuid = player.getUniqueId();
		Progression saved = ACCOUNT_PROGRESS.getOrDefault(uuid, new Progression(mmoPd.getLevel(), mmoPd.getExperience()));
		int level = saved.level;
		double exp = saved.exp;

		APPLYING.add(uuid);
		try {
			SavedClassInformation info = mmoPd.hasSavedClass(target)
					? mmoPd.getClassInfo(target)
					: new SavedClassInformation(MMOCore.plugin.playerDataManager.getDefaultData());
			sanitizeForeignSkillLevels(target, info);
			info.load(target, mmoPd);
			mmoPd.setLevel(level);
			mmoPd.setExperience(exp);
			sanitizeForeignSkillLevels(mmoPd);
			clampExcessSkillPool(player);
			applyFreeSkillPoints(player);
			applyFreeAttributePointsIfActive(player);
			ACCOUNT_PROGRESS.put(uuid, new Progression(level, exp));
		} finally {
			APPLYING.remove(uuid);
		}
	}

	public static void restoreAccountProgression(Player player) {
		if (player == null || APPLYING.contains(player.getUniqueId())) {
			return;
		}
		Progression saved = ACCOUNT_PROGRESS.get(player.getUniqueId());
		if (saved == null) {
			return;
		}
		PlayerData pd = PlayerData.get(player);
		pd.setLevel(saved.level);
		pd.setExperience(saved.exp);
		applyFreeSkillPoints(player);
		applyFreeAttributePointsIfActive(player);
	}

	public static boolean isApplying(UUID uuid) {
		return APPLYING.contains(uuid);
	}

	public static boolean isOnClass(Player player, String classId) {
		if (player == null || classId == null || classId.isBlank()) {
			return false;
		}
		PlayerClass target = MMOCore.plugin.classManager.get(classId);
		if (target == null) {
			return false;
		}
		return target.getId().equalsIgnoreCase(PlayerData.get(player).getProfess().getId());
	}

	public static void sanitizeForeignSkillLevels(Player player) {
		if (player == null) {
			return;
		}
		sanitizeForeignSkillLevels(PlayerData.get(player));
	}

	private static List<String> sanitizeForeignSkillLevels(PlayerClass profess, SavedClassInformation info) {
		List<String> stripped = new ArrayList<>();
		for (String skillId : new ArrayList<>(info.mapSkillLevels().keySet())) {
			Integer level = info.mapSkillLevels().get(skillId);
			if (!profess.hasSkill(skillId) && level != null && level > 1) {
				info.registerSkillLevel(skillId, 1);
				stripped.add(skillId);
			}
		}
		return stripped;
	}

	private static List<String> sanitizeForeignSkillLevels(PlayerData mmoPd) {
		List<String> stripped = new ArrayList<>();
		PlayerClass profess = mmoPd.getProfess();
		for (String skillId : new ArrayList<>(mmoPd.mapSkillLevels().keySet())) {
			Integer level = mmoPd.mapSkillLevels().get(skillId);
			if (!profess.hasSkill(skillId) && level != null && level > 1) {
				mmoPd.setSkillLevel(skillId, 1);
				stripped.add(skillId);
			}
		}
		return stripped;
	}

	private static String formatSkillPointsSpentBreakdown(PlayerData mmoPd) {
		List<String> parts = new ArrayList<>();
		int totalContributed = 0;
		for (ClassSkill classSkill : mmoPd.getProfess().getSkills()) {
			int level = mmoPd.getSkillLevel(classSkill.getSkill());
			int contributed = Math.max(0, level - 1);
			totalContributed += contributed;
			if (contributed > 0) {
				parts.add(classSkill.getSkill().getName() + " lvl" + level + " (+" + contributed + ")");
			}
		}
		if (parts.isEmpty()) {
			return "none §8[mmo spent=0]";
		}
		return String.join(", ", parts) + " §8[mmo spent=" + totalContributed + "]";
	}

	private static void applyFreeAttributePointsIfActive(Player player) {
		net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
		if (pd != null && pd.hasActiveCharacter()) {
			AttributePointService.applyFreeAttributePoints(player, pd.getActiveCharacter());
		}
	}

	private static void clampExcessSkillPool(Player player) {
		net.tfminecraft.rpcharacters.objects.PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return;
		}
		PlayerData mmoPd = PlayerData.get(player);
		int lifetime = pd.getAccountSkillPointsTotal();
		while (mmoPd.getSkillPoints() + mmoPd.countSkillPointsSpent() > lifetime) {
			int unspent = mmoPd.getSkillPoints();
			if (unspent > 0) {
				mmoPd.setSkillPoints(unspent - 1);
				continue;
			}
			List<String> downgradeable = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : mmoPd.mapSkillLevels().entrySet()) {
				if (entry.getValue() != null && entry.getValue() >= 2) {
					downgradeable.add(entry.getKey());
				}
			}
			if (downgradeable.isEmpty()) {
				break;
			}
			Collections.shuffle(downgradeable);
			String skillId = downgradeable.get(0);
			mmoPd.setSkillLevel(skillId, mmoPd.mapSkillLevels().get(skillId) - 1);
		}
	}

	private static final class Progression {
		private final int level;
		private final double exp;

		private Progression(int level, double exp) {
			this.level = level;
			this.exp = exp;
		}
	}
}
