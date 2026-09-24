package net.tfminecraft.rpcharacters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.tfminecraft.rpcharacters.playerlist.PlayerListSettings;

public class Cache {
	public static List<String> attributes = new ArrayList<>();
	public static Set<String> ignoredAttributes = new HashSet<>();
	public static List<String> professions = new ArrayList<>();
	
	public static List<String> backgroundTraitTypes = new ArrayList<>();

	public static List<String> editableTraits = new ArrayList<>();
	
	//Profile stuff
	
	public static List<Integer> characterSlots;
	public static int baseCharacterSlotCount;
	public static int deadSlot;
	public static int maxCharacterSlots;

	public static Map<String, Integer> permissionGroupDefaults = new HashMap<>();
	public static List<net.tfminecraft.rpcharacters.objects.PermissionGroupDefinition> permissionGroups = new ArrayList<>();
	public static Map<String, net.tfminecraft.rpcharacters.objects.WebCreatorRealmAccess> webCreatorAccessByRealm = new HashMap<>();

	public static boolean requireCharacter;
	/** Playtest server: tag in-game creates and stop syncing characters with the website. */
	public static boolean devCharacters;
	public static boolean noCharacterFreeze;
	public static boolean lackingCluesFreeze;
	public static boolean excessCharactersFreeze;

	public static int startingProfessionFactor;

	public static int defaultCluesRequired;
	public static int evilCluesRequired = 4;
	public static int evilMinAccountAgeHours = 24;
	public static int characterDescriptionMinLength = 32;
	public static int characterDescriptionMaxLength = 256;
	public static int clueMinLength;
	public static int clueMaxLength;
	public static int maxClues;
	public static Map<String, Integer> traitClueOverrides = new HashMap<>();
	public static String raceClueTemplate;

	public static int spawnedClueTimerHours;
	public static double spawnedClueVisualYOffset;
	public static double spawnedClueLineSpacing;
	public static double spawnedClueFirstLineOffset;
	public static float spawnedClueScale;
	public static int spawnedClueParticleInterval;
	public static int clueSpawnRadius;
	public static int spawnedClueLineLength;
	public static int conversationReplyTimeoutSeconds = 30;
	public static int conversationPairCooldownHours = 2;

	public static double rpInjureRange = 10;
	public static int rpInjureTimeoutSeconds = 30;

	public static boolean skillPointsAdminDebugMessages = false;
	public static boolean attributePointsAdminDebugMessages = false;

	public static String continent = "Cerrith";

	public static String personaSetPermission = "rpchar.persona.set";
	public static String personaNamecolourPermission = "rpchar.namecolour";
	public static String personaDescriptionColorsPermission = "rpchar.persona.colors";
	public static String personaOverridePermission = "rpchar.persona.override";
	public static String personaBypassCooldownPermission = "rpchar.persona.bypasscooldown";
	public static String personaTempaliasPermission = "rpchar.tempalias";
	public static String personaCharacterHiddenPermission = "rpchar.character.hidden";
	public static String personaNoCharacterFallback = "§f§oUnknown";

	public static int personaDisplayNameMinLength = 3;
	public static int personaDisplayNameMaxLength = 24;
	public static int personaAliasMinLength = 3;
	public static int personaAliasMaxLength = 24;
	public static String personaAliasAllowedChars = "abcdefghijklmnopqrstuvwxyz.,-'' áéíóú";
	public static int personaAliasCooldownSeconds = 10;

	public static List<String> personaGenders = new ArrayList<>();
	public static String personaGenderDefault = "Unset";
	public static int personaGenderCooldownSeconds = 10;

	public static int personaDescriptionMinLength = 3;
	public static int personaDescriptionCooldownSeconds = 10;
	public static String personaDescriptionDefaultTemplate = "A{n} {race} in {continent}.";

	public static String maskedLabel = "Masked";

	public static String chatDefaultChannel = "rp";
	public static String chatBypassCooldownPermission = "rpchar.chat.bypasscooldown";
	public static String chatNoCharacterMessage = "&cNo active character. &e/rpcharacter &cto create one, or &e/ooc &cfor OOC.";

	public static boolean chatChannelSwitcherEnabled = true;
	public static List<String> chatSwitchableChannels = new ArrayList<>();
	public static boolean chatChannelTogglerEnabled = true;
	public static List<String> chatToggleableChannels = new ArrayList<>();

	public static String chatRunAsPlayerMessage = "&cPlayers only.";
	public static String chatChannelSwitchDisabledMessage = "&cChannel switching is disabled.";
	public static String chatChannelToggleDisabledMessage = "&cChannel toggling is disabled.";
	public static String chatChannelInvalidUseMessage = "&cBad syntax. Try /{usage}.";
	public static String chatChannelInvalidChannelMessage = "&cYou cannot target that channel. Allowed channels: {channels}.";
	public static String chatChannelAlreadySwitchedMessage = "&cYou are already using that channel.";
	public static String chatChannelSwitchedMessage = "&aSwitched to {channel}.";
	public static String chatChannelCurrentMessage = "&7Your default chat channel is &e{channel}&7.";
	public static String chatChannelToggledOnMessage = "&aToggled {channel} visibility ON.";
	public static String chatChannelToggledOffMessage = "&eToggled {channel} visibility OFF.";
	public static String chatChannelCantUseWhenToggledOffMessage = "&cChannel is toggled off. Toggle it back on.";

	public static String profilePermission = "rpchar.profile";
	public static boolean profileRequireSneak = true;
	public static boolean profileRequireEmptyHand = true;
	public static int profileViewCooldownSeconds = 0;
	public static List<String> profileFormatLines = new ArrayList<>();

	public static PlayerListSettings playerList = PlayerListSettings.defaults();

	public static String rollPermission = "rpchar.roll";
	public static String rollAltPermission = "rpchar.roll.alt";
	public static int rollDefaultMin = 1;
	public static int rollDefaultMax = 100;
	public static int rollAltMin = 1;
	public static int rollAltMax = 200;
	public static int rollD20Min = 1;
	public static int rollD20Max = 20;
	public static String rollBroadcastText = "&e{display} &7rolled a &6{roll}{modifier} &7out of {max}.";
	public static int rollBroadcastRange = 20;

	public static int calendarYearOffset = 1654;
	public static String calendarEraSuffix = "AE";
	public static int calendarAgeMinimum = 18;
	public static String calendarAgeUnsetLabel = "Unset";

	public static int professionMaxSpendingPoints = 40;
	public static String professionPermContext = "main";
	public static List<String> professionLockedBreeding = new ArrayList<>();
	public static List<String> professionBreedingExp = new ArrayList<>();
	public static boolean professionAdminDebugMessages = false;
	public static List<net.tfminecraft.rpcharacters.professions.ProfessionItemType> professionItemTypes = new ArrayList<>();
}
