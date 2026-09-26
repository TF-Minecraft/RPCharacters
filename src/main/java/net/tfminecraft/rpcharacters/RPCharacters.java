package net.tfminecraft.rpcharacters;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.rpcharacters.loaders.CalendarLoader;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.loaders.ConfigLoader;
import net.tfminecraft.rpcharacters.loaders.MaskLoader;
import net.tfminecraft.rpcharacters.loaders.AttributePointTomeLoader;
import net.tfminecraft.rpcharacters.loaders.SkillPointTomeLoader;
import net.tfminecraft.rpcharacters.loaders.PermissionGroupsLoader;
import net.tfminecraft.rpcharacters.loaders.WebCreatorLoader;
import net.tfminecraft.rpcharacters.loaders.PersonaLoader;
import net.tfminecraft.rpcharacters.loaders.PlayerListLoader;
import net.tfminecraft.rpcharacters.loaders.ProfessionLoader;
import net.tfminecraft.rpcharacters.loaders.ProfessionsGlobalLoader;
import net.tfminecraft.rpcharacters.loaders.ProfileLoader;
import net.tfminecraft.rpcharacters.loaders.ProfileViewLoader;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.loaders.RollLoader;
import net.tfminecraft.rpcharacters.loaders.SpeechBubbleLoader;
import net.tfminecraft.rpcharacters.loaders.SmartMessageLoader;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.ClueInputManager;
import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.loaders.MagnifyingGlassLoader;
import net.tfminecraft.rpcharacters.loaders.InjuryPoolLoader;
import net.tfminecraft.rpcharacters.loaders.InjuryProgressionLoader;
import net.tfminecraft.rpcharacters.loaders.FuelTemplateLoader;
import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.loaders.KitLoader;
import net.tfminecraft.rpcharacters.loaders.PermadeathZoneLoader;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.loaders.PartyLoader;
import net.tfminecraft.rpcharacters.managers.CommandManager;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.ClueDisturbanceListener;
import net.tfminecraft.rpcharacters.managers.MagnifyingGlassListener;
import net.tfminecraft.rpcharacters.managers.PlaceClueManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.managers.AttributePointCommandListener;
import net.tfminecraft.rpcharacters.managers.AttributePointSpendListener;
import net.tfminecraft.rpcharacters.managers.AttributePointTomeListener;
import net.tfminecraft.rpcharacters.permadeath.PermadeathDependencyListener;
import net.tfminecraft.rpcharacters.permadeath.PermadeathZoneListener;
import net.tfminecraft.rpcharacters.permadeath.WorldGuardBridge;
import net.tfminecraft.rpcharacters.prosthetics.ProstheticInstallListener;
import net.tfminecraft.rpcharacters.prosthetics.ProstheticRefuelListener;
import net.tfminecraft.rpcharacters.injuries.RpInjureListener;
import net.tfminecraft.rpcharacters.managers.SkillPointCommandListener;
import net.tfminecraft.rpcharacters.managers.SkillPointTomeListener;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.chat.ChatChannelCommandHandler;
import net.tfminecraft.rpcharacters.chat.ChatChannelCommandInterceptor;
import net.tfminecraft.rpcharacters.chat.ChatChannelPreferenceManager;
import net.tfminecraft.rpcharacters.chat.ChatCooldownManager;
import net.tfminecraft.rpcharacters.chat.ChatManager;
import net.tfminecraft.rpcharacters.chat.VanillaChatKillSwitch;
import net.tfminecraft.rpcharacters.conversation.ConversationManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.CommandTabCompleter;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.api.CharacterSkull;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.mail.CharacterMailTarget;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.persona.PersonaCooldownManager;
import net.tfminecraft.rpcharacters.profile.ProfileManager;
import net.tfminecraft.rpcharacters.profile.ProfileViewCooldownManager;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.professions.ProfessionCommandHandler;
import net.tfminecraft.rpcharacters.professions.ProfessionEffectService;
import net.tfminecraft.rpcharacters.professions.ProfessionListener;
import net.tfminecraft.rpcharacters.grave.GraveCommand;
import net.tfminecraft.rpcharacters.grave.GraveDeathListener;
import net.tfminecraft.rpcharacters.grave.GraveInsuranceListener;
import net.tfminecraft.rpcharacters.grave.GraveInteractListener;
import net.tfminecraft.rpcharacters.grave.GraveLoader;
import net.tfminecraft.rpcharacters.evilrp.EvilRpListener;
import net.tfminecraft.rpcharacters.evilrp.EvilRpLoader;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.tutorial.TutorialLoader;
import net.tfminecraft.rpcharacters.grave.GraveExpiryService;
import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.rpcharacters.grave.GraveVisualManager;
import net.tfminecraft.rpcharacters.grave.LastSolidTracker;
import net.tfminecraft.rpcharacters.playerlist.PlayerListCommand;
import net.tfminecraft.rpcharacters.playerlist.QuickActionPack;
import net.tfminecraft.rpcharacters.pvp.PvpCommand;
import net.tfminecraft.rpcharacters.pvp.PvpKnockoutManager;
import net.tfminecraft.rpcharacters.pvp.PvpStrikeService;
import net.tfminecraft.rpcharacters.party.PartyChatRecipientResolver;
import net.tfminecraft.rpcharacters.party.PartyListener;
import net.tfminecraft.rpcharacters.chat.ChatRecipientResolverRegistry;
import net.tfminecraft.rpcharacters.roll.RollManager;
import net.tfminecraft.rpcharacters.placeholder.RpCharactersExpansion;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleListener;
import net.tfminecraft.rpcharacters.speechbubble.SpeechBubbleManager;
import net.tfminecraft.rpcharacters.speechbubble.fake.FakeBubbleManager;
import net.tfminecraft.rpcharacters.speechbubble.fake.ProtocolLibBridge;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeListener;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

