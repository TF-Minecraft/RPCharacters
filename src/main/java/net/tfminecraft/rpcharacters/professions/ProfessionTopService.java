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
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionTopService {
	private ProfessionTopService() {}

	public static void showTop(CommandSender sender, String professionId) {
		ProfessionDefinition profession = ProfessionRegistry.getProfession(professionId);
		if (profession == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Not a valid profession");
			return;
		}
		List<LevelEntry> entries = new ArrayList<>();
		for (Player player : Bukkit.getOnlinePlayers()) {
			PlayerData pd = PlayerData.get(player);
			Profession mmoProfession = MMOCore.plugin.professionManager.get(profession.getId());
			if (mmoProfession == null) {
				continue;
			}
			PlayerProfessions skills = pd.getCollectionSkills();
			int level = skills.getLevel(mmoProfession);
			entries.add(new LevelEntry(player.getName(), level));
		}
		entries.sort(Comparator.comparingInt(LevelEntry::level).reversed());
		RPTexts.send(sender, RPTexts.ACCENT + "The top players currently online in the Profession "
				+ RPTexts.ERROR + profession.getId().toUpperCase() + RPTexts.ACCENT + " are:");
		for (int i = 0; i < entries.size() && i < 11; i++) {
			LevelEntry entry = entries.get(i);
			RPTexts.send(sender, RPTexts.INFO + entry.name() + " " + RPTexts.MUTED + "(" + RPTexts.ERROR
					+ "Level " + RPTexts.ACCENT + entry.level() + RPTexts.MUTED + ")");
		}
	}

	private record LevelEntry(String name, int level) {}
}
