package net.tfminecraft.rpcharacters.progress;

import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.experience.*;
import net.Indyuce.mmocore.manager.*;
import net.Indyuce.mmocore.manager.data.PlayerDataManager;
import net.Indyuce.mmocore.manager.profession.ProfessionManager;
import net.Indyuce.mmocore.player.DefaultPlayerData;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.*;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.professions.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

abstract class ProgressFixture {
    @TempDir Path root;
    MMOCore oldMmo; RuntimeTestState state; Player player; PlayerData account;
    net.Indyuce.mmocore.api.player.PlayerData mmo;
    net.Indyuce.mmoitems.api.player.PlayerData items;
    PlayerClass warrior; ClassManager classes; Profession profession; PlayerProfessions skills;
    CharacterProgressService service; ProgressStore store;
    final List<AutoCloseable> boundaries = new ArrayList<>();
    final List<Runnable> later = new ArrayList<>();
    MockedStatic<net.Indyuce.mmocore.api.player.PlayerData> mmoPlayers;
    MockedStatic<PlayerManager> players; MockedStatic<MmoCorePlayerReady> ready;
    MockedStatic<Bukkit> bukkit;
    @BeforeEach void setUp() throws Exception {
        MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, ClassService.class, ProfessionRegistry.class);
        oldMmo = MMOCore.plugin; MMOCore.plugin = mock(MMOCore.class);
        when(MMOCore.plugin.getDataFolder()).thenReturn(root.toFile());
        RPCharacters.plugin = mock(RPCharacters.class);
        when(RPCharacters.plugin.getLogger()).thenReturn(mock(Logger.class));
        var manager = mock(PlayerManager.class);
        boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);
        classes = mock(ClassManager.class); field("classManager", classes);
        warrior = mock(PlayerClass.class); when(warrior.getId()).thenReturn("warrior");
        when(classes.get("warrior")).thenReturn(warrior); when(classes.getDefaultClass()).thenReturn(warrior);
        field("attributeManager", mock(AttributeManager.class)); field("skillTreeManager", mock(SkillTreeManager.class));
        var dataManager = mock(PlayerDataManager.class); field("playerDataManager", dataManager);
        when(dataManager.getDefaultData()).thenReturn(new DefaultPlayerData(new YamlConfiguration()));
        var professions = mock(ProfessionManager.class); field("professionManager", professions);
        profession = mock(Profession.class); when(profession.getId()).thenReturn("mining");
        when(professions.getAll()).thenReturn(List.of(profession)); when(professions.get("mining")).thenReturn(profession);
        var definition = mock(ProfessionDefinition.class); when(definition.getId()).thenReturn("mining");
        ProfessionRegistry.setProfessions(List.of(definition));
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("ProgressOwner"); when(player.isOnline()).thenReturn(true);
        account = new PlayerData(player); account.setAccountSkillPointsTotal(12); account.setAccountAttributePointsTotal(7);
        account.setAccountRewardSkillPoints(3); account.setAccountProfessionPoints("mining", 8); account.setProfessionPointsInitialized(true);
        players = boundary(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenAnswer(call -> account);
        mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class, RETURNS_DEEP_STUBS);
        items = mock(net.Indyuce.mmoitems.api.player.PlayerData.class);
        boundary(net.Indyuce.mmoitems.api.player.PlayerData.class)
                .when(() -> net.Indyuce.mmoitems.api.player.PlayerData.get(mmo.getMMOPlayerData())).thenReturn(items);
        when(mmo.getProfess()).thenReturn(warrior);
        var level=new AtomicInteger(8);var experience=new AtomicReference<Double>(30d);
        when(mmo.getLevel()).thenAnswer(c->level.get());when(mmo.getExperience()).thenAnswer(c->experience.get());
        doAnswer(c->{level.set(c.getArgument(0));return null;}).when(mmo).setLevel(anyInt());
        doAnswer(c->{level.set(c.getArgument(0));return null;}).when(mmo).setLevel(anyInt(),any(net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent.Reason.class));
        doAnswer(c->{experience.set(c.getArgument(0));return null;}).when(mmo).setExperience(anyDouble());
        when(mmo.mapSkillLevels()).thenReturn(Map.of()); when(mmo.mapSkillTreePoints()).thenReturn(Map.of());
        when(mmo.getNodeLevels()).thenReturn(Map.of()); when(mmo.getItemClaims()).thenReturn(Map.of());
        when(mmo.getSavedClasses()).thenReturn(Set.of()); when(mmo.mapBoundSkills()).thenReturn(Map.of()); when(mmo.getBoundSkills()).thenReturn(Map.of());
        when(mmo.getUnlockedItems()).thenReturn(Set.of()); when(mmo.getWaypoints()).thenReturn(new HashSet<>());
        skills = mock(PlayerProfessions.class); when(mmo.getCollectionSkills()).thenReturn(skills);
        when(skills.getLevel(profession)).thenReturn(5); when(skills.getExperience(profession)).thenReturn(12d);
        mmoPlayers = boundary(net.Indyuce.mmocore.api.player.PlayerData.class); mmoPlayers.when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        ready = boundary(MmoCorePlayerReady.class); ready.when(() -> MmoCorePlayerReady.isReady(player)).thenReturn(true);
        bukkit = boundary(Bukkit.class); bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        var scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(c -> { later.add(c.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(c -> { later.add(c.getArgument(1)); return mock(BukkitTask.class); });
        store = new ProgressStore(root.resolve("progress"));
    }
    @AfterEach void tearDown() throws Exception {
        if (CharacterProgressService.active() != null) CharacterProgressService.active().close();
        for (int i = boundaries.size()-1; i >= 0; i--) boundaries.get(i).close();
        MMOCore.plugin = oldMmo; state.close(); MockBukkit.unmock();
    }
    <T> MockedStatic<T> boundary(Class<T> type) { var mocked = mockStatic(type); boundaries.add(mocked); return mocked; }
    void field(String key, Object value) throws Exception { var field = MMOCore.class.getField(key); field.setAccessible(true); field.set(MMOCore.plugin, value); }
    RPCharacter character() {
        var character = mock(RPCharacter.class); var active = new AtomicBoolean(); var progress = new AtomicReference<CharacterProgress>();
        when(character.getId()).thenReturn(UUID.randomUUID().toString()); when(character.getName()).thenReturn("Aria");
        when(character.getOwner()).thenReturn(player); when(character.isActive()).thenAnswer(c -> active.get());
        doAnswer(c -> { active.set(c.getArgument(0)); return null; }).when(character).setActiveState(anyBoolean());
        when(character.getProgress()).thenAnswer(c -> progress.get()); doAnswer(c -> { progress.set(c.getArgument(0)); return null; }).when(character).setProgress(any());
        account.getCharacters().add(character); return character;
    }
    CharacterProgress progress(String id) { return new CharacterProgress(account.getUniqueId(), id, CharacterProgress.FRESH, MmoProgressCodec.fresh()); }
    void enable() throws java.io.IOException { service = new CharacterProgressService(root.resolve("progress")); service.recordLegacyActive(account,account.getActiveCharacter()); }
    void play(RPCharacter character) throws Exception { account.getCharacters().forEach(c -> c.setActiveState(false)); character.setActiveState(true); service.activate(account, character); }
}