public class RPCharacters extends JavaPlugin{
	public static RPCharacters plugin;
	private net.tfminecraft.rpcharacters.focus.FocusModule focusModule;
	
	private final CommandManager commandManager = new CommandManager();
	private static final PlayerManager playerManager = new PlayerManager();
	private final CreationManager creationManager = new CreationManager();
	private final ClueInputManager clueInputManager = new ClueInputManager();
	private final PlaceClueManager placeClueManager = new PlaceClueManager();
	private final MagnifyingGlassListener magnifyingGlassListener = new MagnifyingGlassListener();
	private final ClueDisturbanceListener clueDisturbanceListener = new ClueDisturbanceListener();
	private final SpawnedClueManager spawnedClueManager = SpawnedClueManager.get();
	private final net.tfminecraft.rpcharacters.playtime.PlaytimeListener playtimeListener =
			new net.tfminecraft.rpcharacters.playtime.PlaytimeListener();
	private final SkillPointTomeListener skillPointTomeListener = new SkillPointTomeListener();
	private final SkillPointCommandListener skillPointCommandListener = new SkillPointCommandListener();
	private final AttributePointTomeListener attributePointTomeListener = new AttributePointTomeListener();
	private final ProstheticRefuelListener prostheticRefuelListener = new ProstheticRefuelListener();
	private final ProstheticInstallListener prostheticInstallListener = new ProstheticInstallListener();
	private final PermadeathZoneListener permadeathZoneListener = new PermadeathZoneListener();
	private final PermadeathDependencyListener permadeathDependencyListener =
			new PermadeathDependencyListener();
	private final AttributePointCommandListener attributePointCommandListener = new AttributePointCommandListener();
	private final AttributePointSpendListener attributePointSpendListener = new AttributePointSpendListener();
	private final ConversationManager conversationManager = new ConversationManager();
	private final ChatManager chatManager = new ChatManager();
	private final ProfileManager profileManager = new ProfileManager();
	private final RollManager rollManager = new RollManager();
	private final ProfessionListener professionListener = new ProfessionListener();
	private ProfessionEffectService professionEffectService;
	private final ProfessionCommandHandler professionCommandHandler = new ProfessionCommandHandler();
	private final SpeechBubbleListener speechBubbleListener = new SpeechBubbleListener();
	private final ChatChannelCommandHandler chatChannelCommandHandler = new ChatChannelCommandHandler();
	private final WardrobeListener wardrobeListener = new WardrobeListener();
	private final RpInjureListener rpInjureListener = new RpInjureListener();

