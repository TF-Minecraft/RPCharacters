package net.tfminecraft.rpcharacters.ingest;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.loaders.KitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.identity.PersonaService;
import net.tfminecraft.rpcharacters.kit.KitDefinition;
import net.tfminecraft.rpcharacters.kit.KitService;
import net.tfminecraft.rpcharacters.kit.KitStatus;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;

/**
 * Push a player's character roster mirror to ProvinceSystem.
 */
public final class RosterSyncService {

	private static final Database DB = new Database();

	private RosterSyncService() {}

	public static void pushRosterAsync(UUID playerUuid) {
		if (playerUuid == null || RPCharacters.plugin == null || Cache.devCharacters) {
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> pushRosterNow(playerUuid));
	}

	public static void pushRosterForPlayer(Player player) {
		if (player == null || Cache.devCharacters) {
			return;
		}
		pushRosterAsync(player.getUniqueId());
	}

	/** Push roster for every online player (e.g. after {@code /rpcharacter reload}). */
	public static void pushAllOnlineAsync() {
		if (RPCharacters.plugin == null || Cache.devCharacters) {
			return;
		}
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player != null) {
				pushRosterAsync(player.getUniqueId());
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void pushRosterNow(UUID playerUuid) {
		if (playerUuid == null || Cache.devCharacters) {
			return;
		}
		PlayerData pd = null;
		Player online = Bukkit.getPlayer(playerUuid);
		if (online != null && PlayerManager.exists(online)) {
			pd = PlayerManager.get(online);
		} else {
			pd = DB.loadPlayerData(playerUuid);
		}
		if (pd == null) {
			return;
		}

		JSONObject root = new JSONObject();
		root.put("player_uuid", playerUuid.toString());
		JSONArray characters = new JSONArray();
		for (RPCharacter c : pd.getCharacters()) {
			if (c == null || c.getId() == null || c.isDev()) {
				continue;
			}
			JSONObject row = new JSONObject();
			row.put("id", c.getId());
			row.put("name", c.getName() != null ? c.getName() : "");
			row.put("status", c.getStatus() != null ? c.getStatus().toString() : "ALIVE");
			row.put("race", c.getRace() != null ? c.getRace().getId() : null);
			row.put("class", c.hasMMOClass() ? c.getMMOClass() : null);
			if (c.getCreatedAtEpochSeconds() > 0) {
				row.put("created_at", String.valueOf(c.getCreatedAtEpochSeconds()));
			}
			JSONObject statuses = new JSONObject();
			for (var entry : c.getKitStatuses().entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					statuses.put(entry.getKey(), entry.getValue().toStorage());
				}
			}
			if (!statuses.isEmpty()) {
				row.put("kit_statuses", statuses);
			}
			KitStatus starter = c.getKitStatus(KitLoader.DEFAULT_KIT_ID);
			if (starter != null) {
				row.put("kit_status", starter.toStorage());
			}
			appendSheetFields(row, c);
			characters.add(row);
		}
		root.put("characters", characters);

		JSONObject cooldowns = new JSONObject();
		for (KitDefinition kit : KitLoader.getKits().values()) {
			if (kit == null) {
				continue;
			}
			JSONObject one = new JSONObject();
			one.put(
					"seconds_remaining",
					Integer.valueOf((int) (KitService.cooldownRemainingMs(pd, kit.getId()) / 1000L))
			);
			one.put("hours", Integer.valueOf(kit.getCooldownHours()));
			cooldowns.put(kit.getId(), one);
		}
		if (!cooldowns.isEmpty()) {
			root.put("kit_cooldowns", cooldowns);
		}
		root.put(
			"kit_cooldown_seconds_remaining",
			Integer.valueOf((int) (KitService.cooldownRemainingMs(pd, KitLoader.DEFAULT_KIT_ID) / 1000L))
		);
		root.put(
			"kit_cooldown_hours",
			Integer.valueOf(KitLoader.getCooldownHours())
		);

		if (online != null) {
			root.put("max_alive_characters", CharacterSlotService.getMaxAliveCharacters(online));
			root.put("name_colour_stops", Integer.valueOf(PermissionGroupService.getNameColourStops(online)));
			root.put(
				"wardrobe_skin_slots",
				Integer.valueOf(PermissionGroupService.getWardrobeSkinSlots(online))
			);
		}

		boolean realAgeSet = pd.getCompletedStages().contains("creation_age_set_stage")
			|| pd.getCompletedStages().contains("age_stage");
		if (realAgeSet) {
			root.put("real_age_set", Boolean.TRUE);
			root.put("eighteen", Boolean.valueOf(pd.isEighteen()));
		}

		if (pd.getCreatedAtEpochSeconds() > 0) {
			root.put("account_created_at_epoch", Long.valueOf(pd.getCreatedAtEpochSeconds()));
		}

		ProvinceSystemClient.SimpleResult result = ProvinceSystemClient.pushRoster(root.toJSONString());
		if (!result.ok) {
			RPCharacters.plugin.getLogger().warning(
					"[roster] push failed for " + playerUuid + ": " + result.error
			);
		}
	}

