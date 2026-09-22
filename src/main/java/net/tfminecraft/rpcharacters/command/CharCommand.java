package net.tfminecraft.rpcharacters.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.calendar.BirthdayValidator;
import net.tfminecraft.rpcharacters.calendar.FantasyCalendar;
import net.tfminecraft.rpcharacters.identity.NameColour;
import net.tfminecraft.rpcharacters.identity.TempAliasService;
import net.tfminecraft.rpcharacters.persona.AliasValidator;
import net.tfminecraft.rpcharacters.persona.DescriptionValidator;
import net.tfminecraft.rpcharacters.persona.NameColourParser;
import net.tfminecraft.rpcharacters.profile.ProfileManager;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.persona.PersonaCooldownManager;

public final class CharCommand {

	private static final Set<String> SUBCOMMANDS = Set.of(
			"alias", "namecolour", "gender", "description", "profile", "override", "birthday");

	private static final String FIELD_ALIAS = "alias";
	private static final String FIELD_GENDER = "gender";
	private static final String FIELD_DESCRIPTION = "description";

	private CharCommand() {}

	public static boolean isPersonaSubcommand(String subcommand) {
		return subcommand != null && SUBCOMMANDS.contains(subcommand.toLowerCase(Locale.ROOT));
	}

	public static boolean handle(CommandSender sender, String label, String[] args) {
		if (args.length == 0) {
			RPTexts.send(sender, RPTexts.WARN + "Usage: /" + label
					+ " <alias|namecolour|gender|description|profile|birthday|override>");
			return true;
		}

		String sub = args[0].toLowerCase(Locale.ROOT);
		if (sub.equals("override")) {
			return handleOverride(sender, label, args);
		}
		if (sub.equals("profile")) {
			if (!(sender instanceof Player player)) {
				RPTexts.send(sender, RPTexts.ERROR + "Only players can use this command.");
				return true;
			}
			return handleProfile(player, args);
		}

		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, RPTexts.ERROR + "Only players can use this command.");
			return true;
		}

		switch (sub) {
			case "alias":
				return handleAlias(player, label, args);
			case "namecolour":
				return handleNamecolour(player, label, args);
			case "gender":
				return handleGender(player, label, args);
			case "description":
				return handleDescription(player, label, args);
			case "birthday":
				return handleBirthday(player, label, args);
			default:
				RPTexts.send(player, RPTexts.ERROR
						+ "Unknown subcommand. Use alias, namecolour, gender, description, profile, or birthday.");
				return true;
		}
	}

	private static boolean handleAlias(Player player, String label, String[] args) {
		if (!player.hasPermission(Cache.personaSetPermission)) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to change your alias.");
			return true;
		}
		RPCharacter character = requireActiveCharacter(player);
		if (character == null) {
			return true;
		}
		if (args.length < 2) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /" + label + " alias <name...>|clear");
			return true;
		}
		String aliasInput = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
		if (aliasInput.equalsIgnoreCase("clear")) {
			character.clearAlias();
			RPCharacters.getPlayerManager().savePlayer(player);
			RPTexts.send(player, RPTexts.SUCCESS + "Alias cleared.");
			return true;
		}
		if (isOnCooldown(player, FIELD_ALIAS, Cache.personaAliasCooldownSeconds)) {
			return true;
		}
		String error = AliasValidator.validate(aliasInput);
		if (error != null) {
			RPTexts.send(player, error);
			return true;
		}
		character.setAlias(aliasInput);
		applyCooldown(player, FIELD_ALIAS, Cache.personaAliasCooldownSeconds);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, RPTexts.SUCCESS + "Alias set to " + RPTexts.WARN + character.getAlias()
				+ RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean handleNamecolour(Player player, String label, String[] args) {
		if (!PermissionGroupService.canUseNameColour(player)) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to change your name colour.");
			return true;
		}
		RPCharacter character = requireActiveCharacter(player);
		if (character == null) {
			return true;
		}
		if (args.length < 2) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /" + label + " namecolour <#hex> [<#hex>...]|clear");
			return true;
		}
		if (args[1].equalsIgnoreCase("clear")) {
			character.setNameColour(null);
			character.setNameColourStaffOverride(false);
			RPCharacters.getPlayerManager().savePlayer(player);
			RPTexts.send(player, RPTexts.SUCCESS + "Name colour cleared.");
			return true;
		}
		List<String> hexArgs = Arrays.asList(Arrays.copyOfRange(args, 1, args.length));
		Optional<String> validationError = PermissionGroupService.validateNameColourHexes(player, hexArgs, false);
		if (validationError.isPresent()) {
			RPTexts.send(player, validationError.get());
			return true;
		}
		List<String> parsed = parseHexColours(player, hexArgs);
		if (parsed == null) {
			return true;
		}
		character.setNameColour(NameColour.of(parsed));
		character.setNameColourStaffOverride(false);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, RPTexts.SUCCESS + "Name colour updated.");
		return true;
	}

	private static boolean handleGender(Player player, String label, String[] args) {
		if (!player.hasPermission(Cache.personaSetPermission)) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to change your gender.");
			return true;
		}
		RPCharacter character = requireActiveCharacter(player);
		if (character == null) {
			return true;
		}
		if (args.length < 2) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /" + label + " gender <"
					+ String.join("|", Cache.personaGenders) + ">");
			return true;
		}
		if (isOnCooldown(player, FIELD_GENDER, Cache.personaGenderCooldownSeconds)) {
			return true;
		}
		String value = resolveGenderValue(args[1]);
		if (value == null) {
			RPTexts.send(player, RPTexts.ERROR + "Invalid gender. Allowed: " + RPTexts.WARN
					+ String.join(", ", Cache.personaGenders));
			return true;
		}
		character.setGender(value);
		applyCooldown(player, FIELD_GENDER, Cache.personaGenderCooldownSeconds);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, RPTexts.SUCCESS + "Gender set to " + RPTexts.WARN + value + RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean handleDescription(Player player, String label, String[] args) {
		if (!player.hasPermission(Cache.personaSetPermission)) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to change your description.");
			return true;
		}
		RPCharacter character = requireActiveCharacter(player);
		if (character == null) {
			return true;
		}
		if (args.length < 2) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /" + label + " description <text...>|clear");
			return true;
		}
		if (args[1].equalsIgnoreCase("clear")) {
			character.setPersonaDescription(null);
			RPCharacters.getPlayerManager().savePlayer(player);
			RPTexts.send(player, RPTexts.SUCCESS + "Description cleared.");
			return true;
		}
		if (isOnCooldown(player, FIELD_DESCRIPTION, Cache.personaDescriptionCooldownSeconds)) {
			return true;
		}
		String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
		String text = player.hasPermission(Cache.personaDescriptionColorsPermission)
				? raw.trim()
				: ClueFormatter.stripColor(raw);
		String error = DescriptionValidator.validate(text);
		if (error != null) {
			RPTexts.send(player, error);
			return true;
		}
		character.setPersonaDescription(text);
		applyCooldown(player, FIELD_DESCRIPTION, Cache.personaDescriptionCooldownSeconds);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, RPTexts.SUCCESS + "Description updated.");
		return true;
	}

	private static boolean handleBirthday(Player player, String label, String[] args) {
		if (!player.hasPermission(Cache.personaSetPermission)) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to change your birthday.");
			return true;
		}
		RPCharacter character = requireActiveCharacter(player);
		if (character == null) {
			return true;
		}
		if (args.length < 2) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /" + label + " birthday <DD.MM.YYYY>|clear");
			return true;
		}
		if (args[1].equalsIgnoreCase("clear")) {
			character.setBirthday(null);
			RPCharacters.getPlayerManager().savePlayer(player);
			RPTexts.send(player, RPTexts.SUCCESS + "Birthday cleared.");
			return true;
		}
		String birthdayInput = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
		String birthdayIso = FantasyCalendar.parseBirthdayInput(birthdayInput);
		if (birthdayIso == null) {
			RPTexts.send(player, RPTexts.ERROR + "Invalid birthday. Use fantasy date format "
					+ RPTexts.WARN + "DD.MM.YYYY" + RPTexts.ERROR + ".");
			return true;
		}
		String validationError = BirthdayValidator.validateForCharacter(character, birthdayIso);
		if (validationError != null) {
			RPTexts.send(player, validationError);
			return true;
		}
		character.setBirthday(birthdayIso);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, RPTexts.SUCCESS + "Birthday set to " + RPTexts.WARN
				+ FantasyCalendar.formatBirthday(character.getBirthday()) + RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean handleProfile(Player viewer, String[] args) {
		if (!viewer.hasPermission(Cache.profilePermission)) {
			RPTexts.send(viewer, RPTexts.ERROR + "You do not have permission to view character profiles.");
			return true;
		}
		Player target = viewer;
		if (args.length >= 2) {
			Player named = Bukkit.getPlayerExact(args[1]);
			if (named == null) {
				RPTexts.send(viewer, RPTexts.ERROR + "Player not found.");
				return true;
			}
			target = named;
		}
		ProfileManager.showProfile(viewer, target, true);
		return true;
	}

	private static boolean handleOverride(CommandSender sender, String label, String[] args) {
		if (!sender.hasPermission(Cache.personaOverridePermission)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to override persona fields.");
			return true;
		}
		if (args.length < 4) {
			RPTexts.send(sender, RPTexts.WARN + "Usage: /" + label
					+ " override <player> <alias|tempalias|gender|description|namecolour|birthday|playtime> <value...>");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[1]);
		if (target == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Player not found.");
			return true;
		}
		PlayerData data = PlayerManager.get(target);
		if (data == null || !data.hasActiveCharacter()) {
			RPTexts.send(sender, RPTexts.ERROR + "That player has no active character.");
			return true;
		}
		RPCharacter character = data.getActiveCharacter();
		String field = args[2].toLowerCase(Locale.ROOT);
		switch (field) {
			case "alias":
				return overrideAlias(sender, target, character, args);
			case "tempalias":
				return overrideTempalias(sender, target, args);
			case "gender":
				return overrideGender(sender, target, character, args);
			case "description":
				return overrideDescription(sender, target, character, args);
			case "namecolour":
				return overrideNamecolour(sender, target, character, args);
			case "birthday":
				return overrideBirthday(sender, target, character, args);
			case "playtime":
				return overridePlaytime(sender, target, args);
			default:
				RPTexts.send(sender, RPTexts.ERROR
						+ "Unknown field. Use alias, tempalias, gender, description, namecolour, birthday, or playtime.");
				return true;
		}
	}

	private static boolean overrideAlias(CommandSender sender, Player target, RPCharacter character, String[] args) {
		String aliasInput = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
		if (aliasInput.equalsIgnoreCase("clear")) {
			character.clearAlias();
			RPCharacters.getPlayerManager().savePlayer(target);
			RPTexts.send(sender, RPTexts.SUCCESS + "Cleared alias for " + RPTexts.WARN + target.getName()
					+ RPTexts.SUCCESS + ".");
			return true;
		}
		String error = AliasValidator.validate(aliasInput, false);
		if (error != null) {
			RPTexts.send(sender, error);
			return true;
		}
		character.setAlias(aliasInput);
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Set alias for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + " to " + RPTexts.WARN + character.getAlias() + RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean overrideTempalias(CommandSender sender, Player target, String[] args) {
		String aliasInput = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
		if (aliasInput.equalsIgnoreCase("clear")) {
			TempAliasService.clear(target);
			RPTexts.send(sender, RPTexts.SUCCESS + "Cleared temp alias for " + RPTexts.WARN + target.getName()
					+ RPTexts.SUCCESS + ".");
			return true;
		}
		String error = AliasValidator.validate(aliasInput, false);
		if (error != null) {
			RPTexts.send(sender, error);
			return true;
		}
		error = TempAliasService.setPlain(target, aliasInput);
		if (error != null) {
			RPTexts.send(sender, error);
			return true;
		}
		RPTexts.send(sender, RPTexts.SUCCESS + "Set temp alias for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + " to " + RPTexts.WARN + TempAliasService.getPlain(target) + RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean overrideGender(CommandSender sender, Player target, RPCharacter character, String[] args) {
		String value = resolveGenderValue(args[3]);
		if (value == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Invalid gender. Allowed: " + RPTexts.WARN
					+ String.join(", ", Cache.personaGenders));
			return true;
		}
		character.setGender(value);
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Set gender for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + " to " + RPTexts.WARN + value + RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean overrideDescription(CommandSender sender, Player target, RPCharacter character, String[] args) {
		if (args[3].equalsIgnoreCase("clear")) {
			character.setPersonaDescription(null);
			RPCharacters.getPlayerManager().savePlayer(target);
			RPTexts.send(sender, RPTexts.SUCCESS + "Cleared description for " + RPTexts.WARN + target.getName()
					+ RPTexts.SUCCESS + ".");
			return true;
		}
		String raw = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
		String text = sender.hasPermission(Cache.personaDescriptionColorsPermission)
				? raw.trim()
				: ClueFormatter.stripColor(raw);
		String error = DescriptionValidator.validate(text);
		if (error != null) {
			RPTexts.send(sender, error);
			return true;
		}
		character.setPersonaDescription(text);
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Updated description for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean overrideNamecolour(CommandSender sender, Player target, RPCharacter character, String[] args) {
		if (args[3].equalsIgnoreCase("clear")) {
			character.setNameColour(null);
			character.setNameColourStaffOverride(false);
			RPCharacters.getPlayerManager().savePlayer(target);
			RPTexts.send(sender, RPTexts.SUCCESS + "Cleared name colour for " + RPTexts.WARN + target.getName()
					+ RPTexts.SUCCESS + ".");
			return true;
		}
		List<String> hexArgs = Arrays.asList(Arrays.copyOfRange(args, 3, args.length));
		Optional<String> validationError = PermissionGroupService.validateNameColourHexes(target, hexArgs, true);
		if (validationError.isPresent()) {
			RPTexts.send(sender, validationError.get());
			return true;
		}
		List<String> parsed = parseHexColours(sender, hexArgs);
		if (parsed == null) {
			return true;
		}
		character.setNameColour(NameColour.of(parsed));
		character.setNameColourStaffOverride(true);
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Updated name colour for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean overrideBirthday(CommandSender sender, Player target, RPCharacter character, String[] args) {
		if (args[3].equalsIgnoreCase("clear")) {
			character.setBirthday(null);
			RPCharacters.getPlayerManager().savePlayer(target);
			RPTexts.send(sender, RPTexts.SUCCESS + "Cleared birthday for " + RPTexts.WARN + target.getName()
					+ RPTexts.SUCCESS + ".");
			return true;
		}
		String birthdayInput = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
		String birthdayIso = FantasyCalendar.parseBirthdayInput(birthdayInput);
		if (birthdayIso == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Invalid birthday. Use fantasy date format "
					+ RPTexts.WARN + "DD.MM.YYYY" + RPTexts.ERROR + ".");
			return true;
		}
		String validationError = BirthdayValidator.validateForCharacter(character, birthdayIso);
		if (validationError != null) {
			RPTexts.send(sender, validationError);
			return true;
		}
		character.setBirthday(birthdayIso);
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Set birthday for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + " to " + RPTexts.WARN
				+ FantasyCalendar.formatBirthday(character.getBirthday()) + RPTexts.SUCCESS + ".");
		return true;
	}

	private static boolean overridePlaytime(CommandSender sender, Player target, String[] args) {
		if (args.length < 4) {
			RPTexts.send(sender, RPTexts.WARN + "Usage: /rpcharacter override <player> playtime <hours|seconds|clear>");
			return true;
		}
		PlayerData data = PlayerManager.get(target);
		if (data == null) {
			RPTexts.send(sender, RPTexts.ERROR + "Player data not found.");
			return true;
		}
		String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
		long now = java.time.Instant.now().getEpochSecond();
		int createdAt;
		if (value.equalsIgnoreCase("clear")) {
			createdAt = (int) now;
		} else {
			Integer parsed = parsePlaytimeSeconds(value);
			if (parsed == null) {
				RPTexts.send(sender, RPTexts.ERROR + "Invalid age. Use " + RPTexts.WARN + "30h" + RPTexts.ERROR + ", "
						+ RPTexts.WARN + "1800s" + RPTexts.ERROR + ", decimal hours, or " + RPTexts.WARN + "clear"
						+ RPTexts.ERROR + ".");
				return true;
			}
			createdAt = (int) Math.max(0L, now - parsed);
		}
		data.setCreatedAtEpochSeconds(createdAt);
		RPCharacters.getPlayerManager().savePlayer(target);
		RPTexts.send(sender, RPTexts.SUCCESS + "Set account age for " + RPTexts.WARN + target.getName()
				+ RPTexts.SUCCESS + " to " + RPTexts.WARN
				+ net.tfminecraft.rpcharacters.utils.AgeFormatter.formatAge(data.getAgeSeconds()) + RPTexts.SUCCESS + ".");
		return true;
	}

	private static Integer parsePlaytimeSeconds(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim().toLowerCase(Locale.ROOT);
		try {
			if (trimmed.endsWith("s")) {
				return Integer.parseInt(trimmed.substring(0, trimmed.length() - 1).trim());
			}
			if (trimmed.endsWith("h")) {
				double hours = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1).trim());
				return (int) Math.round(hours * 3600.0);
			}
			double hours = Double.parseDouble(trimmed);
			return (int) Math.round(hours * 3600.0);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static RPCharacter requireActiveCharacter(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data == null || !data.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You must have an active character.");
			return null;
		}
		return data.getActiveCharacter();
	}

	private static String resolveGenderValue(String input) {
		for (String allowed : Cache.personaGenders) {
			if (allowed.equalsIgnoreCase(input)) {
				return allowed;
			}
		}
		return null;
	}

	private static boolean isOnCooldown(Player player, String field, int cooldownSeconds) {
		if (player.hasPermission(Cache.personaBypassCooldownPermission)) {
			return false;
		}
		PersonaCooldownManager cooldowns = PersonaCooldownManager.get();
		if (!cooldowns.isOnCooldown(player, field, cooldownSeconds)) {
			return false;
		}
		int remaining = cooldowns.getRemainingSeconds(player, field);
		RPTexts.send(player, RPTexts.ERROR + "Wait " + RPTexts.WARN + remaining + RPTexts.ERROR + "s.");
		return true;
	}

	private static void applyCooldown(Player player, String field, int cooldownSeconds) {
		if (player.hasPermission(Cache.personaBypassCooldownPermission)) {
			return;
		}
		PersonaCooldownManager.get().applyCooldown(player, field, cooldownSeconds);
	}

	private static List<String> parseHexColours(CommandSender sender, List<String> hexArgs) {
		List<String> parsed = new ArrayList<>();
		for (String arg : hexArgs) {
			Optional<String> hex = NameColourParser.parse(arg);
			if (hex.isEmpty()) {
				RPTexts.send(sender, RPTexts.ERROR + "Invalid hex colour: " + RPTexts.WARN + arg);
				return null;
			}
			parsed.add(hex.get());
		}
		return parsed;
	}
}
