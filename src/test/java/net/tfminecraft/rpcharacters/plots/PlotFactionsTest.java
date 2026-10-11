package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.simplefactions.SimpleFactions;
import net.tfminecraft.simplefactions.espionage.EspionageMath;
import net.tfminecraft.simplefactions.espionage.EspionageService;
import net.tfminecraft.simplefactions.espionage.EspionageState;
import net.tfminecraft.simplefactions.espionage.SpecialPositionAssignment;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.managers.ProvinceManager;
import net.tfminecraft.simplefactions.map.ProvinceGrid;
import net.tfminecraft.simplefactions.map.provinces.Province;
import net.tfminecraft.simplefactions.objects.Faction;
import net.tfminecraft.simplefactions.war.battle.warband.Warband;
import net.tfminecraft.simplefactions.war.battle.warband.WarbandManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlotFactionsTest {
    final List<MockedStatic<?>> statics = new ArrayList<>();
    MockedStatic<Bukkit> bukkit;
    final PluginManager plugins = mock(PluginManager.class);
    final Faction faction = mock(Faction.class);
    boolean provinces;
    String worldName;
    SimpleFactions instance;

    <T> MockedStatic<T> boundary(MockedStatic<T> mocked) {
        statics.add(mocked);
        return mocked;
    }

    @BeforeEach
    void setup() {
        provinces = net.tfminecraft.simplefactions.Cache.provincesEnabled;
        worldName = net.tfminecraft.simplefactions.Cache.worldName;
        instance = SimpleFactions.plugin;
        bukkit = boundary(mockStatic(Bukkit.class));
        bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        when(plugins.isPluginEnabled("SimpleFactions")).thenReturn(true);
    }

    @AfterEach
    void close() {
        for (int index = statics.size() - 1; index >= 0; index--) statics.get(index).close();
        net.tfminecraft.simplefactions.Cache.provincesEnabled = provinces;
        net.tfminecraft.simplefactions.Cache.worldName = worldName;
        SimpleFactions.plugin = instance;
    }

    @Test
    void withoutSimpleFactionsThereAreNoSpymastersOrLands() {
        when(plugins.isPluginEnabled("SimpleFactions")).thenReturn(false);
        assertNull(PlotFactions.spymaster("Ana"));
        assertFalse(PlotFactions.sameFaction("Ana", "Bo"));
        assertEquals(List.of(), PlotFactions.members("Ana"));
        assertNull(PlotFactions.lands(new Location(null, 0, 0, 0)));
        assertNull(PlotFactions.intrigueRoll(Map.of(), mock(RandomGenerator.class)));
        assertFalse(PlotFactions.inWarband(mock(Player.class)));
        bukkit.when(Bukkit::getPluginManager).thenReturn(null);
        assertFalse(PlotFactions.enabled());
        bukkit.when(Bukkit::getServer).thenReturn(null);
        assertFalse(PlotFactions.enabled());
    }

    @Test
    void theSpymasterIsTheFactionsValidHolderWithTheirOfficeIntrigue() {
        var factions = boundary(mockStatic(FactionManager.class));
        var espionage = boundary(mockStatic(EspionageService.class));
        var players = boundary(mockStatic(PlayerManager.class));
        assertNull(PlotFactions.spymaster("Ana"));
        factions.when(() -> FactionManager.getByMember("Ana")).thenReturn(faction);
        assertNull(PlotFactions.spymaster("Ana"));
        espionage.when(() -> EspionageService.hasSpymaster(faction)).thenReturn(true);
        EspionageState state = new EspionageState();
        SpecialPositionAssignment holder = new SpecialPositionAssignment();
        holder.playerName = "Spy";
        state.appoint(holder, 70);
        when(faction.getEspionage()).thenReturn(state);
        assertNull(PlotFactions.spymaster("Ana"));
        holder.characterId = "c-spy";
        assertNull(PlotFactions.spymaster("Ana"));
        holder.playerId = UUID.randomUUID();
        espionage.when(() -> EspionageService.effectiveAptitude(faction, holder)).thenReturn(64);
        var spymaster = PlotFactions.spymaster("Ana");
        assertEquals(64, spymaster.intrigue());
        assertEquals("Spy", spymaster.character().name, "no loaded character name falls back to the account");
        PlayerData data = mock(PlayerData.class);
        RPCharacter character = mock(RPCharacter.class);
        when(character.getName()).thenReturn("Shade");
        when(data.getCharacterById("c-spy")).thenReturn(character);
        players.when(() -> PlayerManager.get(holder.playerId)).thenReturn(data);
        assertEquals("Shade", PlotFactions.spymaster("Ana").character().name);
        when(data.getCharacterById("c-spy")).thenReturn(null);
        assertEquals("Spy", PlotFactions.spymaster("Ana").character().name);
    }

    @Test
    void factionMembershipAndRostersComeFromSimpleFactions() {
        var factions = boundary(mockStatic(FactionManager.class));
        factions.when(() -> FactionManager.getByMember("Ana")).thenReturn(faction);
        factions.when(() -> FactionManager.getByMember("Bo")).thenReturn(faction);
        factions.when(() -> FactionManager.getByMember("Cy")).thenReturn(mock(Faction.class));
        assertTrue(PlotFactions.sameFaction("Ana", "Bo"));
        assertFalse(PlotFactions.sameFaction("Ana", "Cy"));
        assertFalse(PlotFactions.sameFaction("Nobody", "Ana"));
        assertEquals(List.of(), PlotFactions.members("Nobody"));
        when(faction.getMembers()).thenReturn(new ArrayList<>(List.of("Ana", "Bo")));
        when(faction.getLeader()).thenReturn("King");
        assertEquals(List.of("Ana", "Bo", "King"), PlotFactions.members("Ana"));
        when(faction.getLeader()).thenReturn("ana");
        assertEquals(List.of("Ana", "Bo"), PlotFactions.members("Ana"));
        when(faction.getLeader()).thenReturn(null);
        assertEquals(List.of("Ana", "Bo"), PlotFactions.members("Ana"));
    }

    @Test
    void warbandsAndTheFormulaComeFromSimpleFactions() {
        var warbands = boundary(mockStatic(WarbandManager.class));
        var math = boundary(mockStatic(EspionageMath.class));
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        assertFalse(PlotFactions.inWarband(player));
        warbands.when(() -> WarbandManager.getByMemberId(id)).thenReturn(mock(Warband.class));
        assertTrue(PlotFactions.inWarband(player));
        math.when(() -> EspionageMath.aptitude(any(), any())).thenReturn(77);
        assertEquals(77, PlotFactions.intrigueRoll(Map.of(), mock(RandomGenerator.class)));
    }

    @Test
    void landsAreTheProvinceOwnerWhereProvincesAreOn() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("TFMC_Map");
        Location at = new Location(world, 10, 64, 20);
        SimpleFactions.plugin = null;
        assertNull(PlotFactions.lands(at));
        SimpleFactions plugin = mock(SimpleFactions.class);
        SimpleFactions.plugin = plugin;
        net.tfminecraft.simplefactions.Cache.provincesEnabled = false;
        assertNull(PlotFactions.lands(at));
        net.tfminecraft.simplefactions.Cache.provincesEnabled = true;
        assertNull(PlotFactions.lands(new Location(null, 0, 0, 0)));
        net.tfminecraft.simplefactions.Cache.worldName = "Elsewhere";
        assertNull(PlotFactions.lands(at));
        net.tfminecraft.simplefactions.Cache.worldName = "TFMC_Map";
        assertNull(PlotFactions.lands(at));
        ProvinceGrid grid = mock(ProvinceGrid.class);
        ProvinceManager manager = mock(ProvinceManager.class);
        when(plugin.getProvinceGrid()).thenReturn(grid);
        assertNull(PlotFactions.lands(at));
        when(plugin.getProvinceManager()).thenReturn(manager);
        assertNull(PlotFactions.lands(at));
        when(grid.getAt(10, 20)).thenReturn(7);
        assertNull(PlotFactions.lands(at));
        when(manager.contains(7)).thenReturn(true);
        Province province = mock(Province.class);
        when(manager.get(7)).thenReturn(province);
        assertNull(PlotFactions.lands(at));
        when(faction.getName()).thenReturn("Thalendor");
        when(province.getOwner()).thenReturn(faction);
        assertEquals("Thalendor", PlotFactions.lands(at));
    }
}
