package net.tfminecraft.rpcharacters.catalog;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.Dependency;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.ClueStage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.QuestionStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.creation.stages.SetterStage;
import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.creation.stages.WardrobeStage;
import net.tfminecraft.rpcharacters.loaders.KitLoader;
import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.kit.KitDefinition;
import net.tfminecraft.rpcharacters.kit.KitItemDefinition;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.ProstheticReplacement;
import net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition;
import net.tfminecraft.rpcharacters.objects.WebCreatorRealmAccess;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.kit.EditableKitPreviewBuilder;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;

/**
 * Build RPCharacters creation catalog JSON and push to ProvinceSystem (fail-soft).
 */
public final class CreationCatalogSyncService {

	private CreationCatalogSyncService() {}

	public static String buildPayloadJson() {
		StringBuilder sb = new StringBuilder(4096);
		sb.append('{');

		appendStages(sb);
		sb.append(',');
		appendAttributePointBuy(sb);
		sb.append(',');
		appendRaces(sb);
		sb.append(',');
		appendTraits(sb);
		sb.append(',');
		appendClasses(sb);
		sb.append(',');
		appendValidation(sb);
		sb.append(',');
		appendSlotLimits(sb);
		sb.append(',');
		appendWebCreatorAccess(sb);
		sb.append(',');
		appendKits(sb);
		sb.append(',');
		appendEditableKit(sb);

		sb.append('}');
		return sb.toString();
	}

