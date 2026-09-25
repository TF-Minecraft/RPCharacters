package net.tfminecraft.rpcharacters.professions;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.experience.EXPSource;
import net.Indyuce.mmocore.experience.Profession;
import net.Indyuce.mmocore.manager.profession.ProfessionManager;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;

/** Awards configured breeding XP through MMOCore's normal experience pipeline. */
public final class BreedingExperienceService {

	private static final String PROFESSION_ID = "forager";
	private final Map<String, Integer> amounts = new HashMap<>();
	private final ProfessionManager professions;
	private final Logger logger;
	private boolean missingProfessionReported;

	public BreedingExperienceService(List<String> specs, ProfessionManager professions, Logger logger) {
		this.professions = professions;
		this.logger = logger;
		for (String spec : specs) {
			try {
				String[] parts = spec.split("\\.", -1);
				if (parts.length != 2) {
					throw new IllegalArgumentException();
				}
				String type = parts[0].trim().toLowerCase(Locale.ROOT);
				if (!"generic".equals(type)) {
					EntityType.valueOf(type.toUpperCase(Locale.ROOT));
				}
				int amount = Integer.parseInt(parts[1].trim());
				if (amount < 0) {
					throw new IllegalArgumentException();
				}
				// The old list lookup used the first matching entry.
				if (amounts.putIfAbsent(type, amount) != null) {
					logger.warning("Duplicate breeding_exp entry '" + spec + "' in professions.yml; keeping the first amount.");
				}
			} catch (IllegalArgumentException e) {
				logger.warning("Invalid breeding_exp entry '" + spec
						+ "' in professions.yml; expected entity.INTEGER or generic.INTEGER with non-negative XP. Skipping entry.");
			}
		}
		if (!amounts.isEmpty()) {
			resolveProfession();
		}
	}

	public void award(Player player, String entityType) {
		int amount = amounts.getOrDefault(entityType.toLowerCase(Locale.ROOT), 0);
		if (amount == 0 || resolveProfession() == null) {
			return;
		}
		MmoCorePlayerReady.runWhenLoaded(player, () -> {
			// Resolve again after waiting: MMOCore can replace professions during reload.
			Profession profession = resolveProfession();
			if (profession != null) {
				// Match `mmocore admin exp give`: COMMAND, no hologram location, party splitting enabled.
				PlayerData.get(player).getCollectionSkills().giveExperience(
						profession, amount, EXPSource.COMMAND, null, true);
			}
		});
	}

	private Profession resolveProfession() {
		Profession profession = professions.get(PROFESSION_ID);
		if (profession == null && !missingProfessionReported) {
			logger.warning("Breeding XP cannot be awarded: MMOCore profession '" + PROFESSION_ID
					+ "' is missing. Restore its MMOCore profession configuration.");
		}
		missingProfessionReported = profession == null;
		return profession;
	}
}
