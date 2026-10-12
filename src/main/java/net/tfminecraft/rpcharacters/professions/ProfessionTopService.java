package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.experience.Profession;
import net.Indyuce.mmocore.experience.PlayerProfessions;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.progress.ProgressRouting;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionTopService {
	private ProfessionTopService() {}

	public static void showTop(CommandSender sender, String professionId) {
		ProfessionDefinition profession = ProfessionRegistry.getProfession(professionId);
		if (profession == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Not a valid profession");
			return;
		}
		// Per character, MMOCore only holds the live character's levels; a player between characters has none.
		boolean perCharacter = ProgressRouting.enabled();
		List<LevelEntry> entries = new ArrayList<>();
		for (Player player : Bukkit.getOnlinePlayers()) {
			String name = player.getName();
			if (perCharacter) {
				net.tfminecraft.rpcharacters.objects.PlayerData account = PlayerManager.get(player);
				if (ProgressRouting.activeProgress(player) == null) {
					continue;
				}
				name = account.getActiveCharacter().getName();
			}
			PlayerData pd = PlayerData.get(player);
			if (pd == null) {
				continue;
			}
			Profession mmoProfession = MMOCore.plugin.professionManager.get(profession.getId());
			if (mmoProfession == null) {
				continue;
			}
			PlayerProfessions skills = pd.getCollectionSkills();
			int level = skills.getLevel(mmoProfession);
			entries.add(new LevelEntry(name, level));
		}
		entries.sort(Comparator.comparingInt(LevelEntry::level).reversed());
		RPTexts.send(sender, RPTexts.ACCENT + "The top " + (perCharacter ? "characters" : "players")
				+ " currently online in the Profession "
				+ RPTexts.ERROR + profession.getId().toUpperCase(java.util.Locale.ROOT) + RPTexts.ACCENT + " are:");
		for (int i = 0; i < entries.size() && i < 11; i++) {
			LevelEntry entry = entries.get(i);
			RPTexts.send(sender, RPTexts.INFO + entry.name() + " " + RPTexts.MUTED + "(" + RPTexts.ERROR
					+ "Level " + RPTexts.ACCENT + entry.level() + RPTexts.MUTED + ")");
		}
	}

	private record LevelEntry(String name, int level) {}
}
