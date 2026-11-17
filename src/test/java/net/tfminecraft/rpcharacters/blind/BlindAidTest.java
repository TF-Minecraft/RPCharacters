package net.tfminecraft.rpcharacters.blind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;

import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;

class BlindAidTest {
    RuntimeTestState state; Plugin plugin, protocolPlugin; PluginManager plugins; Logger logger;
    MockedStatic<Bukkit> bukkit; MockedStatic<ProtocolLibrary> library;
    @TempDir Path dir;

    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(BlindAid.class);
        plugin = MockBukkit.createMockPlugin(); logger = mock(Logger.class);
        plugin = spy(plugin); when(plugin.getLogger()).thenReturn(logger);
        protocolPlugin = mock(Plugin.class); plugins = mock(PluginManager.class);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        library = mockStatic(ProtocolLibrary.class); library.when(ProtocolLibrary::getProtocolManager).thenReturn(mock(ProtocolManager.class));
    }

    @AfterEach void cleanup() {
        BlindAid.shutdown(); library.close(); bukkit.close(); state.close(); MockBukkit.unmock();
    }

    @Test void loadsSettingsFromTheFile() throws Exception {
        assertTrue(BlindAid.settings().enabled());
        Path file = Files.writeString(dir.resolve("blind.yml"), "enabled: false\ntraits: [Sightless]\n");
        BlindAid.load(file.toFile());
        assertFalse(BlindAid.settings().enabled());
        assertEquals(List.of("sightless"), BlindAid.settings().traits());
    }

    @Test void createRegistersTheListenerWithOrWithoutProtocolLib() {
        BlindAid.start(); BlindAid.shutdown();                      // nothing built yet: no-ops
        when(plugins.getPlugin("ProtocolLib")).thenReturn(protocolPlugin); when(protocolPlugin.isEnabled()).thenReturn(true);
        BlindAidService withGlow = BlindAid.create(plugin);
        verify(plugins).registerEvents(withGlow, plugin);
        verify(logger, never()).warning(anyString());
        when(protocolPlugin.isEnabled()).thenReturn(false);
        BlindAid.create(plugin);
        when(plugins.getPlugin("ProtocolLib")).thenReturn(null);
        BlindAid.create(plugin);
        verify(logger, times(2)).warning(contains("ProtocolLib not found"));
        BlindAid.start(); BlindAid.shutdown();
    }

    @Test void blindMeansAnActiveCharacterWithAConfiguredTrait() {
        Player p = mock(Player.class);
        try (MockedStatic<PlayerManager> players = mockStatic(PlayerManager.class)) {
            assertFalse(BlindAid.isBlind(p), "no player data");
            PlayerData pd = mock(PlayerData.class); players.when(() -> PlayerManager.get(p)).thenReturn(pd);
            assertFalse(BlindAid.isBlind(p), "no active character");
            RPCharacter character = mock(RPCharacter.class); when(pd.hasActiveCharacter()).thenReturn(true);
            when(pd.getActiveCharacter()).thenReturn(character);
            Trait brave = mock(Trait.class); when(brave.getId()).thenReturn("brave");
            Trait blind = mock(Trait.class); when(blind.getId()).thenReturn("Blind");
            when(character.getTraits()).thenReturn(List.of(brave));
            assertFalse(BlindAid.isBlind(p));
            when(character.getTraits()).thenReturn(List.of(brave, blind));
            assertTrue(BlindAid.isBlind(p));
        }
    }

    @Test void caneMeansAnyConfiguredItemPath() {
        ItemStack stick = new ItemStack(Material.STICK);
        ItemAPI api = mock(ItemAPI.class); ItemChecker checker = mock(ItemChecker.class); when(api.getChecker()).thenReturn(checker);
        when(checker.checkItemWithPath(stick, "m.props.forearm_crutch")).thenReturn(true);
        try (MockedStatic<TLibs> tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertTrue(BlindAid.isCane(stick, List.of("m.props.walking_cane", "m.props.forearm_crutch")));
            assertFalse(BlindAid.isCane(stick, List.of("m.props.walking_cane")));
        }
    }
}