	private static void appendStages(StringBuilder sb) {
		sb.append("\"stages\":[");
		boolean first = true;
		int order = 0;
		for (Stage stage : StageLoader.oList) {
			if (stage == null || stage.getId() == null) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "id", stage.getId(), true);
			appendField(sb, "type", stageTypeName(stage), false);
			sb.append(",\"order\":").append(order++);
			sb.append(",\"repeat\":").append(stage.shouldRepeat());
			sb.append(",\"auto_next\":").append(stage.autoNext());
			appendField(sb, "platform", stage.getPlatform(), false);
			if (stage.getLockTimeMs() > 0) {
				sb.append(",\"lock_time_ms\":").append(stage.getLockTimeMs());
			}
			if (stage.getRequireAccountAgeHoursMin() != null) {
				sb.append(",\"require_account_age_hours_min\":")
					.append(stage.getRequireAccountAgeHoursMin());
			}
			if (stage.getRequireAccountAgeHoursMax() != null) {
				sb.append(",\"require_account_age_hours_max\":")
					.append(stage.getRequireAccountAgeHoursMax());
			}
			if (stage.hasDependency()) {
				sb.append(',');
				appendDependency(sb, stage.getDependency());
			}
			if (stage instanceof InfoStage info) {
				sb.append(",\"interval\":").append(info.getInterval());
				sb.append(',');
				appendStringList(sb, "messages", substituteHoursList(info.getMessages()));
				if (info.hasWebMessages()) {
					sb.append(',');
					appendStringList(sb, "web_messages",
						substituteHoursList(info.getWebMessages()));
				}
			} else if (stage instanceof WardrobeStage wardrobe) {
				if (wardrobe.hasWebMessages()) {
					sb.append(',');
					appendStringList(sb, "web_messages",
						substituteHoursList(wardrobe.getWebMessages()));
				}
			} else if (stage instanceof SetterStage setter) {
				appendField(sb, "target", setter.getTarget(), false);
			} else if (stage instanceof SelectionStage selection) {
				appendField(sb, "target", selection.getTarget(), false);
				if (selection.getKey() != null) {
					appendField(sb, "key", selection.getKey(), false);
				}
				sb.append(",\"min_select\":").append(selection.getMinSelections());
				sb.append(",\"max_select\":").append(selection.getMaxSelections());
				if (selection.hasPoints()) {
					sb.append(",\"points\":").append(selection.getInitialPoints());
				}
				if (selection.getFilter() != null && !selection.getFilter().isBlank()) {
					appendField(sb, "filter", selection.getFilter(), false);
				}
			} else if (stage instanceof AttributesStage attributes) {
				appendField(sb, "key", attributes.getKey(), false);
				sb.append(",\"points\":").append(attributes.getPool());
				sb.append(",\"max_rank\":").append(attributes.getMaxRank());
			} else if (stage instanceof SummaryStage summary) {
				sb.append(",\"entries\":{");
				boolean firstEntry = true;
				for (Map.Entry<String, String> e : summary.getEntries().entrySet()) {
					if (!firstEntry) {
						sb.append(',');
					}
					firstEntry = false;
					sb.append('"').append(escape(e.getKey())).append("\":\"")
						.append(escape(e.getValue())).append('"');
				}
				sb.append('}');
			} else if (stage instanceof ClueStage || stage instanceof QuestionStage) {
				// type already set
			}
			sb.append('}');
		}
		sb.append(']');
	}

	private static String stageTypeName(Stage stage) {
		if (stage instanceof InfoStage) {
			return "info";
		}
		if (stage instanceof SetterStage) {
			return "setter";
		}
		if (stage instanceof SelectionStage) {
			return "selection";
		}
		if (stage instanceof AttributesStage) {
			return "attributes";
		}
		if (stage instanceof ClueStage) {
			return "clue";
		}
		if (stage instanceof QuestionStage) {
			return "questions";
		}
		if (stage instanceof SummaryStage) {
			return "summary";
		}
		if (stage instanceof WardrobeStage) {
			return "wardrobe";
		}
		return "unknown";
	}

	private static void appendDependency(StringBuilder sb, Dependency dep) {
		sb.append("\"dependency\":{");
		appendField(sb, "type", dep.getType(), true);
		appendField(sb, "mode", dep.getMode(), false);
		sb.append(',');
		appendStringList(sb, "depends_on", dep.getDependencies());
		sb.append('}');
	}

	private static void appendAttributePointBuy(StringBuilder sb) {
		int pool = 12;
		int maxRank = 4;
		List<String> attrs = new ArrayList<>(Cache.attributes);
		for (Stage stage : StageLoader.oList) {
			if (stage instanceof AttributesStage attributes) {
				pool = attributes.getPool();
				maxRank = attributes.getMaxRank();
				if (!attributes.getAttributes().isEmpty()) {
					attrs = new ArrayList<>(attributes.getAttributes());
				}
				break;
			}
		}
		sb.append("\"attribute_point_buy\":{");
		sb.append("\"pool\":").append(pool);
		sb.append(",\"max_rank\":").append(maxRank);
		sb.append(",\"cost_for_rank\":[");
		for (int n = 1; n <= maxRank; n++) {
			if (n > 1) {
				sb.append(',');
			}
			sb.append(AttributesStage.costForRank(n));
		}
		sb.append(']');
		sb.append(',');
		appendStringList(sb, "attributes", attrs);
		sb.append(",\"abbreviations\":{");
		boolean first = true;
		for (String attr : attrs) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(escape(attr.toLowerCase(Locale.ROOT))).append("\":\"")
				.append(escape(AttributesStage.abbrevFor(attr))).append('"');
		}
		sb.append('}');
		sb.append(",\"trait_id_pattern\":\"{abbr}{rank}\"");
		sb.append('}');
	}

	private static void appendRaces(StringBuilder sb) {
		sb.append("\"races\":[");
		boolean first = true;
		for (Race race : RaceLoader.get()) {
			if (race == null || race.getId() == null) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "id", race.getId(), true);
			appendField(sb, "name", strip(race.getName()), false);
			String key = race.getRaceData() != null ? race.getRaceData().getKey() : "race";
			appendField(sb, "key", key, false);
			sb.append(",\"shown\":").append(race.isShown());
			sb.append(",\"age_max\":").append(race.getAgeMax());
			sb.append(',');
			appendStringList(sb, "description", stripList(race.getDesc()));
			if (race.getRaceData() != null) {
				appendAttributeData(sb, race.getRaceData().getAttributeData());
			}
			sb.append('}');
		}
		sb.append(']');
	}

	private static void appendTraits(StringBuilder sb) {
		sb.append("\"traits\":[");
		boolean first = true;
		for (Trait trait : TraitLoader.get()) {
			if (trait == null || trait.getId() == null || trait.getTraitData() == null) {
				continue;
			}
			String key = trait.getTraitData().getKey();
			if (!shouldIncludeTraitInCatalog(trait, key)) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "id", trait.getId(), true);
			appendField(sb, "name", strip(trait.getName()), false);
			if (key != null) {
				appendField(sb, "key", key, false);
			}
			sb.append(",\"cost\":").append(trait.getTraitData().getCost());
			sb.append(',');
			appendStringList(sb, "mutually_exclusive", trait.getTraitData().getExclusive());
			sb.append(',');
			List<String> description = new ArrayList<>(trait.getDesc());
			TraitEffectResolver.appendBlockOffhandLore(trait, description);
			appendStringList(sb, "description", stripList(description));
			if (trait.getTraitData().hasDependency()) {
				sb.append(',');
				appendDependency(sb, trait.getTraitData().getDependency());
			}
			appendAttributeData(sb, trait.getTraitData().getAttributeData());
			sb.append(",\"has_duration\":").append(trait.hasDuration());
			sb.append(",\"fuel_disclaimer\":").append(trait.hasFuelTemplate());
			if (trait.hasIcon()) {
				appendField(sb, "icon", trait.getIcon().name(), false);
			}
			if (key != null && key.equalsIgnoreCase("prosthetic")) {
				ProstheticReplacement replacement = ProstheticLoader.getReplacementForProsthetic(trait.getId());
				if (replacement != null) {
					appendField(sb, "replaces_injury", replacement.getPermanentInjuryId(), false);
				}
			}
			int playtimeSeconds = trait.getTraitData().getRequiredAccountPlaytimeSeconds();
			if (playtimeSeconds > 0) {
				double hours = playtimeSeconds / 3600.0;
				sb.append(",\"required_account_playtime_hours\":");
				if (Math.abs(hours - Math.rint(hours)) < 1e-9) {
					sb.append((long) Math.rint(hours));
				} else {
					sb.append(hours);
				}
			}
			sb.append('}');
		}
		sb.append(']');
	}

	private static boolean shouldIncludeTraitInCatalog(Trait trait, String key) {
		if (key == null) {
			return true;
		}
		if (key.equalsIgnoreCase("injury")) {
			return !trait.hasDuration();
		}
		return true;
	}

	private static void appendClasses(StringBuilder sb) {
		sb.append("\"classes\":[");
		boolean first = true;
		try {
			List<PlayerClass> classes = new ArrayList<>();
			for (PlayerClass playerClass : MMOCore.plugin.classManager.getAll()) {
				if (MmoCoreClassGuiHelper.isClassDisplayed(playerClass)) {
					classes.add(playerClass);
				}
			}
			classes.sort((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()));
			for (PlayerClass playerClass : classes) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append('{');
				appendField(sb, "id", playerClass.getId(), true);
				appendField(sb, "name", strip(playerClass.getName()), false);
				sb.append(",\"display_order\":").append(playerClass.getDisplayOrder());
				sb.append(',');
				appendStringList(sb, "description", stripList(playerClass.getDescription()));
				sb.append(',');
				appendStringList(
					sb,
					"attribute_description",
					stripList(playerClass.getAttributeDescription())
				);
				sb.append('}');
			}
		} catch (Throwable t) {
			RPCharacters.plugin.getLogger().warning(
				"[creation-catalog] could not read MMOCore classes: " + t.getMessage()
			);
		}
		sb.append(']');
	}

	/** Serialize race/trait AttributeData (omit zero amounts). */
	private static void appendAttributeData(StringBuilder sb, AttributeData data) {
		if (data == null) {
			return;
		}
		sb.append(",\"attribute_modifiers\":[");
		boolean first = true;
		for (AttributeModifier mod : data.getModifiers()) {
			if (mod == null || mod.getAmount() == 0) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "type", mod.getType(), true);
			sb.append(",\"amount\":").append(mod.getAmount());
			sb.append('}');
		}
		sb.append(']');
		sb.append(",\"experience_modifiers\":[");
		first = true;
		for (ExperienceModifier mod : data.getExperienceModifiers()) {
			if (mod == null || mod.getModifier() == 0) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "profession", mod.getProfession(), true);
			appendField(sb, "alias", strip(mod.getAlias()), false);
			sb.append(",\"amount\":").append(mod.getModifier());
			sb.append('}');
		}
		sb.append(']');
	}

	private static void appendValidation(StringBuilder sb) {
		sb.append("\"validation\":{");
		sb.append("\"name\":{");
		sb.append("\"min_length\":").append(Cache.personaDisplayNameMinLength);
		sb.append(",\"max_length\":").append(Cache.personaDisplayNameMaxLength);
		sb.append('}');
		sb.append(",\"age\":{");
		sb.append("\"minimum\":").append(Cache.calendarAgeMinimum);
		sb.append('}');
		sb.append(",\"calendar\":{");
		sb.append("\"year_offset\":").append(Cache.calendarYearOffset);
		sb.append(',');
		appendField(sb, "era_suffix", Cache.calendarEraSuffix, true);
		sb.append('}');
		sb.append(",\"description\":{");
		sb.append("\"min_length\":").append(Cache.characterDescriptionMinLength);
		sb.append(",\"max_length\":").append(Cache.characterDescriptionMaxLength);
		sb.append('}');
		sb.append(",\"clues\":{");
		sb.append("\"default_required\":").append(Cache.defaultCluesRequired);
		sb.append(",\"evil_required\":").append(Cache.evilCluesRequired);
		sb.append(",\"evil_min_account_age_hours\":").append(Cache.evilMinAccountAgeHours);
		sb.append(",\"min_length\":").append(Cache.clueMinLength);
		sb.append(",\"max_length\":").append(Cache.clueMaxLength);
		sb.append(",\"max_clues\":").append(Cache.maxClues);
		sb.append('}');
		sb.append('}');
	}

	private static void appendWebCreatorAccess(StringBuilder sb) {
		sb.append("\"web_creator_access\":{\"by_realm\":{");
		boolean firstRealm = true;
		for (Map.Entry<String, WebCreatorRealmAccess> entry : Cache.webCreatorAccessByRealm.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			if (!firstRealm) {
				sb.append(',');
			}
			firstRealm = false;
			WebCreatorRealmAccess access = entry.getValue();
			sb.append('"').append(escape(entry.getKey())).append("\":{");
			sb.append("\"min_tier\":").append(access.getMinTier());
			if (!access.getMinGroupId().isEmpty()) {
				appendField(sb, "min_group_id", access.getMinGroupId(), false);
			}
			sb.append('}');
		}
		sb.append("}}");
	}

	private static void appendSlotLimits(StringBuilder sb) {
		sb.append("\"slot_limits\":{");
		sb.append("\"hard_cap\":").append(CharacterSlotService.getHardSlotCap());
		sb.append(",\"defaults\":{");
		int defaultMax = Cache.permissionGroupDefaults.getOrDefault(
			PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, 3
		);
		int defaultColourStops = Cache.permissionGroupDefaults.getOrDefault(
			PermissionGroupDefinition.KEY_NAME_COLOUR_STOPS, 0
		);
		int defaultWardrobeSlots = Cache.permissionGroupDefaults.getOrDefault(
			PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS, 1
		);
		sb.append("\"max_alive_characters\":").append(defaultMax);
		sb.append(",\"name_colour_stops\":").append(defaultColourStops);
		sb.append(",\"wardrobe_skin_slots\":").append(defaultWardrobeSlots);
		sb.append('}');
		sb.append(",\"groups\":[");
		boolean first = true;
		for (PermissionGroupDefinition group : Cache.permissionGroups) {
			if (group == null) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "id", group.getId(), true);
			appendField(sb, "permission", group.getPermission(), false);
			appendField(sb, "display_name", group.getDisplayName(), false);
			sb.append(",\"tier\":").append(group.getTier());
			sb.append(",\"visible\":").append(group.isVisible());
			sb.append(",\"max_alive_characters\":").append(
				group.getPerk(PermissionGroupDefinition.KEY_MAX_ALIVE_CHARACTERS, defaultMax)
			);
			sb.append(",\"name_colour_stops\":").append(
				group.getPerk(PermissionGroupDefinition.KEY_NAME_COLOUR_STOPS, defaultColourStops)
			);
			sb.append(",\"wardrobe_skin_slots\":").append(
				group.getPerk(PermissionGroupDefinition.KEY_WARDROBE_SKIN_SLOTS, defaultWardrobeSlots)
			);
			sb.append('}');
		}
		sb.append("]}");
	}

	/** Nested kits from kits.yml (all items; editable flagged). */
	private static void appendKits(StringBuilder sb) {
		sb.append("\"kits\":[");
		boolean first = true;
		for (KitDefinition kit : KitLoader.getKits().values()) {
			if (kit == null || kit.getId().isEmpty()) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "id", kit.getId(), true);
			appendField(sb, "display_name", kit.getDisplayName(), false);
			sb.append(",\"cooldown_hours\":").append(kit.getCooldownHours());
			sb.append(",\"once_per_character\":").append(kit.isOncePerCharacter());
			sb.append(",\"items\":[");
			boolean firstItem = true;
			for (KitItemDefinition item : kit.getItems()) {
				if (item == null || item.getPath() == null || item.getPath().isBlank()) {
					continue;
				}
				if (!firstItem) {
					sb.append(',');
				}
				firstItem = false;
				sb.append('{');
				appendField(sb, "path", item.getPath(), true);
				sb.append(",\"amount\":").append(item.getAmount());
				if (item.isEditable()) {
					sb.append(",\"editable\":true");
				}
				if (item.getDisplayName() != null && !item.getDisplayName().isBlank()) {
					appendField(sb, "display_name", item.getDisplayName(), false);
				}
				sb.append('}');
			}
			sb.append("]}");
		}
		sb.append(']');
	}

	/**
	 * Flat editable kit rows (compat). Preview resolution uses Bukkit ItemMeta — call
	 * {@link #buildPayloadJson()} on the main thread.
	 */
	private static void appendEditableKit(StringBuilder sb) {
		sb.append("\"editable_kit\":[");
		boolean first = true;
		for (EditableKitPreviewBuilder.Row row : EditableKitPreviewBuilder.build()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('{');
			appendField(sb, "kit_key", row.getKitKey(), true);
			appendField(sb, "path", row.getPath(), false);
			sb.append(",\"amount\":").append(row.getAmount());
			if (row.getGrantKitId() != null && !row.getGrantKitId().isBlank()) {
				appendField(sb, "kit_id", row.getGrantKitId(), false);
			}
			appendField(sb, "skin_png", row.getSkinPng(), false);
			if (row.getSkinPngSigned() != null && !row.getSkinPngSigned().isBlank()) {
				appendField(sb, "skin_png_signed", row.getSkinPngSigned(), false);
			}
			appendField(sb, "base_set", row.getBaseSet(), false);
			appendField(sb, "2d_template", row.get2dTemplate(), false);
			if (row.get3dTemplate() != null && !row.get3dTemplate().isBlank()) {
				appendField(sb, "3d_template", row.get3dTemplate(), false);
			}
			EditableKitPreviewBuilder.Preview preview = row.getPreview();
			if (preview != null) {
				sb.append(",\"preview\":{");
				appendField(sb, "display_name", preview.getDisplayName(), true);
				sb.append(',');
				appendStringList(sb, "lore", preview.getLore());
				appendField(sb, "material", preview.getMaterial(), false);
				if (preview.getCustomModelData() != null) {
					sb.append(",\"custom_model_data\":").append(preview.getCustomModelData());
				}
				sb.append('}');
			}
			sb.append('}');
		}
		sb.append(']');
	}

	public static void pushAsync(JavaPlugin plugin) {
		if (plugin == null) {
			return;
		}
		Runnable buildThenPush = () -> {
			String json = buildPayloadJson();
			Set<String> skinStems = collectEditableSkinPngStems();
			Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
				ProvinceSystemClient.CatalogPushResult result =
					ProvinceSystemClient.pushCreationCatalog(json);
				logPushResult(plugin.getLogger(), result);
				if (result.ok) {
					syncKitSkins(plugin, plugin.getLogger(), skinStems);
					syncMaskedTemplate(plugin, plugin.getLogger());
				}
			});
		};
		if (Bukkit.isPrimaryThread()) {
			buildThenPush.run();
		} else {
			Bukkit.getScheduler().runTask(plugin, buildThenPush);
		}
	}

	/** Build payload then PUT. Must run on the main thread (ItemStack preview). */
	public static ProvinceSystemClient.CatalogPushResult pushNow() {
		ProvinceSystemClient.CatalogPushResult result =
			ProvinceSystemClient.pushCreationCatalog(buildPayloadJson());
		if (result.ok && RPCharacters.plugin != null) {
			syncKitSkins(
				RPCharacters.plugin,
				RPCharacters.plugin.getLogger(),
				collectEditableSkinPngStems()
			);
			syncMaskedTemplate(RPCharacters.plugin, RPCharacters.plugin.getLogger());
		}
		return result;
	}

	public static ProvinceSystemClient.CatalogPushResult pushJson(String jsonBody) {
		ProvinceSystemClient.CatalogPushResult result =
			ProvinceSystemClient.pushCreationCatalog(jsonBody);
		if (result.ok && RPCharacters.plugin != null) {
			syncKitSkins(
				RPCharacters.plugin,
				RPCharacters.plugin.getLogger(),
				collectEditableSkinPngStems()
			);
			syncMaskedTemplate(RPCharacters.plugin, RPCharacters.plugin.getLogger());
		}
		return result;
	}

	private static void logPushResult(Logger log, ProvinceSystemClient.CatalogPushResult result) {
		if (result.ok) {
			log.info("[creation-catalog] synced to ProvinceSystem: stages="
				+ result.stages
				+ " races=" + result.races
				+ " traits=" + result.traits
				+ " classes=" + result.classes);
		} else {
			log.warning("[creation-catalog] sync failed: " + result.error);
		}
	}

	private static void addSkinStem(Set<String> stems, String raw) {
		if (raw == null) {
			return;
		}
		String stem = raw.trim();
		if (stem.isEmpty()) {
			return;
		}
		if (stem.toLowerCase(Locale.ROOT).endsWith(".png")) {
			stem = stem.substring(0, stem.length() - 4).trim();
		}
		if (stem.isEmpty()
			|| stem.contains("/")
			|| stem.contains("\\")
			|| stem.contains("..")) {
			return;
		}
		stems.add(stem);
	}

	/** Distinct skin_png stems from editable kit rows (no ItemStack work). */
	static Set<String> collectEditableSkinPngStems() {
		Set<String> stems = new LinkedHashSet<>();
		for (KitDefinition kit : KitLoader.getKits().values()) {
			if (kit == null) {
				continue;
			}
			for (KitItemDefinition def : kit.getItems()) {
				if (def == null || !def.isEditable() || def.getEditable() == null) {
					continue;
				}
				addSkinStem(stems, def.getEditable().getSkinPng());
				addSkinStem(stems, def.getEditable().getSkinPngSigned());
			}
		}
		return stems;
	}

	/**
	 * Fail-soft: upload each assets/{stem}.png after a successful catalog JSON push.
	 * Missing files warn and skip; HTTP failures do not roll back the catalog.
	 */
	static void syncKitSkins(JavaPlugin plugin, Logger log, Set<String> stems) {
		if (plugin == null || log == null || stems == null || stems.isEmpty()) {
			return;
		}
		File assetsDir = new File(plugin.getDataFolder(), "assets");
		int ok = 0;
		int missing = 0;
		int failed = 0;
		for (String stem : stems) {
			File file = new File(assetsDir, stem + ".png");
			if (!file.isFile()) {
				log.warning("[creation-catalog] kit skin missing: assets/"
					+ stem + ".png");
				missing++;
				continue;
			}
			try {
				byte[] bytes = Files.readAllBytes(file.toPath());
				ProvinceSystemClient.SimpleResult put =
					ProvinceSystemClient.putKitSkin(stem, bytes);
				if (put.ok) {
					ok++;
				} else {
					failed++;
					log.warning("[creation-catalog] kit skin upload failed for "
						+ stem + ": " + put.error);
				}
			} catch (Exception e) {
				failed++;
				log.warning("[creation-catalog] kit skin read/upload failed for "
					+ stem + ": " + e.getMessage());
			}
		}
		log.info("[creation-catalog] kit skins synced: ok=" + ok
			+ " missing=" + missing
			+ " failed=" + failed);
	}

	/**
	 * Fail-soft: upload assets/masked.png as the auto-masked body template.
	 */
	static void syncMaskedTemplate(JavaPlugin plugin, Logger log) {
		if (plugin == null || log == null) {
			return;
		}
		File file = new File(plugin.getDataFolder(), "assets/masked.png");
		if (!file.isFile()) {
			log.warning("[creation-catalog] masked template missing: assets/masked.png");
			return;
		}
		try {
			byte[] bytes = Files.readAllBytes(file.toPath());
			ProvinceSystemClient.SimpleResult put =
				ProvinceSystemClient.putWardrobeMaskedTemplate(bytes);
			if (put.ok) {
				log.info("[creation-catalog] masked template synced");
			} else {
				log.warning("[creation-catalog] masked template upload failed: "
					+ put.error);
			}
		} catch (Exception e) {
			log.warning("[creation-catalog] masked template read/upload failed: "
				+ e.getMessage());
		}
	}

	public static void pushAsyncFromPlugin() {
		if (RPCharacters.plugin != null) {
			pushAsync(RPCharacters.plugin);
		}
	}

	private static void appendField(StringBuilder sb, String key, String value, boolean first) {
		if (!first) {
			sb.append(',');
		}
		sb.append('"').append(escape(key)).append("\":\"")
			.append(escape(value == null ? "" : value)).append('"');
	}

	private static List<String> substituteHoursList(List<String> values) {
		List<String> out = new ArrayList<>();
		if (values == null) {
			return out;
		}
		for (String v : values) {
			out.add(InfoStage.substitutePlaceholders(v));
		}
		return out;
	}

	private static void appendStringList(StringBuilder sb, String key, List<String> values) {
		sb.append('"').append(escape(key)).append("\":[");
		if (values != null) {
			boolean first = true;
			for (String v : values) {
				if (v == null) {
					continue;
				}
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append('"').append(escape(v)).append('"');
			}
		}
		sb.append(']');
	}

	private static String strip(String raw) {
		if (raw == null) {
			return "";
		}
		return ChatColor.stripColor(raw);
	}

	private static List<String> stripList(List<String> raw) {
		List<String> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (String s : raw) {
			out.add(strip(s));
		}
		return out;
	}

	private static String escape(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length() + 8);
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			switch (c) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}
}
