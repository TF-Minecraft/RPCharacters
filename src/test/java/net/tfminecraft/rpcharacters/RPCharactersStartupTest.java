package net.tfminecraft.rpcharacters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.managers.*;
import net.tfminecraft.rpcharacters.grave.*;
import net.tfminecraft.rpcharacters.speechbubble.*;
import net.tfminecraft.rpcharacters.speechbubble.fake.*;
import net.tfminecraft.rpcharacters.pvp.*;
import net.tfminecraft.rpcharacters.focus.*;

class RPCharactersStartupTest {
    public static class RuntimePlugin extends RPCharacters { @Override public void onEnable(){} @Override public void onDisable(){} public void startRuntime(){super.onEnable();}public void stopRuntime(){super.onDisable();} }
    ServerMock server;RuntimePlugin plugin;RuntimeTestState state;List<AutoCloseable> boundaries=new ArrayList<>();MockedStatic<Bukkit> bukkit;PluginManager plugins;BukkitScheduler scheduler;Server serverFacade;Map<String,PluginCommand> commands=new HashMap<>();MockedConstruction<net.tfminecraft.rpcharacters.database.Database> databases;MockedConstruction<FocusModule> focus;MockedConstruction<ConfigLoader> config;
    @BeforeEach void setup(){server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,PlayerManager.class,StageLoader.class,TraitLoader.class,net.tfminecraft.rpcharacters.chat.ChatRecipientResolverRegistry.class);RPCharacters.plugin=mock(RPCharacters.class);when(RPCharacters.plugin.namespace()).thenReturn("rpcharacters");
        singleton(SpawnedClueManager.class,SpawnedClueManager::get);singleton(GraveManager.class,GraveManager::get);singleton(GraveVisualManager.class,GraveVisualManager::get);singleton(GraveExpiryService.class,GraveExpiryService::get);singleton(LastSolidTracker.class,LastSolidTracker::get);singleton(SpeechBubbleManager.class,SpeechBubbleManager::get);singleton(FakeBubbleManager.class,FakeBubbleManager::get);singleton(net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager.class,net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager::get);singleton(net.tfminecraft.rpcharacters.party.PartyManager.class,net.tfminecraft.rpcharacters.party.PartyManager::get);
        for(Class<?> type:List.of(net.tfminecraft.rpcharacters.ingest.CharacterIngestService.class,net.tfminecraft.rpcharacters.ingest.RosterSyncService.class,net.tfminecraft.rpcharacters.catalog.CreationCatalogSyncService.class,net.tfminecraft.rpcharacters.wardrobe.WardrobeService.class,net.tfminecraft.rpcharacters.mail.MailRecipientDirectory.class,net.tfminecraft.rpcharacters.playtime.PlaytimeService.class,net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.class,ProtocolLibBridge.class,PvpStrikeService.class,net.tfminecraft.rpcharacters.evilrp.EvilRpService.class,net.tfminecraft.rpcharacters.paidchange.PaidChangeListener.class,net.tfminecraft.rpcharacters.permadeath.WorldGuardBridge.class,net.tfminecraft.rpcharacters.playerlist.QuickActionPack.class,net.tfminecraft.rpcharacters.professions.ProfessionCommandHandler.class,net.tfminecraft.rpcharacters.injuries.InjuryHealingService.class,net.tfminecraft.rpcharacters.injuries.OffhandBlockService.class,net.tfminecraft.rpcharacters.prosthetics.ProstheticFuelService.class,ProfessionLoader.class))boundaries.add(mockStatic(type));
        for(Class<?> type:List.of(StageLoader.class,RaceLoader.class,TraitLoader.class,ProfileLoader.class,PersonaLoader.class,PermissionGroupsLoader.class,WebCreatorLoader.class,MaskLoader.class,SkillPointTomeLoader.class,AttributePointTomeLoader.class,ChatLoader.class,ProfileViewLoader.class,PlayerListLoader.class,RollLoader.class,CalendarLoader.class,ProfessionsGlobalLoader.class,SpeechBubbleLoader.class,SmartMessageLoader.class,ClueDiscoveryLoader.class,MagnifyingGlassLoader.class,PermadeathZoneLoader.class,InjuryPoolLoader.class,FuelTemplateLoader.class,InjuryProgressionLoader.class,ProstheticLoader.class,KitLoader.class,PvpLoader.class,PartyLoader.class,GraveLoader.class,net.tfminecraft.rpcharacters.tutorial.TutorialLoader.class,net.tfminecraft.rpcharacters.evilrp.EvilRpLoader.class,net.tfminecraft.rpcharacters.professions.ProfessionEffectService.class,net.tfminecraft.rpcharacters.permadeath.PermadeathDependencyListener.class,PvpKnockoutManager.class,net.tfminecraft.rpcharacters.placeholder.RpCharactersExpansion.class))boundaries.add(mockConstruction(type));
        databases=mockConstruction(net.tfminecraft.rpcharacters.database.Database.class);boundaries.add(databases);config=mockConstruction(ConfigLoader.class);boundaries.add(config);focus=mockConstruction(FocusModule.class);boundaries.add(focus);
        plugin=spy(MockBukkit.loadSimple(RuntimePlugin.class));RPCharacters.plugin=plugin;plugins=mock(PluginManager.class);serverFacade=mock(Server.class);when(serverFacade.getPluginManager()).thenReturn(plugins);when(serverFacade.getWorlds()).thenReturn(List.of());doReturn(serverFacade).when(plugin).getServer();doAnswer(c->commands.computeIfAbsent(c.getArgument(0),key->mock(PluginCommand.class))).when(plugin).getCommand(anyString());
        scheduler=mock(BukkitScheduler.class);when(scheduler.runTaskTimer(any(),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(BukkitTask.class));when(scheduler.runTaskLater(any(),any(Runnable.class),anyLong())).thenReturn(mock(BukkitTask.class));when(scheduler.runTask(any(),any(Runnable.class))).thenReturn(mock(BukkitTask.class));
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);boundaries.add(bukkit);bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    }
    <T> T singleton(Class<T> type,MockedStatic.Verification getter){var api=mockStatic(type);boundaries.add(api);T instance=mock(type);api.when(getter).thenReturn(instance);return instance;}
    @AfterEach void cleanup()throws Exception{for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close();state.close();MockBukkit.unmock();}
    @Test void startupWiresCommandsListenersConfigurationAndShutdown(){plugin.startRuntime();assertSame(plugin,RPCharacters.plugin);assertEquals(1,config.constructed().size());assertEquals(1,focus.constructed().size());verify(focus.constructed().getFirst()).start();assertTrue(commands.keySet().containsAll(List.of("focus","rpcharacter","roll","profession","channel","channeltoggle","pvp","grave","players")));verify(plugins,atLeast(40)).registerEvents(any(),eq(plugin));verify(commands.get("rpcharacter")).setExecutor(any());verify(config.constructed().getFirst()).load(new File(plugin.getDataFolder(),"config.yml"));plugin.stopRuntime();verify(focus.constructed().getFirst()).shutdown();}
    @Test void reloadReportsFocusFailureAndRecoveryWhileRefreshingOtherConfiguration()throws Exception{plugin.startRuntime();var module=focus.constructed().getFirst();var service=mock(FocusService.class);when(module.getService()).thenReturn(service);assertSame(service,RPCharacters.getFocusService());var sender=server.getConsoleSender();plugin.reloadConfigs(sender);when(module.reloadConfig()).thenReturn(true);assertTrue(plugin.reloadFocusConfig());plugin.reload();plugin.reloadConfigs(null);plugin.reloadConfigs(sender);verify(module,atLeast(4)).reloadConfig();doThrow(new IllegalStateException("broken config")).when(config.constructed().getFirst()).load(any());assertDoesNotThrow(()->plugin.reloadConfigs(sender));assertDoesNotThrow(()->plugin.reloadConfigs(null));plugin.stopRuntime();}
    @Test void repeatedStartupReusesDependencyComponentsAndOptionalIntegrationsRegister()throws Exception{when(plugins.isPluginEnabled("SimpleFactions")).thenReturn(true);when(plugins.isPluginEnabled("PlaceholderAPI")).thenReturn(true);var world=mock(World.class);File dataFolder=plugin.getDataFolder();when(world.getWorldFolder()).thenReturn(dataFolder);when(serverFacade.getWorlds()).thenReturn(List.of(world));plugin.createFolders();Files.writeString(plugin.getDataFolder().toPath().resolve("professions.yml"),"{}\n");Files.createDirectories(plugin.getDataFolder().toPath().resolve("traits/subdirectory"));plugin.startRuntime();plugin.stopRuntime();plugin.startRuntime();assertEquals(1,config.constructed().size());assertEquals(2,focus.constructed().size());plugin.stopRuntime();}
    @Test void onlineReloadRefreshesStageWindowsAndSaveStampsCurrentLocation() throws Exception {
        plugin.startRuntime();
        var player=server.addPlayer();var unloaded=server.addPlayer();
        var pd=new net.tfminecraft.rpcharacters.objects.PlayerData(player);
        var cfg=new org.bukkit.configuration.file.YamlConfiguration();cfg.set("name","Human");
        var character=new net.tfminecraft.rpcharacters.objects.RPCharacter(player,UUID.randomUUID().toString(),"Scout",true,net.tfminecraft.rpcharacters.enums.Status.ALIVE,new net.tfminecraft.rpcharacters.objects.races.Race("human",cfg),new ArrayList<>(),null);
        pd.getCharacters().add(character);character.setPaidChangeCount("race",4);
        var stage=new net.tfminecraft.rpcharacters.creation.Stage();stage.setId("race");stage.setRevision(2);StageLoader.oList.add(stage);
        doNothing().when(plugin).loadConfigs();
        try(var players=mockStatic(PlayerManager.class,CALLS_REAL_METHODS)){
            players.when(()->PlayerManager.exists(player)).thenReturn(true);
            players.when(()->PlayerManager.exists(unloaded)).thenReturn(true);
            players.when(()->PlayerManager.get(player)).thenReturn(pd);
            players.when(()->PlayerManager.get(unloaded)).thenReturn(null);
            plugin.loadPlayers();players.verify(()->PlayerManager.exists(player));
            plugin.reload();assertEquals(2,character.getStageRevision("race"));assertEquals(0,character.getPaidChangeCount("race"));assertTrue(character.getStageRevisionSince("race")>0);
            var saved=Path.of("plugins/RPCharacters/data/characterdata",player.getUniqueId().toString(),character.getId()+".json");
            assertTrue(Files.exists(saved));var firstSave=Files.readString(saved);assertTrue(firstSave.contains("stage-revisions"));
            plugin.reload();assertEquals(firstSave,Files.readString(saved));
            player.teleport(new Location(server.addSimpleWorld("save-world"),12,70,18));plugin.save();assertTrue(Files.readString(saved).contains("save-world"));
            players.verify(()->PlayerManager.stampActiveCharacterLocation(player));
        } finally {
            for(var owner:List.of(player.getUniqueId(),unloaded.getUniqueId())) {
                var directory=Path.of("plugins/RPCharacters/data/characterdata",owner.toString());
                if(Files.exists(directory)){try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
                Files.deleteIfExists(Path.of("plugins/RPCharacters/data/playerdata",owner+".json"));
            }
        }
    }
}
