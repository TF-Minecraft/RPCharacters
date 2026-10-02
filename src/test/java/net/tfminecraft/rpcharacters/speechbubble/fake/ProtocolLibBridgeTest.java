package net.tfminecraft.rpcharacters.speechbubble.fake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.logging.Logger;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class ProtocolLibBridgeTest {
    RuntimeTestState state; Plugin plugin, protocolPlugin; PluginManager plugins;
    ProtocolManager protocol; Logger logger; FakeBubbleManager bubbles;
    MockedStatic<Bukkit> bukkit; MockedStatic<ProtocolLibrary> library; MockedStatic<FakeBubbleManager> manager;

    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(ProtocolLibBridge.class);
        plugin = mock(Plugin.class); protocolPlugin = mock(Plugin.class); plugins = mock(PluginManager.class);
        logger = mock(Logger.class); when(plugin.getLogger()).thenReturn(logger);
        when(plugins.getPlugin("ProtocolLib")).thenReturn(protocolPlugin); when(protocolPlugin.isEnabled()).thenReturn(true);
        when(plugins.isPluginEnabled("ProtocolLib")).thenAnswer(call -> plugins.getPlugin("ProtocolLib") != null && protocolPlugin.isEnabled());
        protocol = mock(ProtocolManager.class); bubbles = mock(FakeBubbleManager.class);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        library = mockStatic(ProtocolLibrary.class); library.when(ProtocolLibrary::getProtocolManager).thenReturn(protocol);
        manager = mockStatic(FakeBubbleManager.class); manager.when(FakeBubbleManager::get).thenReturn(bubbles);
        ProtocolLibBridge.shutdown(); clearInvocations(bubbles);
    }

    @AfterEach void cleanup() {
        ProtocolLibBridge.shutdown(); manager.close(); library.close(); bukkit.close(); state.close(); MockBukkit.unmock();
    }

    @Test void availableProtocolCreatesPacketsAndShutdownClearsOnlyOnce() {
        assertFalse(ProtocolLibBridge.isReady()); assertNull(ProtocolLibBridge.getPackets());
        ProtocolLibBridge.init(plugin); assertTrue(ProtocolLibBridge.isReady()); assertNotNull(ProtocolLibBridge.getPackets());
        verify(logger).info(contains("enabled")); ProtocolLibBridge.shutdown();
        assertFalse(ProtocolLibBridge.isReady()); assertNull(ProtocolLibBridge.getPackets()); verify(bubbles).shutdown();
        ProtocolLibBridge.shutdown(); verify(bubbles, times(1)).shutdown();
    }

    @Test void missingPluginAndFailedInitializationClearPreviousReadiness() {
        ProtocolLibBridge.init(plugin); assertTrue(ProtocolLibBridge.isReady());
        when(plugins.getPlugin("ProtocolLib")).thenReturn(null); ProtocolLibBridge.init(plugin);
        assertFalse(ProtocolLibBridge.isReady()); assertNull(ProtocolLibBridge.getPackets());
        verify(logger).warning(contains("not found"));
        when(plugins.getPlugin("ProtocolLib")).thenReturn(protocolPlugin);
        library.when(ProtocolLibrary::getProtocolManager).thenThrow(new IllegalStateException("Manager failed"));
        ProtocolLibBridge.init(plugin); assertFalse(ProtocolLibBridge.isReady()); assertNull(ProtocolLibBridge.getPackets());
        verify(logger).warning(contains("Manager failed"));
    }

    @Test void unavailableManagerCannotAdvertiseAReadyPacketBridge() {
        library.when(ProtocolLibrary::getProtocolManager).thenReturn(null);
        ProtocolLibBridge.init(plugin); assertFalse(ProtocolLibBridge.isReady()); assertNull(ProtocolLibBridge.getPackets());
    }

    @Test void disabledProtocolPluginCannotAdvertiseAReadyPacketBridge() {
        when(protocolPlugin.isEnabled()).thenReturn(false);
        ProtocolLibBridge.init(plugin); assertFalse(ProtocolLibBridge.isReady()); assertNull(ProtocolLibBridge.getPackets());
        library.verifyNoInteractions();
    }
}