	private ConfigLoader configLoader;
	private StageLoader stageLoader;
	private RaceLoader raceLoader;
	private TraitLoader traitLoader;
	private ProfileLoader profileLoader;
	private PersonaLoader personaLoader;
	private PermissionGroupsLoader permissionGroupsLoader;
	private WebCreatorLoader webCreatorLoader;
	private MaskLoader maskLoader;
	private SkillPointTomeLoader skillPointTomeLoader;
	private AttributePointTomeLoader attributePointTomeLoader;
	private ChatLoader chatLoader;
	private ProfileViewLoader profileViewLoader;
	private PlayerListLoader playerListLoader;
	private RollLoader rollLoader;
	private CalendarLoader calendarLoader;
	private ProfessionsGlobalLoader professionsGlobalLoader;
	private SpeechBubbleLoader speechBubbleLoader;
	private SmartMessageLoader smartMessageLoader;
	private ClueDiscoveryLoader clueDiscoveryLoader;
	private MagnifyingGlassLoader magnifyingGlassLoader;
	private PermadeathZoneLoader permadeathZoneLoader;
	private InjuryPoolLoader injuryPoolLoader;
	private FuelTemplateLoader fuelTemplateLoader;
	private InjuryProgressionLoader injuryProgressionLoader;
	private ProstheticLoader prostheticLoader;
	private KitLoader kitLoader;
	private PvpLoader pvpLoader;
	private PartyLoader partyLoader;
	private GraveLoader graveLoader;
	private TutorialLoader tutorialLoader;
	private EvilRpLoader evilRpLoader;
	private final PvpCommand pvpCommand = new PvpCommand();
	private final PlayerListCommand playerListCommand = new PlayerListCommand();
	private final GraveCommand graveCommand = new GraveCommand();
	private final PvpKnockoutManager pvpKnockoutManager = new PvpKnockoutManager();
	private final PartyListener partyListener = new PartyListener();
	private final PartyChatRecipientResolver partyChatRecipientResolver = new PartyChatRecipientResolver();

	private void initDependencyComponents() {
		if (configLoader != null) {
			return;
		}
		professionEffectService = new ProfessionEffectService();
		configLoader = new ConfigLoader();
		stageLoader = new StageLoader();
		raceLoader = new RaceLoader();
		traitLoader = new TraitLoader();
		profileLoader = new ProfileLoader();
		personaLoader = new PersonaLoader();
		permissionGroupsLoader = new PermissionGroupsLoader();
		webCreatorLoader = new WebCreatorLoader();
		maskLoader = new MaskLoader();
		skillPointTomeLoader = new SkillPointTomeLoader();
		attributePointTomeLoader = new AttributePointTomeLoader();
		chatLoader = new ChatLoader();
		profileViewLoader = new ProfileViewLoader();
		playerListLoader = new PlayerListLoader();
		rollLoader = new RollLoader();
		calendarLoader = new CalendarLoader();
		professionsGlobalLoader = new ProfessionsGlobalLoader();
		speechBubbleLoader = new SpeechBubbleLoader();
		smartMessageLoader = new SmartMessageLoader();
		clueDiscoveryLoader = new ClueDiscoveryLoader();
		magnifyingGlassLoader = new MagnifyingGlassLoader();
		permadeathZoneLoader = new PermadeathZoneLoader();
		injuryPoolLoader = new InjuryPoolLoader();
		fuelTemplateLoader = new FuelTemplateLoader();
		injuryProgressionLoader = new InjuryProgressionLoader();
		prostheticLoader = new ProstheticLoader();
		kitLoader = new KitLoader();
		pvpLoader = new PvpLoader();
		partyLoader = new PartyLoader();
		graveLoader = new GraveLoader();
		tutorialLoader = new TutorialLoader();
		evilRpLoader = new EvilRpLoader();
	}
	