	@SuppressWarnings("unchecked")
	private static void appendSheetFields(JSONObject row, RPCharacter c) {
		try {
			c.update();
		} catch (Throwable ignored) {
			// fail-soft: use whatever AttributeData / desc already present
		}
		if (c.getRace() != null && c.getRace().getName() != null) {
			String raceName = strip(c.getRace().getName());
			if (!raceName.isBlank()) {
				row.put("race_name", raceName);
			}
		}
		if (c.hasMMOClass()) {
			String className = resolveClassName(c.getMMOClass());
			if (className != null && !className.isBlank()) {
				row.put("class_name", className);
			}
		}
		String age = PersonaService.resolveAge(c);
		if (age != null && !age.isBlank()) {
			row.put("age", age);
		}
		String birthday = c.getBirthday();
		if (birthday != null && !birthday.isBlank()) {
			row.put("birthday", birthday.trim());
		}
		String gender = c.getGender();
		if (gender != null && !gender.isBlank()) {
			row.put("gender", gender.trim());
		}
		String description = c.getPersonaDescription();
		if (description != null && !description.isBlank()) {
			row.put("description", description.trim());
		}
		String background = backgroundText(c);
		if (background != null && !background.isBlank()) {
			row.put("background", background);
		}
		JSONObject attributes = attributeTotals(c);
		if (!attributes.isEmpty()) {
			row.put("attributes", attributes);
		}
		JSONArray experience = experienceModifierRows(c);
		if (!experience.isEmpty()) {
			row.put("experience_modifiers", experience);
		}
		JSONArray traits = traitRows(c);
		if (!traits.isEmpty()) {
			row.put("traits", traits);
		}
		JSONArray clues = clueRows(c);
		if (!clues.isEmpty()) {
			row.put("clues", clues);
		}
	}

