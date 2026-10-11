package net.tfminecraft.rpcharacters.creation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.calendar.BirthdayValidator;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitData;
import net.tfminecraft.rpcharacters.persona.AliasValidator;
import net.tfminecraft.rpcharacters.persona.DescriptionValidator;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;

/**
 * Rules a finished character sheet must pass. In-game stages check them one pick at a
 * time; web creates are checked here before they are applied. Each check returns a
 * plain-text error, or null when the sheet passes.
 */
public final class CreationRules {

	/** Letters in any alphabet, then letters, spaces, apostrophes, hyphens and full stops. */
	private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L}\\p{M} .'-]*");

	private CreationRules() {}

	public static boolean isNameText(String name) {
		return name != null && NAME.matcher(name).matches();
	}

	public static String nameError(String name) {
		if (!isNameText(name)) {
			return "Name may only use letters, spaces, apostrophes, hyphens and full stops.";
		}
		return plain(AliasValidator.validateCharacterName(name));
	}

	public static String ageError(int age, Race race) {
		int ageMin = Cache.calendarAgeMinimum;
		int ageMax = race.getAgeMax();
		if (age < ageMin || age > ageMax) {
			return "Age must be between " + ageMin + " and " + ageMax + " for your race.";
		}
		return null;
	}

	public static String birthdayError(RPCharacter character, String birthdayIso) {
		return plain(BirthdayValidator.validateForCharacter(character, birthdayIso));
	}

	public static String descriptionError(String description) {
		return plain(DescriptionValidator.validate(description));
	}

	public static String classError(String classId) {
		for (PlayerClass playerClass : ClassPickService.baseClasses()) {
			if (playerClass.getId().equalsIgnoreCase(classId)) {
				return null;
			}
		}
		return "Unknown class: " + classId;
	}

	/** Same limits as {@link RPCharacter#addPlayerClue} plus the character's required count. */
	public static String cluesError(List<String> clues, int needed) {
		if (clues.size() > Cache.maxClues) {
			return "You cannot have more than " + Cache.maxClues + " clues.";
		}
		Set<String> seen = new HashSet<>();
		for (String clue : clues) {
			String text = ClueFormatter.stripColor(clue);
			if (text.length() < Cache.clueMinLength || text.length() > Cache.clueMaxLength) {
				return "Each clue must be " + Cache.clueMinLength + " to " + Cache.clueMaxLength + " characters.";
			}
			if (!seen.add(text.toLowerCase(Locale.ROOT))) {
				return "Duplicate clue: " + text;
			}
		}
		if (clues.size() < needed) {
			return "Needs at least " + needed + " clues.";
		}
		return null;
	}

	/** The race must be offered by a web race stage, when there is one. */
	public static String raceError(Race race, List<Stage> stages) {
		boolean anyRaceStage = false;
		for (Stage stage : stages) {
			if (stage instanceof SelectionStage selection && selection.runsOnWeb()
					&& "race".equalsIgnoreCase(selection.getTarget())) {
				anyRaceStage = true;
				if (race.getRaceData().getKey().equalsIgnoreCase(selection.getKey())) {
					return null;
				}
			}
		}
		return anyRaceStage ? race.getName() + " cannot be picked at creation." : null;
	}

	/**
	 * Trait picks against the web stages, as the in-game selection screens allow them:
	 * stage gates, counts, points, filters, attribute point-buy, conflicts, requirements
	 * and account age.
	 */
	public static String traitsError(RPCharacter draft, PlayerData account, List<Stage> stages) {
		Map<String, List<Trait>> byKey = new LinkedHashMap<>();
		for (Trait trait : draft.getTraits()) {
			byKey.computeIfAbsent(keyOf(trait), k -> new ArrayList<>()).add(trait);
		}
		Set<String> stageKeys = new HashSet<>();
		for (Stage stage : stages) {
			if (!stage.runsOnWeb()) {
				continue;
			}
			String error = null;
			if (stage instanceof SelectionStage selection && "trait".equalsIgnoreCase(selection.getTarget())) {
				String key = selection.getKey().toLowerCase(Locale.ROOT);
				stageKeys.add(key);
				error = selectionError(selection, byKey.getOrDefault(key, List.of()), draft, account);
			} else if (stage instanceof AttributesStage attributes) {
				String key = attributes.getKey().toLowerCase(Locale.ROOT);
				stageKeys.add(key);
				error = attributesError(attributes, byKey.getOrDefault(key, List.of()), draft, account);
			}
			if (error != null) {
				return error;
			}
		}
		for (Trait trait : draft.getTraits()) {
			if (!stageKeys.contains(keyOf(trait))) {
				return trait.getId() + " cannot be picked at creation.";
			}
		}
		return pairingError(draft, account);
	}

	private static String selectionError(SelectionStage stage, List<Trait> picks, RPCharacter draft,
			PlayerData account) {
		if (!opens(stage, draft, account)) {
			return picks.isEmpty() ? null : picks.get(0).getId() + " is not available to this character.";
		}
		boolean permanentOnly = "permanent-only".equalsIgnoreCase(stage.getFilter());
		int cost = 0;
		for (Trait trait : picks) {
			if (permanentOnly && trait.getTraitData().hasDuration()) {
				return trait.getId() + " cannot be picked at creation.";
			}
			cost += trait.getTraitData().getCost();
		}
		if (picks.size() < stage.getMinSelections() || picks.size() > stage.getMaxSelections()) {
			return "Pick " + stage.getMinSelections() + " to " + stage.getMaxSelections() + " "
					+ stage.getKey() + " traits.";
		}
		if (stage.hasPoints() && cost > stage.getInitialPoints()) {
			return "Your " + stage.getKey() + " traits cost more than " + stage.getInitialPoints() + " points.";
		}
		return null;
	}

	/** Ranks must run 1..n per attribute, stay within the max rank and spend the whole pool. */
	private static String attributesError(AttributesStage stage, List<Trait> picks, RPCharacter draft,
			PlayerData account) {
		if (!opens(stage, draft, account)) {
			return picks.isEmpty() ? null : picks.get(0).getId() + " is not available to this character.";
		}
		Set<String> picked = new HashSet<>();
		for (Trait trait : picks) {
			picked.add(trait.getId().toLowerCase(Locale.ROOT));
		}
		long spent = 0;
		for (String attribute : stage.getAttributes()) {
			for (int rank = 1; rank <= stage.getMaxRank()
					&& picked.remove(AttributesStage.traitId(attribute, rank)); rank++) {
				spent += AttributesStage.costForRank(rank);
			}
		}
		if (!picked.isEmpty()) {
			return "Attribute ranks must be bought in order: " + picked.iterator().next();
		}
		if (spent != stage.getPool()) {
			return "Spend exactly " + stage.getPool() + " attribute points (spent " + spent + ").";
		}
		return null;
	}

	private static String pairingError(RPCharacter draft, PlayerData account) {
		List<Trait> traits = draft.getTraits();
		for (Trait trait : traits) {
			TraitData data = trait.getTraitData();
			for (Trait other : traits) {
				if (other != trait && (data.isExclusive(other.getId())
						|| other.getTraitData().isExclusive(trait.getId()))) {
					return trait.getId() + " cannot be combined with " + other.getId() + ".";
				}
			}
			if (data.hasDependency() && !data.getDependency().checkExclude(draft, trait.getId())) {
				return trait.getId() + " needs " + describe(data.getDependency()) + ".";
			}
			if (accountAgeSeconds(account) < data.getRequiredAccountPlaytimeSeconds()) {
				return trait.getId() + " needs an account at least "
						+ data.getRequiredAccountPlaytimeSeconds() / 3600 + " hours old.";
			}
		}
		return null;
	}

	/** Stage dependency and account-age gate, as in-game creation skips stages. */
	private static boolean opens(Stage stage, RPCharacter draft, PlayerData account) {
		return (!stage.hasDependency() || stage.getDependency().check(draft))
				&& stage.passesAccountAgeGate(account);
	}

	private static String describe(Dependency dependency) {
		String joiner = "all".equalsIgnoreCase(dependency.getMode()) ? " and " : " or ";
		return String.join(joiner, dependency.getDependencies());
	}

	private static int accountAgeSeconds(PlayerData account) {
		return account == null ? 0 : account.getAgeSeconds();
	}

	private static String keyOf(Trait trait) {
		return trait.getTraitData().getKey().toLowerCase(Locale.ROOT);
	}

	private static String plain(String formatted) {
		return formatted == null ? null : ClueFormatter.stripColor(formatted);
	}
}