	@Override
	public void onEnable() {
		plugin = this;
		initDependencyComponents();
		createFolders();
		createConfigs();
		registerListeners();
		loadConfigs();
		net.tfminecraft.rpcharacters.party.PartyManager.get()
				.load(new File(getDataFolder(), "data/parties.json").toPath());
		spawnedClueManager.loadAllFromDisk();
		net.tfminecraft.rpcharacters.playtime.PlaytimeService.loadAllFromDisk();
		net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.loadFromDisk(
				getDataFolder().toPath().resolve("data/characterdata"), getLogger()::warning);
		GraveManager.get().loadAll();
		MailRecipientDirectory.scanFromDisk();
		loadPlayers();
		focusModule = new net.tfminecraft.rpcharacters.focus.FocusModule(this);
		focusModule.start();
		var focusCommand = new net.tfminecraft.rpcharacters.focus.FocusCommand(this);
		getCommand("focus").setExecutor(focusCommand);
		getCommand("focus").setTabCompleter(focusCommand);
		startManagers();
		getCommand(commandManager.cmd1).setExecutor(commandManager);
		getCommand("rpcharacter").setTabCompleter(new CommandTabCompleter());
		getCommand("roll").setExecutor(rollManager);
		getCommand("roll").setTabCompleter(rollManager);
		getCommand(ProfessionCommandHandler.COMMAND).setExecutor(professionCommandHandler);
		getCommand(ProfessionCommandHandler.COMMAND).setTabCompleter(professionCommandHandler);
		getCommand("channel").setExecutor(chatChannelCommandHandler);
		getCommand("channel").setTabCompleter(chatChannelCommandHandler);
		getCommand("channeltoggle").setExecutor(chatChannelCommandHandler);
		getCommand("channeltoggle").setTabCompleter(chatChannelCommandHandler);
		getCommand(PvpCommand.COMMAND).setExecutor(pvpCommand);
		getCommand(PvpCommand.COMMAND).setTabCompleter(pvpCommand);
		getCommand(GraveCommand.COMMAND).setExecutor(graveCommand);
		getCommand(GraveCommand.COMMAND).setTabCompleter(graveCommand);
		getCommand(PlayerListCommand.COMMAND).setExecutor(playerListCommand);
		ChatRecipientResolverRegistry.register(
				net.tfminecraft.rpcharacters.party.PartyManager.PARTY_RESOLVER_ID,
				partyChatRecipientResolver);
		registerPlaceholderApi();
	}
	@Override
	public void onDisable() {
		if (focusModule != null) focusModule.shutdown();
		ChatRecipientResolverRegistry.unregister(
				net.tfminecraft.rpcharacters.party.PartyManager.PARTY_RESOLVER_ID);
		net.tfminecraft.rpcharacters.ingest.CharacterIngestService.stopPeriodicPull();
		WardrobeService.stopSoftRefresh();
		ProtocolLibBridge.shutdown();
		GraveVisualManager.get().shutdown();
		GraveExpiryService.get().shutdown();
		SpeechBubbleManager.get().shutdown();
		net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager.get().shutdown();
		spawnedClueManager.shutdown();
		net.tfminecraft.rpcharacters.playtime.PlaytimeService.shutdown();
		pvpCommand.shutdown();
		pvpKnockoutManager.shutdown();
		EvilRpService.shutdown();
		PvpStrikeService.shutdown();
		LastSolidTracker.get().shutdown();
		GraveManager.get().saveAll();
		save();
	}
	