	@SuppressWarnings("unchecked")
	private static JSONObject attributeTotals(RPCharacter character) {
		JSONObject out = new JSONObject();
		if (character == null) {
			return out;
		}
		AttributeData data = character.getAttributeData();
		if (data == null || data.getModifiers() == null) {
			return out;
		}
		for (AttributeModifier mod : data.getModifiers()) {
			if (mod == null || mod.getType() == null || mod.getType().isBlank()) {
				continue;
			}
			String key = mod.getType().trim().toLowerCase(java.util.Locale.ROOT);
			out.put(key, Integer.valueOf(mod.getAmount()));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static JSONArray experienceModifierRows(RPCharacter character) {
		JSONArray out = new JSONArray();
		if (character == null) {
			return out;
		}
		AttributeData data = character.getAttributeData();
		if (data == null || data.getExperienceModifiers() == null) {
			return out;
		}
		for (ExperienceModifier mod : data.getExperienceModifiers()) {
			if (mod == null) {
				continue;
			}
			String profession = mod.getProfession() != null ? mod.getProfession().trim() : "";
			if (profession.isBlank()) {
				continue;
			}
			JSONObject row = new JSONObject();
			row.put("profession", profession.toLowerCase(java.util.Locale.ROOT));
			String alias = mod.getAlias() != null ? strip(mod.getAlias()).trim() : "";
			row.put("alias", alias.isBlank() ? profession : alias);
			row.put("amount", Integer.valueOf(mod.getModifier()));
			out.add(row);
		}
		return out;
	}

	private static String backgroundText(RPCharacter character) {
		if (character == null || character.getDescription() == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (String line : character.getDescription()) {
			if (line == null) {
				continue;
			}
			String plain = strip(line).trim();
			if (plain.isBlank()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(plain);
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	@SuppressWarnings("unchecked")
	private static JSONArray traitRows(RPCharacter character) {
		JSONArray out = new JSONArray();
		if (character == null || character.getTraits() == null) {
			return out;
		}
		java.util.List<String> editable = Cache.editableTraits;
		for (Trait trait : character.getTraits()) {
			if (trait == null || trait.getId() == null || trait.getTraitData() == null) {
				continue;
			}
			String key = trait.getTraitData().getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			String keyNorm = key.trim().toLowerCase(java.util.Locale.ROOT);
			if (isAttributeRankTrait(trait.getId())) {
				continue;
			}
			boolean injuryOrProsthetic = keyNorm.equals("injury") || keyNorm.equals("prosthetic");
			if (!injuryOrProsthetic) {
				if (editable == null || editable.isEmpty()) {
					continue;
				}
				boolean allowed = false;
				for (String editableKey : editable) {
					if (editableKey != null && editableKey.trim().equalsIgnoreCase(keyNorm)) {
						allowed = true;
						break;
					}
				}
				if (!allowed) {
					continue;
				}
			}
			JSONObject row = new JSONObject();
			row.put("id", trait.getId());
			row.put("name", strip(trait.getName()));
			row.put("key", keyNorm);
			if (trait.hasDuration()) {
				long remaining = character.getDurationRemainingMs(trait.getId());
				if (remaining >= 0L) {
					row.put("duration_remaining_ms", Long.valueOf(remaining));
				}
			}
			if (trait.hasFuelTemplate() && trait.getFuelCapacity() > 0D) {
				double fuel = character.getFuel(trait.getId());
				if (fuel >= 0D) {
					int percent = (int) Math.round((fuel / trait.getFuelCapacity()) * 100D);
					percent = Math.max(0, Math.min(100, percent));
					row.put("fuel_percent", Integer.valueOf(percent));
				}
			}
			out.add(row);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static JSONArray clueRows(RPCharacter character) {
		JSONArray out = new JSONArray();
		if (character == null) {
			return out;
		}
		for (String clue : character.getPlayerClues()) {
			if (clue == null || clue.isBlank()) {
				continue;
			}
			String plain = ClueFormatter.stripColor(clue);
			if (plain != null && !plain.isBlank()) {
				out.add(plain.trim());
			}
		}
		return out;
	}

	private static boolean isAttributeRankTrait(String traitId) {
		if (traitId == null || traitId.isBlank()) {
			return false;
		}
		String id = traitId.trim().toLowerCase(java.util.Locale.ROOT);
		java.util.List<String> attrs = Cache.attributes;
		if (attrs == null) {
			return false;
		}
		for (String attr : attrs) {
			if (attr == null || attr.isBlank()) {
				continue;
			}
			String abbrev = AttributesStage.abbrevFor(attr).toLowerCase(java.util.Locale.ROOT);
			if (id.matches(java.util.regex.Pattern.quote(abbrev) + "[0-9]+")) {
				return true;
			}
		}
		return false;
	}

	private static String resolveClassName(String classId) {
		if (classId == null || classId.isBlank()) {
			return null;
		}
		try {
			PlayerClass playerClass = MMOCore.plugin.classManager.get(classId);
			if (playerClass != null && playerClass.getName() != null) {
				return strip(playerClass.getName());
			}
		} catch (Throwable ignored) {
			// fail-soft
		}
		return classId;
	}

	private static String strip(String raw) {
		if (raw == null) {
			return "";
		}
		return ChatColor.stripColor(raw);
	}
}