	public void save() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			PlayerManager.stampActiveCharacterLocation(p);
			playerManager.savePlayer(p);
		}
	}
	public void loadPlayers() {
		for(Player p : Bukkit.getOnlinePlayers()) {
			playerManager.initiatePlayer(p);
		}
	}
	
	public void registerListeners() {
		getServer().getPluginManager().registerEvents(playerManager, this);
		getServer().getPluginManager().registerEvents(new net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady(), this);
		getServer().getPluginManager().registerEvents(creationManager, this);
		getServer().getPluginManager().registerEvents(clueInputManager, this);
		getServer().getPluginManager().registerEvents(placeClueManager, this);
		getServer().getPluginManager().registerEvents(magnifyingGlassListener, this);
		getServer().getPluginManager().registerEvents(clueDisturbanceListener, this);
		getServer().getPluginManager().registerEvents(skillPointTomeListener, this);
		getServer().getPluginManager().registerEvents(skillPointCommandListener, this);
		getServer().getPluginManager().registerEvents(attributePointTomeListener, this);
		getServer().getPluginManager().registerEvents(prostheticRefuelListener, this);
		getServer().getPluginManager().registerEvents(prostheticInstallListener, this);
		getServer().getPluginManager().registerEvents(permadeathZoneListener, this);
		getServer().getPluginManager().registerEvents(permadeathDependencyListener, this);
		getServer().getPluginManager().registerEvents(attributePointCommandListener, this);
		getServer().getPluginManager().registerEvents(attributePointSpendListener, this);
		getServer().getPluginManager().registerEvents(commandManager, this);
		getServer().getPluginManager().registerEvents(spawnedClueManager, this);
		getServer().getPluginManager().registerEvents(playtimeListener, this);
		getServer().getPluginManager().registerEvents(conversationManager, this);
		getServer().getPluginManager().registerEvents(chatManager, this);
		getServer().getPluginManager().registerEvents(new VanillaChatKillSwitch(), this);
		getServer().getPluginManager().registerEvents(new ChatChannelCommandInterceptor(), this);
		getServer().getPluginManager().registerEvents(ChatCooldownManager.get(), this);
		getServer().getPluginManager().registerEvents(ChatChannelPreferenceManager.get(), this);
		getServer().getPluginManager().registerEvents(profileManager, this);
		getServer().getPluginManager().registerEvents(playerListCommand, this);
		getServer().getPluginManager().registerEvents(ProfileViewCooldownManager.get(), this);
		getServer().getPluginManager().registerEvents(PersonaCooldownManager.get(), this);
		getServer().getPluginManager().registerEvents(professionListener, this);
		getServer().getPluginManager().registerEvents(professionEffectService, this);
		getServer().getPluginManager().registerEvents(speechBubbleListener, this);
		getServer().getPluginManager().registerEvents(wardrobeListener, this);
		getServer().getPluginManager().registerEvents(rpInjureListener, this);
		getServer().getPluginManager().registerEvents(pvpKnockoutManager, this);
		getServer().getPluginManager().registerEvents(pvpCommand, this);
		getServer().getPluginManager().registerEvents(partyListener, this);
		getServer().getPluginManager().registerEvents(new GraveDeathListener(), this);
		getServer().getPluginManager().registerEvents(new GraveInteractListener(), this);
		getServer().getPluginManager().registerEvents(new GraveInsuranceListener(), this);
		getServer().getPluginManager().registerEvents(new EvilRpListener(), this);
	}
	public void startManagers() {
		playerManager.start();
		spawnedClueManager.startTicks();
		net.tfminecraft.rpcharacters.playtime.PlaytimeService.startTicks();
		SpeechBubbleManager.get().startTicks();
		ProtocolLibBridge.init(this);
		GraveVisualManager.get().startTicks();
		GraveExpiryService.get().start();
		FakeBubbleManager.get().startTicks();
		net.tfminecraft.rpcharacters.ingest.CharacterIngestService.startPeriodicPull(this);
		WardrobeService.startSoftRefresh(this);
		pvpKnockoutManager.start();
		EvilRpService.start();
		PvpStrikeService.start();
		LastSolidTracker.get().start();
	}
	public void loadConfigs() {
		configLoader.load(new File(getDataFolder(), "config.yml"));
		personaLoader.load(new File(getDataFolder(), "persona.yml"));
		permissionGroupsLoader.load(new File(getDataFolder(), "permission-groups.yml"));
		webCreatorLoader.load(new File(getDataFolder(), "web-creator.yml"));
		maskLoader.load(new File(getDataFolder(), "masks.yml"));
		skillPointTomeLoader.load(new File(getDataFolder(), "items.yml"));
		attributePointTomeLoader.load(new File(getDataFolder(), "items.yml"));
		magnifyingGlassLoader.load(new File(getDataFolder(), "items.yml"));
		permadeathZoneLoader.load(new File(getDataFolder(), "zones.yml"));
		clueDiscoveryLoader.load(new File(getDataFolder(), "clue-discovery.yml"));
		chatLoader.load(new File(getDataFolder(), "chat.yml"));
		speechBubbleLoader.load(new File(getDataFolder(), "speechbubbles.yml"));
		smartMessageLoader.load(new File(getDataFolder(), "smart-messages.yml"));
		profileViewLoader.load(new File(getDataFolder(), "profile-view.yml"));
		playerListLoader.load(new File(getDataFolder(), "player-list.yml"));
		if (!getServer().getWorlds().isEmpty()) {
			QuickActionPack.syncMainWorld(getServer().getWorlds().get(0).getWorldFolder().toPath(),
					Cache.playerList, getLogger());
		}
		rollLoader.load(new File(getDataFolder(), "rolls.yml"));
		calendarLoader.load(new File(getDataFolder(), "calendar.yml"));
		File professionsGlobal = new File(getDataFolder(), "professions.yml");
		if (professionsGlobal.isFile()) {
			professionsGlobalLoader.load(professionsGlobal);
		}
		ProfessionLoader.reload(new File(getDataFolder(), "professions"));
		profileLoader.load(new File(getDataFolder(), "profile.yml"));
		raceLoader.load(new File(getDataFolder(), "races.yml"));
		File folder = new File(getDataFolder(), "traits");
		TraitLoader.oList.clear();
		File[] traitFiles = folder.listFiles();
		if (traitFiles != null) {
			for (final File file : traitFiles) {
				if (!file.isDirectory()) {
					traitLoader.load(file);
				}
			}
		}
		fuelTemplateLoader.load(new File(getDataFolder(), "fuel-templates.yml"));
		injuryProgressionLoader.load(new File(getDataFolder(), "injury-progression.yml"));
		prostheticLoader.load(new File(getDataFolder(), "prosthetics.yml"));
		injuryPoolLoader.load(new File(getDataFolder(), "injuries.yml"));
		StageLoader.oList.clear();
		stageLoader.load(new File(getDataFolder(), "stages.yml"));
		kitLoader.loadPreferred(getDataFolder());
		pvpLoader.load(new File(getDataFolder(), "pvp.yml"));
		partyLoader.load(new File(getDataFolder(), "party.yml"));
		graveLoader.load(new File(getDataFolder(), "graves.yml"));
		tutorialLoader.load(new File(getDataFolder(), "tutorials.yml"));
		evilRpLoader.load(new File(getDataFolder(), "evil-rp.yml"));
		WorldGuardBridge.init();
		permadeathDependencyListener.registerSimpleFactionsIfPresent();
		net.tfminecraft.rpcharacters.catalog.CreationCatalogSyncService.pushAsync(this);
		net.tfminecraft.rpcharacters.ingest.CharacterIngestService.tryPullAsync(this);
	}
	
	public void createFolders() {
		if (!getDataFolder().exists()) getDataFolder().mkdir();
		File subFolder = new File(getDataFolder(), "traits");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "data");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "data/playerdata");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "data/characterdata");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "professions");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "assets");
		if(!subFolder.exists()) subFolder.mkdir();
		subFolder = new File(getDataFolder(), "graves");
		if(!subFolder.exists()) subFolder.mkdir();
	}
	
	public void createConfigs() {
		String[] files = {
				"stages.yml",
				"races.yml",
				"config.yml",
				"profile.yml",
				"persona.yml",
				"masks.yml",
				"items.yml",
				"clue-discovery.yml",
				"chat.yml",
				"speechbubbles.yml",
				"smart-messages.yml",
				"profile-view.yml",
				"player-list.yml",
				"rolls.yml",
				"calendar.yml",
				"permission-groups.yml",
				"web-creator.yml",
				"zones.yml",
				"injuries.yml",
				"injury-progression.yml",
				"fuel-templates.yml",
				"prosthetics.yml",
				"kits.yml",
				"pvp.yml",
				"party.yml",
				"graves.yml",
				"tutorials.yml",
				"evil-rp.yml"
				};
		for(String s : files) {
			File newConfigFile = new File(getDataFolder(), s);
	        if (!newConfigFile.exists()) {
	        	newConfigFile.getParentFile().mkdirs();
	            saveResource(s, false);
	        }
		}
		File knifeSkin = new File(getDataFolder(), "assets/knife_skin.png");
		if (!knifeSkin.exists()) {
			knifeSkin.getParentFile().mkdirs();
			saveResource("assets/knife_skin.png", false);
		}
		File journalSkin = new File(getDataFolder(), "assets/journal_skin.png");
		if (!journalSkin.exists()) {
			journalSkin.getParentFile().mkdirs();
			saveResource("assets/journal_skin.png", false);
		}
		File journalSkinSigned = new File(getDataFolder(), "assets/journal_skin_signed.png");
		if (!journalSkinSigned.exists()) {
			journalSkinSigned.getParentFile().mkdirs();
			saveResource("assets/journal_skin_signed.png", false);
		}
		File maskedSkin = new File(getDataFolder(), "assets/masked.png");
		if (!maskedSkin.exists()) {
			maskedSkin.getParentFile().mkdirs();
			saveResource("assets/masked.png", false);
		}
		String[] traitFiles = {
				"ambition-traits.yml",
				"attributes-traits.yml",
				"cataclysm-traits.yml",
				"celestial-traits.yml",
				"combat-traits.yml",
				"evil-traits.yml",
				"expedition-traits.yml",
				"gift-traits.yml",
				"homeland-traits.yml",
				"injury-traits.yml",
				"motivation-traits.yml",
				"personality-traits.yml",
				"physical-traits.yml",
				"prosthetic-traits.yml",
				"reclamation-traits.yml",
				"virtue-traits.yml"
		};
		for (String traitFile : traitFiles) {
			File traitConfig = new File(getDataFolder(), "traits/" + traitFile);
			if (!traitConfig.exists()) {
				traitConfig.getParentFile().mkdirs();
				saveResource("traits/" + traitFile, false);
			}
		}
	}
	
	public void reload() {
		reloadWithFocusStatus();
	}

	private boolean reloadWithFocusStatus() {
		loadConfigs();
		boolean focusReloaded = reloadFocusConfig();
		if (!focusReloaded) {
			getLogger().warning("Focus did not reload; see the preceding focus error. "
					+ "Continuing the other RPCharacters reload steps.");
		}
		LastSolidTracker.get().start();
		ProfessionCommandHandler.reapplyActiveCharacterPerms();
		// Catalog + pending pull already run inside loadConfigs(); also refresh website sheets.
		net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushAllOnlineAsync();
		return focusReloaded;
	}

	public void reloadConfigs(CommandSender sender) {
		String name = sender != null ? sender.getName() : "unknown";
		getLogger().info("Config reload requested by " + name);
		if (sender != null) {
			RPTexts.sendPrefixed(sender, RPTexts.WARN + "Reloading configs...");
		}
		try {
			boolean focusReloaded = reloadWithFocusStatus();
			getLogger().info(focusReloaded
					? "Config reload complete (catalog + online roster sync kicked)."
					: "Other configs reloaded (catalog + online roster sync kicked); focus did not reload.");
			if (sender != null) {
				RPTexts.sendPrefixed(sender, RPTexts.WARN + (focusReloaded
						? "Reloading complete!"
						: "Other configs reloaded; focus did not reload. Check console for the focus error."));
			}
		} catch (Exception e) {
			getLogger().severe("Config reload failed: " + e.getMessage());
			e.printStackTrace();
			if (sender != null) {
				RPTexts.sendPrefixed(sender, RPTexts.ERROR + "Reload failed. Check console.");
			}
		}
	}

	/** Character focus; null when startup fails. */
	public static net.tfminecraft.rpcharacters.focus.FocusService getFocusService() {
		return plugin == null || plugin.focusModule == null ? null : plugin.focusModule.getService();
	}

	public boolean reloadFocusConfig() {
		return focusModule != null && focusModule.reloadConfig();
	}

	public static PlayerManager getPlayerManager() {
		return playerManager;
	}

	/** External Discord gate (TFMCWeb); see {@link PlayerManager#setDiscordGate(Player, boolean)}. */
	public static void setDiscordGate(Player player, boolean required) {
		playerManager.setDiscordGate(player, required);
	}

	/** External Discord gate by UUID (online reevaluate if present). */
	public static void setDiscordGate(java.util.UUID id, boolean required) {
		playerManager.setDiscordGate(id, required);
	}

	public static boolean isDiscordGate(java.util.UUID id) {
		return playerManager.isDiscordGate(id);
	}

	public static SpawnedClueManager getSpawnedClueManager() {
		return SpawnedClueManager.get();
	}

	public static PlaceClueManager getPlaceClueManager() {
		return plugin.placeClueManager;
	}

	public static int getAccountAgeSeconds(Player player) {
		if (player == null) {
			return 0;
		}
		PlayerData data = PlayerManager.get(player);
		return data != null ? data.getAgeSeconds() : 0;
	}

	public static int getCharacterAgeSeconds(RPCharacter character) {
		return character != null ? character.getAgeSeconds() : 0;
	}

	public static int getConversationCount(RPCharacter character, String otherCharacterId) {
		return character != null ? character.getConversationCount(otherCharacterId) : 0;
	}

	public static List<Map.Entry<String, Integer>> getTopConversationPartners(RPCharacter character, int limit) {
		return ConversationManager.getTopPartners(character, limit);
	}

	public static RPCharacter getActiveCharacter(Player player) {
		if (player == null) {
			return null;
		}
		PlayerData data = PlayerManager.get(player);
		return data != null ? data.getActiveCharacter() : null;
	}

	/** Player head for the viewer's active character, or their account head. */
	public static ItemStack getSkull(Player player) {
		return CharacterSkull.ofActive(player);
	}

	public static List<CharacterMailTarget> listMailTargets() {
		return MailRecipientDirectory.listMailTargets();
	}

	/**
	 * Fetch missing base wardrobe textures from ProvinceSystem, then run {@code onComplete}
	 * on the main thread (e.g. before opening the bird mail character picker).
	 */
	public static void refreshMailTargetTexturesAsync(Runnable onComplete) {
		MailRecipientDirectory.refreshMissingTexturesAsync(onComplete);
	}

	public static Location getMailTargetLocation(UUID ownerUuid, String characterId) {
		return MailRecipientDirectory.getMailTargetLocation(ownerUuid, characterId);
	}

	public static String getCharacterName(Player player) {
		return DisplayIdentityService.resolveCharacterName(player);
	}

	public static String getDisplay(Player player) {
		return DisplayIdentityService.resolveDisplay(player);
	}

	public static String getDisplayTab(Player player) {
		return DisplayIdentityService.resolveDisplayTab(player);
	}

	public static String getDisplaySafe(Player player) {
		return DisplayIdentityService.resolveDisplaySafe(player);
	}

	public static void grantAttributePoints(Player player, int amount) {
		AttributePointService.grantAttributePoints(player, amount);
	}

	public static int getAccountAttributePointsTotal(Player player) {
		if (player == null) {
			return 0;
		}
		PlayerData data = PlayerManager.get(player);
		return data != null ? data.getAccountAttributePointsTotal() : 0;
	}

	public static int getFreeAttributePoints(Player player) {
		if (player == null) {
			return 0;
		}
		PlayerData data = PlayerManager.get(player);
		if (data == null) {
			return 0;
		}
		RPCharacter active = data.getActiveCharacter();
		if (active == null) {
			return data.getAccountAttributePointsTotal();
		}
		return Math.max(0, data.getAccountAttributePointsTotal() - active.getSpentExtraAttributePoints());
	}

	private void registerPlaceholderApi() {
		if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			new RpCharactersExpansion().register();
			getLogger().info("Registered PlaceholderAPI expansion: rpcharacters");
		} else {
			getLogger().warning("PlaceholderAPI not found — %rpcharacters_*% placeholders will not work.");
		}
	}
}
